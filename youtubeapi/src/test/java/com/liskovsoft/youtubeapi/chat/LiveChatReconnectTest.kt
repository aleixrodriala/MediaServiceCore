package com.liskovsoft.youtubeapi.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * NEWTUBE(chat-backoff): the live-chat reconnect policy - backoff, reset, and every way the loop
 * must stop. Offline (UnknownHost wrapped by RetrofitHelper) it used to reconnect with zero delay.
 */
@RunWith(RobolectricTestRunner::class)
class LiveChatReconnectTest {
    private val unknownHost = IllegalStateException(UnknownHostException("Unable to resolve host"))

    private class RecordingSleeper(private val result: Boolean = true) : LiveChatServiceInt.Sleeper {
        val delays = mutableListOf<Long>()

        override fun sleep(delayMs: Long, stopSignal: LiveChatServiceInt.StopSignal): Boolean {
            delays.add(delayMs)
            return result
        }
    }

    @Test
    fun backoffDoublesToThirtySecondsAndResets() {
        val backoff = LiveChatBackoff()

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            List(7) { backoff.nextDelayMs() })
        assertTrue("success after failures reports a reset", backoff.onSuccess())
        assertEquals(1_000L, backoff.nextDelayMs())
        backoff.onSuccess()
        assertFalse("success without failures is not a reset", backoff.onSuccess())
    }

    @Test
    fun interruptedReadIsAStopSignalButTimeoutsAreNot() {
        assertTrue(LiveChatServiceInt.isStopSignal(IllegalStateException(InterruptedIOException())))
        assertTrue(LiveChatServiceInt.isStopSignal(IllegalStateException(InterruptedIOException("interrupted"))))
        assertTrue(LiveChatServiceInt.isStopSignal(InterruptedException()))
        assertFalse(LiveChatServiceInt.isStopSignal(IllegalStateException(SocketTimeoutException("timeout"))))
        assertFalse("OkHttp call timeout", LiveChatServiceInt.isStopSignal(IllegalStateException(InterruptedIOException("timeout"))))
        assertFalse(LiveChatServiceInt.isStopSignal(unknownHost))
        assertFalse(LiveChatServiceInt.isStopSignal(RuntimeException("boom")))
        assertFalse(LiveChatServiceInt.isStopSignal(null))
    }

    @Test
    fun offlineLoopBacksOffAndStopsWhenDisposed() {
        var sessions = 0
        val sleeper = RecordingSleeper()

        LiveChatServiceInt.runReconnectLoop({ sessions >= 8 }, LiveChatBackoff(), sleeper) {
            sessions++
            throw unknownHost
        }

        assertEquals(8, sessions)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), sleeper.delays)
    }

    @Test
    fun disposedBeforeStartNeverPolls() {
        var sessions = 0
        val sleeper = RecordingSleeper()

        LiveChatServiceInt.runReconnectLoop({ true }, LiveChatBackoff(), sleeper) { sessions++; 1 }

        assertEquals(0, sessions)
        assertTrue(sleeper.delays.isEmpty())
    }

    @Test
    fun interruptedReadStopsWithoutRetrying() {
        var sessions = 0
        val sleeper = RecordingSleeper()

        LiveChatServiceInt.runReconnectLoop({ false }, LiveChatBackoff(), sleeper) {
            sessions++
            throw IllegalStateException(InterruptedIOException())
        }

        assertEquals(1, sessions)
        assertTrue(sleeper.delays.isEmpty())
    }

    @Test
    fun interruptedSleepStopsTheLoop() {
        var sessions = 0

        LiveChatServiceInt.runReconnectLoop({ false }, LiveChatBackoff(), RecordingSleeper(result = false)) {
            sessions++
            throw unknownHost
        }

        assertEquals(1, sessions)
    }

    @Test
    fun successfulPollResetsTheBackoff() {
        val backoff = LiveChatBackoff()
        val sleeper = RecordingSleeper()
        var sessions = 0

        LiveChatServiceInt.runReconnectLoop({ sessions >= 5 }, backoff, sleeper) {
            sessions++
            when (sessions) {
                3 -> { backoff.onSuccess(); throw unknownHost } // polled fine, then the link dropped
                else -> throw unknownHost
            }
        }

        assertEquals(listOf(1_000L, 2_000L, 1_000L, 2_000L), sleeper.delays)
    }

    @Test
    fun sessionEndKeepsTheFiveSecondFloor() {
        val sleeper = RecordingSleeper()
        var sessions = 0

        LiveChatServiceInt.runReconnectLoop({ sessions >= 6 }, LiveChatBackoff(), sleeper) {
            sessions++
            if (sessions == 1) 3 else 0 // one healthy session, then the chat stops answering
        }

        // healthy end -> 5 s; empty sessions back off but never below the old 5 s pause
        assertEquals(listOf(5_000L, 5_000L, 5_000L, 5_000L, 8_000L, 16_000L), sleeper.delays)
    }
}
