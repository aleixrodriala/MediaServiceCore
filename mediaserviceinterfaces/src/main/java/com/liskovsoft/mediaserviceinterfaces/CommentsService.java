package com.liskovsoft.mediaserviceinterfaces;

import com.liskovsoft.mediaserviceinterfaces.data.CommentGroup;
import com.liskovsoft.mediaserviceinterfaces.data.CommentItem;
import io.reactivex.rxjava3.core.Observable;

public interface CommentsService {
    Observable<CommentGroup> getCommentsObserve(String key);
    Observable<Void> toggleLikeObserve(String key);
    Observable<Void> toggleDislikeObserve(String key);

    /** NEWTUBE(write-comments): posts a top-level comment on the video; emits the new comment. */
    Observable<CommentItem> createCommentObserve(String videoId, String commentText);

    /** NEWTUBE(write-comments): replies to a top-level comment; emits the new reply. */
    Observable<CommentItem> createReplyObserve(String videoId, String parentCommentId, String commentText);

    /** NEWTUBE(write-comments): deletes one of the signed-in account's comments or replies. */
    Observable<Void> deleteCommentObserve(String videoId, String commentId);
}
