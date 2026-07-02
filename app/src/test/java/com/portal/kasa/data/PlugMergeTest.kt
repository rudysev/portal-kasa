package com.portal.kasa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the discovery↔cache merge (miss tolerance, optimistic preservation, ordering). */
class PlugMergeTest {

    private fun dev(ip: String, alias: String, on: Boolean) = Plug(ip, alias, on)
    private fun merge(
        prev: List<Plug>,
        found: List<Plug>,
        misses: MutableMap<String, Int> = mutableMapOf(),
        pending: Set<String> = emptySet(),
        maxMisses: Int = 3,
    ) = PlugMerge.merge(prev, found, misses, pending, maxMisses)

    @Test fun freshStateWinsAndResultIsAliasSorted() {
        val prev = listOf(dev("2", "Zeta", on = false))
        val found = listOf(dev("2", "Zeta", on = true), dev("1", "Alpha", on = false))
        val merged = merge(prev, found)
        assertEquals(listOf("Alpha", "Zeta"), merged.map { it.alias }) // sorted by alias
        assertTrue(merged.first { it.ip == "2" }.on) // took the fresh on=true
    }

    @Test fun missedPlugIsKeptThenDroppedAfterMaxMisses() {
        val misses = mutableMapOf<String, Int>()
        val a = dev("1", "Alpha", on = true)
        val b = dev("2", "Bravo", on = false)
        var list = listOf(a, b)

        // Bravo stops answering; Alpha keeps answering. maxMisses = 3.
        list = merge(list, listOf(a), misses) // miss 1 → kept
        assertEquals(setOf("Alpha", "Bravo"), list.map { it.alias }.toSet())
        list = merge(list, listOf(a), misses) // miss 2 → kept
        assertTrue(list.any { it.alias == "Bravo" })
        list = merge(list, listOf(a), misses) // miss 3 → dropped
        assertEquals(listOf("Alpha"), list.map { it.alias })
        assertNull(misses["2"]) // counter cleared on drop
    }

    @Test fun reappearingPlugResetsMissCounter() {
        val misses = mutableMapOf("2" to 2)
        val a = dev("1", "Alpha", on = true)
        val b = dev("2", "Bravo", on = true)
        val merged = merge(listOf(a, b), listOf(a, b), misses)
        assertEquals(2, merged.size)
        assertNull(misses["2"]) // seen again → reset
    }

    @Test fun pendingPlugKeepsOptimisticStateOverStaleSnapshot() {
        val prev = listOf(dev("1", "Alpha", on = true)) // optimistic ON after a tap
        val found = listOf(dev("1", "Alpha", on = false)) // discovery still reports stale OFF
        val merged = merge(prev, found, pending = setOf("1"))
        assertTrue(merged.single().on) // optimistic ON preserved
    }

    @Test fun nonPendingPlugTakesFreshState() {
        val prev = listOf(dev("1", "Alpha", on = true))
        val found = listOf(dev("1", "Alpha", on = false))
        val merged = merge(prev, found, pending = emptySet())
        assertFalse(merged.single().on) // not mid-toggle → trust discovery
    }

    @Test fun newlyDiscoveredPlugIsAdded() {
        val merged = merge(emptyList(), listOf(dev("1", "Alpha", on = false)))
        assertEquals(listOf("Alpha"), merged.map { it.alias })
    }

    @Test fun unicastMergeKeepsSilentPlugAndNeverMissCounts() {
        val misses = mutableMapOf<String, Int>()
        val a = dev("1", "Alpha", on = true)
        val b = dev("2", "Bravo", on = false)
        var list = listOf(a, b)

        // Bravo is silent on unicast across many cycles — it must stay, and never accrue a miss (broadcast
        // governs removal, not unicast).
        repeat(5) { list = PlugMerge.mergeUnicast(list, listOf(a), misses, emptySet()) }

        assertEquals(setOf("Alpha", "Bravo"), list.map { it.alias }.toSet())
        assertTrue(misses.isEmpty()) // no miss counting on the unicast path
    }

    @Test fun unicastMergeUpdatesAnsweredStateClearsMissAndKeepsOptimistic() {
        val misses = mutableMapOf("1" to 2) // a prior broadcast miss pending on Alpha
        val prev = listOf(dev("1", "Alpha", on = false), dev("2", "Bravo", on = true))
        // Alpha answers on=true (clears its miss); Bravo answers on=false but is mid-toggle so keeps optimistic.
        val found = listOf(dev("1", "Alpha", on = true), dev("2", "Bravo", on = false))

        val merged = PlugMerge.mergeUnicast(prev, found, misses, pending = setOf("2"))

        assertTrue(merged.first { it.ip == "1" }.on) // took fresh state
        assertNull(misses["1"]) // answered → pending miss cleared
        assertTrue(merged.first { it.ip == "2" }.on) // pending → optimistic ON preserved over stale OFF
    }

    @Test fun unicastMergeNeverAddsNewPlugs() {
        // Unicast only queries known plugs; a stray reply for an unknown ip must not appear.
        val merged = PlugMerge.mergeUnicast(listOf(dev("1", "Alpha", on = false)), listOf(dev("9", "Ghost", on = true)), mutableMapOf(), emptySet())
        assertEquals(listOf("Alpha"), merged.map { it.alias })
    }
}
