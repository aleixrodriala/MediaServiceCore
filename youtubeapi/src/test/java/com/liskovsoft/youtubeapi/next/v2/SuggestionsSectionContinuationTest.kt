package com.liskovsoft.youtubeapi.next.v2

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.liskovsoft.googlecommon.common.helpers.YouTubeHelper
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.SuggestionsGroup
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResult
import com.liskovsoft.youtubeapi.next.v2.gen.WatchNextResultContinuation
import com.liskovsoft.youtubeapi.next.v2.impl.MediaItemMetadataImpl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NEWTUBE(related-more): the TV /next pivot (anonymous TVHTML5, 2026-09-30) is 10 shelves of 3 videos
 * whose section list has a continuation of its own - the next 10 shelves - while no shelf has one.
 * The phone pages the LAST related row at the end of its list, so that row must carry the key.
 */
@RunWith(RobolectricTestRunner::class)
class SuggestionsSectionContinuationTest {
    private val pivotFixture = "next/v2/next_tv_pivot_2026.09.30.json"
    private val continuationFixture = "next/v2/next_tv_pivot_continuation_2026.09.30.json"

    @After
    fun tearDown() {
        WatchNextGates.suggestionsSectionContinuation = false
    }

    @Test
    fun lastRowCarriesThePivotContinuation() {
        WatchNextGates.suggestionsSectionContinuation = true

        val rows = MediaItemMetadataImpl(load(pivotFixture, WatchNextResult::class.java)).suggestions

        assertNotNull(rows)
        assertEquals(10, rows!!.size)
        assertEquals(30, rows.sumOf { it?.mediaItems?.size ?: 0 })
        rows.dropLast(1).forEach { assertNull("only the last row pages the section list", it?.nextPageKey) }
        assertEquals(pivotToken(), rows.last()?.nextPageKey)
        assertEquals("the key the controller's continuation reads", pivotToken(), YouTubeHelper.extractNextKey(rows.last()))
    }

    @Test
    fun withoutTheGateNoRowCanBeContinued() {
        val rows = MediaItemMetadataImpl(load(pivotFixture, WatchNextResult::class.java)).suggestions

        assertEquals(10, rows!!.size)
        rows.forEach { assertNull("upstream behaviour: the shelves have no key of their own", it?.nextPageKey) }
    }

    @Test
    fun theContinuationIsTheNextThirtyRelatedVideos() {
        WatchNextGates.suggestionsSectionContinuation = true

        val rows = MediaItemMetadataImpl(load(pivotFixture, WatchNextResult::class.java)).suggestions!!
        val firstPageIds = rows.flatMap { row -> row?.mediaItems?.map { it?.videoId } ?: emptyList() }.toSet()

        val next = SuggestionsGroup.from(load(continuationFixture, WatchNextResultContinuation::class.java), rows.last())

        assertNotNull(next)
        val items = next!!.mediaItems!!
        assertEquals(30, items.size)
        assertTrue("new videos, not the first page again", items.none { it?.videoId in firstPageIds })
        assertEquals(rows.last()?.title, next.title)
        assertNull("the TV pivot has one continuation page: the list ends there", next.nextPageKey)
    }

    private fun pivotToken(): String {
        val json = JsonParser.parseString(read(pivotFixture)).asJsonObject
        return json.getAsJsonObject("contents").getAsJsonObject("singleColumnWatchNextResults")
            .getAsJsonObject("pivot").getAsJsonObject("sectionListRenderer")
            .getAsJsonArray("continuations")[0].asJsonObject
            .getAsJsonObject("nextContinuationData").get("continuation").asString
    }

    private fun <T> load(path: String, clazz: Class<T>): T = Gson().fromJson(read(path), clazz)

    private fun read(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }
}
