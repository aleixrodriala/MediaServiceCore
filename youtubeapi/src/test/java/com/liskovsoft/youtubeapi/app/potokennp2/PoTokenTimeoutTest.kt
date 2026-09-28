package com.liskovsoft.youtubeapi.app.potokennp2

import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenException
import com.liskovsoft.youtubeapi.app.potokennp2.core.awaitOrThrow
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * NEWTUBE(pot-wv4): fix (b) of potoken-port.md. A mint or init that gets no answer from the WebView
 * fails with a PoTokenException - what PoTokenProviderImpl's recreate-and-retry expects - not with
 * upstream's UninitializedPropertyAccessException and not by waiting forever (PoTokenWebView's old
 * unbounded `latch.await()`).
 */
class PoTokenTimeoutTest {
    @Test
    fun anAnsweredWaitReturns() {
        val latch = CountDownLatch(1)
        latch.countDown()

        awaitOrThrow(latch, 1_000, "mint")
    }

    @Test
    fun anAnswerFromAnotherThreadBeforeTheBoundReturns() {
        val latch = CountDownLatch(1)
        Thread { Thread.sleep(20); latch.countDown() }.start()

        awaitOrThrow(latch, 5_000, "mint")
    }

    @Test
    fun noAnswerIsAPoTokenExceptionAfterTheBound() {
        val startedNs = System.nanoTime()

        try {
            awaitOrThrow(CountDownLatch(1), 50, "PoTokenWebView4 mint")
            fail("expected a PoTokenException")
        } catch (e: PoTokenException) {
            assertTrue(e.message, e.message!!.contains("PoTokenWebView4 mint: no answer within 50 ms"))
        }

        assertTrue((System.nanoTime() - startedNs) / 1_000_000 >= 50)
    }
}
