package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** Pure decision table of the bot-wall memory; explicit clocks, no Android, no network. */
public class BotWallBookTest {
    private static final String CELL = "cell:106";
    private static final String WIFI = "wifi:100";
    private static final long T0 = 1_000_000L;

    private final BotWallBook book = new BotWallBook();

    /** The 2026-09-25 first walk, in ring order: the wall is established at ANDROID_VR. */
    @Test
    public void aWholeRingOfChallengesEstablishesTheWallAtTheSecondPlatformClient() {
        BotWallBook.WalkEvidence walk = new BotWallBook.WalkEvidence();

        assertSame(BotWallBook.Challenge.SUSPECT, challenge(walk, AppClient.VISIONOS, "a", T0));
        assertSame(BotWallBook.Challenge.HELD, challenge(walk, AppClient.WEB, "a", T0));
        assertSame(BotWallBook.Challenge.HELD, challenge(walk, AppClient.WEB_SAFARI, "a", T0));
        assertSame(BotWallBook.Challenge.HELD, challenge(walk, AppClient.MWEB, "a", T0));
        assertFalse("one platform identity is not the whole anonymous ring",
                book.isWalled(CELL, T0));

        assertSame(BotWallBook.Challenge.ESTABLISHED,
                challenge(walk, AppClient.ANDROID_VR, "a", T0));
        assertTrue(book.isWalled(CELL, T0));
    }

    /**
     * HANDOFF section 17: the Web partition alone was challenged while ANDROID_VR served. No number
     * of Web-only challenges may wall the network.
     */
    @Test
    public void webOnlyChallengesNeverWallTheNetwork() {
        BotWallBook.WalkEvidence walk = new BotWallBook.WalkEvidence();
        for (AppClient web : new AppClient[] {AppClient.WEB_EMBED, AppClient.WEB,
                AppClient.WEB_SAFARI, AppClient.MWEB, AppClient.GEO}) {
            assertSame(BotWallBook.Challenge.HELD, challenge(walk, web, "a", T0));
        }
        assertFalse(book.isWalled(CELL, T0));
        assertEquals("none", book.describe(CELL, T0));
    }

    /** One transient LOGIN_REQUIRED "not a bot" must never strand a healthy network. */
    @Test
    public void aSingleChallengeOnlyRaisesASuspicionThatExpires() {
        assertSame(BotWallBook.Challenge.SUSPECT,
                challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "a", T0));
        assertFalse(book.isWalled(CELL, T0));
        assertFalse(book.plan(CELL, true, "v", T0).walled);

