package com.portal.kasa.net

import org.json.JSONObject

/**
 * Pure, Android-free, unit-tested core of the **local-LAN** Kasa protocol — the cipher + JSON shapes the
 * plugs speak on port 9999. The socket I/O (discovery broadcast, TCP send) lives in [KasaLocal]; this is the
 * stuff worth testing without a plug: the autokey-XOR round-trip and the `get_sysinfo` / `set_relay_state`
 * parsing.
 */
object KasaParse {
    /** TP-Link **autokey-XOR**: cipher byte = running key XOR plaintext, and the key becomes that cipher byte
     *  (seed `0xAB`). This is what the plug expects; [decrypt] is the inverse. */
    fun encrypt(s: String): ByteArray {
        var key = 0xAB
        val bytes = s.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            key = key xor (bytes[i].toInt() and 0xff)
            out[i] = key.toByte()
        }
        return out
    }

    fun decrypt(
        data: ByteArray,
        len: Int,
    ): String {
        var key = 0xAB
        val out = ByteArray(len)
        for (i in 0 until len) {
            val c = data[i].toInt() and 0xff
            out[i] = (key xor c).toByte()
            key = c
        }
        return String(out, Charsets.UTF_8)
    }

    /** A plug's name + relay state from a decrypted `get_sysinfo` reply. */
    data class SysInfo(
        val alias: String,
        val on: Boolean,
    )

    fun parseSysinfo(json: String): SysInfo? = runCatching {
        val sys = JSONObject(json).optJSONObject("system")?.optJSONObject("get_sysinfo") ?: return@runCatching null
        val alias = sys.optString("alias")
        if (alias.isBlank()) null else SysInfo(alias, isOn(sys))
    }.getOrNull()

    /**
     * On/off across device families. Plugs (EP10/HS) report a top-level `relay_state` (0/1); smart **bulbs**
     * (KL/globe) have no relay and instead carry the on/off in `light_state.on_off`. Check the relay first,
     * then fall back to the light state, so a discovered bulb shows the right state instead of always "off".
     */
    private fun isOn(sys: JSONObject): Boolean = when {
        sys.has("relay_state") -> sys.optInt("relay_state", 0) == 1
        else -> sys.optJSONObject("light_state")?.optInt("on_off", 0) == 1
    }

    /** True iff a decrypted `set_relay_state` reply acked with `err_code == 0`. */
    fun parseSetAck(json: String): Boolean = runCatching {
        JSONObject(json).optJSONObject("system")?.optJSONObject("set_relay_state")?.optInt("err_code", -1) == 0
    }.getOrDefault(false)

    const val CMD_GET_SYSINFO = """{"system":{"get_sysinfo":{}}}"""

    fun cmdSetRelay(on: Boolean): String = """{"system":{"set_relay_state":{"state":${if (on) 1 else 0}}}}"""
}
