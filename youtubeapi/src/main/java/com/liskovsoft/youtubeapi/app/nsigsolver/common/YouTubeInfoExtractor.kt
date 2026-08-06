package com.liskovsoft.youtubeapi.app.nsigsolver.common

import kotlinx.coroutines.runBlocking

internal object YouTubeInfoExtractor: InfoExtractor() {
    val cache: CacheService = CacheService

    private class PlayerMemo {
        var url: String? = null
        var body: String? = null
    }

    /**
     * One player JS body is ~680 KB and a single PlayerDataExtractor construction asks for the SAME
     * url twice on the cold path: the challenge provider resolves it to build the sig/n solver, and
     * then the cpn/signatureTimestamp extraction re-reads it. There is no HTTP cache here and the
     * whole body is read into memory, all of it under AppServiceIntCached's player lock — so every
     * other /player in the process waits out both downloads (~18s each on a 300 kbps link).
     *
     * The memo is opened by [withPlayerMemo] around that construction and dropped when it returns,
     * so the body is never retained past the open that needed it; it is thread-scoped because the
     * whole point is a within-one-construction reuse (both fetches run on the constructing thread —
     * [loadPlayer] blocks it), not a shared cache with a lifetime and eviction policy to reason
     * about.
     */
    private val playerMemo = ThreadLocal<PlayerMemo?>()

    fun <T> withPlayerMemo(block: () -> T): T {
        if (playerMemo.get() != null) { // already inside a scope: let the outer one own the memo
            return block()
        }

        playerMemo.set(PlayerMemo())
        try {
            return block()
        } finally {
            playerMemo.remove()
        }
    }

    fun loadPlayer(playerUrl: String): String {
        val memo = playerMemo.get()
        val memoized = if (memo != null && memo.url == playerUrl) memo.body else null
        if (memoized != null) {
            android.util.Log.d("NetPath", "player-js memo-hit kb=" + (memoized.length / 1024))
            return memoized
        }

        val startMs = android.os.SystemClock.elapsedRealtime()
        val content = runBlocking { downloadWebpage(playerUrl) }
        android.util.Log.d("NetPath", "player-js fetch kb=" + (content.length / 1024)
                + " ms=" + (android.os.SystemClock.elapsedRealtime() - startMs)
                + " memo=" + (if (memo != null) "y" else "n"))

        if (memo != null) {
            memo.url = playerUrl
            memo.body = content
        }

        return content
    }

    fun loadPlayerSilent(playerUrl: String): String? {
        return try {
            loadPlayer(playerUrl)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun downloadWebpageWithRetries(url: String, errorMsg: String? = null): String = runBlocking {
        return@runBlocking downloadWebpage(url, tries = 3, errorMsg = errorMsg)
    }

    fun downloadWebpageSilent(url: String): String? {
        return try {
            downloadWebpageWithRetries(url)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}