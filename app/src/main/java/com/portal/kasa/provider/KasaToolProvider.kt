package com.portal.kasa.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import com.portal.commons.DebugLog
import com.portal.kasa.Graph
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The assistant's voice entry point (ToolContract): an exported [ContentProvider] the assistant discovers and
 * calls to control plugs by name. A thin shell over [KasaToolHandler], which runs the tools against the single
 * `KasaRepository` shared with the UI (resolved from [Graph][com.portal.kasa.Graph]) — so a warm app resolves
 * instantly; a cold start does one LAN discovery to populate the cache.
 *
 * `call()` runs on a Binder thread; the handler is offloaded to a worker bounded by [INVOKE_TIMEOUT_MS] (under
 * the assistant's ~5 s budget) so a slow/hung LAN can't wedge the voice turn — it returns a speakable error
 * instead.
 */
class KasaToolProvider : ContentProvider() {
    private lateinit var appContext: Context
    private lateinit var handler: KasaToolHandler
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kasa-tool") }

    override fun onCreate(): Boolean {
        appContext = requireNotNull(context).applicationContext
        handler = KasaToolHandler(Graph.repository(appContext))
        if (DebugLog.file == null) DebugLog.file = File(appContext.getExternalFilesDir(null), "debug.txt")
        return true
    }

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?,
    ): Bundle? {
        if (method != METHOD_INVOKE) return null
        val argsJson = extras?.getString(EXTRA_ARGS_JSON)
        val result = invokeBounded(arg.orEmpty(), argsJson)
        return Bundle().apply { putString(EXTRA_RESULT_JSON, result) }
    }

    private fun invokeBounded(
        tool: String,
        argsJson: String?,
    ): String {
        val future = worker.submit<String> { runBlocking { handler.invoke(tool, argsJson) } }
        return runCatching { future.get(INVOKE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrElse {
            future.cancel(true)
            DebugLog.log("kasa tool $tool timed out/failed: ${it.message}")
            err("the smart plugs didn't respond in time")
        }
    }

    private fun err(message: String): String = JSONObject().put("error", message).toString()

    // Unused CRUD surface — this provider only services call().
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    private companion object {
        const val INVOKE_TIMEOUT_MS = 4_000L

        // Portal assistant tool contract — frozen wire strings (mirror of portal-assistant's ToolContract,
        // VERSION 1). A provider needs only these literals, not a dependency on the assistant: the strings
        // are the contract. The two meta-data key names live in AndroidManifest.xml as literals too.
        const val METHOD_INVOKE = "invoke"
        const val EXTRA_ARGS_JSON = "com.portal.assistant.tools.extra.ARGS"
        const val EXTRA_RESULT_JSON = "com.portal.assistant.tools.extra.RESULT"
    }
}
