package com.liskovsoft.youtubeapi.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.DeArrowData;
import com.liskovsoft.mediaserviceinterfaces.data.DislikeData;
import com.liskovsoft.mediaserviceinterfaces.data.FeedbackReasons;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.actions.ActionsService;
import com.liskovsoft.youtubeapi.actions.ActionsServiceWrapper;
import com.liskovsoft.youtubeapi.block.SponsorBlockService;
import com.liskovsoft.youtubeapi.block.data.SegmentList;
import com.liskovsoft.youtubeapi.common.models.impl.mediaitem.BaseMediaItem;
import com.liskovsoft.youtubeapi.dearrow.DeArrowService;
import com.liskovsoft.youtubeapi.feedback.FeedbackService;
import com.liskovsoft.youtubeapi.next.v2.WatchNextService;
import com.liskovsoft.youtubeapi.next.v2.WatchNextServiceWrapper;
import com.liskovsoft.youtubeapi.playlist.PlaylistService;
import com.liskovsoft.youtubeapi.playlist.PlaylistServiceWrapper;
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;
import com.liskovsoft.youtubeapi.service.data.YouTubeSponsorSegment;
import com.liskovsoft.youtubeapi.track.TrackingService;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import io.reactivex.Observable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.net.Uri;

import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.sharedutils.cronet.CronetManager;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

import org.chromium.net.CronetEngine;
import org.chromium.net.CronetException;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

public class YouTubeMediaItemService implements MediaItemService {
    private static final String TAG = YouTubeMediaItemService.class.getSimpleName();
    private static YouTubeMediaItemService sInstance;
    private MediaItemFormatInfo mCachedFormatInfo;

    // Mobile single-flight for format info. When enabled, concurrent getFormatInfo() calls for the SAME
    // videoId collapse into ONE network fetch (the first caller fetches; the rest block on a per-videoId
    // lock and then read the freshly cached result). This is what makes the mobile "prefetch at tap" win
    // real: the tap-time prefetch and the player's own end-of-onCreate fetch share a single round-trip
    // instead of racing into two. Off by default -> TV keeps the exact original single-fetch path.
    private static boolean sSingleFlightEnabled;
    private final ConcurrentHashMap<String, Object> mFormatInfoLocks = new ConcurrentHashMap<>();

    public static void setSingleFlightEnabled(boolean enabled) {
        sSingleFlightEnabled = enabled;
    }

    // Mobile TTFF: the instant format info arrives (usually while the player Activity is still
    // inflating / building the DASH manifest), open a throwaway request to the per-video
    // googlevideo host through the SAME singleton Cronet engine ExoPlayer's CronetDataSourceFactory
    // wraps. Cronet pools QUIC/H2 sessions per host inside the engine, so the DNS + TLS/QUIC
    // handshake is already done when the first real media request goes out. Best-effort: any
    // failure is swallowed. Off by default -> TV path unchanged.
    private static boolean sPreconnectMediaHost;
    private static volatile String sLastWarmedHost;
    private static ExecutorService sPreconnectExecutor;

    public static void setPreconnectMediaHost(boolean enabled) {
        sPreconnectMediaHost = enabled;
    }

