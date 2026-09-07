package com.liskovsoft.youtubeapi.videoinfo.V2;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.app.nsigsolver.impl.V8ChallengeProvider;
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
    private static volatile VideoInfoService sInstance;
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
    // FIRST (PREFERRED_FIRST_CLIENT). Its fallback tail is Web-family-first so restricted /
    // made-for-kids videos do not wander through unrelated platform identities, and an
    // error-driven reload starts directly in that Web partition. This skips roughly one second
    // of median cold-start latency in the Pixel 9 sample while retaining attested Web recovery.
    // TV builds never enable this flag, so they keep the WEB_EMBED-first order and unbounded
    // (no-timeout) behaviour byte-for-byte.
    private static volatile boolean sPreferNoPotClient;
    private static final AppClient PREFERRED_FIRST_CLIENT = AppClient.VISIONOS;
    // Short per-attempt timeout guarding a hanging fast client (ANDROID_VR "often hangs?"). Applied
    // to fast (non-web-pot) clients on the mobile path; web-pot clients get the larger
    // WEB_POT_ATTEMPT_TIMEOUT_MS because their PO-token generation can legitimately take several
    // seconds. The base OkHttp read/connect timeout is 20s with no overall call timeout, so without
    // this a hang would stall TTFF ~20s+ instead of failing over.
    private static final long CLIENT_ATTEMPT_TIMEOUT_MS = 7_000;

    /**
     * Per-attempt budget for the AUTHENTICATED head ({@link #AUTHENTICATED_HEAD}). The 7s
     * above was chosen for a speculative fast client (ANDROID_VR, which "often hangs"), where
     * failing over early costs a second and gains a second. The head is not that: for a
     * signed-in open it IS the route, and falling through it is expensive out of all
     * proportion to the wait. Measured on a roaming link: TV_DOWNGRADED normally answers in
     * 0.9-2.6s, but a COLD first request (DNS + TLS, no warm connection) overran 7s, and the
     * fallthrough then cost ~12s to first frame plus a 10-MINUTE quarantine of the whole
     * authenticated route - every open in that window served anonymously. So the head gets a
     * budget sized for a cold start on a slow link, still short enough to fail over before
     * OkHttp's own 20s read/connect timeout would.
     */
    private static final long AUTH_HEAD_ATTEMPT_TIMEOUT_MS = 15_000;

    /**
     * Per-attempt budget for a web-pot client (WEB, MWEB, WEB_EMBED, WEB_SAFARI, INITIAL, GEO —
     * {@link AppClient#isWebPotRequired()}). Until now these had NO deadline at all: the dispatch
     * in {@link #getVideoInfoWithTimeout} ran them inline on the walking thread, so the only bound
     * was OkHttp's 8s connect + 8s read per socket operation (RetrofitOkHttpHelper's open-path
     * interceptor), and the PO-token mint that precedes the request is not covered by that at all
     * (PoTokenWebView.generatePoToken blocks on a CountDownLatch with NO timeout). Five of the
     * phone ring's ~10 clients are web-pot, which is where the ~2-minute worst-case open came from.
     * <p>
     * Deliberately LARGER than {@link #CLIENT_ATTEMPT_TIMEOUT_MS}: a cold BotGuard mint
     * legitimately takes seconds (HANDOFF §8 — the app-start warmup alone is ~1.1s and a cold
     * content-pot cost ~2.7s before that warmup existed), and WEB_EMBED /player itself measured
     * 0.3–2.1s on LTE. Budget = 8s connect + 8s read (worst case for the request) + ~4s of headroom
     * for a cold mint = 20s. Sizing this tight would trade a bounded wait for the failure mode
     * §8 warns about: falling through the client that is the only one able to serve the video.
     */
    private static final long WEB_POT_ATTEMPT_TIMEOUT_MS = 20_000;

    /**
     * Wall-clock budget for the WHOLE failover walk, checked at the top of every iteration. The
     * per-attempt deadlines above bound ONE /player; nothing bounded their sum, so a bad link could
     * hold this service's process-wide monitor for minutes while the user watched a spinner.
     * <p>
     * Arithmetic (worst realistic prefix that is still worth waiting for): authenticated head 15s
     * + one web-pot client 20s = 35s, plus one 7s fast client = 42s → 45s. Attempts are clamped to
     * whatever is left of this budget, so 45s is a true ceiling for the walk rather than a
     * checkpoint that the last attempt can overrun. Mobile-only, like the per-attempt deadlines
     * (gated on {@link #sPreferNoPotClient}); TV keeps its historical unbounded walk.
     */
    private static final long RING_WALK_BUDGET_MS = 45_000;
    /**
     * Consecutive attempts that produce no HTTP response before the walk gives up and calls the
     * link dead rather than the clients bad. Two, so one client timing out on its own does not
     * abort a walk that would have succeeded on the next entry.
     */
    private static final int TRANSPORT_DOWN_STREAK = 2;

    /**
     * Below this the remaining walk budget cannot buy a useful /player attempt, so stop instead of
     * spending a round trip that would be cut off mid-flight.
     */
    private static final long MIN_ATTEMPT_BUDGET_MS = 1_500;
    private static final long BOT_CHECK_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(15);
    /**
     * While the circuit is armed, let ONE open per interval actually walk the ring instead of
     * serving the canned challenge verdict. A guest-session throttle lifts on the server's clock,
     * not ours, and without a probe the app would sit out the full {@link #BOT_CHECK_COOLDOWN_MS}
     * after the block had already gone. One extra walk a minute is a rounding error next to
     * telling the user "you are a bot" for fourteen minutes longer than YouTube did.
     */
    private static final long BOT_CHECK_PROBE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    /**
     * DIFFERENT videos that must answer an account-bearing client with a no-media verdict before
     * that route is quarantined (see {@link #isAuthRouteReloadVerdict}). One is meaningless - a
     * private, deleted, age-gated or geo-blocked video is genuinely UNPLAYABLE with no formats, and
     * demoting the account for it would cost the user authenticated playback on the NEXT video. The
     * same shape on two different videoIds cannot be a per-video verdict; it is the route.
     */
    private static final int AUTH_RELOAD_QUARANTINE_MIN_HITS = 2;
    /**
     * Signed-in probe head, most-likely-to-work first. yt-dlp's {@code _DEFAULT_AUTHED_CLIENTS} is
     * {@code ('tv_downgraded', 'web')} — plain {@code tv} appears in NO default client list — and
     * the 2026-07-27 Pixel-9 round measured why: TVHTML5 7.x (TV) is handed googlevideo URLs that
     * 403 every chunk once ~60s of media has been served (a soak caught it at {@code pos=59994}),
     * while TVHTML5 5.x (TV_DOWNGRADED) played the same video, same session, same network for
     * minutes with zero 403s. Leading with TV therefore guaranteed a mid-playback break on every
     * video watched longer than a minute.
     */
    private static final AppClient[] AUTHENTICATED_HEAD = {
            AppClient.TV_DOWNGRADED, AppClient.TV
    };
    /**
     * A real media 403 from an authenticated TV-family URL is route evidence, not an invitation to
     * select the same route for every next video. Held per client on that SAME Android default
     * network: the quarantined client is demoted behind its account-bearing sibling, and only when
     * EVERY client in {@link #AUTHENTICATED_HEAD} is quarantined does the walk give up on the
     * account and lead with the attested Web partition. Network replacement clears the quarantine
     * by key mismatch and the short TTL self-heals server-side changes.
     */
    private static final long AUTH_ROUTE_FORBIDDEN_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(10);
    /**
     * How long the anonymous partition stays deprioritized after it answers with bot challenges.
     * Matches {@link #BOT_CHECK_COOLDOWN_MS} — same underlying guest-session restriction.
     */
    private static final long ANON_CHALLENGE_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(15);
    /**
     * Two challenged anonymous clients inside ONE walk is the signal. A single LOGIN_REQUIRED can
     * be a genuine per-video gate; two different anonymous identities rejected back to back means
     * the guest session/IP itself is challenged, not the video.
     */
    private static final int ANON_CHALLENGE_MIN_HITS = 2;
    private static volatile ExecutorService sInfoExecutor;
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
    // Learned per-process: an authenticated TV /player response was SABR-only (every adaptive
    // format broken + serverAbrStreamingUrl present). This NO LONGER drives ordering — TV_DOWNGRADED
    // is now unconditionally the signed-in head (see AUTHENTICATED_HEAD), which subsumes the swap
    // this flag used to perform. Kept purely as an observation logged once per transition, because
    // "TV went SABR-only" is a useful marker when reading a session's NetPath trace.
    private static volatile boolean sAuthTvSabrOnly;
    // See setSkipStoryboardEnrichment.
    private static volatile boolean sSkipStoryboardEnrichment;
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

    /**
     * Enabled once from the mobile flavor (MobileMainApplication). The touch UI does not render
     * seek-preview storyboards yet (loadStoryboard is a stub), but a broken storyboard on the
     * winning client fires a deferred IOS /player per non-live open just to refetch a spec nobody
     * consumes. Skips that trigger; the extended-HLS trigger and the free storyboard apply when a
     * fetch happens anyway are unaffected. Remove the call when the mobile UI gains seek previews.
     */
    public static void setSkipStoryboardEnrichment(boolean skip) {
        sSkipStoryboardEnrichment = skip;
    }

    /** Debug-playground hook. The mobile app calls this only from a debuggable build. */
    public static boolean setDebugForcedClient(@Nullable String clientName) {
        if (clientName == null || clientName.trim().isEmpty()) {
            sDebugForcedClient = null;
            return true;
        }
        try {
            AppClient client = AppClient.valueOf(clientName.trim().toUpperCase(java.util.Locale.US));
            // PREFERRED_FIRST_CLIENT is deliberately off-ring, but forcing it is the whole point
            // of the playground when comparing fast heads.
            if (!Arrays.asList(VIDEO_INFO_TYPE_LIST).contains(client)
                    && client != PREFERRED_FIRST_CLIENT) {
                return false;
            }
            sDebugForcedClient = client;
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Debug playground: give ANDROID_VR a PO token in its /player request so the media URLs it
     * returns stop needing one (yt-dlp's `not_required_with_player_token`). Upstream flagged
     * intermittent POT enforcement on that client in 2026.07. Off by default - it trades the
     * client's cheapness for that protection, and we have not yet observed the enforcement here.
     */
    public static void setPlayerPotEnabled(boolean enabled) {
        PoTokenGate.setPlayerPotEnabled(enabled);
    }

    /**
     * Debug playground, mobile-only, OFF by default: let WEB_EMBED carry the account on /player
     * and lead the fallback walk with it, the way yt-dlp's signed-in client list does. AppClient
     * is module-internal, so the phone flavor flips it through here like every other switch.
     * Read {@link AppClient#setWebEmbedAuthEnabled} for why this is not a default.
     */
    public static void setWebEmbedAuthEnabled(boolean enabled) {
        AppClient.setWebEmbedAuthEnabled(enabled);
    }

    /**
     * Debug playground, mobile-only: point the web-family account gate at a NAMED client
     * instead of just WEB_EMBED, so the HTTP 400 measured on WEB_EMBED can be attributed.
     * Accepts a client name; returns false for anything that is not a web-family client on
     * the ring, so a typo cannot silently arm the account on a TV or native client.
     */
    public static boolean setWebAuthClient(@Nullable String clientName) {
        if (clientName == null || clientName.trim().isEmpty()) {
            AppClient.setWebAuthClient(null);
            return true;
        }
        try {
            AppClient client = AppClient.valueOf(
                    clientName.trim().toUpperCase(java.util.Locale.US));
            if (!client.isWebClient() && !client.isEmbedded()) {
                return false;
            }
            AppClient.setWebAuthClient(client);
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

    /**
     * Called once from the mobile flavor (MobileMainApplication). Keeps the signature solver's V8
     * runtime alive between video opens instead of disposing it after every solve, which put a
     * fresh-heap + solver-lib re-evaluation on the critical path of every open (and silently undid
     * the async warmup after the first video). The mobile flavor releases it again on memory
     * pressure. Never called on TV, where the historical dispose-every-time behaviour stands.
     */
    public static void setKeepSigRuntimeAlive(boolean keepAlive) {
        V8ChallengeProvider.setKeepRuntimeAlive(keepAlive);
    }

    /** Mobile memory-pressure hook: drop the retained solver runtime. Safe from any thread. */
    public static void releaseSigRuntime() {
        V8ChallengeProvider.releaseRuntime();
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
    /**
     * Set when the previous walk gave up because the link was dead (see TRANSPORT_DOWN_STREAK).
     * Suppresses the next walk's recovery cursor: see the comment in {@link #firstPlayable}.
     */
    private volatile boolean mLastWalkTransportDown;
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
    // Whether the walk that armed the circuit actually reached the END of the ring. Suppressing
    // later opens is only defensible once every client has been asked and every one refused; a
    // challenge seen at attempt 3 of 10 (the 2026-09-07 Rusowsky walk) establishes nothing about
    // the seven clients that were never tried.
    private volatile boolean mBotCheckRingExhausted;
    private volatile long mBotCheckNextProbeAtMs;
    @Nullable
    private volatile VideoInfo mBotCheckResult;
    // Consecutive no-media verdicts per account-bearing client, keyed by client and deduplicated by
    // videoId (see AUTH_RELOAD_QUARANTINE_MIN_HITS). Cleared for a client as soon as it answers
    // with anything else, so a route that recovers is never held down by stale hits.
    private final java.util.Map<AppClient, ReloadStreak> mAuthReloadStreaks =
            new java.util.concurrent.ConcurrentHashMap<>();
    // 403-quarantine of account-bearing routes, held PER CLIENT and scoped to one network. Per
    // client because the two TVHTML5 variants fail independently: TV (TVHTML5 7.x) hands out
    // googlevideo URLs that 403 every chunk past the pot-less ~60s mark, while TV_DOWNGRADED
    // (TVHTML5 5.x) keeps serving the same video on the same session. Quarantining "the
    // authenticated route" as a whole threw away the client that actually works.
    private final java.util.Map<AppClient, Long> mAuthRouteForbiddenUntilMs =
            new java.util.concurrent.ConcurrentHashMap<>();
    @Nullable
    private volatile String mAuthRouteForbiddenNetwork;
    // Per-network memory that the ANONYMOUS partition is under a bot challenge (see
    // noteAnonymousChallenge). While set, web-pot clients are probed after everything else.
    private volatile long mAnonChallengeUntilMs;
    @Nullable
    private volatile String mAnonChallengeNetwork;
    private List<TranslationLanguage> mCachedTranslationLanguages;
    private boolean mIsUnplayable;

    private VideoInfoService() {
        mVideoInfoApi = RetrofitHelper.create(VideoInfoApi.class);
    }

    public interface CancellationSignal {
        boolean isCanceled();
    }

    /** Per-client no-media streak. Mutated only under this service's monitor. */
    private static final class ReloadStreak {
        @Nullable
        String lastVideoId;
        int hits;
    }

    /**
     * Walk-scoped bot-check bookkeeping, extracted from {@link #firstPlayable}'s loop so the
     * decision it encodes can be tested without a network.
     * <p>
     * The decision: a challenge answered to an ANONYMOUS request is evidence about the guest
     * identity, not about the video or the ring. Returning on it aborted the walk mid-ring - on
     * 2026-09-07 (Fo89b8zAIE4, Pixel 9, cell) at attempt 3 of 10, before ANDROID_VR, which answered
     * the identical prefix with playable=y and 28 usable formats eight minutes later on the same
     * device, account and network (aqz-KE-bpKQ). So the verdict is HELD while any client remains
     * that does not answer from the challenged identity, published only if the ring then ends with
     * nothing playable, and discarded outright the moment something plays.
     * <p>
     * Separately it tracks whether the walk reached the END of the ring. Suppressing later opens
     * (see {@link #mBotCheckRingExhausted}) is only defensible once every client has been asked;
     * a walk that ran out of clock or link established nothing about the clients it never reached.
     * <p>
     * One instance per walk. Not thread-safe: {@code firstPlayable} runs under this service's
     * monitor and the instance never escapes it.
     */
    static final class BotCheckWalkState {
        @Nullable
        private VideoInfo mResult;
        @Nullable
        private AppClient mClient;
        @Nullable
        private String mSignal;
        private boolean mAuthAttempted;
        private boolean mCutShort;

        /** The verdict and the circuit arguments a finished walk should act on. */
        static final class Outcome {
            final VideoInfo result;
            final AppClient client;
            final String signal;
            final boolean authAttempted;
            final boolean ringExhausted;

            Outcome(VideoInfo result, AppClient client, String signal, boolean authAttempted,
                    boolean ringExhausted) {
                this.result = result;
                this.client = client;
                this.signal = signal;
                this.authAttempted = authAttempted;
                this.ringExhausted = ringExhausted;
            }
        }

        /** An early exit (cancel, walk budget, dead link): the ring was not finished. */
        void markCutShort() {
            mCutShort = true;
        }

        /** @see #mBotCheckRingExhausted */
        boolean ringExhausted() {
            return !mCutShort;
        }

        boolean hasHeldChallenge() {
            return mResult != null;
        }

        /**
         * Records a challenge and answers whether the walk may carry on past it. False means the
         * caller must trip the circuit and return this result now, exactly as it always did.
         * <p>
         * Only the FIRST challenge of a walk is held: it is the one the user's error screen should
         * name, and later ones are the same guest identity being refused again.
         *
         * @param mobile {@code sPreferNoPotClient}. TV never walks on - its historical
         *               abort-and-return behaviour is preserved byte for byte.
         */
        boolean recordChallenge(VideoInfo result, AppClient client, String signal,
                boolean authenticated, List<AppClient> order, int index, boolean mobile) {
            if (!mobile || !hasUnchallengedClientAfter(order, index, authenticated)) {
                return false;
            }

            if (mResult == null) {
                mResult = result;
                mClient = client;
                mSignal = signal;
                mAuthAttempted = authenticated;
            }
            return true;
        }

        /**
         * A client served the video. Whatever challenge was held is no longer this walk's verdict -
         * the guest identity being refused says nothing once something else has played.
         */
        void discardOnPlayable() {
            mResult = null;
            mClient = null;
            mSignal = null;
            mAuthAttempted = false;
        }

        /**
         * What a walk that ended WITHOUT a playable client should publish, or null for "nothing was
         * established". A dead link excludes everything: when nothing answered, nothing was learned
         * about anything (see TRANSPORT_DOWN_STREAK).
         */
        @Nullable
        Outcome finish(boolean transportDown) {
            if (mResult == null || transportDown) {
                return null;
            }
            return new Outcome(mResult, mClient, mSignal, mAuthAttempted, ringExhausted());
        }
    }
    /**
     * Holds the "account-bearing client answered with no media at all" observations of ONE walk
     * until something proves what they mean.
     *
     * <p>The shape {@link #isAuthRouteReloadVerdict} matches is produced by a broken auth route AND
     * by a video that is simply unavailable to everyone - a deleted upload, a region block, an
     * ended live stream whose recording was never published. Requiring
     * {@link #AUTH_RELOAD_QUARANTINE_MIN_HITS} different videoIds was meant to separate the two and
     * does not: two unavailable videos in a row is an ordinary afternoon, and the cost of getting
     * it wrong is silent - the account route is demoted and every later open is served anonymously,
     * losing age-restricted/members-only videos and server-side history with nothing on screen.
     *
     * <p>Measured on the Pixel 9 on 2026-09-07: opening the lofi 24/7 stream (jfKfPfyJRdk, whose
     * recording is not available) walked all eleven clients, every one of them refusing with the
     * same reason, and the two authenticated heads each scored a quarantine hit - on a route that
     * had answered correctly, with `reloadPage=n` right there on the log line.
     *
     * <p>The discriminator is free and already in the walk: did any OTHER client serve this video?
     * If yes, the auth head is the outlier and the observation is real evidence. If no, the video
     * is the outlier and it is evidence about nothing.
     */
    static final class AuthRouteWalkState {
        /** One held observation. The reload-page flag is captured here because the caller counts
         *  it later, once the result object it came from is out of scope. */
        static final class Held {
            final String videoId;
            final boolean reloadPage;

            Held(String videoId, boolean reloadPage) {
                this.videoId = videoId;
                this.reloadPage = reloadPage;
            }
        }

        private final java.util.Map<AppClient, Held> mHeld = new java.util.LinkedHashMap<>();
        private boolean mServedElsewhere;

        /**
         * @return true if the caller should count this observation now. False means it is held
         *         until a client serves the video; if none does, it is dropped.
         */
        boolean hold(AppClient client, String videoId, boolean reloadPage) {
            if (mServedElsewhere) {
                return true;
            }
            mHeld.put(client, new Held(videoId, reloadPage));
            return false;
        }

        /**
         * A client served this video. Everything held so far is now evidence about the route, and
         * anything observed later in the same walk counts immediately (a live result held for a
         * dash manifest keeps walking past clients that have already been outvoted).
         */
        java.util.Map<AppClient, Held> onPlayable() {
            mServedElsewhere = true;
            java.util.Map<AppClient, Held> proven = new java.util.LinkedHashMap<>(mHeld);
            mHeld.clear();
            return proven;
        }

        int heldCount() {
            return mHeld.size();
        }
    }


    public static VideoInfoService instance() {
        VideoInfoService result = sInstance;
        if (result == null) {
            synchronized (VideoInfoService.class) {
                result = sInstance;
                if (result == null) {
                    result = new VideoInfoService();
                    sInstance = result;
                }
            }
        }

        return result;
    }

    public VideoInfo getVideoInfo(String videoId, String clickTrackingParams) {
        return getVideoInfo(videoId, clickTrackingParams, null);
    }

    public synchronized VideoInfo getVideoInfo(String videoId, String clickTrackingParams,
            @Nullable CancellationSignal cancellationSignal) {
        if (videoId == null) {
            return null;
        }
        if (abortCanceledRequest(videoId, "entry", cancellationSignal)) {
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

        VideoInfo result = firstPlayable(
                videoId, clickTrackingParams, authenticated, cancellationSignal);
        if (abortCanceledRequest(videoId, "post-player", cancellationSignal)) {
            return null;
        }

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

        long fixesStartMs = android.os.SystemClock.elapsedRealtime();
        applyFixesIfNeeded(result, videoId, clickTrackingParams);
        android.util.Log.d("NetPath", "player-fixes video=" + videoId
                + " client=" + result.getClient()
                + " ms=" + (android.os.SystemClock.elapsedRealtime() - fixesStartMs));
        if (abortCanceledRequest(videoId, "pre-transform", cancellationSignal)) {
            return null;
        }

        long transformStartMs = android.os.SystemClock.elapsedRealtime();
        transformFormats(result);
        android.util.Log.d("NetPath", "player-transform video=" + videoId
                + " client=" + result.getClient()
                + " ms=" + (android.os.SystemClock.elapsedRealtime() - transformStartMs));
        if (abortCanceledRequest(videoId, "post-transform", cancellationSignal)) {
            return null;
        }

        persistRecentTypeIfNeeded(result);

        mIsUnplayable = result.isUnplayable();

        if (!mIsUnplayable) {
            clearBotCheckCircuit();
        }

        return result;
    }

    /**
     * A tap-time prefetch is disposable. When the user immediately selects another video, do not
     * let the obsolete request sit on this service's routing lock or enter the comparatively costly
     * signature transform. Java monitor acquisition itself is not interruptible, so the entry check
     * is essential for a canceled request that was queued behind another getVideoInfo call.
     */
    private static boolean abortCanceledRequest(String videoId, String stage,
            @Nullable CancellationSignal cancellationSignal) {
        if (!Thread.currentThread().isInterrupted()
                && (cancellationSignal == null || !cancellationSignal.isCanceled())) {
            return false;
        }

        android.util.Log.d("NetPath", "player-request canceled video=" + videoId
                + " stage=" + stage);
        return true;
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
    private VideoInfo firstPlayable(String videoId, String clickTrackingParams,
            boolean authenticated, @Nullable CancellationSignal cancellationSignal) {
        //final AppClient beginType = getDefaultClient();
        // Mobile fast-start: when no client is remembered from a previous video, start at the
        // no-pot/no-cipher client instead of WEB_EMBED. buildVisitOrder keeps this fast head but
        // puts the Web family immediately behind it. TV (flag unset) keeps the raw ring as before.
        final AppClient lastWinner = mActualInfoType;
        // NEWTUBE(net): an outage is not evidence against the client that was working.
        //
        // A recovery walk deliberately steps PAST the last winner, because the case it was written
        // for is a client-specific failure (an expired GVS URL answering 403). When the previous
        // walk instead ended in transport-down, nothing was learned about any client - so honouring
        // the cursor just abandons the known-good route. Measured on the netshape rig: a 150s tunnel
        // ended with playback restored on WEB_EMBED (auth=n, sabr=y) instead of the learned
        // TV_DOWNGRADED (auth=y, 41 formats), i.e. the outage silently cost the user authenticated
        // playback until something else reset the routing.
        final boolean recoveryWalk = mRecoveryWalk && !mLastWalkTransportDown;
        if (mRecoveryWalk && mLastWalkTransportDown) {
            android.util.Log.d("NetPath", "player-ring recovery-suppressed reason=transport-down"
                    + " keeping=" + lastWinner);
        }
        // Only this walk's own outcome may set it again.
        mLastWalkTransportDown = false;
        // A normal signed-in open starts on the account-bearing TV route, matching yt-dlp's use
        // of tv_downgraded for authenticated extraction. An error-driven reload is different: its
        // cursor deliberately points past the client whose GVS URL just failed. Re-promoting TV on
        // that reload selected the same TV_DOWNGRADED client forever and defeated the entire 403
        // recovery ring. Let recovery honor the cursor and Web-family partition; auth headers are
        // still attached automatically if a later auth-capable client is reached.
        final boolean authenticatedRecovery = authenticated && recoveryWalk;
        final java.util.Set<AppClient> forbiddenAuthClients = forbiddenAuthClients();
        // Only a FULLY quarantined account head hands the walk to the anonymous partition. A single
        // 403 (in practice always TV's ~60s pot-less expiry) used to send every open for the next
        // 10 minutes straight into anonymous Web — six guaranteed-dead round trips per open on a
        // bot-challenged network, while the sibling TV_DOWNGRADED route was healthy the whole time.
        final boolean authenticatedWebFirst = authenticated && !authenticatedRecovery
                && forbiddenAuthClients.size() >= AUTHENTICATED_HEAD.length;
        final boolean anonChallenged = isAnonPartitionChallenged();
        final AppClient authBegin = authenticatedWebFirst
                ? AppClient.WEB_EMBED
                : AUTHENTICATED_HEAD[0];
        final AppClient defaultBegin = authenticated && !authenticatedRecovery
                ? authBegin
                : (sPreferNoPotClient ? PREFERRED_FIRST_CLIENT : VIDEO_INFO_TYPE_LIST[0]);
        final AppClient beginType = authenticated && !authenticatedRecovery
                ? authBegin
                : (mNextInfoType != null ? mNextInfoType : defaultBegin);

        java.util.List<AppClient> visitOrder;
        if (sDebugForcedClient != null) {
            visitOrder = java.util.Collections.singletonList(sDebugForcedClient);
            android.util.Log.d("NetPath", "player-ring forced-client=" + sDebugForcedClient);
        } else {
            visitOrder = buildRequestVisitOrder(
                    beginType, lastWinner, sPreferAttestedWebFallback,
                    recoveryWalk, authenticated, forbiddenAuthClients, authenticatedWebFirst,
                    anonChallenged);
            if (anonChallenged) {
                android.util.Log.d("NetPath", "player-ring anon-deprioritized network="
                        + mAnonChallengeNetwork + " first=" + visitOrder.get(0));
            }
            if (authenticatedWebFirst) {
                // Reason deliberately generic: the head can now be quarantined by a media 403 OR by
                // the no-media verdict (see noteAuthRouteVerdict), and the per-client cause is
                // already on the quarantine-auth-route line that armed it.
                android.util.Log.w("NetPath", "player-ring authenticated-web-first reason=auth-head-quarantined"
                        + " failedClients=" + forbiddenAuthClients
                        + " network=" + mAuthRouteForbiddenNetwork);
            } else if (authenticated && !recoveryWalk && !forbiddenAuthClients.isEmpty()) {
                android.util.Log.d("NetPath", "player-ring authenticated-first=" + visitOrder.get(0)
                        + " demoted=" + forbiddenAuthClients);
            } else if (authenticated && !recoveryWalk) {
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
        // Round trips saved by not probing clients that cannot return a live dash manifest.
        int liveDashSkipped = 0;
        // Holds a challenge the walk carried on past, and tracks whether the ring was finished.
        // See BotCheckWalkState.
        final BotCheckWalkState botCheck = new BotCheckWalkState();
        // Holds auth-route no-media observations until a client proves the video is playable at
        // all. See AuthRouteWalkState.
        final AuthRouteWalkState authRoute = new AuthRouteWalkState();
        boolean authenticatedClientAttempted = false;
        int anonChallengeHits = 0;
        int attempt = 0;
        // See RING_WALK_BUDGET_MS. Mobile-only; on TV the deadline is never armed.
        final long walkDeadlineMs = android.os.SystemClock.elapsedRealtime() + RING_WALK_BUDGET_MS;
        boolean budgetExhausted = false;
        // See TRANSPORT_DOWN_STREAK.
        int noResponseStreak = 0;
        boolean transportDown = false;

        for (int visitIndex = 0; visitIndex < visitOrder.size(); visitIndex++) {
            final AppClient nextType = visitOrder.get(visitIndex);
            if (abortCanceledRequest(videoId, "client-ring", cancellationSignal)) {
                botCheck.markCutShort();
                break;
            }

            // Overall wall-clock bound (see RING_WALK_BUDGET_MS). `attempt > 0` guarantees the walk
            // always spends at least one round trip, whatever the clock says.
            long remainingBudgetMs = walkDeadlineMs - android.os.SystemClock.elapsedRealtime();
            if (sPreferNoPotClient && attempt > 0 && remainingBudgetMs < MIN_ATTEMPT_BUDGET_MS) {
                budgetExhausted = true;
                botCheck.markCutShort();
                android.util.Log.w("NetPath", "player-ring budget-exhausted video=" + videoId
                        + " attempts=" + attempt + " budgetMs=" + RING_WALK_BUDGET_MS
                        + " remainingMs=" + remainingBudgetMs + " nextClient=" + nextType
                        + " heldUnplayable=" + (firstUnplayable != null ? "y" : "n")
                        + " heldLive=" + (liveWithoutDash != null ? "y" : "n"));
                break;
            }

            // Phone ring trim: TV-only fallback clients are skipped (a stale mNextInfoType from
            // nextVideoInfoType may land on a TV_* entry; it's simply not probed).
            if (isSkippedClient(nextType)
                    && sDebugForcedClient != nextType
                    && !(authenticated && nextType == AppClient.TV_DOWNGRADED)) {
                continue;
            }

            // A playable live result is already held and ONLY a dash manifest can improve on it
            // (see sPreferDashManifestForLive), so a client that never returns one cannot change
            // the outcome - it can only add a round trip. See isLiveDashCandidate.
            if (liveWithoutDash != null && !isLiveDashCandidate(nextType)) {
                liveDashSkipped++;
                continue;
            }

            attempt++;
            boolean[] noResponse = new boolean[1];
            VideoInfo result = getVideoInfoWithTimeout(
                    nextType, videoId, clickTrackingParams, cancellationSignal, remainingBudgetMs,
                    noResponse);
            if (abortCanceledRequest(videoId, "post-attempt", cancellationSignal)) {
                botCheck.markCutShort();
                break;
            }
            // isAuthCapable, not isAuthSupported: this flag answers "has the account had its turn
            // yet?", which gates the bot-check defer-until-auth-client branch below. If WEB_EMBED
            // is carrying the account (see AppClient.setWebEmbedAuthEnabled) then once it has been
            // attempted the account HAS had its turn, and holding the walk open for a TV client
            // that is currently answering "reload page" to everything buys nothing.
            if (authenticated && nextType.isAuthCapable()) {
                authenticatedClientAttempted = true;
            }
            boolean playable = result != null && !result.isUnplayable();
            logPlayerOutcome(videoId, nextType, attempt, result);

            // The account-bearing route is currently broken server-side (see
            // isAuthRouteReloadVerdict). Demote it the same way a media 403 does, so the walk stops
            // spending two guaranteed-dead round trips on the head of every signed-in open.
            // isAuthSupported (TV family), NOT isAuthCapable - deliberately. Two reasons. The
            // "reload page" shape is a TVHTML5 server behaviour; the same shape from an
            // authenticated WEB_EMBED is far more likely to be a genuinely unplayable video, and
            // quarantining on it would demote the account route for the wrong reason. And the
            // quarantine set this feeds is counted against AUTHENTICATED_HEAD.length to decide
            // authenticatedWebFirst, so admitting a non-head client would corrupt that arithmetic.
            if (sPreferNoPotClient && authenticated && nextType.isAuthSupported()) {
                noteAuthRouteVerdict(nextType, videoId, result, authRoute);
            }

            // NEWTUBE(net): a dead link is not a bad client - stop walking the ring.
            //
            // The ring exists to find a client whose RESPONSE is usable. When an attempt produces no
            // HTTP response at all (read timeout, or an IOException surfaced by RetrofitHelper),
            // the client was never the variable, so trying nine more of them cannot help. Measured
            // in a 150s tunnel on the netshape rig: the walk burned its whole 45s budget on
            // timeouts, reloaded, and burned another walk - and because a recovery walk deliberately
            // treats the last winner as suspect, it finally settled on ANDROID_VR (auth=n, sabr=y,
            // 28 formats) instead of the learned-good TV_DOWNGRADED (auth=y, 41 formats). An outage
            // silently cost the user authenticated playback and 13 renditions.
            //
            // Bailing out returns null fast, which is what ErrorFixerController's escalating backoff
            // is built to handle, and leaves the learned client order untouched for the retry.
            // Two CONSECUTIVE no-response attempts, not one: a single client can time out on its own.
            if (noResponse[0]) {
                if (sPreferNoPotClient && ++noResponseStreak >= TRANSPORT_DOWN_STREAK) {
                    transportDown = true;
                    botCheck.markCutShort();
                    mLastWalkTransportDown = true;
                    android.util.Log.w("NetPath", "player-ring transport-down video=" + videoId
                            + " attempts=" + attempt + " streak=" + noResponseStreak
                            + " lastClient=" + nextType);
                    break;
                }
            } else {
                noResponseStreak = 0;
            }

            // Diagnostic only (see sAuthTvSabrOnly). Only the exact SABR-only signature counts —
            // an age/geo-restricted TV verdict must not be reported as "TV is SABR-only".
            if (authenticated && nextType == AppClient.TV && result != null) {
                boolean sabrOnly = !playable && result.getServerAbrStreamingUrl() != null
                        && result.isAdaptiveFormatsBroken();
                if (sabrOnly != sAuthTvSabrOnly) {
                    sAuthTvSabrOnly = sabrOnly;
                    android.util.Log.d("NetPath", "player-ring learn tv-sabr-only="
                            + (sabrOnly ? "y" : "n"));
                }
            }

            if (result != null) {
                boolean repeatedLoginRequired = firstLoginRequired != null
                        && BotCheckDetector.isRepeatedLoginRequired(
                                firstLoginRequired.getRawPlayabilityStatus(),
                                firstLoginRequired.getPlayabilityStatus(),
                                result.getRawPlayabilityStatus(),
                                result.getPlayabilityStatus());
                if (result.isBotCheckRequired() || repeatedLoginRequired) {
                    final String signal =
                            result.isBotCheckRequired() ? "explicit" : "repeated-login";
                    // A challenge answered to a request that carried no account is evidence about
                    // the ANONYMOUS identity, not about this video. Count it so the walk can learn
                    // to stop leading with a partition the network is currently rejecting.
                    if (!result.isAuth()) {
                        noteAnonymousChallenge(++anonChallengeHits);
                    }
                    // A quarantined public route deliberately tries anonymous Web before the
                    // account-bearing fallback. Do not let two Web LOGIN_REQUIRED/challenge
                    // verdicts prevent the later TV client from serving auth-only content.
                    if (authenticated && !authenticatedClientAttempted) {
                        android.util.Log.d("NetPath", "bot-check defer-until-auth-client client="
                                + nextType + " signal=" + signal);
                    } else if (botCheck.recordChallenge(result, nextType, signal, authenticated,
                            visitOrder, visitIndex, sPreferNoPotClient)) {
                        // NEWTUBE(net): a guest challenge is not a verdict on the whole ring.
                        //
                        // The challenge is bound to the WEB client context, not to the visitor: the
                        // ANDROID_VR request that DID serve the video carried the very same
                        // visitorData (visitor=c3027841b6) as the WEB_EMBED request challenged
                        // moments earlier. Every remaining !isWebPotRequired() client is therefore
                        // still worth a round trip. See BotCheckWalkState for the full evidence.
                        android.util.Log.d("NetPath", "bot-check walk-on client=" + nextType
                                + " signal=" + signal + " attempt=" + attempt);
                    } else {
                        tripBotCheckCircuit(result, nextType, signal, authenticated,
                                botCheck.ringExhausted());
                        return result;
                    }
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
                // Something played, so a challenge this walk carried on past is no longer its
                // verdict - and the circuit must not arm behind a successful open.
                botCheck.discardOnPlayable();
                // ...and this video demonstrably plays, so any auth-route no-media verdict the
                // walk collected is about the route rather than about the video. Only now is it
                // evidence (see AuthRouteWalkState).
                for (java.util.Map.Entry<AppClient, AuthRouteWalkState.Held> held
                        : authRoute.onPlayable().entrySet()) {
                    countAuthRouteVerdict(held.getKey(), held.getValue());
                }
                // The anonymous partition just served a video, so whatever guest challenge was
                // remembered has lifted. Drop it immediately rather than sitting out the TTL.
                if (nextType.isWebPotRequired() && !result.isAuth()) {
                    clearAnonChallenge("anon-served");
                }
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

        // A DEADLINE is not a verdict, and nothing learned may come out of it. The three learning
        // sites all live INSIDE the loop and all require a parsed response from a real attempt -
        // the bot-check trip (BotCheckDetector.isRepeatedLoginRequired / isBotCheckRequired), the
        // anonymous-challenge counter, and the tv-sabr-only observation - so breaking out at the
        // top of an iteration cannot reach any of them; the 403 quarantine
        // (markCurrentPlaybackRouteForbidden) is driven by the player, not by this walk, and is
        // likewise untouched. What remains is the RETURN VALUE: a partially-walked
        // `firstUnplayable` is dropped rather than published, because a verdict from an unfinished
        // walk is a claim the walk never established. It would seat that verdict in
        // YouTubeMediaItemService's 30s negative cache (keyed on this videoId) and set
        // mIsUnplayable, which disables switchNextFormat's pot-reset/circuit-break recovery. Null
        // means "could not load", which is what actually happened and what the app's retry stack
        // (ErrorFixerController's escalating budget) is built to handle.
        // Same reasoning for a walk cut short by a dead link (see TRANSPORT_DOWN_STREAK): nothing
        // was established about this video, so publish nothing about it.
        //
        // A CHALLENGE is the exception, and it is checked first: unlike `firstUnplayable` it is a
        // verdict the server actually stated, and it is the most actionable thing the walk learned
        // - the user gets YouTube's own reason instead of a bare "could not load". Arming the
        // suppression circuit is still conditional on the walk having FINISHED (see
        // mBotCheckRingExhausted), so a challenge seen before the clock ran out publishes the
        // reason without silencing the next fifteen minutes of opens. A dead link is excluded
        // outright: when nothing answered, nothing was established about anything.
        BotCheckWalkState.Outcome challenge = botCheck.finish(transportDown);
        if (challenge != null) {
            tripBotCheckCircuit(challenge.result, challenge.client, challenge.signal,
                    challenge.authAttempted, challenge.ringExhausted);
            return challenge.result;
        }

        if ((budgetExhausted || transportDown) && liveWithoutDash == null) {
            return null;
        }

        // Nobody offered a dash manifest for this live stream: the held HLS-only result is
        // still strictly better than an unplayable verdict.
        if (liveWithoutDash != null) {
            android.util.Log.d("NetPath", "player-ring live-no-dash exhausted video=" + videoId
                    + " attempts=" + attempt + " nonCandidatesSkipped=" + liveDashSkipped);
        }
        return liveWithoutDash != null ? liveWithoutDash : firstUnplayable;
    }

    /**
     * Whether {@code client} can still improve a live result that is being held only because it
     * carries no dash manifest (see {@code sPreferDashManifestForLive}). Everything else can only
     * repeat the answer we already have, one round trip at a time.
     *
     * <p>Measured on the Pixel 9 on 2026-09-07 across two 24/7 live streams: every web-family
     * client answered {@code dash=n} and ANDROID_VR answered {@code dash=y} for both, which is
     * exactly what the flag's own comment predicted. Probing the rest cost five extra round trips
     * on 5yx6BWlEVcY (first frame +3220ms against +2472ms for the stream that reached ANDROID_VR
     * sooner) - the flag was documented as costing ONE extra round trip per live open, and stopped
     * doing so once the auth-route quarantine reordered the ring and pushed ANDROID_VR to seventh.
     *
     * <p>The TV family is kept as a candidate on the strength of that comment rather than on
     * evidence: both live streams came back UNPLAYABLE there while the auth route is broken, so
     * this round could not observe it either way.
     */
    static boolean isLiveDashCandidate(AppClient client) {
        return client == AppClient.ANDROID_VR || client.isAuthSupported();
    }


    /**
     * Whether any client AFTER {@code index} can still answer despite the anonymous web identity
     * being challenged. Web-pot clients all share that identity, so they are not candidates; every
     * other client either carries the account or presents a different platform identity. Mirrors
     * the loop's own skip rule, including its authenticated TV_DOWNGRADED carve-out, so the walk
     * never claims a candidate it would then skip.
     */
    static boolean hasUnchallengedClientAfter(List<AppClient> order, int index,
            boolean authenticated) {
        for (int i = index + 1; i < order.size(); i++) {
            AppClient candidate = order.get(i);
            if (candidate.isWebPotRequired()) {
                continue;
            }
            if (isSkippedClient(candidate)
                    && !(authenticated && candidate == AppClient.TV_DOWNGRADED)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * A /player verdict that carries the account, refuses the video and offers NO media at all.
     * Deliberately structural rather than textual: the phone runs in whatever language the user
     * picked, and the reason string is localized (the 2026-09-07 capture is Spanish). Shape alone
     * cannot separate this from a genuinely unplayable video, which is why the caller requires the
     * same shape on {@link #AUTH_RELOAD_QUARANTINE_MIN_HITS} DIFFERENT videos before acting.
     * {@link BotCheckDetector#isReloadPageVerdict} only annotates the log line.
     */
    private static boolean isAuthRouteReloadVerdict(AppClient client, @Nullable VideoInfo result) {
        // isAuthSupported, not isAuthCapable: this rule was written for the TVHTML5 "reload page"
        // outage and must not be applied to an account-bearing WEB_EMBED. See the caller.
        if (result == null || !client.isAuthSupported() || !result.isAuth()
                || !result.isUnplayable()) {
            return false;
        }

        boolean hasAdaptive = result.getAdaptiveFormats() != null
                && !result.getAdaptiveFormats().isEmpty();
        boolean hasRegular = result.getRegularFormats() != null
                && !result.getRegularFormats().isEmpty();
        return !hasAdaptive && !hasRegular
                && result.getDashManifestUrl() == null
                && result.getHlsManifestUrl() == null
                && result.getServerAbrStreamingUrl() == null;
    }

    /**
     * Tracks {@link #isAuthRouteReloadVerdict} per account-bearing client and quarantines the route
     * once the same shape has come back for {@link #AUTH_RELOAD_QUARANTINE_MIN_HITS} different
     * videos. Any other outcome from that client clears its streak, so the route is held down only
     * while it is actually refusing everything.
     */
    private void noteAuthRouteVerdict(AppClient client, String videoId,
            @Nullable VideoInfo result, AuthRouteWalkState authRoute) {
        if (!isAuthRouteReloadVerdict(client, result)) {
            // Direct evidence the route is healthy: it answered this client with something.
            mAuthReloadStreaks.remove(client);
            return;
        }

        boolean reloadPage = BotCheckDetector.isReloadPageVerdict(
                result.getRawPlayabilityStatus(), result.getPlayabilityStatus());
        if (!authRoute.hold(client, videoId, reloadPage)) {
            android.util.Log.d("NetPath", "player-ring auth-route held client=" + client
                    + " video=" + videoId + " reloadPage=" + (reloadPage ? "y" : "n"));
            return;
        }
        countAuthRouteVerdict(client, new AuthRouteWalkState.Held(videoId, reloadPage));
    }

    /**
     * Counts one PROVEN auth-route no-media verdict and quarantines the route once the same shape
     * has come back for {@link #AUTH_RELOAD_QUARANTINE_MIN_HITS} different videos. Proven means a
     * client served the video in the same walk - see {@link AuthRouteWalkState}. Any other outcome
     * from that client clears its streak, so the route is held down only while it is actually
     * refusing videos that demonstrably play.
     */
    private void countAuthRouteVerdict(AppClient client, AuthRouteWalkState.Held held) {
        ReloadStreak streak = mAuthReloadStreaks.get(client);
        if (streak == null) {
            streak = new ReloadStreak();
            mAuthReloadStreaks.put(client, streak);
        }
        // Same video twice (a reload, a retry) is one piece of evidence, not two.
        if (held.videoId.equals(streak.lastVideoId)) {
            return;
        }
        streak.lastVideoId = held.videoId;
        streak.hits++;

        if (streak.hits < AUTH_RELOAD_QUARANTINE_MIN_HITS) {
            android.util.Log.d("NetPath", "player-ring auth-route no-media client=" + client
                    + " hits=" + streak.hits + "/" + AUTH_RELOAD_QUARANTINE_MIN_HITS
                    + " reloadPage=" + (held.reloadPage ? "y" : "n"));
            return;
        }

        mAuthReloadStreaks.remove(client);
        quarantineAuthRoute(client, "no-media-verdict");
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
                // auth = what WE sent. srvAuth = what the SERVER says it saw (logged_in tracking
                // param), or "?" when the response carried none. Only the second can tell an
                // honoured credential from one that was ignored and served anonymously.
                + " auth=" + (result.isAuth() ? "y" : "n")
                + " srvAuth=" + serverAuthFlag(result)
                + " formats=" + adaptive + '+' + regular + " usableAdaptive=" + usableAdaptive
                + " dash=" + (result.getDashManifestUrl() != null ? "y" : "n")
                + " hls=" + (result.getHlsManifestUrl() != null ? "y" : "n")
                + " sabr=" + (result.getServerAbrStreamingUrl() != null ? "y" : "n")
                + " reason=\"" + safeLogValue(result.getPlayabilityStatus(), 160) + "\"");
    }

    /** "y"/"n" from the server's own logged_in tracking param, "?" when it did not send one. */
    private static String serverAuthFlag(VideoInfo result) {
        Boolean loggedIn = result.isServerLoggedIn();
        return loggedIn == null ? "?" : (loggedIn ? "y" : "n");
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
     * Put {@link #PREFERRED_FIRST_CLIENT} at the head of an order, moving it if already present.
     * Called only once the account has stopped being an option for this open, so it never competes
     * with an account-bearing client - it competes with WEB_EMBED, and wins on cost: no PO token.
     */
    static List<AppClient> leadWithTokenFreeClient(List<AppClient> order) {
        if (order.isEmpty() || order.get(0) == PREFERRED_FIRST_CLIENT) {
            return order;
        }

        List<AppClient> result = new java.util.ArrayList<>(order.size() + 1);
        result.add(PREFERRED_FIRST_CLIENT);
        for (AppClient type : order) {
            if (type != PREFERRED_FIRST_CLIENT) {
                result.add(type);
            }
        }
        return result;
    }

    /**
     * NEWTUBE(auth-probe): with WEB_EMBED carrying the account, it is no longer "the guest client
     * we fall through to" - it is the account route, and yt-dlp puts it FIRST for a signed-in
     * extraction ({@code _DEFAULT_AUTHED_CLIENTS[0]}, commit 5d5b634). So on the walk that has
     * given up on the TV head it leads, and {@link #PREFERRED_FIRST_CLIENT} stays right behind it
     * as the immediate safety net if the bearer is refused.
     * <p>
     * Applied only when {@link AppClient#getSWebEmbedAuthEnabled()} is on, so with the flag off
     * the order is byte-identical to before. Deliberately composed AFTER
     * {@link #leadWithTokenFreeClient} rather than replacing it: that keeps VISIONOS at index 1
     * instead of dropping it back down the ring, which is what preserves the measured
     * "0 bot checks, 0 403s" behaviour on the fallback path.
     */
    static List<AppClient> leadWithAuthenticatedWebClient(List<AppClient> order) {
        if (order.isEmpty() || order.get(0) == AppClient.WEB_EMBED
                || !order.contains(AppClient.WEB_EMBED)) {
            return order;
        }

        List<AppClient> result = new java.util.ArrayList<>(order.size());
        result.add(AppClient.WEB_EMBED);
        for (AppClient type : order) {
            if (type != AppClient.WEB_EMBED) {
                result.add(type);
            }
        }
        return result;
    }

    /**
     * Slots {@link #PREFERRED_FIRST_CLIENT} in immediately BEFORE the first web-pot client, leaving
     * everything ahead of it untouched. Used on a normal signed-in walk, where the account head
     * keeps attempts 1-2 and this only decides which client the walk falls through to.
     * <p>
     * Why it belongs there: a signed-in open that falls through the TV head used to spend its first
     * anonymous attempt on WEB_EMBED, which mints a PO token before it can even ask and answers
     * from the guest identity the network is most likely to be challenging - the exact request that
     * produced "Inicia sesión para confirmar que no eres un bot" on 2026-09-07. VISIONOS mints
     * nothing, needs no JS player, and is yt-dlp's own {@code _DEFAULT_CLIENTS[0]}. It costs one
     * round trip on the videos it cannot serve (made for kids) and then falls through to WEB_EMBED
     * exactly as before. The account head is NOT displaced: this never inserts ahead of it.
     */
    static List<AppClient> insertTokenFreeClientBeforeWebPot(List<AppClient> order) {
        if (order.contains(PREFERRED_FIRST_CLIENT)) {
            return order;
        }

        int insertAt = -1;
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i).isWebPotRequired()) {
                insertAt = i;
                break;
            }
        }
        if (insertAt < 0) {
            return order;
        }

        List<AppClient> result = new java.util.ArrayList<>(order.size() + 1);
        result.addAll(order.subList(0, insertAt));
        result.add(PREFERRED_FIRST_CLIENT);
        result.addAll(order.subList(insertAt, order.size()));
        return result;
    }

    /**
     * Puts the account-bearing head ({@link #AUTHENTICATED_HEAD}) in front of the rest of the ring.
     * A head client currently 403-quarantined on this network is DEMOTED to the back of the head
     * rather than dropped: a quarantined account route is still a better bet than an anonymous
     * client that cannot carry the account at all, and if it 403s again it simply re-quarantines.
     * Kept pure so the ordering can be unit-tested without network calls.
     */
    static List<AppClient> promoteAuthenticatedTvFallback(List<AppClient> rawOrder,
            @Nullable java.util.Set<AppClient> forbidden) {
        java.util.List<AppClient> head = new java.util.ArrayList<>(AUTHENTICATED_HEAD.length);
        java.util.List<AppClient> demoted = new java.util.ArrayList<>(AUTHENTICATED_HEAD.length);
        for (AppClient client : AUTHENTICATED_HEAD) {
            if (forbidden != null && forbidden.contains(client)) {
                demoted.add(client);
            } else {
                head.add(client);
            }
        }

        java.util.List<AppClient> result =
                new java.util.ArrayList<>(rawOrder.size() + AUTHENTICATED_HEAD.length);
        result.addAll(head);
        result.addAll(demoted);
        for (AppClient client : rawOrder) {
            if (!Helpers.equalsAny(client, (Object[]) AUTHENTICATED_HEAD)) {
                result.add(client);
            }
        }
        return result;
    }

    /**
     * While the anonymous partition is bot-challenged on this network every web-pot probe is a
     * guaranteed-dead round trip (42/42 measured on Telefonica cellular, 2026-07-27). Keep those
     * clients in the ring — the challenge expires, and some videos only a Web client can serve —
     * but stable-move them behind every client that might still answer. Stable so the relative
     * order inside each partition (and therefore ring memory) is untouched.
     */
    static List<AppClient> deprioritizeWebPotClients(List<AppClient> order) {
        java.util.List<AppClient> rest = new java.util.ArrayList<>(order.size());
        java.util.List<AppClient> webPot = new java.util.ArrayList<>();
        for (AppClient client : order) {
            if (client.isWebPotRequired()) {
                webPot.add(client);
            } else {
                rest.add(client);
            }
        }
        rest.addAll(webPot);
        return rest;
    }

    /**
     * Applies the signed-in ordering policy without erasing an error-recovery cursor. Kept pure so
     * a regression that silently re-promotes the failed TV client can be covered by unit tests.
     */
    static List<AppClient> buildRequestVisitOrder(AppClient beginType,
            @Nullable AppClient lastWinner, boolean preferWebFamily, boolean recoveryWalk,
            boolean authenticated, @Nullable java.util.Set<AppClient> forbiddenAuthClients) {
        return buildRequestVisitOrder(beginType, lastWinner, preferWebFamily, recoveryWalk,
                authenticated, forbiddenAuthClients, false, false);
    }

    static List<AppClient> buildRequestVisitOrder(AppClient beginType,
            @Nullable AppClient lastWinner, boolean preferWebFamily, boolean recoveryWalk,
            boolean authenticated, @Nullable java.util.Set<AppClient> forbiddenAuthClients,
            boolean authenticatedWebFirst, boolean anonChallenged) {
        List<AppClient> order = buildVisitOrder(
                beginType, lastWinner,
                preferWebFamily && (!authenticated || recoveryWalk || authenticatedWebFirst),
                recoveryWalk);
        if (authenticated && !recoveryWalk && !authenticatedWebFirst) {
            order = promoteAuthenticatedTvFallback(order, forbiddenAuthClients);
            // Mobile only: preferWebFamily is sPreferAttestedWebFallback, which TV never sets.
            if (preferWebFamily) {
                order = insertTokenFreeClientBeforeWebPot(order);
            }
        }
        // An authenticated walk that has fallen through to the anonymous partition should spend
        // its FIRST anonymous attempt on the client that mints nothing. Until now that attempt was
        // WEB_EMBED, which generates a PO token before it can even ask - on the exact path taken
        // after a media 403, when the open is already slow. The token-free head costs one round
        // trip when it cannot serve (made-for-kids) and then falls through to WEB_EMBED as before.
        // It is off-ring, so it is absent from an authenticated order and has to be injected here.
        // Safe against the anon-challenge detector: that needs the SAME reason string from two
        // clients (BotCheckDetector.isRepeatedLoginRequired), and an age gate changes outcome on
        // the embedded client - so this adds a third independent identity, not a false hit.
        if (authenticated && (recoveryWalk || authenticatedWebFirst)) {
            order = leadWithTokenFreeClient(order);
            // ...unless WEB_EMBED is carrying the account, in which case it is the account route
            // and goes in front of the token-free client. Off by default; see
            // leadWithAuthenticatedWebClient.
            if (authenticatedWebFirst && AppClient.isWebEmbedAuthEnabled()) {
                order = leadWithAuthenticatedWebClient(order);
            }
        }
        // Applied LAST, so it also overrides an attested-web-first or authenticated-web-first
        // preference: if the account head is exhausted AND the guest identity is challenged, the
        // non-web platform clients are all that is left worth spending a round trip on.
        return anonChallenged ? deprioritizeWebPotClients(order) : order;
    }

    /**
     * Called only after the player surfaced an actual HTTP 403. It remembers a failed
     * account-bearing route against the current Android default network, without persisting it
     * across processes or leaking any network identifiers beyond the in-memory framework hash.
     */
    public void markCurrentPlaybackRouteForbidden() {
        AppClient failedClient = mActualInfoType;
        // isAuthSupported, not isAuthCapable: the set this writes is counted against
        // AUTHENTICATED_HEAD.length to decide authenticatedWebFirst, so it must only ever contain
        // head clients. A 403 on an account-bearing WEB_EMBED is still handled - by the ordinary
        // recovery walk, which defers the last winner - it just does not quarantine the TV head.
        if (!hasAuthentication() || failedClient == null || !failedClient.isAuthSupported()) {
            return;
        }
        quarantineAuthRoute(failedClient, "media-403");
    }

    /**
     * Demotes one account-bearing client on the ACTIVE network for
     * {@link #AUTH_ROUTE_FORBIDDEN_COOLDOWN_MS}. Shared by the media-403 evidence path and the
     * no-media-verdict path so both keep identical network keying and TTL semantics.
     */
    private void quarantineAuthRoute(AppClient failedClient, String reason) {
        String network = activeNetworkKey();
        if (network == null) {
            return;
        }
        // A quarantine only means anything against the network it was observed on; moving networks
        // starts a clean slate rather than carrying a stale verdict across.
        if (!network.equals(mAuthRouteForbiddenNetwork)) {
            mAuthRouteForbiddenUntilMs.clear();
            mAuthRouteForbiddenNetwork = network;
        }
        mAuthRouteForbiddenUntilMs.put(failedClient,
                android.os.SystemClock.elapsedRealtime() + AUTH_ROUTE_FORBIDDEN_COOLDOWN_MS);
        android.util.Log.w("NetPath", "player-ring quarantine-auth-route client=" + failedClient
                + " reason=" + reason
                + " network=" + network + " cooldownMs=" + AUTH_ROUTE_FORBIDDEN_COOLDOWN_MS
                + " quarantined=" + mAuthRouteForbiddenUntilMs.size()
                + "/" + AUTHENTICATED_HEAD.length);
    }

    /**
     * Account-bearing clients currently 403-quarantined on the ACTIVE network. Never null; expired
     * entries are dropped as they are seen, and a network change wipes the whole set.
     */
    private java.util.Set<AppClient> forbiddenAuthClients() {
        if (mAuthRouteForbiddenUntilMs.isEmpty()) {
            return java.util.Collections.emptySet();
        }

        String currentNetwork = activeNetworkKey();
        if (currentNetwork == null || !currentNetwork.equals(mAuthRouteForbiddenNetwork)) {
            android.util.Log.d("NetPath", "player-ring clear-auth-route-quarantine reason=network-change"
                    + " old=" + mAuthRouteForbiddenNetwork + " new=" + currentNetwork);
            clearAuthenticatedRouteQuarantine();
            return java.util.Collections.emptySet();
        }

        long now = android.os.SystemClock.elapsedRealtime();
        java.util.Set<AppClient> result = new java.util.HashSet<>();
        for (java.util.Map.Entry<AppClient, Long> entry : mAuthRouteForbiddenUntilMs.entrySet()) {
            if (entry.getValue() - now > 0) {
                result.add(entry.getKey());
            } else {
                mAuthRouteForbiddenUntilMs.remove(entry.getKey());
            }
        }
        return result;
    }

    private void clearAuthenticatedRouteQuarantine() {
        mAuthRouteForbiddenUntilMs.clear();
        mAuthRouteForbiddenNetwork = null;
    }

    /**
     * Records that an anonymous /player attempt came back bot-challenged. Only fires once
     * {@link #ANON_CHALLENGE_MIN_HITS} different anonymous identities have been rejected inside the
     * same walk, so a genuine single-video login gate cannot deprioritize the whole partition.
     */
    private void noteAnonymousChallenge(int hits) {
        if (hits < ANON_CHALLENGE_MIN_HITS) {
            return;
        }

        String network = activeNetworkKey();
        if (network == null) {
            return;
        }

        long now = android.os.SystemClock.elapsedRealtime();
        boolean fresh = !network.equals(mAnonChallengeNetwork) || mAnonChallengeUntilMs - now <= 0;
        mAnonChallengeNetwork = network;
        mAnonChallengeUntilMs = now + ANON_CHALLENGE_COOLDOWN_MS;
        if (fresh) {
            // The challenged guest identity is the app's own long-lived visitor, and re-minting a
            // pot for it changes nothing (measured: every rejected call already carried a valid
            // pot). Abandoning the identity is the only lever the client actually has.
            boolean rotated = PoTokenGate.rotateWebVisitor();
            android.util.Log.w("NetPath", "player-ring anon-challenged network=" + network
                    + " hits=" + hits + " cooldownMs=" + ANON_CHALLENGE_COOLDOWN_MS
                    + " visitorRotated=" + (rotated ? "y" : "n"));
        }
    }

    private boolean isAnonPartitionChallenged() {
        if (mAnonChallengeUntilMs - android.os.SystemClock.elapsedRealtime() <= 0) {
            return false;
        }

        String currentNetwork = activeNetworkKey();
        if (currentNetwork == null || !currentNetwork.equals(mAnonChallengeNetwork)) {
            clearAnonChallenge("network-change");
            return false;
        }
        return true;
    }

    private void clearAnonChallenge(String reason) {
        if (mAnonChallengeNetwork == null && mAnonChallengeUntilMs == 0) {
            return;
        }
        android.util.Log.d("NetPath", "player-ring clear-anon-challenge reason=" + reason);
        mAnonChallengeUntilMs = 0;
        mAnonChallengeNetwork = null;
    }

    @Nullable
    private static String activeNetworkKey() {
        try {
            Context context = AppService.instance().getContext();
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager != null ? manager.getActiveNetwork() : null;
            NetworkCapabilities caps = network != null
                    ? manager.getNetworkCapabilities(network) : null;
            if (network == null || caps == null) {
                return null;
            }
            String transport = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cell"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                    : "other";
            return transport + ':' + network.hashCode();
        } catch (RuntimeException e) {
            return null;
        }
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

        // NEWTUBE(net): suppression has to be earned, and it has to expire on YouTube's clock.
        //
        // This gate used to answer EVERY open for fifteen minutes with the stored challenge, which
        // is what turned one guest-session throttle on one client into "the app is banned" - the
        // 2026-09-07 Rusowsky capture tripped at attempt 3 of 10 and then refused videos that
        // ANDROID_VR was serving normally minutes later. Two conditions now apply, both mobile-only
        // so the historical TV behavior above is untouched:
        //   1. the walk that armed the circuit must have REACHED THE END of the ring, and
        //   2. one open per BOT_CHECK_PROBE_INTERVAL_MS is let through to re-test the server.
        // Cost on a healthy path is zero: the circuit is only consulted while it is armed.
        if (sPreferNoPotClient) {
            if (!mBotCheckRingExhausted) {
                android.util.Log.d("NetPath", "bot-check bypass=partial-walk video=" + videoId);
                return null;
            }

            long nowMs = android.os.SystemClock.elapsedRealtime();
            if (nowMs - mBotCheckNextProbeAtMs >= 0) {
                mBotCheckNextProbeAtMs = nowMs + BOT_CHECK_PROBE_INTERVAL_MS;
                android.util.Log.d("NetPath", "bot-check probe video=" + videoId
                        + " remainingMs=" + remainingMs);
                return null;
            }
        }

        android.util.Log.w("NetPath", "bot-check cooldown video=" + videoId
                + " remainingMs=" + remainingMs + " network=n");
        return result;
    }

    private void tripBotCheckCircuit(VideoInfo result, AppClient client, String signal,
            boolean authenticatedAttempted, boolean ringExhausted) {
        result.setBotCheckRequired(true);
        mBotCheckResult = result;
        mBotCheckCooldownUntilMs = android.os.SystemClock.elapsedRealtime() + BOT_CHECK_COOLDOWN_MS;
        mBotCheckAuthenticatedAttempted = authenticatedAttempted;
        mBotCheckRingExhausted = ringExhausted;
        mBotCheckNextProbeAtMs =
                android.os.SystemClock.elapsedRealtime() + BOT_CHECK_PROBE_INTERVAL_MS;
        mNextInfoType = null;
        mRecoveryWalk = false;
        android.util.Log.w("NetPath", "bot-check trip client=" + client
                + " signal=" + signal + " authAttempted=" + (authenticatedAttempted ? "y" : "n")
                + " ringExhausted=" + (ringExhausted ? "y" : "n")
                + " cooldownMs=" + BOT_CHECK_COOLDOWN_MS);
    }

    private void clearBotCheckCircuit() {
        mBotCheckResult = null;
        mBotCheckCooldownUntilMs = 0;
        mBotCheckAuthenticatedAttempted = false;
        mBotCheckRingExhausted = false;
        mBotCheckNextProbeAtMs = 0;
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

        // The fast head may sit OUTSIDE the ring: VIDEO_INFO_TYPE_LIST is upstream's and stays
        // untouched, while PREFERRED_FIRST_CLIENT is ours. Helpers.getNextValue returns element 0
        // for a value it cannot find, so a lap started from an off-ring begin never satisfies this
        // loop's `type != beginType` stop condition - it spins forever. Anchor the lap at element 0
        // and visit that anchor explicitly, which gives an off-ring head the whole canonical ring
        // as its tail.
        final boolean beginInRing = Arrays.asList(VIDEO_INFO_TYPE_LIST).contains(beginType);
        final AppClient anchor = beginInRing ? beginType : VIDEO_INFO_TYPE_LIST[0];
        if (!beginInRing && (deferLastWinner || anchor != lastWinner)) {
            rawOrder.add(anchor);
        }

        for (AppClient type = Helpers.getNextValue(VIDEO_INFO_TYPE_LIST, anchor); type != anchor;
                type = Helpers.getNextValue(VIDEO_INFO_TYPE_LIST, type)) {
            if (deferLastWinner || type != lastWinner) {
                rawOrder.add(type);
            }
        }

        if (!preferWebFamily) {
            return rawOrder;
        }

        // Whatever FAST client the walk chose to begin with keeps attempt 1; only its tail is
        // partitioned. Keyed off beginType rather than PREFERRED_FIRST_CLIENT so a restored
        // previous-session winner is honoured too: with the preferred head now sitting off-ring,
        // pinning this to the constant would have demoted every other fast begin behind the Web
        // family, turning the first cold start after an upgrade into a pot-minting WEB open.
        // Anonymous-walk only - buildRequestVisitOrder passes preferWebFamily=false while
        // authenticated, so the account head never reaches here.
        final AppClient fastHead = !recoveryWalk && !beginType.isWebPotRequired() ? beginType : null;
        boolean keepVrFastHead = fastHead != null;
        java.util.List<AppClient> result = new java.util.ArrayList<>();
        if (keepVrFastHead || recoveryWalk) {
            if (keepVrFastHead) {
                result.add(fastHead);
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
                if (!type.isWebPotRequired() && (fastHead == null || type != fastHead)) {
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
     * Per-attempt timeout for {@code client}: the authenticated head is given a cold-start budget,
     * web-pot clients an even larger one that covers a cold BotGuard mint, and every other fast
     * client keeps the short speculative one. See {@link #AUTH_HEAD_ATTEMPT_TIMEOUT_MS} and
     * {@link #WEB_POT_ATTEMPT_TIMEOUT_MS}.
     */
    static long attemptTimeoutMsFor(AppClient client) {
        for (AppClient head : AUTHENTICATED_HEAD) {
            if (head == client) {
                return AUTH_HEAD_ATTEMPT_TIMEOUT_MS;
            }
        }

        if (client.isWebPotRequired()) {
            return WEB_POT_ATTEMPT_TIMEOUT_MS;
        }

        return CLIENT_ATTEMPT_TIMEOUT_MS;
    }

    /**
     * Mobile-only guard: run every client attempt with a per-client deadline so a hanging client
     * fails over instead of blocking until OkHttp gives up (and a PO-token mint, which OkHttp does
     * not bound at all, cannot stall the walk indefinitely). Web-pot clients used to be exempted
     * here entirely; they now get {@link #WEB_POT_ATTEMPT_TIMEOUT_MS}, a budget sized for a cold
     * BotGuard mint, and the authenticated head keeps its own - see {@link #attemptTimeoutMsFor}.
     * The effective budget is additionally clamped to what is left of the walk's overall
     * {@link #RING_WALK_BUDGET_MS}. TV (flag unset) runs unbounded exactly as before.
     */
    private VideoInfo getVideoInfoWithTimeout(AppClient client, String videoId,
            String clickTrackingParams, @Nullable CancellationSignal cancellationSignal,
            long remainingWalkBudgetMs, boolean[] noResponseOut) {
        if (abortCanceledRequest(videoId, "pre-attempt", cancellationSignal)) {
            return null;
        }

        if (!sPreferNoPotClient) {
            return getVideoInfoWithRentFix(client, videoId, clickTrackingParams);
        }

        Future<VideoInfo> future = getInfoExecutor().submit(() -> getVideoInfoWithRentFix(client, videoId, clickTrackingParams));
        final long attemptTimeoutMs = Math.max(1,
                Math.min(attemptTimeoutMsFor(client), remainingWalkBudgetMs));
        final long deadlineNs = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(attemptTimeoutMs);

        while (true) {
            if (abortCanceledRequest(videoId, "player-wait", cancellationSignal)) {
                future.cancel(true);
                return null;
            }

            long remainingNs = deadlineNs - System.nanoTime();
            if (remainingNs <= 0) {
                Log.e(TAG, "getVideoInfo timed out for client %s after %s ms, failing over...",
                        client, attemptTimeoutMs);
                android.util.Log.w("NetPath", "player-ring attempt-timeout client=" + client
                        + " video=" + videoId + " ms=" + attemptTimeoutMs
                        + " clamped=" + (attemptTimeoutMs < attemptTimeoutMsFor(client) ? "y" : "n"));
                future.cancel(true);
                markNoResponse(noResponseOut); // never got a reply at all - see TRANSPORT_DOWN_STREAK
                return null;
            }

            try {
                long waitMs = Math.min(
                        Math.max(TimeUnit.NANOSECONDS.toMillis(remainingNs), 1), 100);
                return future.get(waitMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Poll the cancellation signal without shortening the existing overall timeout.
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                android.util.Log.d("NetPath", "player-request canceled video=" + videoId
                        + " stage=player-wait client=" + client);
                return null;
            } catch (Exception e) {
                Log.e(TAG, "getVideoInfo failed for client %s (%s), failing over...",
                        client, e.getMessage());
                if (isTransportFailure(e)) {
                    markNoResponse(noResponseOut);
                }
                return null;
            }
        }
    }

    /** Records that this attempt never produced an HTTP response. See TRANSPORT_DOWN_STREAK. */
    private static void markNoResponse(boolean[] noResponseOut) {
        if (noResponseOut != null && noResponseOut.length > 0) {
            noResponseOut[0] = true;
        }
    }

    /**
     * True when the failure came from the transport rather than from YouTube's answer.
     *
     * <p>An {@link IOException} anywhere in the cause chain means the request never completed:
     * RetrofitHelper.getResponse rethrows those as IllegalStateException specifically to "notify
     * caller about network condition", and the executor wraps that again in an ExecutionException.
     * An HTTP error status is NOT an IOException, so 403s and friends still count as real answers
     * from a client and keep advancing the ring exactly as before.</p>
     */
    private static boolean isTransportFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break; // self-referential chain
            }
        }
        return false;
    }

    private static ExecutorService getInfoExecutor() {
        ExecutorService result = sInfoExecutor;
        if (result == null) {
            synchronized (VideoInfoService.class) {
                result = sInfoExecutor;
                if (result == null) {
                    result = Executors.newCachedThreadPool(r -> {
                        Thread t = new Thread(r, "VideoInfoAttempt");
                        t.setDaemon(true);
                        return t;
                    });
                    sInfoExecutor = result;
                }
            }
        }

        return result;
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
        if (!mIsUnplayable && (mActualInfoType == AppClient.ANDROID_VR
                || mActualInfoType == PREFERRED_FIRST_CLIENT)) {
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
        boolean auth = client.isAuthCapable() && mAuthBlock;

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

        return RetrofitHelper.get(wrapper, client.isAuthCapable() && mAuthBlock);
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
        if (shouldObtainExtendedFormats(result) || needStoryboardFix(result)) {
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
     * WEB and IOS are not auth-CAPABLE (see AppClient.isAuthCapable - and the WEB_EMBED auth gate
     * is deliberately scoped to that one client, so it does not reach WEB here), so these fetches
     * run unauthenticated regardless of mAuthBlock - the worker never touches that shared field.
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
        final boolean needExtended = shouldObtainExtendedFormats(result) || needStoryboardFix(result);

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

    /** Storyboard refetch trigger; gated on mobile (see setSkipStoryboardEnrichment). */
    private static boolean needStoryboardFix(VideoInfo result) {
        return !sSkipStoryboardEnrichment && result.isStoryboardBroken();
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
