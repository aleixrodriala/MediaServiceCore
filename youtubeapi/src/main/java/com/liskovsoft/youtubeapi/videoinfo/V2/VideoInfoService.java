package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.innertube.initialresponse.InitialResponseService;
import com.liskovsoft.youtubeapi.videoinfo.VideoInfoServiceBase;
import com.liskovsoft.youtubeapi.videoinfo.models.CaptionTrack;
import com.liskovsoft.youtubeapi.videoinfo.models.TranslationLanguage;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoHls;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfoReel;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import retrofit2.Call;

public class VideoInfoService extends VideoInfoServiceBase {
    private static final String TAG = VideoInfoService.class.getSimpleName();
    private static VideoInfoService sInstance;
    private final VideoInfoApi mVideoInfoApi;
    private final static AppClient[] VIDEO_INFO_TYPE_LIST = {
            AppClient.WEB_EMBED, // Restricted (18+) videos
            AppClient.ANDROID_VR, // doesn't require pot and cipher (often hangs?)
            AppClient.ANDROID_REEL, // doesn't require pot and cipher
            AppClient.TV, // Supports auth. Fixes "please sign in" bug!
            AppClient.WEB, // Fix video clip blocked in current location
            AppClient.WEB_SAFARI,
            AppClient.IOS,
            AppClient.GEO, // Fix video clip blocked in current location
            AppClient.MWEB, // single audio language
            AppClient.TV_LEGACY,
            AppClient.TV_DOWNGRADED,
            AppClient.TV_EMBED, // single audio language
            AppClient.TV_SIMPLY, // hangs?
            //AppClient.ANDROID_SDK_LESS, // doesn't require pot (hangs on cronet!)
    };
    // === Mobile fast-start (NewTube touch flavor) =========================================
    // When enabled by the mobile flavor, getVideoInfo tries a no-PO-token / no-cipher client
    // (ANDROID_VR) FIRST, then ANDROID_REEL, then the rest of the list, keeping WEB_EMBED as the
    // last-resort fallback so restricted / age-gated videos still play. This skips the ~2.7s of
    // self-inflicted PO-token + signature work that WEB_EMBED (the default first client) incurs on
    // every cold start. TV builds never enable this flag, so they keep the WEB_EMBED-first order
    // and unbounded (no-timeout) behaviour byte-for-byte.
    private static volatile boolean sPreferNoPotClient;
    private static final AppClient PREFERRED_FIRST_CLIENT = AppClient.ANDROID_VR;
    // Short per-attempt timeout guarding a hanging fast client (ANDROID_VR "often hangs?"). Applied
    // ONLY to fast (non-web-pot) clients on the mobile path; web-pot clients (WEB_EMBED) run
    // unbounded because their PO-token generation can legitimately take several seconds. The base
    // OkHttp read/connect timeout is 20s with no overall call timeout, so without this a hang would
    // stall TTFF ~20s+ instead of failing over.
    private static final long CLIENT_ATTEMPT_TIMEOUT_MS = 7_000;
    private static ExecutorService sInfoExecutor;
    // Phone ring trim (NewTube touch flavor): the tail of VIDEO_INFO_TYPE_LIST is four TV-app
    // fallback clients that only earn their keep on TV boxes; on a phone they just lengthen the
    // failover walk of a hard video (4 extra /player round-trips per sweep). Gated the same way as
    // the other mobile-only switches in this file (static setter called once from
    // MobileMainApplication); VIDEO_INFO_TYPE_LIST itself stays untouched to keep the upstream
    // merge surface clean (upstream churns that list on every YouTube breakage). AppClient.TV is
    // NOT skipped: it's the ring's only auth-capable client (fixes "please sign in").
    private static volatile boolean sSkipTvFallbackClients;
    private static final AppClient[] TV_FALLBACK_CLIENTS = {
            AppClient.TV_LEGACY, AppClient.TV_DOWNGRADED, AppClient.TV_EMBED, AppClient.TV_SIMPLY
    };

    /**
     * Enabled once from the mobile flavor (MobileMainApplication). Makes getVideoInfo prefer a
     * no-PO-token/no-cipher client first for faster cold-start TTFF. Never called on TV.
     */
    public static void setPreferNoPotClient(boolean prefer) {
        sPreferNoPotClient = prefer;
    }

    /**
     * Enabled once from the mobile flavor (MobileMainApplication). Makes the failover ring skip
     * the TV-only fallback clients ({@link #TV_FALLBACK_CLIENTS}). Never called on TV.
     */
    public static void setSkipTvFallbackClients(boolean skip) {
        sSkipTvFallbackClients = skip;
    }

