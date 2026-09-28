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
 * NEWTUBE(web-embed-last): the phone asks WEB_EMBED LAST instead of never. A video another client
 * serves never reaches it; one that every other client refuses (made-for-kids answered "not
 * available" or SABR-only, issue #5) gets it as the final attempt. The real firstPlayable against
 * scripted answers, as in VideoInfoSkipWebEmbedTest (whose harness this copies).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoBotWallTest.ShadowWalk.class, VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoWebEmbedLastTest {
    private static final String AGE = "Sign in to confirm your age";
    private static final String NOT_AVAILABLE = "This video is not available";
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
        VideoInfoService.setWebEmbedLast(true);
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
        VideoInfoService.setDebugForcedClient(null);
        VideoInfoService.setWebAuthClient(null);
    }

    /** 1. A healthy open is the single request it always was. */
    @Test
    public void aHealthyOpenIsUnchanged() {
        assertFalse(open("v").isUnplayable());
        assertEquals(Collections.singletonList("VISIONOS"), calls());
    }

    /** 2. VISIONOS cannot serve (made for kids) but WEB can: WEB_EMBED is never reached. */
    @Test
    public void aVideoAnotherClientServesNeverReachesWebEmbed() {
        script(AppClient.VISIONOS, unplayable("This video is made for kids"));
        VideoInfo result = open("v");
        assertEquals(AppClient.WEB, result.getClient());
        assertEquals(Arrays.asList("VISIONOS", "WEB"), calls());
    }

    /** 3. Issue #5: everything else refuses it; WEB_EMBED is asked once, last, and serves it. */
    @Test
    public void whenNothingElseServesWebEmbedIsTheLastAttempt() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : unplayable(NOT_AVAILABLE + " " + client).apply(auth);
        VideoInfo result = open("kids");
        assertFalse(result.isUnplayable());
        assertEquals(AppClient.WEB_EMBED, result.getClient());
        List<String> calls = calls();
        assertEquals(calls.toString(), "WEB_EMBED", calls.get(calls.size() - 1));
        assertEquals(calls.toString(), 1, Collections.frequency(calls, "WEB_EMBED"));
        assertTrue(calls.toString(), calls.size() > 2);
    }

    /** 4. A recovery after VISIONOS begins at ring element 0 - WEB_EMBED - which goes last. */
    @Test
    public void aRecoveryWalkStillStartsAtWeb() {
        remember("v", AppClient.VISIONOS);
        service.anchorRouteToVideo("v");
        service.switchNextFormat();
        assertEquals(AppClient.WEB_EMBED, ReflectionHelpers.getField(service, "mNextInfoType"));

        VideoInfo result = open("v");
        assertEquals(AppClient.WEB, result.getClient());
        assertEquals(Collections.singletonList("WEB"), calls());
    }

    /** 5. Signed in, age-gated: the account route still serves it first. */
    @Test
    public void signedInAgeGateGoesToTheAccountRoute() {
        signedInWithQuarantinedHeads();
        script(AppClient.VISIONOS, loginRequired(AGE));
        VideoInfo result = open("v");
        assertEquals(AppClient.TV_TIZEN, result.getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), calls());
    }

    /** 6. The pure reorder: WEB_EMBED to the end, everything else in its order, no-ops kept. */
    @Test
    public void moveWebEmbedLastKeepsTheRestInOrder() {
        assertEquals(Arrays.asList(AppClient.VISIONOS, AppClient.WEB, AppClient.WEB_EMBED),
                VideoInfoService.moveWebEmbedLast(
                        Arrays.asList(AppClient.WEB_EMBED, AppClient.VISIONOS, AppClient.WEB)));
        List<AppClient> without = Arrays.asList(AppClient.VISIONOS, AppClient.WEB);
        assertTrue(without == VideoInfoService.moveWebEmbedLast(without));
        List<AppClient> already = Arrays.asList(AppClient.WEB, AppClient.WEB_EMBED);
        assertTrue(already == VideoInfoService.moveWebEmbedLast(already));
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
