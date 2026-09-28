package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VideoInfoVisitOrderTest {
    /**
     * The 7s per-attempt timeout was sized for a speculative fast client (ANDROID_VR). Applying
     * it to the AUTHENTICATED head is what turned one slow cold request into a fallthrough to
     * TV, a media 403 and a 10-minute quarantine of the whole authenticated route.
     */
    @Test
    public void theAccountRouteGetsAColdStartBudget() {
        long route = VideoInfoService.attemptTimeoutMsFor(AppClient.TV_TIZEN);
        long speculative = VideoInfoService.attemptTimeoutMsFor(AppClient.ANDROID_VR);

        assertTrue("the account route must outlast a cold start", route >= 15_000);
        assertTrue("speculative clients keep the short budget", speculative < route);
    }

    /**
     * The account route by its walk role; then 20 s for the web-pot family (a cold BotGuard mint,
     * or WEB_EMBED's embed page), else 7 s. The TVHTML5 heads lost their 15 s with the heads (the
     * phone no longer asks them: netbench LANES.md).
     */
    @Test
    public void everyClientKeepsItsAttemptBudget() {
        for (AppClient client : AppClient.values()) {
            long expected = client == AppClient.TV_TIZEN ? 15_000
                    : client.isWebPotRequired() ? 20_000 : 7_000;
            assertEquals(client.name(), expected, VideoInfoService.attemptTimeoutMsFor(client));
        }
    }

    /**
     * Web-pot clients used to run with NO per-attempt deadline at all, which is what made a bad
     * link able to spend minutes inside one walk. They are bounded now, but the bound must stay
     * generous: a cold BotGuard mint plus an 8s-connect/8s-read /player is a legitimate wait, and
     * cutting it short abandons the only client that can serve an enforced video (HANDOFF section 8).
     */
    @Test
    public void webPotClientsGetABudgetSizedForAColdMint() {
        long webPot = VideoInfoService.attemptTimeoutMsFor(AppClient.WEB_EMBED);
        long speculative = VideoInfoService.attemptTimeoutMsFor(AppClient.ANDROID_VR);

        assertEquals(webPot, VideoInfoService.attemptTimeoutMsFor(AppClient.WEB));
        assertEquals(webPot, VideoInfoService.attemptTimeoutMsFor(AppClient.GEO));
        assertTrue("8s connect + 8s read must fit inside a web-pot attempt", webPot >= 16_000);
        assertTrue("web-pot needs more room than a speculative fast client", webPot > speculative);
    }

    @Test
    public void tvOrderKeepsLastWinnerSecond() {
        List<AppClient> order = VideoInfoService.buildVisitOrder(
                AppClient.ANDROID_VR, AppClient.WEB_EMBED, false, true);

        assertEquals(AppClient.ANDROID_VR, order.get(0));
        assertEquals(AppClient.WEB_EMBED, order.get(1));
        assertEquals(13, order.size());
    }

    @Test
    public void normalMobileWalkPartitionsWholeOrder() {
        List<AppClient> order = VideoInfoService.buildVisitOrder(
                AppClient.WEB_EMBED, AppClient.ANDROID_VR, true, false);

        assertEquals(Arrays.asList(
                AppClient.WEB_EMBED,
                AppClient.WEB,
                AppClient.WEB_SAFARI,
                AppClient.GEO,
                AppClient.MWEB), order.subList(0, 5));
        assertEquals(AppClient.ANDROID_VR, order.get(5));
        assertEquals(13, order.size());
    }

    @Test
    public void normalHybridWalkKeepsVrFastHeadThenWebFamily() {
        List<AppClient> order = VideoInfoService.buildVisitOrder(
                AppClient.ANDROID_VR, AppClient.ANDROID_REEL, true, false);

        assertEquals(Arrays.asList(
                AppClient.ANDROID_VR,
                AppClient.WEB_EMBED,
                AppClient.WEB,
                AppClient.WEB_SAFARI,
                AppClient.GEO,
                AppClient.MWEB), order.subList(0, 6));
        assertEquals(AppClient.ANDROID_REEL, order.get(6));
        assertEquals(13, order.size());
    }

    @Test
    public void vrRecoveryStartsAtCanonicalWebEmbedFallback() {
        List<AppClient> order = VideoInfoService.buildVisitOrder(
                AppClient.ANDROID_REEL, AppClient.ANDROID_VR, true, true);

        assertEquals(Arrays.asList(
                AppClient.WEB_EMBED,
                AppClient.WEB,
                AppClient.WEB_SAFARI,
                AppClient.GEO,
                AppClient.MWEB), order.subList(0, 5));
        assertEquals(AppClient.ANDROID_REEL, order.get(5));
        assertEquals(13, order.size());
    }

    @Test
    public void recoveryDefersSuspectWinnerBehindSiblingWebClients() {
        List<AppClient> order = VideoInfoService.buildVisitOrder(
                AppClient.ANDROID_VR, AppClient.WEB_EMBED, true, true);

        assertEquals(Arrays.asList(
                AppClient.WEB,
                AppClient.WEB_SAFARI,
                AppClient.GEO,
                AppClient.MWEB,
                AppClient.WEB_EMBED), order.subList(0, 5));
        assertEquals(AppClient.ANDROID_VR, order.get(5));
        assertEquals(13, order.size());
    }

    @Test
    public void visitorIdentityBridgeIsLimitedToWebFamilyAndAndroidVr() {
        assertTrue(VideoInfoApiHelper.usesWebVisitorData(AppClient.WEB_EMBED));
        assertTrue(VideoInfoApiHelper.usesWebVisitorData(AppClient.ANDROID_VR));
        assertTrue(VideoInfoApiHelper.usesWebVisitorData(AppClient.VISIONOS));
        assertFalse(VideoInfoApiHelper.usesWebVisitorData(AppClient.ANDROID_REEL));
        assertFalse(VideoInfoApiHelper.usesWebVisitorData(AppClient.IOS));
        assertFalse(VideoInfoApiHelper.usesWebVisitorData(AppClient.TV));
    }

    /**
     * VISIONOS is the fast head but is NOT a member of VIDEO_INFO_TYPE_LIST (that list is
     * upstream's and stays untouched). Helpers.getNextValue answers with element 0 for a value it
     * cannot find, so before the anchor fix this walk never met its `type != beginType` stop
     * condition and looped forever. If that regresses, this test hangs rather than fails - which
     * is itself the signal.
     */
    @Test
    public void offRingFastHeadWalksTheWholeRingExactlyOnce() {
        List<AppClient> order = VideoInfoService.buildVisitOrder(
                AppClient.VISIONOS, null, true, false);

        assertEquals(AppClient.VISIONOS, order.get(0));
        assertEquals("off-ring head + all 13 ring clients", 14, order.size());
        assertEquals("no client visited twice", order.size(), new HashSet<>(order).size());
        assertTrue("the whole ring is still reachable behind the head",
                order.containsAll(Arrays.asList(
                        AppClient.WEB_EMBED, AppClient.ANDROID_VR, AppClient.TV,
                        AppClient.TV_DOWNGRADED, AppClient.IOS)));
        // The head keeps its Web-family tail: attested Web recovery is what handles the
        // made-for-kids videos VISIONOS cannot serve.
        assertTrue(order.get(1).isWebPotRequired());
    }

    /** Same anchor hazard on the unpartitioned (TV-shaped) walk. */
    @Test
    public void offRingFastHeadTerminatesWithoutWebPartitioning() {
        List<AppClient> order = VideoInfoService.buildVisitOrder(
                AppClient.VISIONOS, AppClient.ANDROID_VR, false, false);

        assertEquals(AppClient.VISIONOS, order.get(0));
        assertEquals(AppClient.ANDROID_VR, order.get(1));
        assertEquals(14, order.size());
        assertEquals(order.size(), new HashSet<>(order).size());
    }

    /** The head must not need a pot or an account - that is the entire reason it leads. */
    @Test
    public void fastHeadNeedsNeitherPoTokenNorAccount() {
        assertFalse(AppClient.VISIONOS.isWebPotRequired());
        assertFalse(AppClient.VISIONOS.isAuthSupported());
        assertEquals("101", AppClient.VISIONOS.getInnerTubeName());
    }

    /**
     * The debug playground has to be able to force the off-ring head to A/B it against VR, and
     * (source benchmark) any client the ring does not use yet. Only unknown names are refused.
     */
    @Test
    public void debugPlaygroundCanForceTheOffRingHead() {
        try {
            assertTrue(VideoInfoService.setDebugForcedClient("VISIONOS"));
            assertTrue(VideoInfoService.setDebugForcedClient("ANDROID_VR"));
            assertTrue("off-ring clients can be benchmarked",
                    VideoInfoService.setDebugForcedClient("WEB_MUSIC"));
            assertTrue(VideoInfoService.setDebugForcedClient("tv_simply"));
            assertFalse("unknown names stay rejected",
                    VideoInfoService.setDebugForcedClient("NOT_A_CLIENT"));
        } finally {
            VideoInfoService.setDebugForcedClient(null);
        }
    }

    /**
     * yt-dlp's _DEFAULT_AUTHED_CLIENTS leads with tv_downgraded and never lists plain tv; on-device
     * the TV route's URLs 403 once ~60s of media has been served. TV_DOWNGRADED must be attempt 1.
     */
    @Test
    public void authenticatedOrderStartsWithDowngradedTv() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR, true, false, true, noneForbidden());

        assertEquals(AppClient.TV_DOWNGRADED, order.get(0));
        assertEquals(AppClient.TV, order.get(1));
        assertEquals(AppClient.ANDROID_VR, order.get(2));
        assertEquals("ring + the token-free client slotted before the web-pot partition",
                14, order.size());
    }

    /**
     * A quarantined head client is demoted behind its healthy account-bearing sibling AND, on the
     * phone, behind the token-free client - never dropped, and still ahead of anonymous Web.
     */
    @Test
    public void quarantinedHeadClientIsDemotedBehindItsAccountBearingSibling() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR, true, false, true,
                forbidden(AppClient.TV_DOWNGRADED));

        assertEquals(AppClient.TV, order.get(0));
        assertEquals(order.indexOf(AppClient.VISIONOS) + 1, order.indexOf(AppClient.TV_DOWNGRADED));
        assertTrue(order.indexOf(AppClient.TV_DOWNGRADED) < order.indexOf(AppClient.WEB_EMBED));
        assertEquals(14, order.size());
        assertEquals(order.size(), new HashSet<>(order).size());
    }

    /**
     * NEWTUBE(auth-route), the common phone case: the last winner is VISIONOS, TV_DOWNGRADED is
     * quarantined by its media 403 and TV's quarantine has expired. TV keeps attempt 1 (it may have
     * recovered), but its fall-through is VISIONOS - not TV_DOWNGRADED winning with dead URLs and
     * paying a 403 + reload (HANDOFF section 26).
     */
    @Test
    public void quarantinedDowngradedHeadFallsThroughToTheTokenFreeClientFirst() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.VISIONOS, true, false, true,
                forbidden(AppClient.TV_DOWNGRADED));

        assertEquals(Arrays.asList(AppClient.TV, AppClient.VISIONOS, AppClient.TV_DOWNGRADED),
                order.subList(0, 3));
        assertEquals(14, order.size());
    }

    /** The mirror case: TV (SABR-only) is quarantined, so it is not paid for before VISIONOS. */
    @Test
    public void quarantinedTvHeadIsNotProbedBeforeTheTokenFreeClient() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.VISIONOS, true, false, true,
                forbidden(AppClient.TV));

        assertEquals(Arrays.asList(AppClient.TV_DOWNGRADED, AppClient.VISIONOS, AppClient.TV),
                order.subList(0, 3));
        assertEquals(14, order.size());
    }

    /** No quarantine: the account head keeps attempts 1-2 exactly as before. */
    @Test
    public void healthyHeadIsNotReorderedByTheDemotion() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.VISIONOS, true, false, true, noneForbidden());

        assertEquals(Arrays.asList(AppClient.TV_DOWNGRADED, AppClient.TV, AppClient.VISIONOS),
                order.subList(0, 3));
    }

    /** TV builds (no web-family preference, no token-free client) keep the in-head demotion. */
    @Test
    public void withoutThePhoneOrderingTheDemotionStaysWithinTheHead() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR, false, false, true,
                forbidden(AppClient.TV_DOWNGRADED));

        assertEquals(Arrays.asList(AppClient.TV, AppClient.TV_DOWNGRADED), order.subList(0, 2));
        assertFalse(order.contains(AppClient.VISIONOS));
    }

    /** Both quarantined is authenticatedWebFirst's job; the pure helper leaves it alone. */
    @Test
    public void demotionNeedsAHealthySiblingToLead() {
        List<AppClient> order = Arrays.asList(AppClient.TV_DOWNGRADED, AppClient.TV,
                AppClient.VISIONOS, AppClient.WEB_EMBED);

        assertEquals(order, VideoInfoService.demoteQuarantinedHeadBehindTokenFreeClient(order,
                forbidden(AppClient.TV, AppClient.TV_DOWNGRADED)));
        assertEquals(order, VideoInfoService.demoteQuarantinedHeadBehindTokenFreeClient(order,
                noneForbidden()));
        assertEquals(order, VideoInfoService.demoteQuarantinedHeadBehindTokenFreeClient(order,
                null));
        assertEquals("no anchor, no move",
                Arrays.asList(AppClient.TV_DOWNGRADED, AppClient.TV, AppClient.WEB_EMBED),
                VideoInfoService.demoteQuarantinedHeadBehindTokenFreeClient(
                        Arrays.asList(AppClient.TV_DOWNGRADED, AppClient.TV, AppClient.WEB_EMBED),
                        forbidden(AppClient.TV)));
    }

    /**
     * The whole point of fixing the quarantine: one 403 must NOT hand the walk to the anonymous
     * partition while a sibling account-bearing client is still healthy.
     */
    @Test
    public void singleQuarantinedClientKeepsBothAccountClientsAheadOfAnonymousWeb() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR, true, false, true,
                forbidden(AppClient.TV));

        assertTrue(order.indexOf(AppClient.TV_DOWNGRADED) < order.indexOf(AppClient.WEB_EMBED));
        assertTrue(order.indexOf(AppClient.TV) < order.indexOf(AppClient.WEB_EMBED));
    }

    /** Only a fully exhausted account head falls through to the attested Web partition. */
    @Test
    public void fullyQuarantinedHeadStartsWithWebButKeepsTvFallbacks() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.WEB_EMBED, AppClient.TV_DOWNGRADED,
                true, false, true, forbidden(AppClient.TV, AppClient.TV_DOWNGRADED), true, false);

        // The token-free client leads the anonymous partition now; the Web family follows it
        // intact, and the quarantined siblings still keep their relative places behind it.
        assertEquals(Arrays.asList(
                AppClient.VISIONOS,
                AppClient.WEB_EMBED,
                AppClient.WEB,
                AppClient.WEB_SAFARI,
                AppClient.GEO,
                AppClient.MWEB), order.subList(0, 6));
        assertEquals(AppClient.TV_DOWNGRADED, order.get(6));
        assertTrue(order.indexOf(AppClient.TV) > 6);
        assertEquals(14, order.size());
    }

    /**
     * Login-required content (private, members-only, age gates) can only be served by the account,
     * so in EVERY quarantine state both account heads must stay in the walk - quarantine reorders,
     * it never drops - and, with at most one head quarantined, the account is asked before any
     * anonymous web-pot client.
     */
    @Test
    public void everyQuarantineStateKeepsBothAccountHeadsReachable() {
        List<Set<AppClient>> states = Arrays.asList(noneForbidden(), forbidden(AppClient.TV),
                forbidden(AppClient.TV_DOWNGRADED),
                forbidden(AppClient.TV, AppClient.TV_DOWNGRADED));
        for (Set<AppClient> quarantined : states) {
            boolean webFirst = quarantined.size() == 2;
            List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                    webFirst ? AppClient.WEB_EMBED : AppClient.TV_DOWNGRADED,
                    AppClient.VISIONOS, true, false, true, quarantined, webFirst, false);

            assertTrue(quarantined.toString(), order.contains(AppClient.TV_DOWNGRADED));
            assertTrue(quarantined.toString(), order.contains(AppClient.TV));
            if (!webFirst) {
                int firstWebPot = order.indexOf(AppClient.WEB_EMBED);
                assertTrue(quarantined.toString(),
                        order.indexOf(AppClient.TV_DOWNGRADED) < firstWebPot);
                assertTrue(quarantined.toString(), order.indexOf(AppClient.TV) < firstWebPot);
            }
        }
    }

    /**
     * A bot-challenged guest identity makes every web-pot probe a guaranteed-dead round trip, so
     * they move behind everything else — without losing any client from the ring.
     */
    @Test
    public void anonChallengeMovesWebPotClientsToTheBack() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR, true, false, true,
                noneForbidden(), false, true);

        assertEquals(AppClient.TV_DOWNGRADED, order.get(0));
        assertEquals(AppClient.TV, order.get(1));
        assertEquals(Arrays.asList(
                AppClient.WEB_EMBED,
                AppClient.WEB,
                AppClient.WEB_SAFARI,
                AppClient.GEO,
                AppClient.MWEB), order.subList(order.size() - 5, order.size()));
        assertEquals(14, order.size());
    }

    /** It must also override an explicit web-first preference — dead is dead. */
    @Test
    public void anonChallengeOverridesWebFirstWhenAccountHeadIsExhausted() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.WEB_EMBED, AppClient.TV_DOWNGRADED, true, false, true,
                forbidden(AppClient.TV, AppClient.TV_DOWNGRADED), true, true);

        assertFalse(order.get(0).isWebPotRequired());
        assertEquals(14, order.size());
    }

    @Test
    public void authenticatedRecoveryHonorsCursorAndDefersFailedTvClient() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_EMBED, AppClient.TV_DOWNGRADED, true, true, true, noneForbidden());

        assertEquals(Arrays.asList(
                AppClient.VISIONOS,
                AppClient.WEB_EMBED,
                AppClient.WEB,
                AppClient.WEB_SAFARI,
                AppClient.GEO,
                AppClient.MWEB), order.subList(0, 6));
        assertTrue("the failed TV client is still deferred behind its Web siblings",
                order.indexOf(AppClient.TV_DOWNGRADED) >= 6);
        assertEquals(14, order.size());
    }

    /**
     * The account is gone for this open, so the first ANONYMOUS attempt should be the client that
     * mints nothing. It used to be WEB_EMBED, which generates a PO token before it can even ask -
     * on the exact path taken after a media 403, when the open is already slow.
     */
    @Test
    public void authenticatedRecoveryLeadsWithTheTokenFreeClient() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_EMBED, AppClient.TV_DOWNGRADED, true, true, true, noneForbidden());

        assertEquals(AppClient.VISIONOS, order.get(0));
        assertTrue("WEB_EMBED still backs it up", order.get(1).isWebPotRequired());
        assertEquals(order.size(), new HashSet<>(order).size());
    }

    /** Same when the account head is fully quarantined rather than mid-recovery. */
    @Test
    public void exhaustedAccountHeadLeadsWithTheTokenFreeClient() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.WEB_EMBED, AppClient.TV_DOWNGRADED, true, false, true,
                forbidden(AppClient.TV, AppClient.TV_DOWNGRADED), true, false);

        assertEquals(AppClient.VISIONOS, order.get(0));
        assertEquals(order.size(), new HashSet<>(order).size());
    }

    /**
     * The account must NOT be given away while it still works. An anonymous client returns fewer
     * formats than the authenticated head (41 vs 32 on device) and its /player response carries
     * anonymous playbackTracking URLs, so the watch is never attributed to the account.
     * <p>
     * The token-free client is now present in a signed-in order too, but strictly BEHIND the whole
     * account head: it decides what the walk falls through TO, never what it starts with.
     */
    @Test
    public void healthyAccountHeadIsNeverDisplacedByTheTokenFreeClient() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR, true, false, true, noneForbidden());

        assertEquals(AppClient.TV_DOWNGRADED, order.get(0));
        assertEquals(AppClient.TV, order.get(1));
        assertTrue("the account head keeps both of its attempts",
                order.indexOf(AppClient.VISIONOS) > order.indexOf(AppClient.TV));
    }

    /**
     * P2: a signed-in walk that falls through the TV head must spend its first ANONYMOUS attempt on
     * the client that mints nothing, not on WEB_EMBED. WEB_EMBED answering from the challenged
     * guest identity is what produced the bot check on 2026-09-07 (Fo89b8zAIE4).
     */
    @Test
    public void authenticatedFallbackReachesTheTokenFreeClientBeforeAnyWebPotClient() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR, true, false, true, noneForbidden());

        int tokenFree = order.indexOf(AppClient.VISIONOS);
        assertTrue("the token-free client must be in a signed-in order", tokenFree >= 0);
        for (AppClient client : order.subList(0, tokenFree)) {
            assertFalse("no web-pot client may be reached before it: " + client,
                    client.isWebPotRequired());
        }
        assertEquals("no client visited twice", order.size(), new HashSet<>(order).size());
    }

    /** TV never sets sPreferAttestedWebFallback, so its signed-in order stays as it was. */
    @Test
    public void tvAuthenticatedOrderDoesNotGainTheTokenFreeClient() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR, false, false, true, noneForbidden());

        assertFalse("off-ring head must stay out of a TV walk",
                order.contains(AppClient.VISIONOS));
        assertEquals(13, order.size());
    }

    /** Idempotent, and it never inserts when there is no web-pot client to get ahead of. */
    @Test
    public void tokenFreeInsertionIsIdempotentAndNeedsAWebPotClient() {
        List<AppClient> once = VideoInfoService.insertTokenFreeClientBeforeWebPot(
                Arrays.asList(AppClient.TV_DOWNGRADED, AppClient.WEB_EMBED, AppClient.ANDROID_VR));
        assertEquals(Arrays.asList(AppClient.TV_DOWNGRADED, AppClient.VISIONOS,
                AppClient.WEB_EMBED, AppClient.ANDROID_VR), once);
        assertEquals(once, VideoInfoService.insertTokenFreeClientBeforeWebPot(once));

        List<AppClient> noWebPot = Arrays.asList(AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR);
        assertEquals(noWebPot, VideoInfoService.insertTokenFreeClientBeforeWebPot(noWebPot));
    }

    // === P1: a guest challenge must not abort the ring ===================================

    /**
     * The ORDERING precondition for P1: at the moment WEB_EMBED is challenged in a real signed-in
     * order, an unchallenged client must still be behind it. This is what makes walking on
     * worthwhile; the walk-on behaviour itself is covered by {@code BotCheckWalkStateTest}.
     * <p>
     * From the 2026-09-07 Rusowsky failure (Fo89b8zAIE4, Pixel 9, cell): the walk reached
     * WEB_EMBED, got LOGIN_REQUIRED "…no eres un bot", and returned at attempt 3 of 10 - so
     * ANDROID_VR was never asked. Eight minutes later, same device/account/network, ANDROID_VR
     * answered the identical prefix with playable=y and 28 usable formats (aqz-KE-bpKQ).
     */
    @Test
    public void guestChallengeOnWebEmbedStillLeavesAnUnchallengedClientToTry() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, null, true, false, true, noneForbidden());

        int challengedAt = order.indexOf(AppClient.WEB_EMBED);
        assertTrue("WEB_EMBED is the client that gets challenged", challengedAt >= 0);
        assertTrue("the walk must carry on past a guest challenge",
                VideoInfoService.hasUnchallengedClientAfter(order, challengedAt, true));
        assertTrue("and ANDROID_VR - the client that actually served - is behind it",
                order.indexOf(AppClient.ANDROID_VR) > challengedAt);
        assertFalse("ANDROID_VR does not depend on the challenged guest identity",
                AppClient.ANDROID_VR.isWebPotRequired());
    }

    /** Web-pot clients all answer from the SAME challenged guest identity, so they never count. */
    @Test
    public void remainingWebPotClientsDoNotCountAsUnchallenged() {
        List<AppClient> webOnlyTail = Arrays.asList(
                AppClient.WEB_EMBED, AppClient.WEB, AppClient.WEB_SAFARI, AppClient.MWEB);

        assertFalse("a tail of nothing but web-pot clients is exhausted",
                VideoInfoService.hasUnchallengedClientAfter(webOnlyTail, 0, true));
        assertTrue("...but one platform client behind them is worth a round trip",
                VideoInfoService.hasUnchallengedClientAfter(
                        Arrays.asList(AppClient.WEB_EMBED, AppClient.WEB, AppClient.ANDROID_VR),
                        0, true));
    }

    /** Nothing after the last client, so the circuit is allowed to arm. */
    @Test
    public void challengeOnTheFinalClientExhaustsTheRing() {
        List<AppClient> order = Arrays.asList(AppClient.ANDROID_VR, AppClient.WEB_EMBED);

        assertFalse(VideoInfoService.hasUnchallengedClientAfter(order, 1, true));
    }

    /** Idempotent: an order already led by the token-free client is returned untouched. */
    @Test
    public void leadWithTokenFreeClientIsIdempotent() {
        List<AppClient> once = VideoInfoService.leadWithTokenFreeClient(
                Arrays.asList(AppClient.WEB_EMBED, AppClient.TV));
        assertEquals(Arrays.asList(AppClient.VISIONOS, AppClient.WEB_EMBED, AppClient.TV), once);
        assertEquals(once, VideoInfoService.leadWithTokenFreeClient(once));
    }

    // ---- P3: WEB_EMBED carrying the account (AppClient.setWebEmbedAuthEnabled) --------------
    //
    // The gate is process-wide static, so every test here restores it in @After. With it OFF the
    // whole ring must be byte-identical to before - that is what the rest of this file asserts.

    /** Default OFF: only the TV family may carry the account, exactly as before. */
    @Test
    public void webEmbedCarriesNoAccountByDefault() {
        assertFalse(AppClient.isWebEmbedAuthEnabled());
        assertFalse(AppClient.WEB_EMBED.isAuthCapable());
        assertTrue(AppClient.TV_DOWNGRADED.isAuthCapable());
        assertTrue(AppClient.TV.isAuthCapable());
        assertFalse(AppClient.VISIONOS.isAuthCapable());
    }

    /** Flipping the gate widens isAuthCapable by exactly one client, and never isAuthSupported. */
    @Test
    public void enablingTheGateAddsOnlyWebEmbed() {
        VideoInfoService.setWebEmbedAuthEnabled(true);

        assertTrue(AppClient.WEB_EMBED.isAuthCapable());
        assertFalse("the TV-only predicate must not move - the reload-page quarantine reads it",
                AppClient.WEB_EMBED.isAuthSupported());
        for (AppClient client : AppClient.values()) {
            if (client != AppClient.WEB_EMBED) {
                assertEquals("no other client may change: " + client,
                        client.isAuthSupported(), client.isAuthCapable());
            }
        }
    }

    /**
     * The point of the experiment: once the TV head is quarantined, the account-bearing WEB_EMBED
     * must actually get a turn. Without this it sits behind VISIONOS, which serves the video and
     * returns, so the arm measures nothing.
     */
    @Test
    public void anAccountBearingWebEmbedLeadsTheExhaustedHeadWalk() {
        VideoInfoService.setWebEmbedAuthEnabled(true);

        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.WEB_EMBED, AppClient.TV_DOWNGRADED, true, false, true,
                forbidden(AppClient.TV, AppClient.TV_DOWNGRADED), true, false);

        assertEquals(AppClient.WEB_EMBED, order.get(0));
        assertEquals("the token-free client stays right behind it as the safety net",
                AppClient.VISIONOS, order.get(1));
        assertEquals(order.size(), new HashSet<>(order).size());
    }

    /** Same inputs, gate off: VISIONOS leads, as the 2026-09-07 device round measured. */
    @Test
    public void theSameWalkIsUnchangedWhileTheGateIsOff() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.WEB_EMBED, AppClient.TV_DOWNGRADED, true, false, true,
                forbidden(AppClient.TV, AppClient.TV_DOWNGRADED), true, false);

        assertEquals(AppClient.VISIONOS, order.get(0));
    }

    /**
     * TV never calls the setter, but assert the shape anyway: a TV-style walk (preferWebFamily
     * false, which is what TV passes) must not gain the reordering even with the gate on.
     */
    @Test
    public void tvOrderIsUntouchedByTheGate() {
        List<AppClient> before = VideoInfoService.buildRequestVisitOrder(
                AppClient.WEB_EMBED, null, false, false, false, noneForbidden());
        VideoInfoService.setWebEmbedAuthEnabled(true);
        List<AppClient> after = VideoInfoService.buildRequestVisitOrder(
                AppClient.WEB_EMBED, null, false, false, false, noneForbidden());

        assertEquals(before, after);
    }

    /** Idempotent, and a no-op on an order that has no WEB_EMBED to promote. */
    @Test
    public void leadWithAuthenticatedWebClientIsIdempotentAndNeedsTheClient() {
        List<AppClient> once = VideoInfoService.leadWithAuthenticatedWebClient(
                Arrays.asList(AppClient.VISIONOS, AppClient.WEB_EMBED, AppClient.ANDROID_VR));
        assertEquals(Arrays.asList(AppClient.WEB_EMBED, AppClient.VISIONOS, AppClient.ANDROID_VR),
                once);
        assertEquals(once, VideoInfoService.leadWithAuthenticatedWebClient(once));

        List<AppClient> noWebEmbed = Arrays.asList(AppClient.VISIONOS, AppClient.ANDROID_VR);
        assertEquals(noWebEmbed, VideoInfoService.leadWithAuthenticatedWebClient(noWebEmbed));
    }

    /**
     * A live result with no dash manifest is held and the walk continues (see
     * sPreferDashManifestForLive). Only a client that can actually RETURN a dash manifest is worth
     * the extra round trip.
     *
     * <p>Pixel 9, 2026-09-07, two 24/7 live streams: every web-family client answered dash=n and
     * ANDROID_VR answered dash=y for both. Probing the rest cost five extra /player round trips on
     * 5yx6BWlEVcY - the flag is documented as costing ONE.
     */
    @Test
    public void onlyDashCapableClientsAreWorthProbingForALiveStream() {
        assertTrue(VideoInfoService.isLiveDashCandidate(AppClient.ANDROID_VR));
        // 2026-09-25, nI725iVsyoQ signed in: TV dash=n sabr=y, TV_DOWNGRADED dash=n sabr=n.
        assertFalse(VideoInfoService.isLiveDashCandidate(AppClient.TV));
        assertFalse(VideoInfoService.isLiveDashCandidate(AppClient.TV_DOWNGRADED));
    }

    /**
     * The measured walk: TV_DOWNGRADED quarantined and demoted behind VISIONOS, TV answers the live
     * stream at attempt 1 without a dash manifest and is held. The next /player must be ANDROID_VR
     * - not the quarantined TV_DOWNGRADED (+0.9 s on the Pixel) - and no account head may be spent
     * on the dash upgrade at all.
     */
    @Test
    public void aHeldLiveAnswerGoesStraightToAndroidVrAndNeverToAnAccountHead() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.VISIONOS, true, false, true,
                forbidden(AppClient.TV_DOWNGRADED));
        assertEquals(Arrays.asList(AppClient.TV, AppClient.VISIONOS, AppClient.TV_DOWNGRADED),
                order.subList(0, 3));

        List<AppClient> probed = new java.util.ArrayList<>();
        for (AppClient client : order.subList(1, order.size())) { // TV's answer is held
            if (VideoInfoService.isLiveDashCandidate(client)) {
                probed.add(client);
            }
        }

        assertEquals(Collections.singletonList(AppClient.ANDROID_VR), probed);
    }

    // ---- Definitive unplayable (2026-09-25, jfKfPfyJRdk walked all 11 clients) ----------------

    private static final String GONE = "la grabacion de esta emision en directo no esta disponible.";
    /** Sent with the account and confirmed by the server ({@code srvAuth=y}). */
    private static final Boolean SIGNED_IN = Boolean.TRUE;
    private static final Boolean NO_ACCOUNT = Boolean.FALSE;
    /** Sent with the account; the server did not say it saw one. */
    private static final Boolean UNCONFIRMED = null;

    /** The measured order: TV (account), VISIONOS (anonymous), TV_DOWNGRADED - stop at 3. */
    @Test
    public void theMeasuredDeadStreamIsSettledAtTheThirdAgreeingClient() {
        VideoInfoService.UnplayableConsensus consensus = new VideoInfoService.UnplayableConsensus();

        assertFalse(consensus.note(AppClient.TV, SIGNED_IN, "UNPLAYABLE", GONE));
        assertFalse("auth + anonymous agree; one more independent confirmation",
                consensus.note(AppClient.VISIONOS, NO_ACCOUNT, "UNPLAYABLE", GONE));
        assertTrue(consensus.note(AppClient.TV_DOWNGRADED, SIGNED_IN, "UNPLAYABLE", GONE));
        assertEquals("TV,VISIONOS,TV_DOWNGRADED", consensus.clients());
        assertEquals(Integer.toHexString(GONE.hashCode()), consensus.reasonHash());
    }

    /** Both sides are required: a signed-out walk, or only the account, never settles early. */
    @Test
    public void agreementNeedsAnAccountAndAnAnonymousClient() {
        VideoInfoService.UnplayableConsensus anonymous =
                new VideoInfoService.UnplayableConsensus();
        for (AppClient client : Arrays.asList(AppClient.VISIONOS, AppClient.ANDROID_VR,
                AppClient.WEB, AppClient.IOS)) {
            assertFalse(client.name(), anonymous.note(client, NO_ACCOUNT, "UNPLAYABLE", GONE));
        }

        VideoInfoService.UnplayableConsensus account = new VideoInfoService.UnplayableConsensus();
        assertFalse(account.note(AppClient.TV, SIGNED_IN, "UNPLAYABLE", GONE));
        assertFalse(account.note(AppClient.TV_DOWNGRADED, SIGNED_IN, "UNPLAYABLE", GONE));
        assertFalse("the same client twice is not a confirmation",
                account.note(AppClient.TV, SIGNED_IN, "UNPLAYABLE", GONE));
    }

    /**
     * Sending the credential is not the signed-in witness: only the server's logged_in answer is.
     * An unconfirmed account request still counts as a distinct client.
     */
    @Test
    public void anUnconfirmedCredentialIsNotTheSignedInWitness() {
        VideoInfoService.UnplayableConsensus consensus = new VideoInfoService.UnplayableConsensus();

        assertFalse(consensus.note(AppClient.TV, UNCONFIRMED, "UNPLAYABLE", GONE));
        assertFalse(consensus.note(AppClient.VISIONOS, NO_ACCOUNT, "UNPLAYABLE", GONE));
        assertFalse("three clients, but nobody the server saw signed in",
                consensus.note(AppClient.TV_DOWNGRADED, UNCONFIRMED, "UNPLAYABLE", GONE));
        assertFalse(consensus.note(AppClient.ANDROID_VR, NO_ACCOUNT, "UNPLAYABLE", GONE));
        assertTrue(consensus.note(AppClient.TV_EMBED, SIGNED_IN, "UNPLAYABLE", GONE));
    }

    /** A removal answers ERROR on every client; with an allowlisted reason it is a witness. */
    @Test
    public void anAllowlistedErrorIsAWitness() {
        String removed = "this video has been removed by the uploader";
        VideoInfoService.UnplayableConsensus consensus = new VideoInfoService.UnplayableConsensus();

        assertFalse(consensus.note(AppClient.TV, SIGNED_IN, "ERROR", removed));
        assertFalse(consensus.note(AppClient.VISIONOS, NO_ACCOUNT, "ERROR", removed));
        assertTrue(consensus.note(AppClient.TV_DOWNGRADED, SIGNED_IN, "ERROR", removed));
    }

    /** WEB_EMBED's embed ERROR and GEO's outdated-client ERROR, and timeouts, are neutral. */
    @Test
    public void errorAnswersAndTimeoutsNeitherConfirmNorBreak() {
        VideoInfoService.UnplayableConsensus consensus = new VideoInfoService.UnplayableConsensus();

        assertFalse(consensus.note(AppClient.VISIONOS, NO_ACCOUNT, "UNPLAYABLE", GONE));
        assertFalse(consensus.note(AppClient.WEB_EMBED, NO_ACCOUNT, "ERROR", null));
        assertFalse(consensus.note(AppClient.GEO, NO_ACCOUNT, null, null));
        assertFalse(consensus.note(AppClient.WEB, NO_ACCOUNT, "UNPLAYABLE", GONE));
        assertTrue(consensus.note(AppClient.TV_DOWNGRADED, SIGNED_IN, "UNPLAYABLE", GONE));
    }

    /**
     * Anything else ends the consensus for the rest of the walk: a client that could play or that
     * gates on sign-in/age, a client-specific reason (key null), or a different reason.
     */
    @Test
    public void anyDisagreementEndsTheConsensusForTheWalk() {
        String[][] breakers = {{"OK", null}, {"LOGIN_REQUIRED", null}, {"UNPLAYABLE", null},
                {"UNPLAYABLE", "another reason"}, {"AGE_CHECK_REQUIRED", null}};
        for (String[] breaker : breakers) {
            VideoInfoService.UnplayableConsensus consensus =
                    new VideoInfoService.UnplayableConsensus();
            consensus.note(AppClient.TV, SIGNED_IN, "UNPLAYABLE", GONE);
            consensus.note(AppClient.VISIONOS, NO_ACCOUNT, breaker[0], breaker[1]);

            assertFalse(breaker[0] + "/" + breaker[1],
                    consensus.note(AppClient.TV_DOWNGRADED, SIGNED_IN, "UNPLAYABLE", GONE));
            assertFalse(consensus.note(AppClient.ANDROID_VR, NO_ACCOUNT, "UNPLAYABLE", GONE));
            assertFalse(consensus.note(AppClient.WEB, NO_ACCOUNT, "UNPLAYABLE", GONE));
        }
    }

    /** The measured dash=n set: probing these can only repeat the answer already held. */
    @Test
    public void webFamilyClientsAreNotProbedForALiveDashManifest() {
        for (AppClient client : Arrays.asList(AppClient.VISIONOS, AppClient.WEB_EMBED,
                AppClient.WEB, AppClient.WEB_SAFARI, AppClient.MWEB, AppClient.GEO)) {
            assertFalse(client + " answered dash=n on both measured live streams",
                    VideoInfoService.isLiveDashCandidate(client));
        }
    }

    // ---- The gate widened to a NAMED client (2026-09-07) ---------------------------------
    //
    // Measured on the Pixel 9 the same day: WEB_EMBED and plain WEB both answer HTTP 400 with a
    // byte-identical body when the bearer is attached, and WEB answers 200 without it. The gate
    // takes a client name so that comparison stays runnable if YouTube's auth handling moves.

    /** Naming a client arms exactly that one, and leaves the TV predicate alone. */
    @Test
    public void namingAWebClientArmsOnlyThatClient() {
        assertTrue(VideoInfoService.setWebAuthClient("WEB"));

        assertTrue(AppClient.WEB.isAuthCapable());
        assertFalse("the TV-only predicate must not move", AppClient.WEB.isAuthSupported());
        assertFalse("arming WEB must not also arm WEB_EMBED",
                AppClient.WEB_EMBED.isAuthCapable());
        for (AppClient client : AppClient.values()) {
            if (client != AppClient.WEB) {
                assertEquals("no other client may change: " + client,
                        client.isAuthSupported(), client.isAuthCapable());
            }
        }
    }

    /** The old boolean keeps its exact meaning, so debug.arc.web_auth=1 is unchanged. */
    @Test
    public void theBooleanShimStillMeansWebEmbed() {
        VideoInfoService.setWebEmbedAuthEnabled(true);

        assertTrue(AppClient.isWebEmbedAuthEnabled());
        assertTrue(AppClient.WEB_EMBED.isAuthCapable());
        assertFalse(AppClient.WEB.isAuthCapable());
    }

    /** A typo must never hand the account to a TV or native client. */
    @Test
    public void aNonWebClientNameIsRefused() {
        assertFalse(VideoInfoService.setWebAuthClient("ANDROID_VR"));
        assertFalse(VideoInfoService.setWebAuthClient("TV"));
        assertFalse(VideoInfoService.setWebAuthClient("NOT_A_CLIENT"));

        for (AppClient client : AppClient.values()) {
            assertEquals("a refused name must arm nothing: " + client,
                    client.isAuthSupported(), client.isAuthCapable());
        }
    }

    /** Clearing it returns the ring to the shipped default. */
    @Test
    public void clearingTheGateDisarmsEveryWebClient() {
        assertTrue(VideoInfoService.setWebAuthClient("WEB_EMBED"));
        assertTrue(AppClient.WEB_EMBED.isAuthCapable());

        assertTrue(VideoInfoService.setWebAuthClient(null));
        assertFalse(AppClient.WEB_EMBED.isAuthCapable());
        assertTrue(VideoInfoService.setWebAuthClient(""));
        assertFalse(AppClient.WEB_EMBED.isAuthCapable());
    }

    @After
    public void resetWebEmbedAuthGate() {
        VideoInfoService.setWebEmbedAuthEnabled(false);
    }

    private static Set<AppClient> noneForbidden() {
        return Collections.emptySet();
    }

    private static Set<AppClient> forbidden(AppClient... clients) {
        return new HashSet<>(Arrays.asList(clients));
    }
}
