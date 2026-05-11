package com.telenav.netmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class KnownHostsResolverTest {

    /** Runs `submit` in the calling thread; records `schedule` calls without firing them. */
    private class DirectScheduledExecutor : AbstractExecutorService(), ScheduledExecutorService {
        val scheduled = mutableListOf<ScheduledCall>()
        private val shutdown = AtomicBoolean(false)

        data class ScheduledCall(val delay: Long, val unit: TimeUnit, val command: Runnable)

        override fun execute(command: Runnable) {
            if (!shutdown.get()) command.run()
        }

        override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> {
            scheduled.add(ScheduledCall(delay, unit, command))
            return DummyScheduledFuture
        }

        override fun <V : Any?> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
            throw UnsupportedOperationException()

        override fun scheduleAtFixedRate(c: Runnable, i: Long, p: Long, u: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()

        override fun scheduleWithFixedDelay(c: Runnable, i: Long, d: Long, u: TimeUnit): ScheduledFuture<*> =
            throw UnsupportedOperationException()

        override fun shutdown() { shutdown.set(true) }
        override fun shutdownNow(): MutableList<Runnable> { shutdown.set(true); return mutableListOf() }
        override fun isShutdown(): Boolean = shutdown.get()
        override fun isTerminated(): Boolean = shutdown.get()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
    }

    private object DummyScheduledFuture : ScheduledFuture<Any?> {
        override fun cancel(mayInterrupt: Boolean): Boolean = true
        override fun isCancelled(): Boolean = false
        override fun isDone(): Boolean = false
        override fun get(): Any? = null
        override fun get(timeout: Long, unit: TimeUnit): Any? = null
        override fun getDelay(unit: TimeUnit): Long = 0L
        override fun compareTo(other: java.util.concurrent.Delayed?): Int = 0
    }

    private fun ip(addr: String): InetAddress = InetAddress.getByName(addr)

    private fun resolverWith(
        domains: List<String>,
        responses: Map<String, () -> Array<InetAddress>>,
        executor: ScheduledExecutorService = DirectScheduledExecutor(),
    ): KnownHostsResolver = KnownHostsResolver(
        knownDomains = domains,
        nameResolver = { host ->
            val supplier = responses[host] ?: throw UnknownHostException(host)
            supplier()
        },
        executor = executor,
        networkObserver = NoopNetworkObserver,
    )

    private object NoopNetworkObserver : KnownHostsResolver.NetworkObserver {
        override fun register(callback: () -> Unit) {}
        override fun unregister() {}
    }

    @Test
    fun `domainsForIp empty before start`() {
        val r = resolverWith(listOf("a.example.com"), mapOf(
            "a.example.com" to { arrayOf(ip("1.2.3.4")) },
        ))
        assertTrue(r.domainsForIp("1.2.3.4").isEmpty())
    }

    @Test
    fun `single domain single ip resolves`() {
        val r = resolverWith(listOf("a.example.com"), mapOf(
            "a.example.com" to { arrayOf(ip("1.2.3.4")) },
        ))
        r.start()
        assertEquals(listOf("a.example.com"), r.domainsForIp("1.2.3.4"))
    }

    @Test
    fun `single domain multiple ips populates each ip key`() {
        val r = resolverWith(listOf("cdn.example.com"), mapOf(
            "cdn.example.com" to { arrayOf(ip("1.2.3.4"), ip("5.6.7.8")) },
        ))
        r.start()
        assertEquals(listOf("cdn.example.com"), r.domainsForIp("1.2.3.4"))
        assertEquals(listOf("cdn.example.com"), r.domainsForIp("5.6.7.8"))
    }

    @Test
    fun `multiple domains sharing one ip return both sorted`() {
        val r = resolverWith(listOf("zeta.example.com", "alpha.example.com"), mapOf(
            "zeta.example.com" to { arrayOf(ip("1.2.3.4")) },
            "alpha.example.com" to { arrayOf(ip("1.2.3.4")) },
        ))
        r.start()
        assertEquals(listOf("alpha.example.com", "zeta.example.com"), r.domainsForIp("1.2.3.4"))
    }

    @Test
    fun `failed domain does not break others`() {
        val r = resolverWith(listOf("good.example.com", "bad.example.com"), mapOf(
            "good.example.com" to { arrayOf(ip("1.2.3.4")) },
            "bad.example.com" to { throw UnknownHostException("bad.example.com") },
        ))
        r.start()
        assertEquals(listOf("good.example.com"), r.domainsForIp("1.2.3.4"))
    }

    @Test
    fun `nameResolver throwing RuntimeException is treated as failure (paranoid catch)`() {
        val r = resolverWith(listOf("good.example.com", "weird.example.com"), mapOf(
            "good.example.com" to { arrayOf(ip("1.2.3.4")) },
            "weird.example.com" to { throw RuntimeException("ART glitch") },
        ))
        r.start()
        assertEquals(listOf("good.example.com"), r.domainsForIp("1.2.3.4"))
        assertTrue(r.domainsForIp("anything").isEmpty())
    }

    @Test
    fun `unknown ip returns empty list`() {
        val r = resolverWith(listOf("a.example.com"), mapOf(
            "a.example.com" to { arrayOf(ip("1.2.3.4")) },
        ))
        r.start()
        assertTrue(r.domainsForIp("9.9.9.9").isEmpty())
    }

    @Test
    fun `start is idempotent`() {
        val callCount = AtomicInteger(0)
        val r = resolverWith(listOf("a.example.com"), mapOf(
            "a.example.com" to {
                callCount.incrementAndGet()
                arrayOf(ip("1.2.3.4"))
            },
        ))
        r.start()
        r.start()
        r.start()
        assertEquals(1, callCount.get())
    }

    @Test
    fun `stop shuts down executor and unregisters network observer`() {
        var unregistered = false
        val observer = object : KnownHostsResolver.NetworkObserver {
            override fun register(callback: () -> Unit) {}
            override fun unregister() { unregistered = true }
        }
        val executor = DirectScheduledExecutor()
        val r = KnownHostsResolver(
            knownDomains = listOf("a.example.com"),
            nameResolver = { arrayOf(ip("1.2.3.4")) },
            executor = executor,
            networkObserver = observer,
        )
        r.start()
        r.stop()
        assertTrue(executor.isShutdown)
        assertTrue(unregistered)
    }

    @Test
    fun `empty knownDomains list is a no-op (no resolution attempts)`() {
        val callCount = AtomicInteger(0)
        val r = KnownHostsResolver(
            knownDomains = emptyList(),
            nameResolver = {
                callCount.incrementAndGet()
                arrayOf(ip("1.2.3.4"))
            },
            executor = DirectScheduledExecutor(),
            networkObserver = NoopNetworkObserver,
        )
        r.start()
        assertEquals(0, callCount.get())
        assertTrue(r.domainsForIp("1.2.3.4").isEmpty())
    }

    @Test
    fun `failed host schedules retry with 5s delay`() {
        val executor = DirectScheduledExecutor()
        val r = KnownHostsResolver(
            knownDomains = listOf("bad.example.com"),
            nameResolver = { throw UnknownHostException(it) },
            executor = executor,
            networkObserver = NoopNetworkObserver,
        )
        r.start()
        assertEquals(1, executor.scheduled.size)
        assertEquals(5L, executor.scheduled[0].delay)
        assertEquals(TimeUnit.SECONDS, executor.scheduled[0].unit)
    }

    @Test
    fun `successive failures use 5s 10s 30s 60s 300s sequence then cap at 300s`() {
        val executor = DirectScheduledExecutor()
        val r = KnownHostsResolver(
            knownDomains = listOf("bad.example.com"),
            nameResolver = { throw UnknownHostException(it) },
            executor = executor,
            networkObserver = NoopNetworkObserver,
        )
        r.start()                                    // attempt 1 fails -> schedule 5s
        repeat(6) { executor.scheduled.last().command.run() } // 6 more retries (all fail)
        val delays = executor.scheduled.map { it.delay }
        assertEquals(listOf(5L, 10L, 30L, 60L, 300L, 300L, 300L), delays)
    }

    @Test
    fun `retry success merges into existing map without dropping other entries`() {
        val executor = DirectScheduledExecutor()
        val attempts = mutableMapOf<String, Int>()
        val r = KnownHostsResolver(
            knownDomains = listOf("good.example.com", "bad.example.com"),
            nameResolver = { host ->
                val n = (attempts[host] ?: 0) + 1
                attempts[host] = n
                when (host) {
                    "good.example.com" -> arrayOf(ip("1.2.3.4"))
                    "bad.example.com" -> if (n == 1) throw UnknownHostException(host) else arrayOf(ip("5.6.7.8"))
                    else -> throw UnknownHostException(host)
                }
            },
            executor = executor,
            networkObserver = NoopNetworkObserver,
        )
        r.start()
        assertEquals(listOf("good.example.com"), r.domainsForIp("1.2.3.4"))
        assertTrue(r.domainsForIp("5.6.7.8").isEmpty())

        executor.scheduled.last().command.run()      // retry bad.example.com -> succeeds
        assertEquals(listOf("good.example.com"), r.domainsForIp("1.2.3.4"))
        assertEquals(listOf("bad.example.com"), r.domainsForIp("5.6.7.8"))
    }

    @Test
    fun `successful retry does not enqueue another schedule and updates map`() {
        val executor = DirectScheduledExecutor()
        var fail = true
        val r = KnownHostsResolver(
            knownDomains = listOf("h.example.com"),
            nameResolver = { _ ->
                if (fail) throw UnknownHostException("h.example.com")
                else arrayOf(ip("1.2.3.4"))
            },
            executor = executor,
            networkObserver = NoopNetworkObserver,
        )
        r.start()                                    // fail attempt 1 -> 5s scheduled
        val schedulesAfterFirstFailure = executor.scheduled.size
        fail = false
        executor.scheduled.last().command.run()      // retry succeeds
        // Map now contains the IP; no fresh schedule was added.
        assertEquals(listOf("h.example.com"), r.domainsForIp("1.2.3.4"))
        assertEquals(schedulesAfterFirstFailure, executor.scheduled.size)
    }

    @Test
    fun `network observer trigger reschedules a full resolveAll and resets backoff`() {
        val executor = DirectScheduledExecutor()
        var triggerNetwork: (() -> Unit)? = null
        val observer = object : KnownHostsResolver.NetworkObserver {
            override fun register(callback: () -> Unit) { triggerNetwork = callback }
            override fun unregister() {}
        }
        var fail = true
        val r = KnownHostsResolver(
            knownDomains = listOf("h.example.com"),
            nameResolver = { _ ->
                if (fail) throw UnknownHostException("h.example.com")
                else arrayOf(ip("1.2.3.4"))
            },
            executor = executor,
            networkObserver = observer,
        )
        r.start()                                                 // fail -> attempt 1 schedule 5s
        executor.scheduled.last().command.run()                   // fail -> attempt 2 schedule 10s
        assertEquals(listOf(5L, 10L), executor.scheduled.map { it.delay })

        fail = false
        triggerNetwork!!.invoke()                                  // network back -> full re-resolve, succeeds
        assertEquals(listOf("h.example.com"), r.domainsForIp("1.2.3.4"))

        // simulate a new failure later: backoff must restart at 5s (counter cleared)
        fail = true
        triggerNetwork!!.invoke()
        val newDelay = executor.scheduled.last().delay
        assertEquals(5L, newDelay)
    }

    @Test
    fun `stop prevents queued retry from mutating map`() {
        val executor = DirectScheduledExecutor()
        var resolveCount = 0
        val r = KnownHostsResolver(
            knownDomains = listOf("h.example.com"),
            nameResolver = { _ ->
                resolveCount++
                throw UnknownHostException("h.example.com")
            },
            executor = executor,
            networkObserver = NoopNetworkObserver,
        )
        r.start()
        val countBeforeStop = resolveCount
        r.stop()
        // simulate the scheduled retry firing after stop — must not invoke nameResolver
        executor.scheduled.last().command.run()
        assertEquals(countBeforeStop, resolveCount)
    }
}
