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
        assertFalse(VideoInfoApiHelper.usesWebVisitorData(AppClient.ANDROID_REEL));
        assertFalse(VideoInfoApiHelper.usesWebVisitorData(AppClient.IOS));
        assertFalse(VideoInfoApiHelper.usesWebVisitorData(AppClient.TV));
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

        assertEquals(Arrays.asList(
                AppClient.WEB_EMBED,
                AppClient.WEB,
                AppClient.WEB_SAFARI,
                AppClient.GEO,
                AppClient.MWEB), order.subList(0, 5));
        assertEquals(AppClient.TV_DOWNGRADED, order.get(5));
        assertTrue(order.indexOf(AppClient.TV) > 5);
        assertEquals(13, order.size());
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
        assertEquals(13, order.size());
    }

    @Test
    public void authenticatedRecoveryHonorsCursorAndDefersFailedTvClient() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_EMBED, AppClient.TV_DOWNGRADED, true, true, true, noneForbidden());

        assertEquals(Arrays.asList(
                AppClient.WEB_EMBED,
                AppClient.WEB,
                AppClient.WEB_SAFARI,
                AppClient.GEO,
                AppClient.MWEB), order.subList(0, 5));
        assertTrue(order.indexOf(AppClient.TV_DOWNGRADED) >= 5);
        assertEquals(13, order.size());
    }

    private static Set<AppClient> noneForbidden() {
        return Collections.emptySet();
    }

    private static Set<AppClient> forbidden(AppClient... clients) {
        return new HashSet<>(Arrays.asList(clients));
    }
}
