package com.liskovsoft.youtubeapi.service

import com.liskovsoft.mediaserviceinterfaces.CommentsService
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper
import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem
import com.liskovsoft.sharedutils.rx.RxHelper
import com.liskovsoft.youtubeapi.comments.CommentsServiceInt
import io.reactivex.rxjava3.core.Observable

internal object YouTubeCommentsService: CommentsService {
    private fun getComments(key: String?): CommentGroup? {
        return key?.let { CommentsServiceInt.getComments(key) }
    }

    private fun toggleLike(key: String?) {
        key?.let { CommentsServiceInt.toggleLike(key) }
    }

    private fun toggleDislike(key: String?) {
        key?.let { CommentsServiceInt.toggleDislike(key) }
    }

    override fun getCommentsObserve(key: String?): Observable<CommentGroup> {
        return RxHelper.fromCallable { getComments(key) }
    }

    override fun toggleLikeObserve(key: String?): Observable<Void> {
        return RxHelper.fromRunnable { toggleLike(key) }
    }

    override fun toggleDislikeObserve(key: String?): Observable<Void> {
        return RxHelper.fromRunnable { toggleDislike(key) }
    }

    override fun createCommentObserve(videoId: String?, commentText: String?): Observable<CommentItem> {
        return RxHelper.fromCallable {
            checkSignedIn()
            CommentsServiceInt.createComment(requireNotNull(videoId), requireNotNull(commentText))
        }
    }

    override fun createReplyObserve(videoId: String?, parentCommentId: String?, commentText: String?): Observable<CommentItem> {
        return RxHelper.fromCallable {
            checkSignedIn()
            CommentsServiceInt.createReply(requireNotNull(videoId), requireNotNull(parentCommentId), requireNotNull(commentText))
        }
    }

    override fun deleteCommentObserve(videoId: String?, commentId: String?): Observable<Void> {
        return RxHelper.fromRunnable {
            checkSignedIn()
            CommentsServiceInt.deleteComment(requireNotNull(videoId), requireNotNull(commentId))
        }
    }

    /**
     * NEWTUBE(write-comments): a write never leaves without the account's header. Right after a
     * cold start the header may not be restored yet, and YouTube answers an anonymous write with
     * 403 "Comment failed to post"; restore it here first (as the other services' checkSigned do)
     * and refuse outright when there is still none.
     */
    private fun checkSignedIn() {
        YouTubeSignInService.instance().checkAuth()
        if (RetrofitOkHttpHelper.authHeaders["Authorization"].isNullOrEmpty()) {
            throw IllegalStateException("Not signed in")
        }
    }
}