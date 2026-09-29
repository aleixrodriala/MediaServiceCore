package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

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
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(token-warmup): the WEB subtitle-enrichment /player waits for the app's gate only when it
 * would build the BotGuard WebView (the session is not minted yet), and always leaves in the end.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoEnrichmentGateTest.ShadowEnrichment.class, VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoEnrichmentGateTest {
    private VideoInfoService service;
    private final List<Runnable> held = new ArrayList<>();

    @Before
    public void setUp() {
        ShadowEnrichment.calls.clear();
        VideoInfoBotWallTest.ShadowTokenGate.webSessionReady = false;
        service = ReflectionHelpers.callConstructor(VideoInfoService.class);
    }

    @After
    public void tearDown() {
        VideoInfoService.setEnrichmentGate(null);
        VideoInfoBotWallTest.ShadowTokenGate.webSessionReady = true;
    }

    /** Cold: held by the gate, nothing leaves; released: the WEB request leaves. */
    @Test
    public void aColdEnrichmentWaitsForTheGateThenLeaves() throws InterruptedException {
        VideoInfoService.setEnrichmentGate(held::add);
        enrich("v1");
        assertEquals(1, held.size());
        Thread.sleep(200);
        assertTrue(ShadowEnrichment.calls.toString(), ShadowEnrichment.calls.isEmpty());
        held.get(0).run();
        awaitCall("WEB v1");
    }

    /** A minted session is never held: the request leaves as before. */
    @Test
    public void aWarmEnrichmentIsNotHeld() throws InterruptedException {
        VideoInfoBotWallTest.ShadowTokenGate.webSessionReady = true;
        VideoInfoService.setEnrichmentGate(held::add);
        enrich("v2");
        assertTrue(held.isEmpty());
        awaitCall("WEB v2");
    }

    /** No gate (TV, the rollback): as before. */
    @Test
    public void withoutAGateNothingIsHeld() throws InterruptedException {
        enrich("v3");
        awaitCall("WEB v3");
    }

    private void enrich(String videoId) {
        VideoInfo info = VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"OK\"}, \"captions\":"
                + " {\"playerCaptionsTracklistRenderer\": {\"captionTracks\": [{\"baseUrl\":"
                + " \"https://media.invalid/cc\", \"languageCode\": \"en\"}]}}}", false);
        ReflectionHelpers.callInstanceMethod(service, "applyFixesAsync",
                ClassParameter.from(VideoInfo.class, info),
                ClassParameter.from(String.class, videoId),
                ClassParameter.from(String.class, null));
    }

    private static void awaitCall(String call) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!ShadowEnrichment.calls.contains(call) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(ShadowEnrichment.calls.toString(), ShadowEnrichment.calls.contains(call));
    }

    @Implements(VideoInfoService.class)
    public static class ShadowEnrichment {
        static final List<String> calls = Collections.synchronizedList(new ArrayList<>());

        @Implementation
        protected void __constructor__() {
        }

        /** The enrichment's /player: recorded, never sent. */
        @Implementation
        protected VideoInfo getVideoInfo(AppClient client, String videoId, String clickTrackingParams) {
            calls.add(client.name() + " " + videoId);
            return null;
        }

        @Implementation
        protected static boolean shouldObtainExtendedFormats(VideoInfo result) {
            return false;
        }
    }
}
