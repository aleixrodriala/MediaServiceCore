package com.liskovsoft.youtubeapi.videoinfo;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.googlecommon.common.api.FileApi;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.common.helpers.MediaHostPreconnect;
import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.youtubeapi.formatbuilders.utils.MediaFormatUtils;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.videoinfo.V2.DashInfoApi;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoUrlHolder;
import com.liskovsoft.youtubeapi.videoinfo.models.DashInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.DashInfoContent;
import com.liskovsoft.youtubeapi.videoinfo.models.DashInfoHeaders;
import com.liskovsoft.youtubeapi.videoinfo.models.DashInfoUrl;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VodDelivery;
import com.liskovsoft.youtubeapi.videoinfo.models.formats.AdaptiveVideoFormat;
import com.liskovsoft.youtubeapi.videoinfo.models.formats.VideoFormat;

import java.util.ArrayList;
import java.util.List;

import kotlin.Pair;

public abstract class VideoInfoServiceBase {
    private static final String TAG = VideoInfoServiceBase.class.getSimpleName();
    private final DashInfoApi mDashInfoApi;
    private final FileApi mFileApi;
    protected final AppService mAppService;
    // See setSkipLiveDashInfoWithManifest.
    private static volatile boolean sSkipLiveDashInfoWithManifest;

    /**
     * NEWTUBE(live-ttff): enabled once from the mobile flavor (MobileMainApplication, through
     * {@code VideoInfoService.setSkipLiveDashInfoWithManifest}). Never called on TV.
     * <p>
     * {@link #getDashInfo} costs up to six SERIAL googlevideo GETs (headers, url, content, then
     * the whole chain again as a blind retry) through the shared OkHttp client - not the player's
     * warm Cronet engine - and it runs inside {@link #transformFormats}, i.e. before the /player
     * result is handed to the player at all. Everything it computes (segment duration, start
     * segment, start time) is for ONE consumer that needs it: the generated-MPD live route
     * (YouTubeMPDBuilder's live SegmentTemplate). The loader only takes that route when a live
     * stream has NEITHER a dashManifestUrl NOR an hlsManifestUrl (VideoLoaderController opens the
     * manifest URL otherwise, and media3 derives the live window from the manifest itself), and
     * next-video prebuild and direct cast both refuse live outright. So with a manifest URL present
     * the probe's result is dead weight on the critical path, and it is skipped; without one it
     * still runs, synchronously, exactly as before, because the generated MPD needs it up front.
     * <p>
     * What else reads the fields, all non-critical with a manifest URL: Video.startTimeMs (the
     * duration fallback for a broken engine duration - falls back to the microformat start
     * timestamp instead), Video.startSegmentNum (isFullLive: now 0, which is what every FRESH live
     * Video already carries at onNewVideo, so only a same-object reopen of a >24 h stream changes -
     * it keeps its position like every <24 h stream does), the live storyboard (the touch UI's
     * loadStoryboard is a stub) and isStreamSeekable (no reader in the app).
     */
    public static void setSkipLiveDashInfoWithManifest(boolean skip) {
        sSkipLiveDashInfoWithManifest = skip;
    }

    /**
     * Why the live dash-info probe is skipped, or null when it must run. Pure so the decision is
     * testable: only the phone gate plus a manifest URL the loader will actually open skips it.
     */
    @Nullable
    static String liveDashInfoSkipReason(boolean gate, @Nullable String dashManifestUrl,
            @Nullable String hlsManifestUrl) {
        if (!gate) {
            return null;
        }
        if (dashManifestUrl != null) {
            return "dash-manifest";
        }
        if (hlsManifestUrl != null) {
            return "hls-manifest";
        }
        return null;
    }

    // See setSkipSolveWithoutChallenges.
    private static volatile boolean sSkipSolveWithoutChallenges;

    /**
     * NEWTUBE(player-js-gate): set from the phone flavor (through VideoInfoService's player-JS
     * gate). Never on TV.
     * <p>
     * {@link #transformFormats} asked AppService for the signature/n solve of EVERY answer, including
     * one with no signature and no n parameter at all (VISIONOS, ANDROID_VR). The solve itself then
     * does nothing, but asking for it builds the player extractor first - and while a new player is
     * being validated in the background (the gate lets those sources' requests go out before that
     * validation) it waits the whole validation out, only to hand back nulls. With nothing to solve
     * the call is skipped: the result is the same (no n, no signature to apply), without the wait.
     * An answer with anything to solve still asks, and still waits.
     */
    protected static void setSkipSolveWithoutChallenges(boolean skip) {
        sSkipSolveWithoutChallenges = skip;
    }

