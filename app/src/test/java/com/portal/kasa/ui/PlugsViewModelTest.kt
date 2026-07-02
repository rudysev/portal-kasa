package com.portal.kasa.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.portal.kasa.data.KasaRepository
import com.portal.kasa.net.KasaClient
import com.portal.kasa.net.KasaLocal.Device
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure JVM tests for the ViewModel's screen state + refresh cadence. A [StandardTestDispatcher] drives Main
 * (so [androidx.lifecycle.viewModelScope] is controllable) and the repository's dispatchers, and the VM is
 * created through a [ViewModelStore] so [tearDown] can clear it — cancelling the init-launched 30s sync loop
 * that would otherwise run forever. [TestCoroutineScheduler.runCurrent] runs work queued *now* without
 * advancing virtual time, so that loop stays parked at its delay instead of spinning.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlugsViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private lateinit var store: ViewModelStore

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = ViewModelStore()
    }

    @After fun tearDown() {
        store.clear() // triggers onCleared → cancels viewModelScope (and the parked sync loop)
        Dispatchers.resetMain()
    }

    private class FakeClient(var devices: List<Device>) : KasaClient {
        val sends = mutableListOf<Pair<String, Boolean>>()
        var discoverCount = 0
        override fun discover(): List<Device> {
            discoverCount++
            return devices
        }

        override fun refreshKnown(ips: List<String>): List<Device> = devices.filter { it.ip in ips }
        override fun setRelay(ip: String, on: Boolean, isBulb: Boolean): Boolean {
            sends.add(ip to on)
            return true
        }
    }

    private fun viewModel(client: FakeClient): PlugsViewModel {
        val repo = KasaRepository(client, io = dispatcher, relayDispatcher = dispatcher, scope = CoroutineScope(dispatcher))
        return ViewModelProvider(store, PlugsViewModel.factory(repo))[PlugsViewModel::class.java]
    }

    @Test fun initialLoadShowsSpinnerThenPopulatesAliasSorted() {
        val vm = viewModel(FakeClient(listOf(Device("1", "Lamp", on = false), Device("2", "Fan", on = true))))

        assertTrue(vm.refreshing.value)            // spinner shown immediately (init kicks off a refresh)
        assertTrue(vm.plugs.value.isEmpty())       // discovery hasn't run yet

        scheduler.runCurrent()

        assertFalse(vm.refreshing.value)           // spinner cleared once discovery completes
        assertEquals(listOf("Fan", "Lamp"), vm.plugs.value.map { it.alias }) // alias-sorted
    }

    @Test fun firstForegroundSkipsDuplicateLoadButLaterResumeRefreshes() {
        val client = FakeClient(listOf(Device("1", "Lamp", on = false)))
        val vm = viewModel(client)
        scheduler.runCurrent() // init's initial load
        assertEquals(1, client.discoverCount)

        vm.onVisibilityChanged(true) // first foreground — init already loaded, so must NOT re-sweep
        scheduler.runCurrent()
        assertEquals(1, client.discoverCount)

        vm.onVisibilityChanged(false) // genuine background
        vm.onVisibilityChanged(true) // genuine return → one quiet broadcast refresh
        scheduler.runCurrent()
        assertEquals(2, client.discoverCount)
    }

    @Test fun broadcastRediscoverScheduleSurvivesVisibilityFlips() {
        // REDISCOVER_EVERY = 10: the 10th re-sync tick is a broadcast. A visibility flip restarts the cadence
        // loop, so if the tick counter were loop-local it would reset and the broadcast would never come due.
        val client = FakeClient(listOf(Device("1", "Lamp", on = false)))
        val vm = viewModel(client)
        scheduler.runCurrent() // init broadcast load
        vm.onVisibilityChanged(true) // first foreground: init already loaded, no extra sweep
        scheduler.runCurrent()
        assertEquals(1, client.discoverCount)

        repeat(5) { scheduler.advanceTimeBy(30_000); scheduler.runCurrent() } // ticks 1-5: unicast
        // Flip visibility (background→foreground). A loop-local counter would reset to 0 here.
        vm.onVisibilityChanged(false)
        vm.onVisibilityChanged(true) // genuine resume → one quiet broadcast refresh (discoverCount 1→2)
        scheduler.runCurrent()
        assertEquals(2, client.discoverCount)
        repeat(5) { scheduler.advanceTimeBy(30_000); scheduler.runCurrent() } // ticks 6-10: 10th is a broadcast

        // init + resume + the 10th-tick rediscover. If the flip had reset the counter, only 5 ticks would have
        // elapsed since and this would still be 2.
        assertEquals(3, client.discoverCount)
    }

    @Test fun toggleDelegatesToRepositoryAndFlipsCache() {
        val client = FakeClient(listOf(Device("1", "Lamp", on = false)))
        val vm = viewModel(client)
        scheduler.runCurrent() // initial load

        vm.toggle("1", on = true)
        scheduler.runCurrent() // drain the send

        assertTrue(vm.plugs.value.single().on)
        assertEquals(listOf("1" to true), client.sends)
    }

    @Test fun setAllFlipsOnlyPlugsNotAlreadyInTargetState() {
        val client = FakeClient(
            listOf(Device("1", "A", on = false), Device("2", "B", on = true), Device("3", "C", on = false)),
        )
        val vm = viewModel(client)
        scheduler.runCurrent()

        vm.setAll(on = true) // B is already on → only A and C should be sent
        scheduler.runCurrent()

        assertEquals(setOf("1" to true, "3" to true), client.sends.toSet())
        assertTrue(vm.plugs.value.all { it.on })
    }
}
