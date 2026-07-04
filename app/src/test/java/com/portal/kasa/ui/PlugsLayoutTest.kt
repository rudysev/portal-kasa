package com.portal.kasa.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the responsive column behaviour of [columnCount] across the device matrix. `maxByWidth` is what
 * BalancedGrid derives from the gap-aware fit `(gridWidth + gap) / (minCardW + gap)` — e.g. Portal 10"
 * landscape → 3 fit, Portal portrait → 2 fit, Portal+ landscape → 4 fit. The balanced target is only ever a
 * *ceiling* the width can pull down — except when `maxRows` (how many rows fit above the card-height floor)
 * forces extra columns, because a cramped card beats a clipped one.
 */
class PlugsLayoutTest {

    @Test
    fun oneOrZeroPlugsIsAlwaysOneColumn() {
        assertEquals(1, columnCount(n = 0, singleColumn = false, maxByWidth = 4))
        assertEquals(1, columnCount(n = 1, singleColumn = false, maxByWidth = 4))
    }

    @Test
    fun phoneStacksAtTwoColumnsButFollowsWidth() {
        assertEquals(2, columnCount(n = 6, singleColumn = true, maxByWidth = 5))
        assertEquals(1, columnCount(n = 6, singleColumn = true, maxByWidth = 1)) // ultra-narrow phone
    }

    @Test
    fun portalLandscapeSixPlugsStaysThreeColumns() {
        assertEquals(3, columnCount(n = 6, singleColumn = false, maxByWidth = 3))
    }

    @Test
    fun portalPortraitSixPlugsCollapsesToTwoColumns() {
        // The bug this fixes: the balanced target for 6 is 3, but only 2 comfortable cards fit in portrait.
        assertEquals(2, columnCount(n = 6, singleColumn = false, maxByWidth = 2))
    }

    @Test
    fun manyPlugsNeverExceedWhatFits() {
        // 12 plugs balances to 4 columns, but a narrow canvas must still cap at what fits.
        assertEquals(4, columnCount(n = 12, singleColumn = false, maxByWidth = 5))
        assertEquals(2, columnCount(n = 12, singleColumn = false, maxByWidth = 2))
        assertEquals(1, columnCount(n = 12, singleColumn = false, maxByWidth = 1))
    }

    @Test
    fun portalPlusWideGridAllowsFourColumns() {
        assertEquals(4, columnCount(n = 16, singleColumn = false, maxByWidth = 4))
    }

    @Test
    fun widthIsACeilingNotAFloor() {
        // A very wide canvas does not inflate a small plug set beyond its balanced target.
        assertEquals(2, columnCount(n = 4, singleColumn = false, maxByWidth = 10))
    }

    @Test
    fun fitCountIsGapAware() {
        // n items need n·minItem + (n−1)·gap: 2×340 + 16 = 696 — plain width/item division would say 2 at
        // 690dp, but the gap makes the second card land under its comfortable width.
        assertEquals(1, fitCount(available = 690.dp, minItem = 340.dp, gap = 16.dp))
        assertEquals(2, fitCount(available = 696.dp, minItem = 340.dp, gap = 16.dp))
        // Portal 10" grid width (1280 − 2×32 padding) comfortably fits 3.
        assertEquals(3, fitCount(available = 1216.dp, minItem = 340.dp, gap = 16.dp))
        // Never zero — a canvas smaller than one item still lays out a single column/row.
        assertEquals(1, fitCount(available = 100.dp, minItem = 340.dp, gap = 16.dp))
    }

    @Test
    fun heightFloorAddsColumnsPastTheWidthCap() {
        // 20 plugs on a Portal 10": 3 comfortable columns would mean 7 rows, squashing cards below their
        // height floor in the non-scrolling grid — the floor wins and columns grow past the width cap.
        assertEquals(7, columnCount(n = 20, singleColumn = false, maxByWidth = 3, maxRows = 3))
        // With enough vertical room the width cap holds as before.
        assertEquals(3, columnCount(n = 20, singleColumn = false, maxByWidth = 3, maxRows = 7))
        // The floor also applies to the phone tier's 2-column stack.
        assertEquals(4, columnCount(n = 8, singleColumn = true, maxByWidth = 2, maxRows = 2))
    }

    // --- dimensFor: lock the tier boundaries so the device matrix can't drift silently ---

    @Test
    fun phoneTierEndsAt600dp() {
        // singleColumn is the phone tier's structural tell.
        assertTrue(dimensFor(599.dp).singleColumn)
        assertFalse(dimensFor(600.dp).singleColumn) // 600 → standard Portal tier (wide cards)
        assertTrue(dimensFor(600.dp, 400.dp).singleColumn) // …unless the canvas is short (height rule wins)
    }

    @Test
    fun portalPlusTierStartsAt1400dp() {
        // pagePadH distinguishes the standard-Portal (32dp) from the Portal+ (40dp) tier.
        assertEquals(32.dp, dimensFor(1399.dp).pagePadH)
        assertEquals(40.dp, dimensFor(1400.dp).pagePadH)
    }

    @Test
    fun deviceMatrixMapsToExpectedTiers() {
        assertTrue(dimensFor(360.dp).singleColumn)        // phone
        assertFalse(dimensFor(800.dp).singleColumn)       // Portal portrait
        assertEquals(64.dp, dimensFor(800.dp).pageTop)    // Portal reserves the overlay strip; phone doesn't
        assertEquals(24.dp, dimensFor(360.dp).pageTop)
        assertEquals(32.dp, dimensFor(1280.dp).pagePadH)  // Portal 10" landscape (standard tier)
        assertEquals(40.dp, dimensFor(1920.dp).pagePadH)  // Portal+ (expanded tier)
    }

    @Test
    fun shortCanvasIsPhoneTierRegardlessOfWidth() {
        // A landscape phone is Portal-wide but ~400dp tall — Portal chrome (overlay reserve, 56sp clock)
        // would eat most of that. Height classifies it back to the phone tier.
        assertTrue(dimensFor(851.dp, 393.dp).singleColumn)
        assertEquals(24.dp, dimensFor(851.dp, 393.dp).pageTop) // no phantom overlay reserve
        // Real Portals are ≥800dp tall in both orientations and keep their tier.
        assertFalse(dimensFor(800.dp, 1280.dp).singleColumn) // Portal portrait
        assertFalse(dimensFor(1280.dp, 800.dp).singleColumn) // Portal landscape
    }
}
