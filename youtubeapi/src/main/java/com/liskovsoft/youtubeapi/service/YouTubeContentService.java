package com.liskovsoft.youtubeapi.service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.actions.ActionsService;
import com.liskovsoft.youtubeapi.actions.ActionsServiceWrapper;
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2;
import com.liskovsoft.youtubeapi.browse.v2.BrowseService2Wrapper;
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.SearchContinuationMediaGroup;
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.SearchSectionMediaGroup;
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.SuggestionsGroup;
import com.liskovsoft.youtubeapi.next.v2.WatchNextService;
import com.liskovsoft.youtubeapi.next.v2.WatchNextServiceWrapper;
import com.liskovsoft.youtubeapi.rss.RssService;
import com.liskovsoft.youtubeapi.search.v1.SearchServiceWrapper;
import com.liskovsoft.youtubeapi.search.v2.SearchService2;
import com.liskovsoft.youtubeapi.search.v2.SearchService2Wrapper;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;
import com.liskovsoft.youtubeapi.utils.UtilsService;
import com.liskovsoft.youtubeapi.browse.v1.BrowseService;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.youtubeapi.common.models.impl.mediagroup.BaseMediaGroup;
import com.liskovsoft.googlecommon.common.helpers.YouTubeHelper;
import com.liskovsoft.youtubeapi.search.v1.SearchService;
import com.liskovsoft.youtubeapi.search.v1.models.SearchResult;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.core.ObservableEmitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

class YouTubeContentService implements ContentService {
    private static final String TAG = YouTubeContentService.class.getSimpleName();
    // NEWTUBE(channel-tabs): concurrent fetches for a channel page's lazily-loaded tabs
    private static final int EMPTY_GROUP_PREFETCH_THREADS = 3;
    private static YouTubeContentService sInstance;
    private static volatile ExecutorService sEmptyGroupExecutor;

    private YouTubeContentService() {
        Log.d(TAG, "Starting...");
    }

    public static ContentService instance() {
        if (sInstance == null) {
            sInstance = new YouTubeContentService();
        }

        return sInstance;
    }

    @Override
    public List<MediaGroup> getSearch(String searchText) {
        checkSigned();

        //SearchResult search = getSearchService().getSearch(searchText);
        //return YouTubeMediaGroup.from(search, MediaGroup.TYPE_SEARCH);
        return getSearchService2().getSearch(searchText);
    }

    @Override
    public List<MediaGroup> getSearch(String searchText, int options) {
        checkSigned();

        //SearchResult search = getSearchService().getSearch(searchText, options);
        //return YouTubeMediaGroup.from(search, MediaGroup.TYPE_SEARCH);
        return getSearchService2().getSearch(searchText, options);
    }

    @Override
    public Observable<List<MediaGroup>> getSearchObserve(String searchText) {
        return RxHelper.fromCallable(() -> getSearch(searchText));
    }

    @Override
    public Observable<List<MediaGroup>> getSearchObserve(String searchText, int options) {
        return RxHelper.fromCallable(() -> getSearch(searchText, options));
    }

    @Override
    public List<String> getSearchTags(String searchText) {
        checkSigned();

        return getSearchService2().getSearchTags(searchText);
    }

    @Override
    public Observable<List<String>> getSearchTagsObserve(String searchText) {
        return RxHelper.fromCallable(() -> getSearchTags(searchText));
    }

    @Override
    public MediaGroup getSubscriptions() {
        Log.d(TAG, "Getting subscriptions...");

        checkSigned();

        MediaGroup subscriptions = getBrowseService2().getSubscriptions();

        // TEMP fix. Subs not fully populated.
        //if (subscriptions != null && subscriptions.getMediaItems() != null && subscriptions.getMediaItems().size() <= 5) {
        //    MediaGroup continuation = continueGroup(subscriptions);
        //    if (continuation == null || continuation.getMediaItems() == null || continuation.getMediaItems().isEmpty()) {
        //        if (getMediaServiceData() != null && !getMediaServiceData().isLegacyUIEnabled()) {
        //            getMediaServiceData().setLegacyUIEnabled(true);
        //            return getBrowseService2().getSubscriptions();
        //        }
        //    }
        //}

        return subscriptions;
    }

