package com.liskovsoft.youtubeapi.browse.v2.gen

import com.liskovsoft.youtubeapi.common.models.gen.ThumbnailItem
import com.liskovsoft.youtubeapi.common.models.gen.getHighResThumbnailUrl

/**
 * NEWTUBE(feed-avatars): the header of a channel's YouTube Music page (WEB_REMIX browse of the
 * channel id), the cheapest response that carries a channel's avatar: a few KB, where the YouTube
 * channel page is ~180 KB. A regular channel gets a visual header whose foreground image is the
 * same avatar YouTube shows. An artist channel gets an immersive header whose only image is the
 * artist photo, a wide banner.
 */
internal data class MusicChannelHeaderResult(
    val header: Header?
) {
    data class Header(
        val musicVisualHeaderRenderer: Renderer?,
        val musicImmersiveHeaderRenderer: Renderer?
    )

    data class Renderer(
        val foregroundThumbnail: ThumbnailWrapper?,
        val thumbnail: ThumbnailWrapper?
    )

    data class ThumbnailWrapper(
        val musicThumbnailRenderer: MusicThumbnailRenderer?
    )

    data class MusicThumbnailRenderer(
        val thumbnail: ThumbnailItem?
    )
}

internal fun MusicChannelHeaderResult.getAvatarUrl(): String? =
    header?.musicVisualHeaderRenderer?.foregroundThumbnail?.musicThumbnailRenderer?.thumbnail?.getHighResThumbnailUrl()
        ?: header?.musicImmersiveHeaderRenderer?.thumbnail?.musicThumbnailRenderer?.thumbnail?.getHighResThumbnailUrl()