    private static boolean isSkippedClient(AppClient client) {
        return sSkipTvFallbackClients && Helpers.equalsAny(client, (Object[]) TV_FALLBACK_CLIENTS);
    }

    @Nullable
    private AppClient mActualInfoType = null;
    @Nullable
    private AppClient mNextInfoType = null;
    // Guards the one-time restore of the persisted "winning" fast client at cold start (mobile only).
    private boolean mInfoTypeRestored;
    private boolean mAuthBlock;
    private List<TranslationLanguage> mCachedTranslationLanguages;
    private boolean mIsUnplayable;

    private VideoInfoService() {
        mVideoInfoApi = RetrofitHelper.create(VideoInfoApi.class);
    }

    public static VideoInfoService instance() {
        if (sInstance == null) {
            sInstance = new VideoInfoService();
        }

        return sInstance;
    }

    public VideoInfo getVideoInfo(String videoId, String clickTrackingParams) {
        if (videoId == null) {
            return null;
        }

        restoreVideoInfoTypeIfNeeded();

        AppService.instance().resetClientPlaybackNonce(); // unique value per each video info

        mAuthBlock = true;

        VideoInfo result = firstPlayable(videoId, clickTrackingParams);

        if (result == null) {
            Log.e(TAG, "Can't get video info. videoId: %s", videoId);
            return null;
        }

        Log.d(TAG, "getVideoInfo: winning client=%s videoId=%s", result.getClient(), videoId);

        applyFixesIfNeeded(result, videoId, clickTrackingParams);

        transformFormats(result);

        persistRecentTypeIfNeeded(result);

        mIsUnplayable = result.isUnplayable();

        return result;
    }

    public VideoInfo getAuthVideoInfo(String videoId, String clickTrackingParams) {
        if (videoId == null) {
            return null;
        }

        mAuthBlock = true;

        // Only the tv client supports auth features
        return getVideoInfo(AppClient.TV, videoId, clickTrackingParams);
    }

    /**
     * Walks the client ring ONCE from the remembered/preferred begin client and returns the first
     * PLAYABLE result. The first non-null (necessarily unplayable) result seen along the way is
     * remembered and returned as a fallback when the whole ring yields nothing playable, so the
     * caller still gets an "unplayable" reason to show. Same outcome as the old two-pass sweep
     * (pass 1: first playable, pass 2: first non-null) at half the worst-case /player call count.
     */
    private VideoInfo firstPlayable(String videoId, String clickTrackingParams) {
        //final AppClient beginType = getDefaultClient();
        // Mobile fast-start: when no client is remembered from a previous video, start at the
        // no-pot/no-cipher client instead of WEB_EMBED. Helpers.getNextValue wraps around the list,
        // so starting at ANDROID_VR still visits every client (WEB_EMBED becomes the last fallback).
        // TV (flag unset) keeps VIDEO_INFO_TYPE_LIST[0] (WEB_EMBED) as before.
        final AppClient defaultBegin = sPreferNoPotClient ? PREFERRED_FIRST_CLIENT : VIDEO_INFO_TYPE_LIST[0];
        final AppClient beginType = mNextInfoType != null ? mNextInfoType : defaultBegin;
        AppClient nextType = beginType;
        VideoInfo firstUnplayable = null;
        int attempt = 0;

        do {
            // Phone ring trim: TV-only fallback clients are skipped. The walk itself continues
            // unconditionally, so the do/while still terminates when it wraps back to beginType
            // even if beginType is a skipped client (e.g. a stale mNextInfoType from
            // nextVideoInfoType landing on a TV_* entry).
            if (!isSkippedClient(nextType)) {
                attempt++;
                VideoInfo result = getVideoInfoWithTimeout(nextType, videoId, clickTrackingParams);
                boolean playable = result != null && !result.isUnplayable();

                // Failover walks leave one logcat line per extra /player attempt (happy path =
                // one attempt = silent) so ring behavior is measurable in verify runs/forensics.
                // NetPath itself lives in the common module, which youtubeapi can't see -> raw tag.
                if (attempt > 1) {
                    android.util.Log.d("NetPath", "player-ring " + nextType + " attempt=" + attempt
                            + " playable=" + (playable ? "y" : "n"));
                }

                if (playable) {
                    return result;
                }

                if (firstUnplayable == null && result != null) {
                    firstUnplayable = result;
                }
            }

            nextType = Helpers.getNextValue(VIDEO_INFO_TYPE_LIST, nextType);
        } while (nextType != beginType);

        return firstUnplayable;
    }

