package com.telenav.netmonitor

import android.util.Log

/**
 * Pluggable log sink for the NetMonitor module.
 *
 * Every module log is emitted with TAG = [TAG] and a `[sub] ` prefix in the
 * message body, so a host can capture every NetMonitor line with one filter:
 *
 *     adb logcat -s NetMonitor:*
 *
 * and narrow further by sub-system with a body grep, e.g. `| grep "\[DNS\]"`.
 *
 * Hosts that need to redirect logs (file sink, telemetry pipeline, JVM tests)
 * can install a custom implementation via [NetMonitorConfig.logger]. The
 * default routes to [android.util.Log] and is JVM-test-safe (calls are wrapped
 * in `runCatching` because the framework class is not mocked in plain JVM).
 */
interface NetMonitorLog {
    fun i(sub: String, msg: String)
    fun w(sub: String, msg: String, t: Throwable? = null)
    fun e(sub: String, msg: String, t: Throwable? = null)
    fun d(sub: String, msg: String) {}

    companion object {
        /** Single grep tag for the entire module. */
        const val TAG: String = "NetMonitor"
    }
}

/** Default sink: [android.util.Log] with try-safety for JVM unit tests. */
internal object AndroidNetMonitorLog : NetMonitorLog {
    override fun i(sub: String, msg: String) {
        runCatching { Log.i(NetMonitorLog.TAG, fmt(sub, msg)) }
    }

    override fun w(sub: String, msg: String, t: Throwable?) {
        runCatching {
            if (t != null) Log.w(NetMonitorLog.TAG, fmt(sub, msg), t)
            else Log.w(NetMonitorLog.TAG, fmt(sub, msg))
        }
    }

    override fun e(sub: String, msg: String, t: Throwable?) {
        runCatching {
            if (t != null) Log.e(NetMonitorLog.TAG, fmt(sub, msg), t)
            else Log.e(NetMonitorLog.TAG, fmt(sub, msg))
        }
    }

    override fun d(sub: String, msg: String) {
        runCatching { Log.d(NetMonitorLog.TAG, fmt(sub, msg)) }
    }

    private fun fmt(sub: String, msg: String): String = "[$sub] $msg"
}

/**
 * Module-internal accessor; resolves the current [NetMonitorLog] on every call
 * so a host can swap the sink at any point in the lifecycle.
 */
internal object NMLog {
    fun i(sub: String, msg: String) = NetMonitorConfig.logger.i(sub, msg)
    fun w(sub: String, msg: String, t: Throwable? = null) = NetMonitorConfig.logger.w(sub, msg, t)
    fun e(sub: String, msg: String, t: Throwable? = null) = NetMonitorConfig.logger.e(sub, msg, t)
    fun d(sub: String, msg: String) = NetMonitorConfig.logger.d(sub, msg)
}

/**
 * Sub-system identifiers used in `[sub]` log prefixes. Keep in sync with the
 * documentation in [NetMonitorLog]; centralised here to avoid typo-driven
 * tag fragmentation across files.
 */
internal object NMSub {
    const val INIT = "Init"           // NetMonitorInitializer
    const val SERVICE = "Service"     // NetMonitorService
    const val WINDOW = "Window"       // EmbeddedOverlayManager
    const val DNS = "DNS"             // KnownHostsLoader, KnownHostsResolver
    const val DIAG = "Diag"           // NetDataRepository TrafficStats one-shot
    const val AVD = "AvD"             // NetDataRepository A vs D periodic dump
}
