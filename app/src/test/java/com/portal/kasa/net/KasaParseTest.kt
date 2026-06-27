package com.portal.kasa.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the local-LAN Kasa protocol — the XOR cipher and the get_sysinfo / set_relay_state JSON. */
class KasaParseTest {
    @Test fun cipherRoundTrips() {
        for (s in listOf(KasaParse.CMD_GET_SYSINFO, KasaParse.cmdSetRelay(true), """{"a":"héllo 🔌","n":42}""")) {
            val enc = KasaParse.encrypt(s)
            assertEquals(s, KasaParse.decrypt(enc, enc.size))
        }
    }

    @Test fun encryptIsAutokeyXorWithSeed0xAB() {
        // First cipher byte = 0xAB XOR first plaintext byte; this is what the plug expects on the wire.
        val enc = KasaParse.encrypt("{")
        assertEquals((0xAB xor '{'.code).toByte(), enc[0])
    }

    @Test fun decryptHonorsLength() {
        val enc = KasaParse.encrypt(KasaParse.CMD_GET_SYSINFO)
        val padded = enc.copyOf(enc.size + 8) // trailing buffer slack, as from a DatagramPacket
        assertEquals(KasaParse.CMD_GET_SYSINFO, KasaParse.decrypt(padded, enc.size))
    }

    @Test fun parseSysinfoReadsAliasAndRelay() {
        val on = KasaParse.parseSysinfo("""{"system":{"get_sysinfo":{"alias":"Coffee Maker","relay_state":1}}}""")!!
        assertEquals("Coffee Maker", on.alias)
        assertTrue(on.on)
        val off = KasaParse.parseSysinfo("""{"system":{"get_sysinfo":{"alias":"Piano Lights","relay_state":0}}}""")!!
        assertEquals("Piano Lights", off.alias)
        assertFalse(off.on)
    }

    @Test fun parseSysinfoReadsBulbLightState() {
        // Smart bulbs have no relay_state; their on/off lives in light_state.on_off.
        val on = KasaParse.parseSysinfo("""{"system":{"get_sysinfo":{"alias":"Globe","light_state":{"on_off":1,"hue":0}}}}""")!!
        assertEquals("Globe", on.alias)
        assertTrue(on.on)
        val off = KasaParse.parseSysinfo("""{"system":{"get_sysinfo":{"alias":"Globe","light_state":{"on_off":0,"dft_on_state":{}}}}}""")!!
        assertFalse(off.on)
    }

    @Test fun parseSysinfoNullOnMissingAliasOrGarbage() {
        assertNull(KasaParse.parseSysinfo("""{"system":{"get_sysinfo":{"relay_state":1}}}"""))
        assertNull(KasaParse.parseSysinfo("""{"system":{}}"""))
        assertNull(KasaParse.parseSysinfo("not json"))
    }

    @Test fun parseSetAck() {
        assertTrue(KasaParse.parseSetAck("""{"system":{"set_relay_state":{"err_code":0}}}"""))
        assertFalse(KasaParse.parseSetAck("""{"system":{"set_relay_state":{"err_code":-1}}}"""))
        assertFalse(KasaParse.parseSetAck("garbage"))
    }

    @Test fun cmdSetRelayEncodesState() {
        assertTrue(KasaParse.cmdSetRelay(true).contains("\"state\":1"))
        assertTrue(KasaParse.cmdSetRelay(false).contains("\"state\":0"))
    }
}
