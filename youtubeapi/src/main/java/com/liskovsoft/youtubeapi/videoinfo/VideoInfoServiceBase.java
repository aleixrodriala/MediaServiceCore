package com.liskovsoft.youtubeapi.videoinfo;

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
            Log.d(TAG, "Enable seeking support on live streams...");
            videoInfo.sync(getDashInfo(videoInfo));

            // A web-family client may require its own platform-valid streaming/GVS token on the
            // manifest URL. Never synthesize one for Android/TV/iOS: BotGuard tokens are Web-only
            // and cannot attest those platforms (Android/iOS require DroidGuard/iOSGuard).
            String manifestPot = PoTokenGate.getPoToken(videoInfo.getClient());
            videoInfo.appendPotToManifestUrls(manifestPot);
            Log.d(TAG, "Live manifest pot: " + (manifestPot != null ? "applied" : "unavailable"));
        }

        videoInfo.setVisitorCookie(getData().getVisitorCookie());
    }

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
        Pair<List<String>, List<String>> result = mAppService.bulkSigExtract(nParams, sParams);
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
                + " ms=" + (android.os.SystemClock.elapsedRealtime() - sigStartMs));

        if (result != null) {
            applyNParams(urlHolders, result.getFirst());
            applySignatures(urlHolders, result.getSecond());
        }

        String poToken = PoTokenGate.getPoToken(videoInfo.getClient(), videoInfo.getVideoDetails().getVideoId());
        videoInfo.setPoToken(poToken);
        // Deliberately no cross-platform fallback here. The previous implementation appended a
        // Web/BotGuard token to ANDROID_VR/TV/IOS URLs. It never prevented the observed 403 wall,
        // and current enforcement rejects tokens that do not match the originating platform and
        // binding. Clients that do not ask PoTokenGate for a token keep their minted URL untouched.
        applySessionPoToken(urlHolders, poToken);
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
