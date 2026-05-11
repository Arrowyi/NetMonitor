package com.telenav.netmonitor

import android.content.Context
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pre-resolves an OEM-provided list of known hosts and answers reverse lookups
 * (`IP -> List<domain>`) for the Layer D socket endpoint UI.
 *
 * - Lifecycle owned by [NetMonitorService]: [start] / [stop].
 * - All DNS work runs on a single-thread daemon executor; UI reads via
 *   [domainsForIp] are lock-free against a volatile map.
 * - Per-host failure: isolated; retried on exponential backoff
 *   (5s / 10s / 30s / 60s / 300s — last value reused beyond attempt 5).
 *   Successful retry merges into the current map.
 * - Network change ([networkObserver].onAvailable): clears backoff and
 *   re-resolves the full list.
 * - Public methods **never throw**.
 */
class KnownHostsResolver(
    private val knownDomains: List<String>,
    private val nameResolver: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "NetMonitor-DNS").apply { isDaemon = true }
    },
    private val networkObserver: NetworkObserver = NoopNetworkObserver,
) {

    /** Test seam for `ConnectivityManager.NetworkCallback` registration. */
    interface NetworkObserver {
        fun register(callback: () -> Unit)
        fun unregister()
    }

    private object NoopNetworkObserver : NetworkObserver {
        override fun register(callback: () -> Unit) {}
        override fun unregister() {}
    }

    @Volatile
    private var ipToDomains: Map<String, List<String>> = emptyMap()

    private val started = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)

    /** attempt counter per host (0 = no failure recorded). */
    private val attempts = ConcurrentHashMap<String, Int>()

    /** Idempotent. No-op for an empty [knownDomains] list. */
    fun start() {
        if (knownDomains.isEmpty()) {
            logI("knownDomains list is empty — DNS reverse-lookup disabled")
            return
        }
        if (!started.compareAndSet(false, true)) return
        if (stopped.get()) return
        try { executor.execute { resolveAll() } } catch (t: Throwable) {
            logW("initial resolve dispatch failed", t)
        }
        try {
            networkObserver.register {
                if (stopped.get()) return@register
                logI("network available, re-resolving")
                try {
                    executor.execute {
                        attempts.clear()                     // network back: restart backoff
                        resolveAll()
                    }
                } catch (t: Throwable) {
                    logW("network-recovery dispatch failed", t)
                }
            }
        } catch (t: Throwable) {
            logW("network observer registration failed", t)
        }
    }

    /** Idempotent. After stop, [domainsForIp] keeps returning the last snapshot. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        try { networkObserver.unregister() } catch (_: Throwable) {}
        try { executor.shutdownNow() } catch (_: Throwable) {}
    }

    fun domainsForIp(ip: String): List<String> = ipToDomains[ip].orEmpty()

    // -- internals -------------------------------------------------------

    private fun resolveAll() {
        if (stopped.get()) return
        val collected = LinkedHashMap<String, MutableList<String>>()
        var ok = 0
        for (host in knownDomains) {
            val addrs = resolveOne(host)
            if (addrs == null) {
                scheduleRetry(host)
                continue
            }
            attempts.remove(host)
            ok++
            for (a in addrs) {
                val ip = a.hostAddress ?: continue
                collected.getOrPut(ip) { mutableListOf() }.add(host)
            }
        }
        if (stopped.get()) return
        ipToDomains = collected.mapValues { (_, v) -> v.sorted().distinct() }
        logI("resolved $ok/${knownDomains.size} hosts, ${ipToDomains.size} unique IPs")
    }

    private fun retryHost(host: String) {
        if (stopped.get()) return
        val addrs = resolveOne(host)
        if (addrs == null) {
            scheduleRetry(host)
            return
        }
        attempts.remove(host)
        // Merge: keep current map's other entries, add/update this host's IPs.
        val merged = LinkedHashMap<String, MutableList<String>>()
        for ((ip, domains) in ipToDomains) {
            merged[ip] = domains.filter { it != host }.toMutableList()
        }
        for (a in addrs) {
            val ip = a.hostAddress ?: continue
            merged.getOrPut(ip) { mutableListOf() }.add(host)
        }
        if (stopped.get()) return
        ipToDomains = merged
            .filterValues { it.isNotEmpty() }
            .mapValues { (_, v) -> v.sorted().distinct() }
        logI("retry succeeded for $host (${addrs.size} addr); ${ipToDomains.size} unique IPs total")
    }

    private fun scheduleRetry(host: String) {
        if (stopped.get()) return
        val attempt = (attempts[host] ?: 0) + 1
        attempts[host] = attempt
        val delaySec = BACKOFF_SECONDS[(attempt - 1).coerceAtMost(BACKOFF_SECONDS.lastIndex)]
        try {
            executor.schedule({ retryHost(host) }, delaySec, TimeUnit.SECONDS)
            logI("$host retry scheduled in ${delaySec}s (attempt $attempt)")
        } catch (t: Throwable) {
            logW("$host retry scheduling failed", t)
        }
    }

    /** Returns the addresses on success, null on failure. */
    private fun resolveOne(host: String): Array<InetAddress>? = try {
        val addrs = nameResolver(host)
        if (addrs.isEmpty()) {
            logW("$host resolved to 0 addresses — treating as failure", null)
            null
        } else addrs
    } catch (t: Throwable) {
        logW("$host resolution failed (${t.javaClass.simpleName}: ${t.message})", null)
        null
    }

    private fun logI(msg: String) = NMLog.i(NMSub.DNS, msg)

    private fun logW(msg: String, t: Throwable?) = NMLog.w(NMSub.DNS, msg, t)

    companion object {
        private val BACKOFF_SECONDS = longArrayOf(5L, 10L, 30L, 60L, 300L)

        /** Production factory; falls back to no-op observer when ConnectivityManager is unavailable. */
        fun create(context: android.content.Context, knownDomains: List<String>): KnownHostsResolver {
            val observer: NetworkObserver = try {
                ConnectivityNetworkObserver(context.applicationContext)
            } catch (t: Throwable) {
                NMLog.w(NMSub.DNS, "ConnectivityManager unavailable; running without network observer", t)
                NoopNetworkObserver
            }
            return KnownHostsResolver(
                knownDomains = knownDomains,
                networkObserver = observer,
            )
        }
    }

    /** [NetworkObserver] backed by `ConnectivityManager.registerDefaultNetworkCallback`. */
    private class ConnectivityNetworkObserver(
        private val context: android.content.Context,
    ) : NetworkObserver {

        private var registeredCallback: android.net.ConnectivityManager.NetworkCallback? = null

        override fun register(callback: () -> Unit) {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) { callback() }
            }
            try {
                cm.registerDefaultNetworkCallback(cb)
                registeredCallback = cb
            } catch (t: Throwable) {
                NMLog.w(NMSub.DNS, "registerDefaultNetworkCallback failed", t)
            }
        }

        override fun unregister() {
            val cb = registeredCallback ?: return
            registeredCallback = null
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return
            try { cm.unregisterNetworkCallback(cb) } catch (_: Throwable) {}
        }
    }
}
