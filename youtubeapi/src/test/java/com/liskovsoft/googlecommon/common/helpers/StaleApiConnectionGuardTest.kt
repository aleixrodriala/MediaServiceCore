package com.liskovsoft.googlecommon.common.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Stale pooled HTTP/1.1 API connections on cellular (Movistar LTE, 2026-09-26). */
class StaleApiConnectionGuardTest {
    @Volatile
    private var now = 1_000L
    private val closed = mutableSetOf<Any>()
    private val guard = StaleApiConnectionGuard { now }
    private val isClosed: (Any) -> Boolean = { it in closed }
    private val evictions = AtomicInteger()
    private val evict: () -> Unit = { evictions.incrementAndGet() }

    private fun check(cellular: Boolean = true) = guard.evictIfStale(cellular, isClosed, evict)

    @Test
    fun theMeasuredCaseEvictsBeforeTheNextOpen() {
        // The r3g timeline: the /player socket had been idle 76 s; another one only 55 s.
        val playerSocket = Any()
        val statsSocket = Any()
        guard.released(playerSocket, http1 = true)
        now += 21_000
        guard.released(statsSocket, http1 = true)
        now += 55_000

        assertEquals(76_000L, check())
        assertEquals(1, evictions.get())
        // The book is cleared: a parallel /next must not evict the connection /player dials now.
        assertEquals(-1L, check())
        assertEquals(1, evictions.get())
    }

    @Test
    fun recentlyUsedConnectionsAreReusedAsUsual() {
        guard.released(Any(), http1 = true)
        now += StaleApiConnectionGuard.STALE_IDLE_MS - 1

        assertEquals(-1L, check())
        assertEquals(0, evictions.get())
    }

    @Test
    fun wifiIsLeftAlone() {
        guard.released(Any(), http1 = true)
        now += 10 * 60_000L

        assertEquals(-1L, check(cellular = false))
        assertEquals(0, evictions.get())
    }

    @Test
    fun http2ConnectionsNeverCountTheirPingKeepsThemAlive() {
        guard.released(Any(), http1 = false)
        now += 10 * 60_000L

        assertEquals(-1L, check())
    }

    @Test
    fun aConnectionInUseIsNotIdle() {
        val socket = Any()
        guard.released(socket, http1 = true)
        now += 60_000
        guard.acquired(socket) // taken again by a call: busy, not idle

        assertEquals(-1L, check())
    }

    @Test
    fun closedConnectionsAreForgottenNotEvictedFor() {
        val gone = Any()
        guard.released(gone, http1 = true)
        now += 5 * 60_000L
        closed += gone // the pool's own 5-minute keep-alive already closed it

        assertEquals(-1L, check())
        assertEquals(0, evictions.get())
    }

    /**
     * Codex round-2 finding: the record used to be cleared BEFORE the pool eviction ran, so a
     * concurrent /next could see "nothing stale" and take the stale socket in between. Now a second
     * interactive call waits until the first one's eviction has finished.
     */
    @Test
    fun aConcurrentOpenWaitsForTheEvictionInsteadOfSlippingPastIt() {
        guard.released(Any(), http1 = true)
        now += 76_000
        val inEviction = CountDownLatch(1)
        val finishEviction = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Long> {
                guard.evictIfStale(true, isClosed) {
                    inEviction.countDown()
                    finishEviction.await(5, TimeUnit.SECONDS)
                    evictions.incrementAndGet()
                }
            }
            assertTrue(inEviction.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Long> { check() }

            Thread.sleep(150)
            assertFalse("the second call must wait for the eviction", second.isDone)

            finishEviction.countDown()
            assertEquals(76_000L, first.get(5, TimeUnit.SECONDS))
            assertEquals(-1L, second.get(5, TimeUnit.SECONDS))
            assertEquals(1, evictions.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun thresholdSitsBelowTheObservedNatCutOff() {
        // 55 s alive, 76 s dead on Movistar: 45 s leaves margin below both.
        assertTrue(StaleApiConnectionGuard.STALE_IDLE_MS < 55_000L)
        assertTrue(StaleApiConnectionGuard.shouldEvict(true, 45_000L))
        assertFalse(StaleApiConnectionGuard.shouldEvict(true, 44_999L))
        assertFalse(StaleApiConnectionGuard.shouldEvict(false, 600_000L))
        assertFalse(StaleApiConnectionGuard.shouldEvict(true, -1L))
    }
}
