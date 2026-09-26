package com.liskovsoft.mediaserviceinterfaces.data;

import java.util.List;

public interface MediaItemMetadata {
    int LIKE_STATUS_INDIFFERENT = 0;
    int LIKE_STATUS_LIKE = 1;
    int LIKE_STATUS_DISLIKE = 2;
    String getTitle();
    CharSequence getSecondTitle();
    String getDescription();
    String getAuthor();
    String getAuthorImageUrl();
    String getViewCount();
    String getLikeCount();
    String getDislikeCount();
    String getSubscriberCount();
    String getPublishedDate();

    /**
     * NEWTUBE(watch-meta): the published date as YouTube words it relative to now ("4 days ago"),
     * or null when not known. {@link #getPublishedDate()} is the absolute date ("Sep 20, 2026").
     */
    default String getRelativePublishedDate() {
        return null;
    }

    String getVideoId();
    MediaItem getNextVideo();
    MediaItem getShuffleVideo();
    boolean isSubscribed();
    boolean isLive();
    String getLiveChatKey();
    String getCommentsKey();
    boolean isUpcoming();
    String getChannelId();
    String getParams();
    int getPercentWatched();
    int getLikeStatus();
    List<MediaGroup> getSuggestions();
    PlaylistInfo getPlaylistInfo();
    List<ChapterItem> getChapters();
    List<NotificationState> getNotificationStates();
    long getDurationMs();
    String getBadgeText();
}
