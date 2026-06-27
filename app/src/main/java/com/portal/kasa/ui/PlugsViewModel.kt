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
import kotlinx.coroutines.launch

/**
 * Owns the plug dashboard's screen state and refresh cadence, so [PlugsScreen] is a pure consumer — no
 * dispatcher juggling, no direct repository/singleton calls, and config-change-safe (survives rotation, the
 * background sync isn't restarted). All network work is delegated to the injected [repository], which hops to
 * its own IO dispatcher, so everything launched here from [viewModelScope] (Main) stays main-safe.
 */
class PlugsViewModel(private val repository: KasaRepository) : ViewModel() {
    /** The discovered plugs, surfaced straight from the repository's cache. */
    val plugs: StateFlow<List<Plug>> = repository.plugs

    private val _refreshing = MutableStateFlow(false)

    /** True only during a foregrounded discovery (initial load / manual retry) — drives the header spinner. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        refresh() // initial load, with the spinner
        // Quiet background re-sync so changes made elsewhere (the Kasa app, a physical button, voice from
        // another device) show up. The merge keeps any in-flight toggle, so this never snaps a switch back.
        viewModelScope.launch {
            while (true) {
                delay(AUTO_REFRESH_MS)
                repository.refresh()
            }
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
        private const val AUTO_REFRESH_MS = 30_000L

        /** Factory that supplies the (non-default-constructible) [repository] to the ViewModel. */
        fun factory(repository: KasaRepository) = viewModelFactory {
            initializer { PlugsViewModel(repository) }
        }
    }
}
