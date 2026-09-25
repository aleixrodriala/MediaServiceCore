package com.liskovsoft.youtubeapi.chat

import com.liskovsoft.mediaserviceinterfaces.data.ChatItem
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.chat.gen.LiveChatResult
import com.liskovsoft.youtubeapi.chat.gen.getActions
import com.liskovsoft.youtubeapi.chat.gen.getContinuation
import com.liskovsoft.youtubeapi.chat.impl.ChatItemImpl
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedByInterruptException
import kotlin.math.max
import kotlin.math.min

internal object LiveChatServiceInt {
    private val TAG = LiveChatServiceInt::class.simpleName
    private val mApi = RetrofitHelper.create(LiveChatApi::class.java)
    // Pause between two chat sessions after one ended normally (continuation ran out)
    internal const val RECONNECT_DELAY_MS = 5_000L // fix too frequent request
    // Upper bound for one sleep slice, so a stop signal that can't interrupt the thread is still
    // noticed within half a second.
    private const val SLEEP_SLICE_MS = 500L

    fun openLiveChat(chatKey: String, onChatItem: OnChatItem) {
        openLiveChat(chatKey, onChatItem) { false }
    }

    /**
     * NEWTUBE(chat-backoff): polls until [stopSignal] fires (the Rx subscriber was disposed) or the
     * thread is interrupted. It used to spin: RetrofitHelper wraps every non-Connect IOException
     * (UnknownHost, SocketTimeout, InterruptedIO) in IllegalStateException, so the typed catches
     * never matched and the catch-all reconnected with zero delay - offline, a busy loop for as long
     * as the chat stayed open, and after dispose too (the interrupted read came back wrapped as well).
     * Errors now back off 1 s -> 30 s, reset by the first poll that yields a continuation.
     */
    fun openLiveChat(chatKey: String, onChatItem: OnChatItem, stopSignal: StopSignal) {
        val backoff = LiveChatBackoff()

        runReconnectLoop(stopSignal, backoff, Sleeper { delayMs, signal -> sleepUnlessStopped(delayMs, signal) }) {
            openLiveChatInt(chatKey, onChatItem, stopSignal, backoff)
        }
    }

    /**
     * The reconnect policy, separated from the network so it can be unit tested.
     * [session] runs one chat session and returns how many polls yielded a continuation.
     */
    internal fun runReconnectLoop(stopSignal: StopSignal, backoff: LiveChatBackoff, sleeper: Sleeper, session: () -> Int) {
        // It's common to stream to be interrupted multiple times
        while (true) {
            if (stopSignal.isStopped()) {
                logNetPath("chat-stop reason=disposed")
                return
            }

            val delayMs: Long = try {
                val goodPolls = session()
                // Session over (continuation ran out). A session that never got a continuation
                // (no connection, chat gone) backs off like an error, never below the old 5 s.
                if (goodPolls > 0) RECONNECT_DELAY_MS else max(RECONNECT_DELAY_MS, backoff.nextDelayMs())
            } catch (e: NullPointerException) {
                Log.e(TAG, "Oops. Stopping. Got NPE.")
                e.printStackTrace()
                logNetPath("chat-stop reason=npe")
                return
            } catch (e: Throwable) {
                if (stopSignal.isStopped()) {
                    logNetPath("chat-stop reason=disposed")
                    return
                }

                if (Thread.currentThread().isInterrupted || isStopSignal(e)) {
                    Log.e(TAG, "Oops. Stopping. Listening thread interrupted.")
                    logNetPath("chat-stop reason=interrupted cause=${describe(e)}")
                    return
                }

                // Continue to listen whichever is happening.
                // Android 4.4: StackOverflowError sometimes (is it recoverable?)
                Log.e(TAG, e.message)
                val retryMs = backoff.nextDelayMs()
                logNetPath("chat-retry in=${retryMs}ms cause=${describe(e)}")
                retryMs
            }

            if (!sleeper.sleep(delayMs, stopSignal)) {
                logNetPath("chat-stop reason=" + if (stopSignal.isStopped()) "disposed" else "interrupted")
                return
            }
        }
    }

