package com.liskovsoft.youtubeapi.app

import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.youtubeapi.app.potoken.PoTokenService
import com.liskovsoft.youtubeapi.app.potokencloud.PoTokenCloudService
import com.liskovsoft.youtubeapi.app.potokennp2.PoTokenProviderImpl
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenResult
import com.liskovsoft.youtubeapi.app.potokennp2.misc.selectFactory
import com.liskovsoft.youtubeapi.common.helpers.AppClient

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
    private var mWebPoToken: PoTokenResult? = null
    private var mCacheResetTimeMs: Long = -1

    init {
        PoTokenProviderImpl.poTokenFactory = selectFactory()
    }

    private fun getWebContentPoToken(videoId: String): String? {
        if (mWebPoToken?.videoId == videoId && !PoTokenProviderImpl.isWebPotExpired) {
            return mWebPoToken?.playerRequestPoToken
        }

        mWebPoToken = if (PoTokenProviderImpl.isWebPotSupported)
            PoTokenProviderImpl.getWebClientPoToken(videoId)
        else null

        return mWebPoToken?.playerRequestPoToken
    }

    private fun getWebSessionPoToken(): String? {
        return if (PoTokenProviderImpl.isWebPotSupported) {
            if (mWebPoToken == null)
                mWebPoToken = PoTokenProviderImpl.getWebClientPoToken("")
            mWebPoToken?.streamingDataPoToken
        } else PoTokenCloudService.getPoToken()
    }
    
    private fun updatePoToken() {
        if (PoTokenProviderImpl.isWebPotSupported) {
            //mNpPoToken = null // only refresh
            mWebPoToken = PoTokenProviderImpl.getWebClientPoToken("") // refresh and preload
        } else {
            PoTokenCloudService.updatePoToken()
        }
    }

    @JvmStatic
    @JvmOverloads
    fun getPoToken(client: AppClient, videoId: String? = null): String? {
        return when {
            client.isWebPotRequired -> if (videoId != null) getWebContentPoToken(videoId) else getWebSessionPoToken()
            else -> null
        }
    }

    /**
     * GVS/session pot for the googlevideo MEDIA URLS of clients whose /player flow mints no
     * pot (isWebPotRequired=false: ANDROID_VR/TV/IOS/...). Carrier CGNAT IPs enforce a pot on
     * every client's media URLs — each pot-less stream serves ~60s of media, then 403s
     * (Source error → visible reload, repeating until a web-pot client wins the ring).
     * Residential IPs rarely enforce, which is why TV boxes and the emulator never hit this.
     * The pot MUST be minted against the APP's visitorData (the session the non-web /player
     * calls run under — see QueryBuilder's appService.visitorData fallback): the WebView's own
     * streaming pot does not validate these URLs (verified on-device).
     * Best-effort: never throws. Blocks only while the WebView generator initializes
     * (~1.5–2.5s cold, ~10ms per token once warm) — warmUp() at app start hides the cold case.
     */
    @JvmStatic
    fun getMediaSessionPoToken(): String? {
        return try {
            PoTokenProviderImpl.getAppClientStreamingPot(AppService.instance().visitorData ?: "")
        } catch (e: Throwable) {
            Log.e(TAG, "getMediaSessionPoToken failed: ${e.message}")
            null
        }
    }

    /**
     * Fire-and-forget WebView-generator init + app-visitor pot mint so the first
     * getMediaSessionPoToken() on the open path finds everything warm. Call once at app start,
     * off the critical path. (getMediaSessionPoToken never throws; reading the app visitorData
     * may block on the persisted-app-info load, which is fine on this background thread.)
     */
    @JvmStatic
    fun warmUp() {
        Thread({
            getMediaSessionPoToken()
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

    fun getWebVisitorData(): String? {
        return mWebPoToken?.visitorData
    }

    private fun resetWebCache(): Boolean {
        val currentTimeMs = System.currentTimeMillis()
        if (currentTimeMs < mCacheResetTimeMs)
            return false

        if (PoTokenProviderImpl.isWebPotSupported) {
            mWebPoToken = null
            PoTokenProviderImpl.resetCache()
        } else
            PoTokenCloudService.resetCache()

        mCacheResetTimeMs = currentTimeMs + 60_000

        return true
    }
}