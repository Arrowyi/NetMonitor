package com.telenav.netmonitor

import indi.arrowyi.netscope.hook.SocketStats
import indi.arrowyi.netscope.hook.SocketTotalStats
import indi.arrowyi.netscope.sdk.ApiStats
import indi.arrowyi.netscope.sdk.Status
import indi.arrowyi.netscope.sdk.TotalStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetDataRepositoryTest {

    private fun makeStats(
        host: String,
        path: String = "/",
        tx: Long = 0L,
        rx: Long = 0L,
        conn: Int = 0,
    ) = ApiStats(
        host = host,
        path = path,
        txBytesTotal = tx,
        rxBytesTotal = rx,
        txBytesInterval = 0L,
        rxBytesInterval = 0L,
        connCountTotal = conn,
        connCountInterval = 0,
        lastActiveMs = 0L,
    )

    private fun repo(
        totalStats: TotalStats? = TotalStats(0L, 0L, 0),
        apiStats: List<ApiStats>? = emptyList(),
        socketStats: List<SocketStats> = emptyList(),
        socketTotal: SocketTotalStats = SocketTotalStats(0L, 0L, 0),
        hookActive: Boolean = false,
        status: Status = Status.ACTIVE,
        networkUsageSource: NetworkUsageSource? = null,
    ) = NetDataRepository(
        totalStatsProvider = { totalStats },
        apiStatsProvider = { apiStats },
        socketStatsProvider = { socketStats },
        socketTotalProvider = { socketTotal },
        socketHookActiveProvider = { hookActive },
        statusProvider = { status },
        networkUsageSource = networkUsageSource,
    )

    @Test
    fun `ACTIVE with zero totals returns empty layerCUsages and zero gap`() {
        val data = repo().getLatestData()
        assertEquals(0L, data.layerATxBytes)
        assertEquals(0L, data.layerGapTxBytes)
        assertEquals(0L, data.layerGapRxBytes)
        assertEquals(0L, data.layerSocketTxBytes)
        assertEquals(0, data.apiCount)
        assertTrue(data.layerBApis.isEmpty())
        assertTrue(data.layerCUsages.isEmpty())
        assertTrue(data.layerSocketEndpoints.isEmpty())
    }

    @Test
    fun `apis sorted by totalBytes descending`() {
        val data = repo(
            apiStats = listOf(
                makeStats("a.com", path = "/v1/x", tx = 100L, rx = 200L),
                makeStats("b.com", path = "/v1/y", tx = 500L, rx = 100L),
                makeStats("c.com", path = "/v1/z", tx = 10L, rx = 10L),
            ),
        ).getLatestData()
        assertEquals("b.com/v1/y", data.layerBApis[0].apiKey)
        assertEquals("a.com/v1/x", data.layerBApis[1].apiKey)
        assertEquals("c.com/v1/z", data.layerBApis[2].apiKey)
    }

    @Test
    fun `gap is A minus B only (no cpp)`() {
        val data = repo(
            totalStats = TotalStats(txTotal = 10_000L, rxTotal = 20_000L, connCountTotal = 5),
            apiStats = listOf(
                makeStats("a.com", path = "/v1/a", tx = 1_000L, rx = 2_000L, conn = 2),
                makeStats("b.com", path = "/v1/b", tx = 3_000L, rx = 4_000L, conn = 3),
            ),
        ).getLatestData()
        assertEquals(10_000L, data.layerATxBytes)
        assertEquals(4_000L, data.layerBTxBytes)
        assertEquals(6_000L, data.layerGapTxBytes) // 10_000 - 4_000
        assertEquals(14_000L, data.layerGapRxBytes) // 20_000 - (2_000 + 4_000)
    }

    @Test
    fun `gap clamps at zero when B exceeds A`() {
        val data = repo(
            totalStats = TotalStats(txTotal = 1_000L, rxTotal = 1_000L, connCountTotal = 0),
            apiStats = listOf(makeStats("hot.com", path = "/v1/x", tx = 5_000L, rx = 5_000L)),
        ).getLatestData()
        assertEquals(0L, data.layerGapTxBytes)
        assertEquals(0L, data.layerGapRxBytes)
    }

    @Test
    fun `NetworkUsageSource events populate layerCUsages`() {
        var capturedListener: ((List<SubsystemUsage>) -> Unit)? = null
        val source = object : NetworkUsageSource {
            override fun subscribe(listener: (List<SubsystemUsage>) -> Unit) {
                capturedListener = listener
            }
            override fun unsubscribe() {}
        }
        val repository = repo(networkUsageSource = source)
        assertTrue(repository.getLatestData().layerCUsages.isEmpty())
        capturedListener!!.invoke(listOf(
            SubsystemUsage("SEARCH", uploadBytes = 100L, downloadBytes = 500L),
            SubsystemUsage("ROUTING", uploadBytes = 50L, downloadBytes = 200L),
        ))
        val data = repository.getLatestData()
        assertEquals(2, data.layerCUsages.size)
        assertEquals("SEARCH", data.layerCUsages[0].subsystem)
        assertEquals(600L, data.layerCUsages[0].totalBytes)
    }

    @Test
    fun `layerCUsages sorted by totalBytes descending`() {
        var capturedListener: ((List<SubsystemUsage>) -> Unit)? = null
        val source = object : NetworkUsageSource {
            override fun subscribe(listener: (List<SubsystemUsage>) -> Unit) {
                capturedListener = listener
            }
            override fun unsubscribe() {}
        }
        val repository = repo(networkUsageSource = source)
        capturedListener!!.invoke(listOf(
            SubsystemUsage("TRAFFIC", uploadBytes = 10L, downloadBytes = 20L),
            SubsystemUsage("SEARCH", uploadBytes = 1000L, downloadBytes = 5000L),
            SubsystemUsage("ROUTING", uploadBytes = 100L, downloadBytes = 200L),
        ))
        val usages = repository.getLatestData().layerCUsages
        assertEquals("SEARCH", usages[0].subsystem)
        assertEquals("ROUTING", usages[1].subsystem)
        assertEquals("TRAFFIC", usages[2].subsystem)
    }

    @Test
    fun `networkUsageCount reflects layerCUsages size`() {
        var capturedListener: ((List<SubsystemUsage>) -> Unit)? = null
        val source = object : NetworkUsageSource {
            override fun subscribe(listener: (List<SubsystemUsage>) -> Unit) {
                capturedListener = listener
            }
            override fun unsubscribe() {}
        }
        val repository = repo(networkUsageSource = source)
        capturedListener!!.invoke(listOf(
            SubsystemUsage("SEARCH", 10L, 20L),
            SubsystemUsage("ROUTING", 5L, 10L),
        ))
        assertEquals(2, repository.getLatestData().networkUsageCount)
    }

    @Test
    fun `NOT_INITIALIZED makes aggregates unavailable`() {
        val data = repo(status = Status.NOT_INITIALIZED).getLatestData()
        assertEquals(Status.NOT_INITIALIZED, data.status)
        assertNull(data.layerATxBytes)
        assertNull(data.layerGapTxBytes)
        assertNull(data.layerSocketTxBytes)
        assertTrue(data.layerBApis.isEmpty())
        assertTrue(data.layerCUsages.isEmpty())
    }

    @Test
    fun `ACTIVE but totalStats is null leaves A and gap unavailable`() {
        val data = repo(
            totalStats = null,
            apiStats = listOf(makeStats("a.com", path = "/x", tx = 100L, rx = 200L, conn = 1)),
        ).getLatestData()
        assertEquals(Status.ACTIVE, data.status)
        assertNull(data.layerATxBytes)
        assertEquals(100L, data.layerBTxBytes)
        assertNull(data.layerBConnCount)
        assertNull(data.layerGapTxBytes)
    }

    @Test
    fun `ACTIVE but apiStats is null leaves B and gap unavailable`() {
        val data = repo(
            totalStats = TotalStats(txTotal = 1_000L, rxTotal = 2_000L, connCountTotal = 4),
            apiStats = null,
        ).getLatestData()
        assertNull(data.layerBTxBytes)
        assertEquals(4, data.layerBConnCount)
        assertNull(data.layerGapTxBytes)
        assertTrue(data.layerBApis.isEmpty())
    }

    @Test
    fun `unavailable factory produces empty layerCUsages`() {
        val u = NetDataRepository.AggregatedData.unavailable(Status.NOT_INITIALIZED)
        assertNull(u.layerSocketTxBytes)
        assertTrue(u.layerSocketEndpoints.isEmpty())
        assertTrue(u.layerCUsages.isEmpty())
    }

    @Test
    fun `blank-host bucket is preserved for layer B`() {
        val repository = NetDataRepository(
            totalStatsProvider = { TotalStats(0L, 500_000L, 22) },
            apiStatsProvider = {
                listOf(
                    makeStats(host = " ", path = "/unknown", tx = 0L, rx = 465_000L, conn = 18),
                    makeStats(host = "pangueu.telenav.com", path = "/v1", tx = 0L, rx = 1_400L, conn = 4),
                )
            },
            statusProvider = { Status.ACTIVE },
            unknownApiLabel = "(UNKNOWN)",
        )
        val data = repository.getLatestData()
        assertEquals(2, data.apiCount)
        assertEquals("(UNKNOWN)", data.layerBApis[0].apiKey)
    }

    @Test
    fun `socket rows pass through`() {
        val data = repo(
            socketStats = listOf(
                // v3.2.8 SocketStats(remoteAddress, protocol, txBytes, rxBytes, connectionCount).
                SocketStats("203.0.113.1:443", "HTTPS", txBytes = 100L, rxBytes = 900L, connectionCount = 2),
            ),
            socketTotal = SocketTotalStats(100L, 900L, 2),
        ).getLatestData()
        assertEquals(1, data.layerSocketEndpoints.size)
        assertEquals("203.0.113.1:443", data.layerSocketEndpoints[0].remoteAddress)
        assertEquals("HTTPS", data.layerSocketEndpoints[0].protocol)
    }

    @Test
    fun `ApiTrafficStats totalBytes sums tx and rx`() {
        val stats = ApiTrafficStats(
            apiKey = "x.com/v1/foo",
            host = "x.com",
            path = "/v1/foo",
            txBytesTotal = 1024L,
            rxBytesTotal = 2048L,
        )
        assertEquals(3072L, stats.totalBytes)
    }

    @Test
    fun `destroy delegates unsubscribe to source`() {
        var unsubscribeCalled = false
        val source = object : NetworkUsageSource {
            override fun subscribe(listener: (List<SubsystemUsage>) -> Unit) {}
            override fun unsubscribe() { unsubscribeCalled = true }
        }
        repo(networkUsageSource = source).destroy()
        assertTrue(unsubscribeCalled)
    }

    private fun repoWithLookup(
        lookup: (String) -> List<String>,
        socketStats: List<SocketStats> = emptyList(),
        socketTotal: SocketTotalStats = SocketTotalStats(0L, 0L, 0),
    ) = NetDataRepository(
        totalStatsProvider = { TotalStats(0L, 0L, 0) },
        apiStatsProvider = { emptyList() },
        socketStatsProvider = { socketStats },
        socketTotalProvider = { socketTotal },
        socketHookActiveProvider = { false },
        statusProvider = { Status.ACTIVE },
        knownHostsLookup = lookup,
    )

    @Test
    fun `extractIp ipv4 with port`() {
        assertEquals("1.2.3.4", NetDataRepository.extractIp("1.2.3.4:443"))
    }

    @Test
    fun `extractIp ipv6 bracketed with port`() {
        assertEquals("2001:db8::1", NetDataRepository.extractIp("[2001:db8::1]:443"))
    }

    @Test
    fun `extractIp ipv6 bracketed no port`() {
        assertEquals("2001:db8::1", NetDataRepository.extractIp("[2001:db8::1]"))
    }

    @Test
    fun `extractIp ipv4 no port returns input`() {
        assertEquals("1.2.3.4", NetDataRepository.extractIp("1.2.3.4"))
    }

    @Test
    fun `extractIp bare ipv6 (no brackets, no port) returns input`() {
        assertEquals("2001:db8::1", NetDataRepository.extractIp("2001:db8::1"))
    }

    @Test
    fun `extractIp empty string returns null`() {
        assertEquals(null, NetDataRepository.extractIp(""))
    }

    @Test
    fun `extractIp malformed input returns input as best-effort key`() {
        // Garbage in -> garbage out: returned as-is so the lookup misses cleanly.
        assertEquals("garbage", NetDataRepository.extractIp("garbage"))
    }

    @Test
    fun `socket rows have empty domains when no lookup is provided`() {
        val data = repo(
            socketStats = listOf(
                SocketStats("1.2.3.4:443", "HTTPS", txBytes = 100L, rxBytes = 900L, connectionCount = 2),
            ),
        ).getLatestData()
        assertTrue(data.layerSocketEndpoints[0].domains.isEmpty())
    }

    @Test
    fun `socket rows are enriched with domains from lookup`() {
        val map = mapOf("1.2.3.4" to listOf("api.example.com"))
        val data = repoWithLookup(
            lookup = { ip -> map[ip].orEmpty() },
            socketStats = listOf(
                SocketStats("1.2.3.4:443", "HTTPS", txBytes = 100L, rxBytes = 900L, connectionCount = 2),
            ),
        ).getLatestData()
        assertEquals(listOf("api.example.com"), data.layerSocketEndpoints[0].domains)
    }

    @Test
    fun `socket rows enrich works for ipv6 bracketed remoteAddress`() {
        val map = mapOf("2001:db8::1" to listOf("v6.example.com"))
        val data = repoWithLookup(
            lookup = { ip -> map[ip].orEmpty() },
            socketStats = listOf(
                SocketStats("[2001:db8::1]:443", "HTTPS", txBytes = 0L, rxBytes = 0L, connectionCount = 1),
            ),
        ).getLatestData()
        assertEquals(listOf("v6.example.com"), data.layerSocketEndpoints[0].domains)
    }

    @Test
    fun `socket rows have empty domains when ip not in lookup map`() {
        val map = mapOf("1.2.3.4" to listOf("api.example.com"))
        val data = repoWithLookup(
            lookup = { ip -> map[ip].orEmpty() },
            socketStats = listOf(
                SocketStats("9.9.9.9:443", "HTTPS", txBytes = 0L, rxBytes = 0L, connectionCount = 0),
            ),
        ).getLatestData()
        assertTrue(data.layerSocketEndpoints[0].domains.isEmpty())
    }

    @Test
    fun `socket rows survive lookup throwing`() {
        val data = repoWithLookup(
            lookup = { throw RuntimeException("boom") },
            socketStats = listOf(
                SocketStats("1.2.3.4:443", "HTTPS", txBytes = 0L, rxBytes = 0L, connectionCount = 0),
            ),
        ).getLatestData()
        assertTrue(data.layerSocketEndpoints[0].domains.isEmpty())
    }
}
