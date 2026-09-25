package com.liskovsoft.youtubeapi.service;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaGroup;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.observers.TestObserver;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * NEWTUBE(channel-tabs): a channel page's empty tabs load concurrently, keep their emission order,
 * and nothing new starts once the subscriber is gone.
 */
@RunWith(RobolectricTestRunner.class)
public class ChannelTabPrefetchTest {
    private final List<String> mLoaded = Collections.synchronizedList(new ArrayList<>());
    private ExecutorService mExecutor;

    @After
    public void tearDown() {
        if (mExecutor != null) {
            mExecutor.shutdownNow();
        }
    }

    private static MediaGroup group(String title, boolean empty) {
        YouTubeMediaGroup group = new YouTubeMediaGroup(MediaGroup.TYPE_CHANNEL);
        group.setTitle(title);
        if (!empty) {
            List<MediaItem> items = new ArrayList<>();
            items.add(new YouTubeMediaItem());
            group.setMediaItems(items);
        }
        return group;
    }

    private static List<MediaGroup> loaded(MediaGroup group) {
        return Collections.singletonList(group(group.getTitle() + "'", false));
    }

    private static List<List<String>> titles(TestObserver<List<MediaGroup>> observer) {
        List<List<String>> result = new ArrayList<>();
        for (List<MediaGroup> emission : observer.values()) {
            List<String> titles = new ArrayList<>();
            for (MediaGroup group : emission) {
                titles.add(group.getTitle());
            }
            result.add(titles);
        }
        return result;
    }

    private TestObserver<List<MediaGroup>> run(TestObserver<List<MediaGroup>> observer, List<MediaGroup> groups,
                                               YouTubeContentService.EmptyGroupLoader loader, ExecutorService executor) {
        Observable.<List<MediaGroup>>create(emitter -> {
            YouTubeContentService.emitGroupsPartial(emitter, groups, loader, executor);
            emitter.onComplete();
        }).subscribe(observer);
        return observer;
    }

    @Test
    public void emptyTabsLoadConcurrentlyInPageOrder() throws InterruptedException {
        mExecutor = Executors.newFixedThreadPool(3);
        CountDownLatch allStarted = new CountDownLatch(3);
        List<MediaGroup> page = Arrays.asList(group("Videos", false), group("Shorts", true), group("Live", true),
                group("Featured", false), group("Playlists", true));

        TestObserver<List<MediaGroup>> observer = run(new TestObserver<>(), page, group -> {
            allStarted.countDown();
            try {
                // A serial walk never gets all three loads running at once
                assertTrue("tabs load in parallel", allStarted.await(5, TimeUnit.SECONDS));
                Thread.sleep("Shorts".equals(group.getTitle()) ? 80 : 0); // first tab finishes last
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return loaded(group);
        }, mExecutor);

        observer.await(5, TimeUnit.SECONDS);
        observer.assertComplete();
        assertEquals(Arrays.asList(
                Collections.singletonList("Videos"),
                Collections.singletonList("Shorts'"),
                Collections.singletonList("Live'"),
                Collections.singletonList("Featured"),
                Collections.singletonList("Playlists'")), titles(observer));
    }

    @Test
    public void disposedBeforeThePageArrivesStartsNothing() {
        mExecutor = Executors.newFixedThreadPool(3);
        TestObserver<List<MediaGroup>> observer = new TestObserver<>();
        observer.dispose(); // left the channel while its first /browse was in flight

        run(observer, Arrays.asList(group("Videos", false), group("Shorts", true), group("Live", true)),
                group -> { mLoaded.add(group.getTitle()); return loaded(group); }, mExecutor);

        assertTrue("no tab request after dispose", mLoaded.isEmpty());
    }

    @Test
    public void disposeMidPageCancelsTheLoadsThatHaveNotStarted() throws InterruptedException {
        mExecutor = Executors.newSingleThreadExecutor(); // deterministic: one load at a time
        TestObserver<List<MediaGroup>> observer = new TestObserver<>();
        CountDownLatch walkDone = new CountDownLatch(1);
        AtomicReference<Boolean> walkResult = new AtomicReference<>();
        List<MediaGroup> page = Arrays.asList(group("Videos", false), group("Shorts", true), group("Live", true),
                group("Playlists", true), group("Podcasts", true));

        Observable.<List<MediaGroup>>create(emitter -> {
            walkResult.set(YouTubeContentService.emitGroupsPartial(emitter, page, group -> {
                mLoaded.add(group.getTitle());
                observer.dispose(); // the user backs out while the first tab loads
                return loaded(group);
            }, mExecutor));
            walkDone.countDown();
        }).subscribe(observer);

        assertTrue(walkDone.await(5, TimeUnit.SECONDS));
        mExecutor.shutdown();
        assertTrue(mExecutor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals("only the load already running when the user left", Collections.singletonList("Shorts"), mLoaded);
        assertFalse("walk reports the early stop", walkResult.get());
    }

    @Test
    public void aFailingTabStillFailsInItsTurn() throws InterruptedException {
        mExecutor = Executors.newFixedThreadPool(3);
        IllegalStateException failure = new IllegalStateException("live tab offline");
        List<MediaGroup> page = Arrays.asList(group("Videos", false), group("Shorts", true), group("Live", true),
                group("Playlists", true));

        TestObserver<List<MediaGroup>> observer = run(new TestObserver<>(), page, group -> {
            if ("Live".equals(group.getTitle())) {
                throw failure;
            }
            return loaded(group);
        }, mExecutor);

        observer.await(5, TimeUnit.SECONDS);
        observer.assertError(failure);
        assertEquals(Arrays.asList(Collections.singletonList("Videos"), Collections.singletonList("Shorts'")), titles(observer));
    }

    @Test
    public void singleEmptyTabTakesTheSerialPath() {
        mExecutor = Executors.newSingleThreadExecutor();
        List<String> threads = new ArrayList<>();
        String caller = Thread.currentThread().getName();

        TestObserver<List<MediaGroup>> observer = run(new TestObserver<>(),
                Arrays.asList(group("Videos", false), group("Shorts", true)),
                group -> { threads.add(Thread.currentThread().getName()); return loaded(group); }, mExecutor);

        observer.assertComplete();
        assertEquals(Collections.singletonList(caller), threads);
    }
}