        long later = T0 + BotWallBook.SUSPECT_WINDOW_MS;
        assertSame("the suspicion lapsed, so this is a fresh first sighting",
                BotWallBook.Challenge.SUSPECT,
                challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "b", later));
        assertFalse(book.isWalled(CELL, later));
    }

    /** The signed-in path: the account route serves each open after ONE anonymous challenge. */
    @Test
    public void aSecondVideoConfirmsThePlatformSuspicion() {
        challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "a", T0);

        assertSame("a reload of the SAME video is not a second witness",
                BotWallBook.Challenge.SUSPECT,
                challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "a", T0 + 1_000));
        assertFalse(book.isWalled(CELL, T0 + 1_000));

        assertSame(BotWallBook.Challenge.ESTABLISHED,
                challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "b", T0 + 2_000));
        assertTrue(book.isWalled(CELL, T0 + 2_000));
    }

    @Test
    public void walledSignedInWalkIsTheAccountRoutePlusAPeriodicProbe() {
        long now = establish(T0);

        BotWallBook.Plan plan = book.plan(CELL, true, "v", now);
        assertTrue(plan.walled);
        assertFalse("the establishing walk just watched the anonymous identity fail", plan.probe);
        assertEquals(Collections.singletonList(BotWallBook.ACCOUNT_ROUTE), plan.order);

        assertFalse("signed in with a working account route, no routine anonymous probing",
                book.plan(CELL, true, "v", now + BotWallBook.PROBE_BASE_MS).probe);
        long probeAt = now + BotWallBook.AUTH_PROBE_BASE_MS;
        plan = book.plan(CELL, true, "v", probeAt);
        assertTrue(plan.probe);
        assertEquals("only the token-free head, and it never delays the account route for long",
                Arrays.asList(BotWallBook.PROBE_CLIENT, BotWallBook.ACCOUNT_ROUTE), plan.order);
        assertTrue("planning alone spends nothing (a canceled prefetch may never send it)",
                book.plan(CELL, true, "v", probeAt + 1).probe);

        book.consumeProbe(CELL, BotWallBook.PROBE_CLIENT, probeAt + 2);
        // ...and it was refused, which re-confirms the wall (as the walk does)
        challenge(new BotWallBook.WalkEvidence(), BotWallBook.PROBE_CLIENT, "p", probeAt + 3);
        assertEquals("one probe SENT per interval, however many opens",
                Collections.singletonList(BotWallBook.ACCOUNT_ROUTE),
                book.plan(CELL, true, "v", probeAt + 3).order);
        assertFalse("the interval doubled", book.plan(CELL, true, "v",
                probeAt + 2 + BotWallBook.AUTH_PROBE_BASE_MS).probe);
        assertTrue(book.plan(CELL, true, "v", probeAt + 2 + 2 * BotWallBook.AUTH_PROBE_BASE_MS).probe);
    }

    @Test
    public void signedOutAsksTheAnonymousAccountRouteOncePerWall() {
        long now = establish(T0);
        assertEquals(Collections.singletonList(BotWallBook.ACCOUNT_ROUTE),
                book.plan(CELL, false, "v", now).order);

        assertSame(BotWallBook.Challenge.CONFIRMED,
                challenge(new BotWallBook.WalkEvidence(), BotWallBook.ACCOUNT_ROUTE, "c", now));

        assertEquals("nothing left to ask: answered with zero requests",
                Collections.emptyList(), book.plan(CELL, false, "v", now).order);
        assertEquals("signing in makes it a different identity again",
                Collections.singletonList(BotWallBook.ACCOUNT_ROUTE),
                book.plan(CELL, true, "v", now).order);
        assertEquals(Collections.singletonList(BotWallBook.PROBE_CLIENT),
                book.plan(CELL, false, "v", now + BotWallBook.PROBE_BASE_MS).order);
    }

    /**
     * Codex P1: one bad URL must not take the account route away from every other video, and a
     * failure on one attachment says nothing about the next one on the same transport.
     */
    @Test
    public void oneFailureBenchesTheRouteForThatVideoOnlyAndASecondVideoForTheAttachment() {
        long now = establish(T0);

        assertSame(BotWallBook.RouteFailure.VIDEO, book.noteRouteFailed(CELL, "a", "media-403", now));
        assertTrue("the recovery reload of the same video does not re-buy it",
                book.isRouteFailed(CELL, "a", now));
        assertEquals(Collections.emptyList(), book.plan(CELL, true, "a", now).order);
        assertFalse("every other video still gets the route", book.isRouteFailed(CELL, "b", now));
        assertEquals(Collections.singletonList(BotWallBook.ACCOUNT_ROUTE),
                book.plan(CELL, true, "b", now).order);
        assertNull(book.routeFailureReason(CELL, now));
        assertSame("a repeat on the same video is still one video",
                BotWallBook.RouteFailure.VIDEO, book.noteRouteFailed(CELL, "a", "media-403", now));
        assertFalse(book.isRouteFailed(CELL, "b", now));

        assertSame(BotWallBook.RouteFailure.ROUTE,
                book.noteRouteFailed(CELL, "b", "reload-page", now + 1_000));
        assertTrue(book.isRouteFailed(CELL, "c", now + 1_000));
        assertEquals("reload-page", book.routeFailureReason(CELL, now + 1_000));
        assertFalse("another attachment of the same transport",
                book.isRouteFailed("cell:107", "c", now + 1_000));
        assertFalse(book.isRouteFailed(WIFI, "c", now + 1_000));

        long expired = now + 1_000 + BotWallBook.ROUTE_FAILURE_TTL_MS;
        assertFalse(book.isRouteFailed(CELL, "c", expired));
        assertNull(book.routeFailureReason(CELL, expired));
    }

    @Test
    public void anUnknownVideoCountsAsADistinctOne() {
        assertSame(BotWallBook.RouteFailure.VIDEO, book.noteRouteFailed(CELL, null, "media-403", T0));
        assertFalse(book.isRouteFailed(CELL, "a", T0));
        assertSame(BotWallBook.RouteFailure.ROUTE, book.noteRouteFailed(CELL, "a", "media-403", T0));
        assertSame(BotWallBook.RouteFailure.NONE, book.noteRouteFailed(null, "a", "media-403", T0));
    }

    @Test
    public void aFirstBenchThatExpiredDoesNotEscalateALaterVideo() {
        book.noteRouteFailed(CELL, "a", "media-403", T0);
        long later = T0 + BotWallBook.ROUTE_FAILURE_TTL_MS;
        assertSame(BotWallBook.RouteFailure.VIDEO, book.noteRouteFailed(CELL, "b", "media-403", later));
        assertFalse(book.isRouteFailed(CELL, "c", later));
    }

    /** Codex P2: signed out, the route is one ask per wall whatever it answered. */
    @Test
    public void aSpentAnonymousAccountRouteIsNotAskedAgainThisWall() {
        long now = establish(T0);
        assertEquals(Collections.singletonList(BotWallBook.ACCOUNT_ROUTE),
                book.plan(CELL, false, "a", now).order);

        book.noteAnonRouteSpent(CELL, now);

        assertEquals(Collections.emptyList(), book.plan(CELL, false, "b", now).order);
        assertEquals("signed in it is the account, not a spent anonymous identity",
                Collections.singletonList(BotWallBook.ACCOUNT_ROUTE),
                book.plan(CELL, true, "b", now).order);
        book.noteAnonRouteSpent(WIFI, now); // not walled: nothing to spend
        assertFalse(book.isWalled(WIFI, now));
    }

    @Test
    public void onlyAStrongWalkMayCutItsOwnRingShort() {
        BotWallBook.WalkEvidence walk = new BotWallBook.WalkEvidence();
        walk.add(AppClient.VISIONOS);
        walk.add(AppClient.WEB);
        walk.add(AppClient.WEB_SAFARI);
        assertFalse("one platform identity", walk.isStrong());
        walk.add(AppClient.ANDROID_VR);
        assertTrue(walk.isStrong());
        assertTrue(walk.takeShortcut());
        assertFalse("once per walk", walk.takeShortcut());
    }

    /** A new credential gets a fresh verdict; the wall (about the anonymous side) stays. */
    @Test
    public void anAccountChangeForgetsRouteFailuresButNotTheWall() {
        long now = establish(T0);
        book.noteRouteFailed(CELL, "a", "challenged", now);
        book.noteRouteFailed(CELL, "b", "challenged", now);

        assertTrue(book.clearRouteFailures());
        assertFalse(book.isRouteFailed(CELL, "a", now));
        assertTrue(book.isWalled(CELL, now));
        assertFalse("nothing left to clear", book.clearRouteFailures());
    }

    @Test
    public void anAnonymousServeClearsTheWallWithoutProbation() {
        long now = establish(T0);

        assertTrue(book.noteAnonServed(CELL));
        assertFalse(book.isWalled(CELL, now));
        assertFalse(book.plan(CELL, true, "v", now).walled);
        assertSame("no probation after a proven serve",
                BotWallBook.Challenge.SUSPECT,
                challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "z", now));
        assertFalse(book.noteAnonServed(WIFI));
    }

    @Test
    public void aChallengedProbeReconfirmsTheWall() {
        long now = establish(T0);
        long nearlyExpired = now + BotWallBook.WALL_TTL_MS - 1_000;

        assertSame(BotWallBook.Challenge.CONFIRMED,
                challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "p", nearlyExpired));

        assertTrue(book.isWalled(CELL, nearlyExpired + 2_000));
    }

    @Test
    public void anExpiredWallLeavesProbationForOneChallenge() {
        long now = establish(T0);
        long expired = now + BotWallBook.WALL_TTL_MS;

        assertFalse(book.plan(CELL, true, "v", expired).walled);
        assertTrue(book.describe(CELL, expired).startsWith("probation"));
        assertSame("a returning wall is recognized by its first platform challenge",
                BotWallBook.Challenge.ESTABLISHED,
                challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "q", expired + 1));

        long afterProbation = establishAndExpire(expired + 1) + BotWallBook.PROBATION_MS;
        assertSame(BotWallBook.Challenge.SUSPECT,
                challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "r", afterProbation));
    }

    @Test
    public void theProbeBacksOffExponentiallyAndIsBounded() {
        long[] anon = {1, 2, 4, 8, 15, 15, 15};
        long[] auth = {5, 10, 20, 30, 30};
        for (int i = 0; i < anon.length; i++) {
            assertEquals(anon[i] * 60_000L, BotWallBook.probeIntervalMs(i, false));
        }
        for (int i = 0; i < auth.length; i++) {
            assertEquals(auth[i] * 60_000L, BotWallBook.probeIntervalMs(i, true));
        }
        assertEquals(BotWallBook.PROBE_MAX_MS, BotWallBook.probeIntervalMs(1_000, false));
    }

    /**
     * Codex (visitor review): one client refused over and over must not keep every other
     * anonymous family from being asked. Signed out, the probe rotates across the families.
     */
    @Test
    public void signedOutTheProbeRotatesAcrossClientFamilies() {
        long now = establish(T0);
        long t = now;
        AppClient[] expected = {AppClient.VISIONOS, AppClient.ANDROID_VR, AppClient.VISIONOS,
                AppClient.ANDROID_VR};
        for (int i = 0; i < expected.length; i++) {
            t += BotWallBook.probeIntervalMs(i, false);
            // re-confirmed by the previous probe, like a real refused probe does
            challenge(new BotWallBook.WalkEvidence(), AppClient.MWEB, "x", t - 1);
            BotWallBook.Plan plan = book.plan(CELL, false, "v", t);
            assertEquals("probe " + i, expected[i], plan.probeClient);
            book.consumeProbe(CELL, plan.probeClient, t);
        }
    }

    /** The rotation starts with a family the establishing walk did NOT see refused. */
    @Test
    public void theRotationStartsWithAFamilyThatWasNotRefused() {
        BotWallBook.WalkEvidence walk = new BotWallBook.WalkEvidence();
        challenge(walk, AppClient.VISIONOS, "a", T0);
        assertSame(BotWallBook.Challenge.ESTABLISHED,
                challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "b", T0 + 1));
        assertEquals(AppClient.ANDROID_VR,
                book.plan(CELL, false, "v", T0 + 1 + BotWallBook.PROBE_BASE_MS).probeClient);
    }

    /** Signed in but with the account route benched, the anonymous side is all there is. */
    @Test
    public void aDeadAccountRouteBringsBackTheFastAnonymousProbe() {
        long now = establish(T0);
        book.noteRouteFailed(CELL, "a", "media-403", now);
        book.noteRouteFailed(CELL, "b", "media-403", now);
        BotWallBook.Plan plan = book.plan(CELL, true, "c", now + BotWallBook.PROBE_BASE_MS);
        assertEquals(Collections.singletonList(AppClient.VISIONOS), plan.order);
    }

    /** However often it is re-confirmed, a wall ends at its cap - and without probation. */
    @Test
    public void aWallEndsAtItsCapWithoutProbation() {
        long now = establish(T0);
        for (long t = now + 10 * 60_000L; t < now + BotWallBook.MAX_WALL_MS; t += 10 * 60_000L) {
            assertSame(BotWallBook.Challenge.CONFIRMED,
                    challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "p", t));
        }
        long capped = now + BotWallBook.MAX_WALL_MS;
        assertTrue(book.isWalled(CELL, capped - 1));
        assertFalse(book.isWalled(CELL, capped));
        assertEquals("the next walk is a full ring walk", "none", book.describe(CELL, capped));
        assertSame(BotWallBook.Challenge.SUSPECT,
                challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "q", capped + 1));
    }

    /** Recovery reloads count: one video cannot spend more than the walled budget. */
    @Test
    public void aWalledVideoHasARequestBudgetThatRecoveryReloadsSpendToo() {
        long now = establish(T0);
        for (int i = 0; i < BotWallBook.WALLED_VIDEO_BUDGET; i++) {
            assertEquals(Collections.singletonList(BotWallBook.ACCOUNT_ROUTE),
                    book.plan(CELL, true, "v", now).order);
            book.noteWalledRequest(CELL, "v", now);
        }
        BotWallBook.Plan spent = book.plan(CELL, true, "v", now);
        assertTrue(spent.budgetCapped);
        assertEquals(Collections.emptyList(), spent.order);
        assertEquals("another video has its own budget",
                Collections.singletonList(BotWallBook.ACCOUNT_ROUTE),
                book.plan(CELL, true, "w", now).order);
        assertEquals("the window ends", Collections.singletonList(BotWallBook.ACCOUNT_ROUTE),
                book.plan(CELL, true, "v", now + BotWallBook.VIDEO_BUDGET_WINDOW_MS).order);
    }

    /** With one request left and a probe due, playing the video wins over re-testing the wall. */
    @Test
    public void aTightBudgetDropsTheProbeBeforeTheAccountRoute() {
        long now = establish(T0);
        long due = now + BotWallBook.AUTH_PROBE_BASE_MS;
        book.noteWalledRequest(CELL, "v", due - 1);
        book.noteWalledRequest(CELL, "v", due - 1);
        BotWallBook.Plan plan = book.plan(CELL, true, "v", due);
        assertEquals(Collections.singletonList(BotWallBook.ACCOUNT_ROUTE), plan.order);
        assertFalse(plan.probe);
        assertTrue(plan.budgetCapped);
    }

    // --- persistence ----------------------------------------------------------------------------

    @Test
    public void theWholeBookSurvivesARestartWithinTheBoot() {
        java.util.List<String> saved = new java.util.ArrayList<>();
        book.setPersister(saved::add, 7, 1_000_000_000L);
        book.restore(null, 7, 1_000_000_000L, T0, new java.util.ArrayList<>());
        long now = establish(T0);
        book.consumeProbe(CELL, AppClient.VISIONOS, now + BotWallBook.PROBE_BASE_MS);
        book.noteAnonRouteSpent(CELL, now + BotWallBook.PROBE_BASE_MS);
        book.noteRouteFailed(CELL, "a", "media-403", now);
        challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "z", now); // confirms
        book.noteChallenge(WIFI, new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "5", now);
        String snapshot = saved.get(saved.size() - 1);

        BotWallBook restored = new BotWallBook();
        java.util.List<String> dropped = new java.util.ArrayList<>();
        long later = now + BotWallBook.PROBE_BASE_MS + 1_000;
        assertEquals(3, restored.restore(snapshot, 7, 1_000_000_000L + 5_000, later, dropped));
        assertEquals(Collections.emptyList(), dropped);
        assertTrue(restored.isWalled(CELL, later));
        assertEquals(book.describe(CELL, later), restored.describe(CELL, later));
        assertTrue(restored.isRouteFailed(CELL, "a", later));
        assertEquals("the backoff and the spent anonymous route came back too",
                Collections.emptyList(), restored.plan(CELL, false, "v", later).order);
        assertTrue(restored.describe(WIFI, later).startsWith("suspect"));
    }

    @Test
    public void anotherBootOrADamagedValueRestoresNothing() {
        java.util.List<String> saved = new java.util.ArrayList<>();
        book.setPersister(saved::add, 7, 1_000_000_000L);
        book.restore(null, 7, 1_000_000_000L, T0, new java.util.ArrayList<>());
        establish(T0);
        String snapshot = saved.get(saved.size() - 1);

        java.util.List<String> dropped = new java.util.ArrayList<>();
        assertEquals(0, new BotWallBook().restore(snapshot, 8, 1_000_000_000L, T0, dropped));
        assertEquals(Collections.singletonList("other-boot"), dropped);
        dropped.clear();
        assertEquals("unknown boot count, but the boot wall time moved by an hour", 0,
                new BotWallBook().restore(snapshot, -1, 1_000_000_000L + 3_600_000L, T0, dropped));
        dropped.clear();
        assertTrue("same BOOT_COUNT, clock unknown", BotWallBook.sameBoot(7, 0, 7, 0));
        assertFalse(BotWallBook.sameBoot(7, 1_000_000_000L, 7, 1_000_000_000L + 3_600_000L));
        assertFalse("nothing known", BotWallBook.sameBoot(-1, 0, -1, 0));
        assertEquals(0, new BotWallBook().restore("garbage", 7, 1_000_000_000L, T0, dropped));
        assertEquals(Collections.singletonList("format"), dropped);
        dropped.clear();
        String damaged = "bw1,7,1000000000\nW,cell:1,x,y\nW,../etc,1,2,1,0,0,walk,\nS,cell:2,zz,5\n"
                + "R,cell:3,DROP TABLE,1,";
        assertEquals(0, new BotWallBook().restore(damaged, 7, 1_000_000_000L, T0, dropped));
        assertEquals(4, dropped.size());
        dropped.clear();
        assertEquals("expired records are dropped", 0, new BotWallBook().restore(snapshot, 7,
                1_000_000_000L, T0 + BotWallBook.MAX_WALL_MS, dropped));
        assertEquals(Collections.singletonList("expired:W"), dropped);
    }

    @Test
    public void thisProcessesOwnVerdictWinsAndNothingIsSavedBeforeTheRestore() {
        java.util.List<String> saved = new java.util.ArrayList<>();
        BotWallBook fresh = new BotWallBook();
        fresh.setPersister(saved::add, 7, 1_000_000_000L);
        BotWallBook.WalkEvidence walk = new BotWallBook.WalkEvidence();
        fresh.noteChallenge(CELL, walk, AppClient.VISIONOS, "a", T0);
        fresh.noteChallenge(CELL, walk, AppClient.WEB, "a", T0);
        fresh.noteChallenge(CELL, walk, AppClient.ANDROID_VR, "a", T0);
        assertEquals("a cold start must not overwrite the stored wall", 0, saved.size());

        String older = "bw1,7,1000000000\nW,cell:106," + (T0 - 1_000) + "," + (T0 + 60_000)
                + "," + (T0 - 1_000) + ",5,2,second-video,VISIONOS";
        assertEquals(1, fresh.restore(older, 7, 1_000_000_000L, T0, new java.util.ArrayList<>()));
        assertTrue(fresh.describe(CELL, T0).contains("cause=walk"));
        fresh.noteAnonServed(CELL);
        assertEquals(1, saved.size());
        assertNull("nothing left: the stored value is cleared", saved.get(0));
    }

    /** Keyed on the attachment: moving to another network starts from nothing. */
    @Test
    public void anotherAttachmentIsNotWalled() {
        long now = establish(T0);

        assertFalse(book.plan(WIFI, true, "v", now).walled);
        assertFalse(book.plan("cell:107", true, "v", now).walled);
        assertTrue(book.plan(CELL, true, "v", now).walled);
    }

    @Test
    public void thePreChecksLetAHealthyWalkSkipTheNetworkLookup() {
        assertFalse(book.hasWalls(T0));
        assertFalse(book.hasSuspicion(T0));

        challenge(new BotWallBook.WalkEvidence(), AppClient.VISIONOS, "a", T0);
        assertFalse(book.hasWalls(T0));
        assertTrue("a suspicion is still something a serve should clear", book.hasSuspicion(T0));
        assertFalse(book.hasSuspicion(T0 + BotWallBook.SUSPECT_WINDOW_MS));

        long now = establish(T0);
        assertTrue(book.hasWalls(now));
        long expired = now + BotWallBook.WALL_TTL_MS;
        assertFalse("an expired wall is pruned by the pre-check itself", book.hasWalls(expired));
        assertTrue("...into probation", book.hasSuspicion(expired));
    }

    @Test
    public void noNetworkMeansNoMemory() {
        BotWallBook.WalkEvidence walk = new BotWallBook.WalkEvidence();
        for (AppClient client : new AppClient[] {AppClient.VISIONOS, AppClient.ANDROID_VR,
                AppClient.WEB, AppClient.IOS}) {
            assertSame(BotWallBook.Challenge.HELD, book.noteChallenge(null, walk, client, "a", T0));
        }
        assertFalse(book.plan(null, true, "v", T0).walled);
        assertFalse(book.isRouteFailed(null, "a", T0));
    }

    @Test
    public void theBookKeepsOnlyAFewAttachments() {
        for (int i = 0; i < BotWallBook.MAX_NETWORKS + 2; i++) {
            BotWallBook.WalkEvidence walk = new BotWallBook.WalkEvidence();
            String network = "cell:" + i;
            book.noteChallenge(network, walk, AppClient.VISIONOS, "a", T0);
            book.noteChallenge(network, walk, AppClient.ANDROID_VR, "a", T0);
            book.noteChallenge(network, walk, AppClient.WEB, "a", T0);
            assertTrue(book.isWalled(network, T0));
        }
        assertFalse("eldest evicted", book.isWalled("cell:0", T0));
        assertTrue(book.isWalled("cell:" + (BotWallBook.MAX_NETWORKS + 1), T0));
    }

    private BotWallBook.Challenge challenge(BotWallBook.WalkEvidence walk, AppClient client,
            String video, long now) {
        return book.noteChallenge(CELL, walk, client, video, now);
    }

    /** Walls CELL with one walk; returns the clock. */
    private long establish(long now) {
        BotWallBook.WalkEvidence walk = new BotWallBook.WalkEvidence();
        challenge(walk, AppClient.VISIONOS, "w", now);
        challenge(walk, AppClient.WEB, "w", now);
        assertSame(BotWallBook.Challenge.ESTABLISHED, challenge(walk, AppClient.ANDROID_VR, "w", now));
        return now;
    }

    private long establishAndExpire(long now) {
        assertTrue(book.isWalled(CELL, now));
        long expired = now + BotWallBook.WALL_TTL_MS;
        assertFalse(book.isWalled(CELL, expired));
        return expired;
    }
}
