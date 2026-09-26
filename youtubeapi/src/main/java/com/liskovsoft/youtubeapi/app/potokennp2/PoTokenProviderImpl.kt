package com.liskovsoft.youtubeapi.app.potokennp2

import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenProvider
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.liskovsoft.googlecommon.common.helpers.VisitorFingerprint
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
    // Volatile: [peekSessionVisitorData] reads it without the lock while a recreate is in flight.
    @Volatile
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenGenerator? = null
    // When the current session finished building (elapsedRealtime), for the web-pot-session line.
    private var webPoTokenSessionBuiltAtMs: Long = -1
    
    var poTokenFactory: PoTokenGenerator.Factory? = null

    // NEWTUBE(visitor-rotation): armed only by PoTokenGate.rotateWebVisitor. The next generator
    // recreate then mints a FRESH visitor instead of reusing the app's persistent one, scoped to
    // this web-pot session: AppService.visitorData keeps driving browse/Home personalization.
    // Dormant since 2026-09-25 (VideoInfoService.rotateAnonymousIdentity is not enabled): the
    // 2026-07-27 round that motivated it (42/42 anonymous calls rejected on one visitor with a
    // valid pot) also probed all 7 anonymous clients with a BRAND-NEW visitor and got 7/7
    // rejected, and after seven rotations on the 09-25 LTE wall none of 140 anonymous answers was
    // OK. Rotation did not rescue those walls; what caused them is unproven.
    @Volatile
    private var forceFreshVisitor = false
    // Bumped by anything that retires the session visitor, so a peek racing a rotation can tell.
    @Volatile
    private var visitorGeneration = 0

    fun requestFreshVisitor() {
        // Flag first: a peek that already passed the flag check then sees the generation move.
        forceFreshVisitor = true
        visitorGeneration++
    }

    /**
     * NEWTUBE(ttff): the visitor the web-pot session is using or is about to adopt, WITHOUT waiting
     * for BotGuard. The recreate block below assigns [webPoTokenVisitorData] first and only then
     * spends ~1-1.5 s building the WebView generator, and when no rotation is armed it adopts
     * AppService.visitorData verbatim - so before that block runs the answer is already known.
     * Returns null whenever it cannot be sure (rotation armed, no persisted visitor yet), and the
     * caller then takes the blocking path.
     */
    fun peekSessionVisitorData(): String? {
        // Snapshot the generation before anything else: rotateWebVisitor bumps it (and arms the
        // flag) before clearing the old visitor, so the re-check below catches a rotation mid-read.
        val generation = visitorGeneration
        if (forceFreshVisitor || !isWebPotSupported) {
            return null
        }
        val visitor = webPoTokenVisitorData ?: AppService.instance().visitorData
        // A rotation that landed while reading would make this the retired identity.
        return if (generation == visitorGeneration && !forceFreshVisitor) visitor else null
    }

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
                    // NEWTUBE(visitor): why this session is being (re)built, captured before the
                    // state below changes - see logWebPotSession.
                    val startedMs = SystemClock.elapsedRealtime()
                    val reason = webPotSessionReason(
                        hadGenerator = webPoTokenGenerator != null,
                        forceRecreate = forceRecreate,
                        rotation = forceFreshVisitor,
                        stateCleared = webPoTokenVisitorData == null || webPoTokenStreamingPot == null
                    )
                    val previousAgeMs =
                        if (webPoTokenSessionBuiltAtMs >= 0) startedMs - webPoTokenSessionBuiltAtMs else -1
                    val visitorSource: String

                    // NEWTUBE(anonymous-recs): bind the whole web-pot session to the app's
                    // persistent visitor instead of minting a throwaway one per session. The
                    // watch-time pings credit whatever visitor the /player call used; with a
                    // throwaway, signed-out history fragments across dead identities and the
                    // anonymous Home never personalizes. Pot, /player and streaming URLs still
                    // share ONE visitor (the deep-range-403 invariant from the Pixel round -
                    // see PoTokenGate.getWebVisitorDataForPlayer). Fallback keeps the old
                    // behavior when AppInfo hasn't produced a visitor yet.
                    // Rotation inverts the preference for exactly one recreate: mint a brand new
                    // visitor and only fall back to the persistent one if minting fails, so a
                    // challenged identity is genuinely left behind rather than re-adopted.
                    // Publish the fresh visitor BEFORE disarming the rotation: peekSessionVisitorData
                    // reads both without this lock, and in between it would hand out the very
                    // identity being rotated away from.
                    if (forceFreshVisitor) {
                        Log.d(TAG, "Rotating web visitor after a bot challenge")
                        val fresh = VisitorService.getVisitorData()
                        visitorSource = if (fresh != null) "visitor-api" else "app"
                        webPoTokenVisitorData = fresh ?: AppService.instance().visitorData
                        forceFreshVisitor = false
                    } else {
                        val persistent = AppService.instance().visitorData
                        visitorSource = if (persistent != null) "app" else "visitor-api"
                        webPoTokenVisitorData = persistent ?: VisitorService.getVisitorData()
                    }

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

                    try {
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
                    } catch (e: Throwable) {
                        logWebPotSession("failed", reason, visitorSource, previousAgeMs, startedMs,
                            " error=" + e.javaClass.simpleName)
                        throw e
                    }
                    webPoTokenSessionBuiltAtMs = SystemClock.elapsedRealtime()
                    logWebPotSession("new", reason, visitorSource, previousAgeMs, startedMs,
                        " generator=" + webPoTokenGenerator!!.javaClass.simpleName)
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
                android.util.Log.w("NetPath", "web-pot-session player-mint-failed error="
                        + throwable.javaClass.simpleName + " action=recreate")
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

    /**
     * NEWTUBE(visitor): one secret-free NetPath line per web-pot session (re)build, so a future
     * natural bot wall can be read against the token context instead of guessed at: why the
     * session was rebuilt, where its visitor came from (the persistent app visitor, or the
     * visitor_id API on rotation / fallback), the visitor's identity fingerprint (hash of the id,
     * never the id), how long the previous session lived, how long the build took, and the token
     * binding this implementation uses (streaming token bound to the visitor, player token bound to
     * the video). No token or raw visitor ever reaches the line.
     */
    private fun logWebPotSession(outcome: String, reason: String, visitorSource: String,
                                 previousAgeMs: Long, startedMs: Long, extra: String) {
        android.util.Log.d("NetPath", "web-pot-session " + outcome + " reason=" + reason
                + " visitorSource=" + visitorSource
                + " visitor=" + VisitorFingerprint.of(webPoTokenVisitorData)
                + " prevAgeMs=" + previousAgeMs
                + " buildMs=" + (SystemClock.elapsedRealtime() - startedMs)
                + " binding=streaming:visitor,player:video" + extra)
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null

    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null

    override fun isWebPotExpired() = isWebPotSupported && webPoTokenGenerator?.isExpired() ?: true

    override fun isWebPotSupported() = webViewSupported && !webViewBadImpl

    fun resetCache() {
        visitorGeneration++
        webPoTokenVisitorData = null
        webPoTokenStreamingPot = null
    }
}

/**
 * NEWTUBE(visitor): why a web-pot session is being (re)built. Precedence: nothing built yet, a
 * mint failed on the current generator, a rotation was armed, the state was cleared by a reset
 * (PoTokenGate.resetCache - e.g. playback recovery), otherwise the generator expired.
 */
internal fun webPotSessionReason(
    hadGenerator: Boolean,
    forceRecreate: Boolean,
    rotation: Boolean,
    stateCleared: Boolean
): String = when {
    !hadGenerator -> "initial"
    forceRecreate -> "mint-failed"
    rotation -> "rotation"
    stateCleared -> "reset"
    else -> "expired"
}