    private static void preconnectMediaHost(MediaItemFormatInfo formatInfo) {
        if (!sPreconnectMediaHost || formatInfo == null) {
            return;
        }

        try {
            String url = firstStreamUrl(formatInfo);
            String host = url != null ? Uri.parse(url).getHost() : null;
            if (host == null || host.equals(sLastWarmedHost)) {
                return; // nothing to warm, or the session to this host is already warm
            }

            if (!GlobalPreferences.isInitialized()) {
                return;
            }

            CronetEngine engine = CronetManager.getEngine(GlobalPreferences.sInstance.getContext());
            if (engine == null) {
                return;
            }

            if (sPreconnectExecutor == null) {
                sPreconnectExecutor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "MediaHostPreconnect");
                    t.setDaemon(true);
                    return t;
                });
            }

            sLastWarmedHost = host;
            UrlRequest request = engine.newUrlRequestBuilder(
                    "https://" + host + "/generate_204", new NoopUrlCallback(), sPreconnectExecutor).build();
            request.start();
            Log.d(TAG, "preconnecting media host: %s", host);
        } catch (Throwable e) {
            Log.d(TAG, "media host preconnect skipped: %s", e.getMessage());
        }
    }

    private static String firstStreamUrl(MediaItemFormatInfo formatInfo) {
        List<MediaFormat> adaptive = formatInfo.getAdaptiveFormats();
        if (adaptive != null && !adaptive.isEmpty() && adaptive.get(0).getUrl() != null) {
            return adaptive.get(0).getUrl();
        }
        if (formatInfo.getServerAbrStreamingUrl() != null) {
            return formatInfo.getServerAbrStreamingUrl();
        }
        List<MediaFormat> regular = formatInfo.getUrlFormats();
        if (regular != null && !regular.isEmpty() && regular.get(0).getUrl() != null) {
            return regular.get(0).getUrl();
        }
        return formatInfo.getDashManifestUrl();
    }

    private static class NoopUrlCallback extends UrlRequest.Callback {
        @Override
        public void onRedirectReceived(UrlRequest request, UrlResponseInfo info, String newLocationUrl) {
            request.followRedirect();
        }

        @Override
        public void onResponseStarted(UrlRequest request, UrlResponseInfo info) {
            request.read(java.nio.ByteBuffer.allocateDirect(1024));
        }

        @Override
        public void onReadCompleted(UrlRequest request, UrlResponseInfo info, java.nio.ByteBuffer byteBuffer) {
            byteBuffer.clear();
            request.read(byteBuffer);
        }

        @Override
        public void onSucceeded(UrlRequest request, UrlResponseInfo info) {
            // connection is warm; nothing to do
        }

        @Override
        public void onFailed(UrlRequest request, UrlResponseInfo info, CronetException error) {
            // best-effort warm; ignore
        }
    }

    private YouTubeMediaItemService() {
    }

    public static YouTubeMediaItemService instance() {
        if (sInstance == null) {
            sInstance = new YouTubeMediaItemService();
        }

        return sInstance;
    }

    /**
     * Format info is cached because it's supposed to run in multiple methods
     */
    @Override
    public MediaItemFormatInfo getFormatInfo(MediaItem item) {
        return getFormatInfo(item.getVideoId(), item.getClickTrackingParams());
    }

    @Override
    public MediaItemFormatInfo getFormatInfo(String videoId) {
        return getFormatInfo(videoId, null);
    }

    @Override
    public MediaItemFormatInfo getFormatInfo(String videoId, String clickTrackingParams) {
        MediaItemFormatInfo cachedFormatInfo = getCachedFormatInfo(videoId);

        if (cachedFormatInfo != null) {
            return cachedFormatInfo;
        }

        // TV path: unchanged single fetch.
        if (!sSingleFlightEnabled || videoId == null) {
            return fetchFormatInfo(videoId, clickTrackingParams);
        }

        // Mobile path: collapse concurrent fetches for the same videoId into one round-trip.
        Object lock = getFormatInfoLock(videoId);
        synchronized (lock) {
            try {
                // Another caller for this videoId may have just populated the cache while we waited.
                MediaItemFormatInfo cached = getCachedFormatInfo(videoId);
                if (cached != null) {
                    return cached;
                }

                return fetchFormatInfo(videoId, clickTrackingParams);
            } finally {
                // Only remove our own lock so a concurrent leader for a different videoId isn't disturbed.
                mFormatInfoLocks.remove(videoId, lock);
            }
        }
    }

    private MediaItemFormatInfo fetchFormatInfo(String videoId, String clickTrackingParams) {
        checkSigned();

        VideoInfo videoInfo = getVideoInfoService().getVideoInfo(videoId, clickTrackingParams);

        MediaItemFormatInfo formatInfo = YouTubeMediaItemFormatInfo.from(videoInfo);

        setCachedFormatInfo(formatInfo, clickTrackingParams);

        // Mobile: warm the media host while the player is still preparing (no-op unless enabled).
        preconnectMediaHost(formatInfo);

        return formatInfo;
    }

    private Object getFormatInfoLock(String videoId) {
        Object lock = mFormatInfoLocks.get(videoId);
        if (lock == null) {
            Object created = new Object();
            Object prev = mFormatInfoLocks.putIfAbsent(videoId, created);
            lock = prev != null ? prev : created;
        }
        return lock;
    }

    //@Override
    //public MediaItemFormatInfo getFormatInfo(String videoId, String clickTrackingParams) {
    //    //videoId = "K04WmBtVsOs"; // the testing video: Memories of Memories
    //
    //    MediaItemFormatInfo cachedFormatInfo = getCachedFormatInfo(videoId);
    //
    //    if (cachedFormatInfo != null) {
    //        return cachedFormatInfo;
    //    }
    //
    //    checkSigned();
    //
    //    MediaItemFormatInfo formatInfo = InnertubeService.createFormatInfo(videoId);
    //
    //    setCachedFormatInfo(formatInfo, clickTrackingParams);
    //
    //    return formatInfo;
    //}

    @Override
    public Observable<MediaItemFormatInfo> getFormatInfoObserve(MediaItem item) {
        return RxHelper.fromCallable(() -> getFormatInfo(item));
    }

    @Override
    public Observable<MediaItemFormatInfo> getFormatInfoObserve(String videoId) {
        return RxHelper.fromCallable(() -> getFormatInfo(videoId));
    }

    @Override
    public Observable<MediaItemFormatInfo> getFormatInfoObserve(String videoId, String clickTrackingParams) {
        return RxHelper.fromCallable(() -> getFormatInfo(videoId, clickTrackingParams));
    }

    @Override
    public MediaItemStoryboard getStoryboard(MediaItem item) {
        return getStoryboard(item.getVideoId());
    }

    @Override
    public MediaItemStoryboard getStoryboard(String videoId) {
        MediaItemFormatInfo format = getFormatInfo(videoId);
        return format != null ? format.createStoryboard() : null;
    }

    @Override
    public Observable<MediaItemStoryboard> getStoryboardObserve(MediaItem item) {
        return RxHelper.fromCallable(() -> getStoryboard(item));
    }

    @Override
    public Observable<MediaItemStoryboard> getStoryboardObserve(String videoId) {
        return RxHelper.fromCallable(() -> getStoryboard(videoId));
    }

    @Override
    public MediaItemMetadata getMetadata(MediaItem item) {
        return getMetadata(item.getVideoId(), item.getPlaylistId(), item.getPlaylistIndex(), item.getParams());
    }

    @Override
    public MediaItemMetadata getMetadata(String videoId, String playlistId, int playlistIndex, String playlistParams) {
        return getWatchNextService().getMetadata(videoId, playlistId, playlistIndex, playlistParams);
    }

    @Override
    public MediaItemMetadata getMetadata(String videoId) {
        return getWatchNextService().getMetadata(videoId);
    }

    @Override
    public Observable<MediaItemMetadata> getMetadataObserve(MediaItem item) {
        return RxHelper.create(emitter -> {
            MediaItemMetadata metadata = getMetadata(item);

            if (metadata != null) {
                syncItem(item, metadata);
                emitter.onNext(metadata);
                emitter.onComplete();
            } else {
                RxHelper.onError(emitter, "getMetadataObserve result is null");
            }
        });
    }

    @Override
    public Observable<MediaItemMetadata> getMetadataObserve(String videoId) {
        return RxHelper.fromCallable(() -> getMetadata(videoId));
    }

    @Override
    public Observable<MediaItemMetadata> getMetadataObserve(String videoId, String playlistId, int playlistIndex, String playlistParams) {
        return RxHelper.fromCallable(() -> getMetadata(videoId, playlistId, playlistIndex, playlistParams));
    }

    @Override
    public void updateHistoryPosition(MediaItem item, float positionSec) {
        checkSigned();

        updateHistoryPosition(item.getVideoId(), positionSec);
    }

    @Override
    public void updateHistoryPosition(String videoId, float positionSec) {
        checkSigned();

        MediaItemFormatInfo formatInfo = getFormatInfo(videoId);

        if (formatInfo == null) {
            Log.e(TAG, "Can't update history for video id %s. formatInfo == null", videoId);
            return;
        }

        // Improve the performance by fetching the history data on the second run
        syncWithAuthFormatIfNeeded(formatInfo);

        if (shouldBeSynced(formatInfo)) {
            throw new IllegalStateException("Update history error: the format should be synced first");
        }

        getTrackingService().updateWatchTime(
                formatInfo.getVideoId(), positionSec, Helpers.parseFloat(formatInfo.getLengthSeconds()), formatInfo.getEventId(),
                formatInfo.getVisitorMonitoringData(), formatInfo.getOfParam());
    }

    @Override
    public Observable<Void> updateHistoryPositionObserve(MediaItem item, float positionSec) {
        return RxHelper.fromRunnable(() -> updateHistoryPosition(item, positionSec));
    }

    @Override
    public Observable<Void> updateHistoryPositionObserve(String videoId, float positionSec) {
        return RxHelper.fromRunnable(() -> updateHistoryPosition(videoId, positionSec));
    }

    @Override
    public Observable<Void> subscribeObserve(MediaItem item) {
        return RxHelper.fromRunnable(() -> subscribe(item));
    }

    @Override
    public Observable<Void> subscribeObserve(String channelId) {
        return RxHelper.fromRunnable(() -> subscribe(channelId));
    }

    @Override
    public Observable<Void> unsubscribeObserve(MediaItem item) {
        return RxHelper.fromRunnable(() -> unsubscribe(item));
    }

    @Override
    public Observable<Void> unsubscribeObserve(String channelId) {
        return RxHelper.fromRunnable(() -> unsubscribe(channelId));
    }

    @Override
    public Observable<Void> setLikeObserve(MediaItem item) {
        return RxHelper.fromRunnable(() -> setLike(item));
    }

    @Override
    public Observable<Void> removeLikeObserve(MediaItem item) {
        return RxHelper.fromRunnable(() -> removeLike(item));
    }

    @Override
    public Observable<Void> setDislikeObserve(MediaItem item) {
        return RxHelper.fromRunnable(() -> setDislike(item));
    }

    @Override
    public Observable<Void> removeDislikeObserve(MediaItem item) {
        return RxHelper.fromRunnable(() -> removeDislike(item));
    }

    @Override
    public void setLike(MediaItem item) {
        checkSigned();

        getActionsService().setLike(item.getVideoId());
    }

    @Override
    public void removeLike(MediaItem item) {
        checkSigned();

        getActionsService().removeLike(item.getVideoId());
    }

    @Override
    public void setDislike(MediaItem item) {
        checkSigned();

        getActionsService().setDislike(item.getVideoId());
    }

    @Override
    public void removeDislike(MediaItem item) {
        checkSigned();

        getActionsService().removeDislike(item.getVideoId());
    }

    @Override
    public void subscribe(MediaItem item) {
        subscribe(item.getChannelId(), item.getParams());
    }

    @Override
    public void subscribe(String channelId) {
        subscribe(channelId, null);
    }

    private void subscribe(String channelId, String params) {
        checkSigned();

        getActionsService().subscribe(channelId, params);
    }

    @Override
    public void unsubscribe(MediaItem item) {
        unsubscribe(item.getChannelId());
    }

    @Override
    public void unsubscribe(String channelId) {
        checkSigned();

        getActionsService().unsubscribe(channelId);
    }

    @Override
    public void markAsNotInterested(String feedbackToken) {
        checkSigned();

        getFeedbackService().markAsNotInterested(feedbackToken);
    }

    @Override
    public Observable<Void> markAsNotInterestedObserve(String feedbackToken) {
        return RxHelper.fromRunnable(() -> markAsNotInterested(feedbackToken));
    }

    @Override
    public FeedbackReasons getFeedbackReasons(String feedbackToken) {
        checkSigned();

        return getFeedbackService().getReasons(feedbackToken);
    }

    @Override
    public Observable<FeedbackReasons> getFeedbackReasonsObserve(String feedbackToken) {
        return RxHelper.fromCallable(() -> getFeedbackReasons(feedbackToken));
    }

    @Override
    public List<PlaylistInfo> getPlaylistsInfo(String videoId) {
        checkSigned();

        return getPlaylistService().getPlaylistsInfo(videoId);
    }

    private void addToPlaylist(String playlistId, String videoId) {
        checkSigned();

        getPlaylistService().addToPlaylist(playlistId, videoId);
    }

    private void addToPlaylist(String playlistId, MediaItem item) {
        checkSigned();

        PlaylistGroupServiceImpl.cachedItem = item;
        getPlaylistService().addToPlaylist(playlistId, item.getVideoId());
    }

    @Override
    public void removeFromPlaylist(String playlistId, String videoId) {
        checkSigned();

        getPlaylistService().removeFromPlaylist(playlistId, videoId);
    }

    @Override
    public void renamePlaylist(String playlistId, String newName) {
        checkSigned();

        getPlaylistService().renamePlaylist(playlistId, newName);
    }

    @Override
    public void setPlaylistOrder(String playlistId, int playlistOrder) {
        checkSigned();

        getPlaylistService().setPlaylistOrder(playlistId, playlistOrder);
    }

    private void savePlaylist(String playlistId) {
        checkSigned();

        getPlaylistService().savePlaylist(playlistId);
    }

    private void savePlaylist(MediaItem item) {
        checkSigned();

        PlaylistGroupServiceImpl.cachedItem = item;
        getPlaylistService().savePlaylist(item.getPlaylistId());
    }

    @Override
    public void removePlaylist(String playlistId) {
        checkSigned();

        getPlaylistService().removePlaylist(playlistId);
    }

    private void createPlaylist(String playlistName, String videoId) {
        checkSigned();

        getPlaylistService().createPlaylist(playlistName, videoId);
    }

    private void createPlaylist(String playlistName, @Nullable MediaItem item) {
        checkSigned();

        PlaylistGroupServiceImpl.cachedItem = item;
        getPlaylistService().createPlaylist(playlistName, item != null ? item.getVideoId() : null);
    }

    @Override
    public List<SponsorSegment> getSponsorSegments(String videoId) {
        SegmentList segmentList = getSponsorBlockService().getSegmentList(videoId);

        return YouTubeSponsorSegment.from(segmentList);
    }

    @Override
    public List<SponsorSegment> getSponsorSegments(String videoId, Set<String> categories) {
        SegmentList segmentList = getSponsorBlockService().getSegmentList(videoId, categories);

        return YouTubeSponsorSegment.from(segmentList);
    }

    @Override
    public Observable<List<PlaylistInfo>> getPlaylistsInfoObserve(String videoId) {
        return RxHelper.fromCallable(() -> getPlaylistsInfo(videoId));
    }

    @Override
    public Observable<Void> addToPlaylistObserve(String playlistId, String videoId) {
        return RxHelper.fromRunnable(() -> addToPlaylist(playlistId, videoId));
    }

    @Override
    public Observable<Void> addToPlaylistObserve(String playlistId, MediaItem item) {
        return RxHelper.fromRunnable(() -> addToPlaylist(playlistId, item));
    }

    @Override
    public Observable<Void> removeFromPlaylistObserve(String playlistId, String videoId) {
        return RxHelper.fromRunnable(() -> removeFromPlaylist(playlistId, videoId));
    }

    @Override
    public Observable<Void> renamePlaylistObserve(String playlistId, String newName) {
        return RxHelper.fromRunnable(() -> renamePlaylist(playlistId, newName));
    }

    @Override
    public Observable<Void> setPlaylistOrderObserve(String playlistId, int playlistOrder) {
        return RxHelper.fromRunnable(() -> setPlaylistOrder(playlistId, playlistOrder));
    }

    @Override
    public Observable<Void> savePlaylistObserve(String playlistId) {
        return RxHelper.fromRunnable(() -> savePlaylist(playlistId));
    }

    @Override
    public Observable<Void> savePlaylistObserve(MediaItem item) {
        return RxHelper.fromRunnable(() -> savePlaylist(item));
    }

    @Override
    public Observable<Void> removePlaylistObserve(String playlistId) {
        return RxHelper.fromRunnable(() -> removePlaylist(playlistId));
    }

    @Override
    public Observable<Void> createPlaylistObserve(String playlistName, String videoId) {
        return RxHelper.fromRunnable(() -> createPlaylist(playlistName, videoId));
    }

    @Override
    public Observable<Void> createPlaylistObserve(String playlistName, MediaItem item) {
        return RxHelper.fromRunnable(() -> createPlaylist(playlistName, item));
    }

    @Override
    public Observable<List<SponsorSegment>> getSponsorSegmentsObserve(String videoId) {
        return RxHelper.fromCallable(() -> getSponsorSegments(videoId));
    }

    @Override
    public Observable<List<SponsorSegment>> getSponsorSegmentsObserve(String videoId, Set<String> categories) {
        return RxHelper.fromCallable(() -> getSponsorSegments(videoId, categories));
    }

    @Override
    public Observable<DeArrowData> getDeArrowDataObserve(String videoId) {
        return RxHelper.fromCallable(() -> getDeArrowData(videoId));
    }

    @Override
    public Observable<DeArrowData> getDeArrowDataObserve(List<String> videoIds) {
        return RxHelper.create(emitter -> {
            for (String videoId : videoIds) {
                DeArrowData result = getDeArrowData(videoId);
                if (result != null) {
                    emitter.onNext(result);
                }
            }
            emitter.onComplete();
        });
    }

    private DeArrowData getDeArrowData(String videoId) {
        return DeArrowService.getData(videoId);
    }

    @Override
    public Observable<DislikeData> getDislikeDataObserve(String videoId) {
        return RxHelper.fromCallable(() -> getWatchNextService().getDislikeData(videoId));
    }

    @Override
    public Observable<String> getUnlocalizedTitleObserve(String videoId) {
        return RxHelper.fromCallable(() -> getWatchNextService().getUnlocalizedTitle(videoId));
    }

    public void invalidateCache() {
        mCachedFormatInfo = null;
    }

    private MediaItemFormatInfo getCachedFormatInfo(String videoId) {
        return  mCachedFormatInfo != null &&
                mCachedFormatInfo.getVideoId() != null &&
                mCachedFormatInfo.getVideoId().equals(videoId) &&
                mCachedFormatInfo.isCacheActual() ? mCachedFormatInfo : null;
    }

    private void setCachedFormatInfo(MediaItemFormatInfo formatInfo, String clickTrackingParams) {
        mCachedFormatInfo = formatInfo;

        if (formatInfo != null) {
            formatInfo.setClickTrackingParams(clickTrackingParams);
        }
    }

    private void checkSigned() {
        getSignInService().checkAuth();
    }

    @NonNull
    private static YouTubeSignInService getSignInService() {
        return YouTubeSignInService.instance();
    }

    @NonNull
    private static SponsorBlockService getSponsorBlockService() {
        return SponsorBlockService.instance();
    }

    @NonNull
    private static TrackingService getTrackingService() {
        return TrackingService.instance();
    }

    @NonNull
    private static VideoInfoService getVideoInfoService() {
        return VideoInfoService.instance();
    }

    @NonNull
    private static ActionsService getActionsService() {
        return ActionsServiceWrapper.instance();
    }

    @NonNull
    private static PlaylistService getPlaylistService() {
        return PlaylistServiceWrapper.instance();
    }

    @NonNull
    private static FeedbackService getFeedbackService() {
        return FeedbackService.instance();
    }

    @NonNull
    private static WatchNextService getWatchNextService() {
        return WatchNextServiceWrapper.INSTANCE;
    }

    private static void syncWithAuthFormatIfNeeded(MediaItemFormatInfo formatInfo) {
        if (formatInfo == null) {
            return;
        }

        if (shouldBeSynced(formatInfo) && !formatInfo.isSynced()) {
            VideoInfo videoInfo = getVideoInfoService().getAuthVideoInfo(formatInfo.getVideoId(), formatInfo.getClickTrackingParams());
            formatInfo.sync(YouTubeMediaItemFormatInfo.from(videoInfo));
        }
    }

    private static boolean shouldBeSynced(MediaItemFormatInfo formatInfo) {
        return !formatInfo.isAuth() && !formatInfo.isUnplayable() && getSignInService().isSigned();
    }

    private static void syncItem(MediaItem item, MediaItemMetadata metadata) {
        if (item instanceof BaseMediaItem) {
            ((BaseMediaItem) item).sync(metadata);
        } else if (item instanceof YouTubeMediaItem) {
            ((YouTubeMediaItem) item).sync(metadata);
        }
    }
}
