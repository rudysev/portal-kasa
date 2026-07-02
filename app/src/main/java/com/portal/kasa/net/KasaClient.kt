package com.portal.kasa.net

import android.content.Context

/**
 * The seam between the data layer and the Android-coupled LAN client. [KasaRepository][com.portal.kasa.data.KasaRepository]
 * depends on this interface rather than the [KasaLocal] object directly, so the repository's discovery/send
 * pipeline can be driven by a fake in a plain JVM unit test (no device, no `WifiManager`). The Context that
 * discovery needs is bound once here instead of being threaded through every repository call.
 */
interface KasaClient {
    /** Broadcast-discover plugs on the current Wi-Fi. Blocking — callers hop to an IO dispatcher. */
    fun discover(): List<KasaLocal.Device>

    /** Directed unicast `get_sysinfo` refresh of already-known plugs (no broadcast). Blocking. */
    fun refreshKnown(ips: List<String>): List<KasaLocal.Device>

    /** Flip a single device on/off over TCP; true on an `err_code: 0` ack. [isBulb] routes plug vs bulb. Blocking. */
    fun setRelay(ip: String, on: Boolean, isBulb: Boolean): Boolean
}

/** Production [KasaClient] backed by [KasaLocal], with the application Context bound at construction. */
class KasaLocalClient(private val appContext: Context) : KasaClient {
    override fun discover(): List<KasaLocal.Device> = KasaLocal.discover(appContext)

    override fun refreshKnown(ips: List<String>): List<KasaLocal.Device> = KasaLocal.refreshKnown(ips)

    override fun setRelay(ip: String, on: Boolean, isBulb: Boolean): Boolean = KasaLocal.setRelay(ip, on, isBulb)
}