    @Override
    public Observable<MediaGroup> getSubscriptionsObserve() {
        return RxHelper.fromCallable(this::getSubscriptions);
    }

    @Override
    public MediaGroup getRssFeed(String... channelIds) {
        if (channelIds == null) {
            return null;
        }

        checkSigned();

        return RssService.getFeed(channelIds);
    }

    @Override
    public Observable<MediaGroup> getRssFeedObserve(String... channelIds) {
        return RxHelper.fromCallable(() -> getRssFeed(channelIds));
    }

    @Override
    public MediaGroup getSubscribedChannels() {
        checkSigned();

        return getBrowseService2().getSubscribedChannels();
    }

    @Override
    public MediaGroup getSubscribedChannelsByNewContent() {
        checkSigned();

        //List<GridTab> subscribedChannels = getBrowseService().getSubscribedChannelsUpdate();
        //return YouTubeMediaGroup.fromTabs(subscribedChannels, MediaGroup.TYPE_CHANNEL_UPLOADS);

        return getBrowseService2().getSubscribedChannelsByNewContent();
    }

    @Override
    public MediaGroup getSubscribedChannelsByName() {
        checkSigned();

        return getBrowseService2().getSubscribedChannelsByName();
    }

    @Override
    public MediaGroup getSubscribedChannelsByLastViewed() {
        checkSigned();

        return getBrowseService2().getSubscribedChannels();
    }

    @Override
    public Observable<MediaGroup> getSubscribedChannelsObserve() {
        return RxHelper.fromCallable(this::getSubscribedChannels);
    }

    @Override
    public Observable<MediaGroup> getSubscribedChannelsByNewContentObserve() {
        return RxHelper.fromCallable(this::getSubscribedChannelsByNewContent);
    }

    @Override
    public Observable<MediaGroup> getSubscribedChannelsByNameObserve() {
        return RxHelper.fromCallable(this::getSubscribedChannelsByName);
    }

    @Override
    public Observable<MediaGroup> getSubscribedChannelsByLastViewedObserve() {
        return RxHelper.fromCallable(this::getSubscribedChannelsByLastViewed);
    }

    @Override
    public MediaGroup getRecommended() {
        Log.d(TAG, "Getting recommended...");

        checkSigned();

        kotlin.Pair<List<MediaGroup>, String> home = getBrowseService2().getHome();

        List<MediaGroup> groups = home != null ? home.getFirst() : null;

        return groups != null && !groups.isEmpty() ? groups.get(0) : null;
    }

    @Override
    public Observable<MediaGroup> getRecommendedObserve() {
        return RxHelper.fromCallable(this::getRecommended);
    }

    @Override
    public MediaGroup getHistory() {
        Log.d(TAG, "Getting history...");

        checkSigned();

        return getBrowseService2().getHistory();
    }

    @Override
    public Observable<MediaGroup> getHistoryObserve() {
        return RxHelper.fromCallable(this::getHistory);
    }

    @Override
    public MediaGroup getGroup(String reloadPageKey) {
        return getBrowseService2().getGroup(reloadPageKey, MediaGroup.TYPE_UNDEFINED, null);
    }

    @Override
    public MediaGroup getGroup(MediaItem mediaItem) {
        if (mediaItem.getReloadPageKey() != null) {
            return getBrowseService2().getGroup(mediaItem.getReloadPageKey(), mediaItem.getType(), mediaItem.getTitle());
        }

        String playlistId = mediaItem.getPlaylistId();
        if (playlistId != null && mediaItem.getVideoId() == null) {
            return getBrowseService2().getPlaylist(playlistId, mediaItem.getTitle());
        }

        return getBrowseService2().getChannelAsGrid(mediaItem.getChannelId());
    }

    @Override
    public Observable<MediaGroup> getGroupObserve(MediaItem mediaItem) {
        return RxHelper.fromCallable(() -> getGroup(mediaItem));
    }

    @Override
    public Observable<MediaGroup> getGroupObserve(String reloadPageKey) {
        return RxHelper.fromCallable(() -> getGroup(reloadPageKey));
    }

