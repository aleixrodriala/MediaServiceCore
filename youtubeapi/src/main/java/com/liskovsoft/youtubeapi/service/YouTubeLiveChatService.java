package com.liskovsoft.youtubeapi.service;

import com.liskovsoft.mediaserviceinterfaces.LiveChatService;
import com.liskovsoft.mediaserviceinterfaces.data.ChatItem;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.chat.LiveChatServiceInt;
import io.reactivex.rxjava3.core.Observable;

class YouTubeLiveChatService implements LiveChatService {
    private static YouTubeLiveChatService sInstance;
    private final LiveChatServiceInt mLiveChatServiceInt;

    private YouTubeLiveChatService() {
        mLiveChatServiceInt = LiveChatServiceInt.INSTANCE;
    }

    public static YouTubeLiveChatService instance() {
        if (sInstance == null) {
            sInstance = new YouTubeLiveChatService();
        }

        return sInstance;
    }

    @Override
    public Observable<ChatItem> openLiveChatObserve(String chatKey) {
        return RxHelper.createLong(emitter -> {
            // NEWTUBE(chat-backoff): the poll loop never ends on its own, so hand it the subscriber's
            // disposed state - disposal used to leave it polling (the interrupted read came back
            // wrapped in IllegalStateException and was retried like any other error).
            mLiveChatServiceInt.openLiveChat(
                    chatKey, emitter::onNext, emitter::isDisposed
            );

            emitter.onComplete();
        });
    }
}
