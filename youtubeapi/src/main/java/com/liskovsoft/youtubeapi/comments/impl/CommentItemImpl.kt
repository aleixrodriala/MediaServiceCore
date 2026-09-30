package com.liskovsoft.youtubeapi.comments.impl

import com.liskovsoft.mediaserviceinterfaces.data.CommentItem
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.youtubeapi.comments.gen.CommentItemWrapper
import com.liskovsoft.youtubeapi.comments.gen.getContinuationKey
import com.liskovsoft.youtubeapi.comments.gen.getContinuationLabel
import com.liskovsoft.youtubeapi.comments.gen.getPinnedLabel
import com.liskovsoft.youtubeapi.common.models.gen.TextItem
import com.liskovsoft.youtubeapi.common.models.gen.getOptimalResThumbnailUrl
import com.liskovsoft.youtubeapi.common.models.gen.getText

internal data class CommentItemImpl(val commentItemWrapper: CommentItemWrapper): CommentItem {
    private val commentRenderer by lazy {
        commentItemWrapper.commentThreadRenderer?.comment?.commentRenderer
    }

    private val idItem by lazy { commentRenderer?.commentId }

    private val messageIem by lazy { commentRenderer?.contentText?.getText() }

    private val authorNameItem by lazy { commentRenderer?.authorText?.getText() }

    private val authorPhotoItem by lazy { commentRenderer?.authorThumbnail?.getOptimalResThumbnailUrl() }

    private val publishedDateItem by lazy { commentRenderer?.publishedTimeText?.getText() }

    private val nestedCommentKeyItem by lazy { commentRenderer?.getContinuationKey() }

    private val replyCountItem by lazy { commentRenderer?.getContinuationLabel()?.replace(" ", Helpers.NON_BREAKING_SPACE) }

    private val isLikedItem by lazy { commentRenderer?.isLiked ?: false }

    private val likeCountItem by lazy { commentRenderer?.voteCount?.getText() }

    private val pinnedLabelItem by lazy { commentRenderer?.getPinnedLabel() }

    private val messageSpansItem by lazy { commentRenderer?.contentText?.let { toSpans(it) } }

    override fun getId(): String? = idItem

    override fun getMessage(): String? = messageIem

    override fun getAuthorName(): String? = authorNameItem

    override fun getAuthorPhoto(): String? = authorPhotoItem

    override fun getPublishedDate(): String? = publishedDateItem

    override fun getNestedCommentsKey(): String? = nestedCommentKeyItem

    override fun isLiked(): Boolean = isLikedItem

    override fun getLikeCount(): String? = likeCountItem

    override fun getReplyCount(): String? = replyCountItem

    override fun isEmpty(): Boolean = replyCountItem == null || nestedCommentKeyItem == null // empty replies fix

    override fun getPinnedLabel(): String? = pinnedLabelItem

    override fun getMessageSpans(): List<CommentItem.Span>? = messageSpansItem

    /**
     * NEWTUBE(comments-panel): the bold and linked runs of the text, with offsets into the joined
     * text exactly as [TextItem.getText] joins it (a run's text, else its emoji's, else nothing).
     */
    private fun toSpans(text: TextItem): List<CommentItem.Span>? {
        val runs = text.runs ?: return null
        var offset = 0
        var spans: MutableList<CommentItem.Span>? = null
        for (run in runs) {
            val part = run?.text ?: run?.emoji?.getText() ?: ""
            val start = offset
            offset += part.length
            if (run == null || part.isEmpty()) {
                continue
            }
            val endpoint = run.navigationEndpoint
            val watch = endpoint?.watchEndpoint
            val url = endpoint?.urlEndpoint?.url
            val bold = run.bold == true
            if (!bold && watch?.videoId == null && url == null) {
                continue
            }
            if (spans == null) {
                spans = mutableListOf()
            }
            spans.add(CommentItem.Span(start, offset, bold, url, watch?.videoId, watch?.startTimeSeconds ?: -1))
        }
        return spans
    }
}