    @Override
    public List<MediaGroup> getHome() {
        checkSigned();

        List<MediaGroup> result = new ArrayList<>();
        kotlin.Pair<List<MediaGroup>, String> home = getBrowseService2().getHome();
        List<MediaGroup> groups = home != null ? home.getFirst() : null;

        if (groups == null) {
            Log.e(TAG, "Home group is empty");
            return null;
        }

        for (MediaGroup group : groups) {
            // Load chips
            if (group != null && group.isEmpty()) {
                List<MediaGroup> sections = getBrowseService2().continueEmptyGroup(group);

                if (sections != null) {
                    result.addAll(sections);
                }
            } else if (group != null) {
                result.add(group);
            }
        }

        return result;
    }

    @Override
    public Observable<List<MediaGroup>> getHomeObserve() {
        return RxHelper.create(emitter -> {
            checkSigned();

            emitGroups(emitter, getBrowseService2().getHome());
        });
    }

    @Override
    public Observable<List<MediaGroup>> getTrendingObserve() {
        return RxHelper.create(emitter -> {
            checkSigned();

            emitGroups(emitter, getBrowseService2().getTrending());
        });
    }

    @Override
    public Observable<MediaGroup> getShortsObserve() {
        return RxHelper.create(emitter -> {
            checkSigned();

            //emitGroup(emitter, getBrowseService2().getShorts());

            MediaGroup shorts = getBrowseService2().getShorts();

            if (shorts != null && shorts.getNextPageKey() != null) {
                emitGroup(emitter, shorts);
            } else {
                emitGroupPartial(emitter, shorts);
                emitGroup(emitter, getBrowseService2().getShorts2());
            }
        });
    }

    @Override
    public Observable<List<MediaGroup>> getKidsHomeObserve() {
        return RxHelper.create(emitter -> {
            checkSigned();

            emitGroups(emitter, getBrowseService2().getKidsHome());
        });
    }

    @Override
    public Observable<List<MediaGroup>> getSportsObserve() {
        return RxHelper.create(emitter -> {
            checkSigned();

            emitGroups(emitter, getBrowseService2().getSports());
        });
    }

    @Override
    public Observable<List<MediaGroup>> getLiveObserve() {
        return RxHelper.create(emitter -> {
            checkSigned();

            emitGroups(emitter, getBrowseService2().getLive());
        });
    }

    @Override
    public Observable<MediaGroup> getMyVideosObserve() {
        return RxHelper.fromCallable(getBrowseService2()::getMyVideos);
    }

    @Override
    public Observable<List<MediaGroup>> getMusicObserve() {
        return RxHelper.create(emitter -> {
            checkSigned();

            MediaGroup firstRow = getBrowseService2().getLikedMusic();
            emitGroupsPartial(emitter, Collections.singletonList(firstRow));

            emitGroups(emitter, getBrowseService2().getMusic());
        });
    }

    @Override
    public Observable<List<MediaGroup>> getNewsObserve() {
        return RxHelper.create(emitter -> {
            checkSigned();

            emitGroups(emitter, getBrowseService2().getNews());
        });
    }

    @Override
    public Observable<List<MediaGroup>> getGamingObserve() {
        return RxHelper.create(emitter -> {
            checkSigned();

            emitGroups(emitter, getBrowseService2().getGaming());
        });
    }

    @Override
    public Observable<List<MediaGroup>> getChannelObserve(String channelId) {
        return getChannelObserve(channelId, null, null);
    }

    @Override
    public Observable<List<MediaGroup>> getChannelObserve(MediaItem item) {
        return getChannelObserve(item.getChannelId(), item.getTitle(), item.getParams());
    }

    private Observable<List<MediaGroup>> getChannelObserve(String channelId, String title, String params) {
        return RxHelper.create(emitter -> {
            checkSigned();

            String canonicalId = UtilsService.canonicalChannelId(channelId);

            // Special type of channel that could be found inside Music section (see Liked row More button)
            if (YouTubeHelper.isGridChannel(canonicalId)) {
                MediaGroup gridChannel = getBrowseService2().getGridChannel(canonicalId, params);

                if (gridChannel instanceof BaseMediaGroup && !gridChannel.isEmpty()) {
                    ((BaseMediaGroup) gridChannel).setTitle(title);
                    emitGroups(emitter, Collections.singletonList(gridChannel));
                } else {
                    kotlin.Pair<List<MediaGroup>, String> channel = getBrowseService2().getChannel(canonicalId, params);
                    emitGroups(emitter, channel, true);
                }
            } else {
                kotlin.Pair<List<MediaGroup>, String> channel = getBrowseService2().getChannel(canonicalId, params);
                emitGroups(emitter, channel, true);
            }
        });
    }

