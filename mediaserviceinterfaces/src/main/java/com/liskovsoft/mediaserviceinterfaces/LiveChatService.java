package com.liskovsoft.mediaserviceinterfaces;

import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import io.reactivex.rxjava3.core.Observable;

public interface LiveChatService {
    Observable<ChatItem> openLiveChatObserve(String chatKey);
}
