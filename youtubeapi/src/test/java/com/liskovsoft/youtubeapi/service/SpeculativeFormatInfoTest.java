package com.liskovsoft.youtubeapi.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;
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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Converter;

/**
 * NEWTUBE(walk-role): what the format caches do with a preload's answer when the user then opens
 * the same video (Codex review of the walk roles, findings 1 and 3).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {SpeculativeFormatInfoTest.ShadowEngine.class, SpeculativeFormatInfoTest.ShadowSignIn.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SpeculativeFormatInfoTest {
    private YouTubeMediaItemService service;

    @Implements(VideoInfoService.class)
    public static class ShadowEngine {
        static VideoInfo answer;
        static final List<String> walks = new ArrayList<>();
        static final List<String> adopted = new ArrayList<>();
        private static VideoInfoService sEngine;

        @Implementation
        protected void __constructor__() {
        }

        @Implementation
        protected static VideoInfoService instance() {
            if (sEngine == null) {
                sEngine = ReflectionHelpers.callConstructor(VideoInfoService.class);
            }
            return sEngine;
        }

        @Implementation
        protected static String currentNetworkKey() {
            return "wifi:100";
        }

        @Implementation
        protected VideoInfo getVideoInfo(String videoId, String clickTrackingParams,
                VideoInfoService.CancellationSignal cancellationSignal, VideoInfoService.WalkRole role) {
            walks.add(videoId + ":" + role);
            return answer;
        }

        @Implementation
        protected void adoptSpeculativeResult(String videoId, boolean unplayable, boolean live) {
            adopted.add(videoId + ":" + (unplayable ? "unplayable" : "playable"));
        }
    }

    @Implements(YouTubeSignInService.class)
    public static class ShadowSignIn {
        @Implementation
        protected void __constructor__() {
        }

        @Implementation
        protected void checkAuth() {
        }
    }

    @Before
    public void setUp() {
        ShadowEngine.walks.clear();
        ShadowEngine.adopted.clear();
        YouTubeMediaItemService.setSingleFlightEnabled(true);
        service = ReflectionHelpers.callConstructor(YouTubeMediaItemService.class);
    }

    @After
    public void tearDown() {
        YouTubeMediaItemService.setSingleFlightEnabled(false);
    }

    /** The preload may have been answered from the bot-check cooldown; the open may be the probe. */
    @Test
    public void aPreloadsBotCheckAnswerIsNotReusedByTheOpen() {
        ShadowEngine.answer = parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                + " \"reason\": \"Sign in to confirm you're not a bot\"}}");
        ShadowEngine.answer.setBotCheckRequired(true);
        service.getSpeculativeFormatInfo("walled");
        service.getFormatInfo("walled");
        assertEquals("[walled:SPECULATIVE, walled:ACTIVE]", ShadowEngine.walks.toString());
    }

    @Test
    public void anOpenThatReusesAPreloadAdoptsItOnce() {
        ShadowEngine.answer = parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\","
                + " \"reason\": \"This video is not available\"}}");
        service.getSpeculativeFormatInfo("kids");
        assertTrue(ShadowEngine.adopted.isEmpty());

        service.getFormatInfo("kids");
        service.getFormatInfo("kids");
        assertEquals("the open reused the preload's answer", "[kids:SPECULATIVE]", ShadowEngine.walks.toString());
        assertEquals("[kids:unplayable]", ShadowEngine.adopted.toString());
    }

    @Test
    public void anOpensOwnAnswerIsNotAdopted() {
        ShadowEngine.answer = parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\","
                + " \"reason\": \"This video is not available\"}}");
        service.getFormatInfo("kids");
        service.getFormatInfo("kids");
        assertTrue(ShadowEngine.adopted.isEmpty());
    }

    private static VideoInfo parse(String json) {
        try {
            Converter<ResponseBody, ?> converter = JsonPathConverterFactory.create()
                    .responseBodyConverter(VideoInfo.class, new Annotation[0], null);
            return (VideoInfo) converter.convert(ResponseBody.create(MediaType.get("application/json"), json));
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