    @Nullable
    private List<MediaGroup> getChannelSortingOptions(String channelId) {
        checkSigned();

        return getBrowseService2().getChannelSortingOptions(channelId);
    }

    @Override
    public Observable<List<MediaGroup>> getChannelSortingOptionsObserve(String channelId) {
        return RxHelper.fromCallable(() -> getChannelSortingOptions(channelId));
    }

    @Override
    public Observable<List<MediaGroup>> getChannelSortingOptionsObserve(MediaItem item) {
        return item != null && item.getChannelId() != null ? getChannelSortingOptionsObserve(item.getChannelId()) : null;
    }

    @Override
    public MediaGroup getChannelSearch(String channelId, String query) {
        checkSigned();

        return getBrowseService2().getChannelSearch(channelId, query);
    }

    @Override
    public Observable<MediaGroup> getChannelSearchObserve(String channelId, String query) {
        return RxHelper.fromCallable(() -> getChannelSearch(channelId, query));
    }

    private void emitGroups(ObservableEmitter<List<MediaGroup>> emitter, kotlin.Pair<List<MediaGroup>, String> groupsAndKey) {
        emitGroups(emitter, groupsAndKey, false);
    }

    private void emitGroups(ObservableEmitter<List<MediaGroup>> emitter, kotlin.Pair<List<MediaGroup>, String> groupsAndKey,
                            boolean prefetchEmptyGroups) {
        emitGroupsPartial(emitter, groupsAndKey, prefetchEmptyGroups);

        emitter.onComplete();
    }

    private void emitGroupsPartial(ObservableEmitter<List<MediaGroup>> emitter, kotlin.Pair<List<MediaGroup>, String> groupsAndKey,
                                   boolean prefetchEmptyGroups) {
        if (groupsAndKey == null) {
            Log.e(TAG, "emitGroupsPartial: groupsAndKey is null");
            return;
        }

        List<MediaGroup> groups = groupsAndKey.getFirst();
        String nextKey = groupsAndKey.getSecond();
        int page = 0;

        while (groups != null && !groups.isEmpty()) {
            if (!emitGroupsPartial(emitter, groups, prefetchEmptyGroups)) {
                return;
            }

            // NEWTUBE(dispose-stop): a Home/channel section list walks EVERY continuation page back to
            // back; leaving the screen mid-load used to keep that walk going to the end.
            if (emitter.isDisposed()) {
                android.util.Log.d("NetPath", "browse-stop disposed before continuation page=" + (page + 1)
                        + " type=" + groups.get(0).getType());
                return;
            }

            // NEWTUBE(lazy-home): the app may pace the walk (Home fetches the next page when the
            // user scrolls toward it, not all of them at launch). See BrowseServiceGates.
            com.liskovsoft.youtubeapi.browse.v2.BrowseServiceGates.SectionListPacer pacer =
                    com.liskovsoft.youtubeapi.browse.v2.BrowseServiceGates.getSectionListPacer();
            if (pacer != null && nextKey != null
                    && !pacer.awaitNextPage(groups.get(0).getType(), page + 2, emitter::isDisposed)) {
                android.util.Log.d("NetPath", "browse-stop paced before continuation page=" + (page + 1)
                        + " type=" + groups.get(0).getType());
                return;
            }

            groupsAndKey = getBrowseService2().continueSectionList(nextKey, groups.get(0).getType());
            groups = groupsAndKey != null ? groupsAndKey.getFirst() : null;
            nextKey = groupsAndKey != null ? groupsAndKey.getSecond() : null;
            page++;
        }
    }

    private void emitGroups(ObservableEmitter<List<MediaGroup>> emitter, List<MediaGroup> groups) {
        emitGroupsPartial(emitter, groups);

        emitter.onComplete();
    }

    private void emitGroupsPartial(ObservableEmitter<List<MediaGroup>> emitter, List<MediaGroup> groups) {
        emitGroupsPartial(emitter, groups, false);
    }

