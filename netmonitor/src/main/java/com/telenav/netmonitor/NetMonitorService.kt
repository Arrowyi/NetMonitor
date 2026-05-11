package com.telenav.netmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Foreground service that owns the floating-window UI and polls
 * [NetDataRepository] at [NetMonitorConfig.refreshIntervalMs] cadence.
 *
 * Does **not** call `NetScope.init()` — that's the host app's job (HMI's
 * `ProductApplication.onCreate`). NetScope is read-only from here.
 *
 * Includes a crash-loop breaker: if the service has been (re)created
 * [BREAKER_THRESHOLD]+ times within [BREAKER_WINDOW_MS], we suspend ourselves
 * so an unrelated host-app crash loop isn't masked by a resident service.
 */
class NetMonitorService : Service() {

    private lateinit var floatingWindowManager: FloatingWindowManager
    private lateinit var repository: NetDataRepository
    private var knownHostsResolver: KnownHostsResolver? = null
    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            floatingWindowManager.updateData(repository.getLatestData())
            handler.postDelayed(this, NetMonitorConfig.refreshIntervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    /** Returns true once the recent-restarts window crosses the breaker threshold. */
    private fun tripCrashLoopBreaker(): Boolean {
        return try {
            val sp = getSharedPreferences(PREFS_BREAKER, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val raw = sp.getString(KEY_RESTARTS, "") ?: ""
            val recent = raw.split(',').mapNotNull { it.toLongOrNull() }
                .filter { now - it < BREAKER_WINDOW_MS }
                .plus(now)
                .takeLast(BREAKER_MAX_RETAINED)
            sp.edit().putString(KEY_RESTARTS, recent.joinToString(",")).apply()
            recent.size >= BREAKER_THRESHOLD
        } catch (t: Throwable) {
            NMLog.e(NMSub.SERVICE, "breaker check threw", t)
            false
        }
    }

    override fun onCreate() {
        super.onCreate()

        if (tripCrashLoopBreaker()) {
            NMLog.e(NMSub.SERVICE, "crash-loop detected — suspending NetMonitor")
            startForeground(NOTIFICATION_ID, buildNotification())
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        val knownHosts = KnownHostsLoader.load(this)
        val resolver = KnownHostsResolver.create(this, knownHosts).also { it.start() }
        knownHostsResolver = resolver
        repository = NetDataRepository(
            knownHostsLookup = resolver::domainsForIp,
            networkUsageSource = NetMonitorConfig.networkUsageSource,
        )
        floatingWindowManager = FloatingWindowManager(this)
        floatingWindowManager.show()

        handler.post { floatingWindowManager.updateData(repository.getLatestData()) }
        handler.postDelayed(refreshRunnable, NetMonitorConfig.refreshIntervalMs)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshRunnable)
        knownHostsResolver?.stop()
        knownHostsResolver = null
        repository.destroy()
        floatingWindowManager.hide()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Net Monitor", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Net Monitor")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "net_monitor"
        private const val NOTIFICATION_ID = 0x4E4D

        private const val PREFS_BREAKER = "netmonitor_breaker"
        private const val KEY_RESTARTS = "restart_timestamps"
        private const val BREAKER_THRESHOLD = 3
        private const val BREAKER_WINDOW_MS = 60_000L
        private const val BREAKER_MAX_RETAINED = 8

        fun start(context: Context) =
            context.startForegroundService(Intent(context, NetMonitorService::class.java))
    }
}
