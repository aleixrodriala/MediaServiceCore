package com.liskovsoft.youtubeapi.search.v2

import com.liskovsoft.youtubeapi.search.SearchTagStorage

/**
 * Phone-only static gates for search suggestions, set from the app's Application class (same
 * pattern as BrowseServiceGates - the TV flavors never set them, so upstream behavior is unchanged
 * by default). Lives outside the internal SearchService2Wrapper so the app module can reach it.
 */
object SearchServiceGates {
    /**
     * NEWTUBE(search-history): when the suggest endpoint has nothing for a TYPED query, upstream
     * falls back to the user's WHOLE search history - on a phone that list is drawn under the
     * query as if it were suggestions for it ("xqzv" listed every past search). With this on, the
     * whole history is only the answer to an EMPTY field; a typed query gets the past searches that
     * match it on top of the server's suggestions (all of them, when the server has nothing or
     * cannot be reached - offline, history is the only help there is).
     */
    @JvmStatic
    @Volatile
    var historyMatchesQuery: Boolean = false

    /** Whether [tag] is one of the user's past searches (the phone draws those with a clock). */
    @JvmStatic
    fun isHistoryTag(tag: String?): Boolean = tag != null && SearchTagStorage.tags.contains(tag)

    /** How many matching past searches lead the server's suggestions (YouTube shows a few). */
    private const val HISTORY_ABOVE_SUGGESTIONS = 3

    /**
     * Matching past searches first (capped when the server also answered), then the server's
     * suggestions that aren't already listed.
     */
    @JvmStatic
    fun mergeWithHistory(server: List<String>?, history: List<String>, query: String): List<String> {
        val matches = matchHistory(history, query)
        val result = ArrayList(if (server.isNullOrEmpty()) matches else matches.take(HISTORY_ABOVE_SUGGESTIONS))
        server?.forEach { suggestion ->
            if (result.none { it.equals(suggestion, ignoreCase = true) }) {
                result.add(suggestion)
            }
        }
        return result
    }

    /**
     * The past searches that match [query]: the query starts the entry or one of its words,
     * case-insensitively. Keeps [history]'s order (most recent first).
     */
    @JvmStatic
    fun matchHistory(history: List<String>, query: String): List<String> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            return history
        }
        return history.filter {
            val entry = it.lowercase()
            entry.startsWith(needle) || entry.contains(" $needle")
        }
    }
}
