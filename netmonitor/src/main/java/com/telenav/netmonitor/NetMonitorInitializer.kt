package com.telenav.netmonitor

import android.content.Context
import android.os.Build
import androidx.startup.Initializer

class NetMonitorInitializer : Initializer<Unit> {

    override fun create(context: Context) {
        NMLog.i(NMSub.INIT, "create called, SDK_INT=${Build.VERSION.SDK_INT}")

        if (!isNetMonitorEnabled()) {
            NMLog.w(NMSub.INIT, "disabled via debug.netmonitor.enabled — skipping service start")
            return
        }

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                NetMonitorService.start(context)
                NMLog.i(NMSub.INIT, "service start requested")
            } catch (t: Throwable) {
                NMLog.e(NMSub.INIT, "service start failed", t)
            }
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/**
 * Kill switch shared by [NetMonitorInitializer] (skips the service) and
 * [NetMonitor.init] (skips activity-lifecycle registration).
 *
 * `adb shell setprop debug.netmonitor.enabled 0` disables the entire module
 * at process start: no service, no in-app overlay.
 */
internal fun isNetMonitorEnabled(): Boolean {
    val raw = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java, String::class.java)
        (get.invoke(null, "debug.netmonitor.enabled", "1") as String).trim()
    } catch (_: Throwable) {
        "1"
    }
    return raw != "0" && !raw.equals("false", ignoreCase = true)
}
