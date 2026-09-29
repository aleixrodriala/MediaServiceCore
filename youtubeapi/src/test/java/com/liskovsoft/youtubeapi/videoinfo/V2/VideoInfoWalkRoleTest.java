package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.SystemClock;

import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService.WalkRole;
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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Converter;

/**
 * NEWTUBE(walk-role): a preload's walk (the next video, a touch preload, the warmup) leaves the
 * watched video's routing state alone. The phone's walk as MobileMainApplication configures it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoBotWallTest.ShadowWalk.class, VideoInfoBotWallTest.ShadowTokenGate.class,
                VideoInfoWalkRoleTest.ShadowAppService.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoWalkRoleTest {
    private VideoInfoService service;

    @Implements(AppService.class)
    public static class ShadowAppService {
        @Implementation
        protected void __constructor__() {
        }

        @Implementation
        protected void resetClientPlaybackNonce() {
        }

        /** Nothing to decipher in these answers; no player JS. */
        @Implementation
        protected kotlin.Pair<List<String>, List<String>> bulkSigExtract(List<String> nParams, List<String> sParams) {
            return null;
        }

        /** NEWTUBE(player-js-gate): no player is being validated. */
        @Implementation
        protected boolean isPlayerJsValidationPending() {
            return false;
        }
    }

    @Before
    public void setUp() {
        VideoInfoBotWallTest.ShadowWalk.network = "wifi:100";
        VideoInfoBotWallTest.ShadowWalk.transport = "wifi";
        VideoInfoBotWallTest.ShadowWalk.signedIn = false;
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> playable(auth);
        VideoInfoService.setPreferNoPotClient(true);
        VideoInfoService.setPreferDashManifestForLive(true);
        service = ReflectionHelpers.callConstructor(VideoInfoService.class);
        initIfNull("mAuthRouteQuarantine", new AuthRouteQuarantineBook());
        initIfNull("mBotWall", new BotWallBook());
        initIfNull("mVideoWinners", Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, AppClient>()));
        initIfNull("mRoutingGeneration", new java.util.concurrent.atomic.AtomicLong());
        ReflectionHelpers.setField(service, "mWalkRole", WalkRole.ACTIVE);
    }

    @After
    public void tearDown() {
        VideoInfoService.setPreferNoPotClient(false);
        VideoInfoService.setPreferDashManifestForLive(false);
    }

    /** The player's 403 set a recovery cursor; the next-video preload runs before the reload. */
    @Test
    public void aPreloadNeitherFollowsNorSpendsTheRecoveryCursor() {
        ReflectionHelpers.setField(service, "mActualInfoType", AppClient.VISIONOS);
        service.switchNextFormat();
        assertTrue(ReflectionHelpers.<Boolean>getField(service, "mRecoveryWalk"));

        open("next", WalkRole.SPECULATIVE);
        assertEquals("the preload starts at the head", "VISIONOS", calls().get(0));
        assertTrue("the cursor is still there", ReflectionHelpers.<Boolean>getField(service, "mRecoveryWalk"));

        open("current", WalkRole.ACTIVE);
        assertFalse("the reload steps past the failed client", "VISIONOS".equals(calls().get(0)));
        assertFalse("and spends the cursor", ReflectionHelpers.<Boolean>getField(service, "mRecoveryWalk"));
    }

    @Test
    public void aPreloadDoesNotMoveTheCurrentClient() {
        ReflectionHelpers.setField(service, "mActualInfoType", AppClient.VISIONOS);
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.VISIONOS
                ? unplayable(auth) : playable(auth);

        VideoInfo next = open("kids", WalkRole.SPECULATIVE);
        assertFalse(next.isUnplayable());
        assertTrue(next.getClient() != AppClient.VISIONOS);
        assertSame(AppClient.VISIONOS, ReflectionHelpers.getField(service, "mActualInfoType"));

        VideoInfo watched = open("kids", WalkRole.ACTIVE);
        assertSame(watched.getClient(), ReflectionHelpers.getField(service, "mActualInfoType"));
    }

    /** The circuit lets one open through per interval to re-test the server: the user's, not a preload's. */
    @Test
    public void aPreloadNeverTakesTheBotCheckProbe() {
        VideoInfo challenge = unplayable(false);
        ReflectionHelpers.setField(service, "mBotCheckResult", challenge);
        ReflectionHelpers.setField(service, "mBotCheckCooldownUntilMs", SystemClock.elapsedRealtime() + 600_000);
        ReflectionHelpers.setField(service, "mBotCheckRingExhausted", true);
        ReflectionHelpers.setField(service, "mBotCheckNextProbeAtMs", SystemClock.elapsedRealtime() - 1);
        ReflectionHelpers.setField(service, "mBotCheckNetwork", "wifi:100");

        assertSame(challenge, open("next", WalkRole.SPECULATIVE));
        assertEquals(Collections.emptyList(), calls());

        assertFalse(open("watched", WalkRole.ACTIVE).isUnplayable());
        assertEquals(Collections.singletonList("VISIONOS"), calls());
    }

    /** A preload that trips the circuit leaves the watched video's recovery cursor in place. */
    @Test
    public void aPreloadsTripKeepsTheRecoveryCursor() {
        ReflectionHelpers.setField(service, "mActualInfoType", AppClient.VISIONOS);
        service.switchNextFormat();
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse(
                "{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                        + " \"reason\": \"Sign in to confirm you're not a bot\"}}", auth);
        open("next", WalkRole.SPECULATIVE);
        assertTrue("the circuit is armed", ReflectionHelpers.<Object>getField(service, "mBotCheckResult") != null);
        assertTrue("the cursor is the watched video's", ReflectionHelpers.<Boolean>getField(service, "mRecoveryWalk"));
    }

    /** The user opens the preloaded video: its winner becomes the current client. */
    @Test
    public void anOpenedPreloadIsAdopted() {
        ReflectionHelpers.setField(service, "mActualInfoType", AppClient.VISIONOS);
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.VISIONOS
                ? unplayable(auth) : playable(auth);
        VideoInfo next = open("kids", WalkRole.SPECULATIVE);
        assertSame(AppClient.VISIONOS, ReflectionHelpers.getField(service, "mActualInfoType"));

        service.adoptSpeculativeResult("kids", false, false);
        assertSame(next.getClient(), ReflectionHelpers.getField(service, "mActualInfoType"));
    }

    // ------------------------------------------------------------------------------------------

    private VideoInfo open(String videoId, WalkRole role) {
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        return service.getVideoInfo(videoId, null, null, role);
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
        return parse("{\"playabilityStatus\": {\"status\": \"OK\"}, \"videoDetails\": {\"videoId\": \"v\"}}", auth);
    }

    private static VideoInfo unplayable(boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"reason\": \"This video is not available\"}}",
                auth);
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
