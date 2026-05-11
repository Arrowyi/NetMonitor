package com.telenav.netmonitor

import org.junit.Assert.assertEquals
import org.junit.Test

class SubsystemUsageTest {

    @Test
    fun `totalBytes sums upload and download`() {
        val u = SubsystemUsage("SEARCH", uploadBytes = 1024L, downloadBytes = 3072L)
        assertEquals(4096L, u.totalBytes)
    }

    @Test
    fun `totalBytes with zero upload`() {
        val u = SubsystemUsage("ROUTING", uploadBytes = 0L, downloadBytes = 500L)
        assertEquals(500L, u.totalBytes)
    }
}
