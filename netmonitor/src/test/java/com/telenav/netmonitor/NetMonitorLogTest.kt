package com.telenav.netmonitor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NetMonitorLogTest {

    /** Captures every emit so we can assert sub-tag, message, and severity routing. */
    private class CapturingLog : NetMonitorLog {
        data class Entry(val level: Char, val sub: String, val msg: String, val t: Throwable?)

        val entries = mutableListOf<Entry>()

        override fun i(sub: String, msg: String) {
            entries += Entry('I', sub, msg, null)
        }

        override fun w(sub: String, msg: String, t: Throwable?) {
            entries += Entry('W', sub, msg, t)
        }

        override fun e(sub: String, msg: String, t: Throwable?) {
            entries += Entry('E', sub, msg, t)
        }

        override fun d(sub: String, msg: String) {
            entries += Entry('D', sub, msg, null)
        }
    }

    private lateinit var original: NetMonitorLog

    @Before
    fun saveLogger() {
        original = NetMonitorConfig.logger
    }

    @After
    fun restoreLogger() {
        NetMonitorConfig.logger = original
    }

    @Test
    fun `default logger is AndroidNetMonitorLog`() {
        assertSame(AndroidNetMonitorLog, original)
    }

    @Test
    fun `module-wide tag is the single grep marker`() {
        assertEquals("NetMonitor", NetMonitorLog.TAG)
    }

    @Test
    fun `injected logger receives all NMLog calls with the right severity`() {
        val sink = CapturingLog()
        NetMonitorConfig.logger = sink

        NMLog.i(NMSub.SERVICE, "service up")
        val boom = RuntimeException("boom")
        NMLog.w(NMSub.DNS, "dns failed", boom)
        NMLog.e(NMSub.SERVICE, "fatal", boom)
        NMLog.d(NMSub.AVD, "avd dump")

        assertEquals(4, sink.entries.size)
        assertEquals(CapturingLog.Entry('I', "Service", "service up", null), sink.entries[0])
        assertEquals(CapturingLog.Entry('W', "DNS", "dns failed", boom), sink.entries[1])
        assertEquals(CapturingLog.Entry('E', "Service", "fatal", boom), sink.entries[2])
        assertEquals(CapturingLog.Entry('D', "AvD", "avd dump", null), sink.entries[3])
    }

    @Test
    fun `swapping logger mid-stream takes effect immediately`() {
        val first = CapturingLog()
        val second = CapturingLog()

        NetMonitorConfig.logger = first
        NMLog.i(NMSub.INIT, "first")

        NetMonitorConfig.logger = second
        NMLog.i(NMSub.INIT, "second")

        assertEquals(1, first.entries.size)
        assertEquals("first", first.entries[0].msg)
        assertEquals(1, second.entries.size)
        assertEquals("second", second.entries[0].msg)
    }

    @Test
    fun `nm sub identifiers cover every documented call site`() {
        // Guards against typo-driven tag fragmentation if someone hand-codes
        // a sub string instead of using NMSub.
        val all = setOf(NMSub.INIT, NMSub.SERVICE, NMSub.WINDOW, NMSub.DNS, NMSub.DIAG, NMSub.AVD)
        assertEquals(6, all.size)
        assertTrue(all.all { it.isNotBlank() })
    }

    @Test
    fun `default w accepts null throwable`() {
        // Smoke test that the default logger never throws when t is null,
        // exercising the runCatching wrapper around android.util.Log under
        // the JVM unit-test runner (where the framework class is not mocked).
        AndroidNetMonitorLog.w(NMSub.SERVICE, "no throwable", null)
        AndroidNetMonitorLog.e(NMSub.SERVICE, "no throwable", null)
        AndroidNetMonitorLog.i(NMSub.SERVICE, "info")
        AndroidNetMonitorLog.d(NMSub.SERVICE, "debug")
        // No assertion: success == no exception escaped.
    }

    @Test
    fun `custom logger d default override is invocable`() {
        // The interface gives `d` an empty default body; verify a host that
        // skips overriding it can still receive the call without error.
        val minimalSink = object : NetMonitorLog {
            var iCalled: String? = null
            override fun i(sub: String, msg: String) { iCalled = "$sub|$msg" }
            override fun w(sub: String, msg: String, t: Throwable?) {}
            override fun e(sub: String, msg: String, t: Throwable?) {}
        }
        NetMonitorConfig.logger = minimalSink
        NMLog.d(NMSub.AVD, "ignored")
        NMLog.i(NMSub.AVD, "kept")
        assertEquals("AvD|kept", minimalSink.iCalled)
    }

    @Test
    fun `logger injection survives a roundtrip restore`() {
        // Sanity test for @After teardown.
        val sink = CapturingLog()
        NetMonitorConfig.logger = sink
        NetMonitorConfig.logger = original
        assertSame(original, NetMonitorConfig.logger)
        NMLog.i(NMSub.INIT, "after restore goes to default")
        assertNull(sink.entries.firstOrNull())
    }
}
