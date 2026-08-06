package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

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
    public void authenticatedHeadGetsAColdStartBudget() {
        long head = VideoInfoService.attemptTimeoutMsFor(AppClient.TV_DOWNGRADED);
        long speculative = VideoInfoService.attemptTimeoutMsFor(AppClient.ANDROID_VR);

        assertEquals(head, VideoInfoService.attemptTimeoutMsFor(AppClient.TV));
        assertTrue("auth head must outlast a cold start", head >= 15_000);
        assertTrue("speculative clients keep the short budget", speculative < head);
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

    /** The debug playground has to be able to force the off-ring head to A/B it against VR. */
    @Test
    public void debugPlaygroundCanForceTheOffRingHead() {
        try {
            assertTrue(VideoInfoService.setDebugForcedClient("VISIONOS"));
            assertTrue(VideoInfoService.setDebugForcedClient("ANDROID_VR"));
            assertFalse("clients outside both the ring and the head stay rejected",
                    VideoInfoService.setDebugForcedClient("WEB_MUSIC"));
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
        assertEquals(13, order.size());
    }

    /** A quarantined head client is demoted WITHIN the head, never dropped or skipped past. */
    @Test
    public void quarantinedHeadClientIsDemotedBehindItsAccountBearingSibling() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR, true, false, true,
                forbidden(AppClient.TV_DOWNGRADED));

        assertEquals(AppClient.TV, order.get(0));
        assertEquals(AppClient.TV_DOWNGRADED, order.get(1));
        assertEquals(13, order.size());
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
        assertEquals(13, order.size());
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
     */
    @Test
    public void healthyAccountHeadIsNeverDisplacedByTheTokenFreeClient() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_DOWNGRADED, AppClient.ANDROID_VR, true, false, true, noneForbidden());

        assertEquals(AppClient.TV_DOWNGRADED, order.get(0));
        assertEquals(AppClient.TV, order.get(1));
        assertFalse("off-ring head must stay out of an authenticated walk",
                order.contains(AppClient.VISIONOS));
    }

    /** Idempotent: an order already led by the token-free client is returned untouched. */
    @Test
    public void leadWithTokenFreeClientIsIdempotent() {
        List<AppClient> once = VideoInfoService.leadWithTokenFreeClient(
                Arrays.asList(AppClient.WEB_EMBED, AppClient.TV));
        assertEquals(Arrays.asList(AppClient.VISIONOS, AppClient.WEB_EMBED, AppClient.TV), once);
        assertEquals(once, VideoInfoService.leadWithTokenFreeClient(once));
    }

    private static Set<AppClient> noneForbidden() {
        return Collections.emptySet();
    }

    private static Set<AppClient> forbidden(AppClient... clients) {
        return new HashSet<>(Arrays.asList(clients));
    }
}
