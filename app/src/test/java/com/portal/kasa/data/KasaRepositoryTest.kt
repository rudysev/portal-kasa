package com.portal.kasa.data

import com.portal.kasa.net.KasaClient
import com.portal.kasa.net.KasaLocal.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the discovery/send pipeline — **previously impossible** while [KasaRepository] was a
 * process-`object` with a hardcoded `Dispatchers.IO`. With the client and dispatcher injected, a fake LAN seam
 * and a test dispatcher drive the optimistic-flip / send / revert logic deterministically, no device required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KasaRepositoryTest {

    /** In-memory [KasaClient]: returns a fixed plug list and records every relay send. */
    private class FakeClient(
        private val devices: List<Device>,
        private val relayOk: (ip: String, on: Boolean) -> Boolean = { _, _ -> true },
    ) : KasaClient {
        val sends = mutableListOf<Pair<String, Boolean>>()
        override fun discover(): List<Device> = devices
        override fun setRelay(ip: String, on: Boolean): Boolean {
            sends.add(ip to on)
            return relayOk(ip, on)
        }
    }

    private fun repo(client: FakeClient): KasaRepository {
        val dispatcher = UnconfinedTestDispatcher()
        return KasaRepository(client, io = dispatcher, relayDispatcher = dispatcher, scope = CoroutineScope(dispatcher))
    }

    @Test fun refreshPopulatesCacheFromDiscovery() = runTest {
        val client = FakeClient(listOf(Device("1", "Lamp", on = false)))
        val repo = repo(client)

        assertTrue(repo.refresh())
        assertEquals(listOf("Lamp"), repo.snapshot().map { it.alias })
    }

    @Test fun setRelayFlipsCacheAndSendsOneCommand() = runTest {
        val client = FakeClient(listOf(Device("1", "Lamp", on = false)))
        val repo = repo(client)
        repo.refresh()

        val ok = repo.setRelay("1", on = true)

        assertTrue(ok)
        assertTrue(repo.snapshot().single().on)        // optimistic flip stuck (send acked)
        assertEquals(listOf("1" to true), client.sends) // exactly one command on the wire
    }

    @Test fun toggleFlipsCacheSynchronouslyBeforeTheSendRuns() = runTest {
        // StandardTestDispatcher does NOT auto-run launched coroutines, so the background send stays queued
        // until advanceUntilIdle — letting us prove the optimistic flip is synchronous, not pool-dependent.
        val dispatcher = StandardTestDispatcher(testScheduler)
        val client = FakeClient(listOf(Device("1", "Lamp", on = false)))
        val repo = KasaRepository(client, io = dispatcher, relayDispatcher = dispatcher, scope = CoroutineScope(dispatcher))
        repo.refresh()
        advanceUntilIdle()

        repo.toggle("1", on = true)

        assertTrue(repo.snapshot().single().on)                 // flipped on the calling thread, send not yet run
        assertTrue(client.sends.isEmpty())                       // background drain still queued

        advanceUntilIdle()
        assertEquals(listOf("1" to true), client.sends)          // send drains once the dispatcher advances
    }

    @Test fun failedSendRevertsTheOptimisticFlip() = runTest {
        val client = FakeClient(
            listOf(Device("1", "Lamp", on = false)),
            relayOk = { _, _ -> false }, // plug rejects / unreachable
        )
        val repo = repo(client)
        repo.refresh()

        val ok = repo.setRelay("1", on = true)

        assertFalse(ok)
        assertFalse(repo.snapshot().single().on) // reverted to last-known state
    }
}
