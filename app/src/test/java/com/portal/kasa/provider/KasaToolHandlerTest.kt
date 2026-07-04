package com.portal.kasa.provider

import com.portal.kasa.data.KasaRepository
import com.portal.kasa.data.Plug
import com.portal.kasa.data.PlugDiskCache
import com.portal.kasa.net.KasaClient
import com.portal.kasa.net.KasaLocal.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure JVM tests for the voice tool dispatch (list_plugs / set_plug) over a fake LAN client. */
@OptIn(ExperimentalCoroutinesApi::class)
class KasaToolHandlerTest {

    private class FakeClient(
        var devices: List<Device>,
        private val relayOk: (ip: String, on: Boolean) -> Boolean = { _, _ -> true },
    ) : KasaClient {
        val sends = mutableListOf<Pair<String, Boolean>>()
        override fun discover(): List<Device> = devices
        override fun refreshKnown(ips: List<String>): List<Device> = devices.filter { it.ip in ips }
        override fun setRelay(ip: String, on: Boolean, isBulb: Boolean): Boolean {
            sends.add(ip to on)
            return relayOk(ip, on)
        }
    }

    private fun handler(client: FakeClient): KasaToolHandler {
        val d = UnconfinedTestDispatcher()
        return KasaToolHandler(KasaRepository(client, io = d, relayDispatcher = d, scope = CoroutineScope(d)))
    }

    @Test fun listPlugsReturnsAliasSortedNamesAndStates() = runTest {
        val h = handler(FakeClient(listOf(Device("1", "Lamp", on = true), Device("2", "Fan", on = false))))

        val plugs = JSONObject(h.invoke(KasaToolHandler.TOOL_LIST_PLUGS, null)).getJSONArray("plugs")

        assertEquals(2, plugs.length())
        assertEquals("Fan", plugs.getJSONObject(0).getString("name")) // alias-sorted
        assertFalse(plugs.getJSONObject(0).getBoolean("on"))
        assertEquals("Lamp", plugs.getJSONObject(1).getString("name"))
        assertTrue(plugs.getJSONObject(1).getBoolean("on"))
    }

    @Test fun listPlugsOnEmptyNetworkReturnsError() = runTest {
        val h = handler(FakeClient(emptyList()))
        assertTrue(JSONObject(h.invoke(KasaToolHandler.TOOL_LIST_PLUGS, null)).getString("error").contains("no plugs"))
    }

    @Test fun setPlugRequiresNameAndOnFlag() = runTest {
        val h = handler(FakeClient(listOf(Device("1", "Lamp", on = false))))
        assertEquals("plug_name required", JSONObject(h.invoke(KasaToolHandler.TOOL_SET_PLUG, "{}")).getString("error"))
        assertEquals(
            "on (true/false) required",
            JSONObject(h.invoke(KasaToolHandler.TOOL_SET_PLUG, """{"plug_name":"lamp"}""")).getString("error"),
        )
    }

    @Test fun setPlugTurnsOnByNameAndAcks() = runTest {
        val client = FakeClient(listOf(Device("1", "Coffee Maker", on = false)))
        val h = handler(client)

        val r = JSONObject(h.invoke(KasaToolHandler.TOOL_SET_PLUG, """{"plug_name":"coffee maker","on":true}"""))

        assertTrue(r.getBoolean("ok"))
        assertEquals("Coffee Maker", r.getString("plug"))
        assertTrue(r.getBoolean("on"))
        assertEquals(listOf("1" to true), client.sends)
    }

    @Test fun setPlugUnknownNameReturnsErrorWithCandidates() = runTest {
        val h = handler(FakeClient(listOf(Device("1", "Coffee Maker", on = false))))
        val r = JSONObject(h.invoke(KasaToolHandler.TOOL_SET_PLUG, """{"plug_name":"zzzzz","on":true}"""))
        assertTrue(r.getString("error").contains("no plug called"))
        assertTrue(r.has("candidates"))
    }

