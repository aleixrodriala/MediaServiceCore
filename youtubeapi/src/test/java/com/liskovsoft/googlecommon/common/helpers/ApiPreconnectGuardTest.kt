package com.liskovsoft.googlecommon.common.helpers

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Codex round-2 finding: the app-start www.youtube.com preconnect ran on OkHttpManager's base
 * client, whose connection events the InnerTube client's stale-connection guard never sees - an
 * HTTP/1.1 preconnect idle 76 s before the first /player slipped past it. It now runs through
 * [RetrofitHelper.warmUpApiConnection]; a local keep-alive HTTP/1.1 server stands in for the edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], application = Application::class)
class ApiPreconnectGuardTest {
    @Test
    fun thePreconnectConnectionIsGuardedAndEvictedOnceStale() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val held = CopyOnWriteArrayList<Socket>()
        thread(isDaemon = true) {
            try {
                while (true) {
                    val socket = server.accept()
                    held += socket
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                    while (reader.readLine()?.isNotEmpty() == true) {
                        // drain the request headers
                    }
                    // Keep-alive HTTP/1.1 answer: the connection goes back to the pool idle.
                    socket.getOutputStream().write(
                        "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII),
                    )
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) {
                // server closed
            }
        }
        val pool = RetrofitOkHttpHelper.client.connectionPool
        pool.evictAll()
        RetrofitOkHttpHelper.guardedHosts = setOf("127.0.0.1")
        try {
            RetrofitHelper.warmUpApiConnection("http://127.0.0.1:${server.localPort}/generate_204")
            assertEquals(1, pool.idleConnectionCount())

            // Fresh: an interactive call rides it as usual.
            assertEquals(-1L, RetrofitOkHttpHelper.evictStaleApiConnectionsIfNeeded(true, "/youtubei/v1/player", "cell:1"))
            assertEquals(1, pool.idleConnectionCount())

            ShadowSystemClock.advanceBy(Duration.ofSeconds(76))

            val idleMs = RetrofitOkHttpHelper.evictStaleApiConnectionsIfNeeded(true, "/youtubei/v1/player", "cell:1")
            assertTrue("idleMs=$idleMs", idleMs >= 76_000L)
            assertEquals(0, pool.idleConnectionCount())
        } finally {
            RetrofitOkHttpHelper.guardedHosts = setOf("www.youtube.com")
            server.close()
            held.forEach { runCatching { it.close() } }
        }
    }
}
