package com.portal.kasa.net

import android.content.Context
import android.net.wifi.WifiManager
import com.portal.commons.DebugLog
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Local-LAN client for TP-Link **Kasa** smart plugs (the EP10) — same Wi-Fi only, **no cloud/account**
 * (TP-Link blocked third-party cloud access; this is the path that actually works). Plugs listen on port
 * **9999** speaking [KasaParse]-XOR JSON: we **discover**
 * by UDP-broadcasting `get_sysinfo` and reading each reply's `alias` + `relay_state`, then flip a plug over
 * **TCP** (TCP frames are length-prefixed with a 4-byte big-endian header; UDP is not). Synchronous — call
 * off the main thread.
 */
object KasaLocal {
    private const val PORT = 9999
    private const val DISCOVER_MS = 1500 // total listen window
    private const val RESEND_MS = 250 // re-broadcast cadence — UDP broadcast is lossy, so ask repeatedly
    private const val SWEEP_TICKS = 2 // ticks on which to also unicast-sweep (direct sends are reliable)
    private const val SOCKET_MS = 1200 // per-plug TCP connect/read timeout

    data class Device(
        val ip: String,
        val alias: String,
        val on: Boolean,
    )

    /**
     * Broadcast `get_sysinfo` and collect every Kasa plug that replies within [DISCOVER_MS]. The request is
     * **re-broadcast every [RESEND_MS]** across the window rather than once: UDP broadcast over Wi-Fi is
     * lossy, so a single ask routinely drops a random plug's reply. Repeating it makes a full sweep reliable.
     */
    fun discover(context: Context): List<Device> {
        val payload = KasaParse.encrypt(KasaParse.CMD_GET_SYSINFO)
        val found = LinkedHashMap<String, Device>() // keyed by ip → dedupe repeat replies
        // Some Wi-Fi chipsets filter inbound broadcast/multicast unless a MulticastLock is held — a common
        // cause of replies silently never arriving. Hold one for the sweep.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("kasa-discovery")?.apply {
            setReferenceCounted(false)
            runCatching { acquire() }
        }
        runCatching {
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 200 // short ticks so we can poll the deadline and re-send
                // The broadcast reaches every device that honours it and is re-sent each tick because broadcast
                // over Wi-Fi is lossy. The unicast sweep targets devices that **ignore the broadcast** (observed
                // on a "Globe" plug — pingable and replying to unicast, yet absent from every broadcast sweep);
                // those are direct, AP-buffered, reliable deliveries, so we send them only on the first couple
                // of ticks rather than blasting the whole subnet on every tick.
                val broadcast = broadcastAddrs(context)
                val sweep = sweepAddrs()
                // Generous enough for a smart bulb's larger `get_sysinfo` (light state + preset scenes); plugs
                // reply in <1 KB. Avoids ever truncating a datagram, which would fail the JSON parse.
                val buf = ByteArray(8 * 1024)
                val deadline = System.currentTimeMillis() + DISCOVER_MS
                var nextSend = 0L
                var tick = 0
                while (System.currentTimeMillis() < deadline) {
                    val nowMs = System.currentTimeMillis()
                    if (nowMs >= nextSend) {
                        val targets = if (tick < SWEEP_TICKS) broadcast + sweep else broadcast
                        for (addr in targets) {
                            runCatching { sock.send(DatagramPacket(payload, payload.size, addr, PORT)) }
                        }
                        tick++
                        nextSend = nowMs + RESEND_MS
                    }
                    val pkt = DatagramPacket(buf, buf.size)
                    if (!runCatching {
                            sock.receive(pkt)
                            true
                        }.getOrDefault(false)
                    ) {
                        continue
                    }
                    val ip = pkt.address?.hostAddress ?: continue
                    val sys = KasaParse.parseSysinfo(KasaParse.decrypt(pkt.data, pkt.length))
                    if (sys == null) {
                        // A reply we couldn't read — log the size so a truncated/oversized device shows up in
                        // diagnostics instead of just vanishing from the grid.
                        DebugLog.log("kasa discover: unparsed reply from $ip len=${pkt.length}")
                        continue
                    }
                    found[ip] = Device(ip, sys.alias, sys.on)
                }
            }
        }.onFailure { DebugLog.log("kasa discover failed: ${it.message}") }
        runCatching { lock?.release() }
        DebugLog.log("kasa discover → ${found.size}: ${found.values.joinToString { "${it.alias}@${it.ip}" }}")
        return found.values.toList()
    }

    /** Set a single plug's relay on/off over TCP. Returns true on an `err_code: 0` ack. */
    fun setRelay(
        ip: String,
        on: Boolean,
    ): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, PORT), SOCKET_MS)
            s.soTimeout = SOCKET_MS
            val body = KasaParse.encrypt(KasaParse.cmdSetRelay(on))
            DataOutputStream(s.getOutputStream()).run {
                writeInt(body.size) // 4-byte big-endian length header (TCP framing)
                write(body)
                flush()
            }
            val ins = DataInputStream(s.getInputStream())
            val resp = ByteArray(ins.readInt())
            ins.readFully(resp)
            KasaParse.parseSetAck(KasaParse.decrypt(resp, resp.size))
        }
    }.getOrElse {
        DebugLog.log("kasa setRelay $ip=$on failed: ${it.message}")
        false
    }

    /** The Wi-Fi subnet broadcast (e.g. 192.168.1.255) from DHCP, plus 255.255.255.255 as a fallback. */
    private fun broadcastAddrs(context: Context): List<InetAddress> {
        val addrs = mutableListOf<InetAddress>()
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            @Suppress("DEPRECATION")
            val dhcp = wifi.dhcpInfo
            if (dhcp != null && dhcp.ipAddress != 0) {
                val bc = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                // DhcpInfo ints are little-endian (least-significant byte first).
                addrs.add(
                    InetAddress.getByAddress(
                        byteArrayOf(
                            (bc and 0xff).toByte(),
                            (bc shr 8 and 0xff).toByte(),
                            (bc shr 16 and 0xff).toByte(),
                            (bc shr 24 and 0xff).toByte(),
                        ),
                    ),
                )
            }
        }
        runCatching { addrs.add(InetAddress.getByName("255.255.255.255")) }
        return addrs
    }

    /**
     * Unicast probe targets for devices that don't answer the broadcast: the LAN hosts already in the kernel
     * **ARP table** (`/proc/net/arp`). Some Kasa devices sit in Wi-Fi power-save and miss broadcast frames
     * (delivered only at DTIM intervals) while still answering buffered unicast — a "Globe" plug here was
     * pingable and replied to a direct query yet never showed up in any broadcast sweep. Probing the ARP table
     * reaches exactly those real, already-resolved neighbours, so it's reliable without a /24 blast (which
     * floods the radio with ARP for hundreds of non-existent hosts and drops the genuine replies).
     */
    private fun sweepAddrs(): List<InetAddress> {
        val out = mutableListOf<InetAddress>()
        runCatching {
            java.io.File("/proc/net/arp").useLines { lines ->
                lines.drop(1).forEach { line -> // first line is the column header
                    val cols = line.trim().split(Regex("\\s+"))
                    // cols: ip, hwType, flags, hwAddr, mask, device. Test the ATF_COM (0x2) *bit* so both a
                    // dynamic entry (0x2) and a complete-but-permanent one (0x6) count as resolved.
                    val flags = if (cols.size >= 3) cols[2].removePrefix("0x").toIntOrNull(16) ?: 0 else 0
                    if (cols.size >= 4 && (flags and 0x2) != 0 && cols[3] != "00:00:00:00:00:00") {
                        runCatching { out.add(InetAddress.getByName(cols[0])) }
                    }
                }
            }
        }
        return out
    }
}
