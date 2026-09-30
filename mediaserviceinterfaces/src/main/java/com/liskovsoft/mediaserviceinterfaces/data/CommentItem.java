package com.liskovsoft.mediaserviceinterfaces.data;

import androidx.annotation.Nullable;

import java.util.List;

public interface CommentItem {
    @Nullable
    String getId();
    @Nullable
    String getMessage();
    @Nullable
    String getAuthorName();
    @Nullable
    String getAuthorPhoto();
    @Nullable
    String getPublishedDate();
    @Nullable
    String getNestedCommentsKey();
    boolean isLiked();
    @Nullable
    String getLikeCount();
    @Nullable
    String getReplyCount();
    boolean isEmpty();

    /** NEWTUBE(comments-panel): "Pinned by @handle" on the pinned comment, null on every other one. */
    @Nullable
    default String getPinnedLabel() {
        return null;
    }

    /**
     * NEWTUBE(comments-panel): the styled and linked stretches of {@link #getMessage()}, in order,
     * or null when the text is plain. Offsets index into {@link #getMessage()}.
     */
    @Nullable
    default List<Span> getMessageSpans() {
        return null;
    }

    /**
     * A stretch of a comment's text that YouTube marked up: bold, a link to a web address, or a
     * link to a video - a timestamp when {@link #startTimeSeconds} is set, which for the video the
     * comment belongs to means "seek there".
     */
    final class Span {
        public final int start;
        public final int end;
        public final boolean bold;
        @Nullable
        public final String url;
        @Nullable
        public final String videoId;
        /** Seconds into {@link #videoId}, or -1 when the link has no start time. */
        public final int startTimeSeconds;

        public Span(int start, int end, boolean bold, @Nullable String url, @Nullable String videoId,
                    int startTimeSeconds) {
            this.start = start;
            this.end = end;
            this.bold = bold;
            this.url = url;
            this.videoId = videoId;
            this.startTimeSeconds = startTimeSeconds;
        }

        public boolean isLink() {
            return url != null || videoId != null;
        }
    }
}
