package com.liskovsoft.youtubeapi.app.potokennp2.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class PoTokenException(message: String) : RuntimeException(message)

// to be thrown if the WebView provided by the system is broken
internal class BadWebViewException(message: String) : RuntimeException(message)

internal class V8WrapperException(message: String, cause: Exception? = null) : RuntimeException(message, cause)

internal fun buildExceptionForJsError(error: String): Throwable {
    return if (error.contains("SyntaxError"))
        BadWebViewException(error)
    else
        PoTokenException(error)
}

/**
 * NEWTUBE(pot-wv4): waits for a WebView callback and fails with a [PoTokenException] when it does
 * not come. Upstream's PoTokenWebView4 waited 10 s for a mint and then returned an unassigned
 * `lateinit` (UninitializedPropertyAccessException), and our PoTokenWebView waited with no bound at
 * all, so a WebView that lost its content could hold the /player thread indefinitely. A
 * PoTokenException is what PoTokenProviderImpl's recreate-and-retry path expects.
 */
internal fun awaitOrThrow(latch: CountDownLatch, timeoutMs: Long, what: String) {
    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        throw PoTokenException("$what: no answer within $timeoutMs ms")
    }
}
