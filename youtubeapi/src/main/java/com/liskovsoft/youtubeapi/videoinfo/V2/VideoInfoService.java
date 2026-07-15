package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.googlecommon.common.helpers.RetrofitOkHttpHelper;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.innertube.initialresponse.InitialResponseService;
import com.liskovsoft.youtubeapi.videoinfo.VideoInfoServiceBase;
import com.liskovsoft.youtubeapi.videoinfo.models.BotCheckDetector;
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
import java.util.concurrent.atomic.AtomicLong;

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
    // When enabled by the mobile flavor, getVideoInfo tries the repaired no-PO-token / no-cipher
    // ANDROID_VR client FIRST. Its fallback tail is Web-family-first so restricted / made-for-kids
    // videos do not wander through unrelated platform identities, and an error-driven reload starts
    // directly in that Web partition. This skips roughly one second of median cold-start latency in
    // the Pixel 9 sample while retaining attested Web recovery. TV builds never enable this flag, so
    // they keep the WEB_EMBED-first order and unbounded (no-timeout) behaviour byte-for-byte.
    private static volatile boolean sPreferNoPotClient;
    private static final AppClient PREFERRED_FIRST_CLIENT = AppClient.ANDROID_VR;
    // Short per-attempt timeout guarding a hanging fast client (ANDROID_VR "often hangs?"). Applied
    // ONLY to fast (non-web-pot) clients on the mobile path; web-pot clients (WEB_EMBED) run
    // unbounded because their PO-token generation can legitimately take several seconds. The base
    // OkHttp read/connect timeout is 20s with no overall call timeout, so without this a hang would
    // stall TTFF ~20s+ instead of failing over.
    private static final long CLIENT_ATTEMPT_TIMEOUT_MS = 7_000;
    private static final long BOT_CHECK_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(15);
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
    // Web-family-first fallback (NewTube touch flavor): GVS acceptance is client/session-specific,
    // not a transport or carrier-CGNAT property. On-device isolation found that iOS and the old
    // Android VR request could return signed URLs whose init ranges worked but deep ranges got 403;
    // sibling Web clients remained healthy. Android VR is repaired separately by using the same
    // fresh Web-derived visitor identity as current extractors. This partition is still valuable:
    // after WEB_EMBED cannot serve a video, probe WEB/WEB_SAFARI/GEO/MWEB before falling through to
    // platform clients with different token requirements. During recovery, also defer the suspect
    // last winner so it cannot immediately win again. VIDEO_INFO_TYPE_LIST stays byte-identical for
    // upstream compatibility, and TV never enables this behavior.
    private static volatile boolean sPreferAttestedWebFallback;
    @Nullable
    private static volatile AppClient sDebugForcedClient;

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

    /**
     * Enabled once from the mobile flavor (MobileMainApplication). Makes the failover walk probe
     * Web-family clients (isWebPotRequired) before platform fallbacks with different identity and
     * token requirements. See {@link #sPreferAttestedWebFallback}. Never called on TV.
     */
    public static void setPreferAttestedWebFallback(boolean prefer) {
        sPreferAttestedWebFallback = prefer;
    }

    /** Debug-playground hook. The mobile app calls this only from a debuggable build. */
    public static boolean setDebugForcedClient(@Nullable String clientName) {
        if (clientName == null || clientName.trim().isEmpty()) {
            sDebugForcedClient = null;
            return true;
        }
        try {
            AppClient client = AppClient.valueOf(clientName.trim().toUpperCase(java.util.Locale.US));
            if (!Arrays.asList(VIDEO_INFO_TYPE_LIST).contains(client)) {
                return false;
            }
            sDebugForcedClient = client;
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Called once from the mobile flavor (MobileMainApplication). Initializes the WebView/BotGuard
     * generator in the background so the first Web-family request finds it warm. Never called on
     * TV, and deliberately does not mint a cross-platform token for Android/TV/iOS clients.
     */
    public static void warmUpPoTokenGate() {
        PoTokenGate.warmUp();
    }

    // Mobile live routing: WEB_EMBED (the ring head) answers live videos with an HLS-only
    // response (no dashManifestUrl -> no LiveDashManifestParser DVR window), and on
    // pot-enforcing networks its HLS segments 403 instantly — with or without a client-side
    // pot on the manifest/segment URLs (both verified on-device, Pixel 9 2026-07-12). When
    // enabled, a playable live result WITHOUT a dash manifest is held as fallback and the walk
    // continues toward a client that provides one (ANDROID_VR/TV). Costs one extra /player
    // round-trip per live open. TV never sets this -> TV behavior unchanged.
    private static volatile boolean sPreferDashManifestForLive;

    /**
     * Enabled once from the mobile flavor (MobileMainApplication). Never called on TV.
     */
    public static void setPreferDashManifestForLive(boolean prefer) {
        sPreferDashManifestForLive = prefer;
    }

    private static boolean isSkippedClient(AppClient client) {
        return sSkipTvFallbackClients && Helpers.equalsAny(client, (Object[]) TV_FALLBACK_CLIENTS);
    }

    @Nullable
    private volatile AppClient mActualInfoType = null;
    @Nullable
    private volatile AppClient mNextInfoType = null;
    // mNextInfoType is also used for the persisted-client cold-start hint. Keep an explicit bit so
    // only an error-driven cursor invokes recovery ordering and defers the previous winner.
    private volatile boolean mRecoveryWalk;
    // A next-video prefetch may already be inside synchronized getVideoInfo when the player reports
    // a media 403. That older request must not clear the recovery cursor installed by
    // switchNextFormat after it finishes. The generation makes cursor consumption conditional on
    // the request having observed the same routing state it is about to clear.
    private final AtomicLong mRoutingGeneration = new AtomicLong();
    // Guards the one-time restore of the persisted "winning" fast client at cold start (mobile only).
    private boolean mInfoTypeRestored;
    private boolean mAuthBlock;
    private volatile long mBotCheckCooldownUntilMs;
    private volatile boolean mBotCheckAuthenticatedAttempted;
    @Nullable
    private volatile VideoInfo mBotCheckResult;
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

    public synchronized VideoInfo getVideoInfo(String videoId, String clickTrackingParams) {
        if (videoId == null) {
            return null;
        }

        final long routingGeneration = mRoutingGeneration.get();
        final boolean authenticated = hasAuthentication();
        VideoInfo blockedResult = getActiveBotCheckResult(authenticated, videoId);
        if (blockedResult != null) {
            return blockedResult;
        }

        restoreVideoInfoTypeIfNeeded();

        AppService.instance().resetClientPlaybackNonce(); // unique value per each video info

        mAuthBlock = true;

        VideoInfo result = firstPlayable(videoId, clickTrackingParams, authenticated);

        // An error cursor and a persisted cold-start hint are both one-shot on mobile. Leaving either
        // set after a successful failover made every later open start from stale routing state.
        // TV keeps its historical behavior.
        if (sPreferAttestedWebFallback && routingGeneration == mRoutingGeneration.get()) {
            mNextInfoType = null;
            mRecoveryWalk = false;
        } else if (sPreferAttestedWebFallback) {
            android.util.Log.d("NetPath", "player-ring keep-newer-recovery requestGen="
                    + routingGeneration + " currentGen=" + mRoutingGeneration.get());
        }

        if (result == null) {
            Log.e(TAG, "Can't get video info. videoId: %s", videoId);
            return null;
        }

        Log.d(TAG, "getVideoInfo: winning client=%s videoId=%s", result.getClient(), videoId);

        applyFixesIfNeeded(result, videoId, clickTrackingParams);

        transformFormats(result);

        persistRecentTypeIfNeeded(result);

        mIsUnplayable = result.isUnplayable();

        if (!mIsUnplayable) {
            clearBotCheckCircuit();
        }

        return result;
    }

    public synchronized VideoInfo getAuthVideoInfo(String videoId, String clickTrackingParams) {
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
    private VideoInfo firstPlayable(String videoId, String clickTrackingParams, boolean authenticated) {
        //final AppClient beginType = getDefaultClient();
        // Mobile fast-start: when no client is remembered from a previous video, start at the
        // no-pot/no-cipher client instead of WEB_EMBED. buildVisitOrder keeps this fast head but
        // puts the Web family immediately behind it. TV (flag unset) keeps the raw ring as before.
        final AppClient lastWinner = mActualInfoType;
        final boolean recoveryWalk = mRecoveryWalk;
        // A normal signed-in open starts on the account-bearing TV route, matching yt-dlp's use
        // of tv_downgraded for authenticated extraction. An error-driven reload is different: its
        // cursor deliberately points past the client whose GVS URL just failed. Re-promoting TV on
        // that reload selected the same TV_DOWNGRADED client forever and defeated the entire 403
        // recovery ring. Let recovery honor the cursor and Web-family partition; auth headers are
        // still attached automatically if a later auth-capable client is reached.
        final boolean authenticatedRecovery = authenticated && recoveryWalk;
        final AppClient defaultBegin = authenticated && !authenticatedRecovery
                ? AppClient.TV
                : (sPreferNoPotClient ? PREFERRED_FIRST_CLIENT : VIDEO_INFO_TYPE_LIST[0]);
        final AppClient beginType = authenticated && !authenticatedRecovery
                ? AppClient.TV
                : (mNextInfoType != null ? mNextInfoType : defaultBegin);

        java.util.List<AppClient> visitOrder;
        if (sDebugForcedClient != null) {
            visitOrder = java.util.Collections.singletonList(sDebugForcedClient);
            android.util.Log.d("NetPath", "player-ring forced-client=" + sDebugForcedClient);
        } else {
            visitOrder = buildRequestVisitOrder(
                    beginType, lastWinner, sPreferAttestedWebFallback,
                    recoveryWalk, authenticated);
            if (authenticated && !recoveryWalk) {
                android.util.Log.d("NetPath", "player-ring authenticated-first=" + visitOrder.get(0));
            } else if (authenticatedRecovery) {
                android.util.Log.d("NetPath", "player-ring authenticated-recovery first="
                        + visitOrder.get(0) + " suspect=" + lastWinner);
            }
            if (recoveryWalk) {
                android.util.Log.d("NetPath", "player-ring recovery begin=" + beginType
                        + " suspect=" + lastWinner + " first=" + visitOrder.get(0));
            }
        }

        VideoInfo firstUnplayable = null;
        VideoInfo firstLoginRequired = null;
        VideoInfo liveWithoutDash = null;
        int attempt = 0;

        for (AppClient nextType : visitOrder) {
            // Phone ring trim: TV-only fallback clients are skipped (a stale mNextInfoType from
            // nextVideoInfoType may land on a TV_* entry; it's simply not probed).
            if (isSkippedClient(nextType)
                    && sDebugForcedClient != nextType
                    && !(authenticated && nextType == AppClient.TV_DOWNGRADED)) {
                continue;
            }

            attempt++;
            VideoInfo result = getVideoInfoWithTimeout(nextType, videoId, clickTrackingParams);
            boolean playable = result != null && !result.isUnplayable();
            logPlayerOutcome(videoId, nextType, attempt, result);

            if (result != null) {
                boolean repeatedLoginRequired = firstLoginRequired != null
                        && BotCheckDetector.isRepeatedLoginRequired(
                                firstLoginRequired.getRawPlayabilityStatus(),
                                firstLoginRequired.getPlayabilityStatus(),
                                result.getRawPlayabilityStatus(),
                                result.getPlayabilityStatus());
                if (result.isBotCheckRequired() || repeatedLoginRequired) {
                    tripBotCheckCircuit(result, nextType,
                            result.isBotCheckRequired() ? "explicit" : "repeated-login",
                            authenticated);
                    return result;
                }
                if (firstLoginRequired == null && result.isLoginRequired()) {
                    firstLoginRequired = result;
                }
            }

            // Failover walks leave one logcat line per extra /player attempt (happy path =
            // one attempt = silent) so ring behavior is measurable in verify runs/forensics.
            // NetPath itself lives in the common module, which youtubeapi can't see -> raw tag.
            if (attempt > 1) {
                android.util.Log.d("NetPath", "player-ring " + nextType + " attempt=" + attempt
                        + " playable=" + (playable ? "y" : "n"));
            }

            if (playable) {
                // Mobile live routing (see sPreferDashManifestForLive): hold an HLS-only live
                // result and keep walking toward a dash-manifest client.
                if (sPreferDashManifestForLive && result.isLive() && result.getDashManifestUrl() == null) {
                    if (liveWithoutDash == null) {
                        liveWithoutDash = result;
                        android.util.Log.d("NetPath", "player-ring " + nextType
                                + " live-no-dash, walking on");
                    }
                } else {
                    return result;
                }
            }

            if (firstUnplayable == null && result != null) {
                firstUnplayable = result;
            }
        }

        // Nobody offered a dash manifest for this live stream: the held HLS-only result is
        // still strictly better than an unplayable verdict.
        return liveWithoutDash != null ? liveWithoutDash : firstUnplayable;
    }

    /** One credential-free line per parsed /player result, including HTTP-200 playback failures. */
    private static void logPlayerOutcome(String videoId, AppClient client, int attempt,
            @Nullable VideoInfo result) {
        if (result == null) {
            android.util.Log.w("NetPath", "player-result video=" + videoId + " client=" + client
                    + " attempt=" + attempt + " parsed=null");
            return;
        }

        int adaptive = result.getAdaptiveFormats() != null ? result.getAdaptiveFormats().size() : 0;
        int regular = result.getRegularFormats() != null ? result.getRegularFormats().size() : 0;
        int usableAdaptive = 0;
        if (result.getAdaptiveFormats() != null) {
            for (com.liskovsoft.youtubeapi.videoinfo.models.formats.AdaptiveVideoFormat format
                    : result.getAdaptiveFormats()) {
                if (format != null && !format.isBroken()) {
                    usableAdaptive++;
                }
            }
        }
        android.util.Log.d("NetPath", "player-result video=" + videoId + " client=" + client
                + " attempt=" + attempt
                + " status=" + safeLogValue(result.getRawPlayabilityStatus(), 32)
                + " playable=" + (!result.isUnplayable() ? "y" : "n")
                + " auth=" + (result.isAuth() ? "y" : "n")
                + " formats=" + adaptive + '+' + regular + " usableAdaptive=" + usableAdaptive
                + " dash=" + (result.getDashManifestUrl() != null ? "y" : "n")
                + " hls=" + (result.getHlsManifestUrl() != null ? "y" : "n")
                + " sabr=" + (result.getServerAbrStreamingUrl() != null ? "y" : "n")
                + " reason=\"" + safeLogValue(result.getPlayabilityStatus(), 160) + "\"");
    }

    private static String safeLogValue(@Nullable String value, int maxLength) {
        if (value == null) {
            return "null";
        }
        String printable = value.replaceAll("[\\p{Cntrl}]+", " ").replaceAll("\\s+", " ").trim();
        return printable.length() <= maxLength
                ? printable
                : printable.substring(0, maxLength) + "…";
    }

    /**
     * A current authenticated TV response can be SABR-only. yt-dlp keeps an authenticated
     * downgraded TV identity for this case; try that single account-bearing fallback before any
     * anonymous Web client that may be under a guest/IP bot challenge.
     */
    static List<AppClient> promoteAuthenticatedTvFallback(List<AppClient> rawOrder) {
        java.util.List<AppClient> result = new java.util.ArrayList<>(rawOrder.size());
        result.add(AppClient.TV);
        result.add(AppClient.TV_DOWNGRADED);
        for (AppClient client : rawOrder) {
            if (client != AppClient.TV && client != AppClient.TV_DOWNGRADED) {
                result.add(client);
            }
        }
        return result;
    }

    /**
     * Applies the signed-in ordering policy without erasing an error-recovery cursor. Kept pure so
     * a regression that silently re-promotes the failed TV client can be covered by unit tests.
     */
    static List<AppClient> buildRequestVisitOrder(AppClient beginType,
            @Nullable AppClient lastWinner, boolean preferWebFamily, boolean recoveryWalk,
            boolean authenticated) {
        List<AppClient> order = buildVisitOrder(
                beginType, lastWinner,
                preferWebFamily && (!authenticated || recoveryWalk),
                recoveryWalk);
        return authenticated && !recoveryWalk
                ? promoteAuthenticatedTvFallback(order)
                : order;
    }

    private static boolean hasAuthentication() {
        String authorization = RetrofitOkHttpHelper.getAuthHeaders().get("Authorization");
        return authorization != null && !authorization.isEmpty();
    }

    @Nullable
    private VideoInfo getActiveBotCheckResult(boolean authenticated, String videoId) {
        VideoInfo result = mBotCheckResult;
        long remainingMs = mBotCheckCooldownUntilMs - android.os.SystemClock.elapsedRealtime();
        if (result == null || remainingMs <= 0) {
            if (result != null) {
                clearBotCheckCircuit();
            }
            return null;
        }

        // Allow exactly one authenticated recovery after a circuit was opened anonymously. If the
        // account-bearing attempt itself received the challenge, further opens stay inside the
        // cooldown too; repeatedly exempting a present-but-rejected account recreates the storm.
        if (authenticated && !mBotCheckAuthenticatedAttempted) {
            android.util.Log.d("NetPath", "bot-check bypass=authenticated video=" + videoId);
            return null;
        }

        android.util.Log.w("NetPath", "bot-check cooldown video=" + videoId
                + " remainingMs=" + remainingMs + " network=n");
        return result;
    }

    private void tripBotCheckCircuit(VideoInfo result, AppClient client, String signal,
            boolean authenticatedAttempted) {
        result.setBotCheckRequired(true);
        mBotCheckResult = result;
        mBotCheckCooldownUntilMs = android.os.SystemClock.elapsedRealtime() + BOT_CHECK_COOLDOWN_MS;
        mBotCheckAuthenticatedAttempted = authenticatedAttempted;
        mNextInfoType = null;
        mRecoveryWalk = false;
        android.util.Log.w("NetPath", "bot-check trip client=" + client
                + " signal=" + signal + " authAttempted=" + (authenticatedAttempted ? "y" : "n")
                + " cooldownMs=" + BOT_CHECK_COOLDOWN_MS);
    }

    private void clearBotCheckCircuit() {
        mBotCheckResult = null;
        mBotCheckCooldownUntilMs = 0;
        mBotCheckAuthenticatedAttempted = false;
    }
    /**
     * Pure visit-order builder, split out so the 403 recovery semantics can be unit-tested without
     * network calls. TV's ring-memory order stays byte-for-byte equivalent: begin, last winner,
     * then the rest of the ring. On the mobile web-first path the WHOLE order is partitioned, not
     * merely the tail; otherwise a non-web last winner bypasses the preference and wins again. The
     * one exception is the normal ANDROID_VR fast head: preserve it at attempt 1 and partition only
     * its tail, yielding VR -> Web family -> other platforms. During an error recovery the whole
     * order is partitioned and the suspect last winner is left at its natural wraparound position,
     * after its sibling Web clients, rather than being promoted back to attempt 2.
     */
    static List<AppClient> buildVisitOrder(AppClient beginType, @Nullable AppClient lastWinner,
            boolean preferWebFamily, boolean recoveryWalk) {
        java.util.List<AppClient> rawOrder = new java.util.ArrayList<>();
        rawOrder.add(beginType);

        boolean deferLastWinner = preferWebFamily && recoveryWalk;
        if (!deferLastWinner && lastWinner != null && lastWinner != beginType) {
            rawOrder.add(lastWinner);
        }

        for (AppClient type = Helpers.getNextValue(VIDEO_INFO_TYPE_LIST, beginType); type != beginType;
                type = Helpers.getNextValue(VIDEO_INFO_TYPE_LIST, type)) {
            if (deferLastWinner || type != lastWinner) {
                rawOrder.add(type);
            }
        }

        if (!preferWebFamily) {
            return rawOrder;
        }

        boolean keepVrFastHead = !recoveryWalk && beginType == PREFERRED_FIRST_CLIENT;
        java.util.List<AppClient> result = new java.util.ArrayList<>();
        if (keepVrFastHead || recoveryWalk) {
            if (keepVrFastHead) {
                result.add(PREFERRED_FIRST_CLIENT);
                // A previous Web winner is the best fallback hint; otherwise use the canonical
                // ring order, whose WEB_EMBED head handles the broadest set in device tests.
                if (lastWinner != null && lastWinner.isWebPotRequired()) {
                    result.add(lastWinner);
                }
            }

            for (AppClient type : VIDEO_INFO_TYPE_LIST) {
                if (type.isWebPotRequired() && type != lastWinner) {
                    result.add(type);
                }
            }
            // On recovery, a failed Web winner is retried only after every sibling Web client.
            if (recoveryWalk && lastWinner != null && lastWinner.isWebPotRequired()) {
                result.add(lastWinner);
            }

            for (AppClient type : rawOrder) {
                if (!type.isWebPotRequired() && (!keepVrFastHead || type != PREFERRED_FIRST_CLIENT)) {
                    result.add(type);
                }
            }
            return result;
        }

        java.util.List<AppClient> web = new java.util.ArrayList<>();
        java.util.List<AppClient> nonWeb = new java.util.ArrayList<>();
        for (AppClient type : rawOrder) {
            if (type.isWebPotRequired()) {
                web.add(type);
            } else {
                nonWeb.add(type);
            }
        }
        result.addAll(web);
        result.addAll(nonWeb);
        return result;
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

        // ANDROID_VR deliberately shares the Web token session's visitor identity, but it is still
        // a distinct /player/GVS platform. Clear that visitor session and continue into the Web
        // recovery partition; treating the cache clear as the whole fix just remints the failed VR
        // route. A Web-family winner can retry itself after a genuine Web token refresh as before.
        if (!mIsUnplayable && mActualInfoType == AppClient.ANDROID_VR) {
            PoTokenGate.resetCache();
            nextVideoInfoType();
            android.util.Log.d("NetPath", "player-ring circuit-break suspect=" + mActualInfoType
                    + " next=" + mNextInfoType);
            return;
        }

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
        clearBotCheckCircuit();
    }

    private void nextVideoInfoType() {
        mNextInfoType = Helpers.getNextValue(VIDEO_INFO_TYPE_LIST, mActualInfoType);
        mRecoveryWalk = true;
        mRoutingGeneration.incrementAndGet();
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
            VideoInfoApiHelper.PlayerRequest request =
                    VideoInfoApiHelper.getVideoInfoRequest(client, videoId, clickTrackingParams);
            result = getVideoInfo(client, request);
        }

        if (result != null) {
            result.setClient(client);
        }

        return result;
    }

    private VideoInfo getVideoInfo(AppClient client, VideoInfoApiHelper.PlayerRequest request) {
        boolean auth = client.isAuthSupported() && mAuthBlock;

        if (client.isReelClient()) {
            Call<VideoInfoReel> wrapper = mVideoInfoApi.getVideoInfoReel(request.query, request.visitorData,
                    client.getUserAgent(), client.getInnerTubeName(), client.getClientVersion());
            return getVideoInfoReel(wrapper, auth);
        }

        Call<VideoInfo> wrapper = mVideoInfoApi.getVideoInfo(request.query, request.visitorData,
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
        VideoInfoApiHelper.PlayerRequest request =
                VideoInfoApiHelper.getVideoInfoRequest(AppClient.IOS, videoId, clickTrackingParams);
        return getVideoInfoHls(AppClient.IOS, request);
    }

    private VideoInfoHls getVideoInfoHls(AppClient client, VideoInfoApiHelper.PlayerRequest request) {
        Call<VideoInfoHls> wrapper = mVideoInfoApi.getVideoInfoHls(request.query, request.visitorData,
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

        // Authenticated TV may be the only route still accepted while the guest Web session is
        // challenged. Its own caption tracks are already usable; don't spend another anonymous
        // /player request merely to enrich the optional auto-translate language list.
        final boolean skipGuestWebEnrichment = result.isAuth();
        final boolean needSubs = !warmCache && needMoreSubtitles(result) && !skipGuestWebEnrichment;
        final boolean needExtended = shouldObtainExtendedFormats(result) || result.isStoryboardBroken();

        if (skipGuestWebEnrichment && !warmCache && needMoreSubtitles(result)) {
            android.util.Log.d("NetPath", "player-enrichment web=n reason=authenticated video=" + videoId);
        }

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
        mRecoveryWalk = false;
        mActualInfoType = VIDEO_INFO_TYPE_LIST[0];
        mRoutingGeneration.incrementAndGet();
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
