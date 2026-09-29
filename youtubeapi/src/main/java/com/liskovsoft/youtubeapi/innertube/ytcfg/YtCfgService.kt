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
     * NEWTUBE(embed-reroll): when this process last re-rolled the identity (wall clock, under
     * [identityLock]); 0 = never. Carried into every identity fetched while it is recent, and
     * persisted with it, so a re-roll's budget survives a 152 refetch and a new process.
     */
    private var lastRerollAtMs = 0L

    /** Test hooks: where the re-roll's background fetch runs, and what fetches the embed page. */
    @JvmField
    @Volatile
    internal var rerollExecutor: (Runnable) -> Unit = { task ->
        Thread(task, "EmbedReroll").apply { isDaemon = true }.start()
    }
    @JvmField
    @Volatile
    internal var embedPageFetcher: (String) -> JsonObject? = { videoId ->
        downloadYtCfg(AppClient.WEB_EMBED, videoId)
    }

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
    class EmbedIdentity @JvmOverloads constructor(
        @JvmField val encryptedHostFlags: String,
        @JvmField val visitorData: String,
        @JvmField val fetchedAtMs: Long,
        /** NEWTUBE(embed-reroll): the re-roll whose budget this identity carries; 0 = none. */
        @JvmField val rerolledAtMs: Long = 0L
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
        val restored = EmbedIdentityPersistence.restore(nowMs, EMBED_IDENTITY_TTL_MS)
        synchronized(identityLock) {
            // NEWTUBE(embed-reroll): the budget an earlier process left, with or without a pair.
            lastRerollAtMs = maxOf(lastRerollAtMs, EmbedIdentityPersistence.restoredRerolledAtMs())
        }
        restored?.let {
            synchronized(identityLock) {
                if (invalidations == epoch)
                    cachedEmbedIdentity = it
                lastRerollAtMs = maxOf(lastRerollAtMs, it.rerolledAtMs)
            }
            android.util.Log.d("NetPath",
                "embed-identity source=restored ageMin=" + (nowMs - it.fetchedAtMs) / 60_000)
            return it
        }

        if (videoId == null)
            return null

        val ytCfg = try {
            embedPageFetcher(videoId)
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

        val fetchedAtMs = System.currentTimeMillis()
        val rerollBudget = synchronized(identityLock) {
            if (lastRerollAtMs > 0 && fetchedAtMs - lastRerollAtMs in 0 until EMBED_IDENTITY_TTL_MS)
                lastRerollAtMs else 0L
        }
        return EmbedIdentity(flags, visitorData, fetchedAtMs, rerollBudget).also {
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
            // NEWTUBE(embed-reroll): a recent re-roll's budget stays on disk without the pair.
            EmbedIdentityPersistence.clear(recentReroll(System.currentTimeMillis()))
        }
    }

    /** [lastRerollAtMs] while it still holds a budget, else 0. Under [identityLock]. */
    private fun recentReroll(nowMs: Long): Long =
        if (lastRerollAtMs > 0 && nowMs - lastRerollAtMs in 0 until EMBED_IDENTITY_TTL_MS) lastRerollAtMs else 0L

    /**
     * NEWTUBE(embed-reroll): the identity just used for [videoId] got a SABR-only answer (formats
     * without URLs, HLS only). That is its visitor's bucket, not the video: 11 of 60 logged visitors
     * were SABR-only every time and 49 never (netbench r11 analysis, 3.4b), and a persisted one keeps
     * every WEB_EMBED open on HLS (~550 ms slower to the first frame) for 6 h. The answer plays as it
     * is; this drops the identity, both copies, and fetches a new one off the walk, so the next
     * WEB_EMBED ask gets a new visitor. At most once per [EMBED_IDENTITY_TTL_MS]: an identity a
     * re-roll fetched is never re-rolled (its budget is its own lifetime), and a new visitor that is
     * bucketed too keeps HLS until then. Never in a loop, never in the walk; 152 invalidates as before.
     * Returns whether it re-rolled.
     */
    @JvmStatic
    @JvmOverloads
    fun rerollEmbedIdentity(videoId: String?, usedVisitorData: String? = null): Boolean {
        if (videoId == null)
            return false
        val nowMs = System.currentTimeMillis()
        val dropped = synchronized(identityLock) {
            val current = cachedEmbedIdentity
            if (current == null) {
                android.util.Log.d("NetPath", "embed-identity reroll-skip reason=no-identity video=$videoId")
                return false
            }
            // Only the identity that got the SABR-only answer: a 152 or another walk may have
            // replaced it since, and the newer one says nothing yet.
            if (usedVisitorData != null && usedVisitorData != current.visitorData) {
                android.util.Log.d("NetPath", "embed-identity reroll-skip reason=replaced video=$videoId")
                return false
            }
            val last = maxOf(lastRerollAtMs, current.rerolledAtMs)
            if (last > 0 && nowMs - last in 0 until EMBED_IDENTITY_TTL_MS) {
                android.util.Log.d("NetPath", "embed-identity reroll-skip reason=budget video=$videoId"
                        + " lastRerollMin=" + (nowMs - last) / 60_000)
                return false
            }
            lastRerollAtMs = nowMs
            invalidations++
            cachedEmbedIdentity = null
            // The budget goes to disk now, before the new pair exists: a process that dies before
            // the background fetch still knows it re-rolled.
            EmbedIdentityPersistence.clear(nowMs)
            current
        }
        android.util.Log.d("NetPath", "embed-identity reroll reason=sabr-only video=$videoId"
                + " ageMin=" + (nowMs - dropped.fetchedAtMs) / 60_000)
        try {
            rerollExecutor(Runnable {
                val fresh = try {
                    getEmbedIdentity(videoId)
                } catch (e: Exception) {
                    null
                }
                android.util.Log.d("NetPath", "embed-identity reroll-fetched ok=" + (if (fresh != null) "y" else "n"))
            })
        } catch (e: Throwable) {
            // The next WEB_EMBED ask fetches it in the walk instead, as after a 152.
            android.util.Log.w("NetPath", "embed-identity reroll-fetch not started error=" + e.javaClass.simpleName)
        }
        return true
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
