package com.liskovsoft.youtubeapi.panel

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper
import com.liskovsoft.mediaserviceinterfaces.data.FeedbackEndpoint
import com.liskovsoft.youtubeapi.browse.v2.gen.getFeedbackToken
import com.liskovsoft.youtubeapi.next.v2.gen.getMenuItems

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