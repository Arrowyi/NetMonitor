package com.telenav.netmonitor

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import indi.arrowyi.netscope.hook.NetScopeHook
import indi.arrowyi.netscope.sdk.NetScope
import java.util.concurrent.atomic.AtomicBoolean

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
 * NetMonitor.preInit(this)                       // stage 1 — before super.onCreate()
 * super.onCreate()
 * NetMonitor.init(this, MainActivity::class.java) // stage 2 — after super.onCreate()
 * // optionally, once upstream services are ready:
 * NetMonitor.setNetworkUsageSource(mySource)
 * ```
 *
 * The panel attaches only to the Activity class passed to [init]; other
 * Activities (cluster, settings, etc.) are not affected. No
 * `SYSTEM_ALERT_WINDOW` permission is required.
 */
object NetMonitor {

    private val callbacksRegistered = AtomicBoolean(false)

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
     * [android.app.Application.onCreate]. Completes monitoring setup, aligns
     * internal baselines, and registers an [Application.ActivityLifecycleCallbacks]
     * that auto-attaches the embedded overlay whenever an instance of
     * [hostActivity] is in the foreground.
     *
     * @param hostActivity     Activity class the embedded panel will attach to.
     *                         Other Activities are ignored.
     * @param logIntervalSec   internal periodic-logging cadence in seconds.
     */
    @JvmStatic
    @JvmOverloads
    fun init(
        context: Context,
        hostActivity: Class<out Activity>,
        logIntervalSec: Int = 30,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { NetScopeHook.reinspect() }
            runCatching { NetScopeHook.clearAllStats() }
        }
        runCatching {
            NetScope.init(context)
            NetScope.setLogInterval(logIntervalSec)
        }

        NetMonitorConfig.hostActivityClass = hostActivity

        if (!isNetMonitorEnabled()) {
            NMLog.w(NMSub.INIT, "disabled via debug.netmonitor.enabled — skipping overlay attach")
            return
        }

        val app = context.applicationContext as? Application ?: run {
            NMLog.e(NMSub.INIT, "application context unavailable — overlay will not attach")
            return
        }
        if (callbacksRegistered.compareAndSet(false, true)) {
            app.registerActivityLifecycleCallbacks(HostActivityLifecycleCallbacks)
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

/**
 * Routes [Activity] start/stop events to [EmbeddedOverlayManager] when the
 * activity matches [NetMonitorConfig.hostActivityClass]. Registered once per
 * process from [NetMonitor.init].
 */
private object HostActivityLifecycleCallbacks : Application.ActivityLifecycleCallbacks {

    private fun isHost(activity: Activity): Boolean {
        val hostClass = NetMonitorConfig.hostActivityClass ?: return false
        return hostClass.isInstance(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        if (isHost(activity)) EmbeddedOverlayManager.attach(activity)
    }

    override fun onActivityStopped(activity: Activity) {
        if (isHost(activity)) EmbeddedOverlayManager.detach()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
