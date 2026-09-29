package com.liskovsoft.youtubeapi.innertube.ytcfg

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.innertube.utils.traverseObj
import java.util.concurrent.TimeUnit

object YtCfgService {
    private val api = RetrofitHelper.create(YtCfgApi::class.java)
    private val EMBED_IDENTITY_TTL_MS = TimeUnit.HOURS.toMillis(6)
    @Volatile
    private var cachedEmbedIdentity: EmbedIdentity? = null
    /**
     * NEWTUBE(embed-persist): bumped by every invalidation, under [identityLock]. An identity that
     * was restored or fetched while one happened is used for its own request but never cached or
     * persisted, so a refused pair cannot come back through an ask that was already in flight.
     */
    private var invalidations = 0
    private val identityLock = Any()

    /**
     * NEWTUBE(web-embed-identity): what a WEB_EMBEDDED_PLAYER /player request needs from the embed
     * page. YouTube enforces encryptedHostFlags on embeds, and the flags are bound to the visitor
     * the page was served to: flags + that page's visitorData -> status OK with working formats;
     * flags + any other visitor (the app's shared Web visitor, which is what used to be sent) or
     * no visitor -> "This video is unavailable - Error code: 152 - 18", on every video. That was
     * the whole reason WEB_EMBED looked dead from 2026-09 on. The pair is not bound to the video
     * (one page served every video probed), so it is cached and reused. Measured 2026-09-28 against
     * the /player endpoint directly; yt-dlp's web_embedded sends the same pair.
     */
    class EmbedIdentity(
        @JvmField val encryptedHostFlags: String,
        @JvmField val visitorData: String,
        @JvmField val fetchedAtMs: Long
    )

    @JvmStatic
    fun getEmbedIdentity(videoId: String?): EmbedIdentity? {
        val nowMs = System.currentTimeMillis()
        cachedEmbedIdentity?.let {
            if (EmbedIdentityPersistence.isFresh(it, nowMs, EMBED_IDENTITY_TTL_MS))
                return it
        }

        val epoch = synchronized(identityLock) { invalidations }
        // NEWTUBE(embed-persist): a new process's first ask takes the pair an earlier process
        // fetched while it is inside the TTL of that fetch, instead of paying the embed page again
        // (218-236 ms warm, ~390 ms cold on the Pixel over LTE; see EmbedIdentityPersistence).
        EmbedIdentityPersistence.restore(nowMs, EMBED_IDENTITY_TTL_MS)?.let {
            synchronized(identityLock) {
                if (invalidations == epoch)
                    cachedEmbedIdentity = it
            }
            android.util.Log.d("NetPath",
                "embed-identity source=restored ageMin=" + (nowMs - it.fetchedAtMs) / 60_000)
            return it
        }

        if (videoId == null)
            return null

        val ytCfg = try {
            downloadYtCfg(AppClient.WEB_EMBED, videoId)
        } catch (e: Exception) {
            null
        } ?: return null

        val flags = traverseObj(
            ytCfg,
            "WEB_PLAYER_CONTEXT_CONFIGS",
            "WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER",
            "encryptedHostFlags"
        )?.asString
        val visitorData = traverseObj(ytCfg, "VISITOR_DATA")?.asString
            ?: traverseObj(ytCfg, "INNERTUBE_CONTEXT", "client", "visitorData")?.asString

        if (flags.isNullOrEmpty() || visitorData.isNullOrEmpty())
            return null

        return EmbedIdentity(flags, visitorData, System.currentTimeMillis()).also {
            synchronized(identityLock) {
                if (invalidations == epoch) {
                    cachedEmbedIdentity = it
                    EmbedIdentityPersistence.save(it)
                }
            }
            android.util.Log.d("NetPath", "embed-identity source=fetched ms=" + (it.fetchedAtMs - nowMs))
        }
    }

    /**
     * Next WEB_EMBED request fetches a fresh page (its answer came back refused). NEWTUBE(embed-persist):
     * the persisted copy goes too, or the next process would restore the refused pair.
     */
    @JvmStatic
    fun invalidateEmbedIdentity() {
        synchronized(identityLock) {
            invalidations++
            cachedEmbedIdentity = null
            EmbedIdentityPersistence.clear()
        }
    }

    /**
     * https://github.com/yt-dlp/yt-dlp/blob/48a61d0f38b156785d24df628d42892441e008c4/yt_dlp/extractor/youtube/_base.py#L956
     *
     * https://github.com/yt-dlp/yt-dlp/blob/48a61d0f38b156785d24df628d42892441e008c4/yt_dlp/extractor/youtube/_video.py#L3876
     */
    private fun downloadYtCfg(client: AppClient, videoId: String?): JsonObject? {
        val configUrl = client.getRefererUrl(videoId) ?: return null
        val wrapper = api.getYtCfg(configUrl, client.userAgent)
        val ytCfg = RetrofitHelper.get(wrapper)?.ytCfg ?: return null

        return JsonParser.parseString(ytCfg).asJsonObject
    }
}
