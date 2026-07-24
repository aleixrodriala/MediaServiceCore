package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class VideoInfoVisitOrderTest {
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

    @Test
    public void authenticatedOrderStartsWithTv() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV, AppClient.ANDROID_VR, true, false, true, false);

        assertEquals(AppClient.TV, order.get(0));
        assertEquals(AppClient.TV_DOWNGRADED, order.get(1));
        assertEquals(AppClient.ANDROID_VR, order.get(2));
        assertEquals(13, order.size());
    }

    @Test
    public void authenticatedOrderStartsWithDowngradedTvOnceTvIsSabrOnly() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV, AppClient.ANDROID_VR, true, false, true, true);

        assertEquals(AppClient.TV_DOWNGRADED, order.get(0));
        assertEquals(AppClient.TV, order.get(1));
        assertEquals(AppClient.ANDROID_VR, order.get(2));
        assertEquals(13, order.size());
    }

    @Test
    public void authenticatedRecent403StartsWithWebButKeepsTvFallbacks() {
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.WEB_EMBED, AppClient.TV_DOWNGRADED,
                true, false, true, true, true);

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

    @Test
    public void authenticatedRecoveryHonorsCursorAndDefersFailedTvClient() {
        // authTvSabrOnly=true must not disturb recovery ordering — the swap is happy-path only.
        List<AppClient> order = VideoInfoService.buildRequestVisitOrder(
                AppClient.TV_EMBED, AppClient.TV_DOWNGRADED, true, true, true, true);

        assertEquals(Arrays.asList(
                AppClient.WEB_EMBED,
                AppClient.WEB,
                AppClient.WEB_SAFARI,
                AppClient.GEO,
                AppClient.MWEB), order.subList(0, 5));
        assertTrue(order.indexOf(AppClient.TV_DOWNGRADED) >= 5);
        assertEquals(13, order.size());
    }
}
