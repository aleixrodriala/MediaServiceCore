package com.liskovsoft.youtubeapi.app.models.cached;

import androidx.annotation.NonNull;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.youtubeapi.app.models.AppInfo;

public class AppInfoCached extends AppInfo {
    private static final String DELIM = "%aic%";
    private final String mPlayerUrl;
    private final String mClientUrl;
    private final String mVisitorData;
    // Fetch time. Lets a cold process reuse the persisted app info while it's still inside the
    // 10h refresh window (mobile fast-start) instead of paying the youtube.com round-trip again.
    // Serialized as an extra trailing field: specs persisted by older builds parse to 0 (= stale).
    private final long mTimestampMs;

    private AppInfoCached(String playerUrl, String clientUrl, String visitorData, long timestampMs) {
        mPlayerUrl = playerUrl;
        mClientUrl = clientUrl;
        mVisitorData = visitorData;
        mTimestampMs = timestampMs;
    }

    public static AppInfoCached fromString(String spec) {
        if (spec == null) {
            return null;
        }

        String[] split = Helpers.split(spec, DELIM);

        String playerUrl = Helpers.parseStr(split, 0);
        String clientUrl = Helpers.parseStr(split, 1);
        String visitorData = Helpers.parseStr(split, 2);
        long timestampMs = Helpers.parseLong(split, 3);

        return new AppInfoCached(playerUrl, clientUrl, visitorData, timestampMs);
    }

    public static AppInfoCached from(AppInfo appInfo) {
        if (appInfo == null) {
            return null;
        }

        return new AppInfoCached(appInfo.getPlayerUrl(), appInfo.getClientUrl(), appInfo.getVisitorData(),
                System.currentTimeMillis());
    }

    @NonNull
    @Override
    public String toString() {
        return Helpers.merge(DELIM, mPlayerUrl, mClientUrl, mVisitorData, mTimestampMs);
    }

    public long getTimestampMs() {
        return mTimestampMs;
    }

    @Override
    public String getPlayerUrl() {
        return mPlayerUrl;
    }

    @Override
    public String getClientUrl() {
        return mClientUrl;
    }

    @Override
    public String getVisitorData() {
        return mVisitorData;
    }

    public boolean validate() {
        return mPlayerUrl != null && mClientUrl != null && mVisitorData != null;
    }
}
