package com.liskovsoft.youtubeapi.browse.v2

/**
 * Phone-only static gates for the browse services, set from the app's Application class
 * (mirrors VideoInfoService.setSkipTvFallbackClients — the TV flavors never call these, so
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
}
