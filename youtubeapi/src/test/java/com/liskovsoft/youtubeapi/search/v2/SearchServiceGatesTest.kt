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
}
