package com.liskovsoft.youtubeapi.app

import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.app.potoken.PoTokenService
import com.liskovsoft.youtubeapi.app.potokencloud.PoTokenCloudService
import com.liskovsoft.youtubeapi.app.potokennp2.PoTokenProviderImpl
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult
import com.liskovsoft.youtubeapi.app.potokennp2.misc.selectFactory
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import android.os.SystemClock

/**
 * PoTokenType
 *
 * `CONTENT` A poToken generated from videoId.
 * Used in DASH/SABR requests (e.g. `pot` param).
 * Previously used in player requests.
 *
 * `SESSION` A poToken generated from visitorData.
 * Usage is unknown. Previously used in DASH/SABR requests (e.g. `pot` param).
 */
internal object PoTokenGate {
    private const val TAG = "PoTokenGate"
    /**
     * Stays false here so TV behaviour is untouched: like every other mobile-only switch in this
     * codebase it is turned on by one explicit call from the phone flavor's Application (see
     * MobileMainApplication), never by a default in shared code. What it buys and what it
     * deliberately does NOT touch: [PoTokenUse.PLAYER_REQUEST] and [selectPoTokenSource].
     */
    @Volatile
    private var mPlayerPotEnabled = false
    /**
     * Single slot, keyed on ONE videoId at a time - see the eviction note on
     * [getWebContentPoToken]. Volatile because the mint runs on whichever thread walks the
     * /player ring while [warmUp] races it from its own thread; without it a reader can observe
     * a half-published reference. (Pre-existing, but the ANDROID_VR default makes this slot
     * load-bearing for signed-in playback rather than for web clients only.)
     */
    @Volatile
    private var mWebPoToken: PoTokenResult? = null
    @Volatile
    private var mWebPoTokenCreatedAtMs: Long = -1
    @Volatile
    private var mCacheResetTimeMs: Long = -1
    @Volatile
    private var mVisitorRotationAllowedAtMs: Long = -1
    /**
     * Rotating tears down and rebuilds the BotGuard WebView (~1s) and throws away a warm token
     * session, so it must stay rare. One rotation per challenge cooldown window is the intent.
     */
    private const val VISITOR_ROTATION_MIN_INTERVAL_MS = 15 * 60_000L

    init {
        PoTokenProviderImpl.poTokenFactory = selectFactory()
    }

    /** @see AppClient.isPlayerPotSupported */
    @JvmStatic
    fun setPlayerPotEnabled(enabled: Boolean) {
        mPlayerPotEnabled = enabled
    }

    /**
     * Single-slot cache, keyed on the videoId the token is bound to. A mint for a NEW videoId
     * evicts the previous entry, so walk the timeline before adding a consumer (CLAUDE.md: two
     * shipped bugs came from a newer write evicting the entry a feature depended on).
     *
     * Eviction is safe here because only [PoTokenResult.playerRequestPoToken] is per-video, and
     * it is consumed synchronously by the /player request that asked for it and never read again.
     * The two session-level fields survive: [PoTokenResult.visitorData] and
     * [PoTokenResult.streamingDataPoToken] are cached inside PoTokenProviderImpl behind
     * WebPoTokenGenLock and only recomputed when the generator is recreated or expires, so every
     * result carries the same values. Verified on the Pixel 9 trace of 2026-09-07: the mint for
     * videoId="" at 12:35:37.474 and the mint for "aqz-KE-bpKQ" at 12:35:51.920 returned
     * byte-identical streamingPot and visitor_data.
     *
     * Nor does an extra consumer add mints: within one ring walk every client asking for the SAME
     * videoId hits the cache, so the count stays at one per video regardless of ring order - which
     * matters because the ring now leads with different clients than it used to.
     */
    private fun getWebContentPoToken(videoId: String): String? {
        if (mWebPoToken?.videoId == videoId && !PoTokenProviderImpl.isWebPotExpired) {
            return mWebPoToken?.playerRequestPoToken
        }

        mWebPoToken = if (PoTokenProviderImpl.isWebPotSupported)
            PoTokenProviderImpl.getWebClientPoToken(videoId)
        else null
        markWebPoTokenCreated()

        return mWebPoToken?.playerRequestPoToken
    }

