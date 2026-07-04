package com.portal.kasa.data

import com.portal.commons.DebugLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON snapshot of the last-known plugs under the app's private files dir, so a cold start (app open or voice
 * turn) renders/acts on real plugs immediately instead of waiting out the ~1.5 s broadcast-discovery window.
 *
 * The snapshot is a warm-start hint, not a source of truth: [KasaRepository] seeds from it only while its
 * in-memory cache is empty, and the first live sweep reconciles state and membership (on/off — and, rarely, an
 * ip after DHCP churn — may be stale until then; the same trade the repository already makes restoring
 * `lastGood`). Best-effort by design: a read/parse failure is an empty list, a write failure is dropped, and
 * writes go through a temp file + fsync + rename so neither a mid-write crash nor a power cut can leave a
 * truncated snapshot.
 */
class PlugDiskCache(private val file: File) {

    fun load(): List<Plug> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ip = o.optString("ip")
            val alias = o.optString("alias")
            // A record without an ip/alias can't be shown or targeted — skip it rather than fail the file.
            if (ip.isBlank() || alias.isBlank()) {
                null
            } else {
                Plug(ip, alias, o.optBoolean("on"), o.optBoolean("isBulb"))
            }
        }
    }.getOrElse {
        DebugLog.log("kasa disk cache load failed: ${it.message}")
        emptyList()
    }

    fun save(plugs: List<Plug>) {
        runCatching {
            val arr = JSONArray()
            plugs.forEach {
                arr.put(JSONObject().put("ip", it.ip).put("alias", it.alias).put("on", it.on).put("isBulb", it.isBulb))
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(arr.toString().toByteArray())
                // fsync before the rename: without it, a power cut can commit the rename ahead of the data
                // blocks (ext4/f2fs) and leave a truncated file — the rename alone only covers crashes.
                out.fd.sync()
            }
            // renameTo replaces the target atomically on POSIX; some filesystems refuse when it exists, so
            // fall back to delete-then-rename (worst case: no snapshot, never a truncated one).
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) DebugLog.log("kasa disk cache rename failed")
            }
        }.onFailure { DebugLog.log("kasa disk cache save failed: ${it.message}") }
    }
}