    // See setFoldHlsChallenge.
    private static volatile boolean sFoldHlsChallenge;

    /**
     * NEWTUBE(hls-vod-fold): set from the phone flavor. An answer played over HLS for VOD has its
     * manifest's "/n/" challenge solved too (see solveVodHlsChallenge), and that solve was a second
     * V8 run after the bulk one ({@code hls-vod-n ... shared=n}: 65-195 ms on the Pixel). On, the
     * manifest's challenge goes into the bulk solve with the formats' ones: one V8 run. If the
     * bulk answer does not carry it, the separate solve runs as before. Never on TV.
     */
    public static void setFoldHlsChallenge(boolean fold) {
        sFoldHlsChallenge = fold;
    }

    /** Whether any url holder has an n or a signature parameter to solve. */
    static boolean hasChallenge(List<String> nParams, List<String> sParams) {
        return !Helpers.allNulls(nParams) || !Helpers.allNulls(sParams);
    }

    protected VideoInfoServiceBase() {
        mAppService = AppService.instance();
        mDashInfoApi = RetrofitHelper.create(DashInfoApi.class);
        mFileApi = RetrofitHelper.create(FileApi.class);
    }

    protected void transformFormats(VideoInfo videoInfo) {
        if (videoInfo == null || videoInfo.isUnplayable()) {
            return;
        }

        // Mobile TTFF: the googlevideo host is already known here and deciphering never rewrites
        // it (only the n/sig query params), so start the TLS/QUIC handshake NOW and let it run
        // concurrently with the transform below. Warming after the transform, as the format-info
        // publish path did on its own, left the handshake only ~114ms ahead of the first init
        // segment on a measured Pixel 9 cold open -- so that segment opened its own connection and
        // paid a 400ms TTFB. No-op unless the mobile flavor enabled it.
        MediaHostPreconnect.warmUrlEarly(firstStreamUrl(videoInfo));

        decipherFormats(videoInfo);

        if (videoInfo.isLive()) {
            String videoId = videoInfo.getVideoDetails() != null
                    ? videoInfo.getVideoDetails().getVideoId() : null;
            String skipReason = liveDashInfoSkipReason(sSkipLiveDashInfoWithManifest,
                    videoInfo.getDashManifestUrl(), videoInfo.getHlsManifestUrl());
            if (skipReason != null) {
                // NEWTUBE(live-ttff): see setSkipLiveDashInfoWithManifest.
                android.util.Log.d("NetPath", "live-dashinfo skipped reason=" + skipReason
                        + " video=" + videoId + " client=" + videoInfo.getClient());
            } else {
                Log.d(TAG, "Enable seeking support on live streams...");
                long dashInfoStartMs = android.os.SystemClock.elapsedRealtime();
                DashInfo dashInfo = getDashInfo(videoInfo);
                videoInfo.sync(dashInfo);
                android.util.Log.d("NetPath", "live-dashinfo fetched video=" + videoId
                        + " client=" + videoInfo.getClient()
                        + " ok=" + (dashInfo != null ? "y" : "n")
                        + " ms=" + (android.os.SystemClock.elapsedRealtime() - dashInfoStartMs));
            }

            // A web-family client may require its own platform-valid streaming/GVS token on the
            // manifest URL. Never synthesize one for Android/TV/iOS: BotGuard tokens are Web-only
            // and cannot attest those platforms (Android/iOS require DroidGuard/iOSGuard).
            String manifestPot = PoTokenGate.getPoToken(videoInfo.getClient());
            videoInfo.appendPotToManifestUrls(manifestPot);
            Log.d(TAG, "Live manifest pot: " + (manifestPot != null ? "applied" : "unavailable"));
        }

        videoInfo.setVisitorCookie(getData().getVisitorCookie());
    }

    private static final java.util.regex.Pattern MANIFEST_N =
            java.util.regex.Pattern.compile("/n/([^/]+)/");

