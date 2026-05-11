package com.telenav.netmonitor

import android.content.Context
import android.os.Build
import indi.arrowyi.netscope.hook.NetScopeHook
import indi.arrowyi.netscope.sdk.NetScope

/**
 * NetMonitor public facade — the single entry point for host applications.
 *
 * Internal implementation details (the underlying network-monitoring SDKs and
 * native hooks) are intentionally hidden behind this object. Host code should
 * only ever reference [NetMonitor], [NetMonitorConfig], [NetworkUsageSource],
 * [SubsystemUsage] and [NetMonitorLog].
 *
 * Typical integration:
 * ```
 * NetMonitor.preInit(this)   // stage 1 — before super.onCreate()
 * super.onCreate()
 * NetMonitor.init(this)      // stage 2 — after super.onCreate()
 * // optionally, once upstream services are ready:
 * NetMonitor.setNetworkUsageSource(mySource)
 * ```
 */
object NetMonitor {

    /**
     * Stage 1 attach. MUST be invoked as the very first statement of
     * [android.app.Application.onCreate], before `super.onCreate()`.
     *
     * Some monitoring mechanisms need to install themselves before any native
     * libraries are loaded into the process, which is why a pre-super stage
     * exists. On API < Q this is a no-op and returns `false`.
     *
     * @return `true` if pre-init succeeded; `false` if it could not run (older
     *         API level, or the underlying hook reported failure).
     */
    @JvmStatic
    fun preInit(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching {
            NetScopeHook.init(context)
            NetScopeHook.start()
        }.getOrDefault(false)
    }

    /**
     * Stage 2 attach. MUST be invoked after `super.onCreate()` returns in
     * [android.app.Application.onCreate]. Completes monitoring setup and
     * aligns internal baselines so subsequent measurements are consistent.
     *
     * @param logIntervalSec internal periodic-logging cadence in seconds.
     */
    @JvmStatic
    @JvmOverloads
    fun init(context: Context, logIntervalSec: Int = 30) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { NetScopeHook.reinspect() }
            runCatching { NetScopeHook.clearAllStats() }
        }
        runCatching {
            NetScope.init(context)
            NetScope.setLogInterval(logIntervalSec)
        }
    }

    /**
     * Inject a custom subsystem-level network usage data source.
     *
     * NetMonitor itself cannot observe how much traffic each application
     * subsystem is producing — that information must be supplied by the host
     * via this SPI. Implement [NetworkUsageSource] and pass it in once your
     * upstream services are initialised. Passing `null` clears the source.
     */
    @JvmStatic
    fun setNetworkUsageSource(source: NetworkUsageSource?) {
        NetMonitorConfig.networkUsageSource = source
    }

    /** Redirect NetMonitor's internal logging to a custom sink. */
    @JvmStatic
    fun setLogger(logger: NetMonitorLog) {
        NetMonitorConfig.logger = logger
    }
}
