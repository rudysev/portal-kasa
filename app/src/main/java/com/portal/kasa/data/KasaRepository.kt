package com.portal.kasa.data

import com.portal.commons.DebugLog
import com.portal.kasa.net.KasaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

private const val MAX_PARALLEL_SENDS = 4 // cap concurrent plug sends so a mass-toggle can't saturate the IO pool

/**
 * Holds the discovered plugs — shared by the UI (a [StateFlow]) and the voice provider (synchronous getters).
 * Local LAN: no account/token.
 *
 * Injectable **class** (not an `object`): the [client] (LAN seam) and the dispatchers are passed in, so the
 * discovery/send pipeline below — the concurrency-critical code — is unit-testable with a fake client and a
 * test dispatcher (see `KasaRepositoryTest`). A single instance is held by [Graph][com.portal.kasa.Graph] and
 * shared by the activity and the provider.
 *
 * Two dispatchers, by design:
 * - [io] (unbounded `Dispatchers.IO`) runs the **discovery** sweep — a single, refresh-serialised, ~1.5 s
 *   blocking call that must not sit in a scarce pool.
 * - [relayDispatcher] (`io.limitedParallelism`) runs the **per-plug sends** — throttled so a mass-toggle of
 *   many plugs can't flood IO. Keeping discovery and sends on separate pools means a slow sweep never starves
 *   a tap, and vice-versa.
 *
 * Concurrency model (the UI, the voice provider, and the periodic refresh all touch this from different
 * coroutines/threads):
 * - **One send pipeline.** Both [toggle] (UI, fire-and-forget) and [setRelay] (voice, suspending ack) record
 *   the latest desired state per plug and drain it through [drain], which holds a **per-ip [Mutex]** — so the
 *   two paths never fire conflicting TCP commands at the same plug, and mashing collapses to the most recent
 *   state (latest-wins: rapid taps overwrite [desired] before the previous drain reads it).
 * - **Instant, pool-independent optimistic flip.** [setCachedState] runs synchronously under a plain
 *   [cacheLock] monitor (a microsecond in-memory list copy, never wrapping I/O), so a tapped Switch moves on
 *   the calling thread without waiting for a dispatcher slot.
 * - **Refresh can't clobber an in-flight toggle.** [refresh] merges discovery with the current list and, for
 *   any plug mid-toggle ([pendingIps]), keeps the optimistic on/off. The merge write shares [cacheLock] with
 *   [setCachedState], so an optimistic flip and a merge can't interleave and lose an update. [refresh] cycles
 *   are serialised by [refreshMutex] (a [Mutex], since it is held across the discovery suspension).
 */
