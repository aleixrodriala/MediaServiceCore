package com.liskovsoft.youtubeapi.search.v2

import org.junit.Assert.assertEquals
import org.junit.Test

/** NEWTUBE(search-history): a typed query falls back only to the past searches that match it. */
class SearchServiceGatesTest {
    private val history = listOf("veritasium", "Linus Tech Tips", "cat videos", "tech news", "Minecraft")

    @Test
    fun prefixOfTheEntryMatches() {
        assertEquals(listOf("veritasium"), SearchServiceGates.matchHistory(history, "ver"))
    }

    @Test
    fun prefixOfALaterWordMatchesCaseInsensitively() {
        assertEquals(listOf("Linus Tech Tips", "tech news"), SearchServiceGates.matchHistory(history, "TECH"))
    }

    @Test
    fun unrelatedQueryMatchesNothing() {
        assertEquals(emptyList<String>(), SearchServiceGates.matchHistory(history, "xqzv"))
    }

    @Test
    fun midWordFragmentDoesNotMatch() {
        assertEquals(emptyList<String>(), SearchServiceGates.matchHistory(history, "ideos"))
    }

    @Test
    fun blankQueryKeepsTheWholeHistory() {
        assertEquals(history, SearchServiceGates.matchHistory(history, "  "))
    }

    @Test
    fun historyMatchesLeadServerSuggestionsWithoutDuplicates() {
        assertEquals(listOf("Linus Tech Tips", "tech news", "technology", "tech tips"),
                SearchServiceGates.mergeWithHistory(listOf("Tech News", "technology", "tech tips"), history, "tech"))
    }

    @Test
    fun offlineOrEmptyServerFallsBackToAllMatchingHistory() {
        assertEquals(listOf("Linus Tech Tips", "tech news"), SearchServiceGates.mergeWithHistory(null, history, "tech"))
        assertEquals(listOf("veritasium"), SearchServiceGates.mergeWithHistory(emptyList(), history, "ver"))
    }

    @Test
    fun onlyThreeHistoryRowsLeadTheServer() {
        val many = listOf("cat 1", "cat 2", "cat 3", "cat 4")
        assertEquals(listOf("cat 1", "cat 2", "cat 3", "cats"),
                SearchServiceGates.mergeWithHistory(listOf("cats"), many, "cat"))
    }
}