    private fun getWebSessionPoToken(): String? {
        return if (PoTokenProviderImpl.isWebPotSupported) {
            if (mWebPoToken == null) {
                mWebPoToken = PoTokenProviderImpl.getWebClientPoToken("")
                markWebPoTokenCreated()
            }
            mWebPoToken?.streamingDataPoToken
        } else PoTokenCloudService.getPoToken()
    }
    
    private fun updatePoToken() {
        if (PoTokenProviderImpl.isWebPotSupported) {
            //mNpPoToken = null // only refresh
            mWebPoToken = PoTokenProviderImpl.getWebClientPoToken("") // refresh and preload
            markWebPoTokenCreated()
        } else {
            PoTokenCloudService.updatePoToken()
        }
    }

    /**
     * Token for a googlevideo MEDIA URL. Web family only - see [selectPoTokenSource].
     */
    @JvmStatic
    @JvmOverloads
    fun getPoToken(client: AppClient, videoId: String? = null): String? =
        resolvePoToken(client, videoId, PoTokenUse.MEDIA_URL)

    /**
     * Token for the /player REQUEST body.
     *
     * ANDROID_VR is the only client that may receive one, and it stays OFF by default. yt-dlp's
     * `not_required_with_player_token` is set for android_vr on all three GVS protocols, so
     * attesting the request here should make the media URLs it returns stop needing a token.
     * Measured on the Pixel 9 on 2026-09-07 (one video per arm, debug.arc.player_client=
     * ANDROID_VR): it does not. Both arms reached first frame and both then died on the same
     * deep-range `load[E-http] code=403` about nine seconds in - pot off at req=854906+158684,
     * pot on at req=991541+181034. That is the wall yt-dlp recorded on 2026-08-17 ("ALL formats
     * ... are 403'd with version 1.65.10") before removing the client from its defaults entirely
     * (commit dae52d8, closes #17456); attesting does not buy it back.
     *
     * Nor do we still need it to: VISIONOS now leads the fallback ring and served 140 media loads
     * across two sessions in that same round with zero errors, so ANDROID_VR is a late fallback
     * rather than the route a signed-in open lands on. The flag remains for re-measuring if
     * YouTube's enforcement moves - `debug.arc.player_pot 1`.
     *
     * Minting is cheap when it happens: [warmUp] initializes the generator at app start (measured
     * 12:35:36.516 -> 12:35:37.459, ~943ms, a full 14s before the first /player) and a warm
     * content-bound mint measured 13ms (12:35:51.907 -> 51.920), shared with the web-family
     * client in the same walk - one mint per video, not one per client. Cheap is not free, and
     * for no measured benefit it stays off.
     */
    @JvmStatic
    fun getPlayerRequestPoToken(client: AppClient, videoId: String?): String? =
        resolvePoToken(client, videoId, PoTokenUse.PLAYER_REQUEST)

    private fun resolvePoToken(client: AppClient, videoId: String?, use: PoTokenUse): String? =
        when (selectPoTokenSource(client, videoId != null, use, mPlayerPotEnabled)) {
            PoTokenSource.WEB_CONTENT -> getWebContentPoToken(videoId!!)
            PoTokenSource.WEB_SESSION -> getWebSessionPoToken()
            PoTokenSource.NONE -> null
        }

    /**
     * Fire-and-forget WebView/BotGuard initialization so the first web-family /player request
     * finds the generator warm. This intentionally mints only Web tokens; a Web token must never
     * be attached to Android/TV/iOS MEDIA URLs as a cross-platform fallback - [getPoToken]
     * enforces that. Presenting one in a non-Web client's /player request body is a different
     * thing and is allowed for exactly one client; see [getPlayerRequestPoToken].
     */
    @JvmStatic
    fun warmUp() {
        Thread({
            try {
                getWebSessionPoToken()
            } catch (e: Throwable) {
                Log.e(TAG, "warmUp failed: ${e.message}")
            }
        }, "PoTokenWarmUp").start()
    }

