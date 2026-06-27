package com.portal.kasa.data

/**
 * Pure, Android-free, unit-tested merge of a fresh discovery against the plugs already on screen. Discovery is
 * lossy UDP, so this is *not* a replace:
 * - a plug that answered → take the fresh state (it's authoritative)… **unless** it's mid-toggle ([pending]),
 *   in which case keep the optimistic on/off so an in-flight command isn't snapped back by a stale snapshot;
 * - a plug that didn't answer → keep its last-known card, counting the miss; drop it only after [maxMisses]
 *   consecutive misses (genuinely removed). [misses] is mutated in place (consecutive-miss counts per ip).
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
        val prevByIp = prev.associateBy { it.ip }
        return (prevByIp.keys + foundByIp.keys).mapNotNull { ip ->
            val fresh = foundByIp[ip]
            if (fresh != null) {
                misses.remove(ip)
                if (ip in pending) fresh.copy(on = prevByIp[ip]?.on ?: fresh.on) else fresh
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
}
