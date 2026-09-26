package com.liskovsoft.youtubeapi.search.v2

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.search.SearchTagStorage
import com.liskovsoft.youtubeapi.search.SearchTagStorage.clear
import com.liskovsoft.youtubeapi.search.SearchTagStorage.saveTag

internal object SearchService2Wrapper: SearchService2() {
    override fun getSearch(searchText: String?): List<MediaGroup>? {
        saveTagIfNeeded(searchText)

        return super.getSearch(searchText)
    }

    override fun getSearch(searchText: String?, options: Int): List<MediaGroup>? {
        saveTagIfNeeded(searchText)

        return super.getSearch(searchText, options)
    }

    override fun getSearchTags(searchText: String?): List<String>? {
        // NEWTUBE(search-history): phone gate - see SearchServiceGates.historyMatchesQuery
        if (SearchServiceGates.historyMatchesQuery && !searchText.isNullOrBlank()) {
            return getSearchTagsWithHistory(searchText)
        }

        val result = super.getSearchTags(searchText)

        if (result == null || result.isEmpty()) {
            return getTagsIfNeeded()
        }

        return result
    }

    override fun clearSearchHistory() {
        clear()
    }

    override fun removeTag(tag: String?) {
        SearchTagStorage.removeTag(tag)
    }

    /**
     * Phone: the past searches matching [searchText] lead, then the server's suggestions. With no
     * answer at all (offline: the suggest request throws) the matching history is still offered.
     */
    private fun getSearchTagsWithHistory(searchText: String): List<String> {
        val server = try {
            super.getSearchTags(searchText)
        } catch (e: Exception) {
            null
        }
        val history = if (GlobalPreferences.sInstance != null) SearchTagStorage.tags else emptyList()

        return SearchServiceGates.mergeWithHistory(server, history, searchText)
    }

    private fun getTagsIfNeeded(): List<String>? {
        if (GlobalPreferences.sInstance != null) {
            return SearchTagStorage.tags
        }

        return null
    }

    private fun saveTagIfNeeded(searchText: String?) {
        if (GlobalPreferences.sInstance != null) {
            saveTag(searchText)
        }
    }
}