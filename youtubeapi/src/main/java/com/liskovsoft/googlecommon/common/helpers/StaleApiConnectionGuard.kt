package com.liskovsoft.googlecommon.common.helpers

import java.util.WeakHashMap

/**
 * NEWTUBE(api-pool): keeps an interactive /player or /next from riding a pooled HTTP/1.1 API
 * connection that a carrier NAT has silently forgotten.
 *
 * Measured 2026-09-26, Movistar LTE: a /player POST reused a www.youtube.com HTTP/1.1 socket idle
 * for 76 s. The NAT mapping was gone (no FIN, no RST), OkHttp's health check passed it, the request
 * was written into the void and waited out the 8 s open-path read timeout - the ring then walked
 * on and first frame came at +9.2 s. A socket last used 55 s earlier answered in 124 ms, so the
 * cut-off sits between the two.
 *
 * HTTP/2 does not need this: the shared client's 10 s PING keeps the mapping alive and kills a
 * dead connection, so only HTTP/1.1 connections are tracked (HTTP/1.1 carries no ping). The app is
 * meant to run the API on H2 (OkHttpManager.setPreferHttp2); this is the safety net for when ALPN
 * still yields HTTP/1.1 (a proxy, a middlebox, a regression like round 3's).
 *
 * Decision: on CELLULAR, before an interactive call, if any live pooled HTTP/1.1 connection has
 * been idle for [STALE_IDLE_MS] or more, the caller evicts the pool's idle connections so the call
 * dials a fresh one (one TCP+TLS1.3 handshake, ~100-200 ms on LTE) instead of betting 8 s on a
 * socket that may be dead. Wi-Fi NATs keep TCP mappings far longer, so Wi-Fi is left alone.
 */
internal class StaleApiConnectionGuard(private val nowMs: () -> Long) {

    /** HTTP/1.1 connection -> when it went idle. Weak: the pool, not this book, owns lifetimes. */
    private val idleSince = WeakHashMap<Any, Long>()

    @Synchronized
    fun acquired(connection: Any) {
        idleSince.remove(connection)
    }

    @Synchronized
    fun released(connection: Any, http1: Boolean) {
        if (http1) {
            idleSince[connection] = nowMs()
        } else {
            idleSince.remove(connection)
        }
    }

    /**
     * Before an interactive call: when a live idle HTTP/1.1 connection is stale, runs [evict]
     * (drop every idle pooled connection) and returns the longest idle time (ms); otherwise -1.
     *
     * Decision, eviction and forgetting happen under ONE lock: a concurrent interactive call
     * blocks here until the stale sockets are gone, so it can neither take one in between nor
     * evict the fresh connection this call is about to dial. OkHttp 5 reports connection events
     * outside its connection locks and [okhttp3.ConnectionPool.evictAll] reports none, so holding
     * this lock across the eviction cannot deadlock.
     */
    @Synchronized
    fun evictIfStale(cellular: Boolean, isClosed: (Any) -> Boolean, evict: () -> Unit): Long {
        val now = nowMs()
        var oldest = -1L
        val entries = idleSince.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (isClosed(entry.key)) {
                entries.remove() // evicted by the pool or closed by the server: never reused
                continue
            }
            oldest = maxOf(oldest, now - entry.value)
        }
        if (!shouldEvict(cellular, oldest)) {
            return -1
        }
        evict()
        idleSince.clear()
        return oldest
    }

    companion object {
        /** Below Movistar's observed cut-off (55 s alive, 76 s dead) with margin. */
        const val STALE_IDLE_MS = 45_000L

        @JvmStatic
        fun shouldEvict(cellular: Boolean, oldestIdleMs: Long): Boolean =
            cellular && oldestIdleMs >= STALE_IDLE_MS
    }
}