    @Test fun setPlugReportsUnreachableWhenRelayFails() = runTest {
        val client = FakeClient(listOf(Device("1", "Coffee Maker", on = false)), relayOk = { _, _ -> false })
        val h = handler(client)
        val r = JSONObject(h.invoke(KasaToolHandler.TOOL_SET_PLUG, """{"plug_name":"coffee","on":true}"""))
        assertTrue(r.getString("error").contains("couldn't reach"))
    }

    @Test fun unknownToolReturnsError() = runTest {
        val h = handler(FakeClient(emptyList()))
        assertTrue(JSONObject(h.invoke("com.portal.kasa.bogus", null)).getString("error").contains("unknown tool"))
    }

    @get:Rule val tmp = TemporaryFolder()

    @Test fun setPlugRediscoversAndRetriesWhenTheSnapshotIpIsStale() = runTest {
        // Cold voice turn seeded from a stale disk snapshot: DHCP moved the plug from .9 to .1 since the last
        // sweep. The first send fails; the handler must rediscover, re-match by name, and retry the fresh ip.
        val cache = PlugDiskCache(File(tmp.root, "plugs.json"))
        cache.save(listOf(Plug("10.0.0.9", "Coffee Maker", on = false)))
        val client = FakeClient(
            listOf(Device("10.0.0.1", "Coffee Maker", on = false)), // where discovery actually finds it now
            relayOk = { ip, _ -> ip == "10.0.0.1" }, // the old ip is unreachable
        )
        val d = UnconfinedTestDispatcher()
        val repo = KasaRepository(client, io = d, relayDispatcher = d, diskCache = cache, scope = CoroutineScope(d))
        val h = KasaToolHandler(repo)

        val r = JSONObject(h.invoke(KasaToolHandler.TOOL_SET_PLUG, """{"plug_name":"coffee maker","on":true}"""))

        assertTrue(r.getBoolean("ok")) // the voice turn still succeeds
        assertEquals(listOf("10.0.0.9" to true, "10.0.0.1" to true), client.sends) // stale attempt, then retry
    }

    @Test fun setPlugDoesNotRetryWhenRediscoveryFindsTheSameIp() = runTest {
        // Genuinely unreachable plug (same ip after a rediscover): resending would just burn the turn budget.
        val cache = PlugDiskCache(File(tmp.root, "plugs.json"))
        cache.save(listOf(Plug("10.0.0.9", "Coffee Maker", on = false)))
        val client = FakeClient(
            listOf(Device("10.0.0.9", "Coffee Maker", on = false)),
            relayOk = { _, _ -> false },
        )
        val d = UnconfinedTestDispatcher()
        val repo = KasaRepository(client, io = d, relayDispatcher = d, diskCache = cache, scope = CoroutineScope(d))
        val h = KasaToolHandler(repo)

        val r = JSONObject(h.invoke(KasaToolHandler.TOOL_SET_PLUG, """{"plug_name":"coffee maker","on":true}"""))

        assertTrue(r.getString("error").contains("couldn't reach"))
        assertEquals(listOf("10.0.0.9" to true), client.sends) // exactly one attempt, no futile resend
    }

    @Test fun setPlugDoesNotRetryAcrossDuplicateAliases() = runTest {
        // Two physical plugs share an alias; the exact-tie auto-pick chose the dead one. The retry's
        // same-alias-different-ip lookup would hit the OTHER device — it must not run when the alias
        // wasn't unique to begin with (silently actuating the wrong plug is worse than an honest error).
        val client = FakeClient(
            listOf(Device("1", "Lamp", on = false), Device("2", "Lamp", on = false)),
            relayOk = { ip, _ -> ip == "2" }, // the picked one is dead; the other would "succeed"
        )
        val h = handler(client)

        val r = JSONObject(h.invoke(KasaToolHandler.TOOL_SET_PLUG, """{"plug_name":"lamp","on":true}"""))

        assertTrue(r.getString("error").contains("couldn't reach"))
        assertEquals(1, client.sends.size) // no second send to the other same-named device
        assertEquals("1", client.sends.single().first)
    }
}