class KasaRepository(
    private val client: KasaClient,
    private val io: CoroutineDispatcher,
    private val relayDispatcher: CoroutineDispatcher = io.limitedParallelism(MAX_PARALLEL_SENDS),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + io),
) {
    private val _plugs = MutableStateFlow<List<Plug>>(emptyList())
    val plugs: StateFlow<List<Plug>> = _plugs.asStateFlow()

    @Volatile private var lastGood: List<Plug> = emptyList()
    private val misses = ConcurrentHashMap<String, Int>() // ip → consecutive discovery cycles unseen

    private val desired = ConcurrentHashMap<String, Boolean>() // ip → latest requested state (not yet sent)
    private val inflight = ConcurrentHashMap.newKeySet<String>() // ips with a send currently on the wire
    private val ipMutexes = ConcurrentHashMap<String, Mutex>() // serialises sends per plug (suspending)
    private val cacheLock = Any() // guards every _plugs mutation; held only for the in-memory write
    private val refreshMutex = Mutex() // serialises whole refresh cycles (held across discovery I/O)

    /** Cached snapshot for the provider (no network). */
    fun snapshot(): List<Plug> = _plugs.value

    /**
     * Re-discover plugs on the LAN and **merge** with the current list (see [PlugMerge]): a plug that misses a
     * (lossy UDP) cycle keeps its card until [MAX_MISSES] misses in a row, and a plug mid-toggle keeps its
     * optimistic state. A totally empty result is treated as a local blip and left intact. Discovery I/O runs
     * on the unbounded [io]; the whole cycle is serialised so two callers (UI refresh + voice) can't interleave.
     */
    suspend fun refresh(): Boolean = refreshMutex.withLock {
        // Map the wire type to the domain [Plug] at this boundary — net types don't travel past the repository.
        val found = withContext(io) { client.discover() }.map { Plug(it.ip, it.alias, it.on) }
        synchronized(cacheLock) {
            if (found.isEmpty()) {
                if (_plugs.value.isEmpty()) _plugs.value = lastGood
            } else {
                val merged = PlugMerge.merge(_plugs.value, found, misses, pendingIps(), MAX_MISSES)
                _plugs.value = merged
                lastGood = merged
            }
        }
        _plugs.value.isNotEmpty()
    }

    /** Ensure plugs are loaded (discover once if the cache is empty) — the voice provider's fast path. */
    suspend fun ensureLoaded(): Boolean = if (_plugs.value.isNotEmpty()) true else refresh()

    /**
     * UI toggle: mark the desired state, flip the cache optimistically (synchronously — the Switch moves now),
     * and send in the background, coalesced per plug. Safe to call rapidly from the main thread.
     */
    fun toggle(ip: String, on: Boolean) {
        desired[ip] = on // mark pending *before* the optimistic flip so a refresh in the gap can't overwrite it
        setCachedState(ip, on)
        scope.launch { drain(ip) }
    }

    /**
     * Voice flip: same pipeline as [toggle] but **suspending**, returning the ack the provider speaks back.
     * Shares the per-ip [Mutex] with the UI path, so a voice command and a UI toggle on the same plug serialise
     * instead of racing. If the caller is cancelled (e.g. the provider's invoke timeout) before the send drains
     * the pending state, the pending mark is cleared so a later [refresh] reconciles the cache from discovery
     * instead of preserving a phantom optimistic state forever.
     */
    suspend fun setRelay(ip: String, on: Boolean): Boolean {
        desired[ip] = on // pending before the optimistic flip (see [toggle])
        setCachedState(ip, on)
        return try {
            drain(ip)
        } catch (c: CancellationException) {
            desired.remove(ip, on) // conditional: don't drop a newer UI intent that landed in the meantime
            throw c
        }
    }

    /** Set of ips whose desired state isn't yet confirmed on the plug — refresh must not overwrite these. */
    private fun pendingIps(): Set<String> = HashSet(desired.keys).apply { addAll(inflight) }

    /**
     * Drain the latest desired state(s) for [ip] until none is pending. Per-ip-locked so only one sender runs
     * per plug; the socket write itself runs on the throttled [relayDispatcher]. Reverts the optimistic flip on
     * failure when nothing newer is queued. Returns the ack of the last send (true if another drain already
     * satisfied it). Launching two drains for one ip is harmless — the loser finds [desired] empty and returns.
     */
    private suspend fun drain(ip: String): Boolean = mutexFor(ip).withLock {
        inflight.add(ip)
        try {
            var lastOk = true
            while (true) {
                val want = desired.remove(ip) ?: break
                lastOk = withContext(relayDispatcher) { client.setRelay(ip, want) }
                if (!lastOk && !desired.containsKey(ip)) setCachedState(ip, !want)
                DebugLog.log("kasa relay $ip=$want → $lastOk")
            }
            lastOk
        } finally {
            inflight.remove(ip)
        }
    }

    private fun mutexFor(ip: String): Mutex = ipMutexes.getOrPut(ip) { Mutex() }

    private fun setCachedState(ip: String, on: Boolean) = synchronized(cacheLock) {
        _plugs.value = _plugs.value.map { if (it.ip == ip) it.copy(on = on) else it }
    }

    private companion object {
        const val MAX_MISSES = 3 // keep showing a known plug until this many cycles in a row miss it
    }
}
