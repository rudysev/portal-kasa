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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pure JVM tests for the discovery/send pipeline — **previously impossible** while [KasaRepository] was a
 * process-`object` with a hardcoded `Dispatchers.IO`. With the client and dispatcher injected, a fake LAN seam
 * and a test dispatcher drive the optimistic-flip / send / revert logic deterministically, no device required.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KasaRepositoryTest {

    /** In-memory [KasaClient]: returns a mutable plug list and records discovery / unicast-refresh / sends. */
    private class FakeClient(
        var devices: List<Device>,
        private val relayOk: (ip: String, on: Boolean) -> Boolean = { _, _ -> true },
    ) : KasaClient {
        val sends = mutableListOf<Pair<String, Boolean>>()
        val bulbFlags = mutableListOf<Boolean>() // isBulb passed to each setRelay — proves command routing
        val refreshKnownCalls = mutableListOf<List<String>>()
        var discoverCount = 0
        override fun discover(): List<Device> {
            discoverCount++
            return devices
        }

        // Directed unicast: only the queried ips that actually exist "answer" (simulating reachable plugs).
        override fun refreshKnown(ips: List<String>): List<Device> {
            refreshKnownCalls.add(ips)
            return devices.filter { it.ip in ips }
        }

        override fun setRelay(ip: String, on: Boolean, isBulb: Boolean): Boolean {
            sends.add(ip to on)
            bulbFlags.add(isBulb)
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

    @Test fun syncKnownRefreshesKnownPlugsByUnicastNotBroadcast() = runTest {
        val client = FakeClient(listOf(Device("1", "Lamp", on = false)))
        val repo = repo(client)
        repo.refresh() // one broadcast discover to learn the plug
        assertEquals(1, client.discoverCount)

        client.devices = listOf(Device("1", "Lamp", on = true)) // flipped elsewhere
        assertTrue(repo.syncKnown())

        assertTrue(repo.snapshot().single().on)                    // picked up via unicast refresh
        assertEquals(listOf(listOf("1")), client.refreshKnownCalls) // queried only the known ip
        assertEquals(1, client.discoverCount)                      // no extra broadcast
    }

    @Test fun syncKnownKeepsAPlugThatGoesSilentOnUnicast() = runTest {
        // Regression guard: the tighter unicast window must NOT evict a known plug — broadcast governs removal.
        val client = FakeClient(listOf(Device("1", "Lamp", on = true), Device("2", "Fan", on = false)))
        val repo = repo(client)
        repo.refresh() // learn both

        client.devices = listOf(Device("1", "Lamp", on = true)) // Fan stops answering unicast
        repeat(5) { repo.syncKnown() } // well past MAX_MISSES

        assertEquals(setOf("Fan", "Lamp"), repo.snapshot().map { it.alias }.toSet()) // Fan kept, not dropped
    }

    @Test fun broadcastRefreshStillDropsAPlugGoneForMaxMisses() = runTest {
        // Membership removal still works — it's just moved to the broadcast path.
        val client = FakeClient(listOf(Device("1", "Lamp", on = true), Device("2", "Fan", on = false)))
        val repo = repo(client)
        repo.refresh()

        client.devices = listOf(Device("1", "Lamp", on = true)) // Fan genuinely gone from the LAN
        repeat(3) { repo.refresh() } // MAX_MISSES = 3 broadcast misses

        assertEquals(listOf("Lamp"), repo.snapshot().map { it.alias })
    }

    @Test fun syncKnownFallsBackToBroadcastWhenNothingKnown() = runTest {
        val client = FakeClient(listOf(Device("1", "Lamp", on = false)))
        val repo = repo(client)

        assertTrue(repo.syncKnown()) // empty cache → must discover to find plugs

        assertEquals(listOf("Lamp"), repo.snapshot().map { it.alias })
        assertEquals(1, client.discoverCount)
        assertTrue(client.refreshKnownCalls.isEmpty())
    }

    @Test fun setRelayRoutesBulbFlagThroughToClient() = runTest {
        // A bulb toggle must carry isBulb=true so the client sends the lighting-service command, not set_relay_state.
        val client = FakeClient(listOf(Device("1", "Globe", on = false, isBulb = true)))
        val repo = repo(client)
        repo.refresh()

        assertTrue(repo.setRelay("1", on = true))

        assertEquals(listOf(true), client.bulbFlags) // repository looked up the plug's kind and passed it through
    }

    @Test fun drainRoutesBulbEvenAfterItWasDroppedFromTheCache() = runTest {
        // Device kind is stable, so a command must still route as a bulb even if the card was evicted mid-life —
        // re-deriving isBulb from the (evictable) cache would misroute it to set_relay_state.
        val client = FakeClient(listOf(Device("1", "Lamp", on = true), Device("2", "Globe", on = false, isBulb = true)))
        val repo = repo(client)
        repo.refresh() // learns both kinds

        client.devices = listOf(Device("1", "Lamp", on = true)) // Globe leaves the LAN
        repeat(3) { repo.refresh() } // MAX_MISSES broadcast misses → Globe dropped from the grid
        assertFalse(repo.snapshot().any { it.ip == "2" })

        repo.setRelay("2", on = true) // a still-in-flight toggle targeting the dropped bulb

        assertEquals(listOf(true), client.bulbFlags) // routed as a bulb, not fallen back to plug
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

    // --- warm-start disk snapshot ---

    @get:Rule val tmp = TemporaryFolder()

    private fun diskRepo(client: FakeClient, cache: PlugDiskCache): KasaRepository {
        val dispatcher = UnconfinedTestDispatcher() // unconfined → the seed job runs eagerly at construction
        return KasaRepository(client, io = dispatcher, relayDispatcher = dispatcher, diskCache = cache, scope = CoroutineScope(dispatcher))
    }

    private fun cache(): PlugDiskCache = PlugDiskCache(java.io.File(tmp.root, "plugs.json"))

    @Test fun seedsFromDiskSnapshotWithoutDiscovery() = runTest {
        val cache = cache().apply { save(listOf(Plug("1", "Lamp", on = true))) }
        val client = FakeClient(emptyList())

        val repo = diskRepo(client, cache)

        assertEquals(listOf("Lamp"), repo.snapshot().map { it.alias }) // real cards before any sweep
        assertTrue(repo.ensureLoaded()) // voice fast path: seeded cache satisfies it…
        assertEquals(0, client.discoverCount) // …with no broadcast discovery
        assertEquals(listOf(listOf("1")), client.refreshKnownCalls) // …just the cheap unicast confirm sweep
    }

    @Test fun ensureLoadedConfirmsSeededStateByUnicast() = runTest {
        // The seed alone can't be trusted (state ages, DHCP moves ips): the first ensureLoaded of a process
        // must run the directed confirm sweep so voice acts on current state, not the snapshot's.
        val cache = cache().apply { save(listOf(Plug("1", "Lamp", on = false))) }
        val client = FakeClient(listOf(Device("1", "Lamp", on = true))) // flipped since the snapshot

        val repo = diskRepo(client, cache)
        repo.ensureLoaded()

        assertTrue(repo.snapshot().single().on) // confirm sweep refreshed the seeded state
        assertEquals(0, client.discoverCount) // still no broadcast

        client.refreshKnownCalls.clear()
        repo.ensureLoaded()
        assertTrue(client.refreshKnownCalls.isEmpty()) // once live-confirmed, ensureLoaded is a no-op again
    }

    @Test fun liveSweepOverridesSeededState() = runTest {
        val cache = cache().apply { save(listOf(Plug("1", "Lamp", on = false))) }
        val client = FakeClient(listOf(Device("1", "Lamp", on = true))) // flipped since the snapshot

        val repo = diskRepo(client, cache)
        repo.refresh()

        assertTrue(repo.snapshot().single().on) // discovery is authoritative over the snapshot
    }

    @Test fun seededPlugGoneFromLanIsDroppedByBroadcastMisses() = runTest {
        val cache = cache().apply { save(listOf(Plug("1", "Lamp", on = true), Plug("9", "Moved", on = false))) }
        val client = FakeClient(listOf(Device("1", "Lamp", on = true))) // "Moved" no longer answers

        val repo = diskRepo(client, cache)
        repeat(3) { repo.refresh() } // MAX_MISSES broadcast misses

        assertEquals(listOf("Lamp"), repo.snapshot().map { it.alias }) // stale card ages out normally
    }

    @Test fun seededBulbKindRoutesAToggleBeforeAnySweep() = runTest {
        // A voice toggle right after a cold start must route from the seeded kind — there has been no
        // discovery yet, so kindByIp has only the snapshot to go on.
        val cache = cache().apply { save(listOf(Plug("1", "Globe", on = false, isBulb = true))) }
        val client = FakeClient(emptyList())

        val repo = diskRepo(client, cache)
        repo.setRelay("1", on = true)

        assertEquals(listOf(true), client.bulbFlags) // sent the lighting command, not set_relay_state
    }

    @Test fun refreshPersistsTheMergedListToDisk() = runTest {
        val cache = cache()
        val client = FakeClient(listOf(Device("1", "Lamp", on = true)))

        diskRepo(client, cache).refresh()

        assertEquals(listOf(Plug("1", "Lamp", on = true)), cache.load()) // next cold start seeds from this
    }

    @Test fun emptySweepDoesNotClobberTheSnapshot() = runTest {
        val cache = cache().apply { save(listOf(Plug("1", "Lamp", on = true))) }
        val client = FakeClient(emptyList()) // Wi-Fi blip: nothing answers

        diskRepo(client, cache).refresh()

        assertEquals(listOf(Plug("1", "Lamp", on = true)), cache.load()) // snapshot survives the blip
    }

    @Test fun persistSkipsRewritingAnUnchangedList() = runTest {
        // The plug list is highly stable and the 30s tick sweeps constantly on an always-on device — identical
        // results must not rewrite the file every time (flash wear for zero information).
        val file = java.io.File(tmp.root, "plugs.json")
        val client = FakeClient(listOf(Device("1", "Lamp", on = true)))
        val repo = diskRepo(client, PlugDiskCache(file))
        repo.refresh() // first sweep persists
        assertTrue(file.exists())

        file.delete() // observable probe: a redundant write would recreate it
        repo.syncKnown() // same list again
        assertFalse(file.exists()) // skipped — nothing changed

        client.devices = listOf(Device("1", "Lamp", on = false)) // a real change
        repo.syncKnown()
        assertEquals(listOf(Plug("1", "Lamp", on = false)), PlugDiskCache(file).load()) // written again
    }

    @Test fun failedUiToggleKicksARediscover() = runTest {
        // The UI has no retry, so a failed tap (stale seeded ip, plug moved) must at least rediscover — the
        // card set corrects within a sweep and the next tap targets the fresh ip.
        val client = FakeClient(listOf(Device("1", "Lamp", on = false)), relayOk = { _, _ -> false })
        val repo = repo(client)
        repo.refresh()
        assertEquals(1, client.discoverCount)

        repo.toggle("1", on = true) // send fails

        assertEquals(2, client.discoverCount) // one background rediscover kicked
        assertFalse(repo.snapshot().single().on) // optimistic flip still reverted
    }
}
