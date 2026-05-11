package com.telenav.netmonitor

import android.net.TrafficStats
import android.os.Process
import indi.arrowyi.netscope.hook.NetScopeHook
import indi.arrowyi.netscope.hook.SocketStats
import indi.arrowyi.netscope.hook.SocketTotalStats
import indi.arrowyi.netscope.sdk.ApiStats
import indi.arrowyi.netscope.sdk.NetScope
import indi.arrowyi.netscope.sdk.Status
import indi.arrowyi.netscope.sdk.TotalStats

/**
 * Aggregates NetScope v3.2.8 telemetry for the floating window.
 *
 * Layer model:
 *  - A: kernel UID total (`TrafficStats`)
 *  - B: Java AOP per (host + path)
 *  - C: DataCollector NetworkUsage per C++ subsystem, provided via [NetworkUsageSource]
 *  - D: socket hook per IP:port (with port-inferred protocol label)
 *
 * `Gap = max(A − B, 0)` is computed but no longer rendered (kept for
 * diagnostics callers). See SDK README "Layer D captures C++ HTTP traffic
 * at the wire level" — D is the comprehensive application-side view.
 */
class NetDataRepository(
    private val totalStatsProvider: () -> TotalStats? = {
        try { NetScope.getTotalStats() } catch (_: Throwable) { null }
    },
    private val apiStatsProvider: () -> List<ApiStats>? = {
        try { NetScope.getApiStats() } catch (_: Throwable) { null }
    },
    private val socketStatsProvider: () -> List<SocketStats> = {
        try { NetScopeHook.getSocketStats() } catch (_: Throwable) { emptyList() }
    },
    private val socketTotalProvider: () -> SocketTotalStats = {
        try { NetScopeHook.getSocketTotalStats() } catch (_: Throwable) { SocketTotalStats(0L, 0L, 0) }
    },
    private val socketHookActiveProvider: () -> Boolean = {
        try { NetScopeHook.isActive } catch (_: Throwable) { false }
    },
    private val statusProvider: () -> Status = {
        try { NetScope.status() } catch (_: Throwable) { Status.NOT_INITIALIZED }
    },
    private val unknownApiLabel: String = "（未识别 host）",
    private val knownHostsLookup: ((String) -> List<String>)? = null,
    networkUsageSource: NetworkUsageSource? = null,
) {

    @Volatile private var _latestCUsages: List<SubsystemUsage> = emptyList()
    private val _trafficStatsDiagDone = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var _avdLastLogMs: Long = 0L

    companion object {
        private const val AVD_LOG_INTERVAL_MS = 30_000L

        /**
         * Extracts the bare IP from a NetScope `remoteAddress` string.
         * Handles `1.2.3.4:443`, `[v6]:443`, `[v6]`, bare v4, bare v6, and
         * empty input. Returns `null` for empty; returns the input as-is
         * for unparseable garbage so the caller's lookup misses cleanly.
         */
        @JvmStatic
        fun extractIp(remoteAddress: String): String? {
            if (remoteAddress.isEmpty()) return null
            if (remoteAddress.startsWith("[")) {
                val end = remoteAddress.indexOf(']')
                if (end > 1) return remoteAddress.substring(1, end)
                return remoteAddress
            }
            // Bare IPv6 has 2+ colons and no port suffix; return as-is.
            val colonCount = remoteAddress.count { it == ':' }
            if (colonCount > 1) return remoteAddress
            val lastColon = remoteAddress.lastIndexOf(':')
            return if (lastColon < 0) remoteAddress else remoteAddress.substring(0, lastColon)
        }
    }

    // ctorSource may be null when service starts before Application.onCreate
    // (androidx.startup ordering); ensureSubscribed() picks it up lazily.
    private val ctorSource: NetworkUsageSource? = networkUsageSource
    @Volatile private var subscribedSource: NetworkUsageSource? = null

    init {
        ctorSource?.let { subscribe(it) }
    }

    private fun subscribe(s: NetworkUsageSource) {
        s.subscribe { usages -> _latestCUsages = usages.sortedByDescending { it.totalBytes } }
        subscribedSource = s
    }

    private fun ensureSubscribed() {
        if (subscribedSource != null) return
        val s = NetMonitorConfig.networkUsageSource ?: return
        subscribe(s)
    }

    data class AggregatedData(
        val status: Status,
        val layerATxBytes: Long?,
        val layerARxBytes: Long?,
        val layerBTxBytes: Long?,
        val layerBRxBytes: Long?,
        val layerBConnCount: Int?,
        val layerBApis: List<ApiTrafficStats>,
        val layerCUsages: List<SubsystemUsage>,
        val layerGapTxBytes: Long?,
        val layerGapRxBytes: Long?,
        val layerSocketTxBytes: Long?,
        val layerSocketRxBytes: Long?,
        val layerSocketConnCount: Int?,
        val layerSocketEndpoints: List<SocketTrafficRow>,
        val socketHookActive: Boolean,
    ) {
        val layerATotalBytes: Long? get() = addOrNull(layerATxBytes, layerARxBytes)
        val layerBTotalBytes: Long? get() = addOrNull(layerBTxBytes, layerBRxBytes)
        val layerCTxBytes: Long get() = layerCUsages.sumOf { it.uploadBytes }
        val layerCRxBytes: Long get() = layerCUsages.sumOf { it.downloadBytes }
        val layerCTotalBytes: Long get() = layerCUsages.sumOf { it.totalBytes }
        val layerGapTotalBytes: Long? get() = addOrNull(layerGapTxBytes, layerGapRxBytes)
        val layerSocketTotalBytes: Long? get() = addOrNull(layerSocketTxBytes, layerSocketRxBytes)
        // B + D combined total is intentionally NOT exposed: per SDK
        // README, summing them double-counts every request that flows
        // through both layers. Use Layer D as the application-side view.
        val apiCount: Int get() = layerBApis.size
        val networkUsageCount: Int get() = layerCUsages.size

        companion object {
            private fun addOrNull(a: Long?, b: Long?): Long? =
                if (a == null || b == null) null else a + b

            fun unavailable(status: Status) = AggregatedData(
                status = status,
                layerATxBytes = null,
                layerARxBytes = null,
                layerBTxBytes = null,
                layerBRxBytes = null,
                layerBConnCount = null,
                layerBApis = emptyList(),
                layerCUsages = emptyList(),
                layerGapTxBytes = null,
                layerGapRxBytes = null,
                layerSocketTxBytes = null,
                layerSocketRxBytes = null,
                layerSocketConnCount = null,
                layerSocketEndpoints = emptyList(),
                socketHookActive = false,
            )
        }
    }

    fun getLatestData(): AggregatedData {
        ensureSubscribed()
        val cUsages = _latestCUsages   // capture once before any other reads

        // One-shot device-capability dump (TrafficStats may be UNSUPPORTED on
        // some pre-Q OEM kernels). Diagnostic only — never gate the result.
        if (_trafficStatsDiagDone.compareAndSet(false, true)) {
            try {
                val uid = Process.myUid()
                val rawTx = TrafficStats.getUidTxBytes(uid)
                val rawRx = TrafficStats.getUidRxBytes(uid)
                NMLog.i(NMSub.DIAG, "TrafficStats raw: uid=$uid tx=$rawTx rx=$rawRx " +
                        "(UNSUPPORTED=${TrafficStats.UNSUPPORTED})")
            } catch (_: Throwable) { /* JVM unit test — no Android framework */ }
        }

        val status = statusProvider()
        if (status != Status.ACTIVE) {
            return AggregatedData.unavailable(status)
        }

        val totalStats = totalStatsProvider()
        val layerATx = totalStats?.txTotal
        val layerARx = totalStats?.rxTotal
        val layerBConn = totalStats?.connCountTotal

        val rawApis = apiStatsProvider()
        val apis = rawApis
            ?.map { it.toUiModelB() }
            ?.sortedByDescending { it.totalBytes }
            ?: emptyList()
        val layerBTx = rawApis?.sumOf { it.txBytesTotal }
        val layerBRx = rawApis?.sumOf { it.rxBytesTotal }

        val layerGapTx = gap1d(layerATx, layerBTx)
        val layerGapRx = gap1d(layerARx, layerBRx)

        val hookActive = socketHookActiveProvider()
        val sockTotal = socketTotalProvider()
        val sockets = socketStatsProvider()
            .map { stat ->
                val domains: List<String> = knownHostsLookup?.let { lookup ->
                    val ip = extractIp(stat.remoteAddress)
                    if (ip == null) emptyList()
                    else try { lookup(ip) } catch (_: Throwable) { emptyList() }
                } ?: emptyList()
                SocketTrafficRow(
                    remoteAddress = stat.remoteAddress,
                    protocol = stat.protocol,
                    txBytes = stat.txBytes,
                    rxBytes = stat.rxBytes,
                    connectionCount = stat.connectionCount,
                    domains = domains,
                )
            }
            .sortedByDescending { it.totalBytes }

        // 30s-throttled Layer A vs Layer D side-by-side dump.
        // Negative `D-A` is normal (A includes IP/TCP/TLS headers, D doesn't);
        // positive D-A indicates a hook over-count regression worth checking.
        // Capped at 64 endpoint lines per tick so a pathological snapshot
        // can't flood logcat.
        val nowMs = System.currentTimeMillis()
        if (nowMs - _avdLastLogMs >= AVD_LOG_INTERVAL_MS) {
            _avdLastLogMs = nowMs
            try {
                val aTx = layerATx ?: -1L
                val aRx = layerARx ?: -1L
                val dTx = sockTotal.txTotal
                val dRx = sockTotal.rxTotal
                val dEndpoints = sockets.size
                val diffTx = dTx - aTx
                val diffRx = dRx - aRx
                NMLog.i(
                    NMSub.AVD,
                    "A_tx=$aTx A_rx=$aRx | D_tx=$dTx D_rx=$dRx | " +
                            "D-A_tx=$diffTx D-A_rx=$diffRx | " +
                            "D_conn=${sockTotal.connectionCount} D_endpoints=$dEndpoints hookActive=$hookActive"
                )
                val avdDumpCap = NetMonitorConfig.maxVisibleSockets.coerceIn(1, 64)
                sockets.take(avdDumpCap).forEachIndexed { i, ep ->
                    NMLog.i(
                        NMSub.AVD,
                        "  ep[$i] [${ep.protocol}] ${ep.remoteAddress} " +
                                "tx=${ep.txBytes} rx=${ep.rxBytes} conn=${ep.connectionCount}"
                    )
                }
                if (sockets.size > avdDumpCap) {
                    NMLog.i(NMSub.AVD, "  ... ${sockets.size - avdDumpCap} more endpoints elided (cap=$avdDumpCap)")
                }
            } catch (_: Throwable) { /* defensive — log sink may be misbehaving */ }
        }

        return AggregatedData(
            status = status,
            layerATxBytes = layerATx,
            layerARxBytes = layerARx,
            layerBTxBytes = layerBTx,
            layerBRxBytes = layerBRx,
            layerBConnCount = layerBConn,
            layerBApis = apis,
            layerCUsages = cUsages,
            layerGapTxBytes = layerGapTx,
            layerGapRxBytes = layerGapRx,
            layerSocketTxBytes = sockTotal.txTotal,
            layerSocketRxBytes = sockTotal.rxTotal,
            layerSocketConnCount = sockTotal.connectionCount,
            layerSocketEndpoints = sockets,
            socketHookActive = hookActive,
        )
    }

    fun destroy() {
        subscribedSource?.unsubscribe()
    }

    private fun gap1d(a: Long?, bSum: Long?): Long? {
        if (a == null || bSum == null) return null
        return (a - bSum).coerceAtLeast(0L)
    }

    private fun ApiStats.toUiModelB(): ApiTrafficStats = ApiTrafficStats(
        apiKey = if (host.isBlank()) unknownApiLabel else key,
        host = host,
        path = path,
        txBytesTotal = txBytesTotal,
        rxBytesTotal = rxBytesTotal,
        txBytesInterval = txBytesInterval,
        rxBytesInterval = rxBytesInterval,
        connCountTotal = connCountTotal,
        connCountInterval = connCountInterval,
        lastActiveMs = lastActiveMs,
    )
}
