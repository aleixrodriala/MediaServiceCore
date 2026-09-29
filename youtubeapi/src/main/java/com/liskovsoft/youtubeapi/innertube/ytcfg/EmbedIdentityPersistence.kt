package com.liskovsoft.youtubeapi.innertube.ytcfg

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * NEWTUBE(embed-persist): keeps WEB_EMBED's embed identity ([YtCfgService.EmbedIdentity]) across
 * process restarts, inside the same 6 h TTL, measured from the page fetch that produced it.
 *
 * Measured on a Pixel 9 over LTE (2026-09-29, netbench ttff-analysis, section 3.4): the identity lived in
 * memory only, so every new process fetched the embed page and parsed its ytcfg before its first
 * WEB_EMBED /player - 218-236 ms after a warm connection, ~390 ms cold - although the pair is not
 * bound to the video and one page serves for hours.
 *
 * A persisted pair that has gone stale is answered "Error code: 152" like an in-memory one, and
 * [YtCfgService.invalidateEmbedIdentity] clears both copies through [clear]: one refetch, as before.
 *
 * Kept out of [YtCfgService] on purpose: that object builds its Retrofit API - and with it the
 * shared OkHttp client - when it loads, and the phone sets the store from Application.onCreate.
 * Building that client early is what once pinned InnerTube to HTTP/1.1. No Android types here, so
 * the rules are plain unit tests.
 */
object EmbedIdentityPersistence {
    /** Supplied by the phone app; TV never sets one, so its identity stays in memory only. */
    interface Store {
        /** Last saved snapshot, or null. Called at most once per process, on a /player worker thread. */
        fun load(): String?

        /** Stores a snapshot; null clears it. Must not block (SharedPreferences.apply). */
        fun save(snapshot: String?)
    }

    private const val VERSION = 1

    @Volatile
    private var store: Store? = null
    /** Guarded by this object: the persisted copy is read (and handed out) once per process. */
    private var restoreDone = false

    /** Phone flavor only, once at process start; null keeps the identity in memory only. */
    @JvmStatic
    @Synchronized
    fun setStore(store: Store?) {
        this.store = store
        restoreDone = false
    }

    /**
     * The identity an earlier process fetched, handed out on this process's first WEB_EMBED ask
     * only, and only while it is within [ttlMs] of its original fetch. A stale, future-dated or
     * damaged copy is removed from the store.
     */
    @JvmStatic
    @Synchronized
    fun restore(nowMs: Long, ttlMs: Long): YtCfgService.EmbedIdentity? {
        if (restoreDone) {
            return null
        }
        restoreDone = true
        val store = store ?: return null
        val snapshot = try {
            store.load()
        } catch (e: RuntimeException) {
            null
        } ?: return null
        val identity = decode(snapshot)
        if (identity != null && isFresh(identity, nowMs, ttlMs)) {
            return identity
        }
        saveQuietly(store, null)
        return null
    }

    /** A freshly fetched identity, for the next process. Ordered with [clear] by this lock. */
    @JvmStatic
    @Synchronized
    fun save(identity: YtCfgService.EmbedIdentity) {
        store?.let { saveQuietly(it, encode(identity)) }
    }

    /**
     * The identity was refused or reset: the persisted copy goes with the in-memory one, and a copy
     * this process has not restored yet can no longer come back either.
     */
    @JvmStatic
    @Synchronized
    fun clear() {
        restoreDone = true
        store?.let { saveQuietly(it, null) }
    }

    /** Fresh = fetched at most [ttlMs] ago, and not in the future (a wall clock set back). */
    @JvmStatic
    fun isFresh(identity: YtCfgService.EmbedIdentity, nowMs: Long, ttlMs: Long): Boolean {
        val ageMs = nowMs - identity.fetchedAtMs
        return ageMs in 0 until ttlMs
    }

    @JvmStatic
    fun encode(identity: YtCfgService.EmbedIdentity): String = JsonObject().apply {
        addProperty("v", VERSION)
        addProperty("flags", identity.encryptedHostFlags)
        addProperty("visitor", identity.visitorData)
        addProperty("fetchedAtMs", identity.fetchedAtMs)
        if (identity.rerolledAtMs > 0)
            addProperty("rerolledAtMs", identity.rerolledAtMs)
    }.toString()

    /** Null for anything but a complete snapshot of this version. */
    @JvmStatic
    fun decode(snapshot: String): YtCfgService.EmbedIdentity? = try {
        val json = JsonParser.parseString(snapshot).asJsonObject
        val flags = json.get("flags")?.asString
        val visitor = json.get("visitor")?.asString
        val fetchedAtMs = json.get("fetchedAtMs")?.asLong
        // NEWTUBE(embed-reroll): optional; a snapshot without it (older builds) carries no budget.
        val rerolledAtMs = json.get("rerolledAtMs")?.asLong ?: 0L
        if (json.get("v")?.asInt != VERSION || flags.isNullOrEmpty() || visitor.isNullOrEmpty()
            || fetchedAtMs == null) null
        else YtCfgService.EmbedIdentity(flags, visitor, fetchedAtMs, rerolledAtMs)
    } catch (e: RuntimeException) {
        null // IllegalStateException / JsonParseException / NumberFormatException: nothing restored
    }

    private fun saveQuietly(store: Store, snapshot: String?) {
        try {
            store.save(snapshot)
        } catch (e: RuntimeException) {
            // Persistence is an optimisation: the in-memory identity is still correct.
        }
    }
}
