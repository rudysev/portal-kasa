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
        // ensureLoaded joins the disk seed and runs the confirm sweep — a bare isEmpty→refresh would race it.
        repository.ensureLoaded()
        val arr = JSONArray()
        repository.snapshot().forEach { arr.put(JSONObject().put("name", it.alias).put("on", it.on)) }
        if (arr.length() == 0) return err("no plugs found on the network — check they're on the same Wi-Fi")
        DebugLog.log("kasa list_plugs → ${arr.length()}")
        return JSONObject().put("plugs", arr).toString()
    }

    private suspend fun setPlug(args: JSONObject): String {
        val started = System.nanoTime()
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

        var ok = repository.setRelay(pick.id, on)
        if (!ok && canRetry(started, pick.alias, candidates)) {
            // Stale snapshot (DHCP moved the plug): rediscover, then retry the same alias at its ONE new ip.
            // Exact-alias, not a fuzzy re-match — the merge keeps the old-ip card for a few sweeps, and a
            // fuzzy match would call that duplicate ambiguous. Same ip after the sweep → genuinely
            // unreachable, no resend.
            repository.refresh()
            val moved = repository.snapshot().filter { it.alias == pick.alias && it.ip != pick.id }
            if (moved.size == 1) {
                DebugLog.log("kasa set_plug \"${pick.alias}\" moved ${pick.id} → ${moved.single().ip}, retrying")
                ok = repository.setRelay(moved.single().ip, on)
            }
        }
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

    /**
     * Whether the rediscover-and-retry is safe to run after a failed send:
     * - **Budget**: the retry costs a ~1.5 s sweep plus a send; past [RETRY_BUDGET_MS] elapsed it would blow
     *   the provider's 4 s invoke cap — and a send racing that timeout can't be cancelled (blocking socket),
     *   so it could flip the plug *after* the assistant already spoke failure. Better an honest error.
     * - **Ambiguity**: with two plugs sharing [alias], PlugMatch auto-picks one of them on the exact-name tie;
     *   retrying "the same alias at another ip" would then actuate the *other physical device* and call it
     *   success. If the alias wasn't unique before the send, don't retry.
     */
    private fun canRetry(startedNanos: Long, alias: String, candidates: List<PlugMatch.Candidate>): Boolean {
        val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
        if (elapsedMs >= RETRY_BUDGET_MS) {
            DebugLog.log("kasa set_plug \"$alias\" retry skipped: ${elapsedMs}ms elapsed (budget $RETRY_BUDGET_MS)")
            return false
        }
        if (candidates.count { it.alias == alias } != 1) {
            DebugLog.log("kasa set_plug \"$alias\" retry skipped: duplicate alias")
            return false
        }
        return true
    }

    private fun err(message: String): String = JSONObject().put("error", message).toString()

    companion object {
        const val TOOL_LIST_PLUGS = "com.portal.kasa.list_plugs"
        const val TOOL_SET_PLUG = "com.portal.kasa.set_plug"

        /** Max elapsed ms after which the retry (a ~1.5 s sweep + send) no longer fits the provider's 4 s cap. */
        private const val RETRY_BUDGET_MS = 2_000L
    }
}