    /**
     * True when [error] means "this thread is being torn down" rather than "the network hiccuped".
     * Walks the cause chain because RetrofitHelper wraps IOExceptions in IllegalStateException.
     * SocketTimeoutException extends InterruptedIOException, and OkHttp's call timeout is a plain
     * InterruptedIOException("timeout") - both are network conditions, so both are retried.
     */
    internal fun isStopSignal(error: Throwable?): Boolean {
        var e = error
        var depth = 0

        while (e != null && depth++ < 10) {
            when {
                e is InterruptedException -> return true
                e is ClosedByInterruptException -> return true
                e is SocketTimeoutException -> return false
                e is InterruptedIOException -> return e.message != "timeout"
            }
            if (e.cause === e) {
                break
            }
            e = e.cause
        }

        return false
    }

    private fun openLiveChatInt(chatKey: String, onChatItem: OnChatItem, stopSignal: StopSignal, backoff: LiveChatBackoff): Int {
        var continuationKey: String? = chatKey
        var timeoutMs: Int?
        var goodPolls = 0

        while (true) {
            if (continuationKey.isNullOrEmpty() || stopSignal.isStopped()) {
                break
            }

            val chatResult = getLiveChatResult(continuationKey)
            val continuation = chatResult?.getContinuation()
            continuationKey = continuation?.continuation
            timeoutMs = continuation?.timeoutMs

            if (!continuationKey.isNullOrEmpty()) {
                goodPolls++
                if (backoff.onSuccess()) {
                    logNetPath("chat-recovered backoff reset")
                }
            }

            val actions = chatResult?.getActions()
            actions?.forEach { it?.let { onChatItem.onChatItem(ChatItemImpl(it)) } }

            if (timeoutMs != null && !sleepUnlessStopped(timeoutMs.toLong(), stopSignal)) {
                break
            }
        }

        return goodPolls
    }

    private fun getLiveChatResult(chatKey: String): LiveChatResult? {
        val chatQuery = LiveChatApiParams.getLiveChatQuery(chatKey)
        val wrapper = mApi.getLiveChat(chatQuery)
        return RetrofitHelper.get(wrapper)
    }

    /**
     * Sleeps in short slices so a disposal is honoured even when the thread isn't interrupted.
     * @return false if the stop signal fired or the thread was interrupted
     */
    private fun sleepUnlessStopped(delayMs: Long, stopSignal: StopSignal): Boolean {
        var remainingMs = delayMs

        try {
            while (remainingMs > 0) {
                if (stopSignal.isStopped()) {
                    return false
                }
                val sliceMs = min(remainingMs, SLEEP_SLICE_MS)
                Thread.sleep(sliceMs)
                remainingMs -= sliceMs
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt() // keep the flag for the outer loop
            return false
        }

        return !stopSignal.isStopped()
    }

    private fun describe(e: Throwable): String {
        var root: Throwable = e
        var depth = 0
        while (root.cause != null && root.cause !== root && depth++ < 10) {
            root = root.cause!!
        }
        return root.javaClass.simpleName
    }

    private fun logNetPath(message: String) {
        android.util.Log.d("NetPath", message)
    }

    interface OnChatItem {
        fun onChatItem(chatItem: ChatItem)
    }

    fun interface StopSignal {
        fun isStopped(): Boolean
    }

    internal fun interface Sleeper {
        /** @return false to end the loop (stopped or interrupted) */
        fun sleep(delayMs: Long, stopSignal: StopSignal): Boolean
    }
}

/**
 * NEWTUBE(chat-backoff): exponential reconnect delay for the live-chat poll, 1 s doubling to 30 s,
 * back to 1 s after a poll that yields a continuation.
 */
internal class LiveChatBackoff(private val initialMs: Long = INITIAL_MS, private val maxMs: Long = MAX_MS) {
    private var nextMs = initialMs

    fun nextDelayMs(): Long {
        val delayMs = nextMs
        nextMs = min(nextMs * 2, maxMs)
        return delayMs
    }

    /** @return true if the delay had grown (i.e. this success ends a failure streak) */
    fun onSuccess(): Boolean {
        val wasBackingOff = nextMs != initialMs
        nextMs = initialMs
        return wasBackingOff
    }

    companion object {
        const val INITIAL_MS = 1_000L
        const val MAX_MS = 30_000L
    }
}