    private void decipherFormats(VideoInfo videoInfo) {
        List<? extends VideoFormat> adaptiveFormats = videoInfo.getAdaptiveFormats();
        List<? extends VideoFormat> regularFormats = videoInfo.getRegularFormats();

        List<VideoUrlHolder> urlHolders = new ArrayList<>();
        if (adaptiveFormats != null)
            for (VideoFormat videoFormat : adaptiveFormats) {
                urlHolders.add(videoFormat.getUrlHolder());
            }
        if (regularFormats != null)
            for (VideoFormat videoFormat : regularFormats) {
                urlHolders.add(videoFormat.getUrlHolder());
            }
        urlHolders.add(videoInfo.getUrlHolder());

        List<String> nParams = extractNParams(urlHolders);
        List<String> sParams = extractSParams(urlHolders);
        long sigStartMs = android.os.SystemClock.elapsedRealtime();
        // NEWTUBE(hls-vod-fold): the HLS manifest's challenge rides the bulk solve, as one more
        // entry after the holders' (see setFoldHlsChallenge).
        String manifestN = sFoldHlsChallenge ? vodHlsChallenge(videoInfo) : null;
        List<String> nSolve = nParams;
        List<String> sSolve = sParams;
        if (manifestN != null) {
            nSolve = new ArrayList<>(nParams);
            nSolve.add(manifestN);
            sSolve = new ArrayList<>(sParams);
            sSolve.add(null);
        }
        // NEWTUBE(player-js-gate): nothing to solve asks nothing of the player (see
        // setSkipSolveWithoutChallenges); anything to solve waits for the validated player.
        boolean solve = !sSkipSolveWithoutChallenges || hasChallenge(nSolve, sSolve);
        if (!solve && mAppService.isPlayerJsValidationPending()) {
            android.util.Log.d("NetPath", "player-js-gate transform video="
                    + videoInfo.getVideoDetails().getVideoId() + " client=" + videoInfo.getClient()
                    + " solve=none validation=pending");
        }
        Pair<List<String>, List<String>> result = solve ? mAppService.bulkSigExtract(nSolve, sSolve) : null;
        String manifestSolved = null;
        if (manifestN != null && result != null) {
            List<String> nOut = result.getFirst();
            List<String> sOut = result.getSecond();
            boolean split = nOut != null && nOut.size() == nSolve.size();
            if (split) {
                manifestSolved = nOut.get(nOut.size() - 1);
                nOut = new ArrayList<>(nOut.subList(0, nParams.size()));
            }
            if (sOut != null && sOut.size() == sSolve.size()) {
                sOut = new ArrayList<>(sOut.subList(0, sParams.size()));
            }
            result = new Pair<>(nOut, sOut);
        }
        // The V8 solve is charged per DISTINCT param, not per format, and it is the only part of
        // the transform that costs real CPU -- so log both counts next to the elapsed time. Without
        // them a slow transform is indistinguishable between "many distinct challenges" (which
        // lazy per-itag deciphering could fix) and "cold V8" (which it could not).
        android.util.Log.d("NetPath", "player-sig video=" + videoInfo.getVideoDetails().getVideoId()
                + " holders=" + urlHolders.size()
                + " n=" + distinctCount(nParams) + "/" + nonNullCount(nParams)
                + " s=" + distinctCount(sParams) + "/" + nonNullCount(sParams)
                + " nOut=" + FormatTransformDiagnostics.summarize(nParams, result != null ? result.getFirst() : null)
                + " sOut=" + FormatTransformDiagnostics.summarize(sParams, result != null ? result.getSecond() : null)
                + " ms=" + (android.os.SystemClock.elapsedRealtime() - sigStartMs)
                + (manifestN != null ? " hlsN=" + (manifestSolved != null ? "folded" : "missed") : ""));

        if (result != null) {
            applyNParams(urlHolders, result.getFirst());
            applySignatures(urlHolders, result.getSecond());
        }
        solveVodHlsChallenge(videoInfo, nParams, result != null ? result.getFirst() : null, manifestSolved);

        String poToken = PoTokenGate.getPoToken(videoInfo.getClient(), videoInfo.getVideoDetails().getVideoId());
        videoInfo.setPoToken(poToken);
        // Deliberately no cross-platform fallback here. The previous implementation appended a
        // Web/BotGuard token to ANDROID_VR/TV/IOS URLs. It never prevented the observed 403 wall,
        // and current enforcement rejects tokens that do not match the originating platform and
        // binding. Clients that do not ask PoTokenGate for a token keep their minted URL untouched.
        applySessionPoToken(urlHolders, poToken);
    }

