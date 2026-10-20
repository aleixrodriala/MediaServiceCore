package com.liskovsoft.youtubeapi.panel

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper
import com.liskovsoft.mediaserviceinterfaces.data.FeedbackEndpoint
import com.liskovsoft.youtubeapi.browse.v2.gen.getFeedbackToken
import com.liskovsoft.youtubeapi.next.v2.gen.getMenuItems
import com.liskovsoft.youtubeapi.next.v2.gen.EngagementPanel

internal object PanelService {
    private val mPanelApi = RetrofitHelper.create(PanelApi::class.java)
    @Volatile
    private var mCachedToken: CachedToken? = null

    private data class CachedToken(
        val panelId: String, val params: String, val tokens: List<String>?
    )

    fun getFeedbackTokens(endpoint: FeedbackEndpoint): List<String>? {
        return getFeedbackTokens(endpoint.panelId, endpoint.params)
    }

    /**
     * NEWTUBE(feed-avatars): the channel of a Home tile, which no longer carries it: the tile's context
     * menu (the panel its long-press opens) has a "Go to channel" item. ~1 KB, sent without the
     * account's token: reading a menu is not something to tie to the account. Null = no channel in
     * the menu; a refused or failed request throws instead, so the caller does not remember it.
     */
    fun getChannelId(endpoint: FeedbackEndpoint): String? {
        val call = mPanelApi.getPanel(PanelApiHelper.getPanelQuery(endpoint.panelId, endpoint.params))
        RetrofitOkHttpHelper.addAuthSkip(call.request())
        val response = try {
            call.execute()
        } catch (e: java.io.IOException) {
            throw IllegalStateException(e)
        }
        if (!response.isSuccessful) {
            throw IllegalStateException("context menu HTTP ${response.code()}")
        }

        return response.body()?.content?.getChannelId()
    }

    private fun getFeedbackTokens(panelId: String, params: String): List<String>? {
        // NEWTUBE(not-interested): one read, so a concurrent store cannot hand back another card's tokens.
        val cached = mCachedToken
        if (cached?.panelId == panelId && cached.params == params) {
            return cached.tokens
        }

        val panelResultWrapper = mPanelApi.getPanel(
            PanelApiHelper.getPanelQuery(panelId, params))

        val panelResult = RetrofitHelper.get(panelResultWrapper)

        val tokens = panelResult?.content?.getMenuItems()?.mapNotNull { it?.getFeedbackToken() }

        // NEWTUBE(not-interested): keep only a usable answer (both tokens), so "Try again" after a
        // failed or partial panel asks YouTube again instead of replaying it from this one-slot cache.
        if (tokens?.size == 2) {
            mCachedToken = CachedToken(panelId, params, tokens)
        }

        return tokens
    }
}
/** NEWTUBE(feed-avatars): the channel a context menu's "Go to channel" item browses to. */
internal fun EngagementPanel.getChannelId(): String? = getMenuItems()?.firstNotNullOfOrNull { item ->
    val command = item?.listItemViewModel?.rendererContext?.commandContext?.onTap?.innertubeCommand
    (command?.browseEndpoint?.browseId
        ?: command?.commandExecutorCommand?.commands?.firstNotNullOfOrNull { it?.browseEndpoint?.browseId })
        ?.takeIf { it.startsWith("UC") }
}
