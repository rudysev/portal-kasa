package com.portal.kasa.data

/**
 * Pure, Android-free, unit-tested merge of a fresh discovery against the plugs already on screen. Discovery is
 * lossy UDP, so this is *not* a replace:
 * - a plug that answered → take the fresh state (it's authoritative)… **unless** it's mid-toggle ([pending]),
 *   in which case keep the optimistic on/off so an in-flight command isn't snapped back by a stale snapshot;
 * - a plug that didn't answer → keep its last-known card, counting the miss; drop it only after [maxMisses]
 *   consecutive misses (genuinely removed). [misses] is mutated in place (consecutive-miss counts per ip);
 * - a plug that **moved** (DHCP gave it a new ip) → its old-ip card goes at once (see [retireMoved]), so the
 *   same device never shows — or gets voice-picked — twice.
 *
 * The result is sorted by alias so cards keep a stable order across refreshes.
 */
object PlugMerge {
    fun merge(
        prev: List<Plug>,
        found: List<Plug>,
        misses: MutableMap<String, Int>,
        pending: Set<String>,
        maxMisses: Int,
    ): List<Plug> {
        val foundByIp = found.associateBy { it.ip }
        val prevByIp = retireMoved(prev, found, misses).associateBy { it.ip }
        return (prevByIp.keys + foundByIp.keys).mapNotNull { ip ->
            val fresh = foundByIp[ip]
            if (fresh != null) {
                answered(fresh, prevByIp[ip], ip in pending, misses)
            } else {
                val n = (misses[ip] ?: 0) + 1
                misses[ip] = n
                if (n >= maxMisses) {
                    misses.remove(ip)
                    null
                } else {
                    prevByIp[ip]
                }
            }
        }.sortedBy { it.alias.lowercase() }
    }

    /**
     * State-only merge for a **unicast** refresh ([KasaClient.refreshKnown][com.portal.kasa.net.KasaClient]),
     * which only queries plugs we already know. Unlike [merge], a plug that doesn't answer is **not**
     * miss-counted or dropped — it just keeps its card, because *broadcast* discovery (which sweeps the whole
     * LAN) is the authority on membership. This prevents the tighter unicast window from evicting a plug that's
     * merely slow this cycle (it would otherwise flicker out for minutes until the next broadcast re-found it).
     * A plug that *does* answer takes the fresh state (unless mid-toggle) and has any pending miss cleared, so a
     * later broadcast won't drop a plug that unicast can plainly see is alive. Never adds plugs (a new ip only
     * appears via broadcast), though it does retire a moved plug's old card ([retireMoved]). Sorted by alias.
     */
    fun mergeUnicast(
        prev: List<Plug>,
        found: List<Plug>,
        misses: MutableMap<String, Int>,
        pending: Set<String>,
    ): List<Plug> {
        val foundByIp = found.associateBy { it.ip }
        return retireMoved(prev, found, misses).map { p ->
            val fresh = foundByIp[p.ip] ?: return@map p // silent on unicast → keep card, don't miss-count
            answered(fresh, p, p.ip in pending, misses)
        }.sortedBy { it.alias.lowercase() }
    }

    /**
     * Drop the [prev] cards that [found] shows have **moved**: silent at their ip while the same device answered
     * elsewhere. Same device means the same `deviceId`; a card with no id (a snapshot from before ids were
     * tracked) counts as the leftover of an identified plug answering to its alias. Keyed by ip alone, such a
     * card would linger for [merge]'s miss window beside its fresh twin, and a voice pick could land on it.
     */
    private fun retireMoved(prev: List<Plug>, found: List<Plug>, misses: MutableMap<String, Int>): List<Plug> {
        val foundIps = found.mapTo(HashSet()) { it.ip }
        val identified = found.filter { it.deviceId.isNotBlank() }
        val ids = identified.mapTo(HashSet()) { it.deviceId }
        val identifiedAliases = identified.mapTo(HashSet()) { it.alias }
        return prev.filter { p ->
            val moved = p.ip !in foundIps &&
                if (p.deviceId.isNotBlank()) p.deviceId in ids else p.alias in identifiedAliases
            if (moved) misses.remove(p.ip)
            !moved
        }
    }

    /**
     * Reconcile a plug that *answered* a sweep, shared by both merges: clear any pending miss (it's alive), and
     * take the fresh state — unless it's mid-toggle ([isPending]), in which case keep [prev]'s optimistic on/off
     * so an in-flight command isn't snapped back by a stale snapshot.
     */
    private fun answered(fresh: Plug, prev: Plug?, isPending: Boolean, misses: MutableMap<String, Int>): Plug {
        misses.remove(fresh.ip)
        return if (isPending) fresh.copy(on = prev?.on ?: fresh.on) else fresh
    }
}