    @JvmStatic
    fun getColdStartPoToken(client: AppClient, videoId: String): String? =
        if (client.isWebPotRequired) PoTokenService.generateColdStartToken(videoId) else null

    @JvmStatic
    fun getVisitorData(client: AppClient): String? {
        return when {
            client.isWebPotRequired -> getWebVisitorData()
            else -> null
        }
    }

    /**
     * A non-Web /player request may still use the anonymous Web visitor identity obtained before
     * that request (as current extractors do). This deliberately returns only visitorData; it does
     * not expose or attach the Web PO token to another platform. Keep the visitor sourced directly
     * from this token session: independently fetching/caching a second Web visitor reintroduced the
     * deep-range 403 on the Pixel 9.
     */
    @JvmStatic
    fun getWebVisitorDataForPlayer(): String? {
        getWebSessionPoToken()
        return getWebVisitorData()
    }

    /** Age of the visitor/token session used by Web-family and Android VR /player requests. */
    @JvmStatic
    fun getWebVisitorAgeMs(): Long = if (mWebPoTokenCreatedAtMs >= 0)
        SystemClock.elapsedRealtime() - mWebPoTokenCreatedAtMs else -1

    @JvmStatic
    fun isWebPotSupported() = PoTokenProviderImpl.isWebPotSupported

    @JvmStatic
    fun isWebPotExpired() = PoTokenProviderImpl.isWebPotExpired

    @JvmStatic
    fun resetCache(client: AppClient): Boolean {
        return when {
            client.isWebPotRequired -> resetWebCache()
            else -> false
        }
    }

    @JvmStatic
    fun resetCache() {
        resetWebCache()
    }

    /**
     * Abandons the current anonymous Web identity and forces the next web-pot session to mint a
     * fresh visitor. Called when the /player ring has seen the anonymous partition answer with bot
     * challenges: a plain [resetCache] only re-mints a token for the SAME persistent visitor, which
     * is the identity being challenged, so it can never clear the challenge on its own.
     *
     * Rate-limited to [VISITOR_ROTATION_MIN_INTERVAL_MS] and deliberately independent of
     * [resetWebCache]'s own 60s throttle. Returns true if a rotation was armed.
     *
     * Cost of rotating: a signed-out session's watch-time pings for subsequent videos credit the
     * new visitor, so signed-out history continuity breaks at this point. That is a strictly better
     * outcome than the alternative at the moment it fires, which is that nothing plays at all.
     * Browse/Home personalization is unaffected -- that rides AppService.visitorData, not this one.
     */
    @JvmStatic
    fun rotateWebVisitor(): Boolean {
        if (!PoTokenProviderImpl.isWebPotSupported) {
            return false
        }

        val nowMs = SystemClock.elapsedRealtime()
        if (mVisitorRotationAllowedAtMs >= 0 && nowMs < mVisitorRotationAllowedAtMs) {
            return false
        }
        mVisitorRotationAllowedAtMs = nowMs + VISITOR_ROTATION_MIN_INTERVAL_MS

        Log.d(TAG, "Rotating the anonymous web visitor identity")
        PoTokenProviderImpl.requestFreshVisitor()
        mWebPoToken = null
        mWebPoTokenCreatedAtMs = -1
        PoTokenProviderImpl.resetCache()

        return true
    }

    fun getWebVisitorData(): String? {
        return mWebPoToken?.visitorData
    }

    private fun resetWebCache(): Boolean {
        val currentTimeMs = System.currentTimeMillis()
        if (currentTimeMs < mCacheResetTimeMs)
            return false

        if (PoTokenProviderImpl.isWebPotSupported) {
            mWebPoToken = null
            mWebPoTokenCreatedAtMs = -1
            PoTokenProviderImpl.resetCache()
        } else
            PoTokenCloudService.resetCache()

        mCacheResetTimeMs = currentTimeMs + 60_000

        return true
    }

    private fun markWebPoTokenCreated() {
        mWebPoTokenCreatedAtMs = if (mWebPoToken != null) SystemClock.elapsedRealtime() else -1
    }
}