    /**
     * Mobile-only guard: run a fast (non-web-pot) client attempt with a short timeout so a hanging
     * ANDROID_VR fails over to the next client quickly instead of blocking on the 20s OkHttp read
     * timeout. TV (flag unset) and web-pot clients (WEB_EMBED fallback for restricted videos, whose
     * PO-token generation can legitimately take several seconds) run unbounded exactly as before.
     */
    private VideoInfo getVideoInfoWithTimeout(AppClient client, String videoId, String clickTrackingParams) {
        if (!sPreferNoPotClient || client.isWebPotRequired()) {
            return getVideoInfoWithRentFix(client, videoId, clickTrackingParams);
        }

        Future<VideoInfo> future = getInfoExecutor().submit(() -> getVideoInfoWithRentFix(client, videoId, clickTrackingParams));

        try {
            return future.get(CLIENT_ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            Log.e(TAG, "getVideoInfo timed out for client %s after %s ms, failing over...", client, CLIENT_ATTEMPT_TIMEOUT_MS);
            future.cancel(true);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "getVideoInfo failed for client %s (%s), failing over...", client, e.getMessage());
            return null;
        }
    }

    private static ExecutorService getInfoExecutor() {
        if (sInfoExecutor == null) {
            sInfoExecutor = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "VideoInfoAttempt");
                t.setDaemon(true);
                return t;
            });
        }

        return sInfoExecutor;
    }

    //private void initInfoTypeIfNeeded() {
    //    if (mActualInfoType != null) {
    //        return;
    //    }
    //
    //    restoreVideoInfoType();
    //}

    public void switchNextFormat() {
        //initInfoTypeIfNeeded();

        // Try to reset pot cache for the last video
        if (!mIsUnplayable && mActualInfoType != null && PoTokenGate.resetCache(mActualInfoType)) {
            return;
        }
        // The Premium is likely broken
        //if (getData().isFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS)) {
        //    // Skip additional formats fetching that could produce an error
        //    getData().setFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS, false);
        //    return;
        //}
        // And last, try to switch the client
        nextVideoInfoType();
        //persistVideoInfoType();
    }

    public void switchNextSubtitle() {
        CaptionTrack.sFormat = Helpers.getNextValue(CaptionTrack.CaptionFormat.values(), CaptionTrack.sFormat);
    }

    public void resetInfoType() {
        resetInfoTypeToDefault();
        PoTokenGate.resetCache();
    }

    private void nextVideoInfoType() {
        mNextInfoType = Helpers.getNextValue(VIDEO_INFO_TYPE_LIST, mActualInfoType);
    }

    private VideoInfo getVideoInfoWithRentFix(AppClient client, String videoId, String clickTrackingParams) {
        VideoInfo result = getVideoInfo(client, videoId, clickTrackingParams);

        if (result != null && result.isRent()) {
            Log.e(TAG, "Found rent content. Show trailer instead...");
            result = getVideoInfo(client, result.getTrailerVideoId(), clickTrackingParams);
        }

        return result;
    }

    private VideoInfo getVideoInfo(AppClient client, String videoId, String clickTrackingParams) {
        VideoInfo result;

        if (client == AppClient.INITIAL) {
            result = InitialResponseService.getVideoInfo(videoId, mAuthBlock);
        } else {
            String videoInfoQuery = VideoInfoApiHelper.getVideoInfoQuery(client, videoId, clickTrackingParams);
            result = getVideoInfo(client, videoInfoQuery);
        }

        if (result != null) {
            result.setClient(client);
        }

        return result;
    }

    private VideoInfo getVideoInfo(AppClient client, String videoInfoQuery) {
        boolean auth = client.isAuthSupported() && mAuthBlock;

        if (client.isReelClient()) {
            Call<VideoInfoReel> wrapper = mVideoInfoApi.getVideoInfoReel(videoInfoQuery, mAppService.getVisitorData(),
                    client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());
            return getVideoInfoReel(wrapper, auth);
        }

        Call<VideoInfo> wrapper = mVideoInfoApi.getVideoInfo(videoInfoQuery, mAppService.getVisitorData(),
                client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());
        return getVideoInfo(wrapper, auth);
    }

    private @Nullable VideoInfo getVideoInfo(Call<VideoInfo> wrapper, boolean auth) {
        VideoInfo videoInfo = RetrofitHelper.get(wrapper, auth);

        if (videoInfo == null) {
            return null;
        }

        videoInfo.setAuth(auth);

        return videoInfo;
    }

    private @Nullable VideoInfo getVideoInfoReel(Call<VideoInfoReel> wrapper, boolean auth) {
        VideoInfoReel videoInfo = RetrofitHelper.get(wrapper, auth);

        if (videoInfo == null || videoInfo.getVideoInfo() == null) {
            return null;
        }

        videoInfo.getVideoInfo().setAuth(auth);

        return videoInfo.getVideoInfo();
    }

    private VideoInfoHls getVideoInfoIOSHls(String videoId, String clickTrackingParams) {
        String videoInfoQuery = VideoInfoApiHelper.getVideoInfoQuery(AppClient.IOS, videoId, clickTrackingParams);
        return getVideoInfoHls(AppClient.IOS, videoInfoQuery);
    }

    private VideoInfoHls getVideoInfoHls(AppClient client, String videoInfoQuery) {
        Call<VideoInfoHls> wrapper = mVideoInfoApi.getVideoInfoHls(videoInfoQuery, mAppService.getVisitorData(),
                client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());

        return RetrofitHelper.get(wrapper, client.isAuthSupported() && mAuthBlock);
    }

    private void applyFixesIfNeeded(VideoInfo result, String videoId, String clickTrackingParams) {
        if (result == null || result.isUnplayable()) {
            return;
        }

        // Mobile fast-start (NewTube touch flavor): the two fixes below are synchronous network
        // round-trips that gate first-playable-return. The subtitle-enrichment fetch uses the
        // web-pot WEB client, which regenerates the botguard PO-token (~1s) on every cold start;
        // the extended-HLS/storyboard fix is an extra IOS round-trip. Neither affects the base
        // playable formats or the video's OWN caption tracks - both are already present from the
        // winning fast client (e.g. ANDROID_VR). They only enrich the auto-translate target-language
        // list and the seek-preview storyboard. Defer both to a background thread so they never gate
        // first frame; the translation-language result also warms mCachedTranslationLanguages so
        // subsequent videos in the session apply it synchronously without any fetch. TV (flag unset)
        // keeps the original synchronous behaviour byte-for-byte.
        if (sPreferNoPotClient) {
            applyFixesAsync(result, videoId, clickTrackingParams);
            return;
        }

        applyFixesSync(result, videoId, clickTrackingParams);
    }

    private void applyFixesSync(VideoInfo result, String videoId, String clickTrackingParams) {
        if (shouldObtainExtendedFormats(result) || result.isStoryboardBroken()) {
            Log.d(TAG, "Enable high bitrate formats...");
            mAuthBlock = false;
            VideoInfoHls videoInfoHls = getVideoInfoIOSHls(videoId, clickTrackingParams);
            if (videoInfoHls != null && shouldObtainExtendedFormats(result)) {
                result.setHlsManifestUrl(videoInfoHls.getHlsManifestUrl());
            }
            if (videoInfoHls != null && result.isStoryboardBroken()) {
                result.setStoryboardSpec(videoInfoHls.getStoryboardSpec());
            }
        }

        // TV and others has a limited number of auto generated subtitles
        if (needMoreSubtitles(result)) {
            Log.d(TAG, "Enable full list of auto generated subtitles...");

            if (mCachedTranslationLanguages == null || mCachedTranslationLanguages.size() < 100) {
                mAuthBlock = false;
                VideoInfo webInfo = null;
                try {
                    webInfo = getVideoInfo(AppClient.WEB, videoId, clickTrackingParams);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (webInfo != null) {
                    mCachedTranslationLanguages = webInfo.getTranslationLanguages();
                }
            }

            if (mCachedTranslationLanguages != null) {
                result.setTranslationLanguages(mCachedTranslationLanguages);
            }
        }
    }

    /**
     * Mobile fast-start: run applyFixesSync's network fetches OFF the cold-start critical path.
     * If the translation-language cache is already warm from an earlier video this session, apply it
     * synchronously (no fetch). Otherwise defer the web-pot WEB subtitle-enrichment fetch and the IOS
     * extended-HLS/storyboard fetch to the shared background executor; their results warm the cache
     * and best-effort update this VideoInfo, so first frame is never gated by PO-token regeneration.
     * The video's own caption tracks and playable formats are already set from the winning client,
     * so base subtitles/CC still render immediately; only the extra auto-translate language list for
     * this first video may be smaller (it becomes full for later videos once the cache is warm).
     * WEB and IOS are not auth-supported (see AppClient.isAuthSupported), so these fetches run
     * unauthenticated regardless of mAuthBlock - the worker never touches that shared field.
     */
    private void applyFixesAsync(VideoInfo result, String videoId, String clickTrackingParams) {
        final boolean warmCache = mCachedTranslationLanguages != null && mCachedTranslationLanguages.size() >= 100;

        // Warm cache: apply immediately, no network round-trip needed.
        if (warmCache && needMoreSubtitles(result)) {
            result.setTranslationLanguages(mCachedTranslationLanguages);
        }

        final boolean needSubs = !warmCache && needMoreSubtitles(result);
        final boolean needExtended = shouldObtainExtendedFormats(result) || result.isStoryboardBroken();

        if (!needSubs && !needExtended) {
            return;
        }

        getInfoExecutor().submit(() -> {
            try {
                if (needExtended) {
                    Log.d(TAG, "Enable high bitrate formats (deferred)...");
                    VideoInfoHls videoInfoHls = getVideoInfoIOSHls(videoId, clickTrackingParams);
                    if (videoInfoHls != null && shouldObtainExtendedFormats(result)) {
                        result.setHlsManifestUrl(videoInfoHls.getHlsManifestUrl());
                    }
                    if (videoInfoHls != null && result.isStoryboardBroken()) {
                        result.setStoryboardSpec(videoInfoHls.getStoryboardSpec());
                    }
                }

                if (needSubs) {
                    Log.d(TAG, "Enable full list of auto generated subtitles (deferred)...");
                    VideoInfo webInfo = getVideoInfo(AppClient.WEB, videoId, clickTrackingParams);
                    if (webInfo != null && webInfo.getTranslationLanguages() != null) {
                        mCachedTranslationLanguages = webInfo.getTranslationLanguages();
                        result.setTranslationLanguages(mCachedTranslationLanguages);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "applyFixesAsync enrichment failed: %s", e.getMessage());
            }
        });
    }

    /**
     * Mobile-only: at the first getVideoInfo of the process, restore the "winning" fast client from a
     * previous session so subsequent cold starts skip straight to it (persisted by
     * persistRecentTypeIfNeeded). Only a fast (non-web-pot) client is restored: a one-off restricted
     * video that fell back to WEB_EMBED must not poison the fast path for normal videos. TV never
     * enables the flag, so TV restore stays disabled (WEB_EMBED-first order unchanged).
     */
    private void restoreVideoInfoTypeIfNeeded() {
        if (!sPreferNoPotClient || mInfoTypeRestored || mNextInfoType != null) {
            return;
        }

        // Prefs may not be ready on the very first call; try again on the next one.
        if (!GlobalPreferences.isInitialized()) {
            return;
        }

        mInfoTypeRestored = true;

        int videoInfoType = getData().getVideoInfoType();
        if (videoInfoType < 0 || videoInfoType >= AppClient.values().length) {
            return;
        }

        AppClient restored = AppClient.values()[videoInfoType];
        // Skipped (TV-only) clients aren't restored either: a winner persisted before the phone
        // ring trim existed must not make the ring begin at a client it would skip anyway.
        if (!restored.isWebPotRequired() && !isSkippedClient(restored) && Arrays.asList(VIDEO_INFO_TYPE_LIST).contains(restored)) {
            mNextInfoType = restored;
        }
    }

    private void resetInfoTypeToDefault() {
        mNextInfoType = null;
        mActualInfoType = VIDEO_INFO_TYPE_LIST[0];
        persistVideoInfoType();
    }

    private void persistVideoInfoType() {
        if (!GlobalPreferences.isInitialized()) {
            return;
        }

        getData().setVideoInfoType(mActualInfoType != null ? mActualInfoType.ordinal() : -1);
    }

    private void persistRecentTypeIfNeeded(VideoInfo videoInfo) {
        if (videoInfo == null || videoInfo.isUnplayable() || videoInfo.getClient() == mActualInfoType) {
            return;
        }

        mActualInfoType = videoInfo.getClient();
        persistVideoInfoType();
    }

    private static boolean shouldObtainExtendedFormats(VideoInfo result) {
        return getData().isFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS) && result.isExtendedHlsFormatsBroken();
    }

    private static boolean shouldUnlockMoreSubtitles(VideoInfo videoInfo) {
        return videoInfo != null && videoInfo.hasSubtitles() && getData().isMoreSubtitlesUnlocked();
    }

    private static boolean needMoreSubtitles(VideoInfo videoInfo) {
        return videoInfo != null && videoInfo.hasSubtitles() && (videoInfo.getTranslationLanguages() == null || videoInfo.getTranslationLanguages().size() < 100);
    }

    private static boolean isAuthSupported(AppClient client) {
        return client != null && client.isAuthSupported();
    }
}
