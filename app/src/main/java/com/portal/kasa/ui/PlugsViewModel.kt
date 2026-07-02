package com.portal.kasa.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.portal.kasa.data.KasaRepository
import com.portal.kasa.data.Plug
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns the plug dashboard's screen state and refresh cadence, so [PlugsScreen] is a pure consumer — no
 * dispatcher juggling, no direct repository/singleton calls, and config-change-safe (survives rotation, the
 * background sync isn't restarted). All network work is delegated to the injected [repository], which hops to
 * its own IO dispatcher, so everything launched here from [viewModelScope] (Main) stays main-safe.
 *
 * The re-sync is **lifecycle-aware**: it runs every [FOREGROUND_MS] while the screen is visible, but backs off
 * to [BACKGROUND_MS] once it isn't, so an always-on Portal isn't hammering the LAN (and waking the Wi-Fi radio)
 * every 30s in perpetuity. It backs off rather than fully pausing so the shared repository cache stays warm —
 * the voice provider ([com.portal.kasa.provider.KasaToolProvider]) reads that same cache, so a slow keep-warm
 * sweep keeps its plug name→IP mapping fresh without a scan on the voice turn. The UI reports visibility via
 * [onVisibilityChanged].
 *
 * Most ticks are the cheap [KasaRepository.syncKnown] (unicast to known plugs); only every [REDISCOVER_EVERY]-th
 * tick (plus initial load, manual retry, and resume) does a full broadcast [KasaRepository.refresh] to pick up
 * new/returned plugs.
 */
class PlugsViewModel(private val repository: KasaRepository) : ViewModel() {
    /** The discovered plugs, surfaced straight from the repository's cache. */
    val plugs: StateFlow<List<Plug>> = repository.plugs

    private val _refreshing = MutableStateFlow(false)

    /** True only during a foregrounded discovery (initial load / manual retry) — drives the header spinner. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /** Whether the dashboard is currently on screen — drives the re-sync cadence (see [onVisibilityChanged]). */
    private val visible = MutableStateFlow(false)

    /** True until the first foreground transition: [init] already did the initial load, so don't repeat it. */
    private var firstForeground = true

    /**
     * Count of completed re-sync ticks — every [REDISCOVER_EVERY]-th one is a broadcast rediscover. A field, not
     * a loop-local, so it survives the [kotlinx.coroutines.flow.collectLatest] restart on each visibility flip;
     * otherwise frequent flips would keep resetting it to 0 and the periodic broadcast could never come due.
     */
    private var syncTick = 0

    init {
        refresh() // initial load, with the spinner
        // Background re-sync so changes made elsewhere (the Kasa app, a physical button, other-device voice) show
        // up. collectLatest restarts the wait when visibility flips, so a cadence change takes effect at once.
        viewModelScope.launch {
            visible.collectLatest { isVisible ->
                val interval = if (isVisible) FOREGROUND_MS else BACKGROUND_MS
                while (true) {
                    delay(interval)
                    if (++syncTick % REDISCOVER_EVERY == 0) repository.refresh() else repository.syncKnown()
                }
            }
        }
    }

    /**
     * Report whether the dashboard is on screen; called from the composable's lifecycle. A genuine *return* to
     * the foreground kicks one quiet discovery so the dashboard is fresh on resume. The very first foreground is
     * skipped ([firstForeground]) because [init] already loaded — that's what prevents a duplicate sweep at
     * start/reopen. A configuration change (rotation) is filtered out upstream (see PlugsScreen), so it never
     * reports "hidden" and thus never looks like a return, keeping the re-sync config-change-safe.
     */
    fun onVisibilityChanged(isVisible: Boolean) {
        val wasVisible = visible.value
        visible.value = isVisible
        if (isVisible && !wasVisible) {
            if (firstForeground) firstForeground = false else viewModelScope.launch { repository.refresh() }
        }
    }

    /** Discover plugs with the spinner shown; ignored if a spinner-refresh is already running. */
    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        viewModelScope.launch {
            repository.refresh()
            _refreshing.value = false
        }
    }

    fun toggle(ip: String, on: Boolean) = repository.toggle(ip, on)

    /** "All on" / "All off": flip every plug not already in [on]. */
    fun setAll(on: Boolean) = plugs.value.filter { it.on != on }.forEach { repository.toggle(it.ip, on) }

    companion object {
        private const val FOREGROUND_MS = 30_000L // re-sync cadence while the dashboard is on screen
        private const val BACKGROUND_MS = 600_000L // backed-off cadence (10 min) that keeps the voice cache warm
        private const val REDISCOVER_EVERY = 10 // every Nth tick is a broadcast rediscover; the rest are unicast

        /** Factory that supplies the (non-default-constructible) [repository] to the ViewModel. */
        fun factory(repository: KasaRepository) = viewModelFactory {
            initializer { PlugsViewModel(repository) }
        }
    }
}
