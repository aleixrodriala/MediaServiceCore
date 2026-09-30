package com.liskovsoft.youtubeapi.notifications

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.NotificationState
import com.liskovsoft.youtubeapi.common.models.gen.NotificationStateItem
import com.liskovsoft.youtubeapi.common.models.impl.NotificationStateImpl
import com.liskovsoft.youtubeapi.rss.RssService

private const val ALL = 0 // Enable notifications
private const val PERSONALIZED = 1 // Disable notifications
private const val NONE = 2 // Disable notifications

internal object NotificationsServiceIntWrapper: NotificationsServiceInt() {
    override fun getItems(): MediaGroup? {
        return try {
            super.getItems().also {
                logNetPath("notifications source=inbox items=${it?.mediaItems?.size ?: -1}")
            }
        } catch (e: IllegalStateException) {
            // NEWTUBE(notifications): the inbox request carries the TVHTML5 context, which the endpoint
            // refuses (anonymous probe 2026-09-30: HTTP 400 FAILED_PRECONDITION for TVHTML5, 200 for
            // WEB). The fallback is an RSS feed of the channels whose bell was set to "All" in this app
            // (or liked 6+ times) - none for most phone users, hence an empty section. One line, so
            // an empty Notifications screen tells which of the two it was.
            val channels = NotificationStorage.getChannels()
            val feed = channels?.let { RssService.getFeed(*it.toTypedArray(), type = MediaGroup.TYPE_NOTIFICATIONS) }
            logNetPath("notifications source=rss inbox-error=${e.message?.take(80)} channels=${channels?.size ?: 0} " +
                    "items=${feed?.mediaItems?.size ?: 0}")
            feed
        }
    }

    private fun logNetPath(message: String) {
        android.util.Log.d("NetPath", message)
    }

    override fun modifyNotification(notificationState: NotificationState?) {
        if (notificationState is NotificationStateImpl) {
            if (notificationState.index == ALL)
                NotificationStorage.addChannel(notificationState.channelId)
            else
                NotificationStorage.removeChannel(notificationState.channelId)
        }

        try {
            super.modifyNotification(notificationState)
        } catch (e: IllegalStateException) {
            // Notification cannot be modified
        }
    }
}

internal class NotificationStateImplWrapper(
    notificationStateItem: NotificationStateItem,
    selectedSateId: Int?,
    channelId: String?,
    params: String?,
    isSubscribed: Boolean
): NotificationStateImpl(notificationStateItem, selectedSateId, channelId, params, isSubscribed) {
    override fun isSelected(): Boolean {
        return if (NotificationStorage.contains(channelId))
             if (index == ALL) true else false
        else if (super.isSelected() && index == ALL) {
            // Set to none if selected globally (global notifications doesn't work)
            allStates.getOrNull(NONE)?.setSelected()
            false
        }
        else super.isSelected()
    }
}