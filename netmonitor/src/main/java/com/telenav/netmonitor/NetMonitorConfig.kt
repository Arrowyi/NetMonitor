package com.telenav.netmonitor

import android.app.Activity

object NetMonitorConfig {
    var refreshIntervalMs: Long = 2000L
    var maxVisibleApis: Int = 10

    /**
     * Cap on Layer D socket endpoint rows. `Int.MAX_VALUE` = no cap (the
     * floating window's RecyclerView is scrollable). OEMs may shrink it.
     */
    var maxVisibleSockets: Int = Int.MAX_VALUE

    /** Cap on Layer C NetworkUsage subsystem rows. */
    var maxVisibleNetworkUsage: Int = 8

    var expandedAreaFraction: Double = 0.64
    var expandedMinWidthDp: Int = 400
    var expandedMinHeightDp: Int = 300
    var expandedMaxWidthDp: Int = 840
    var expandedMaxHeightDp: Int = 900

    /**
     * The Activity class the embedded overlay attaches to. Set via
     * [NetMonitor.init]. When `null` (init not yet called or no host
     * activity provided), no overlay is attached and the data path is a
     * no-op for the UI side.
     */
    var hostActivityClass: Class<out Activity>? = null

    /** Optional DataCollector-based per-subsystem traffic source (Layer C). */
    var networkUsageSource: NetworkUsageSource? = null

    /**
     * Pluggable log sink — see [NetMonitorLog]. Defaults to [AndroidNetMonitorLog],
     * which routes to `android.util.Log` under TAG = `NetMonitor`. Hosts that
     * need to redirect logs (file, telemetry, test capture) can replace it at
     * any point; module call sites resolve through this field on every emit.
     */
    var logger: NetMonitorLog = AndroidNetMonitorLog
}
