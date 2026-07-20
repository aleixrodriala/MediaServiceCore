package com.liskovsoft.mediaserviceinterfaces.data;

/**
 * A remote YouTube Lounge screen (TV app) the phone can cast to.<br/>
 * Only {@code screenId} is required to (re)connect — the lounge token is minted on demand.
 * Instances are created from manual TV-code pairing or DIAL discovery and persisted app-side.
 */
public final class CastScreen {
    private final String mScreenId;
    private final String mName;

    public CastScreen(String screenId, String name) {
        mScreenId = screenId;
        mName = name;
    }

    public String getScreenId() {
        return mScreenId;
    }

    public String getName() {
        return mName;
    }
}
