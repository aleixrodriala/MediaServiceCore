package com.liskovsoft.youtubeapi.panel

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.liskovsoft.youtubeapi.browse.v2.gen.getFeedbackToken
import com.liskovsoft.youtubeapi.common.models.gen.ItemWrapper
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.WrapperMediaItem
import com.liskovsoft.youtubeapi.next.v2.gen.getMenuItems
import com.liskovsoft.youtubeapi.panel.gen.PanelResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val PANEL_ID = "PAcontext_menu"
private const val PANEL_PARAMS = "-golCgtOTzZaQjlpRjhrYyCeAUITCAISD0ZFd2hhdF90b193YXRjaA%3D%3D"

/**
 * NEWTUBE(not-interested): offline parsing of the TV "Not interested" / "Don't recommend channel"
 * data (upstream 8b4a884b). A Home tile no longer carries feedback tokens, only a long-press
 * engagement-panel endpoint; the get_panel answer (fixture 2026.09.20_engagementPanel.json) holds
 * the two tokens. The live PanelApiTest needs a signed-in account; this one needs no network.
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackTokenParsingTest {
    private val gson = Gson()

    @Test
    fun panelAnswerYieldsNotInterestedThenDontRecommendToken() {
        val json = readResource("browse/tv/2026.09.20_engagementPanel.json")

        val tokens = gson.fromJson(json, PanelResult::class.java)
            .content?.getMenuItems()?.mapNotNull { it?.getFeedbackToken() }

        // The expected values come straight from the raw JSON, by the labels YouTube shows.
        val items = JsonParser.parseString(json).asJsonObject
            .obj("content").obj("engagementPanelSectionListRenderer").obj("content").obj("listViewModel")
            .getAsJsonArray("listItems").map { it.asJsonObject.obj("listItemViewModel") }
        val notInterested = items.single { it.obj("title")["content"].asString == "Not interested" }
            .onTap().obj("feedbackEndpoint")["feedbackToken"].asString
        val dontRecommend = items.single { it.obj("title")["content"].asString == "Don't recommend channel" }
            .onTap().obj("openPopupAction").obj("popup").obj("overlaySectionRenderer").obj("overlay")
            .obj("overlayTwoPanelRenderer").obj("actionPanel").obj("overlayPanelRenderer").obj("content")
            .obj("overlayPanelItemListRenderer").getAsJsonArray("items")[0].asJsonObject
            .obj("compactLinkRenderer").obj("serviceEndpoint").obj("commandExecutorCommand")
            .getAsJsonArray("commands")[0].asJsonObject.obj("feedbackEndpoint")["feedbackToken"].asString

        // Order matters: the menu takes [0] for "Not interested" and [1] for "Don't recommend channel".
        assertEquals(listOf(notInterested, dontRecommend), tokens)
    }

    @Test
    fun homeVideoTileCarriesTheEngagementPanelEndpoint() {
        val item = WrapperMediaItem(tile(onSelect = """{"watchEndpoint":{"videoId":"dQw4w9WgXcQ"}}""", withPanel = true))

        val endpoint = item.feedbackEndpoint
        assertNotNull("video tile has a feedback endpoint", endpoint)
        assertEquals(PANEL_ID, endpoint!!.panelId)
        assertEquals(PANEL_PARAMS, endpoint.params)
        assertNull("no legacy token on the tile", item.feedbackToken)
        assertNull("no legacy token 2 on the tile", item.feedbackToken2)
    }

    @Test
    fun channelTileIgnoresTheEndpoint() {
        val item = WrapperMediaItem(tile(onSelect = """{"browseEndpoint":{"browseId":"UCuAXFkgsw1L7xaCfnd5JJOw"}}""", withPanel = true))

        assertNull("a channel's panel only holds its id", item.feedbackEndpoint)
    }

    @Test
    fun tileWithoutLongPressPanelHasNoEndpoint() {
        val item = WrapperMediaItem(tile(onSelect = """{"watchEndpoint":{"videoId":"dQw4w9WgXcQ"}}""", withPanel = false))

        assertNull(item.feedbackEndpoint)
    }

    private fun tile(onSelect: String, withPanel: Boolean): ItemWrapper {
        val longPress = if (withPanel)
            ""","onLongPressCommand":{"showEngagementPanelEndpoint":{"identifier":{"tag":"$PANEL_ID"},"globalConfiguration":{"params":"$PANEL_PARAMS"}}}"""
        else ""
        return gson.fromJson("""{"tileRenderer":{"onSelectCommand":$onSelect$longPress}}""", ItemWrapper::class.java)
    }

    private fun JsonObject.obj(name: String): JsonObject = getAsJsonObject(name)

    private fun JsonObject.onTap(): JsonObject =
        obj("rendererContext").obj("commandContext").obj("onTap").obj("innertubeCommand")

    private fun readResource(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
}
