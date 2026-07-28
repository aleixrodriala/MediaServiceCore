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
    private var mPlayerPotEnabled = false
    private var mWebPoToken: PoTokenResult? = null
    private var mWebPoTokenCreatedAtMs: Long = -1
    private var mCacheResetTimeMs: Long = -1
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

    @JvmStatic
    @JvmOverloads
    fun getPoToken(client: AppClient, videoId: String? = null): String? {
        return when {
            client.isWebPotRequired -> if (videoId != null) getWebContentPoToken(videoId) else getWebSessionPoToken()
            // Off by default: minting this costs a BotGuard round on a path whose whole point is
            // to be token-free, and the enforcement it guards against is intermittent. Measure
            // with debug.arc.player_pot before considering it a default.
            client.isPlayerPotSupported && mPlayerPotEnabled ->
                if (videoId != null) getWebContentPoToken(videoId) else null
            else -> null
        }
    }

    /**
     * Fire-and-forget WebView/BotGuard initialization so the first web-family /player request
     * finds the generator warm. This intentionally mints only Web tokens; a Web token must never
     * be attached to Android/TV/iOS media URLs as a cross-platform fallback.
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
