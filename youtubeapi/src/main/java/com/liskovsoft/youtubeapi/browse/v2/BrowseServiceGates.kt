package com.liskovsoft.youtubeapi.browse.v2

/**
 * Phone-only static gates for the browse services, set from the app's Application class
 * (mirrors VideoInfoService.setPreferNoPotClient — the TV flavors never call these, so
 * upstream behavior is unchanged by default). Lives outside the internal BrowseService2
 * class so the app module can reach it.
 */
object BrowseServiceGates {
    /**
     * Emit grids after ONE /browse instead of blocking first paint behind the TV pre-combine
     * loop (continueIfNeededTV: up to 10 serial continuations gathering 60+ items, purely so
     * live streams can be sorted above page-1 items). Page 1 keeps its own live-first stable
     * sort — the combined window just shrinks to one page — and deeper pages arrive through
     * the normal scroll-driven pagination instead of in front of the first paint.
     */
    @JvmStatic
    @Volatile
    var skipContinuationPreCombine: Boolean = false

    /**
     * NEWTUBE(lazy-home): lets the app pace a section-list walk instead of draining every
     * continuation page back to back. YouTubeContentService consults it before each section-list
     * continuation request; null (the default, and always on TV) keeps the eager walk.
     */
    @JvmStatic
    @Volatile
    var sectionListPacer: SectionListPacer? = null

    fun interface SectionListPacer {
        /**
         * Blocks the walking thread until page [nextPage] (2 = the first continuation) of a
         * [groupType] section list may be fetched. Returning false ends the walk: the
         * observable completes with the pages emitted so far. Must return promptly once
         * [disposal] reports the subscriber gone.
         */
        fun awaitNextPage(groupType: Int, nextPage: Int, disposal: Disposal): Boolean
    }

    fun interface Disposal {
        fun isDisposed(): Boolean
    }
}
