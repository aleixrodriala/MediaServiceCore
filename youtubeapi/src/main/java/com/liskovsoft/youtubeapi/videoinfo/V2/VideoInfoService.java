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
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.KidsChannelMemory;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PhoneSourcePlanner;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSourceCatalog;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.SourceWinnerHint;
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
    // When enabled by the mobile flavor, getVideoInfo asks the sources in PhoneSourcePlanner's
    // order (netbench LANES.md), led by a no-PO-token / no-cipher client (PREFERRED_FIRST_CLIENT).
    // TV builds never enable this flag, so they keep the WEB_EMBED-first order and unbounded
    // (no-timeout) behaviour byte-for-byte.
    private static volatile boolean sAccountRouteFirst;
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
    /** See isChallengeConfirmed. */
    private static final String SIGNAL_REPEATED_LOGIN = "repeated-login";
    private static final long REPEATED_LOGIN_CONFIRM_MS = TimeUnit.MINUTES.toMillis(10);
    /**
     * DIFFERENT videos that must answer an account-bearing client with a no-media verdict before
     * that route is quarantined (see {@link #isAuthRouteReloadVerdict}). One is meaningless - a
     * private, deleted, age-gated or geo-blocked video is genuinely UNPLAYABLE with no formats, and
     * demoting the account for it would cost the user authenticated playback on the NEXT video. The
     * same shape on two different videoIds cannot be a per-video verdict; it is the route.
     * <p>
     * NEWTUBE(auth-route): the streak itself now lives in {@link AuthRouteQuarantineBook} (per
     * transport, persisted, bounded in age) so that cold opens add up; a client with a remembered
     * strike is on probation and needs only one. See {@link #countAuthRouteVerdict}.
     */
    private static final int AUTH_RELOAD_QUARANTINE_MIN_HITS =
            AuthRouteQuarantineBook.NO_MEDIA_MIN_HITS;
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
    /*
     * A real media 403 from an authenticated TV-family URL is route evidence, not an invitation to
     * select the same route for every next video. Held per client and per TRANSPORT (see
     * AuthRouteQuarantineBook): the quarantined client is demoted behind its healthy account-bearing
     * sibling - on the phone behind the token-free client too, see
     * demoteQuarantinedHeadBehindTokenFreeClient - and only when EVERY client in
     * AUTHENTICATED_HEAD is quarantined does the walk give up on the account and lead with the
     * token-free client and the attested Web partition.
     *
     * NEWTUBE(auth-route): the cooldown was a fixed 10 minutes keyed on the Network handle. It now
     * escalates per re-quarantine (10 min x 4^(strikes-1), capped at 24 h, strikes forgotten after
     * 48 h without a quarantine) and is keyed on the transport, so neither the expiry nor a
     * reconnect makes every signed-in user re-pay the dead TVHTML5 probe (HANDOFF section 26: ~2.5 s
     * of first frame on 2-3 consecutive opens). Policy, clocks and rationale live in
     * AuthRouteQuarantineBook; persistence in AuthRouteQuarantineSnapshot.
     */
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
    // Learned per-process: an authenticated TV /player response was SABR-only (every adaptive
    // format broken + serverAbrStreamingUrl present). This NO LONGER drives ordering — TV_DOWNGRADED
    // is now unconditionally the signed-in head (see AUTHENTICATED_HEAD), which subsumes the swap
    // this flag used to perform. Kept purely as an observation logged once per transition, because
    // "TV went SABR-only" is a useful marker when reading a session's NetPath trace.
    private static volatile boolean sAuthTvSabrOnly;
    // Rotate the web-pot session's visitor when the guest partition is challenged (see
    // rotateAnonymousIdentity; the app's persistent visitor never rotates). Off on TV AND on the
    // phone since 2026-09-25 (rotation did not rescue the walls we saw and cost identity): both
    // keep recording the cooldown against the identity they have.
    private static volatile boolean sRotateVisitorOnAnonChallenge;
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
        applyPlayerJsGate();
    }

    // See setPlayerJsGateEnabled. Only the phone path reads it (sPreferNoPotClient).
    private static volatile boolean sPlayerJsGate = true;

    /**
     * NEWTUBE(player-js-gate): on the phone, a source whose answers need no signature/n solve
     * (PlayerJsGate: VISIONOS, ANDROID_VR) sends its /player as soon as a new player's JS is read,
     * instead of after that player's V8 validation (~3 s on the emulator); the validation runs in
     * the background and everything that deciphers still waits for it. On by default with the
     * phone path. false restores waiting for the whole validation on every request: the rollback
     * for debug and benchmark builds (debug.arc.player_js_gate=0). Never called on TV, where the
     * gate is off anyway.
     */
    public static void setPlayerJsGateEnabled(boolean enabled) {
        sPlayerJsGate = enabled;
        applyPlayerJsGate();
    }

    /** Pushes the phone gate down to the extractor build and the format transform. */
    private static void applyPlayerJsGate() {
        boolean active = sPreferNoPotClient && sPlayerJsGate;
        AppService.setPlayerJsReadAhead(active);
        VideoInfoServiceBase.setSkipSolveWithoutChallenges(active);
    }

    /** NEWTUBE(player-js-gate): whether {@code client}'s /player may skip the wait. See PlayerJsGate. */
    static boolean skipsPlayerJsValidation(AppClient client) {
        return PlayerJsGate.skipsValidation(client, sPreferNoPotClient, sPlayerJsGate);
    }

    /**
     * NEWTUBE(planner): signed in, the account route (TV_TIZEN) first instead of second. A debug and
     * benchmark A/B switch (netbench LANES.md section 2.1), off by default.
     */
    public static void setAccountRouteFirst(boolean first) {
        sAccountRouteFirst = first;
    }

    // NEWTUBE(kids-channel): see setKidsChannelHintEnabled. Process-wide like the switches (the
    // service is a singleton), so the app can name a channel without building the service.
    private static volatile boolean sKidsChannelHint = true;
    private static final KidsChannelMemory sKidsChannels = new KidsChannelMemory();

    /**
     * NEWTUBE(kids-channel): on the phone, a channel whose video VISIONOS (or ANDROID_VR) refused
     * on its content and the account route (TV_TIZEN) served is remembered for the process
     * (KidsChannelMemory), and the next open of a video of that channel - when the app named its
     * channel, {@link #noteVideoChannel} - asks TV_TIZEN first: anonymously signed out, with the
     * account signed in. A made-for-kids open is then one request instead of two (~0.25 s of first
     * frame on the Pixel 9). A benched account route, a recovery walk, a bot wall (signed out, its
     * suspicion too) and a forced client ignore it; an answer other than a serve from the hinted
     * TV_TIZEN drops the channel and the walk goes on in the lane's order. On by default; only the phone's planned walk reads
     * it. false = today's order for every open and nothing remembered: the rollback for debug and
     * benchmark builds (debug.arc.kids_channel=0).
     */
    public static void setKidsChannelHintEnabled(boolean enabled) {
        sKidsChannelHint = enabled;
    }

    /**
     * NEWTUBE(kids-channel): {@code videoId} belongs to {@code channelId}, as the app knows it -
     * the card tapped or the next video before the open, /next after it. Before the walk it is the
     * walk's hint; after, it names the channel of a proof whose /player answers carried none. The
     * app does not name live or upcoming videos (TV_TIZEN is never a live route). No request; any
     * thread.
     */
    public static void noteVideoChannel(@Nullable String videoId, @Nullable String channelId) {
        if (!sKidsChannelHint || !sPreferNoPotClient || videoId == null || channelId == null
                || channelId.isEmpty()) {
            return;
        }
        // Once per video and channel: the tap, the player and /next name the same one.
        boolean named = channelId.equals(sKidsChannels.channelOf(videoId));
        PhoneSourcePlanner.Lane lane = sKidsChannels.noteVideoChannel(videoId, channelId);
        if (!named) {
            android.util.Log.d("NetPath", "kids-channel named video=" + videoId
                    + " channel=" + KidsChannelMemory.tag(channelId));
        }
        if (lane != null) {
            android.util.Log.d("NetPath", "kids-channel remember channel="
                    + KidsChannelMemory.tag(channelId) + " video=" + videoId + " lane=" + laneName(lane)
                    + " src=named-later hintsLeft=" + KidsChannelMemory.HINTS_PER_PROOF);
        }
    }

    /** The process's kids-channel memory (tests reset it). */
    static KidsChannelMemory kidsChannels() {
        return sKidsChannels;
    }

    private static String laneName(PhoneSourcePlanner.Lane lane) {
        return lane == PhoneSourcePlanner.Lane.SIGNED_IN ? "signed-in" : "signed-out";
    }

    /**
     * Lets a fresh bot challenge on the anonymous partition start a NEW web-pot session visitor
     * instead of only starting its cooldown. Not called by either flavor since 2026-09-25 - see
     * {@link #rotateAnonymousIdentity()} for the evidence and what would justify turning it on.
     */
    public static void setRotateVisitorOnAnonChallenge(boolean rotate) {
        sRotateVisitorOnAnonChallenge = rotate;
    }

    /** Any sign the answer is about a live stream, current, ended or scheduled. */
    static boolean hasLiveSignal(VideoInfo result) {
        return result.isLive() || result.getStartTimestamp() != null
                || result.getVideoDetails() != null && result.getVideoDetails().isLiveContent();
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

    /**
     * Debug-playground hook. The mobile app calls this only from a debug or benchmark build.
     * NEWTUBE(bench): any AppClient can be forced, not only ring members - the in-app source
     * benchmark measures clients the ring does not use yet (TV_SIMPLY, WEB_MUSIC, ANDROID, ...).
     */
    public static boolean setDebugForcedClient(@Nullable String clientName) {
        if (clientName == null || clientName.trim().isEmpty()) {
            sDebugForcedClient = null;
            return true;
        }
        try {
            sDebugForcedClient = AppClient.valueOf(clientName.trim().toUpperCase(java.util.Locale.US));
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
     * Debug playground, mobile-only, OFF by default: let WEB_EMBED carry the account on /player,
     * the way yt-dlp's signed-in client list does; it keeps its place in the walk. AppClient
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

    /**
     * NEWTUBE(v8-memo): the retained solver runtime keeps each player's evaluated n/sig functions,
     * so a solve stops re-reading and re-evaluating the ~3.7 MB player (206-240 ms per TV_TIZEN /
     * WEB_EMBED / MWEB answer on the Pixel 9). On by default, behind a per-player cross-check
     * against the full path. false restores re-evaluating on every solve: the rollback for debug and
     * benchmark builds (debug.arc.v8_memo=0). Only matters with setKeepSigRuntimeAlive(true).
     */
    public static void setSigSolverMemoEnabled(boolean enabled) {
        V8ChallengeProvider.setPlayerMemoEnabled(enabled);
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

    /**
     * Enabled once from the mobile flavor (MobileMainApplication). Never called on TV. Skips the
     * blocking googlevideo dash-info probe of a live open when the response already carries a DASH
     * or HLS manifest URL. See {@link VideoInfoServiceBase#setSkipLiveDashInfoWithManifest}.
     */
    public static void setSkipLiveDashInfoWithManifest(boolean skip) {
        VideoInfoServiceBase.setSkipLiveDashInfoWithManifest(skip);
    }

    @Nullable
    private volatile AppClient mActualInfoType = null;
    @Nullable
    private volatile AppClient mNextInfoType = null;
    // Set with mNextInfoType by an error-driven cursor (nextVideoInfoType): only such a cursor
    // invokes recovery ordering and defers the previous winner.
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

    /**
     * NEWTUBE(walk-role): who a walk is for. The routing state here - the recovery cursor, the
     * current client that recovery blames, the cold-start hint, the "was it playable" flag, the bot
     * check's one probe per interval, the transport-down memory - belongs to the video the user is
     * watching. A SPECULATIVE walk (the next-video preload, a touch preload, the session warmup)
     * warms the caches and records its video's winner, and leaves all of that alone. Evidence about
     * the network itself (a bot wall, a challenge, the account route) counts whoever saw it.
     */
    public enum WalkRole { ACTIVE, SPECULATIVE }

    /** The role of the walk in progress; walks are serialized by this service's monitor. */
    private WalkRole mWalkRole = WalkRole.ACTIVE;
    private boolean mAuthBlock;
    private volatile long mBotCheckCooldownUntilMs;
    // NEWTUBE(classification): the last video whose walk ended on the repeated sign-in request
    // alone, and when (see isChallengeConfirmed).
    @Nullable
    private String mRepeatedLoginVideo;
    private long mRepeatedLoginAtMs;
    private volatile boolean mBotCheckAuthenticatedAttempted;
    // Whether the walk that armed the circuit actually reached the END of the ring. Suppressing
    // later opens is only defensible once every client has been asked and every one refused; a
    // challenge seen at attempt 3 of 10 (the 2026-09-07 Rusowsky walk) establishes nothing about
    // the seven clients that were never tried.
    private volatile boolean mBotCheckRingExhausted;
    private volatile long mBotCheckNextProbeAtMs;
    @Nullable
    private volatile VideoInfo mBotCheckResult;
    // NEWTUBE(botwall): the attachment the circuit was armed on (mobile). The challenge was observed
    // for the anonymous clients in THAT network context, so moving from a walled LTE to a healthy
    // Wi-Fi must not keep answering opens with the LTE verdict until the next permitted probe.
    @Nullable
    private volatile String mBotCheckNetwork;
    // NEWTUBE(botwall): this getVideoInfo was let through as the circuit's probe; the allowance is
    // spent by the first request the walk actually sends (only one walk runs at a time: monitor).
    private boolean mBotCheckProbePending;
    // NEWTUBE(auth-route): the no-media streak (consecutive proven no-media verdicts per
    // account-bearing client, deduplicated by video - see AUTH_RELOAD_QUARANTINE_MIN_HITS) used to
    // be a field here, so it died with the process and a cold open never reached two hits. It is
    // held in mAuthRouteQuarantine now, beside the records it leads to, and persisted with them.
    // Still cleared for a client as soon as it answers with anything else.
    //
    // 403-quarantine of account-bearing routes, held PER CLIENT and per transport. Per client
    // because the two TVHTML5 variants fail independently: TV (TVHTML5 7.x) hands out googlevideo
    // URLs that 403 every chunk past the pot-less ~60s mark, while TV_DOWNGRADED (TVHTML5 5.x) kept
    // serving the same video on the same session. Quarantining "the authenticated route" as a whole
    // threw away the client that actually worked. Per transport and with escalating cooldowns: see
    // AuthRouteQuarantineBook. Thread-safe on its own lock - the player's 403 writes it outside
    // this service's monitor.
    private final AuthRouteQuarantineBook mAuthRouteQuarantine = new AuthRouteQuarantineBook();
    /** Bumped by onAccountChanged under the book's lock; see countAuthRouteVerdict. */
    private volatile long mAccountGeneration;
    /**
     * Optional persistence for the 403-quarantine, supplied by the app layer (phones only, set
     * once at process start like the other mobile gates). Without it the quarantine is
     * process-local, so the FIRST open after every cold start re-probes a route this device has
     * already proven dead on this network -- measured 2026-09-07 on the Pixel 9 as 5.48s to first
     * frame instead of 2.80s, plus a wasted /player round trip, two dead media opens and a player
     * reload. Persisted, the cooldown AND its strike count keep their meaning across restarts, and
     * the route is still re-probed once the (escalating) cooldown expires.
     */
    public interface AuthRouteQuarantineStore {
        /** Last saved snapshot, or null. */
        @Nullable
        String load();
        /** Stores a snapshot; null clears it. */
        void save(@Nullable String snapshot);
    }
    @Nullable
    private static volatile AuthRouteQuarantineStore sAuthRouteQuarantineStore;
    private volatile boolean mAuthRouteQuarantineRestored;

    /** Phone flavor only; TV leaves this null and keeps the process-local quarantine. */
    public static void setAuthRouteQuarantineStore(@Nullable AuthRouteQuarantineStore store) {
        sAuthRouteQuarantineStore = store;
    }
    // Per-network memory that the ANONYMOUS partition is under a bot challenge (see
    // noteAnonymousChallenge). While set, web-pot clients are probed after everything else.
    private volatile long mAnonChallengeUntilMs;
    @Nullable
    private volatile String mAnonChallengeNetwork;
    /**
     * NEWTUBE(botwall): the stronger sibling of the anonymous-challenge memory above. That one only
     * reorders the Web partition; this one remembers that the anonymous identity as a WHOLE is
     * walled on a network attachment and then walks only the account route plus a rate-limited
     * re-probe. See BotWallBook for the evidence rules and the 2026-09-25 capture behind them.
     */
    private final BotWallBook mBotWall = new BotWallBook();
    /** The last real "not a bot" reason (localized), shown when a walled open asks no one. */
    @Nullable
    private volatile String mBotWallReason;
    private static final String DEFAULT_BOT_CHECK_REASON = "Sign in to confirm you’re not a bot";
    /**
     * NEWTUBE(recovery-blame): which client served each recently resolved video. mActualInfoType is
     * ONE process-wide slot that every resolution overwrites - including a next-video prefetch while
     * the current video plays - so a media 403 on the current video used to blame (quarantine,
     * defer on recovery) whatever client the PREFETCH happened to win with. The player now anchors
     * the route to the failing video first; see {@link #anchorRouteToVideo}.
     */
    /**
     * NEWTUBE(recovery-blame): the failing video's route, handed from {@link #anchorRouteToVideo}
     * to the two calls the player makes next (the 403 quarantine and switchNextFormat). It is a
     * separate field on purpose: a prefetch finishing in between writes mActualInfoType, never this.
     */
    @Nullable
    private volatile RouteAnchor mRouteAnchor;
    /** The client a recovery walk must step past; see switchNextFormat. */
    @Nullable
    private volatile AppClient mRecoverySuspect;

    private static final class RouteAnchor {
        final String videoId;
        final AppClient client;

        RouteAnchor(String videoId, AppClient client) {
            this.videoId = videoId;
            this.client = client;
        }
    }
    private final java.util.Map<String, AppClient> mVideoWinners = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, AppClient>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, AppClient> eldest) {
                    return size() > VIDEO_WINNER_MEMORY;
                }
            });
    private static final int VIDEO_WINNER_MEMORY = 8;
    /**
     * NEWTUBE(botwall): debug-build fault injection, installed by the phone app only when
     * BuildConfig.DEBUG (DebugBotWall). Null in release, so the per-request cost there is one
     * volatile read. See {@link #shouldInjectBotWall} for the modes.
     */
    public interface DebugBotWallSource {
        /** The current {@code debug.arc.botwall} value; re-read on every /player answer. */
        @Nullable
        String mode();
    }
    @Nullable
    private static volatile DebugBotWallSource sDebugBotWall;
    static final String DEBUG_BOT_CHECK_REASON =
            "Sign in to confirm you’re not a bot (debug.arc.botwall)";

    /** Debug builds only (see {@link DebugBotWallSource}); TV and release never call it. */
    public static void setDebugBotWallSource(@Nullable DebugBotWallSource source) {
        sDebugBotWall = source;
    }

    /**
     * NEWTUBE(botwall): persistence for the bot-wall book, supplied by the phone app (TV never
     * calls {@link #setBotWallStore}, so its book stays process-local). Without it a cold start
     * under a wall - a share link, a relaunch - re-walked the ring: ~7 anonymous /player calls
     * signed out, a challenged VISIONOS every time signed in, and a fresh probe schedule.
     */
    public interface BotWallStore {
        /** Last saved snapshot, or null. Called once, on a background thread. */
        @Nullable
        String load();

        /** Stores a snapshot; null clears it. Must not block (SharedPreferences.apply). */
        void save(@Nullable String snapshot);

        /** {@code Settings.Global.BOOT_COUNT}, or -1 when unknown. */
        long bootCount();
    }

    /**
     * Phone flavor only, once at process start. Restores the saved book on its own thread - the
     * first /player must not wait for preferences - and saves every change from then on. Anything
     * this process learns before the restore finishes is newer and wins; nothing is saved before
     * it, so a fast cold open cannot overwrite the stored wall with an empty book.
     */
    public static void setBotWallStore(@Nullable BotWallStore store) {
        VideoInfoService service = instance();
        if (store == null) {
            service.mBotWall.setPersister(null, -1, 0);
            return;
        }
        Thread restore = new Thread(() -> service.restoreBotWall(store), "BotWallRestore");
        restore.setDaemon(true);
        restore.start();
    }

    private void restoreBotWall(BotWallStore store) {
        String snapshot;
        long bootCount;
        try {
            snapshot = store.load();
            bootCount = store.bootCount();
        } catch (RuntimeException e) {
            snapshot = null;
            bootCount = -1;
        }
        long nowMs = android.os.SystemClock.elapsedRealtime();
        long bootWallMs = System.currentTimeMillis() - nowMs;
        mBotWall.setPersister(store::save, bootCount, bootWallMs);
        java.util.List<String> dropped = new java.util.ArrayList<>();
        int restored = mBotWall.restore(snapshot, bootCount, bootWallMs, nowMs, dropped);
        if (snapshot != null || restored > 0) {
            String network = activeNetworkKey();
            android.util.Log.d("NetPath", "player-ring botwall restore records=" + restored
                    + (dropped.isEmpty() ? "" : " dropped=" + dropped)
                    // At app start the network monitor usually hasn't reported yet: the restored
                    // records are applied when the first walk resolves its network key.
                    + (network != null ? " network=" + network + " " + mBotWall.describe(network,
                            android.os.SystemClock.elapsedRealtime()) : " network=pending"));
        }
        if (!dropped.isEmpty()) {
            store.save(restored > 0 ? mBotWall.encode(bootCount, bootWallMs) : null);
        }
    }
    private List<TranslationLanguage> mCachedTranslationLanguages;
    private volatile boolean mIsUnplayable;

    private VideoInfoService() {
        mVideoInfoApi = RetrofitHelper.create(VideoInfoApi.class);
    }

    public interface CancellationSignal {
        boolean isCanceled();
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
        // NEWTUBE(classification): which clients challenged this walk, and how many non-web clients
        // refused the video itself. See isLoneChallengeAmidRefusals.
        private final java.util.Set<AppClient> mChallenged = java.util.EnumSet.noneOf(AppClient.class);
        private int mContentRefusals;

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
            if (mobile) {
                mChallenged.add(client);
            }
            if (!mobile || !hasUnchallengedClientAfter(order, index, authenticated)
                    && !isLoneChallengeAmidRefusals()) {
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

        /** A non-web client refused the video itself (UNPLAYABLE) without the account. */
        void noteContentRefusal() {
            mContentRefusals++;
        }

        /**
         * NEWTUBE(classification): one client challenged while non-web clients refused the video on
         * its content. On a Pixel 9 over LTE (2026-09-28) every signed-out made-for-kids walk ended
         * like this: VISIONOS and ANDROID_VR "This video is not available", TV (TVHTML5 7.x) "Sign
         * in to confirm you're not a bot". Published as the walk's verdict it told the user the
         * wrong reason, cancelled autoplay and armed the fifteen-minute circuit that answers the
         * next opens with no request at all. That is one client's identity problem, not a verdict
         * on the video or the network: in a real wall VISIONOS is challenged too (BotWallBook asks
         * three clients, two of them non-web). The video's own refusal is the verdict instead.
         */
        boolean isLoneChallengeAmidRefusals() {
            return mChallenged.size() == 1 && mContentRefusals > 0;
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
            if (mResult == null || transportDown || isLoneChallengeAmidRefusals()) {
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
            /**
             * The empty answer carried a sign-in/age/visibility gate (LOGIN_REQUIRED,
             * AGE_CHECK_REQUIRED, CONTENT_CHECK_REQUIRED, ERROR) rather than the UNPLAYABLE of the
             * reload-page outage. Still counts toward the two-video streak as it always did, but
             * is too ambiguous to re-quarantine a client on probation by itself: an age-gated video
             * this account may not watch can still be served by anonymous WEB_EMBED (on TV; the
             * phone skips WEB_EMBED and sends age gates to the account route, TV_TIZEN).
             */
            final boolean gated;

            Held(String videoId, boolean reloadPage) {
                this(videoId, reloadPage, false);
            }

            Held(String videoId, boolean reloadPage, boolean gated) {
                this.videoId = videoId;
                this.reloadPage = reloadPage;
                this.gated = gated;
            }
        }

        private final java.util.Map<AppClient, Held> mHeld = new java.util.LinkedHashMap<>();
        private boolean mServedElsewhere;
        /** The service's account generation when this walk began (see onAccountChanged). */
        final long accountGeneration;

        AuthRouteWalkState() {
            this(0);
        }

        AuthRouteWalkState(long accountGeneration) {
            this.accountGeneration = accountGeneration;
        }

        /**
         * @return true if the caller should count this observation now. False means it is held
         *         until a client serves the video; if none does, it is dropped.
         */
        boolean hold(AppClient client, String videoId, boolean reloadPage) {
            return hold(client, videoId, reloadPage, false);
        }

        /** @see Held#gated */
        boolean hold(AppClient client, String videoId, boolean reloadPage, boolean gated) {
            if (mServedElsewhere) {
                return true;
            }
            mHeld.put(client, new Held(videoId, reloadPage, gated));
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

    /**
     * NEWTUBE(net): when every client says the same thing, the ring has nothing left to find.
     *
     * <p>Pixel 9, 2026-09-25, jfKfPfyJRdk (an ended stream whose recording was never published):
     * all eleven clients were asked, nine answered UNPLAYABLE "La grabación de esta emisión en
     * directo no está disponible." and the two others answered embed/outdated-client ERRORs - 11
     * /player calls and 4-5 s before the app could say so. The verdict was settled at attempt 3.
     *
     * <p>Definitive = at least {@link #MIN_CLIENTS} distinct clients, at least one the SERVER
     * confirmed signed in ({@code srvAuth=y} - sending the credential is not enough) and one that
     * carried no account, all answering with the same verdict that
     * {@link BotCheckDetector#definitiveUnplayableKey} ALLOWLISTS as terminal for the content (an
     * unpublished live recording, a removal by the uploader or for a violation - however each
     * client words it - or a terminated account), and no parsed answer so far saying anything
     * else. Stability over speed: a generic "Video unavailable" never qualifies. A credential the
     * server did not confirm still counts as a distinct client, not as the signed-in witness.
     * ERROR answers with no allowlisted reason are neutral (they are the client-specific ones
     * here: embedding disabled, client too old); no answer at all is no evidence. Anything else -
     * a playable or OK answer, a sign-in or age gate, any other reason - ends the consensus for
     * the rest of the walk. Mobile only.
     *
     * <p>One instance per walk. Not thread-safe: {@code firstPlayable} runs under this service's
     * monitor and the instance never escapes it.
     */
    static final class UnplayableConsensus {
        static final int MIN_CLIENTS = 3;

        @Nullable
        private String mKey;
        private boolean mBroken;
        private boolean mAuth;
        private boolean mAnonymous;
        private final java.util.Set<AppClient> mClients = new java.util.LinkedHashSet<>();
        /**
         * NEWTUBE(planner): a signed-out walk has no signed-in witness, so it never reached the
         * verdict and asked the whole ring about a removed video. Planned, its first sources ride
         * three different visitors (the web session, the app, the embed page - PlayerSource
         * identity); the same allowlisted verdict from three identities is the same independence,
         * reached without an account. Signed in, the account the server confirmed is one more
         * identity: VISIONOS, TV_TIZEN with the account and WEB_EMBED settle it at the third request.
         */
        static final int MIN_IDENTITIES = 3;
        private final boolean mByIdentity;
        private final boolean mAccountRequired;
        private final java.util.Set<PlayerSource.Identity> mIdentities =
                java.util.EnumSet.noneOf(PlayerSource.Identity.class);

        UnplayableConsensus() {
            this(false);
        }

        UnplayableConsensus(boolean byIdentity) {
            this(byIdentity, false);
        }

        /**
         * @param accountRequired signed in: identities settle nothing without the witness the
         *                        server confirmed signed in - three anonymous answers must not
         *                        settle a video the account was never heard on (Codex astra
         *                        review of LANES.md, 2026-09-29)
         */
        UnplayableConsensus(boolean byIdentity, boolean accountRequired) {
            mByIdentity = byIdentity;
            mAccountRequired = accountRequired;
        }

        /**
         * @param signedIn TRUE: sent with the account and the server confirmed it; FALSE: sent
         *                 without one; null: sent with the account, not confirmed
         * @param status the answer's raw playability status, or null for no answer
         * @param key {@link BotCheckDetector#definitiveUnplayableKey} of the answer
         * @return true once the verdict is definitive (stays true for later calls)
         */
        boolean note(AppClient client, @Nullable Boolean signedIn, @Nullable String status,
                @Nullable String key) {
            if (mBroken || status == null || (key == null && "ERROR".equals(status))) {
                return !mBroken && isDefinitive();
            }
            if (key == null || (mKey != null && !mKey.equals(key))) {
                mBroken = true;
                return false;
            }
            mKey = key;
            mClients.add(client);
            mAuth |= Boolean.TRUE.equals(signedIn);
            mAnonymous |= Boolean.FALSE.equals(signedIn);
            if (Boolean.FALSE.equals(signedIn)) {
                mIdentities.add(PlayerSourceCatalog.defaultFor(client).identity);
            }
            return isDefinitive();
        }

        /** The witness a parsed answer can be; see {@link #note}. */
        @Nullable
        static Boolean witness(VideoInfo result) {
            if (!result.isAuth()) {
                return Boolean.FALSE;
            }
            return Boolean.TRUE.equals(result.isServerLoggedIn()) ? Boolean.TRUE : null;
        }

        private boolean isDefinitive() {
            return mAuth && mAnonymous && mClients.size() >= MIN_CLIENTS
                    || mByIdentity && (mAuth || !mAccountRequired)
                            && mIdentities.size() + (mAuth ? 1 : 0) >= MIN_IDENTITIES;
        }

        String clients() {
            StringBuilder result = new StringBuilder();
            for (AppClient client : mClients) {
                result.append(result.length() > 0 ? "," : "").append(client);
            }
            return result.toString();
        }

        /** Credential- and content-free: the reason text itself stays on its player-result line. */
        String reasonHash() {
            return mKey != null ? Integer.toHexString(mKey.hashCode()) : "none";
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

    public VideoInfo getVideoInfo(String videoId, String clickTrackingParams,
            @Nullable CancellationSignal cancellationSignal) {
        return getVideoInfo(videoId, clickTrackingParams, cancellationSignal, WalkRole.ACTIVE);
    }

    public synchronized VideoInfo getVideoInfo(String videoId, String clickTrackingParams,
            @Nullable CancellationSignal cancellationSignal, WalkRole role) {
        mWalkRole = role;
        try {
            return getVideoInfoAs(videoId, clickTrackingParams, cancellationSignal);
        } finally {
            mWalkRole = WalkRole.ACTIVE;
        }
    }

    private VideoInfo getVideoInfoAs(String videoId, String clickTrackingParams,
            @Nullable CancellationSignal cancellationSignal) {
        if (videoId == null) {
            return null;
        }
        if (abortCanceledRequest(videoId, "entry", cancellationSignal)) {
            return null;
        }

        final long routingGeneration = mRoutingGeneration.get();
        final boolean authenticated = hasAuthentication();
        final boolean speculative = mWalkRole == WalkRole.SPECULATIVE;
        mBotCheckProbePending = false; // see spendBotCheckProbeIfPending
        VideoInfo blockedResult = getActiveBotCheckResult(authenticated, videoId);
        if (blockedResult != null) {
            return blockedResult;
        }

        AppService.instance().resetClientPlaybackNonce(); // unique value per each video info

        mAuthBlock = true;

        VideoInfo result = firstPlayable(
                videoId, clickTrackingParams, authenticated, cancellationSignal);
        if (abortCanceledRequest(videoId, "post-player", cancellationSignal)) {
            return null;
        }

        // An error cursor is one-shot on mobile. Leaving it set after a successful failover made
        // every later open start from stale routing state. TV keeps its historical behavior. A
        // speculative walk leaves it to the video the user opens (see WalkRole).
        if (!speculative && sPreferNoPotClient
                && routingGeneration == mRoutingGeneration.get()) {
            mNextInfoType = null;
            mRecoveryWalk = false;
            mRecoverySuspect = null;
        } else if (!speculative && sPreferNoPotClient) {
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

        rememberVideoWinner(videoId, result);
        if (speculative) {
            // NEWTUBE(walk-role): the current client, the cold-start hint and the "was it
            // playable" flag describe the video being watched; this one is not (yet).
            android.util.Log.d("NetPath", "player-ring speculative video=" + videoId
                    + " client=" + result.getClient() + " routing=kept");
            if (!result.isUnplayable()) {
                clearBotCheckCircuit();
            }
            return result;
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
     * Walks the order ONCE (the phone's from PhoneSourcePlanner, TV's the client ring from the
     * remembered begin client) and returns the first PLAYABLE result. The first non-null
     * (necessarily unplayable) result seen along the way is remembered and returned as a fallback
     * when the whole walk yields nothing playable, so the caller still gets an "unplayable" reason
     * to show. Same outcome as the old two-pass sweep (pass 1: first playable, pass 2: first
     * non-null) at half the worst-case /player call count.
     */
    private VideoInfo firstPlayable(String videoId, String clickTrackingParams,
            boolean authenticated, @Nullable CancellationSignal cancellationSignal) {
        //final AppClient beginType = getDefaultClient();
        // NEWTUBE(recovery-blame): a recovery walk steps past the client that served the FAILING
        // video, which a prefetch may no longer have in mActualInfoType (see switchNextFormat).
        // NEWTUBE(walk-role): a speculative walk (next-video preload, touch preload, warmup) is not
        // the recovery of the video being watched: it neither follows nor spends the cursor.
        final boolean speculative = mWalkRole == WalkRole.SPECULATIVE;
        final boolean cursorOwned = mRecoveryWalk && !speculative;
        final AppClient lastWinner = cursorOwned && mRecoverySuspect != null
                ? mRecoverySuspect : mActualInfoType;
        // NEWTUBE(net): an outage is not evidence against the client that was working.
        //
        // A recovery walk deliberately steps PAST the last winner, because the case it was written
        // for is a client-specific failure (an expired GVS URL answering 403). When the previous
        // walk instead ended in transport-down, nothing was learned about any client - so honouring
        // the cursor just abandons the known-good route. Measured on the netshape rig: a 150s tunnel
        // ended with playback restored on WEB_EMBED (auth=n, sabr=y) instead of the learned
        // TV_DOWNGRADED (auth=y, 41 formats), i.e. the outage silently cost the user authenticated
        // playback until something else reset the routing.
        final boolean recoveryWalk = cursorOwned && !mLastWalkTransportDown;
        if (cursorOwned && mLastWalkTransportDown) {
            android.util.Log.d("NetPath", "player-ring recovery-suppressed reason=transport-down"
                    + " keeping=" + lastWinner);
        }
        // Only this walk's own outcome may set it again (the watched video's walks only).
        if (!speculative) {
            mLastWalkTransportDown = false;
        }
        // A normal signed-in open starts on the account-bearing TV route, matching yt-dlp's use
        // of tv_downgraded for authenticated extraction. An error-driven reload is different: its
        // cursor deliberately points past the client whose GVS URL just failed. Re-promoting TV on
        // that reload selected the same TV_DOWNGRADED client forever and defeated the entire 403
        // recovery ring. Let recovery honor the cursor; auth headers are still attached
        // automatically if a later auth-capable client is reached.
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
                : VIDEO_INFO_TYPE_LIST[0];
        // mNextInfoType is the recovery cursor, which a speculative walk does not follow.
        final AppClient beginType = authenticated && !authenticatedRecovery
                ? authBegin
                : (mNextInfoType != null && !(mRecoveryWalk && speculative)
                        ? mNextInfoType : defaultBegin);

        // NEWTUBE(botwall): mobile only, and never while a client is forced (the playground must
        // measure exactly the client it names). Keyed on the network attachment - see
        // BotWallBook. The key is looked up only once the book has something to say, so a healthy
        // open pays no extra system calls.
        final boolean mobileWall = sPreferNoPotClient && sDebugForcedClient == null;
        // NEWTUBE(planner): the phone's order, both lanes, comes from PhoneSourcePlanner (netbench
        // LANES.md); upstream's ring and the helpers that bent it are the TV path only.
        final boolean planned = mobileWall;
        final PhoneSourcePlanner.Lane lane = authenticated
                ? PhoneSourcePlanner.Lane.SIGNED_IN : PhoneSourcePlanner.Lane.SIGNED_OUT;
        maybeResetBotWallForDebug();
        final WallKeys wallKeys = new WallKeys();
        final long walkStartMs = android.os.SystemClock.elapsedRealtime();
        final BotWallBook.Plan wallPlan = mobileWall && mBotWall.hasWalls(walkStartMs)
                ? mBotWall.plan(wallKeys.network(), authenticated, noMediaVideoKey(videoId),
                        walkStartMs)
                : BotWallBook.Plan.NONE;

        java.util.List<AppClient> visitOrder;
        // NEWTUBE(kids-channel): this open's channel as the app named it, what the lane remembers
        // of it, and whether the account route leads because of it (planned walks only).
        String kidsChannel = null;
        KidsChannelMemory.Hint kidsHint = KidsChannelMemory.Hint.NONE;
        boolean kidsHinted = false;
        final long kidsGeneration = sKidsChannels.generation();
        if (sDebugForcedClient != null) {
            visitOrder = java.util.Collections.singletonList(sDebugForcedClient);
            android.util.Log.d("NetPath", "player-ring forced-client=" + sDebugForcedClient);
        } else if (wallPlan.walled) {
            // The whole ring is known to answer "not a bot" from here: ask only what can still
            // serve (the account route) and, once per interval, re-test the anonymous identity.
            // An empty plan is answered without a single request.
            android.util.Log.w("NetPath", "player-ring botwall route video=" + videoId
                    + " order=" + wallPlan.order + " probe=" + (wallPlan.probe ? "y" : "n")
                    + (wallPlan.budgetCapped ? " budget=capped" : "")
                    + " auth=" + (authenticated ? "y" : "n") + " network=" + wallKeys.network()
                    + " accountRoute=" + accountRouteState(wallKeys.network(), videoId)
                    + " " + mBotWall.describe(wallKeys.network(),
                            android.os.SystemClock.elapsedRealtime()));
            if (wallPlan.order.isEmpty()) {
                return walledVerdict();
            }
            visitOrder = new java.util.ArrayList<>(wallPlan.order);
        } else if (planned) {
            // Signed in, the account route is planned unless BotWallBook has benched it for this
            // video or this attachment (a media 403, a challenge, a reload-page or SABR-only answer).
            // NEWTUBE(kids-channel): a recovery walk has its own order; the hint is never read.
            if (sKidsChannelHint && !recoveryWalk) {
                kidsChannel = sKidsChannels.channelOf(videoId);
                kidsHint = sKidsChannels.hintFor(lane, kidsChannel);
            }
            // Signed out, the same record benches the anonymous ask a hint would lead with.
            final boolean accountRouteBenched = (authenticated || kidsHint == KidsChannelMemory.Hint.FIRST)
                    && mBotWall.hasRouteRecords()
                    && mBotWall.isRouteFailed(wallKeys.network(), noMediaVideoKey(videoId), walkStartMs);
            // Signed out, a bot-wall suspicion (a platform challenge in the last minutes, or a wall
            // just lapsed) keeps the anonymous TV_TIZEN from the head: one more challenge on another
            // video establishes the wall, and it must not come from an ask only a hint added (a
            // mixed channel's ordinary video). The refusal rule still admits it after VISIONOS.
            final boolean kidsSuspicion = kidsHint == KidsChannelMemory.Hint.FIRST && !authenticated
                    && mBotWall.hasSuspicion(walkStartMs);
            kidsHinted = kidsHint == KidsChannelMemory.Hint.FIRST && !accountRouteBenched
                    && !kidsSuspicion;
            visitOrder = PhoneSourcePlanner.order(new PhoneSourcePlanner.Context(
                    lane, recoveryWalk ? lastWinner : null, anonChallenged, accountRouteBenched,
                    sAccountRouteFirst, kidsHinted));
            android.util.Log.d("NetPath", "player-ring plan video=" + videoId
                    + " lane=" + (authenticated ? "signed-in" : "signed-out")
                    + (recoveryWalk ? " suspect=" + lastWinner : "")
                    + (anonChallenged ? " anon-challenged" : "")
                    + (accountRouteBenched ? " account-route=benched" : "")
                    + " order=" + visitOrder);
            if (kidsHint == KidsChannelMemory.Hint.FIRST) {
                android.util.Log.d("NetPath", "kids-channel " + (kidsHinted ? "hint"
                        : "hint-skip reason=" + (accountRouteBenched ? "benched" : "suspicion"))
                        + " video=" + videoId + " channel=" + KidsChannelMemory.tag(kidsChannel)
                        + " lane=" + laneName(lane)
                        + " hintsLeft=" + sKidsChannels.hintsLeft(lane, kidsChannel)
                        + " order=" + visitOrder);
            } else if (kidsHint == KidsChannelMemory.Hint.REPROOF) {
                android.util.Log.d("NetPath", "kids-channel reproof video=" + videoId
                        + " channel=" + KidsChannelMemory.tag(kidsChannel) + " lane=" + laneName(lane));
            }
        } else {
            visitOrder = buildRequestVisitOrder(
                    beginType, lastWinner, false,
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
                        + " network=" + activeTransportKey());
            } else if (authenticated && !recoveryWalk && !forbiddenAuthClients.isEmpty()) {
                android.util.Log.d("NetPath", "player-ring authenticated-first=" + visitOrder.get(0)
                        + " demoted=" + forbiddenAuthClients);
                logHeadDemotedBehindTokenFreeClient(visitOrder, forbiddenAuthClients);
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
        // NEWTUBE(classification): the distinct sign-in requests (LOGIN_REQUIRED, no age marker, no
        // explicit bot text) this walk was answered with; see isChallengeConfirmed.
        final java.util.Set<String> signInReasons = new java.util.HashSet<>();
        VideoInfo liveWithoutDash = null;
        // Round trips saved by not probing clients that cannot return a live dash manifest.
        int liveDashSkipped = 0;
        // Holds a challenge the walk carried on past, and tracks whether the ring was finished.
        // See BotCheckWalkState.
        final BotCheckWalkState botCheck = new BotCheckWalkState();
        // Holds auth-route no-media observations until a client proves the video is playable at
        // all. See AuthRouteWalkState.
        final AuthRouteWalkState authRoute = new AuthRouteWalkState(mAccountGeneration);
        // Stops the walk once the ring agrees the video cannot play. See UnplayableConsensus.
        final UnplayableConsensus unplayable = new UnplayableConsensus(planned, authenticated);
        // NEWTUBE(botwall): this walk's anonymous bot checks, and who has been asked already (the
        // account route is inserted at most once, and a mid-walk wall never re-asks anyone).
        final BotWallBook.WalkEvidence wallEvidence = new BotWallBook.WalkEvidence();
        final java.util.Set<AppClient> attempted = java.util.EnumSet.noneOf(AppClient.class);
        // NEWTUBE(planner): TV_TIZEN was put next by the anonymous-refusal rule, not by a wall plan.
        // NEWTUBE(kids-channel): or first by a channel hint, signed out: the same speculative ask,
        // with the same short budget.
        boolean anonTizenSpeculative = kidsHinted && !authenticated;
        // NEWTUBE(kids-channel): this walk's first anonymous content refusal from VISIONOS or
        // ANDROID_VR; followed by a serve from the account route, it is the proof KidsChannelMemory keeps.
        VideoInfo kidsRefusal = null;
        // NEWTUBE(planner): the first age gate of this walk, and the sources that answered it
        // without serving the video (see PhoneSourcePlanner.isAgeGateSettled).
        VideoInfo firstAgeGate = null;
        final java.util.Set<AppClient> refused = java.util.EnumSet.noneOf(AppClient.class);
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

            // A playable live result is already held and ONLY a dash manifest can improve on it
            // (see sPreferDashManifestForLive), so a client that never returns one cannot change
            // the outcome - it can only add a round trip. See isLiveDashCandidate.
            if (liveWithoutDash != null && !isLiveDashCandidate(nextType)) {
                liveDashSkipped++;
                continue;
            }

            attempt++;
            attempted.add(nextType);
            // NEWTUBE(botwall): probes are spent when SENT (see BotWallBook.consumeProbe), and
            // every request of a walled open - its recovery reloads included - counts against
            // that video's walled budget.
            if (attempt == 1) {
                spendBotCheckProbeIfPending();
            }
            if (wallPlan.probe && nextType == wallPlan.probeClient) {
                mBotWall.consumeProbe(wallKeys.network(), nextType,
                        android.os.SystemClock.elapsedRealtime());
            }
            if (mobileWall && (wallPlan.walled || wallEvidence.hasShortcut())) {
                mBotWall.noteWalledRequest(wallKeys.network(), noMediaVideoKey(videoId),
                        android.os.SystemClock.elapsedRealtime());
            }
            boolean[] noResponse = new boolean[1];
            // The anonymous-refusal rule's TV_TIZEN is a speculative ask: the short budget, not the
            // account route's cold-start one (it may hang with the rest of the ring still to go).
            long attemptBudgetMs = anonTizenSpeculative && nextType == BotWallBook.ACCOUNT_ROUTE
                    ? Math.min(remainingBudgetMs, CLIENT_ATTEMPT_TIMEOUT_MS) : remainingBudgetMs;
            VideoInfo result = getVideoInfoWithTimeout(
                    nextType, videoId, clickTrackingParams, cancellationSignal, attemptBudgetMs,
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
            // NEWTUBE(kids-channel): the hinted account route's own answer. A serve spends a hint.
            // Anything else - a refusal, a gate, a challenge, SABR only, no answer - drops the
            // channel, and the walk goes on in the lane's order, VISIONOS next (never back to
            // TV_TIZEN: it is attempted). A playable live answer is never the account route's to
            // play (formats only, no manifest): it is set aside unused, the live sources answer as
            // they do today (VISIONOS's HLS held, ANDROID_VR's DASH), and the channel is kept. A
            // refusing live answer (a recording gone, a gate, a challenge) is an answer like any
            // other: it drops the channel and goes through the walk's bookkeeping below.
            if (kidsHinted && attempt == 1 && nextType == PhoneSourcePlanner.ACCOUNT_ROUTE) {
                if (playable && hasLiveSignal(result)) {
                    android.util.Log.d("NetPath", "kids-channel hint-skip reason=live video=" + videoId
                            + " channel=" + KidsChannelMemory.tag(kidsChannel));
                    continue;
                }
                if (playable) {
                    // The card's channel may not be the video's: the answer's own, when it differs.
                    String answered = channelOf(result);
                    android.util.Log.d("NetPath", "kids-channel hint-served video=" + videoId
                            + " channel=" + KidsChannelMemory.tag(kidsChannel) + " client=" + nextType
                            + " hintsLeft=" + sKidsChannels.spendHint(lane, kidsChannel, kidsGeneration)
                            + (answered != null && !answered.equals(kidsChannel)
                                    ? " answerChannel=" + KidsChannelMemory.tag(answered) : ""));
                } else {
                    sKidsChannels.drop(lane, kidsChannel, kidsGeneration);
                    android.util.Log.d("NetPath", "kids-channel drop channel="
                            + KidsChannelMemory.tag(kidsChannel) + " video=" + videoId
                            + " lane=" + laneName(lane)
                            + " reason=" + kidsHintFailure(result, noResponse[0]));
                }
            }
            if (kidsRefusal == null && planned && sKidsChannelHint && result != null
                    && PhoneSourcePlanner.refusesMadeForKids(nextType) && isContentRefusal(result)) {
                kidsRefusal = result;
            }
            if (result != null && !result.isAuth() && result.isUnknownRestricted()
                    && !nextType.isWebPotRequired()) {
                botCheck.noteContentRefusal();
            }

            // NEWTUBE(web-embed-identity): an embed identity that is refused ("Error code: 152")
            // may have gone stale; the next WEB_EMBED request fetches a fresh embed page.
            if (nextType == AppClient.WEB_EMBED && result != null
                    && result.getPlayabilityStatus() != null
                    && result.getPlayabilityStatus().contains("152")) {
                com.liskovsoft.youtubeapi.innertube.ytcfg.YtCfgService.invalidateEmbedIdentity();
            }

            // NEWTUBE(botwall): wall evidence and the account route. Runs BEFORE the bot-check
            // block below, because that block decides whether the walk may carry on past a
            // challenge by reading visitOrder - which this can extend with the account route.
            if (mobileWall && result != null) {
                visitOrder = noteBotWallEvidence(videoId, nextType, result, authenticated,
                        wallKeys, wallEvidence, wallPlan.walled, visitOrder, visitIndex, attempted,
                        attempt);
            }
            // NEWTUBE(planner): signed out, an anonymous content refusal admits the account route
            // without the account (PhoneSourcePlanner.admitsAccountRouteAfter). The wall is read
            // NOW: this walk's own answers may have just established one, and then its plan owns
            // TV_TIZEN's single anonymous ask. Live signals (an ended stream whose recording is gone
            // answers UNPLAYABLE) and verdicts terminal for everyone (members only, removed) are not
            // a refusal TV_TIZEN can help with.
            if (planned && PhoneSourcePlanner.admitsAccountRouteAfter(lane, nextType)
                    && result != null && !result.isAuth()
                    && result.isUnknownRestricted() && !hasLiveSignal(result)
                    && BotCheckDetector.definitiveUnplayableKey(result.getRawPlayabilityStatus(),
                            result.getPlayabilityStatus()) == null
                    && liveWithoutDash == null && !wallPlan.walled
                    && !mBotWall.isWalled(wallKeys.network(), android.os.SystemClock.elapsedRealtime())
                    && !attempted.contains(BotWallBook.ACCOUNT_ROUTE)
                    && !(recoveryWalk && lastWinner == BotWallBook.ACCOUNT_ROUTE)
                    && (visitIndex + 1 >= visitOrder.size()
                            || visitOrder.get(visitIndex + 1) != BotWallBook.ACCOUNT_ROUTE)
                    && !mBotWall.isRouteFailed(wallKeys.network(), noMediaVideoKey(videoId),
                            android.os.SystemClock.elapsedRealtime())) {
                visitOrder = insertAfter(visitOrder, visitIndex, BotWallBook.ACCOUNT_ROUTE);
                anonTizenSpeculative = true;
                android.util.Log.d("NetPath", "player-ring anon-tizen next after=" + nextType
                        + " attempt=" + attempt + " reason=unplayable");
            }
            // Signed out, the account route is one more anonymous identity: on a walled
            // attachment it gets ONE ask per wall, whatever it answers - a timeout, a reload-page
            // or SABR-only answer included, none of which the challenge path above records.
            if (mobileWall && !authenticated && nextType == BotWallBook.ACCOUNT_ROUTE && !playable
                    && mBotWall.hasWalls(android.os.SystemClock.elapsedRealtime())) {
                mBotWall.noteAnonRouteSpent(wallKeys.network(),
                        android.os.SystemClock.elapsedRealtime());
            }

            // The account-bearing route is currently broken server-side (see
            // isAuthRouteReloadVerdict). Demote it the same way a media 403 does, so the walk stops
            // spending two guaranteed-dead round trips on the head of every signed-in open.
            // A planned walk asks no TVHTML5 client, so only a forced-client walk gets here.
            // isAuthSupported (TV family), NOT isAuthCapable - deliberately. Two reasons. The
            // "reload page" shape is a TVHTML5 server behaviour; the same shape from an
            // authenticated WEB_EMBED is far more likely to be a genuinely unplayable video, and
            // quarantining on it would demote the account route for the wrong reason. And the
            // quarantine set this feeds is counted against AUTHENTICATED_HEAD.length to decide
            // authenticatedWebFirst, so admitting a non-head client would corrupt that arithmetic.
            // NEWTUBE(botwall): the same arithmetic is why the bot-wall account route is excluded:
            // it is not a head, and its failures live in BotWallBook instead.
            if (sPreferNoPotClient && authenticated && nextType.isAuthSupported()
                    && nextType != BotWallBook.ACCOUNT_ROUTE) {
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
                    if (!speculative) {
                        mLastWalkTransportDown = true;
                    }
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

            if (result != null && sPreferNoPotClient && result.isLoginRequired() && !result.isAgeGate()
                    && !result.isBotCheckRequired()) {
                signInReasons.add(BotCheckDetector.normalizedReason(result.getPlayabilityStatus()));
            }
            if (result != null) {
                // NEWTUBE(age-gate): an age gate repeats the same LOGIN_REQUIRED reason on every
                // client too, so it must not read as a localized bot check: that tripped the
                // bot-check circuit before an age-capable client (WEB_EMBED) was reached, and
                // then answered "not a bot" for other videos for a minute (netbench 2026-09-28).
                // firstLoginRequired is never an age gate (see below).
                boolean repeatedLoginRequired = firstLoginRequired != null && !result.isAgeGate()
                        && BotCheckDetector.isRepeatedLoginRequired(
                                firstLoginRequired.getRawPlayabilityStatus(),
                                firstLoginRequired.getPlayabilityStatus(),
                                result.getRawPlayabilityStatus(),
                                result.getPlayabilityStatus());
                if (result.isBotCheckRequired() || repeatedLoginRequired) {
                    final String signal =
                            result.isBotCheckRequired() ? "explicit" : SIGNAL_REPEATED_LOGIN;
                    // A challenge answered to a request that carried no account is evidence about
                    // the ANONYMOUS identity, not about this video. Count it so the walk can learn
                    // to stop leading with a partition the network is currently rejecting.
                    // NEWTUBE(classification): on the phone only an explicit bot text counts; the
                    // repeated sign-in request is also what a private video answers (see
                    // isChallengeConfirmed) and has to be confirmed first.
                    if (!result.isAuth()
                            && (!sPreferNoPotClient || !SIGNAL_REPEATED_LOGIN.equals(signal))) {
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
                    } else if (isChallengeConfirmed(signal, videoId, signInReasons.size() <= 1)) {
                        tripBotCheckCircuit(result, nextType, signal, authenticated,
                                botCheck.ringExhausted());
                        return result;
                    }
                }
                // An age gate is kept out of the comparison slot, or two later bot-check answers
                // would each be compared with it and never with each other.
                if (firstLoginRequired == null && result.isLoginRequired() && !result.isAgeGate()) {
                    firstLoginRequired = result;
                }
            }

            if (sPreferNoPotClient && liveWithoutDash == null && result != null
                    && unplayable.note(nextType, UnplayableConsensus.witness(result),
                            result.getRawPlayabilityStatus(),
                            result.isBotCheckRequired() || result.isRent() ? null
                                    : BotCheckDetector.definitiveUnplayableKey(
                                            result.getRawPlayabilityStatus(),
                                            result.getPlayabilityStatus()))) {
                android.util.Log.d("NetPath", "player-ring definitive-unplayable video=" + videoId
                        + " clients=" + unplayable.clients()
                        + " reason-hash=" + unplayable.reasonHash()
                        + " attempts=" + attempt
                        + " skipped=" + (visitOrder.size() - visitIndex - 1));
                return result;
            }

            // NEWTUBE(planner): an age gate nothing left in this walk can serve is the verdict:
            // signed out once WEB_EMBED has answered without serving it (the second request, where
            // the ring asked eight clients and minted three web tokens to hear the same gate),
            // signed in once the account route and WEB_EMBED have. Only YouTube's structured age
            // gate starts it (isAgeGate): a plain sign-in request keeps walking. The age gate is
            // the answer returned, whatever the last source said (an embed refusal, usually).
            if (planned && liveWithoutDash == null && result != null && !playable) {
                if (firstAgeGate == null && result.isAgeGate()) {
                    firstAgeGate = result;
                }
                refused.add(nextType);
                if (PhoneSourcePlanner.isAgeGateSettled(lane, firstAgeGate != null, refused,
                        attempted, visitOrder.subList(visitIndex + 1, visitOrder.size()))) {
                    android.util.Log.d("NetPath", "player-ring age-gate-settled video=" + videoId
                            + " refused=" + refused + " attempts=" + attempt
                            + " skipped=" + (visitOrder.size() - visitIndex - 1));
                    return firstAgeGate;
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
                    countAuthRouteVerdict(held.getKey(), held.getValue(),
                            authRoute.accountGeneration);
                }
                // The anonymous partition just served a video, so whatever guest challenge was
                // remembered has lifted. Drop it immediately rather than sitting out the TTL.
                if (nextType.isWebPotRequired() && !result.isAuth()) {
                    clearAnonChallenge("anon-served");
                }
                // NEWTUBE(botwall): same for the wall, from ANY anonymous identity - except the
                // account route asked anonymously, which serving behind a wall says nothing about
                // the rest of the anonymous ring (keeping the wall keeps the short walk).
                if (mobileWall && !result.isAuth() && nextType != BotWallBook.ACCOUNT_ROUTE
                        && mBotWall.hasSuspicion(android.os.SystemClock.elapsedRealtime())
                        && mBotWall.noteAnonServed(wallKeys.network())) {
                    android.util.Log.w("NetPath", "player-ring botwall cleared reason=anon-served"
                            + " client=" + nextType + " network=" + wallKeys.network());
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
                    // NEWTUBE(kids-channel): a hinted walk's serve was counted where it was asked.
                    if (planned && sKidsChannelHint && !kidsHinted) {
                        noteKidsChannelServed(videoId, lane, nextType, result, kidsRefusal,
                                kidsGeneration);
                    }
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
        if (challenge == null && botCheck.hasHeldChallenge() && botCheck.isLoneChallengeAmidRefusals()) {
            android.util.Log.d("NetPath", "bot-check discounted video=" + videoId
                    + " reason=lone-challenge-amid-refusals");
        }
        if (challenge != null
                && !isChallengeConfirmed(challenge.signal, videoId, signInReasons.size() <= 1)) {
            challenge = null; // the video's own refusal is published below
        }
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
     * repeat the answer we already have, one round trip at a time. Skipped clients cost nothing,
     * so once a result is held the walk goes straight to ANDROID_VR wherever it sits in the order.
     *
     * <p>Measured on the Pixel 9 on 2026-09-07 across two 24/7 live streams: every web-family
     * client answered {@code dash=n} and ANDROID_VR answered {@code dash=y} for both. Probing the
     * rest cost five extra round trips on 5yx6BWlEVcY (first frame +3220ms against +2472ms for the
     * stream that reached ANDROID_VR sooner).
     *
     * <p>The TV family used to be kept as a candidate on the strength of the flag's comment rather
     * than evidence. It now has evidence against it: signed in on 2026-09-25 (nI725iVsyoQ, LTE),
     * TV answered live with {@code dash=n hls=n sabr=y} and TV_DOWNGRADED - quarantined and
     * demoted on that network, yet still probed because it was a candidate - answered
     * {@code dash=n hls=n sabr=n}, a ~0.9 s round trip before ANDROID_VR's {@code dash=y}. No
     * client other than ANDROID_VR has ever returned a live dashManifestUrl here.
     */
    static boolean isLiveDashCandidate(AppClient client) {
        return client == AppClient.ANDROID_VR;
    }

    /** Network keys for ONE walk, read only when the bot-wall book actually needs them. */
    private static final class WallKeys {
        @Nullable
        private String mNetwork;
        @Nullable
        private String mTransport;
        private boolean mNetworkRead;
        private boolean mTransportRead;

        @Nullable
        String network() {
            if (!mNetworkRead) {
                mNetwork = activeNetworkKey();
                mNetworkRead = true;
            }
            return mNetwork;
        }

        @Nullable
        String transport() {
            if (!mTransportRead) {
                mTransport = activeTransportKey();
                mTransportRead = true;
            }
            return mTransport;
        }
    }

    /**
     * NEWTUBE(botwall): what one parsed answer teaches about the wall and the account route, and
     * the (possibly extended) visit order. Three rules, in this order:
     * <ol>
     *   <li>The account route's own answer, when it carried the account, is only evidence about
     *   that route: a challenge, a reload-page verdict or a SABR-only answer marks it failed on
     *   this transport (see {@link #accountRouteFailure}).</li>
     *   <li>An explicit bot check answered to an ANONYMOUS request is wall evidence. If it
     *   establishes the wall mid-walk, the rest of this walk becomes the walled plan - the first
     *   walk on a walled network stops paying for clients that are about to say the same thing.</li>
     *   <li>Signed in, any LOGIN_REQUIRED from an anonymous request (the bot check or an age gate:
     *   YouTube is literally asking for the account) puts the account route next, once per walk,
     *   unless it has proven dead on this transport. This is what serves a signed-in open on the
     *   FIRST challenged attempt, before any wall exists; a healthy open never reaches it.</li>
     * </ol>
     */
    private List<AppClient> noteBotWallEvidence(String videoId, AppClient client, VideoInfo result,
            boolean authenticated, WallKeys keys, BotWallBook.WalkEvidence walk,
            boolean walledAtStart, List<AppClient> order, int index,
            java.util.Set<AppClient> attempted, int attempt) {
        long nowMs = android.os.SystemClock.elapsedRealtime();
        boolean anonymous = !result.isAuth();
        boolean botCheck = result.isBotCheckRequired();
        if (botCheck && result.getPlayabilityStatus() != null) {
            // Kept for the no-request answer; without the bidi marks the display helper adds, so
            // re-wrapping it there does not stack them.
            mBotWallReason = result.getPlayabilityStatus()
                    .replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", "").trim();
        }

        if (client == BotWallBook.ACCOUNT_ROUTE && !anonymous) {
            String failure = accountRouteFailure(result);
            if (failure != null) {
                logAccountRouteFailure(client, failure, keys.network(),
                        mBotWall.noteRouteFailed(keys.network(), noMediaVideoKey(videoId), failure,
                                nowMs));
            }
            return order;
        }

        if (anonymous && botCheck) {
            String network = keys.network();
            BotWallBook.Challenge outcome = mBotWall.noteChallenge(network, walk, client,
                    noMediaVideoKey(videoId), nowMs);
            if (outcome == BotWallBook.Challenge.ESTABLISHED) {
                android.util.Log.w("NetPath", "player-ring botwall established network=" + network
                        + " by=" + client + " walkChallenged=" + walk.clients()
                        + " attempts=" + attempt + " " + mBotWall.describe(network, nowMs));
            } else if (outcome == BotWallBook.Challenge.SUSPECT) {
                android.util.Log.d("NetPath", "player-ring botwall suspect client=" + client
                        + " network=" + network + " windowMs=" + BotWallBook.SUSPECT_WINDOW_MS);
            } else if (outcome == BotWallBook.Challenge.CONFIRMED) {
                android.util.Log.d("NetPath", "player-ring botwall confirmed client=" + client
                        + " network=" + network);
            }
            // The rest of THIS walk becomes the walled plan only on this walk's own strong
            // evidence (see WalkEvidence.isStrong); a wall from the two-video or probation rule
            // lets the remaining client families keep their turn in the walk that raised it.
            if (!walledAtStart && walk.isStrong() && mBotWall.isWalled(network, nowMs)
                    && walk.takeShortcut()) {
                BotWallBook.Plan plan = mBotWall.plan(network, authenticated,
                        noMediaVideoKey(videoId), nowMs);
                List<AppClient> updated = replaceRemaining(order, index, plan.order, attempted);
                android.util.Log.w("NetPath", "player-ring botwall shortcut network=" + network
                        + " attempts=" + attempt + " dropped=" + (order.size() - index - 1)
                        + " next=" + updated.subList(index + 1, updated.size()));
                order = updated;
            }
        }

        // "Next" literally: the account route may already sit later in the order (a recovery walk
        // asks its suspect last), so checking only for its absence would skip the move. In the old
        // ring that cost the Pixel VISIONOS + five challenged Web clients before it (2026-09-25,
        // botwall run B, open 2).
        if (authenticated && anonymous && result.isLoginRequired()
                && !attempted.contains(BotWallBook.ACCOUNT_ROUTE)
                && (index + 1 >= order.size() || order.get(index + 1) != BotWallBook.ACCOUNT_ROUTE)
                && !mBotWall.isRouteFailed(keys.network(), noMediaVideoKey(videoId), nowMs)) {
            order = insertAfter(order, index, BotWallBook.ACCOUNT_ROUTE);
            android.util.Log.d("NetPath", "player-ring account-route next reason="
                    + (botCheck ? "bot-check" : "login-required") + " after=" + client
                    + " attempt=" + attempt);
        }
        return order;
    }

    /**
     * NEWTUBE(kids-channel): an unhinted walk served {@code videoId} with {@code served}.
     * <ul>
     *   <li>The account route served it after VISIONOS or ANDROID_VR refused it on its content
     *   ({@code refusal}): the proof. The channel is the answer's own, else the refusal's, else the
     *   one the app named; with none of them it waits for the app to name it (/next).</li>
     *   <li>VISIONOS or ANDROID_VR served it: whatever this video's channel was remembered for, it
     *   is not a channel they refuse (a mixed channel, seen by a re-proof or an open the app named
     *   no channel for).</li>
     * </ul>
     */
    private static void noteKidsChannelServed(String videoId, PhoneSourcePlanner.Lane lane,
            AppClient client, VideoInfo served, @Nullable VideoInfo refusal, long generation) {
        if (hasLiveSignal(served)) {
            return;
        }
        if (client == PhoneSourcePlanner.ACCOUNT_ROUTE && refusal != null) {
            String channel = channelOf(served);
            String src = "answer";
            if (channel == null) {
                channel = channelOf(refusal);
                src = "refusal";
            }
            if (channel == null) {
                channel = sKidsChannels.channelOf(videoId);
                src = "named";
            }
            if (channel == null) {
                sKidsChannels.rememberWhenNamed(videoId, lane, generation);
                android.util.Log.d("NetPath", "kids-channel pending video=" + videoId
                        + " lane=" + laneName(lane) + " refusedBy=" + refusal.getClient());
            } else if (sKidsChannels.remember(lane, channel, generation)) {
                android.util.Log.d("NetPath", "kids-channel remember channel="
                        + KidsChannelMemory.tag(channel) + " video=" + videoId + " lane=" + laneName(lane)
                        + " src=" + src + " refusedBy=" + refusal.getClient()
                        + " hintsLeft=" + KidsChannelMemory.HINTS_PER_PROOF);
            } else {
                android.util.Log.d("NetPath", "kids-channel stale channel="
                        + KidsChannelMemory.tag(channel) + " video=" + videoId + " reason=account-changed");
            }
        } else if (PhoneSourcePlanner.refusesMadeForKids(client)) {
            String channel = channelOf(served);
            if (channel == null) {
                channel = sKidsChannels.channelOf(videoId);
            }
            if (sKidsChannels.drop(lane, channel, generation)) {
                android.util.Log.d("NetPath", "kids-channel drop channel=" + KidsChannelMemory.tag(channel)
                        + " video=" + videoId + " lane=" + laneName(lane) + " reason=served-by-" + client);
            }
        }
    }

    /** NEWTUBE(kids-channel): the answer's channel (videoDetails.channelId), or null. */
    @Nullable
    private static String channelOf(@Nullable VideoInfo result) {
        String channel = result != null && result.getVideoDetails() != null
                ? result.getVideoDetails().getChannelId() : null;
        return channel != null && !channel.isEmpty() ? channel : null;
    }

    /**
     * NEWTUBE(kids-channel): the refusal a made-for-kids video gets from VISIONOS and ANDROID_VR:
     * UNPLAYABLE without the account, and none of a bot check, an age gate, a live signal or a
     * verdict terminal for everyone (the refusal rule's own test, plus the challenge and the gate).
     */
    static boolean isContentRefusal(VideoInfo result) {
        return !result.isAuth() && result.isUnknownRestricted() && !result.isBotCheckRequired()
                && !result.isAgeGate() && !hasLiveSignal(result)
                && BotCheckDetector.definitiveUnplayableKey(result.getRawPlayabilityStatus(),
                        result.getPlayabilityStatus()) == null;
    }

    /** NEWTUBE(kids-channel): why the hinted account route did not serve, for the drop line. */
    static String kidsHintFailure(@Nullable VideoInfo result, boolean noResponse) {
        if (result == null) {
            return noResponse ? "no-response" : "error";
        }
        if (result.isBotCheckRequired()) {
            return "challenged";
        }
        if (result.isAgeGate()) {
            return "age-gate";
        }
        String routeFailure = accountRouteFailure(result);
        return routeFailure != null ? routeFailure : "refused";
    }

    /**
     * Why an account-bearing answer from {@link BotWallBook#ACCOUNT_ROUTE} proves the ROUTE cannot
     * serve, or null. Deliberately narrow: a video this account may not watch (private, removed,
     * members-only) is a verdict about the video and must not bench the route for everyone.
     */
    @Nullable
    static String accountRouteFailure(VideoInfo result) {
        if (result.isBotCheckRequired()) {
            return "challenged";
        }
        if (BotCheckDetector.isReloadPageVerdict(result.getRawPlayabilityStatus(),
                result.getPlayabilityStatus())) {
            return "reload-page";
        }
        if (result.getServerAbrStreamingUrl() != null && result.isAdaptiveFormatsBroken()
                && result.getDashManifestUrl() == null && result.getHlsManifestUrl() == null) {
            return "sabr-only";
        }
        return null;
    }

    /** The walk up to and including {@code index}, then whatever of {@code next} is still unasked. */
    static List<AppClient> replaceRemaining(List<AppClient> order, int index, List<AppClient> next,
            java.util.Set<AppClient> attempted) {
        List<AppClient> result = new java.util.ArrayList<>(order.subList(0, index + 1));
        for (AppClient client : next) {
            if (!attempted.contains(client) && !result.contains(client)) {
                result.add(client);
            }
        }
        return result;
    }

    /** {@code client} as the very next attempt (moved there if it was queued later). */
    static List<AppClient> insertAfter(List<AppClient> order, int index, AppClient client) {
        List<AppClient> result = new java.util.ArrayList<>(order.size() + 1);
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i) != client || i <= index) {
                result.add(order.get(i));
            }
            if (i == index) {
                result.add(client);
            }
        }
        return result;
    }

    /** The answer for a walled open that asks no one: YouTube's own reason, no media, no details. */
    private VideoInfo walledVerdict() {
        String reason = mBotWallReason;
        VideoInfo verdict = VideoInfo.botCheckVerdict(
                reason != null && !reason.isEmpty() ? reason : DEFAULT_BOT_CHECK_REASON, false);
        verdict.setClient(BotWallBook.PROBE_CLIENT);
        return verdict;
    }

    private String accountRouteState(@Nullable String network, String videoId) {
        long nowMs = android.os.SystemClock.elapsedRealtime();
        String failure = mBotWall.routeFailureReason(network, nowMs);
        if (failure != null) {
            return "failed:" + failure;
        }
        return mBotWall.isRouteFailed(network, noMediaVideoKey(videoId), nowMs)
                ? "benched-for-video" : "ok";
    }

    private static void logAccountRouteFailure(AppClient client, String reason,
            @Nullable String network, BotWallBook.RouteFailure outcome) {
        android.util.Log.w("NetPath", "player-ring account-route failed client=" + client
                + " reason=" + reason + " network=" + network
                + " scope=" + (outcome == BotWallBook.RouteFailure.ROUTE ? "attachment"
                        : outcome == BotWallBook.RouteFailure.VIDEO ? "video" : "none")
                + " ttlMs=" + BotWallBook.ROUTE_FAILURE_TTL_MS);
    }

    /** See mBotCheckProbePending: the walk is sending its first request now. */
    private void spendBotCheckProbeIfPending() {
        if (mBotCheckProbePending) {
            mBotCheckProbePending = false;
            mBotCheckNextProbeAtMs = android.os.SystemClock.elapsedRealtime()
                    + BOT_CHECK_PROBE_INTERVAL_MS;
        }
    }

    /**
     * NEWTUBE(botwall): the debug-only wall. {@code mode} is the {@code debug.arc.botwall} value:
     * {@code anon} walls every answer to a request that carried no account, {@code all} walls
     * every answer, and a comma-separated list of client names walls exactly those clients.
     * Empty, {@code none} and {@code 0} are off; unknown names are ignored.
     */
    static boolean shouldInjectBotWall(@Nullable String mode, AppClient client, boolean auth) {
        if (mode == null) {
            return false;
        }
        String value = mode.trim();
        if (value.isEmpty() || "none".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        if ("all".equalsIgnoreCase(value)) {
            return true;
        }
        if ("anon".equalsIgnoreCase(value)) {
            return !auth;
        }
        for (String name : value.split(",")) {
            if (client.name().equalsIgnoreCase(name.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Debug builds only: {@code debug.arc.botwall reset} forgets the wall book (and its stored
     * copy) and the bot-check circuit at the start of every walk while it is set, so a device
     * test can leave the phone exactly as it found it. Release builds have no source: no-op.
     */
    private void maybeResetBotWallForDebug() {
        DebugBotWallSource source = sDebugBotWall;
        if (source == null) {
            return;
        }
        String mode;
        try {
            mode = source.mode();
        } catch (RuntimeException e) {
            return;
        }
        if (mode != null && "reset".equalsIgnoreCase(mode.trim())) {
            boolean had = mBotWall.clearAll();
            clearBotCheckCircuit();
            android.util.Log.w("NetPath", "debug-botwall reset had=" + (had ? "y" : "n"));
        }
    }

    /** Debug builds only: replace a real answer with the wall's, keeping what WE sent. */
    @Nullable
    private static VideoInfo maybeInjectBotWall(AppClient client, @Nullable VideoInfo result) {
        DebugBotWallSource source = sDebugBotWall;
        if (source == null || result == null) {
            return result;
        }
        String mode;
        try {
            mode = source.mode();
        } catch (RuntimeException e) {
            return result;
        }
        if (!shouldInjectBotWall(mode, client, result.isAuth())) {
            return result;
        }
        android.util.Log.w("NetPath", "debug-botwall client=" + client + " mode=" + mode
                + " auth=" + (result.isAuth() ? "y" : "n") + " realStatus="
                + result.getRawPlayabilityStatus() + " -> LOGIN_REQUIRED");
        return VideoInfo.botCheckVerdict(DEBUG_BOT_CHECK_REASON, result.isAuth());
    }

    /**
     * NEWTUBE(walk-role): the user opened a video whose answer a speculative walk fetched (a preload
     * flight the open joined, or its cached result), so that answer's client is now the watched
     * one: the state that walk left alone is set as the watched video's own walk would have set it.
     * Not synchronized - an open served from the cache must not wait behind another walk.
     */
    public void adoptSpeculativeResult(String videoId, boolean unplayable, boolean live) {
        mIsUnplayable = unplayable;
        AppClient winner = videoId != null && !unplayable ? mVideoWinners.get(videoId) : null;
        if (winner == null || winner == mActualInfoType) {
            return;
        }
        mActualInfoType = winner;
        android.util.Log.d("NetPath", "player-ring speculative-adopted video=" + videoId
                + " client=" + winner);
        if (!(sPreferDashManifestForLive && live)) {
            persistVideoInfoType();
        }
    }

    /** See {@link #mVideoWinners}. Only a playable answer names a route worth blaming later. */
    private void rememberVideoWinner(String videoId, @Nullable VideoInfo result) {
        if (videoId != null && result != null && !result.isUnplayable() && result.getClient() != null) {
            mVideoWinners.put(videoId, result.getClient());
        }
    }

    /**
     * NEWTUBE(recovery-blame): pin the recovery that is about to run - the 403 quarantine / bot-wall
     * route memory ({@link #markCurrentPlaybackRouteForbidden}) and the recovery cursor
     * ({@link #switchNextFormat}) - to the client that actually served {@code videoId}. Both used to
     * read mActualInfoType, which a next-video prefetch overwrites; and anchoring by writing that
     * same field was still racy, because a prefetch could land between the anchor and the read.
     * The anchor is its own field, consumed by switchNextFormat. An unknown video (a cached answer
     * from an older process) leaves no anchor, i.e. the historical behaviour.
     */
    public void anchorRouteToVideo(@Nullable String videoId) {
        AppClient winner = videoId != null ? mVideoWinners.get(videoId) : null;
        if (winner == null) {
            mRouteAnchor = null;
            return;
        }
        AppClient current = mActualInfoType;
        if (winner != current) {
            android.util.Log.w("NetPath", "player-ring route-anchor video=" + videoId
                    + " client=" + winner + " was=" + current);
        }
        mRouteAnchor = new RouteAnchor(videoId, winner);
    }

    /**
     * The current network ATTACHMENT key ({@code transport:netIdHash}), or null offline. Public for
     * caches whose entries are only valid on the attachment they were minted on (googlevideo URLs
     * carry the public IP they were issued to).
     */
    @Nullable
    public static String currentNetworkKey() {
        return activeNetworkKey();
    }


    /**
     * Whether any client AFTER {@code index} can still answer despite the anonymous web identity
     * being challenged. Web-pot clients all share that identity, so they are not candidates; every
     * other client either carries the account or presents a different platform identity.
     */
    static boolean hasUnchallengedClientAfter(List<AppClient> order, int index,
            boolean authenticated) {
        for (int i = index + 1; i < order.size(); i++) {
            AppClient candidate = order.get(i);
            if (candidate.isWebPotRequired()) {
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
     * The other shape of "this account route cannot serve media": a response whose only delivery
     * is SABR.
     *
     * <p>This build has no SABR streaming source (see the phone port's HANDOFF -- the accepted TV
     * response needs a protocol module the Media3 port does not have, and adding it is a real
     * integration, not a classification change). So an authenticated head that answers with a
     * serverAbrStreamingUrl, broken adaptive formats and nothing else playable has told us it
     * cannot serve THIS build, exactly as conclusively as an empty response does.
     *
     * <p>Kept separate from {@link #isAuthRouteReloadVerdict} on purpose: that one is about a
     * server outage returning nothing at all, this one is about a capability we do not have. Both
     * feed the same two-different-videos streak, so a per-video SABR rollout still cannot quarantine
     * a route on one observation.
     *
     * <p>Package-private for {@link AuthRouteSabrOnlyVerdictTest}.
     */
    static boolean isAuthRouteSabrOnlyVerdict(AppClient client, @Nullable VideoInfo result) {
        if (com.liskovsoft.youtubeapi.videoinfo.models.SabrVodCapability.accepts(result)) {
            return false; // Optional VOD engine can consume this accepted response; not a route failure.
        }
        if (result == null || !client.isAuthSupported() || !result.isAuth()
                || result.getServerAbrStreamingUrl() == null
                || !result.isAdaptiveFormatsBroken()) {
            return false;
        }

        // A restricted video is a verdict about the VIDEO. Only the SABR signature above may
        // speak for the route, so age/geo/visibility gates are excluded even though
        // VideoInfo.isUnplayable() lumps them in with broken formats.
        if (result.isUnknownRestricted() || result.isVisibilityRestricted()
                || result.isAgeRestricted()) {
            return false;
        }

        // A manifest IS a delivery this build can play, so its presence means the response was
        // usable and the route is fine. A lone progressive format is not the same thing: the walk
        // already rejects a broken-adaptive response (VideoInfo.isUnplayable) and never selects
        // it, which is exactly the observed TV shape - formats=22+1, usableAdaptive=0, sabr=y.
        return result.getDashManifestUrl() == null && result.getHlsManifestUrl() == null;
    }

    /**
     * Tracks {@link #isAuthRouteReloadVerdict} per account-bearing client and quarantines the route
     * once the same shape has come back for {@link #AUTH_RELOAD_QUARANTINE_MIN_HITS} different
     * videos. Any other outcome from that client clears its streak, so the route is held down only
     * while it is actually refusing everything.
     */
    private void noteAuthRouteVerdict(AppClient client, String videoId,
            @Nullable VideoInfo result, AuthRouteWalkState authRoute) {
        boolean sabrOnly = isAuthRouteSabrOnlyVerdict(client, result);
        if (!sabrOnly && !isAuthRouteReloadVerdict(client, result)) {
            // Direct evidence the route is healthy: it answered this client with something. A null
            // result (timeout, dead link) is NOT an answer and says nothing either way - it used to
            // clear the streak too, which is harmless in memory but would now also cost a write.
            if (result != null) {
                clearAuthRouteNoMediaStreak(client);
            }
            return;
        }

        boolean reloadPage = !sabrOnly && BotCheckDetector.isReloadPageVerdict(
                result.getRawPlayabilityStatus(), result.getPlayabilityStatus());
        // The SABR-only shape already excludes every gate; see AuthRouteWalkState.Held#gated.
        boolean gated = !sabrOnly && (result.isAgeRestricted() || result.isVisibilityRestricted());
        if (!authRoute.hold(client, videoId, reloadPage, gated)) {
            android.util.Log.d("NetPath", "player-ring auth-route held client=" + client
                    + " video=" + videoId + " shape=" + (sabrOnly ? "sabr-only" : "empty")
                    + " reloadPage=" + (reloadPage ? "y" : "n") + (gated ? " gated=y" : ""));
            return;
        }
        countAuthRouteVerdict(client, new AuthRouteWalkState.Held(videoId, reloadPage, gated),
                authRoute.accountGeneration);
    }

    /**
     * Counts one PROVEN auth-route no-media verdict and quarantines the route once the same shape
     * has come back for {@link #AUTH_RELOAD_QUARANTINE_MIN_HITS} different videos. Proven means a
     * client served the video in the same walk - see {@link AuthRouteWalkState}. Any other outcome
     * from that client clears its streak, so the route is held down only while it is actually
     * refusing videos that demonstrably play.
     * <p>
     * NEWTUBE(auth-route): the streak is held per transport in {@link AuthRouteQuarantineBook} and
     * persisted with the quarantine, so two cold opens (one video each) add up instead of each
     * ending at {@code hits=1/2}; and a client with a remembered strike is on probation - its first
     * proven verdict re-quarantines it with escalation. Policy: {@link AuthRouteQuarantineBook#noteNoMedia}.
     */
    private void countAuthRouteVerdict(AppClient client, AuthRouteWalkState.Held held,
            long walkAccountGeneration) {
        restoreAuthRouteQuarantineOnce();
        String transport = activeTransportKey();
        String reloadPage = (held.reloadPage ? "y" : "n") + (held.gated ? " gated=y" : "");
        if (transport == null) {
            // Nothing to key it on - the quarantine it could lead to would be dropped as well.
            android.util.Log.d("NetPath", "player-ring auth-route no-media client=" + client
                    + " reloadPage=" + reloadPage + " counted=n reason=no-network");
            return;
        }

        long nowElapsedMs = android.os.SystemClock.elapsedRealtime();
        AuthRouteQuarantineBook.NoMediaOutcome outcome;
        // Checked and counted under the book's lock, which onAccountChanged also holds while it
        // bumps the generation and drops the streaks: a walk that began under the previous account
        // cannot slip its verdict (or a probation escalation) in as the new account's evidence.
        synchronized (mAuthRouteQuarantine) {
            if (walkAccountGeneration != mAccountGeneration) {
                android.util.Log.d("NetPath", "player-ring auth-route no-media client=" + client
                        + " reloadPage=" + reloadPage + " counted=n reason=account-changed");
                return;
            }
            outcome = mAuthRouteQuarantine.noteNoMedia(transport, client,
                    noMediaVideoKey(held.videoId), !held.gated, nowElapsedMs,
                    System.currentTimeMillis());
        }
        switch (outcome.kind) {
            case DUPLICATE:
                // Same video twice (a reload, a retry) is one piece of evidence, not two.
                android.util.Log.d("NetPath", "player-ring auth-route no-media client=" + client
                        + " hits=" + outcome.hits + "/" + AUTH_RELOAD_QUARANTINE_MIN_HITS
                        + " reloadPage=" + reloadPage + " network=" + transport + " duplicate=y");
                return;
            case COUNTED:
                android.util.Log.d("NetPath", "player-ring auth-route no-media client=" + client
                        + " hits=" + outcome.hits + "/" + AUTH_RELOAD_QUARANTINE_MIN_HITS
                        + " reloadPage=" + reloadPage + " network=" + transport
                        + " persisted=" + (persistAuthRouteQuarantine() ? "y" : "n"));
                return;
            case QUARANTINED:
            default:
                boolean persisted = persistAuthRouteQuarantine();
                if (outcome.probation) {
                    android.util.Log.d("NetPath", "player-ring auth-route no-media client=" + client
                            + " probation=y priorStrikes=" + outcome.previousStrikes
                            + " reloadPage=" + reloadPage + " network=" + transport
                            + " persisted=" + (persisted ? "y" : "n"));
                } else {
                    android.util.Log.d("NetPath", "player-ring auth-route no-media client=" + client
                            + " hits=" + outcome.hits + "/" + AUTH_RELOAD_QUARANTINE_MIN_HITS
                            + " reloadPage=" + reloadPage + " network=" + transport
                            + " persisted=" + (persisted ? "y" : "n"));
                }
                logAuthRouteQuarantined(client,
                        outcome.probation ? "no-media-probation" : "no-media-verdict", transport,
                        outcome.record, outcome.wasLive, nowElapsedMs);
        }
    }

    /**
     * The client answered with something other than a no-media verdict, so any partial streak it
     * had on the active transport is stale. Written through only when there was one to drop, so a
     * healthy walk never touches the store.
     */
    private void clearAuthRouteNoMediaStreak(AppClient client) {
        if (!mAuthRouteQuarantine.hasNoMediaStreak(client)) {
            return;
        }
        String transport = activeTransportKey();
        if (mAuthRouteQuarantine.clearNoMedia(transport, client)) {
            android.util.Log.d("NetPath", "player-ring auth-route no-media-cleared client=" + client
                    + " network=" + transport
                    + " persisted=" + (persistAuthRouteQuarantine() ? "y" : "n"));
        }
    }

    /**
     * What the streak remembers of a video: a short hash, because only equality is used and the
     * persisted value then carries no watch history. A collision can only make two different
     * videos count once - the conservative direction.
     */
    static String noMediaVideoKey(String videoId) {
        return Integer.toHexString(videoId.hashCode());
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
        PlayabilityLog.log(videoId, client, attempt, result); // debug builds: exact replay fixtures
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
     * NEWTUBE(auth-route): when exactly ONE account head is quarantined, move it from attempt 2
     * to immediately BEHIND {@link #PREFERRED_FIRST_CLIENT}. The healthy sibling keeps attempt 1,
     * so the account still gets its turn first; what changes is the fall-through.
     * <p>
     * {@link #promoteAuthenticatedTvFallback} keeps a quarantined head at attempt 2 on the theory
     * that an account route is a better bet than an anonymous one. HANDOFF section 26 retired that
     * theory for TVHTML5: it has no working configuration today, so a quarantined head reached at
     * attempt 2 either burns a /player on a SABR-only answer (TV) or WINS with URLs that 403 on the
     * first byte (TV_DOWNGRADED) - a media 403, a quarantine refresh and a player reload - while
     * VISIONOS, one slot later, would have played. The measured cost of the TV_DOWNGRADED case is
     * the 5.15-5.48 s vs 2.80 s first frame of section 26.
     * <p>
     * Only reordered, never dropped: the demoted head still precedes the whole web-pot partition,
     * so a video only the account can serve (VISIONOS answers LOGIN_REQUIRED) still reaches it
     * before any anonymous Web client. The section 17/18 safeguards are untouched: observations are
     * still held by AuthRouteWalkState until some client serves the video, and restricted videos
     * still never count against the route. A FULLY quarantined head is not handled here - that is
     * authenticatedWebFirst, which already leads with the token-free client. Mobile only, like the
     * token-free injection it anchors on.
     */
    static List<AppClient> demoteQuarantinedHeadBehindTokenFreeClient(List<AppClient> order,
            @Nullable java.util.Set<AppClient> forbidden) {
        if (forbidden == null || forbidden.isEmpty()) {
            return order;
        }

        java.util.List<AppClient> demoted = new java.util.ArrayList<>(AUTHENTICATED_HEAD.length);
        boolean healthySibling = false;
        for (AppClient head : AUTHENTICATED_HEAD) {
            if (!forbidden.contains(head)) {
                healthySibling = true;
            } else if (order.contains(head)) {
                demoted.add(head);
            }
        }
        if (!healthySibling || demoted.isEmpty() || !order.contains(PREFERRED_FIRST_CLIENT)) {
            return order;
        }

        List<AppClient> result = new java.util.ArrayList<>(order.size());
        for (AppClient client : order) {
            if (demoted.contains(client)) {
                continue;
            }
            result.add(client);
            if (client == PREFERRED_FIRST_CLIENT) {
                result.addAll(demoted);
            }
        }
        return result;
    }

    /** One NetPath line per walk in which a quarantined head sits behind the token-free client. */
    private static void logHeadDemotedBehindTokenFreeClient(List<AppClient> order,
            java.util.Set<AppClient> forbidden) {
        int anchor = order.indexOf(PREFERRED_FIRST_CLIENT);
        if (anchor < 0) {
            return;
        }
        for (AppClient client : forbidden) {
            int at = order.indexOf(client);
            if (at > anchor) {
                android.util.Log.d("NetPath", "player-ring auth-head-demoted client=" + client
                        + " behind=" + PREFERRED_FIRST_CLIENT + " orderIndex=" + at
                        + " first=" + order.get(0));
            }
        }
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
            // preferWebFamily was the phone's ring before PhoneSourcePlanner; TV passes false.
            if (preferWebFamily) {
                order = insertTokenFreeClientBeforeWebPot(order);
                order = demoteQuarantinedHeadBehindTokenFreeClient(order, forbiddenAuthClients);
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
        }
        // Applied LAST, so it also overrides an attested-web-first or authenticated-web-first
        // preference: if the account head is exhausted AND the guest identity is challenged, the
        // non-web platform clients are all that is left worth spending a round trip on.
        return anonChallenged ? deprioritizeWebPotClients(order) : order;
    }

    /**
     * Called only after the player surfaced an actual HTTP 403. It remembers a failed
     * account-bearing route against the current transport. The phone flavor also writes that
     * memory through {@link AuthRouteQuarantineStore} so a cold start does not re-probe a route
     * this device just proved dead; the stored value is a transport name (wifi/cell/...), a client
     * name, an expiry, a strike count and when that strike was armed - no network identifiers.
     */
    public void markCurrentPlaybackRouteForbidden() {
        // NEWTUBE(recovery-blame): the anchored video's client when the player pinned one.
        RouteAnchor anchor = mRouteAnchor;
        AppClient failedClient = anchor != null ? anchor.client : mActualInfoType;
        // NEWTUBE(botwall): the account route is not an AUTHENTICATED_HEAD member (see the
        // isAuthSupported note below), so its media 403 is remembered by BotWallBook instead of
        // the head quarantine: benched for that video (so the recovery reload does not re-buy it),
        // and for the attachment once a second video fails too.
        if (failedClient == BotWallBook.ACCOUNT_ROUTE) {
            String network = activeNetworkKey();
            logAccountRouteFailure(failedClient, "media-403", network,
                    mBotWall.noteRouteFailed(network,
                            anchor != null ? noMediaVideoKey(anchor.videoId) : null, "media-403",
                            android.os.SystemClock.elapsedRealtime()));
            return;
        }
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
     * Called on every sign-in, account switch and removal (YouTubeAccountManager). The partial
     * no-media streaks were counted for the previous account and are persisted, so without this a
     * hit under account A plus one under account B would quarantine B's route - across restarts,
     * too. Quarantine records are left alone (client-level, see
     * {@link AuthRouteQuarantineBook#clearAllNoMedia}).
     */
    public void onAccountChanged() {
        restoreAuthRouteQuarantineOnce();
        boolean cleared;
        synchronized (mAuthRouteQuarantine) {
            mAccountGeneration++; // walks already in flight belong to the previous account
            cleared = mAuthRouteQuarantine.clearAllNoMedia();
        }
        if (cleared) {
            android.util.Log.d("NetPath", "player-ring auth-route no-media-cleared"
                    + " reason=account-change persisted="
                    + (persistAuthRouteQuarantine() ? "y" : "n"));
        }
        // NEWTUBE(botwall): same for the bot-wall account route's failures (the wall itself is
        // about the anonymous clients on this network and stays).
        if (mBotWall.clearRouteFailures()) {
            android.util.Log.d("NetPath", "player-ring account-route cleared reason=account-change");
        }
        // NEWTUBE(kids-channel): the records were the previous account's (or lane's) evidence.
        int kidsChannels = sKidsChannels.clear();
        if (kidsChannels > 0) {
            android.util.Log.d("NetPath", "kids-channel cleared records=" + kidsChannels
                    + " reason=account-change");
        }
    }

    /**
     * Demotes one account-bearing client on the ACTIVE transport. Shared by the media-403 evidence
     * path and the no-media-verdict path so both keep identical keying, strike and TTL semantics:
     * a first strike holds {@link AuthRouteQuarantineBook#BASE_TTL_MS}, each re-quarantine of the
     * same client multiplies it (see {@link AuthRouteQuarantineBook#quarantine}).
     */
    private void quarantineAuthRoute(AppClient failedClient, String reason) {
        restoreAuthRouteQuarantineOnce();
        String transport = activeTransportKey();
        if (transport == null) {
            return;
        }

        long nowElapsedMs = android.os.SystemClock.elapsedRealtime();
        boolean wasLive = mAuthRouteQuarantine.active(transport, nowElapsedMs)
                .contains(failedClient);
        AuthRouteQuarantineBook.Record record = mAuthRouteQuarantine.quarantine(
                transport, failedClient, nowElapsedMs, System.currentTimeMillis());
        persistAuthRouteQuarantine();
        logAuthRouteQuarantined(failedClient, reason, transport, record, wasLive, nowElapsedMs);
    }

    /** The one quarantine line, shared by the media-403 and no-media paths. */
    private void logAuthRouteQuarantined(AppClient client, String reason, String transport,
            @Nullable AuthRouteQuarantineBook.Record record, boolean wasLive, long nowElapsedMs) {
        if (record == null) {
            return;
        }
        android.util.Log.w("NetPath", "player-ring quarantine-auth-route client=" + client
                + " reason=" + reason
                + " network=" + transport
                + " strike=" + record.strikes
                // refresh = it was still quarantined (same episode), so no escalation.
                + " escalation=" + (wasLive ? "refresh" : (record.strikes > 1 ? "up" : "first"))
                + " cooldownMs=" + (record.untilElapsedMs - nowElapsedMs)
                + " quarantined=" + mAuthRouteQuarantine.active(transport, nowElapsedMs).size()
                + "/" + AUTHENTICATED_HEAD.length);
    }

    /**
     * Account-bearing clients currently quarantined on the ACTIVE transport. Never null. Expired
     * records are kept while their strike memory lasts (so the next failure escalates) and pruned
     * after; another transport's records are left alone rather than wiped, so moving between
     * Wi-Fi and cellular never evicts the verdict the next open on the other one depends on.
     */
    private java.util.Set<AppClient> forbiddenAuthClients() {
        restoreAuthRouteQuarantineOnce();
        if (mAuthRouteQuarantine.isEmpty()) {
            return java.util.Collections.emptySet();
        }

        long nowElapsedMs = android.os.SystemClock.elapsedRealtime();
        if (mAuthRouteQuarantine.prune(nowElapsedMs, System.currentTimeMillis())) {
            persistAuthRouteQuarantine();
        }
        return mAuthRouteQuarantine.active(activeTransportKey(), nowElapsedMs);
    }

    /** @return whether a store was there to write to (phones); TV keeps it process-local. */
    private boolean persistAuthRouteQuarantine() {
        AuthRouteQuarantineStore store = sAuthRouteQuarantineStore;
        if (store == null) {
            return false;
        }

        // Encode and save as one step: the player thread (403) and the walk (prune) both persist,
        // and an older encode landing after a newer one would silently drop a fresh strike.
        synchronized (mAuthRouteQuarantine) {
            store.save(AuthRouteQuarantineSnapshot.encode(mAuthRouteQuarantine.records(),
                    mAuthRouteQuarantine.streaks(),
                    android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis()));
        }
        return true;
    }

    private void restoreAuthRouteQuarantineOnce() {
        if (mAuthRouteQuarantineRestored) {
            return;
        }
        // The player thread (media 403) can race the walk here; restore exactly once.
        synchronized (mAuthRouteQuarantine) {
            if (mAuthRouteQuarantineRestored) {
                return;
            }
            mAuthRouteQuarantineRestored = true;

            AuthRouteQuarantineStore store = sAuthRouteQuarantineStore;
            if (store == null) {
                return;
            }

            String saved = store.load();
            long nowElapsedMs = android.os.SystemClock.elapsedRealtime();
            long nowWallMs = System.currentTimeMillis();
            AuthRouteQuarantineSnapshot.Decoded restored =
                    AuthRouteQuarantineSnapshot.decodeAll(saved, AUTHENTICATED_HEAD, nowElapsedMs,
                            nowWallMs);
            String format = AuthRouteQuarantineSnapshot.formatName(saved);
            String transport = activeTransportKey();
            if (restored.isEmpty()) {
                // Nothing survived: forgotten strikes, stale streaks, an unreadable value, or
                // nothing stored. Logged anyway, so a device trace proves the restore ran.
                if (saved != null) {
                    store.save(null);
                }
                android.util.Log.d("NetPath", "player-ring restore-auth-route-quarantine network="
                        + transport + " quarantined=0/" + AUTHENTICATED_HEAD.length
                        + " format=" + format + " records=none streaks=none"
                        + (saved != null ? " dropped=y" : ""));
                return;
            }

            mAuthRouteQuarantine.restore(restored.records, restored.streaks);
            if (!AuthRouteQuarantineSnapshot.isCurrentFormat(saved)) {
                persistAuthRouteQuarantine(); // migrate a v2 / legacy (1.9.0) value in place
            }
            android.util.Log.d("NetPath", "player-ring restore-auth-route-quarantine network="
                    + transport
                    + " quarantined=" + mAuthRouteQuarantine.active(transport, nowElapsedMs).size()
                    + "/" + AUTHENTICATED_HEAD.length
                    + " format=" + format
                    + " records=" + mAuthRouteQuarantine.describe(nowElapsedMs)
                    // Expired but remembered on this transport: one proven no-media verdict
                    // re-quarantines them (see AuthRouteQuarantineBook.noteNoMedia).
                    + " probation=" + mAuthRouteQuarantine.probation(transport, nowElapsedMs,
                            nowWallMs)
                    + " streaks=" + mAuthRouteQuarantine.describeStreaks(nowWallMs));
        }
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
            boolean rotated = sRotateVisitorOnAnonChallenge && rotateAnonymousIdentity();
            android.util.Log.w("NetPath", "player-ring anon-challenged network=" + network
                    + " hits=" + hits + " cooldownMs=" + ANON_CHALLENGE_COOLDOWN_MS
                    + " visitorRotated=" + (rotated ? "y" : "n"));
        }
    }

    /**
     * Starts a NEW anonymous identity for the /player web-pot session after the guest partition is
     * challenged. NOT enabled on the phone (see {@link #setRotateVisitorOnAnonChallenge}): kept as
     * the least destructive variant should device evidence ever show an identity-bound challenge.
     * <p>
     * NEWTUBE(visitor), evaluated 2026-09-25: rotation did not rescue either observed wall, and
     * what causes these walls (IP, client, attestation, session, or their interaction) is unproven.
     * <ul>
     * <li>2026-07-27 Pixel/LTE: 42/42 anonymous calls challenged on one visitor with a valid pot; a
     * probe of all 7 anonymous clients with a BRAND-NEW visitor was challenged 7/7 as well.</li>
     * <li>2026-07-28 Mac: VISIONOS OK while ANDROID_VR and TV_DOWNGRADED were challenged in the same
     * second on the same IP - a client effect (which does not exclude a visitor effect).</li>
     * <li>2026-09-07 off-device: challenged with NO visitorData, OK with a freshly minted one. Like
     * NewPipe's missing / locally generated visitor data, that shows visitor VALIDITY matters
     * (present and server-issued, which the persistent one is) - not that replacing a valid
     * visitor helps.</li>
     * <li>2026-09-25 Pixel/LTE wall: this used to call {@code AppService.rotateVisitorData()} (the
     * 09-07 owner decision, HANDOFF section 26: visitor cookie + app info + web-pot session). It
     * rotated seven times in about two minutes (this cooldown is per process, so every cold start
     * rotated again), and none of the 140 anonymous /player answers that followed was OK (133 bot
     * checks, 7 WEB_EMBED/GEO errors). Correlated retries in one episode, with no wait-only
     * control - but each rotation did move Home, /next, search and signed-out history onto a new
     * identity, and the next cold start paid an extra youtube.com/tv fetch because the persisted
     * app info was never re-saved.</li>
     * </ul>
     * So the cost is certain and the benefit unshown; the phone keeps its identity and relies on the
     * anonymous cooldown (which reorders the ring) and BotWallBook (which limits walled requests).
     * Settling it needs natural walls with a matched wait-only control (the web-pot-session NetPath
     * line records the token context for that).
     * <p>
     * Re-enabling is one line in MobileMainApplication: {@code setRotateVisitorOnAnonChallenge(true)}.
     * This variant then rotates ONLY the web-pot session's visitor (what VISIONOS, ANDROID_VR and
     * the Web family present), never the persistent AppService visitor; persist its cooldown per
     * network first, or every cold start under a wall rotates again.
     */
    private boolean rotateAnonymousIdentity() {
        try {
            // Arms a fresh visitor for the next web-pot session and retires the current token
            // session, so the challenged identity is not handed straight back to the clients that
            // were just challenged. Returns false when rate-limited or without a WebView.
            return PoTokenGate.rotateWebVisitor();
        } catch (Exception e) {
            android.util.Log.w("NetPath", "player-ring visitor-rotate-failed " + e);
            return false;
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

    /**
     * One specific network ATTACHMENT ({@code transport:netIdHash}); a reconnect is a new key.
     * Right for the anonymous-challenge memory, which is a joint verdict on (client, identity,
     * IP) - HANDOFF section 26 - and so genuinely may not survive a new public IP.
     */
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
            return transportName(caps) + ':' + network.hashCode();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * NEWTUBE(auth-route): the transport alone (wifi / cell / vpn / ethernet / other), which is
     * what the account-route quarantine is keyed on. Deliberately NOT {@link #activeNetworkKey}:
     * that changes on every reconnect, and the auth-route 403 is not a property of the attachment
     * or its IP - section 26 reproduced it from a laptop on a different IP and traced it to the
     * TVHTML5 request's signatureTimestamp suffix. See AuthRouteQuarantineBook.
     */
    @Nullable
    private static String activeTransportKey() {
        try {
            Context context = AppService.instance().getContext();
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network = manager != null ? manager.getActiveNetwork() : null;
            NetworkCapabilities caps = network != null
                    ? manager.getNetworkCapabilities(network) : null;
            return caps != null ? transportName(caps) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String transportName(NetworkCapabilities caps) {
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "vpn"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "wifi"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "cell"
                : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ethernet"
                : "other";
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

        // NEWTUBE(botwall): the challenge was observed on another network attachment; what it says
        // about this one is nothing. Armed on a walled LTE, it used to keep answering opens on a
        // healthy Wi-Fi until the next permitted probe. Mobile only; an unknown network (offline)
        // keeps the circuit as it was.
        if (sPreferNoPotClient && isBotCheckCircuitStale(mBotCheckNetwork, activeNetworkKey())) {
            android.util.Log.d("NetPath", "bot-check cleared reason=network-change video=" + videoId
                    + " armedOn=" + mBotCheckNetwork);
            clearBotCheckCircuit();
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
            // NEWTUBE(walk-role): the one probe per interval re-tests the server for the user's
            // own open; a preload taking it left that open answered from the cooldown.
            if (nowMs - mBotCheckNextProbeAtMs >= 0 && mWalkRole != WalkRole.SPECULATIVE) {
                // NEWTUBE(botwall): spent when the walk SENDS its first request, not here - a
                // canceled tap-time prefetch must not use up the interval's only probe.
                mBotCheckProbePending = true;
                android.util.Log.d("NetPath", "bot-check probe video=" + videoId
                        + " remainingMs=" + remainingMs);
                return null;
            }
        }

        android.util.Log.w("NetPath", "bot-check cooldown video=" + videoId
                + " remainingMs=" + remainingMs + " network=n");
        return result;
    }

    /**
     * NEWTUBE(classification): whether a challenge may trip the circuit. An explicit bot text may.
     * The other signal - the same LOGIN_REQUIRED text from two clients, the fallback for a localized
     * bot text (BotCheckDetector.isRepeatedLoginRequired) - is also exactly what a PRIVATE video
     * answers: on the Pixel (2026-09-29, yZIXLfi8CZQ, Spanish) VISIONOS and ANDROID_VR both said
     * "Inicia sesión", the walk armed the fifteen-minute circuit, the private video was reported as
     * "confirm you're not a bot" and the next opens were answered from the cooldown. Two tests tell
     * them apart, neither of them reading the text:
     * <ul>
     *   <li>A challenge is worded the same by every client it reaches (Pixel LTE wall, 2026-09-29:
     *   WEB_EMBED, IOS and ANDROID_REEL gave one sentence), while a private video's sign-in requests
     *   differ by client ("Inicia sesión", "Este vídeo es privado…", "…si tienes acceso a este
     *   vídeo"). A walk whose sign-in requests disagree ({@code reasonsAgree} false) is about the
     *   video: it neither trips nor counts towards a later confirmation.</li>
     *   <li>A challenge of the identity repeats across videos; a video's own refusal does not. So
     *   the signal trips the circuit only when a second video repeats it within
     *   REPEATED_LOGIN_CONFIRM_MS; the first is published as the video's refusal.</li>
     * </ul>
     * Mobile only: TV trips as it always did.
     */
    private boolean isChallengeConfirmed(String signal, String videoId, boolean reasonsAgree) {
        if (!sPreferNoPotClient || !SIGNAL_REPEATED_LOGIN.equals(signal)) {
            return true;
        }
        if (!reasonsAgree) {
            android.util.Log.d("NetPath", "bot-check repeated-login video=" + videoId
                    + " unconfirmed reason=sign-in-requests-differ");
            return false;
        }
        long nowMs = android.os.SystemClock.elapsedRealtime();
        String earlier = mRepeatedLoginVideo;
        boolean confirmed = earlier != null && !earlier.equals(videoId)
                && nowMs - mRepeatedLoginAtMs <= REPEATED_LOGIN_CONFIRM_MS;
        mRepeatedLoginVideo = videoId;
        mRepeatedLoginAtMs = nowMs;
        android.util.Log.d("NetPath", "bot-check repeated-login video=" + videoId
                + (confirmed ? " confirmed-by=" + earlier : " unconfirmed reason=one-video"));
        return confirmed;
    }

    private void tripBotCheckCircuit(VideoInfo result, AppClient client, String signal,
            boolean authenticatedAttempted, boolean ringExhausted) {
        result.setBotCheckRequired(true);
        mBotCheckResult = result;
        mBotCheckCooldownUntilMs = android.os.SystemClock.elapsedRealtime() + BOT_CHECK_COOLDOWN_MS;
        mBotCheckAuthenticatedAttempted = authenticatedAttempted;
        mBotCheckRingExhausted = ringExhausted;
        mBotCheckNetwork = sPreferNoPotClient ? activeNetworkKey() : null;
        mBotCheckNextProbeAtMs =
                android.os.SystemClock.elapsedRealtime() + BOT_CHECK_PROBE_INTERVAL_MS;
        // NEWTUBE(walk-role): the challenge is network evidence whoever saw it; the cursor is the
        // watched video's, and only its own walk may drop it.
        if (mWalkRole != WalkRole.SPECULATIVE) {
            mNextInfoType = null;
            mRecoveryWalk = false;
            mRecoverySuspect = null;
        }
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
        mBotCheckNetwork = null;
    }

    /** Armed on a known attachment and the device is now on a DIFFERENT known one. */
    static boolean isBotCheckCircuitStale(@Nullable String armedOn, @Nullable String current) {
        return armedOn != null && current != null && !armedOn.equals(current);
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
        // NEWTUBE(botwall): when the account route is asked it is the one route left (a walled
        // network, or the anonymous identity just asked for a sign-in), so it gets the head's
        // cold-start budget rather than a speculative client's.
        if (client == BotWallBook.ACCOUNT_ROUTE) {
            return AUTH_HEAD_ATTEMPT_TIMEOUT_MS;
        }

        // NEWTUBE(source-catalog): otherwise the source's own budget.
        if (PlayerSourceCatalog.defaultFor(client).budget == PlayerSource.Budget.WEB_POT) {
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
        // NEWTUBE(recovery-blame): the anchored video's client, consumed here (see
        // anchorRouteToVideo). An anchor only exists for a video that PLAYED, so it also answers
        // "was it playable" for that video instead of the global flag a prefetch may have set.
        RouteAnchor anchor = mRouteAnchor;
        mRouteAnchor = null;
        final AppClient suspect = anchor != null ? anchor.client : mActualInfoType;
        final boolean unplayable = anchor == null && mIsUnplayable;
        if (!unplayable && (suspect == AppClient.ANDROID_VR
                || suspect == PREFERRED_FIRST_CLIENT)) {
            PoTokenGate.resetCache();
            nextVideoInfoType(suspect);
            android.util.Log.d("NetPath", "player-ring circuit-break suspect=" + suspect
                    + " next=" + mNextInfoType);
            return;
        }

        // Try to reset pot cache for the last video
        if (!unplayable && suspect != null && PoTokenGate.resetCache(suspect)) {
            return;
        }
        // The Premium is likely broken
        //if (getData().isFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS)) {
        //    // Skip additional formats fetching that could produce an error
        //    getData().setFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS, false);
        //    return;
        //}
        // And last, try to switch the client
        nextVideoInfoType(suspect);
        //persistVideoInfoType();
    }

    public void switchNextSubtitle() {
        CaptionTrack.sFormat = Helpers.getNextValue(CaptionTrack.CaptionFormat.values(), CaptionTrack.sFormat);
    }

    public void resetInfoType() {
        mRouteAnchor = null;
        resetInfoTypeToDefault();
        PoTokenGate.resetCache();
        clearBotCheckCircuit();
    }

    private void nextVideoInfoType(@Nullable AppClient suspect) {
        mNextInfoType = Helpers.getNextValue(VIDEO_INFO_TYPE_LIST, suspect);
        mRecoverySuspect = suspect;
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
            if (result != null) {
                // NEWTUBE(readiness): the pre-roll wait counts from here (see PrerollAds).
                result.setReceivedAtMs(android.os.SystemClock.elapsedRealtime());
            }
        } else {
            VideoInfoApiHelper.PlayerRequest request =
                    VideoInfoApiHelper.getVideoInfoRequest(client, videoId, clickTrackingParams);
            result = getVideoInfo(client, request);
        }

        // NEWTUBE(botwall): debug builds only. The real request was made (timing, server load and
        // the player-http log line stay honest); only its answer is replaced.
        result = maybeInjectBotWall(client, result);

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
        // NEWTUBE(net): the phone walk needs a refused connection as a transport failure (see
        // RetrofitHelper.getOrThrowOnConnect); TV keeps upstream's null.
        VideoInfo videoInfo = sPreferNoPotClient
                ? RetrofitHelper.getOrThrowOnConnect(wrapper, auth) : RetrofitHelper.get(wrapper, auth);

        if (videoInfo == null) {
            return null;
        }

        // NEWTUBE(planner): "the request carried the account", not "the client could have": signed
        // out, an auth-capable client's answer (TV_TIZEN) read as the account route failing, as a
        // signed-in consensus witness, and skipped the guest subtitle enrichment (LANES.md, C-5).
        videoInfo.setAuth(auth && hasAuthentication());
        // NEWTUBE(readiness): the pre-roll wait counts from here (see PrerollAds).
        videoInfo.setReceivedAtMs(android.os.SystemClock.elapsedRealtime());

        return videoInfo;
    }

    private @Nullable VideoInfo getVideoInfoReel(Call<VideoInfoReel> wrapper, boolean auth) {
        VideoInfoReel videoInfo = sPreferNoPotClient
                ? RetrofitHelper.getOrThrowOnConnect(wrapper, auth) : RetrofitHelper.get(wrapper, auth);

        if (videoInfo == null || videoInfo.getVideoInfo() == null) {
            return null;
        }

        videoInfo.getVideoInfo().setAuth(auth && hasAuthentication());
        videoInfo.getVideoInfo().setReceivedAtMs(android.os.SystemClock.elapsedRealtime());

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

    private void resetInfoTypeToDefault() {
        mNextInfoType = null;
        mRecoveryWalk = false;
        mRecoverySuspect = null;
        mActualInfoType = VIDEO_INFO_TYPE_LIST[0];
        mRoutingGeneration.incrementAndGet();
        persistVideoInfoType();
    }

    private void persistVideoInfoType() {
        if (!GlobalPreferences.isInitialized()) {
            return;
        }

        SourceWinnerHint.write(winnerHintPrefs(), mActualInfoType);
    }

    /**
     * NEWTUBE(source-catalog): the winner hint's own preference. It is still written on every
     * winner change, but no walk reads it back: TV never did, and the phone's order comes from
     * PhoneSourcePlanner (its cold-start restore went with design 3).
     */
    private SourceWinnerHint.Prefs winnerHintPrefs() {
        return new SourceWinnerHint.Prefs() {
            private static final String KEY = "newtube_player_winner_source";

            @Override
            public String get() {
                return GlobalPreferences.sInstance.getString(KEY, null);
            }

            @Override
            public void put(String id) {
                GlobalPreferences.sInstance.putString(KEY, id);
            }

            @Override
            public int legacyOrdinal() {
                return getData().getVideoInfoType();
            }
        };
    }

    private void persistRecentTypeIfNeeded(VideoInfo videoInfo) {
        if (videoInfo == null || videoInfo.isUnplayable() || videoInfo.getClient() == mActualInfoType) {
            return;
        }

        mActualInfoType = videoInfo.getClient();

        // NEWTUBE(live-winner): a live answer's client was chosen by manifest type, not by health -
        // the walk holds HLS-only answers and goes on to the one client with a live DASH manifest
        // (ANDROID_VR, see sPreferDashManifestForLive). It stays the CURRENT client above, because
        // recovery and the route quarantine must blame the client that actually served the stream,
        // but it is not persisted: as the cold-start hint it made the first VOD open after the next
        // launch begin on ANDROID_VR and hit its deep-range 403 wall about a second in (emulator:
        // first frame, then 403 and three recovery reloads).
        if (sPreferDashManifestForLive && videoInfo.isLive()) {
            android.util.Log.d("NetPath", "player-ring winner-kept reason=live client="
                    + videoInfo.getClient() + " persisted=n");
            return;
        }

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
