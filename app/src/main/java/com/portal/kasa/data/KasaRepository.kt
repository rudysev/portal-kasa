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
    /**
     * ip → isBulb. A device's kind is stable, so — unlike [_plugs] — this is never evicted; [drain] routes the
     * command from it even if the card was dropped mid-toggle (re-deriving from [_plugs] could misroute a
     * dropped bulb to `set_relay_state`).
     */
    private val kindByIp = ConcurrentHashMap<String, Boolean>()
    private val ipMutexes = ConcurrentHashMap<String, Mutex>() // serialises sends per plug (suspending)
    private val cacheLock = Any() // guards every _plugs mutation; held only for the in-memory write
    private val refreshMutex = Mutex() // serialises whole refresh cycles (held across discovery I/O)

    /** Cached snapshot for the provider (no network). */
    fun snapshot(): List<Plug> = _plugs.value

    /**
     * Broadcast-discover the LAN and merge into the cache (see [broadcastDiscover]). Serialised with [syncKnown]
     * via [refreshMutex] so a UI refresh and a voice call can't interleave. Returns whether any plug is known.
     */
    suspend fun refresh(): Boolean = refreshMutex.withLock { broadcastDiscover() }

    /**
     * Cheap periodic re-sync: directed-unicast [KasaClient.refreshKnown] the known plugs, skipping the broadcast
     * blast (and `MulticastLock`) that [refresh] does. Broadcast is only needed to *find* new/returned plugs, so
     * the frequent tick uses this; a cold cache falls back to a broadcast discover. Unicast only updates state and
     * never drops a silent plug — membership is [refresh]'s job (see [PlugMerge.mergeUnicast]).
     */
    suspend fun syncKnown(): Boolean = refreshMutex.withLock {
        val known = _plugs.value.map { it.ip }
        if (known.isEmpty()) {
            broadcastDiscover()
        } else {
            val found = withContext(io) { client.refreshKnown(known) }.map { Plug(it.ip, it.alias, it.on, it.isBulb) }
            store(found) { prev -> PlugMerge.mergeUnicast(prev, found, misses, pendingIps()) }
        }
    }

    /**
     * Broadcast-discover and merge for membership (miss-counts and can drop plugs it no longer sees). Shared by
     * [refresh] and [syncKnown]'s cold-cache fallback; assumes the caller holds [refreshMutex] (it's non-reentrant,
     * so this can't just call [refresh]).
     */
    private suspend fun broadcastDiscover(): Boolean {
        // Map the wire type to the domain [Plug] at this boundary — net types don't travel past the repository.
        val found = withContext(io) { client.discover() }.map { Plug(it.ip, it.alias, it.on, it.isBulb) }
        return store(found) { prev -> PlugMerge.merge(prev, found, misses, pendingIps(), MAX_MISSES) }
    }

    /**
     * Store a discovery/refresh result into the cache under [cacheLock]: a totally empty result is treated as a
     * local blip and left intact; otherwise [merge] produces the new list (the caller picks the broadcast
     * membership merge or the unicast state-only merge). Also records each device's (stable) kind in [kindByIp].
     * Shared by [broadcastDiscover] and [syncKnown].
     */
    private inline fun store(found: List<Plug>, merge: (List<Plug>) -> List<Plug>): Boolean {
        found.forEach { kindByIp[it.ip] = it.isBulb } // remember kinds even for plugs a later merge may drop
        synchronized(cacheLock) {
            if (found.isEmpty()) {
                if (_plugs.value.isEmpty()) _plugs.value = lastGood
            } else {
                val merged = merge(_plugs.value)
                _plugs.value = merged
                lastGood = merged
            }
        }
        return _plugs.value.isNotEmpty()
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
                val isBulb = kindByIp[ip] ?: false // stable kind, not re-derived from the evictable cache
                lastOk = withContext(relayDispatcher) { client.setRelay(ip, want, isBulb) }
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
