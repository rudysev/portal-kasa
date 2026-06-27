package com.portal.kasa.provider

import com.portal.commons.DebugLog
import com.portal.kasa.data.KasaRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tool-dispatch core behind [KasaToolProvider] (ToolContract): resolve a tool name + JSON args against the
 * shared [repository] and return the JSON string the assistant speaks back. Split out of the ContentProvider
 * so it's unit-testable on a plain JVM — it depends only on the injectable [repository], [PlugMatch], and
 * org.json, with no Android `Context`/`Bundle`. The provider is a thin shell that offloads [invoke] to a worker
 * bounded by a timeout.
 */
class KasaToolHandler(private val repository: KasaRepository) {

    /** Run [tool] with [argsJson] (a JSON object string, or null); returns the JSON result string. */
    suspend fun invoke(tool: String, argsJson: String?): String {
        val args = runCatching { JSONObject(argsJson ?: "{}") }.getOrDefault(JSONObject())
        return when (tool) {
            TOOL_LIST_PLUGS -> listPlugs()
            TOOL_SET_PLUG -> setPlug(args)
            else -> err("unknown tool: $tool")
        }
    }

    private suspend fun listPlugs(): String {
        if (repository.snapshot().isEmpty()) repository.refresh()
        val arr = JSONArray()
        repository.snapshot().forEach { arr.put(JSONObject().put("name", it.alias).put("on", it.on)) }
        if (arr.length() == 0) return err("no plugs found on the network — check they're on the same Wi-Fi")
        DebugLog.log("kasa list_plugs → ${arr.length()}")
        return JSONObject().put("plugs", arr).toString()
    }

    private suspend fun setPlug(args: JSONObject): String {
        val name = args.optString("plug_name").trim()
        if (name.isBlank()) return err("plug_name required")
        if (!args.has("on")) return err("on (true/false) required")
        val on = args.optBoolean("on")

        if (!repository.ensureLoaded()) return err("no plugs found on the network")
        val candidates = repository.snapshot().map { PlugMatch.Candidate(it.ip, it.alias) }
        val match = PlugMatch.best(name, candidates)
        val pick =
            match.pick ?: return JSONObject()
                .put("error", "no plug called \"$name\"")
                .put("candidates", JSONArray(match.candidates))
                .toString()
                .also { DebugLog.log("kasa set_plug no match for \"$name\" (candidates=${match.candidates})") }

        val ok = repository.setRelay(pick.id, on)
        DebugLog.log("kasa set_plug \"${pick.alias}\"=$on → $ok")
        return if (ok) {
            JSONObject()
                .put("ok", true)
                .put("plug", pick.alias)
                .put("on", on)
                .toString()
        } else {
            err("couldn't reach ${pick.alias}")
        }
    }

    private fun err(message: String): String = JSONObject().put("error", message).toString()

    companion object {
        const val TOOL_LIST_PLUGS = "com.portal.kasa.list_plugs"
        const val TOOL_SET_PLUG = "com.portal.kasa.set_plug"
    }
}
