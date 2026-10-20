package com.liskovsoft.youtubeapi.browse.v2

import com.google.gson.Gson
import com.liskovsoft.youtubeapi.browse.v2.gen.BrowseResultTV
import com.liskovsoft.youtubeapi.browse.v2.gen.MusicChannelHeaderResult
import com.liskovsoft.youtubeapi.browse.v2.gen.getAvatarUrl
import com.liskovsoft.youtubeapi.browse.v2.gen.getTabs
import com.liskovsoft.youtubeapi.panel.gen.PanelResult
import com.liskovsoft.youtubeapi.panel.getChannelId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * NEWTUBE(feed-avatars): offline parsing of the two avatar sources of the Home/Subscriptions cards:
 * the channel column of the TV Subscriptions page, and the header of a channel's YouTube Music page.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelAvatarParsingTest {
    private val gson = Gson()

    @Test
    fun subscriptionsPageListsEveryChannelWithItsAvatar() {
        for (fixture in listOf("browse/tv/2026.02.21_subscriptions.json", "browse/tv/2025.10.31_subscriptions.json")) {
            val result = gson.fromJson(readResource(fixture), BrowseResultTV::class.java)
            val tabs = result.getTabs()!!

            val generation = SubscribedChannelAvatars.generation()
            SubscribedChannelAvatars.put(tabs, generation)
            val avatars = SubscribedChannelAvatars.get()!!

            // Every tab but "All subscriptions" (and any non-channel tab) is a channel.
            assertTrue("$fixture: ${avatars.size} of ${tabs.size} tabs", avatars.size >= tabs.size - 2)
            avatars.forEach { (channelId, url) ->
                assertTrue(channelId, Regex("UC[\\w-]{22}").matches(channelId))
                assertTrue(url, url.startsWith("https://") && (url.contains("ggpht.com") || url.contains("googleusercontent.com")))
            }
        }
    }

    @Test
    fun staleResponseIsNotKeptAfterAnAccountChange() {
        val result = gson.fromJson(readResource("browse/tv/2026.02.21_subscriptions.json"), BrowseResultTV::class.java)
        val generation = SubscribedChannelAvatars.generation()

        SubscribedChannelAvatars.onAccountChanged(null)
        SubscribedChannelAvatars.put(result.getTabs(), generation)

        assertNull(SubscribedChannelAvatars.get())
    }

    @Test
    fun regularChannelGetsItsYouTubeAvatar() {
        val header = gson.fromJson(readResource("browse/music/2026.10.20_channel_header_visual.json"), MusicChannelHeaderResult::class.java)

        // The same image YouTube's own channel page shows for Marques Brownlee.
        val url = header.getAvatarUrl()
        assertNotNull(url)
        assertTrue(url!!, url.startsWith("https://yt3.googleusercontent.com/qu4TmIaYUlS41-dJ9gZ7DUR3nilvmB5_11i6"))
    }

    @Test
    fun artistChannelGetsTheArtistPhoto() {
        val header = gson.fromJson(readResource("browse/music/2026.10.20_channel_header_immersive.json"), MusicChannelHeaderResult::class.java)

        assertNotNull(header.getAvatarUrl())
    }

    @Test
    fun homeTileMenuNamesTheChannel() {
        // A signed-in Home tile's long-press menu (get_panel), whose "Go to channel" item is the
        // only place the tile's channel is.
        val panel = gson.fromJson(readResource("browse/tv/2026.09.20_engagementPanel.json"), PanelResult::class.java)

        assertEquals("UC5A0PgkH6vndkzWBBUnRuxw", panel.content!!.getChannelId())
    }

    @Test
    fun pageWithoutHeaderHasNoAvatar() {
        assertEquals(null, gson.fromJson("{}", MusicChannelHeaderResult::class.java).getAvatarUrl())
    }

    private fun readResource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
