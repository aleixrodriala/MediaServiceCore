package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 * NEWTUBE(planner): the phone's walk with the order from PhoneSourcePlanner, as
 * MobileMainApplication configures it plus {@code setPlannerEnabled}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoBotWallTest.ShadowWalk.class, VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoPlannerTest {
    private static final String NOT_AVAILABLE = "This video is not available";
    private VideoInfoService service;

    @Before
    public void setUp() {
        VideoInfoBotWallTest.ShadowWalk.network = "wifi:100";
        VideoInfoBotWallTest.ShadowWalk.transport = "wifi";
        VideoInfoBotWallTest.ShadowWalk.signedIn = false;
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> playable(auth);
        VideoInfoService.setPreferNoPotClient(true);
        VideoInfoService.setPreferAttestedWebFallback(true);
        VideoInfoService.setSkipTvFallbackClients(true);
        VideoInfoService.setPreferDashManifestForLive(true);
        VideoInfoService.setWebEmbedLast(true);
        VideoInfoService.setPlannerEnabled(true);
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
        VideoInfoService.setWebEmbedLast(false);
        VideoInfoService.setPlannerEnabled(false);
    }

    @Test
    public void aHealthyOpenIsOneRequest() {
        assertFalse(open("normal").isUnplayable());
        assertEquals(Collections.singletonList("VISIONOS"), calls());
    }

    /** Issue #5: VISIONOS refuses a kids video, TV_TIZEN (no account) serves it second. */
    @Test
    public void aKidsVideoIsServedSecond() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN
                ? playable(auth) : unplayable(NOT_AVAILABLE, auth);
        VideoInfo result = open("kids");
        assertEquals(AppClient.TV_TIZEN, result.getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN"), calls());
    }

    @Test
    public void whenTizenRefusesWebEmbedIsThird() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : unplayable(NOT_AVAILABLE, auth);
        assertEquals(AppClient.WEB_EMBED, open("kids").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN", "WEB_EMBED"), calls());
    }

    /** Nothing serves it: the video's own refusal, the whole lane asked once, no TV 7.x or GEO. */
    @Test
    public void aVideoNothingServesAsksTheLaneOnce() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> unplayable(NOT_AVAILABLE, auth);
        VideoInfo result = open("gone");
        assertTrue(result.isUnplayable());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN", "WEB_EMBED", "ANDROID_VR", "IOS", "ANDROID_REEL",
                "MWEB", "WEB", "WEB_SAFARI"), calls());
    }

    /** An age gate: TV_TIZEN anonymous answers it the same, so WEB_EMBED is next. */
    @Test
    public void anAgeGateGoesStraightToWebEmbed() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                        + " \"reason\": \"Sign in to confirm your age\", \"desktopLegacyAgeGateReason\": 1}}", auth);
        assertEquals(AppClient.WEB_EMBED, open("adult").getClient());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED"), calls());
    }

    /** Live: VISIONOS's HLS answer is held and only the live-DASH client is asked. */
    @Test
    public void liveGoesStraightToTheDashClient() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse(
                "{\"playabilityStatus\": {\"status\": \"OK\"}, \"videoDetails\": {\"videoId\": \"live\","
                        + " \"isLive\": true, \"isLiveContent\": true}, \"streamingData\": {\"hlsManifestUrl\":"
                        + " \"https://media.invalid/live.m3u8\"" + (client == AppClient.ANDROID_VR
                        ? ", \"dashManifestUrl\": \"https://media.invalid/live.mpd\"" : "") + "}}", auth);
        VideoInfo result = open("live");
        assertEquals(AppClient.ANDROID_VR, result.getClient());
        assertEquals(Arrays.asList("VISIONOS", "ANDROID_VR"), calls());
    }

    @Test
    public void signedInTheAccountHeadStillLeads() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        open("normal");
        assertEquals(Collections.singletonList("TV_DOWNGRADED+auth"), calls());
    }

    /** Off, the walk is the ring as before (the released order, WEB_EMBED last). */
    @Test
    public void offTheRingIsUnchanged() {
        VideoInfoService.setPlannerEnabled(false);
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : unplayable(NOT_AVAILABLE + " " + client, auth);
        open("kids");
        assertEquals(Arrays.asList("VISIONOS", "WEB", "WEB_SAFARI", "GEO", "MWEB", "ANDROID_VR", "ANDROID_REEL",
                "TV", "IOS", "WEB_EMBED"), calls());
    }

    // ------------------------------------------------------------------------------------------

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

    private void initIfNull(String field, Object value) {
        if (ReflectionHelpers.getField(service, field) == null) {
            ReflectionHelpers.setField(service, field, value);
        }
    }

    private static VideoInfo playable(boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"OK\"}}", auth);
    }

    private static VideoInfo unplayable(String reason, boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"reason\": \""
                + reason + "\"}}", auth);
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
