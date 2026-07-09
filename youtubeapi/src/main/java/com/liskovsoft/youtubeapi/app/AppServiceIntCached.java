package com.liskovsoft.youtubeapi.app;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.app.models.AppInfo;
import com.liskovsoft.youtubeapi.app.models.ClientData;
import com.liskovsoft.youtubeapi.app.models.cached.AppInfoCached;
import com.liskovsoft.youtubeapi.app.models.cached.ClientDataCached;
import com.liskovsoft.youtubeapi.app.playerdata.PlayerDataExtractor;
import com.liskovsoft.youtubeapi.common.helpers.AppConstants;

public class AppServiceIntCached extends AppServiceInt {
    private static final String TAG = AppServiceIntCached.class.getSimpleName();
    private static final long CACHE_REFRESH_PERIOD_MS = 10 * 60 * 60 * 1_000; // check updated core files every 10 hours

    // Mobile fast-start: reuse the persisted, extractor-validated app info at cold process start while
    // it's still inside the 10h refresh window, instead of paying the youtube.com round-trip that
    // otherwise sits serially inside the FIRST getVideoInfo of every process. Self-healing: the
    // persisted copy is nulled by firstValidExtractor when its playerUrl stops validating, which
    // forces the network path on the next start. Off by default -> TV behavior byte-for-byte unchanged.
    private static volatile boolean sPersistedAppInfoEnabled;

    public static void setPersistedAppInfoEnabled(boolean enabled) {
        sPersistedAppInfoEnabled = enabled;
    }
    private AppInfoCached mAppInfo;
    private ClientDataCached mClientData;
    private PlayerDataExtractor mPlayerDataExtractor;
    private long mAppInfoUpdateTimeMs;
    private final Object mAppInfoSync = new Object();
    private final Object mPlayerSync = new Object();
    private final Object mClientDataSync = new Object();

    @Override
    protected AppInfo getAppInfo(String userAgent) {
        synchronized (mAppInfoSync) {
            return getAppInfoSync(userAgent);
        }
    }

    private AppInfo getAppInfoSync(String userAgent) {
        if (mAppInfo != null && System.currentTimeMillis() - mAppInfoUpdateTimeMs < CACHE_REFRESH_PERIOD_MS) {
            return mAppInfo;
        }

        // Mobile: adopt the persisted copy at cold start if it's still fresh. Anchoring
        // mAppInfoUpdateTimeMs to the ORIGINAL fetch time keeps the 10h policy absolute -
        // the in-memory check above expires at the true boundary and refreshes over network.
        if (sPersistedAppInfoEnabled && mAppInfo == null) {
            AppInfoCached persisted = getData().getAppInfo();
            if (check(persisted) && persisted.getTimestampMs() > 0
                    && System.currentTimeMillis() - persisted.getTimestampMs() < CACHE_REFRESH_PERIOD_MS) {
                Log.d(TAG, "using persisted app info (age %s min)",
                        (System.currentTimeMillis() - persisted.getTimestampMs()) / 60_000);
                mAppInfo = persisted;
                mAppInfoUpdateTimeMs = persisted.getTimestampMs();
                return mAppInfo;
            }
        }

        Log.d(TAG, "updateAppInfoData");

        AppInfo appInfo = super.getAppInfo(userAgent);

        mAppInfo = AppInfoCached.from(appInfo);
        mAppInfoUpdateTimeMs = System.currentTimeMillis();

        return mAppInfo;
    }

    @Override
    public PlayerDataExtractor getPlayerDataExtractor(String playerUrl) {
        synchronized (mPlayerSync) {
            return getPlayerDataExtractorSync(playerUrl);
        }
    }

    private PlayerDataExtractor getPlayerDataExtractorSync(String playerUrl) {
        if (mPlayerDataExtractor != null && Helpers.equalsAny(playerUrl, mPlayerDataExtractor.getPlayerUrl(), getFailedPlayerUrl())) {
            return mPlayerDataExtractor;
        }

        firstValidExtractor(
                playerUrl,
                check(getData().getAppInfo()) ? getData().getAppInfo().getPlayerUrl() : null,
                AppConstants.playerUrls.get(0)
        );

        return mPlayerDataExtractor;
    }

    @Override
    protected ClientData getClientData(String clientUrl) {
        synchronized (mClientDataSync) {
            return getClientDataSync(clientUrl);
        }
    }

    private ClientData getClientDataSync(String clientUrl) {
        if (mClientData != null && Helpers.equals(clientUrl, mClientData.getClientUrl())) {
            return mClientData;
        }

        ClientDataCached clientDataCached = getData().getClientData();

        if (clientDataCached != null && Helpers.equals(clientUrl, clientDataCached.getClientUrl())) {
            mClientData = clientDataCached;
            return mClientData;
        }

        Log.d(TAG, "updateClientData");

        ClientData clientData = super.getClientData(clientUrl);

        mClientData = ClientDataCached.from(clientUrl, clientData);

        if (check(mClientData)) {
            getData().setClientData(mClientData);
        }

        return mClientData;
    }

    @Override
    public void invalidateCache() {
        mAppInfo = null;
        // Don't reset Player's cache. It's too heavy to recreate often.
        // Better do it inside MediaServiceData after the update
    }

    @Override
    public boolean isPlayerCacheActual() {
        synchronized (mPlayerSync) {
            return mPlayerDataExtractor != null;
        }
    }

    private boolean check(AppInfoCached appInfo) {
        return appInfo != null && appInfo.validate();
    }

    private boolean check(ClientDataCached clientData) {
        return clientData != null && clientData.validate();
    }

    private String getFailedPlayerUrl() {
        return getData().getFailedAppInfo() != null ? getData().getFailedAppInfo().getPlayerUrl() : null;
    }

    private void firstValidExtractor(String... playerUrls) {
        int idx = -1;
        final int MAIN = 0;
        final int DATA = 1;
        final int APP_CONST = 2;
        String actualTimestamp = null;

        for (String url : playerUrls) {
            idx++;
            if (url == null) {
                continue;
            }

            mPlayerDataExtractor = super.getPlayerDataExtractor(url);

            if (mPlayerDataExtractor.validate()) {
                switch (idx) {
                    case MAIN:
                        getData().setAppInfo(mAppInfo);
                        getData().setFailedAppInfo(null);
                        break;
                    case DATA:
                    case APP_CONST:
                        getData().setFailedAppInfo(mAppInfo);
                        getData().setAppInfo(null);
                        break;
                }

                if (actualTimestamp != null) {
                    mPlayerDataExtractor.setSignatureTimestamp(actualTimestamp);
                }

                break;
            }

            // Try to fetch the actual timestamp for old players. Needed for history (tracking) and possibly more.
            // NOTE: the older player may not work on newer timestamp
            if (idx == MAIN) {
                actualTimestamp = mPlayerDataExtractor.getSignatureTimestamp();
            }
        }
    }
}
