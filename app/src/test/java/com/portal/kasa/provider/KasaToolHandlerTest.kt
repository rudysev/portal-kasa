package com.portal.kasa.provider

import com.portal.kasa.data.KasaRepository
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
import org.junit.Test

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
}
