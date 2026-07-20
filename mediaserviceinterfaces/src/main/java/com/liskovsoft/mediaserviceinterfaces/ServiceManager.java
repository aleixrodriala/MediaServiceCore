package com.liskovsoft.mediaserviceinterfaces;

public interface ServiceManager {
    SignInService getSignInService();
    RemoteControlService getRemoteControlService();
    default CastSenderService getCastSenderService() {
        return null;
    }
    ContentService getContentService();
    MediaItemService getMediaItemService();
    LiveChatService getLiveChatService();
    CommentsService getCommentsService();
    NotificationsService getNotificationsService();
    ChannelGroupService getChannelGroupService();
    void invalidateCache();
    void refreshCacheIfNeeded();
    void applyNoPlaybackFix();
    void applySubtitleFix();
}
