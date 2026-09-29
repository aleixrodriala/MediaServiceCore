package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.innertube.ytcfg.YtCfgService;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VodDelivery;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * NEWTUBE(embed-reroll): the walk's side. A SABR-only WEB_EMBED answer plays as it is, and the
 * identity that got it is re-rolled; any other answer, or the switch off, keeps it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoBotWallTest.ShadowWalk.class, VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoEmbedRerollTest {
    private static final String NOT_AVAILABLE = "This video is not available";
    private final List<Runnable> background = new ArrayList<>();
    private VideoInfoService service;
    private Object savedExecutor;
    private YtCfgService.EmbedIdentity identity;

    @Before
    public void setUp() {
        VideoInfoBotWallTest.ShadowWalk.network = "wifi:100";
        VideoInfoBotWallTest.ShadowWalk.transport = "wifi";
        VideoInfoBotWallTest.ShadowWalk.signedIn = false;
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        VideoInfoService.setPreferNoPotClient(true);
        VideoInfoService.setPreferDashManifestForLive(true);
        VideoInfoService.setEmbedRerollEnabled(true);
        VodDelivery.setHlsEnabled(true);
        service = ReflectionHelpers.callConstructor(VideoInfoService.class);
        initIfNull("mAuthRouteQuarantine", new AuthRouteQuarantineBook());
        initIfNull("mBotWall", new BotWallBook());
        initIfNull("mVideoWinners", Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, AppClient>()));
        initIfNull("mRoutingGeneration", new java.util.concurrent.atomic.AtomicLong());
        savedExecutor = ReflectionHelpers.getStaticField(YtCfgService.class, "rerollExecutor");
        ReflectionHelpers.setStaticField(YtCfgService.class, "rerollExecutor",
                (Function1<Runnable, Unit>) task -> {
                    background.add(task);
                    return Unit.INSTANCE;
                });
        ReflectionHelpers.setStaticField(YtCfgService.class, "lastRerollAtMs", 0L);
        identity = new YtCfgService.EmbedIdentity("flags", "bucketed-visitor", System.currentTimeMillis());
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity", identity);
    }

    @After
    public void tearDown() {
        VideoInfoService.setPreferNoPotClient(false);
        VideoInfoService.setPreferDashManifestForLive(false);
        VideoInfoService.setEmbedRerollEnabled(false);
        VodDelivery.setHlsEnabled(false);
        ReflectionHelpers.setStaticField(YtCfgService.class, "rerollExecutor", savedExecutor);
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity", null);
        ReflectionHelpers.setStaticField(YtCfgService.class, "lastRerollAtMs", 0L);
    }

    /** The Pixel's 18+ opens: WEB_EMBED SABR-only, played over HLS, and the visitor replaced. */
    @Test
    public void aSabrOnlyAnswerPlaysAndItsIdentityIsReRolled() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? sabrOnly(auth) : unplayable(auth);
        VideoInfo result = open("adult");
        assertEquals(AppClient.WEB_EMBED, result.getClient());
        assertFalse(result.isUnplayable());
        assertNull(ReflectionHelpers.getStaticField(YtCfgService.class, "cachedEmbedIdentity"));
        assertEquals(1, background.size());
    }

    /** DASH formats from WEB_EMBED: the visitor is fine, nothing happens. */
    @Test
    public void aDashAnswerKeepsTheIdentity() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"OK\"}}", auth)
                : unplayable(auth);
        open("adult");
        assertSame(identity, ReflectionHelpers.getStaticField(YtCfgService.class, "cachedEmbedIdentity"));
        assertTrue(background.isEmpty());
    }

    /** The rollback (debug.arc.embed_reroll=0): kept. */
    @Test
    public void offTheIdentityIsKept() {
        VideoInfoService.setEmbedRerollEnabled(false);
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? sabrOnly(auth) : unplayable(auth);
        open("adult");
        assertSame(identity, ReflectionHelpers.getStaticField(YtCfgService.class, "cachedEmbedIdentity"));
        assertTrue(background.isEmpty());
    }

    @Test
    public void whatIsSabrOnly() {
        assertTrue(VideoInfoService.isSabrOnlyAnswer(sabrOnly(false)));
        assertFalse("DASH", VideoInfoService.isSabrOnlyAnswer(VideoInfoBotWallTest.parse(
                "{\"playabilityStatus\": {\"status\": \"OK\"}, \"streamingData\": {\"adaptiveFormats\":"
                        + " [{\"itag\": 137, \"url\": \"https://media.invalid/v\", \"mimeType\": \"video/mp4\"}],"
                        + " \"hlsManifestUrl\": \"https://media.invalid/hls.m3u8\"}}", false)));
        assertFalse("no HLS", VideoInfoService.isSabrOnlyAnswer(VideoInfoBotWallTest.parse(
                "{\"playabilityStatus\": {\"status\": \"OK\"}, \"streamingData\": {\"adaptiveFormats\":"
                        + " [{\"itag\": 137, \"mimeType\": \"video/mp4\"}]}}", false)));
        assertFalse("live", VideoInfoService.isSabrOnlyAnswer(VideoInfoBotWallTest.parse(
                "{\"playabilityStatus\": {\"status\": \"OK\"}, \"videoDetails\": {\"videoId\": \"x\","
                        + " \"isLive\": true, \"isLiveContent\": true}, \"streamingData\": {\"adaptiveFormats\":"
                        + " [{\"itag\": 137, \"mimeType\": \"video/mp4\"}],"
                        + " \"hlsManifestUrl\": \"https://media.invalid/hls.m3u8\"}}", false)));
        assertFalse("refused", VideoInfoService.isSabrOnlyAnswer(unplayable(false)));
        assertFalse(VideoInfoService.isSabrOnlyAnswer(null));
    }

    private static VideoInfo sabrOnly(boolean auth) {
        return VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"OK\"}, \"streamingData\":"
                + " {\"adaptiveFormats\": [{\"itag\": 137, \"mimeType\": \"video/mp4\"}],"
                + " \"hlsManifestUrl\": \"https://media.invalid/hls.m3u8\","
                + " \"serverAbrStreamingUrl\": \"https://media.invalid/sabr\"}}", auth);
    }

    private static VideoInfo unplayable(boolean auth) {
        return VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                + " \"reason\": \"Sign in to confirm your age\", \"desktopLegacyAgeGateReason\": 1}}", auth);
    }

    private VideoInfo open(String videoId) {
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        return ReflectionHelpers.callInstanceMethod(service, "firstPlayable",
                ClassParameter.from(String.class, videoId),
                ClassParameter.from(String.class, null),
                ClassParameter.from(boolean.class, false),
                ClassParameter.from(VideoInfoService.CancellationSignal.class, null));
    }

    private void initIfNull(String field, Object value) {
        if (ReflectionHelpers.getField(service, field) == null) {
            ReflectionHelpers.setField(service, field, value);
        }
    }
}