    /**
     * NEWTUBE(hls-vod): a VOD HLS manifest URL carries its throttling challenge as a path pair
     * ("/n/&lt;challenge&gt;/"), which the format transform above never reads. The app fetched the
     * manifest with the raw challenge and googlevideo refused every segment it listed (the Pixel
     * over LTE, 2026-09-29: dQw4w9WgXcQ from WEB_EMBED, 403 three times past the pre-roll wait),
     * while the netbench harness, which solves it as yt-dlp does, played the same client's HLS to
     * the end. Solved only for an answer {@link VodDelivery} would play over HLS - no adaptive
     * format with a URL, which is when the loader opens the manifest; live manifests are left as
     * they were.
     */
    private void solveVodHlsChallenge(VideoInfo videoInfo, List<String> nParams,
            @Nullable List<String> nSolved, @Nullable String folded) {
        String url = videoInfo.getHlsManifestUrl();
        String n = vodHlsChallenge(videoInfo);
        if (n == null) {
            return;
        }
        String solved = folded;
        int index = nParams.indexOf(n);
        if (solved == null && index >= 0 && nSolved != null && !nSolved.isEmpty()) {
            // The bulk transform answers per holder, or once when every holder had the same one.
            solved = nSolved.get(nSolved.size() == nParams.size() ? index : 0);
        }
        boolean shared = solved != null;
        if (solved == null) {
            solved = mAppService.extractNSig(n);
        }
        android.util.Log.d("NetPath", "hls-vod-n video=" + videoInfo.getVideoDetails().getVideoId()
                + " client=" + videoInfo.getClient() + " solved=" + (solved != null ? "y" : "n")
                + " shared=" + (shared ? "y" : "n"));
        if (solved != null) {
            videoInfo.setHlsManifestUrl(withManifestChallenge(url, solved));
        }
    }

    /**
     * The HLS manifest's "/n/" challenge of an answer the loader would open over HLS for VOD (no
     * adaptive format with a URL, and VodDelivery plays it), or null: the one solveVodHlsChallenge
     * solves.
     */
    @Nullable
    private static String vodHlsChallenge(VideoInfo videoInfo) {
        String url = videoInfo.getHlsManifestUrl();
        List<? extends VideoFormat> adaptive = videoInfo.getAdaptiveFormats();
        boolean noAdaptiveUrl = adaptive == null || adaptive.isEmpty()
                || videoInfo.isAdaptiveFormatsBroken();
        return url != null && noAdaptiveUrl && VodDelivery.acceptsHls(videoInfo)
                ? manifestChallenge(url) : null;
    }

    /** The "/n/&lt;challenge&gt;/" path value of a googlevideo manifest URL, or null. */
    @Nullable
    static String manifestChallenge(String url) {
        java.util.regex.Matcher challenge = MANIFEST_N.matcher(url);
        return challenge.find() ? challenge.group(1) : null;
    }

    static String withManifestChallenge(String url, String solved) {
        java.util.regex.Matcher challenge = MANIFEST_N.matcher(url);
        return challenge.find()
                ? url.substring(0, challenge.start(1)) + solved + url.substring(challenge.end(1))
                : url;
    }

    /**
     * A URL whose HOST identifies the media server for this video. Only the host is consumed, so
     * an un-deciphered URL is as good as a deciphered one. Mirrors the adaptive -> SABR -> regular
     * -> DASH-manifest fallback order the format-info publish path uses.
     */
    private static String firstStreamUrl(VideoInfo videoInfo) {
        List<? extends VideoFormat> adaptive = videoInfo.getAdaptiveFormats();
        if (adaptive != null && !adaptive.isEmpty() && adaptive.get(0).getUrl() != null) {
            return adaptive.get(0).getUrl();
        }
        if (videoInfo.getServerAbrStreamingUrl() != null) {
            return videoInfo.getServerAbrStreamingUrl();
        }
        List<? extends VideoFormat> regular = videoInfo.getRegularFormats();
        if (regular != null && !regular.isEmpty() && regular.get(0).getUrl() != null) {
            return regular.get(0).getUrl();
        }
        return videoInfo.getDashManifestUrl();
    }

    private static int distinctCount(List<String> values) {
        java.util.Set<String> distinct = new java.util.HashSet<>();
        for (String value : values) {
            if (value != null) {
                distinct.add(value);
            }
        }
        return distinct.size();
    }

