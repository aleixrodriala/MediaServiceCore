package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Converter;

/**
 * NEWTUBE(no-web-embed): with the phone's gates on, no walk ever sends WEB_EMBED (it answers
 * "152 - 18" everywhere), and every path it used to serve still ends where it should. The real
 * firstPlayable against scripted answers, as in VideoInfoBotWallTest (whose shadows it reuses).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoBotWallTest.ShadowWalk.class, VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoSkipWebEmbedTest {
    private static final String AGE = "Sign in to confirm your age";
    private VideoInfoService service;

    @Before
    public void setUp() {
        VideoInfoBotWallTest.ShadowWalk.network = "wifi:100";
        VideoInfoBotWallTest.ShadowWalk.transport = "wifi";
        VideoInfoBotWallTest.ShadowWalk.signedIn = false;
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> playable(auth);
        // Exactly what MobileMainApplication sets.
        VideoInfoService.setPreferNoPotClient(true);
        VideoInfoService.setPreferAttestedWebFallback(true);
        VideoInfoService.setSkipTvFallbackClients(true);
        VideoInfoService.setPreferDashManifestForLive(true);
        VideoInfoService.setSkipWebEmbed(true);
        service = ReflectionHelpers.callConstructor(VideoInfoService.class);
        initIfNull("mAuthRouteQuarantine", new AuthRouteQuarantineBook());
        initIfNull("mBotWall", new BotWallBook());
        initIfNull("mVideoWinners", Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, AppClient>()));
        initIfNull("mRoutingGeneration", new java.util.concurrent.atomic.AtomicLong());
    }

    @After
    public void tearDown() {
        VideoInfoService.setPreferNoPotClient(false);
        VideoInfoService.setPreferAttestedWebFallback(false);
        VideoInfoService.setSkipTvFallbackClients(false);
        VideoInfoService.setPreferDashManifestForLive(false);
        VideoInfoService.setSkipWebEmbed(false);
        VideoInfoService.setDebugForcedClient(null);
        VideoInfoService.setWebAuthClient(null);
    }

    /** 1. A healthy open is the single request it always was. */
    @Test
    public void aHealthyOpenIsUnchanged() {
        assertFalse(open("v").isUnplayable());
        assertEquals(Collections.singletonList("VISIONOS"), calls());
    }

    /** 2. VISIONOS cannot serve (made for kids): the Web partition now starts at WEB. */
    @Test
    public void signedOutFallbackStartsAtWeb() {
        script(AppClient.VISIONOS, unplayable("This video is made for kids"));
        VideoInfo result = open("v");
        assertEquals(AppClient.WEB, result.getClient());
        assertEquals(Arrays.asList("VISIONOS", "WEB"), calls());
    }

    /** 3. Signed in, age-gated: the account route serves it, on attempt 2. */
    @Test
    public void signedInAgeGateGoesToTheAccountRoute() {
        signedInWithQuarantinedHeads();
        script(AppClient.VISIONOS, loginRequired(AGE));
        VideoInfo result = open("v");
        assertEquals(AppClient.TV_TIZEN, result.getClient());
        assertFalse(result.isUnplayable());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), calls());
    }

    /** 4. Signed in, the account may not watch it either: YouTube's own reason, still no WEB_EMBED. */
    @Test
    public void signedInAgeGateTheAccountCannotPassEndsWithTheReason() {
        signedInWithQuarantinedHeads();
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> loginRequired(AGE).apply(auth);
        VideoInfo result = open("v");
        assertTrue(result.isUnplayable());
        assertTrue(result.getPlayabilityStatus().contains(AGE));
        List<String> calls = calls();
        assertEquals("VISIONOS", calls.get(0));
        assertEquals("TV_TIZEN+auth", calls.get(1));
        assertNoWebEmbed(calls);
    }

    /** 5. Signed out, age-gated: nothing can serve it; it ends with the reason, no WEB_EMBED. */
    @Test
    public void signedOutAgeGateEndsWithTheReason() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> loginRequired(AGE).apply(auth);
        VideoInfo result = open("v");
        assertTrue(result.isUnplayable());
        assertTrue(result.getPlayabilityStatus().contains(AGE));
        List<String> calls = calls();
        assertEquals("VISIONOS", calls.get(0));
        assertTrue("the rest of the ring still had its turn", calls.contains("WEB"));
        assertNoWebEmbed(calls);
    }

    /** 6. Live: VISIONOS's HLS-only answer is held, ANDROID_VR brings the DASH manifest. */
    @Test
    public void liveStillWalksToTheDashClient() {
        script(AppClient.VISIONOS, live(false));
        script(AppClient.ANDROID_VR, live(true));
        VideoInfo result = open("live");
        assertEquals(AppClient.ANDROID_VR, result.getClient());
        assertNotNull(result.getDashManifestUrl());
        assertEquals(Arrays.asList("VISIONOS", "ANDROID_VR"), calls());

        script(AppClient.ANDROID_VR, unplayable("Something went wrong"));
        VideoInfo held = open("live2");
        assertEquals("the held HLS answer", AppClient.VISIONOS, held.getClient());
        assertEquals(Arrays.asList("VISIONOS", "ANDROID_VR"), calls());
    }

    /** 7. A recovery after VISIONOS begins at ring element 0 - WEB_EMBED - and skips it. */
    @Test
    public void aRecoveryWalkSkipsItsWebEmbedBegin() {
        remember("v", AppClient.VISIONOS);
        service.anchorRouteToVideo("v");
        service.switchNextFormat();
        assertEquals(AppClient.WEB_EMBED, ReflectionHelpers.getField(service, "mNextInfoType"));

        VideoInfo result = open("v");
        assertEquals(AppClient.WEB, result.getClient());
        assertEquals(Collections.singletonList("WEB"), calls());
    }

    /** 8. Gate off (TV, and the phone before this change): WEB_EMBED is asked as it always was. */
    @Test
    public void withTheGateOffWebEmbedIsStillAsked() {
        VideoInfoService.setSkipWebEmbed(false);
        script(AppClient.VISIONOS, unplayable("This video is made for kids"));
        script(AppClient.WEB_EMBED, unplayable("This video is unavailable - Error code: 152 - 18"));
        open("v");
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED", "WEB"), calls());
    }

    /** 9. The two debug playgrounds that name WEB_EMBED still reach it. */
    @Test
    public void theDebugPlaygroundsStillReachWebEmbed() {
        assertTrue(VideoInfoService.setDebugForcedClient("WEB_EMBED"));
        open("v");
        assertEquals(Collections.singletonList("WEB_EMBED"), calls());
        VideoInfoService.setDebugForcedClient(null);

        signedInWithQuarantinedHeads();
        assertTrue(VideoInfoService.setWebAuthClient("WEB_EMBED"));
        open("w");
        assertEquals(Collections.singletonList("WEB_EMBED+auth"), calls());
    }

    // ------------------------------------------------------------------------------------------

    private interface Answer {
        VideoInfo apply(boolean auth);
    }

    private final java.util.Map<AppClient, Answer> mScripted = new java.util.EnumMap<>(AppClient.class);

    private void script(AppClient client, Answer answer) {
        mScripted.put(client, answer);
        VideoInfoBotWallTest.ShadowWalk.script = (c, auth) -> {
            Answer scripted = mScripted.get(c);
            return scripted != null ? scripted.apply(auth) : playable(auth);
        };
    }

    private static void assertNoWebEmbed(List<String> calls) {
        for (String call : calls) {
            assertFalse(calls.toString(), call.startsWith("WEB_EMBED"));
        }
    }

    private VideoInfo open(String videoId) {
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        return ReflectionHelpers.callInstanceMethod(service, "firstPlayable",
                ClassParameter.from(String.class, videoId),
                ClassParameter.from(String.class, null),
                ClassParameter.from(boolean.class, VideoInfoBotWallTest.ShadowWalk.signedIn),
                ClassParameter.from(VideoInfoService.CancellationSignal.class, null));
    }

    private static List<String> calls() {
        return new ArrayList<>(VideoInfoBotWallTest.ShadowWalk.calls);
    }

    private void signedInWithQuarantinedHeads() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        for (AppClient head : new AppClient[] {AppClient.TV_DOWNGRADED, AppClient.TV}) {
            ReflectionHelpers.setField(service, "mActualInfoType", head);
            service.markCurrentPlaybackRouteForbidden();
        }
        ReflectionHelpers.setField(service, "mActualInfoType", null);
    }

    private void remember(String videoId, AppClient client) {
        VideoInfo served = playable(false);
        served.setClient(client);
        ReflectionHelpers.callInstanceMethod(service, "rememberVideoWinner",
                ClassParameter.from(String.class, videoId), ClassParameter.from(VideoInfo.class, served));
        ReflectionHelpers.setField(service, "mActualInfoType", client);
    }

    private void initIfNull(String field, Object value) {
        if (ReflectionHelpers.getField(service, field) == null) {
            ReflectionHelpers.setField(service, field, value);
        }
    }

    private static VideoInfo playable(boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"OK\"}}", auth);
    }

    private static Answer unplayable(String reason) {
        return auth -> parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"reason\": \""
                + reason + "\"}}", auth);
    }

    private static Answer loginRequired(String reason) {
        return auth -> parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                + " \"reason\": \"" + reason + "\"}}", auth);
    }

    private static Answer live(boolean dash) {
        return auth -> parse("{\"playabilityStatus\": {\"status\": \"OK\"},"
                + " \"videoDetails\": {\"videoId\": \"live\", \"isLive\": true, \"isLiveContent\": true},"
                + " \"streamingData\": {\"hlsManifestUrl\": \"https://manifest.invalid/hls.m3u8\""
                + (dash ? ", \"dashManifestUrl\": \"https://manifest.invalid/dash.mpd\"" : "")
                + "}}", auth);
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
}
