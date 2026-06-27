package com.portal.kasa.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for spoken-name → plug resolution. */
class PlugMatchTest {
    private val plugs =
        listOf(
            PlugMatch.Candidate("p1", "Coffee Maker"),
            PlugMatch.Candidate("p2", "Piano Lights"),
            PlugMatch.Candidate("p3", "Living Room Lamp"),
            PlugMatch.Candidate("p4", "Espresso Machine"),
        )

    private fun pick(q: String) = PlugMatch.best(q, plugs).pick?.alias

    @Test fun exactAndCaseInsensitive() {
        assertEquals("Coffee Maker", pick("Coffee Maker"))
        assertEquals("Coffee Maker", pick("coffee maker"))
    }

    @Test fun stripsLeadingArticleAndMatchesPrefix() {
        assertEquals("Piano Lights", pick("the piano lights"))
        assertEquals("Coffee Maker", pick("coffee")) // prefix of the compact alias
    }

    @Test fun containsMatch() {
        assertEquals("Living Room Lamp", pick("living room lamp"))
        assertEquals("Living Room Lamp", pick("lamp")) // alias contains "lamp"... only one lamp
    }

    @Test fun shortQueryDoesNotAutoPick() {
        assertNull(PlugMatch.best("co", plugs).pick) // below min length, not exact
    }

    @Test fun noMatchReturnsCandidates() {
        val r = PlugMatch.best("coffee maler", plugs) // misheard "coffee maker" — shares the 'coffee' token
        assertNull(r.pick)
        assertTrue(r.candidates.contains("Coffee Maker"))
    }

    @Test fun emptyAndBlankSafe() {
        assertNull(pick(""))
        assertNull(pick("the"))
        assertNull(PlugMatch.best("coffee", emptyList()).pick)
    }

    @Test fun ambiguousWeakTieReturnsCandidatesNotAGuess() {
        val twoLamps = listOf(
            PlugMatch.Candidate("a", "Bedroom Lamp"),
            PlugMatch.Candidate("b", "Living Room Lamp"),
        )
        val r = PlugMatch.best("lamp", twoLamps) // both only 'contains' (weak) → ambiguous, don't guess
        assertNull(r.pick)
        assertTrue(r.candidates.containsAll(listOf("Bedroom Lamp", "Living Room Lamp")))
    }

    @Test fun strongPrefixTieStillAutoPicks() {
        val twoPianos = listOf(
            PlugMatch.Candidate("a", "Piano Light Left"),
            PlugMatch.Candidate("b", "Piano Light Right"),
        )
        // "piano light left" is an exact match → confident even though both share the 'piano' prefix.
        assertEquals("Piano Light Left", PlugMatch.best("piano light left", twoPianos).pick?.alias)
    }

    @Test fun ambiguousStrongPrefixReturnsCandidatesNotAGuess() {
        val pianos = listOf(
            PlugMatch.Candidate("a", "Piano Light Left"),
            PlugMatch.Candidate("b", "Piano Light Right"),
            PlugMatch.Candidate("c", "Piano Light Right Right"),
        )
        // "piano" is a strict prefix of all three at the same (strong) score — genuinely ambiguous, so it must
        // offer candidates rather than silently auto-picking the shortest-named one.
        val r = PlugMatch.best("piano", pianos)
        assertNull(r.pick)
        assertEquals(
            setOf("Piano Light Left", "Piano Light Right", "Piano Light Right Right"),
            r.candidates.toSet(),
        )
    }
}