    /**
     * @param prefetchEmptyGroups NEWTUBE(channel-tabs): fetch the empty (lazily-loaded) groups of this
     *                            page concurrently instead of one after another
     * @return false if the subscriber went away and the walk stopped early
     */
    private boolean emitGroupsPartial(ObservableEmitter<List<MediaGroup>> emitter, List<MediaGroup> groups, boolean prefetchEmptyGroups) {
        return emitGroupsPartial(emitter, groups, group -> getBrowseService2().continueEmptyGroup(group),
                prefetchEmptyGroups ? getEmptyGroupExecutor() : null);
    }

    /** Loads the content of an empty (lazily-loaded) group. */
    interface EmptyGroupLoader {
        List<MediaGroup> load(MediaGroup group);
    }

    /**
     * Emits a page of groups in order, loading each empty group in its turn.<br/>
     * NEWTUBE(channel-tabs): with a {@code prefetchExecutor}, the page's empty groups are loaded
     * concurrently instead of one after another. Emission order and error propagation are unchanged:
     * results are still emitted in page order, and a failing load still throws when its turn comes.
     * Nothing new starts after the subscriber goes away (queued loads are cancelled on dispose); a
     * request already in flight completes - threads are never interrupted.
     * @return false if the subscriber went away and the walk stopped early
     */
    static boolean emitGroupsPartial(ObservableEmitter<List<MediaGroup>> emitter, List<MediaGroup> groups,
                                     EmptyGroupLoader loader, @Nullable ExecutorService prefetchExecutor) {
        if (groups == null || groups.isEmpty()) {
            Log.e(TAG, "emitGroupsPartial: groups are null or empty");
            return true;
        }

        MediaGroup firstGroup = groups.get(0);
        Log.d(TAG, "emitGroupsPartial: begin emitting group of type %s...", firstGroup != null ? firstGroup.getType() : null);

        List<Future<List<MediaGroup>>> prefetched = prefetchExecutor != null ?
                prefetchEmptyGroups(emitter, groups, loader, prefetchExecutor) : null;

        try {
            List<MediaGroup> collector = new ArrayList<>();

            for (int i = 0; i < groups.size(); i++) { // Preserve positions
                MediaGroup group = groups.get(i);

                if (group == null) {
                    continue;
                }

                if (group.isEmpty()) { // Contains Chips (nested sections)?
                    if (!collector.isEmpty()) {
                        emitter.onNext(collector);
                        collector = new ArrayList<>();
                    }

                    // NEWTUBE(dispose-stop): each empty group costs a request (a channel's tabs)
                    if (emitter.isDisposed()) {
                        android.util.Log.d("NetPath", "browse-stop disposed before empty group " + i + "/" + groups.size()
                                + " type=" + group.getType());
                        return false;
                    }

                    List<MediaGroup> sections = prefetched != null && prefetched.get(i) != null ?
                            awaitPrefetched(prefetched.get(i)) : loader.load(group);

                    if (sections != null) {
                        emitter.onNext(sections);
                    }
                } else {
                    collector.add(group);
                }
            }

            if (!collector.isEmpty()) {
                emitter.onNext(collector);
            }
        } finally {
            cancelAll(prefetched); // early exit: drop what hasn't started
        }

        return true;
    }

    /**
     * NEWTUBE(channel-tabs): a channel page arrives with one filled tab and the rest empty, each
     * needing its own /browse. They used to load strictly one after another (the tab bar filling in
     * one round trip per tab). Returns one future per position (null for non-empty groups), or null
     * when there is nothing to overlap or the subscriber is already gone.
     */
    @Nullable
    private static List<Future<List<MediaGroup>>> prefetchEmptyGroups(ObservableEmitter<List<MediaGroup>> emitter, List<MediaGroup> groups,
                                                                      EmptyGroupLoader loader, ExecutorService executor) {
        int count = 0;

        for (MediaGroup group : groups) {
            if (group != null && group.isEmpty()) {
                count++;
            }
        }

        if (count < 2) {
            return null; // nothing to overlap: keep the plain serial path
        }

        // The channel's first /browse can finish after the user already left: start nothing then
        if (emitter.isDisposed()) {
            return null;
        }

        List<Future<List<MediaGroup>>> futures = new ArrayList<>(Collections.nCopies(groups.size(), null));

        for (int i = 0; i < groups.size(); i++) {
            MediaGroup group = groups.get(i);

            if (group != null && group.isEmpty()) {
                // A queued load that gets its thread after dispose (before the cancel lands) does nothing
                futures.set(i, executor.submit(() -> emitter.isDisposed() ? null : loader.load(group)));
            }
        }

        // Dispose cancels whatever hasn't started. Set after the list is complete: an emitter that got
        // disposed meanwhile runs the cancellable right away.
        emitter.setCancellable(() -> cancelAll(futures));

        android.util.Log.d("NetPath", "channel-tabs prefetch empty=" + count + " of " + groups.size()
                + " parallel=" + Math.min(count, EMPTY_GROUP_PREFETCH_THREADS));

        return futures;
    }

