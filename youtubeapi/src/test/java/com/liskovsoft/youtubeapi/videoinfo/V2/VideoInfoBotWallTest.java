package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Converter;

/**
 * The REAL phone walk (firstPlayable, all mobile gates on) against scripted /player answers:
 * how many requests an open costs on a walled network, and that a healthy one is untouched.
 * No HTTP: the per-attempt request is shadowed, everything above it is production code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoBotWallTest.ShadowWalk.class,
                VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoBotWallTest {
    private static final String NOT_A_BOT = "Inicia sesión para confirmar que no eres un bot";
    private VideoInfoService service;

    @Before
    public void setUp() {
        ShadowWalk.network = "cell:106";
        ShadowWalk.transport = "cell";
        ShadowWalk.signedIn = false;
        ShadowWalk.calls.clear();
        ShadowWalk.script = (client, auth) -> playable(auth);
        VideoInfoService.setPreferNoPotClient(true);
        VideoInfoService.setPreferAttestedWebFallback(true);
        VideoInfoService.setSkipTvFallbackClients(true);
        VideoInfoService.setPreferDashManifestForLive(true);
        VideoInfoService.setSkipWebEmbed(true); // as MobileMainApplication does
        service = ReflectionHelpers.callConstructor(VideoInfoService.class);
        // The shadowed constructor skips field initializers; give the instance what the walk uses.
        initIfNull("mAuthRouteQuarantine", new AuthRouteQuarantineBook());
        initIfNull("mBotWall", new BotWallBook());
        initIfNull("mVideoWinners", java.util.Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, AppClient>()));
        initIfNull("mRoutingGeneration", new java.util.concurrent.atomic.AtomicLong());
    }

    private void initIfNull(String field, Object value) {
        if (ReflectionHelpers.getField(service, field) == null) {
            ReflectionHelpers.setField(service, field, value);
        }
    }

    @After
    public void tearDown() {
        VideoInfoService.setPreferNoPotClient(false);
        VideoInfoService.setPreferAttestedWebFallback(false);
        VideoInfoService.setSkipTvFallbackClients(false);
        VideoInfoService.setPreferDashManifestForLive(false);
        VideoInfoService.setSkipWebEmbed(false);
        VideoInfoService.setDebugBotWallSource(null);
        VideoInfoService.setDebugForcedClient(null);
    }

    /** Healthy network: exactly the pre-existing single request, the account route never asked. */
    @Test
    public void aHealthyOpenIsUntouched() {
        ShadowWalk.keyLookups = 0;
        VideoInfo anon = open("v1");
        assertFalse(anon.isUnplayable());
        assertEquals(Collections.singletonList("VISIONOS"), ShadowWalk.calls);
        assertEquals("no extra network lookups on a healthy open", 0, ShadowWalk.keyLookups);

        ShadowWalk.signedIn = true;
        quarantineBothAccountHeads();
        ShadowWalk.calls.clear();
        open("v2");
        assertEquals(Collections.singletonList("VISIONOS"), ShadowWalk.calls);
    }

    /**
     * Replay of the 2026-09-25 LTE state, signed in: every anonymous client answers "not a bot",
     * TV is SABR-only, TV_DOWNGRADED is "playable" with dead URLs. Before: ~20 /player per open and
     * nothing played. Now: the account route serves the first open on attempt 2, the second open
     * establishes the wall, and from then on an open is ONE request plus a probe per minute.
     */
    @Test
    public void signedInUnderTheWallThePhoneStillPlaysAndStopsWalkingTheRing() {
        ShadowWalk.signedIn = true;
        quarantineBothAccountHeads();
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        ShadowWalk.calls.clear();

        VideoInfo first = open("a");
        assertEquals(AppClient.TV_TIZEN, first.getClient());
        assertFalse(first.isUnplayable());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), drain());

        open("b"); // second video: the suspicion is confirmed, the wall is established
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), drain());

        VideoInfo walled = open("c");
        assertEquals(AppClient.TV_TIZEN, walled.getClient());
        assertEquals(Collections.singletonList("TV_TIZEN+auth"), drain());

        ShadowSystemClock.advanceBy(Duration.ofMillis(BotWallBook.PROBE_BASE_MS));
        open("d");
        assertEquals("no routine anonymous probing while the account route plays",
                Collections.singletonList("TV_TIZEN+auth"), drain());
        ShadowSystemClock.advanceBy(Duration.ofMillis(
                BotWallBook.AUTH_PROBE_BASE_MS - BotWallBook.PROBE_BASE_MS));
        open("e");
        assertEquals("a slow re-probe of the token-free head",
                Arrays.asList("VISIONOS", "TV_TIZEN+auth"), drain());
        open("f");
        assertEquals(Collections.singletonList("TV_TIZEN+auth"), drain());
    }

    /**
     * A media 403 on the account route benches it for THAT video, so its recovery reloads cost
     * zero requests; other videos keep the route until a second one fails too (Codex P1).
     */
    @Test
    public void aDeadAccountRouteIsNotReboughtByEveryRecovery() {
        ShadowWalk.signedIn = true;
        quarantineBothAccountHeads();
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        open("a");
        open("b");
        drain();

        mediaForbidden("b", AppClient.TV_TIZEN);

        for (int reload = 0; reload < 3; reload++) {
            VideoInfo verdict = open("b");
            assertTrue(verdict.isUnplayable());
            assertTrue(verdict.isBotCheckRequired());
            assertNull("no other video's details leak in", verdict.getVideoDetails());
            assertTrue("YouTube's own reason", verdict.getPlayabilityStatus().contains(NOT_A_BOT));
        }
        assertEquals(Collections.emptyList(), drain());
        assertEquals("the auth-head quarantine arithmetic is untouched", 2,
                ((java.util.Set<?>) ReflectionHelpers.callInstanceMethod(service,
                        "forbiddenAuthClients")).size());

        assertEquals("one bad URL does not take the route from another video",
                Collections.singletonList("TV_TIZEN+auth"), requestsFor("c"));
        mediaForbidden("c", AppClient.TV_TIZEN);
        assertEquals("a second video: benched for the attachment",
                Collections.emptyList(), requestsFor("d"));

        ShadowWalk.network = "cell:107"; // a new attachment on the same transport
        ShadowWalk.script = (client, auth) -> client == AppClient.VISIONOS
                ? challenged(auth) : playable(auth);
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), requestsFor("e"));
    }

    /**
     * Signed out, the same wall: the first walk stops at the second challenged platform client
     * (7 requests instead of 11 - WEB_EMBED is skipped too), and later opens ask nobody until the
     * next probe.
     */
    @Test
    public void signedOutUnderTheWallTheSecondOpenCostsNothing() {
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;

        VideoInfo first = open("a");
        assertTrue(first.isBotCheckRequired());
        assertEquals(Arrays.asList("VISIONOS", "WEB", "WEB_SAFARI", "GEO", "MWEB",
                "ANDROID_VR", "TV_TIZEN"), drain());

        VideoInfo second = open("b");
        assertTrue(second.isBotCheckRequired());
        assertEquals(Collections.emptyList(), drain());

        ShadowSystemClock.advanceBy(Duration.ofMillis(BotWallBook.PROBE_BASE_MS));
        assertTrue(open("c").isBotCheckRequired());
        assertEquals(Collections.singletonList("VISIONOS"), drain());
    }

    /** The probe that finds the wall gone restores the normal ring at once. */
    @Test
    public void theWallEndsTheMomentAnAnonymousClientServes() {
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        open("a");
        drain();

        ShadowWalk.script = (client, auth) -> playable(auth);
        ShadowSystemClock.advanceBy(Duration.ofMillis(BotWallBook.PROBE_BASE_MS));
        assertFalse(open("b").isUnplayable());
        assertEquals(Collections.singletonList("VISIONOS"), drain());

        ShadowWalk.script = (client, auth) -> client == AppClient.VISIONOS
                ? challenged(auth) : playable(auth);
        open("c");
        // Back to the ordinary ring (the Web partition is still deprioritized by the separate
        // anonymous-challenge memory, which only a Web serve clears): no wall, no account route.
        assertEquals(Arrays.asList("VISIONOS", "ANDROID_VR"), drain());
        assertFalse(isWalled());
    }

    /** A different network attachment is not walled. */
    @Test
    public void anotherNetworkIsNotWalled() {
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        open("a");
        drain();

        ShadowWalk.network = "wifi:100";
        ShadowWalk.transport = "wifi";
        ShadowWalk.script = (client, auth) -> playable(auth);
        open("b");
        assertEquals(Collections.singletonList("VISIONOS"), drain());
    }

    /** An age gate is not a bot check: the account route serves it, the network is not walled. */
    @Test
    public void anAgeGateGoesToTheAccountRouteWithoutWallingAnything() {
        ShadowWalk.signedIn = true;
        quarantineBothAccountHeads();
        ShadowWalk.script = (client, auth) -> client == AppClient.VISIONOS
                ? parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                        + " \"reason\": \"Sign in to confirm your age\"}}", auth)
                : playable(auth);
        ShadowWalk.calls.clear();

        for (String video : new String[] {"a", "b", "c"}) {
            assertEquals(AppClient.TV_TIZEN, open(video).getClient());
            assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), drain());
        }
        assertFalse(isWalled());
    }

    /** debug.arc.botwall anon on a healthy network reproduces the signed-in wall end to end. */
    @Test
    public void debugInjectionWallsTheAnonymousAnswersOnly() {
        ShadowWalk.signedIn = true;
        quarantineBothAccountHeads();
        VideoInfoService.setDebugBotWallSource(() -> "anon");
        ShadowWalk.calls.clear();

        open("a");
        open("b");
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth", "VISIONOS", "TV_TIZEN+auth"), drain());
        assertTrue(isWalled());
        assertEquals(Collections.singletonList("TV_TIZEN+auth"), requestsFor("c"));

        // "all" walls the account route too: challenged on two videos it is benched for the
        // attachment, and the next open asks no one.
        VideoInfoService.setDebugBotWallSource(() -> "all");
        assertTrue(open("d").isBotCheckRequired());
        assertEquals(Collections.singletonList("TV_TIZEN+auth"), drain());
        assertTrue(open("e").isBotCheckRequired());
        assertEquals(Collections.singletonList("TV_TIZEN+auth"), drain());
        assertEquals(Collections.emptyList(), requestsFor("f"));
    }

    /**
     * Pixel 9, 2026-09-25, botwall run B (debug.arc.botwall anon, signed in, both TVHTML5 heads
     * quarantined on wifi), open 2: the two-video rule raised the wall, but TV_TIZEN - open 1's
     * winner, so already queued as the ring's last winner BEHIND the Web partition - was not moved
     * up: VISIONOS, WEB_EMBED, WEB, WEB_SAFARI, GEO, MWEB, then TV_TIZEN at attempt 7, and no
     * "account-route next" line. The account route is the next attempt after the challenge.
     */
    @Test
    public void runBOpenTwoGoesStraightToTheAccountRoute() {
        ShadowWalk.signedIn = true;
        quarantineBothAccountHeads();
        VideoInfoService.setDebugBotWallSource(() -> "anon");
        ShadowWalk.script = (client, auth) -> { // the real answers on that Wi-Fi, before injection
            switch (client) {
                case WEB_EMBED:
                    return parse("{\"playabilityStatus\": {\"status\": \"ERROR\", \"reason\":"
                            + " \"Video unavailable - Error code: 152 - 18\"}}", auth);
                case GEO:
                    return parse("{\"playabilityStatus\": {\"status\": \"ERROR\","
                            + " \"reason\": \"Watch in the latest version\"}}", auth);
                default:
                    return playable(auth);
            }
        };
        ShadowWalk.calls.clear();

        open("Fo89b8zAIE4");
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), drain());
        assertEquals(AppClient.TV_TIZEN, ReflectionHelpers.getField(service, "mActualInfoType"));

        org.robolectric.shadows.ShadowLog.clear();
        VideoInfo second = open("u_vnA6nlDvs");
        assertEquals(AppClient.TV_TIZEN, second.getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), drain());
        boolean established = false;
        boolean movedUp = false;
        for (org.robolectric.shadows.ShadowLog.LogItem item
                : org.robolectric.shadows.ShadowLog.getLogsForTag("NetPath")) {
            established |= item.msg.startsWith("player-ring botwall established")
                    && item.msg.contains("cause=second-video");
            movedUp |= item.msg.startsWith("player-ring account-route next reason=bot-check");
        }
        assertTrue(established);
        assertTrue(movedUp);

        assertEquals(Collections.singletonList("TV_TIZEN+auth"), requestsFor("aqz-KE-bpKQ"));
    }

    @Test
    public void theAccountRouteIsMovedUpWhereverItIsQueued() {
        List<AppClient> queuedLate = Arrays.asList(AppClient.VISIONOS, AppClient.WEB_EMBED,
                AppClient.WEB, AppClient.TV_TIZEN, AppClient.ANDROID_VR);
        assertEquals(Arrays.asList(AppClient.VISIONOS, AppClient.TV_TIZEN, AppClient.WEB_EMBED,
                AppClient.WEB, AppClient.ANDROID_VR),
                VideoInfoService.insertAfter(queuedLate, 0, AppClient.TV_TIZEN));
    }

    /**
     * Signed out, the probe rotates across families, and the first family that answers OK ends
     * the wall - even though VISIONOS keeps being refused (Codex visitor review).
     */
    @Test
    public void aRecoveredFamilyEndsTheWallWhileVisionosStaysRefused() {
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        open("a");
        drain();

        ShadowSystemClock.advanceBy(Duration.ofMillis(BotWallBook.probeIntervalMs(0, false)));
        assertEquals(Collections.singletonList("VISIONOS"), requestsFor("b"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(BotWallBook.probeIntervalMs(1, false)));
        assertEquals(Collections.singletonList("ANDROID_VR"), requestsFor("c"));

        // The Web family recovers; VISIONOS and ANDROID_VR are still refused.
        ShadowWalk.script = (client, auth) -> client == AppClient.WEB ? playable(auth)
                : walledLte(client, auth);
        ShadowSystemClock.advanceBy(Duration.ofMillis(BotWallBook.probeIntervalMs(2, false)));
        assertFalse(open("d").isUnplayable());
        assertEquals(Collections.singletonList("WEB"), drain());
        assertFalse("the recovered family ended the wall", isWalled());
    }

    /**
     * A cold restart under a wall resumes it from the store instead of re-walking the ring:
     * signed out it asks nobody, signed in it goes straight to the account route.
     */
    @Test
    public void aColdRestartResumesTheWallFromTheStore() {
        MemoryWallStore store = new MemoryWallStore();
        ReflectionHelpers.callInstanceMethod(service, "restoreBotWall",
                ClassParameter.from(VideoInfoService.BotWallStore.class, store));
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        open("a");
        drain();
        assertNotNull("the wall was saved", store.value);

        setUp(); // a new process: an empty book, restored from the store
        ReflectionHelpers.callInstanceMethod(service, "restoreBotWall",
                ClassParameter.from(VideoInfoService.BotWallStore.class, store));
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        assertTrue(isWalled());
        assertTrue(open("b").isBotCheckRequired());
        assertEquals(Collections.emptyList(), drain());

        ShadowWalk.signedIn = true;
        quarantineBothAccountHeads();
        assertEquals(Collections.singletonList("TV_TIZEN+auth"), requestsFor("c"));
    }

    /** Recovery reloads of one video are paid from that video's walled budget. */
    @Test
    public void recoveryTrafficCountsInTheWalledBudget() {
        ShadowWalk.signedIn = true;
        quarantineBothAccountHeads();
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        open("a");
        open("b");
        drain();

        for (int reload = 0; reload < BotWallBook.WALLED_VIDEO_BUDGET; reload++) {
            assertEquals(Collections.singletonList("TV_TIZEN+auth"), requestsFor("c"));
        }
        VideoInfo spent = open("c");
        assertTrue(spent.isBotCheckRequired());
        assertEquals(Collections.emptyList(), drain());
        assertEquals("another video is unaffected",
                Collections.singletonList("TV_TIZEN+auth"), requestsFor("d"));
    }

    /** debug.arc.botwall reset leaves a device test's phone as it found it. */
    @Test
    public void aDebugResetForgetsTheWallAndItsStoredCopy() {
        MemoryWallStore store = new MemoryWallStore();
        ReflectionHelpers.callInstanceMethod(service, "restoreBotWall",
                ClassParameter.from(VideoInfoService.BotWallStore.class, store));
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        open("a");
        assertTrue(isWalled());
        assertNotNull(store.value);

        VideoInfoService.setDebugBotWallSource(() -> "reset");
        ShadowWalk.script = (client, auth) -> playable(auth);
        drain();
        open("b");
        assertFalse(isWalled());
        assertNull(store.value);
        assertNull(ReflectionHelpers.getField(service, "mBotCheckResult"));
        assertEquals("reset injects nothing", Collections.singletonList("VISIONOS"), drain());
    }

    @Test
    public void injectionModes() {
        assertFalse(VideoInfoService.shouldInjectBotWall(null, AppClient.VISIONOS, false));
        for (String off : new String[] {"", " ", "none", "NONE", "0"}) {
            assertFalse(off, VideoInfoService.shouldInjectBotWall(off, AppClient.VISIONOS, false));
        }
        assertTrue(VideoInfoService.shouldInjectBotWall("anon", AppClient.VISIONOS, false));
        assertFalse(VideoInfoService.shouldInjectBotWall("anon", AppClient.TV_TIZEN, true));
        assertTrue(VideoInfoService.shouldInjectBotWall("anon", AppClient.TV_TIZEN, false));
        assertTrue(VideoInfoService.shouldInjectBotWall("all", AppClient.TV_TIZEN, true));
        assertTrue(VideoInfoService.shouldInjectBotWall("visionos, WEB", AppClient.WEB, false));
        assertFalse(VideoInfoService.shouldInjectBotWall("visionos, WEB", AppClient.IOS, false));
        assertFalse(VideoInfoService.shouldInjectBotWall("bogus", AppClient.IOS, false));
        assertFalse(VideoInfoService.shouldInjectBotWall("reset", AppClient.VISIONOS, false));
    }

    @Test
    public void accountRouteFailureIsNarrow() {
        assertEquals("challenged", VideoInfoService.accountRouteFailure(challenged(true)));
        assertEquals("reload-page", VideoInfoService.accountRouteFailure(parse(
                "{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\","
                        + " \"reason\": \"The page needs to be reloaded.\"}}", true)));
        assertEquals("sabr-only", VideoInfoService.accountRouteFailure(sabrOnly(true)));
        assertNull("a video this account may not watch says nothing about the route",
                VideoInfoService.accountRouteFailure(parse(
                        "{\"playabilityStatus\": {\"status\": \"ERROR\","
                                + " \"reason\": \"Video unavailable\"}}", true)));
        assertNull(VideoInfoService.accountRouteFailure(playable(true)));
    }

    @Test
    public void orderSplicing() {
        List<AppClient> order = Arrays.asList(AppClient.VISIONOS, AppClient.WEB_EMBED,
                AppClient.WEB, AppClient.TV_TIZEN, AppClient.IOS);
        assertEquals(Arrays.asList(AppClient.VISIONOS, AppClient.TV_TIZEN, AppClient.WEB_EMBED,
                AppClient.WEB, AppClient.IOS), VideoInfoService.insertAfter(order, 0, AppClient.TV_TIZEN));
        assertEquals(Arrays.asList(AppClient.VISIONOS, AppClient.WEB_EMBED, AppClient.TV_TIZEN),
                VideoInfoService.replaceRemaining(order, 1,
                        Arrays.asList(AppClient.VISIONOS, AppClient.TV_TIZEN),
                        java.util.EnumSet.of(AppClient.VISIONOS, AppClient.WEB_EMBED)));
    }

    @Test
    public void theAccountRouteGetsTheHeadBudgetAndCanBeForced() {
        assertEquals(VideoInfoService.attemptTimeoutMsFor(AppClient.TV_DOWNGRADED),
                VideoInfoService.attemptTimeoutMsFor(AppClient.TV_TIZEN));
        assertTrue(VideoInfoService.setDebugForcedClient("tv_tizen"));
    }

    /** Codex C3: a circuit armed on one attachment does not answer opens on another. */
    @Test
    public void theBotCheckCircuitIsScopedToItsAttachment() {
        assertTrue(VideoInfoService.isBotCheckCircuitStale("cell:106", "wifi:100"));
        assertFalse(VideoInfoService.isBotCheckCircuitStale("cell:106", "cell:106"));
        assertFalse("offline keeps it", VideoInfoService.isBotCheckCircuitStale("cell:106", null));
        assertFalse("TV never records one", VideoInfoService.isBotCheckCircuitStale(null, "wifi:1"));

        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        open("a"); // trips the circuit on cell:106
        assertNotNull(ReflectionHelpers.getField(service, "mBotCheckResult"));
        assertEquals("cell:106", ReflectionHelpers.getField(service, "mBotCheckNetwork"));

        ShadowWalk.network = "wifi:100";
        assertNull(ReflectionHelpers.callInstanceMethod(service, "getActiveBotCheckResult",
                ClassParameter.from(boolean.class, false), ClassParameter.from(String.class, "b")));
        assertNull(ReflectionHelpers.getField(service, "mBotCheckResult"));
    }

    /** Codex C2: a prefetch that won with another client must not take the blame. */
    @Test
    public void recoveryBlamesTheClientThatServedTheFailingVideo() {
        ShadowWalk.signedIn = true;
        remember("playing", playable(true), AppClient.TV_TIZEN);
        remember("prefetched", playable(false), AppClient.VISIONOS);
        ReflectionHelpers.setField(service, "mActualInfoType", AppClient.VISIONOS);
        ReflectionHelpers.setField(service, "mIsUnplayable", true); // the prefetch's verdict

        service.anchorRouteToVideo("playing");
        // Codex P2: a prefetch landing BETWEEN the anchor and the reads must not re-point them.
        ReflectionHelpers.setField(service, "mActualInfoType", AppClient.WEB);
        service.markCurrentPlaybackRouteForbidden();
        BotWallBook book = ReflectionHelpers.getField(service, "mBotWall");
        long now = android.os.SystemClock.elapsedRealtime();
        assertTrue("the playing video's route took the blame", book.isRouteFailed("cell:106",
                VideoInfoService.noMediaVideoKey("playing"), now));

        service.switchNextFormat();
        assertEquals(AppClient.TV_TIZEN, ReflectionHelpers.getField(service, "mRecoverySuspect"));
        assertTrue((boolean) ReflectionHelpers.getField(service, "mRecoveryWalk"));
        assertNull("consumed", ReflectionHelpers.getField(service, "mRouteAnchor"));
        assertEquals(AppClient.WEB, ReflectionHelpers.getField(service, "mActualInfoType"));

        // The recovery walk steps past TV_TIZEN, not past the prefetch's WEB.
        org.robolectric.shadows.ShadowLog.clear();
        open("playing");
        boolean suspectLogged = false;
        for (org.robolectric.shadows.ShadowLog.LogItem item
                : org.robolectric.shadows.ShadowLog.getLogsForTag("NetPath")) {
            suspectLogged |= item.msg.startsWith("player-ring authenticated-recovery")
                    && item.msg.contains("suspect=TV_TIZEN");
        }
        assertTrue(suspectLogged);

        service.anchorRouteToVideo("never-resolved");
        assertNull(ReflectionHelpers.getField(service, "mRouteAnchor"));
        remember("gated", challenged(false), AppClient.WEB);
        service.anchorRouteToVideo("gated");
        assertNull("an unplayable answer names no route",
                ReflectionHelpers.getField(service, "mRouteAnchor"));
    }

    /** Codex P1: planning a probe that a canceled prefetch never sends does not spend it. */
    @Test
    public void aCanceledOpenDoesNotSpendTheWallProbe() {
        ShadowWalk.script = VideoInfoBotWallTest::walledLte;
        open("a");
        drain();
        ShadowSystemClock.advanceBy(Duration.ofMillis(BotWallBook.PROBE_BASE_MS));

        VideoInfo canceled = ReflectionHelpers.callInstanceMethod(service, "firstPlayable",
                ClassParameter.from(String.class, "b"),
                ClassParameter.from(String.class, null),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(VideoInfoService.CancellationSignal.class,
                        (VideoInfoService.CancellationSignal) () -> true));
        assertNull(canceled);
        assertEquals(Collections.emptyList(), drain());

        assertEquals("the probe is still due", Collections.singletonList("VISIONOS"),
                requestsFor("c"));
        assertEquals("...and now spent", Collections.emptyList(), requestsFor("d"));
    }

    /** Codex P1, the circuit half: its probe allowance is also spent only when sent. */
    @Test
    public void aCanceledOpenDoesNotSpendTheCircuitProbe() {
        long now = android.os.SystemClock.elapsedRealtime();
        ReflectionHelpers.setField(service, "mBotCheckResult", challenged(false));
        ReflectionHelpers.setField(service, "mBotCheckCooldownUntilMs", now + 600_000);
        ReflectionHelpers.setField(service, "mBotCheckRingExhausted", true);
        ReflectionHelpers.setField(service, "mBotCheckNetwork", "cell:106");
        ReflectionHelpers.setField(service, "mBotCheckNextProbeAtMs", now);

        assertNull("the probe is let through", circuit());
        assertNull("not spent by being let through", circuit());

        ReflectionHelpers.callInstanceMethod(service, "spendBotCheckProbeIfPending");
        assertNotNull("spent once a request was sent", circuit());
    }

    /** Codex P2: signed out, a failing account route is one ask per wall, timeouts included. */
    @Test
    public void signedOutAFailingAccountRouteIsAskedOncePerWall() {
        for (VideoInfo answer : new VideoInfo[] {null, parse("{\"playabilityStatus\":"
                + " {\"status\": \"UNPLAYABLE\", \"reason\": \"The page needs to be reloaded.\"}}",
                false), sabrOnly(false)}) {
            setUp();
            ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN
                    ? answer : walledLte(client, auth);
            open("a");
            assertTrue(drain().contains("TV_TIZEN"));
            assertEquals(Collections.emptyList(), requestsFor("b"));
        }
    }

    /**
     * Codex P2 (conditional): a wall raised by the two-video rule applies to LATER opens; the walk
     * that raised it still lets the other client families try, and a serve clears it at once.
     */
    @Test
    public void aTwoVideoWallDoesNotCutTheWalkThatRaisedIt() {
        ShadowWalk.signedIn = true;
        quarantineBothAccountHeads();
        java.util.concurrent.atomic.AtomicInteger tizenAsks = new java.util.concurrent.atomic.AtomicInteger();
        ShadowWalk.script = (client, auth) -> {
            if (client == AppClient.VISIONOS) {
                return challenged(auth);
            }
            if (client == AppClient.TV_TIZEN) {
                // the first video plays; the second is one this account may not watch
                return tizenAsks.incrementAndGet() == 1 ? playable(auth) : parse(
                        "{\"playabilityStatus\": {\"status\": \"ERROR\","
                                + " \"reason\": \"Video unavailable\"}}", auth);
            }
            return playable(auth);
        };
        ShadowWalk.calls.clear();
        open("a");
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), drain());

        VideoInfo second = open("b");
        assertFalse(second.isUnplayable());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth", "WEB"), drain());
        assertFalse("the serve cleared the wall the same walk raised", isWalled());
    }

    // ------------------------------------------------------------------------------------------

    /** 2026-09-25, Movistar LTE: the observed answer of every client. */
    private static VideoInfo walledLte(AppClient client, boolean auth) {
        switch (client) {
            case WEB_EMBED:
                return parse("{\"playabilityStatus\": {\"status\": \"ERROR\", \"reason\":"
                        + " \"Este vídeo no está disponible • Código de error: 152 - 18\"}}", auth);
            case GEO:
                return parse("{\"playabilityStatus\": {\"status\": \"ERROR\", \"reason\":"
                        + " \"Este vídeo no está disponible\"}}", auth);
            case TV:
                return auth ? sabrOnly(true) : challenged(false);
            case TV_DOWNGRADED:
            case TV_TIZEN:
                return auth ? playable(true) : challenged(false);
            default:
                return challenged(auth);
        }
    }

    /**
     * One open as getVideoInfo runs it: the walk, then the routing bookkeeping it does afterwards
     * (the winner becomes mActualInfoType - the next walk's "last winner" - and the one-shot
     * recovery cursor ends). Leaving that out is what hid the Pixel's run-B open 2 from this suite.
     */
    private VideoInfo open(String videoId) {
        VideoInfo result = ReflectionHelpers.callInstanceMethod(service, "firstPlayable",
                ClassParameter.from(String.class, videoId),
                ClassParameter.from(String.class, null),
                ClassParameter.from(boolean.class, ShadowWalk.signedIn),
                ClassParameter.from(VideoInfoService.CancellationSignal.class, null));
        ReflectionHelpers.setField(service, "mNextInfoType", null);
        ReflectionHelpers.setField(service, "mRecoveryWalk", false);
        ReflectionHelpers.setField(service, "mRecoverySuspect", null);
        if (result != null) {
            ReflectionHelpers.callInstanceMethod(service, "persistRecentTypeIfNeeded",
                    ClassParameter.from(VideoInfo.class, result));
            ReflectionHelpers.callInstanceMethod(service, "rememberVideoWinner",
                    ClassParameter.from(String.class, videoId),
                    ClassParameter.from(VideoInfo.class, result));
        }
        return result;
    }

    private List<String> requestsFor(String videoId) {
        drain();
        open(videoId);
        return drain();
    }

    private static List<String> drain() {
        List<String> calls = new ArrayList<>(ShadowWalk.calls);
        ShadowWalk.calls.clear();
        return calls;
    }

    private boolean isWalled() {
        BotWallBook book = ReflectionHelpers.getField(service, "mBotWall");
        return book.isWalled(ShadowWalk.network, android.os.SystemClock.elapsedRealtime());
    }

    /** The persisted 2026-09-25 state: both TVHTML5 heads quarantined on cell. */
    private void quarantineBothAccountHeads() {
        for (AppClient head : new AppClient[] {AppClient.TV_DOWNGRADED, AppClient.TV}) {
            ReflectionHelpers.setField(service, "mActualInfoType", head);
            service.markCurrentPlaybackRouteForbidden();
        }
        ReflectionHelpers.setField(service, "mActualInfoType", null);
    }

    static final class MemoryWallStore implements VideoInfoService.BotWallStore {
        String value;

        @Override
        public String load() {
            return value;
        }

        @Override
        public void save(String snapshot) {
            value = snapshot;
        }

        @Override
        public long bootCount() {
            return 7;
        }
    }

    private VideoInfo circuit() {
        return ReflectionHelpers.callInstanceMethod(service, "getActiveBotCheckResult",
                ClassParameter.from(boolean.class, false), ClassParameter.from(String.class, "x"));
    }

    /** What ErrorFixerController does on a media 403 of {@code videoId}. */
    private void mediaForbidden(String videoId, AppClient servedBy) {
        remember(videoId, playable(true), servedBy);
        service.anchorRouteToVideo(videoId);
        service.markCurrentPlaybackRouteForbidden();
        ReflectionHelpers.setField(service, "mRouteAnchor", null); // switchNextFormat consumes it
    }

    private void remember(String videoId, VideoInfo result, AppClient client) {
        result.setClient(client);
        ReflectionHelpers.callInstanceMethod(service, "rememberVideoWinner",
                ClassParameter.from(String.class, videoId), ClassParameter.from(VideoInfo.class, result));
    }

    private static VideoInfo playable(boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"OK\"}}", auth);
    }

    private static VideoInfo challenged(boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\", \"reason\": \""
                + NOT_A_BOT + "\"}}", auth);
    }

    private static VideoInfo sabrOnly(boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"OK\"}, \"streamingData\":"
                + " {\"adaptiveFormats\": [{\"itag\": 251, \"mimeType\": \"audio/webm\"},"
                + " {\"itag\": 248, \"mimeType\": \"video/webm\"}],"
                + " \"serverAbrStreamingUrl\": \"https://media.invalid/sabr\"}}", auth);
    }

    private static VideoInfo parse(String json, boolean auth) {
        try {
            Converter<ResponseBody, ?> converter = JsonPathConverterFactory.create()
                    .responseBodyConverter(VideoInfo.class, new Annotation[0], null);
            VideoInfo info = (VideoInfo) converter.convert(
                    ResponseBody.create(MediaType.get("application/json"), json));
            info.setAuth(auth);
            return info;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** PoTokenGate without its WebView factory; resetCache answers like the real one. */
    @Implements(com.liskovsoft.youtubeapi.app.PoTokenGate.class)
    public static class ShadowTokenGate {
        @Implementation
        protected static void __staticInitializer__() {
        }

        @Implementation
        protected static void resetCache() {
        }

        @Implementation
        protected static boolean resetCache(AppClient client) {
            return client.isWebPotRequired();
        }

        /** No BotGuard in a unit test: no token (the format transform of a full getVideoInfo). */
        @Implementation
        protected static String getPoToken(AppClient client, String videoId) {
            return null;
        }
    }

    @Implements(VideoInfoService.class)
    public static class ShadowWalk {
        static String network;
        static String transport;
        static boolean signedIn;
        static final List<String> calls = new ArrayList<>();
        static BiFunction<AppClient, Boolean, VideoInfo> script;

        @Implementation
        protected void __constructor__() {
            // The walk needs no Retrofit, preferences or singleton.
        }

        static int keyLookups;

        @Implementation
        protected static String activeNetworkKey() {
            keyLookups++;
            return network;
        }

        @Implementation
        protected static String activeTransportKey() {
            keyLookups++;
            return transport;
        }

        @Implementation
        protected static boolean hasAuthentication() {
            return signedIn;
        }

        /** A full getVideoInfo stops after the walk: no subtitle or storyboard enrichment. */
        @Implementation
        protected void applyFixesIfNeeded(VideoInfo result, String videoId, String clickTrackingParams) {
        }

        /** One scripted /player answer, then the production debug-injection step. */
        @Implementation
        protected VideoInfo getVideoInfoWithTimeout(AppClient client, String videoId,
                String clickTrackingParams, VideoInfoService.CancellationSignal cancellationSignal,
                long remainingWalkBudgetMs, boolean[] noResponseOut) {
            boolean auth = client.isAuthCapable() && signedIn;
            calls.add(client.name() + (auth ? "+auth" : ""));
            VideoInfo result = script.apply(client, auth);
            result = ReflectionHelpers.callStaticMethod(VideoInfoService.class, "maybeInjectBotWall",
                    ClassParameter.from(AppClient.class, client),
                    ClassParameter.from(VideoInfo.class, result));
            if (result != null) {
                result.setClient(client);
            }
            return result;
        }
    }
}
