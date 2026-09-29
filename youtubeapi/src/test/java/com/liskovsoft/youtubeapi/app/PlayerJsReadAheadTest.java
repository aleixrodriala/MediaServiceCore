package com.liskovsoft.youtubeapi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NEWTUBE(player-js-gate): a /player request that may skip the validation waits for the player's JS
 * to be read, and for nothing else; anything it cannot use sends it back to the extractor.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public class PlayerJsReadAheadTest {
    private static final String PLAYER = "https://www.youtube.com/s/player/aaaa1111/tv-player-ias.vflset/tv-player-ias.js";
    private static final String OTHER = "https://www.youtube.com/s/player/bbbb2222/tv-player-ias.vflset/tv-player-ias.js";

    private final PlayerJsReadAhead readAhead = new PlayerJsReadAhead();
    private final AtomicInteger builds = new AtomicInteger();
    private final List<PlayerJsReadAhead.Fallback> fallbacks = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @After
    public void tearDown() {
        executor.shutdownNow();
    }

    @Test
    public void aBuiltPlayerIsAskedAsBefore() {
        readAhead.markReady(PLAYER);

        assertNull(await(PLAYER));
        assertEquals("no build is started for a built player", 0, builds.get());
        assertTrue("the everyday case logs nothing", fallbacks.isEmpty());
    }

    @Test
    public void aRequestWaitsOnlyForTheJsToBeRead() throws Exception {
        Future<PlayerJsReadAhead.Data> request = executor.submit(() -> await(PLAYER));
        assertStillWaiting(request);
        assertEquals("the request starts the validation in the background", 1, builds.get());

        readAhead.publish(PLAYER, "20697", "cpn-code", 1_000);
        PlayerJsReadAhead.Data data = request.get(5, TimeUnit.SECONDS);

        assertNotNull(data);
        assertEquals("20697", data.signatureTimestamp);
        assertEquals("cpn-code", data.cpnCode);
        assertTrue("the validation is still running", readAhead.hasPending());
        assertTrue(readAhead.hasPlayer());

        readAhead.markReady(PLAYER);
        assertFalse(readAhead.hasPending());
        assertNull("once built, requests ask the extractor again", await(PLAYER));
    }

    @Test
    public void aLaterRequestTakesTheReadAheadAtOnce() {
        readAhead.publish(PLAYER, "20697", null, 1_000);

        PlayerJsReadAhead.Data data = await(PLAYER);

        assertNotNull(data);
        assertNull(data.cpnCode);
        assertEquals("the validation is kept going", 1, builds.get());
    }

    @Test
    public void anotherPlayersJsDoesNotReleaseTheRequest() throws Exception {
        Future<PlayerJsReadAhead.Data> request = executor.submit(() -> await(PLAYER));
        assertStillWaiting(request);

        readAhead.publish(OTHER, "20600", "cpn-code", 1_000);
        assertStillWaiting(request);

        readAhead.buildEnded();
        assertNull(request.get(5, TimeUnit.SECONDS));
        assertEquals(PlayerJsReadAhead.Fallback.BUILD_ENDED, onlyFallback());
    }

    @Test
    public void aBuildThatReadsNothingSendsTheRequestBackToTheExtractor() throws Exception {
        Future<PlayerJsReadAhead.Data> request = executor.submit(() -> await(PLAYER));
        assertStillWaiting(request);

        // An unreadable JS or no timestamp in it: the build publishes nothing and ends.
        readAhead.buildEnded();

        assertNull(request.get(5, TimeUnit.SECONDS));
        assertEquals(PlayerJsReadAhead.Fallback.BUILD_ENDED, onlyFallback());
    }

    @Test
    public void aPlayerRestoredFromTheCacheIsAskedAsBefore() throws Exception {
        Future<PlayerJsReadAhead.Data> request = executor.submit(() -> await(PLAYER));
        assertStillWaiting(request);

        // A player validated by an earlier process is rebuilt from the cache without a read-ahead.
        readAhead.markReady(PLAYER);
        readAhead.buildEnded();

        assertNull(request.get(5, TimeUnit.SECONDS));
        assertEquals(PlayerJsReadAhead.Fallback.READY, onlyFallback());
    }

    @Test
    public void anAbandonedAttemptStopsWaitingAndKeepsItsInterrupt() throws Exception {
        Future<Boolean> request = executor.submit(() -> {
            PlayerJsReadAhead.Data data = await(PLAYER);
            return data == null && Thread.currentThread().isInterrupted();
        });
        assertStillWaiting(request);

        request.cancel(true);
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(PlayerJsReadAhead.Fallback.INTERRUPTED, onlyFallback());
    }

    @Test
    public void noPlayerUrlStartsNothing() {
        assertNull(await(null));
        assertEquals(0, builds.get());
        assertEquals(PlayerJsReadAhead.Fallback.NO_URL, onlyFallback());
    }

    @Test
    public void readForMatchesOnlyItsPlayer() {
        assertFalse(readAhead.hasPlayer());
        readAhead.publish(PLAYER, "20697", "cpn-code", 1_000);

        assertSame(readAhead.readFor(PLAYER), readAhead.readFor(PLAYER));
        assertNotNull(readAhead.readFor(PLAYER));
        assertNull(readAhead.readFor(OTHER));
        assertNull(readAhead.readFor(null));
    }

    /** Codex review: a build that throws after its read-ahead must not keep answering requests. */
    @Test
    public void aFailedBuildsReadAheadServesNoOne() throws Exception {
        readAhead.publish(PLAYER, "20697", "cpn-code", 1_000);
        readAhead.discard(OTHER);
        assertNotNull("another player's failure discards nothing", readAhead.readFor(PLAYER));

        readAhead.discard(PLAYER);
        readAhead.buildEnded();

        assertNull(readAhead.readFor(PLAYER));
        assertFalse("no extractor, no cached answer is actual on its account", readAhead.hasPlayer());
        Future<PlayerJsReadAhead.Data> request = executor.submit(() -> await(PLAYER));
        assertStillWaiting(request);
        readAhead.buildEnded(); // the next build fails as well
        assertNull(request.get(5, TimeUnit.SECONDS));
    }

    /**
     * Codex review: the background build thread signals once more after it stops counting as
     * running, so a request that saw it running just after its build ended is not left waiting.
     */
    @Test
    public void aRequestThatSawAFinishingBuildIsWokenByItsExit() throws Exception {
        Future<PlayerJsReadAhead.Data> request = executor.submit(
                () -> readAhead.await(PLAYER, () -> true, this::onFallback)); // "one is running"
        assertStillWaiting(request);

        readAhead.buildEnded(); // the thread's exit signal

        assertNull(request.get(5, TimeUnit.SECONDS));
        assertEquals(PlayerJsReadAhead.Fallback.BUILD_ENDED, onlyFallback());
    }

    @Test
    public void aBuildThatCannotStartSendsTheRequestBackAtOnce() {
        PlayerJsReadAhead.Data data = readAhead.await(PLAYER, () -> false, this::onFallback);

        assertNull(data);
        assertEquals(PlayerJsReadAhead.Fallback.NO_BUILD, onlyFallback());
    }

    private PlayerJsReadAhead.Data await(String playerUrl) {
        return readAhead.await(playerUrl, () -> {
            builds.incrementAndGet();
            return true;
        }, this::onFallback);
    }

    private void onFallback(PlayerJsReadAhead.Fallback reason, long waitedMs) {
        synchronized (fallbacks) {
            fallbacks.add(reason);
        }
    }

    private PlayerJsReadAhead.Fallback onlyFallback() {
        synchronized (fallbacks) {
            assertEquals(fallbacks.toString(), 1, fallbacks.size());
            return fallbacks.get(0);
        }
    }

    private static void assertStillWaiting(Future<?> request) throws Exception {
        try {
            request.get(200, TimeUnit.MILLISECONDS);
            throw new AssertionError("the request did not wait");
        } catch (TimeoutException expected) {
            // still waiting
        }
    }
}