    private static void cancelAll(@Nullable List<Future<List<MediaGroup>>> futures) {
        if (futures == null) {
            return;
        }

        for (Future<List<MediaGroup>> future : futures) {
            if (future != null) {
                future.cancel(false); // never interrupt: a request in flight completes
            }
        }
    }

    @Nullable
    private static List<MediaGroup> awaitPrefetched(Future<List<MediaGroup>> future) {
        try {
            return future.get();
        } catch (CancellationException e) {
            return null; // disposed meanwhile - the caller's next isDisposed() check ends the walk
        } catch (ExecutionException e) {
            // Same exception the serial call would have thrown on this thread
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ExecutorService getEmptyGroupExecutor() {
        ExecutorService result = sEmptyGroupExecutor;

        if (result == null) {
            synchronized (YouTubeContentService.class) {
                result = sEmptyGroupExecutor;
                if (result == null) {
                    ThreadPoolExecutor executor = new ThreadPoolExecutor(EMPTY_GROUP_PREFETCH_THREADS, EMPTY_GROUP_PREFETCH_THREADS,
                            30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
                                Thread thread = new Thread(r, "channel-tabs");
                                thread.setDaemon(true);
                                return thread;
                            });
                    executor.allowCoreThreadTimeOut(true);
                    sEmptyGroupExecutor = result = executor;
                }
            }
        }

        return result;
    }

    private void emitGroup(ObservableEmitter<MediaGroup> emitter, MediaGroup group) {
        emitGroupPartial(emitter, group);

        emitter.onComplete();
    }

    private void emitGroupPartial(ObservableEmitter<MediaGroup> emitter, MediaGroup group) {
        if (group == null) {
            Log.e(TAG, "emitGroupPartial: group is null");
            return;
        }

        Log.d(TAG, "emitGroupPartial: begin emitting group of type %s...", group.getType());

        emitter.onNext(group);
    }

    @Override
    public MediaGroup continueGroup(MediaGroup mediaGroup) {
        MediaGroup result = continueGroupChecked(mediaGroup);

        if (result == null) {
            return null;
        }

        if (result.isEmpty()) {
            // All contents has been filtered (e.g. shorts)
            return continueGroupChecked(result);
        }

        return result;
    }

    private MediaGroup continueGroupChecked(MediaGroup mediaGroup) {
        MediaGroup result = continueGroupInt(mediaGroup);

        if (result == null) {
            return null;
        }

        if (Helpers.equals(mediaGroup.getMediaItems(), result.getMediaItems()) &&
                Helpers.equals(mediaGroup.getNextPageKey(), result.getNextPageKey())) {
            // Result group is duplicate of the original. Seems that we've reached the end before. Skipping...
            return null;
        }

        return result;
    }

    private MediaGroup continueGroupInt(MediaGroup mediaGroup) {
        if (mediaGroup == null) {
            return null;
        }

        checkSigned();

        Log.d(TAG, "Continue group " + mediaGroup.getTitle() + "...");

        if (mediaGroup instanceof SuggestionsGroup) {
            return getWatchNextService().continueGroup(mediaGroup);
        }

        if (mediaGroup instanceof SearchSectionMediaGroup || mediaGroup instanceof SearchContinuationMediaGroup) {
            return getSearchService2().continueSearch(mediaGroup.getNextPageKey());
        }

        if (mediaGroup instanceof BaseMediaGroup) {
            MediaGroup group = null;

            // Fix channels with multiple empty groups (e.g. https://www.youtube.com/@RuhiCenetMedya/videos)
            for (int i = 0; i < 3; i++) {
                group = getBrowseService2().continueGroup(group == null ? mediaGroup : group);

                if (group == null || !group.isEmpty()) {
                    break;
                }
            }

            return group;
        }

        String nextKey = YouTubeHelper.extractNextKey(mediaGroup);

        switch (mediaGroup.getType()) {
            case MediaGroup.TYPE_SEARCH:
                return YouTubeMediaGroup.from(
                        getSearchService().continueSearch(nextKey),
                        mediaGroup);
            case MediaGroup.TYPE_HISTORY:
            case MediaGroup.TYPE_SUBSCRIPTIONS:
            case MediaGroup.TYPE_USER_PLAYLISTS:
            case MediaGroup.TYPE_CHANNEL_UPLOADS:
            case MediaGroup.TYPE_UNDEFINED:
                return YouTubeMediaGroup.from(
                        getBrowseService().continueGridTab(nextKey),
                        mediaGroup
                );
            default:
                return YouTubeMediaGroup.from(
                        getBrowseService().continueSection(nextKey),
                        mediaGroup
                );
        }
    }

    @Override
    public Observable<MediaGroup> continueGroupObserve(MediaGroup mediaGroup) {
        return RxHelper.fromCallable(() -> continueGroup(mediaGroup));
    }

    private void checkSigned() {
        getSignInService().checkAuth();
    }

    @Override
    public Observable<List<MediaGroup>> getPlaylistRowsObserve() {
        return RxHelper.create(emitter -> {
            checkSigned();

            MediaGroup playlists = getPlaylists();

            if (playlists != null && playlists.getMediaItems() != null) {
                for (MediaItem playlist : playlists.getMediaItems()) {
                    kotlin.Pair<List<MediaGroup>, String> content = getBrowseService2().getChannel(playlist.getChannelId(), playlist.getParams());
                    if (content != null && content.getFirst() != null) {
                        MediaGroup mediaGroup = content.getFirst().get(0);
                        if (mediaGroup instanceof BaseMediaGroup) {
                            ((BaseMediaGroup) mediaGroup).setTitle(playlist.getTitle());
                        }
                        emitter.onNext(content.getFirst());
                    }
                }
                emitter.onComplete();
            } else {
                RxHelper.onError(emitter, "getPlaylistsRowObserve: the content is null");
            }
        });
    }

    @Override
    public Observable<MediaGroup> getPlaylistsObserve() {
        return RxHelper.fromCallable(this::getPlaylists);
    }

    private MediaGroup getPlaylists() {
        checkSigned();

        return getBrowseService2().getMyPlaylists();
    }

    @Override
    public void enableHistory(boolean enable) {
        if (AccountWrites.blocked(enable ? "history-resume" : "history-pause")) {
            return;
        }
        if (enable) {
            getActionsService().resumeWatchHistory();
        } else {
            getActionsService().pauseWatchHistory();
        }
    }

    @Override
    public void clearHistory() {
        if (AccountWrites.blocked("history-clear")) {
            return;
        }
        getActionsService().clearWatchHistory();
    }

    @Override
    public void clearSearchHistory() {
        getActionsService().clearSearchHistory();
        getSearchService2().clearSearchHistory();
    }

    @Override
    public void removeSearchTag(String tag) {
        getSearchService2().removeTag(tag);
    }

    @NonNull
    private static YouTubeSignInService getSignInService() {
        return YouTubeSignInService.instance();
    }

    @NonNull
    private static ActionsService getActionsService() {
        return ActionsServiceWrapper.instance();
    }

    @NonNull
    private static SearchService getSearchService() {
        return SearchServiceWrapper.instance();
    }

    @NonNull
    private static SearchService2 getSearchService2() {
        return SearchService2Wrapper.INSTANCE;
    }

    @NonNull
    private static BrowseService getBrowseService() {
        return BrowseService.instance();
    }

    @NonNull
    private static BrowseService2 getBrowseService2() {
        return BrowseService2Wrapper.INSTANCE;
    }

    @NonNull
    private static WatchNextService getWatchNextService() {
        return WatchNextServiceWrapper.INSTANCE;
    }

    @Nullable
    private static MediaServiceData getMediaServiceData() {
        return MediaServiceData.instance();
    }
}
