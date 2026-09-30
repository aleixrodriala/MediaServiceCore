package com.liskovsoft.youtubeapi.common.models.gen

import com.google.gson.Gson
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.WrapperMediaItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEWTUBE(shorts): TVHTML5 search results and Shorts shelves send Shorts as plain lockupViewModel
 * items (contentType LOCKUP_CONTENT_TYPE_SHORT) with no Shorts badge or style; only the tap command,
 * a reelWatchEndpoint, says what they are. Shapes trimmed from a real /search reply (2026-09-30).
 */
class LockupShortsTest {
    private fun lockup(videoId: String, contentType: String, command: String) = Gson().fromJson("""
        {"lockupViewModel": {
            "contentId": "$videoId",
            "contentType": "$contentType",
            "metadata": {"lockupMetadataViewModel": {"title": {"content": "title"}}},
            "rendererContext": {"commandContext": {"onTap": {"innertubeCommand": $command}}}
        }}
    """.trimIndent(), ItemWrapper::class.java)

    @Test
    fun lockupOpeningTheReelPlayerIsAShort() {
        val item = lockup("d99vrWc2m7E", "LOCKUP_CONTENT_TYPE_SHORT",
            """{"reelWatchEndpoint": {"videoId": "d99vrWc2m7E"}}""")

        assertTrue(item.isShorts())
        assertTrue(WrapperMediaItem(item).isShorts())
    }

    @Test
    fun lockupOpeningTheWatchPageIsNot() {
        val item = lockup("Aar9urB9BIw", "LOCKUP_CONTENT_TYPE_VIDEO",
            """{"watchEndpoint": {"videoId": "Aar9urB9BIw"}}""")

        assertFalse(item.isShorts())
        assertFalse(WrapperMediaItem(item).isShorts())
    }
}
