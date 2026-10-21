package com.sscrobbler.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugLogTest {

    @Before
    fun setUp() {
        DebugLog.clear()
    }

    @Test
    fun log_appendsEntriesAndExportsThem() {
        DebugLog.log("ENGINE", "START Artist - Track")
        DebugLog.log("API", "track.scrobble -> HTTP 200, ok")

        val entries = DebugLog.entries.value
        assertEquals(2, entries.size)
        assertEquals("ENGINE", entries[0].tag)
        assertTrue(DebugLog.export().contains("START Artist - Track"))
        assertTrue(DebugLog.export().contains("[API]"))
    }

    @Test
    fun clear_removesEverything() {
        DebugLog.log("TRACK", "state=3")
        DebugLog.clear()
        assertTrue(DebugLog.entries.value.isEmpty())
        assertEquals("sScrobbler debug log is empty", DebugLog.export())
    }

    @Test
    fun buffer_isBoundedToMaxEntries() {
        repeat(900) { index ->
            DebugLog.log("TRACK", "entry $index")
        }
        val entries = DebugLog.entries.value
        assertTrue(entries.size <= 800)
        assertEquals("entry 899", entries.last().message)
    }
}
