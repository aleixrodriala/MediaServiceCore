package com.liskovsoft.youtubeapi.browse.v2

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * NEWTUBE(home-parallel): signed-out Home - topic feeds in parallel with the old merge semantics,
 * and the process-wide "personalized home is empty" verdict.
 */
@RunWith(RobolectricTestRunner::class)
class AnonymousHomeTest {
    private var nowMs = 1_000_000L
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        AnonymousHome.clearVerdict()
        AnonymousHome.clock = { nowMs }
    }

    @After
    fun tearDown() {
        AnonymousHome.clearVerdict()
        AnonymousHome.clock = { System.currentTimeMillis() }
    }

    private fun group(title: String, items: Int = 1): MediaGroup = YouTubeMediaGroup(MediaGroup.TYPE_HOME).apply {
        this.title = title
        mediaItems = if (items > 0) List(items) { YouTubeMediaItem() } else null
    }

    private fun rows(vararg titles: String): HomeRows = Pair(titles.map { group(it) }, null)

    private val emptyProbe: HomeRows = Pair(listOf(group("start searching", items = 0)), "ctoken")

    private fun topicFeeds(): List<() -> HomeRows?> = listOf("music", "gaming", "news", "sports").map { name ->
        { events.add("topic:$name"); rows("$name-1", "$name-2") }
    }

    private fun titles(result: HomeRows?): List<String?>? = result?.first?.map { it?.title }

    @Test
    fun fetchAllRunsConcurrentlyAndKeepsInputOrder() {
        val allStarted = CountDownLatch(4)

        val results = AnonymousHome.fetchAll((0 until 4).map { index ->
            {
                allStarted.countDown()
                // Serial execution would never reach zero here.
                assertTrue("tasks run in parallel", allStarted.await(5, TimeUnit.SECONDS))
                Thread.sleep((3 - index) * 40L) // finish in reverse order
                index
            }
        })

        assertEquals(listOf(0, 1, 2, 3), results.map { it.getOrThrow() })
    }

    @Test
    fun mergeKeepsFeedOrderAndSkipsEmptyFeeds() {
        val merged = AnonymousHome.mergeTopicFeeds(listOf(
            Result.success(rows("a1", "a2")), Result.success(null), Result.success(rows("c1"))))

        assertEquals(listOf("a1", "a2", "c1"), titles(merged))
        assertNull(merged.second)
        assertNull("no rows -> null, as before", AnonymousHome.mergeTopicFeeds(listOf(Result.success(null))).first)
    }

    @Test
    fun mergeRethrowsTheFirstFailureInFeedOrder() {
        val first = IllegalStateException("gaming")
        val second = IllegalStateException("news")

        try {
            AnonymousHome.mergeTopicFeeds(listOf(Result.success(rows("m")), Result.failure(first), Result.failure(second)))
            fail("the serial loop propagated the first failing feed")
        } catch (e: IllegalStateException) {
            assertSame(first, e)
        }
    }

    @Test
    fun personalizedHomeIsOneRequest() {
        val home = rows("recommended")

        val result = AnonymousHome.resolve({ home }, topicFeeds())

        assertSame(home, result)
        assertTrue("topic feeds untouched", events.isEmpty())
        assertFalse(AnonymousHome.hasEmptyVerdict())
    }

    @Test
    fun emptyProbeFallsBackToTopicsAndRemembersTheVerdict() {
        val result = AnonymousHome.resolve({ events.add("probe"); emptyProbe }, topicFeeds())

        assertEquals(listOf("music-1", "music-2", "gaming-1", "gaming-2", "news-1", "news-2", "sports-1", "sports-2"),
            titles(result))
        assertNull(result?.second)
        assertEquals("probe first while nothing is known", "probe", events[0])
        assertTrue(AnonymousHome.hasEmptyVerdict())
    }

    @Test
    fun knownEmptyVisitorFiresProbeAlongsideTopics() {
        AnonymousHome.resolve({ emptyProbe }, topicFeeds())
        events.clear()
        nowMs += 5 * 60 * 1_000L

        val topicsStarted = CountDownLatch(4)
        val feeds = topicFeeds().map { feed -> { topicsStarted.countDown(); feed() } }
        val result = AnonymousHome.resolve({
            // Serial probe-first would deadlock here: no topic feed can start before it returns.
            assertTrue("probe runs together with the topic feeds", topicsStarted.await(5, TimeUnit.SECONDS))
            emptyProbe
        }, feeds)

        assertEquals(8, result?.first?.size)
        assertTrue(AnonymousHome.hasEmptyVerdict())
    }

    @Test
    fun personalizedProbeWinsAndClearsTheVerdict() {
        AnonymousHome.resolve({ emptyProbe }, topicFeeds())
        val home = rows("recommended")

        val result = AnonymousHome.resolve({ home }, topicFeeds())

        assertSame(home, result)
        assertFalse(AnonymousHome.hasEmptyVerdict())
    }

    @Test
    fun failedProbeUnderAVerdictStillServesTopics() {
        AnonymousHome.resolve({ emptyProbe }, topicFeeds())

        val result = AnonymousHome.resolve({ throw IllegalStateException("offline probe") }, topicFeeds())

        assertEquals(8, result?.first?.size)
        assertTrue("verdict kept", AnonymousHome.hasEmptyVerdict())
    }

    @Test
    fun verdictExpiresAfterThirtyMinutes() {
        AnonymousHome.resolve({ emptyProbe }, topicFeeds())
        nowMs += AnonymousHome.VERDICT_TTL_MS
        events.clear()

        AnonymousHome.resolve({ events.add("probe"); emptyProbe }, topicFeeds())

        assertEquals("expired verdict -> probe first again", "probe", events[0])
        assertTrue("re-learned", AnonymousHome.hasEmptyVerdict())
    }

    @Test
    fun nullProbeIsNotAnEmptyVerdict() {
        val result = AnonymousHome.resolve({ null }, topicFeeds())

        assertEquals(8, result?.first?.size)
        assertFalse(AnonymousHome.hasEmptyVerdict())
    }

    @Test
    fun accountChangeClearsTheVerdict() {
        AnonymousHome.resolve({ emptyProbe }, topicFeeds())

        AnonymousHome.onAccountChanged(null)

        assertFalse(AnonymousHome.hasEmptyVerdict())
    }
}
