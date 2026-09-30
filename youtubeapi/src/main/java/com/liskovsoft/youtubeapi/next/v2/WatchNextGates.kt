package com.liskovsoft.youtubeapi.next.v2

/**
 * Phone-only static gates for the watch-next (/next) services, set from the app's Application class
 * (same pattern as BrowseServiceGates: the TV flavors never call these, so upstream behavior is
 * unchanged by default).
 */
object WatchNextGates {
    /**
     * NEWTUBE(related-more): hand the TV pivot's section-list continuation (the next page of
     * suggestion shelves) to the last suggestion row, so paging that row at the end of the related
     * list loads more videos instead of ending at the first 30. Off on TV: there a row that can be
     * continued is topped up eagerly, which would pull the extra shelves at every open.
     */
    @JvmStatic
    @Volatile
    var suggestionsSectionContinuation: Boolean = false
}
