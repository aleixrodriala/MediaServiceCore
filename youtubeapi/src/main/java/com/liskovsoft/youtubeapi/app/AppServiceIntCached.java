package com.liskovsoft.youtubeapi.app;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.app.models.AppInfo;
import com.liskovsoft.youtubeapi.app.models.ClientData;
import com.liskovsoft.youtubeapi.app.models.cached.AppInfoCached;
import com.liskovsoft.youtubeapi.app.models.cached.ClientDataCached;
import com.liskovsoft.youtubeapi.app.playerdata.PlayerDataExtractor;
import com.liskovsoft.youtubeapi.common.helpers.AppConstants;

import java.util.concurrent.atomic.AtomicBoolean;

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

    // See setPlayerJsReadAhead. Off by default -> TV behavior byte-for-byte unchanged.
    private static volatile boolean sPlayerJsReadAhead;

    /**
     * NEWTUBE(player-js-gate): set from the phone flavor (VideoInfoService.setPreferNoPotClient /
     * setPlayerJsGateEnabled). On: the extractor for a player this device has not validated
     * publishes its signatureTimestamp and cpn code as soon as its JS is read
     * ({@link PlayerJsReadAhead}), so a request that needs nothing else from it can go out while
     * the V8 validation carries on in the background. Off: every caller waits for the whole
     * construction, as before.
     */
    public static void setPlayerJsReadAhead(boolean enabled) {
        sPlayerJsReadAhead = enabled;
    }

    private final PlayerJsReadAhead mReadAhead = new PlayerJsReadAhead();
    private final AtomicBoolean mBackgroundBuild = new AtomicBoolean();
    // When the build in progress started (under mPlayerSync); for its read-ahead line.
    private long mBuildStartMs;
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
        final boolean readAhead = sPlayerJsReadAhead;

        if (mPlayerDataExtractor != null && Helpers.equalsAny(playerUrl, mPlayerDataExtractor.getPlayerUrl(), getFailedPlayerUrl())) {
            if (readAhead) {
                mReadAhead.markReady(playerUrl);
            }
            return mPlayerDataExtractor;
        }

        mBuildStartMs = android.os.SystemClock.elapsedRealtime();
        boolean built = false;
        try {
            firstValidExtractor(
                    readAhead,
                    playerUrl,
                    check(getData().getAppInfo()) ? getData().getAppInfo().getPlayerUrl() : null,
                    AppConstants.playerUrls.get(0)
            );
            built = true;
            if (readAhead) {
                logReadAheadValidation(playerUrl);
                mReadAhead.markReady(playerUrl);
            }
        } finally {
            if (readAhead) {
                if (!built) {
                    // NEWTUBE(player-js-gate): a build that threw left no extractor. What it read
                    // ahead must not go on serving requests, nor answer isPlayerCacheActual: they
                    // wait for the next build, and fail with it as before.
                    mReadAhead.discard(playerUrl);
                }
                mReadAhead.buildEnded();
            }
        }

        return mPlayerDataExtractor;
    }

    /**
     * NEWTUBE(player-js-gate): the read-ahead for the current player, waiting only for its JS to be
     * read (and starting a build in the background if none runs); null to ask the extractor as
     * before. See {@link PlayerJsReadAhead#await}.
     */
    @Override
    PlayerJsReadAhead.Data awaitPlayerJsReadAhead() {
        if (!sPlayerJsReadAhead) {
            return null;
        }

        final String playerUrl = getPlayerUrl();
        return mReadAhead.await(playerUrl, this::startBackgroundBuild,
                (reason, waitedMs) -> android.util.Log.d("NetPath", "player-js-gate wait"
                        + " fallback=" + reason.name().toLowerCase(java.util.Locale.US)
                        + " waitMs=" + waitedMs));
    }

    @Override
    boolean isPlayerJsValidationPending() {
        return sPlayerJsReadAhead && mReadAhead.hasPending();
    }

    /**
     * Builds the extractor off the request thread, so the request can go as soon as the JS is read
     * and an abandoned attempt never owns the build. One at a time: a second one would only queue
     * on the player lock. It builds for the app info's player, like every other caller.
     */
    private boolean startBackgroundBuild() {
        if (!mBackgroundBuild.compareAndSet(false, true)) {
            return true; // ours is running
        }

        Thread thread = new Thread(() -> {
            try {
                getPlayerDataExtractor();
            } catch (Throwable e) {
                // The request that waits falls back to the extractor, which fails there as before.
                android.util.Log.w("NetPath", "player-js-gate build failed error=" + e.getClass().getSimpleName());
            } finally {
                mBackgroundBuild.set(false);
                // Only after the flag: a request that found this thread still running (its build
                // already ended) waits for one more end, and this is it.
                mReadAhead.buildEnded();
            }
        }, "PlayerJsValidation");
        thread.setDaemon(true);
        try {
            thread.start();
            return true;
        } catch (Throwable e) {
            // The waiting request asks the extractor itself, which builds on its own thread.
            mBackgroundBuild.set(false);
            android.util.Log.w("NetPath", "player-js-gate build not started error=" + e.getClass().getSimpleName());
            return false;
        }
    }

    /** On the building thread, under mPlayerSync: the app info's player JS was read. */
    private void onPlayerJsRead(String playerUrl, String signatureTimestamp, String cpnCode) {
        long nowMs = android.os.SystemClock.elapsedRealtime();
        android.util.Log.d("NetPath", "player-js-gate read-ahead ms=" + (nowMs - mBuildStartMs)
                + " cpnCode=" + (cpnCode != null ? "y" : "n") + " validation=started");
        mReadAhead.publish(playerUrl, signatureTimestamp, cpnCode, nowMs);
    }

    /** Caller holds mPlayerSync, the build for {@code playerUrl} just finished. */
    private void logReadAheadValidation(String playerUrl) {
        PlayerJsReadAhead.Data read = mReadAhead.readFor(playerUrl);
        if (read == null || read.readAtMs < mBuildStartMs) {
            return; // nothing was read ahead by this build: nobody could have gone early
        }
        long nowMs = android.os.SystemClock.elapsedRealtime();
        PlayerDataExtractor extractor = mPlayerDataExtractor;
        android.util.Log.d("NetPath", "player-js-gate validated ms=" + (nowMs - read.readAtMs)
                + " totalMs=" + (nowMs - mBuildStartMs)
                + " valid=" + (extractor != null && extractor.validate() ? "y" : "n")
                + " player=" + (extractor != null && playerUrl.equals(extractor.getPlayerUrl()) ? "main" : "fallback"));
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
    public void rotateVisitorData() {
        synchronized (mAppInfoSync) {
            super.rotateVisitorData();
            // Without this the persisted copy adopted at cold start (see getAppInfoSync) would
            // hand the challenged visitorData straight back on the next process.
            getData().setAppInfo(null);
        }
    }

    @Override
    public void invalidateCache() {
        mAppInfo = null;
        // Don't reset Player's cache. It's too heavy to recreate often.
        // Better do it inside MediaServiceData after the update
    }

    @Override
    public boolean isPlayerCacheActual() {
        // NEWTUBE(player-js-gate): a read-ahead player's validation holds mPlayerSync for seconds,
        // and the format cache asks this right after an answer that needed no solve came back
        // (the tap-time prefetch stores, the player's own fetch reads). A build that has read its
        // JS, or has finished, ends with an extractor, so answer what the lock would have.
        if (sPlayerJsReadAhead && mReadAhead.hasPlayer()) {
            return true;
        }
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

    private void firstValidExtractor(boolean readAhead, String... playerUrls) {
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

            // NEWTUBE(player-js-gate): only the app info's player reads ahead. Its timestamp is the
            // one the finished extractor carries whichever player validates (actualTimestamp below).
            mPlayerDataExtractor = readAhead && idx == MAIN
                    ? new PlayerDataExtractor(url, (timestamp, cpnCode) -> onPlayerJsRead(url, timestamp, cpnCode))
                    : super.getPlayerDataExtractor(url);

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
