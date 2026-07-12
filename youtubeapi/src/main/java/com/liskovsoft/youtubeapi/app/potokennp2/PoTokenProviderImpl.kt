package com.liskovsoft.youtubeapi.app.potokennp2

import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenProvider
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult
import android.os.Handler
import android.os.Looper
import com.liskovsoft.sharedutils.helpers.DeviceHelpers
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.app.potokennp2.generators.PoTokenWebView
import com.liskovsoft.youtubeapi.app.potokennp2.core.BadWebViewException
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenException
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenGenerator
import com.liskovsoft.youtubeapi.app.potokennp2.visitor.VisitorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object PoTokenProviderImpl : PoTokenProvider {
    val TAG = PoTokenProviderImpl::class.simpleName
    private val webViewSupported by lazy { DeviceHelpers.isWebViewSupported() }
    private var webViewBadImpl = false // whether the system has a bad WebView implementation

    private object WebPoTokenGenLock
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenGenerator? = null
    
    var poTokenFactory: PoTokenGenerator.Factory? = null
    
    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        if (!isWebPotSupported) {
            return null
        }

        try {
            return getWebClientPoToken(videoId = videoId, forceRecreate = false)
        } catch (e: RuntimeException) {
            // RxJava's Single wraps exceptions into RuntimeErrors, so we need to unwrap them here
            when (val cause = e.cause) {
                is BadWebViewException -> {
                    Log.e(TAG, "Could not obtain poToken because WebView is broken", e)
                    webViewBadImpl = true
                    return null
                }
                null -> throw e
                else -> throw cause // includes PoTokenException
            }
        }
    }

    /**
     * @param forceRecreate whether to force the recreation of [webPoTokenGenerator], to be used in
     * case the current [webPoTokenGenerator] threw an error last time
     * [PoTokenGenerator.generatePoToken] was called
     */
    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        // just a helper class since Kotlin does not have builtin support for 4-tuples
        data class Quadruple<T1, T2, T3, T4>(val t1: T1, val t2: T2, val t3: T3, val t4: T4)

        val (poTokenGenerator, visitorData, streamingPot, hasBeenRecreated) =
            synchronized(WebPoTokenGenLock) {
                val shouldRecreate = webPoTokenGenerator == null || webPoTokenVisitorData == null || webPoTokenStreamingPot == null ||
                   forceRecreate || webPoTokenGenerator!!.isExpired()

                if (shouldRecreate) {
                    // MOD: my visitor data
                    //webPoTokenVisitorData = AppService.instance().visitorData
                    webPoTokenVisitorData = VisitorService.getVisitorData()

                    val latch = if (webPoTokenGenerator != null) CountDownLatch(1) else null

                    // close the current webPoTokenGenerator on the main thread
                    webPoTokenGenerator?.let {
                        Handler(Looper.getMainLooper()).post {
                            try {
                                it.close()
                            } catch (_: Exception) {
                                // NullPointerException: android.webkit.WebViewClassic.clearHistory (WebViewClassic.java:3670)
                            } finally {
                                latch?.countDown()
                            }
                        }
                    }

                    latch?.await(3, TimeUnit.SECONDS)

                    //// create a new webPoTokenGenerator
                    //webPoTokenGenerator = (poTokenFactory ?: PoTokenWebView)
                    //    .newPoTokenGenerator(AppService.instance().context)

                    // create a new webPoTokenGenerator
                    val context = AppService.instance().context
                    webPoTokenGenerator = try {
                        (poTokenFactory ?: PoTokenWebView)
                            .newPoTokenGenerator(context)
                    } catch (e: Exception) {
                        when (e) {
                            is BadWebViewException, is PoTokenException -> {
                                // BadWebViewException: Error invoking onRunBotguardResult
                                // PoTokenException: mintCallback is not defined
                                // PoTokenWebView2/3 may fail due to too many requests. Switching to the default variant.
                                if (poTokenFactory != null && poTokenFactory != PoTokenWebView)
                                    PoTokenWebView.newPoTokenGenerator(context)
                                else
                                    throw e
                            }
                            else -> throw e
                        }
                    }

                    // The streaming poToken needs to be generated exactly once before generating
                    // any other (player) tokens.
                    webPoTokenStreamingPot = webPoTokenGenerator!!
                        .generatePoToken(webPoTokenVisitorData!!)
                }

                return@synchronized Quadruple(
                    webPoTokenGenerator!!,
                    webPoTokenVisitorData!!,
                    webPoTokenStreamingPot!!,
                    shouldRecreate
                )
            }

        val playerPot = try {
            // Not using synchronized here, since poTokenGenerator would be able to generate
            // multiple poTokens in parallel if needed. The only important thing is for exactly one
            // visitorData/streaming poToken to be generated before anything else.
            if (videoId.isEmpty()) "" else poTokenGenerator.generatePoToken(videoId)
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                // the poTokenGenerator has just been recreated (and possibly this is already the
                // second time we try), so there is likely nothing we can do
                throw throwable
            } else {
                // retry, this time recreating the [webPoTokenGenerator] from scratch;
                // this might happen for example if NewPipe goes in the background and the WebView
                // content is lost
                Log.e(TAG, "Failed to obtain poToken, retrying", throwable)
                return getWebClientPoToken(videoId = videoId, forceRecreate = true)
            }
        }

        Log.d(
            TAG,
            "poToken for $videoId: playerPot=$playerPot, " +
                    "streamingPot=$streamingPot, visitor_data=$visitorData"
        )

        return PoTokenResult(videoId, visitorData, playerPot, streamingPot)
    }

    private var appStreamingPot: String? = null
    private var appStreamingPotVisitor: String? = null

    /**
     * Streaming pot bound to the CALLER-supplied visitorData (the app's InnerTube visitor
     * session) instead of the WebView's own. googlevideo validates the URL `pot` param against
     * the visitor session that minted the URLs, and non-web clients' /player calls run under
     * the app session — the standard web streaming pot (bound to the WebView's visitorData)
     * does NOT validate their URLs (verified on-device: the ~60s pot-less grace wall 403s
     * continued with the web pot attached). Reuses the warm generator (~10ms per mint); caches
     * one pot per visitorData (the app visitor rotates with the ~10h app-info refresh).
     */
    fun getAppClientStreamingPot(visitorData: String): String? {
        if (!isWebPotSupported || visitorData.isEmpty()) {
            return null
        }

        synchronized(WebPoTokenGenLock) {
            if (appStreamingPotVisitor == visitorData && appStreamingPot != null
                && webPoTokenGenerator?.isExpired() == false) {
                return appStreamingPot
            }
        }

        // Ensure the generator is initialized. This also mints the web streaming pot first,
        // preserving the "exactly one streaming pot before any other token" init ordering.
        getWebClientPoToken("") ?: return null

        return synchronized(WebPoTokenGenLock) {
            try {
                appStreamingPot = webPoTokenGenerator?.generatePoToken(visitorData)
                appStreamingPotVisitor = visitorData
                appStreamingPot
            } catch (e: Throwable) {
                Log.e(TAG, "getAppClientStreamingPot failed: ${e.message}")
                null
            }
        }
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    override fun isWebPotExpired() = isWebPotSupported && webPoTokenGenerator?.isExpired() ?: true

    override fun isWebPotSupported() = webViewSupported && !webViewBadImpl

    fun resetCache() {
        webPoTokenVisitorData = null
        webPoTokenStreamingPot = null
        appStreamingPot = null
        appStreamingPotVisitor = null
    }
}