    private static int nonNullCount(List<String> values) {
        int count = 0;
        for (String value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    private static List<String> extractSParams(List<VideoUrlHolder> urlHolders) {
        List<String> result = new ArrayList<>();

        for (VideoUrlHolder urlHolder : urlHolders) {
            result.add(urlHolder.getSParam());
        }

        return result;
    }

    private static void applySignatures(List<VideoUrlHolder> urlHolders, List<String> signatures) {
        if (signatures == null) {
            return;
        }

        if (signatures.size() != urlHolders.size()) {
            throw new IllegalStateException("Sizes of urlHolders and signatures should match!");
        }

        for (int i = 0; i < urlHolders.size(); i++) {
            urlHolders.get(i).setSignature(signatures.get(i));
        }
    }

    private static List<String> extractNParams(List<VideoUrlHolder> urlHolders) {
        List<String> result = new ArrayList<>();

        for (VideoUrlHolder urlHolder : urlHolders) {
            result.add(urlHolder.getNParam());
            // All throttled strings has same values
        }

        return result;
    }

    private static void applyNParams(List<VideoUrlHolder> urlHolders, List<String> nParams) {
        if (nParams == null || nParams.isEmpty()) {
            return;
        }

        // All throttled strings has same values
        boolean sameSize = nParams.size() == urlHolders.size();

        for (int i = 0; i < urlHolders.size(); i++) {
            urlHolders.get(i).setNParam(nParams.get(sameSize ? i : 0));
        }
    }

    private static void applySessionPoToken(List<VideoUrlHolder> urlHolders, String poToken) {
        if (poToken == null) {
            return;
        }

        for (int i = 0; i < urlHolders.size(); i++) {
            urlHolders.get(i).setPoToken(poToken);
        }
    }

    private DashInfoUrl getDashInfoUrl(String url) {
        if (url == null) {
            return null;
        }

        return RetrofitHelper.get(mDashInfoApi.getDashInfoUrl(url));
    }

    private DashInfoContent getDashInfoContent(String url) {
        if (url == null) {
            return null;
        }

        return RetrofitHelper.get(mDashInfoApi.getDashInfoContent(url));
    }

    private DashInfoHeaders getDashInfoHeaders(String url) {
        if (url == null) {
            return null;
        }

        // Range doesn't work???
        //return RetrofitHelper.getHeaders(mFileApi.getHeaders(url + SMALL_RANGE));
        return new DashInfoHeaders(RetrofitHelper.getHeaders(mFileApi.getHeaders(url)));
    }

    private DashInfo getDashInfo(VideoInfo videoInfo) {
        if (videoInfo == null || videoInfo.getAdaptiveFormats() == null || videoInfo.getAdaptiveFormats().isEmpty()) {
            return null;
        }

        DashInfo info = getCumulativeDashInfo(videoInfo);

        // Do retry. Sometimes the previous try failed?
        if (info == null || info.getSegmentDurationUs() <= 0 || info.getStartTimeMs() <= 0 || info.getStartSegmentNum() < 0) {
            info = getCumulativeDashInfo(videoInfo);
        }

        return info;
    }

    private DashInfo getCumulativeDashInfo(VideoInfo videoInfo) {
        AdaptiveVideoFormat format = getSmallestAudio(videoInfo);

        if (format == null) {
            return null;
        }

        try {
            return getDashInfoHeaders(format.getUrl());
        } catch (ArithmeticException | NumberFormatException | IllegalStateException ex) {
            try {
                return getDashInfoUrl(format.getUrl());
            } catch (ArithmeticException | NumberFormatException exc) {
                // Empty results received. Url isn't available or something like that
                return getDashInfoContent(format.getUrl());
            }
        }
    }

    private AdaptiveVideoFormat getSmallestAudio(VideoInfo videoInfo) {
        AdaptiveVideoFormat format = Helpers.findFirst(videoInfo.getAdaptiveFormats(),
                item -> MediaFormatUtils.isAudio(item.getMimeType())); // smallest format
        return format;
    }

    private AdaptiveVideoFormat getSmallestVideo(VideoInfo videoInfo) {
        AdaptiveVideoFormat format = Helpers.findLast(videoInfo.getAdaptiveFormats(),
                item -> MediaFormatUtils.isVideo(item.getMimeType())); // smallest format
        return format;
    }
    
    private AdaptiveVideoFormat getLargestVideo(VideoInfo videoInfo) {
        AdaptiveVideoFormat format = Helpers.findFirst(videoInfo.getAdaptiveFormats(),
                item -> MediaFormatUtils.isVideo(item.getMimeType())); // first is largest
        return format;
    }

    protected static MediaServiceData getData() {
        return MediaServiceData.instance();
    }
}
