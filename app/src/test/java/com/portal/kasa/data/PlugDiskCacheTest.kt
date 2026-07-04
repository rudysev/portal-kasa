package com.portal.kasa.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure JVM tests for the warm-start snapshot: round-trip, and every failure mode degrades to "no snapshot". */
class PlugDiskCacheTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun cacheFile(): File = File(tmp.root, "plugs.json")

    @Test fun saveThenLoadRoundTripsAllFields() {
        val cache = PlugDiskCache(cacheFile())
        val plugs = listOf(
            Plug("192.168.1.10", "Coffee Maker", on = true),
            Plug("192.168.1.11", "Globe", on = false, isBulb = true),
        )

        cache.save(plugs)

        assertEquals(plugs, PlugDiskCache(cacheFile()).load()) // fresh instance: really from disk
    }

    @Test fun loadWithNoFileReturnsEmpty() {
        assertTrue(PlugDiskCache(cacheFile()).load().isEmpty())
    }

    @Test fun loadCorruptFileReturnsEmpty() {
        cacheFile().writeText("not json at all {")
        assertTrue(PlugDiskCache(cacheFile()).load().isEmpty())
    }

    @Test fun loadSkipsRecordsMissingIpOrAlias() {
        cacheFile().writeText("""[{"ip":"1","alias":"Lamp","on":true},{"alias":"NoIp"},{"ip":"2"}]""")
        assertEquals(listOf(Plug("1", "Lamp", on = true)), PlugDiskCache(cacheFile()).load())
    }

    @Test fun saveReplacesThePreviousSnapshot() {
        val cache = PlugDiskCache(cacheFile())
        cache.save(listOf(Plug("1", "Old", on = true)))

        cache.save(listOf(Plug("2", "New", on = false)))

        assertEquals(listOf(Plug("2", "New", on = false)), cache.load())
    }
}
