package com.liskovsoft.youtubeapi.videoinfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoBotWallTest;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VodDelivery;

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
import java.util.List;

import kotlin.Pair;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Converter;

/**
 * NEWTUBE(hls-vod-fold): an answer played over HLS for VOD has its manifest's "/n/" challenge solved
 * in the same bulk solve as the formats' ones (one V8 run instead of two); off, or when the bulk
 * answer does not carry it, the separate solve runs as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class, shadows = {
        HlsChallengeFoldTest.ShadowAppService.class, VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class HlsChallengeFoldTest {
    private static final String MANIFEST = "https://manifest.googlevideo.com/api/manifest/hls_variant"
            + "/expire/1759200000/id/760f8f1e2b/n/hlsChallenge/sn/sn-h5q7kne7/file/index.m3u8";
    private Base base;

    static final class Base extends VideoInfoServiceBase {
    }

    @Before
    public void setUp() {
        ShadowAppService.service = ReflectionHelpers.callConstructor(AppService.class);
        ShadowAppService.bulk.clear();
        ShadowAppService.single.clear();
        ShadowAppService.echoSize = true;
        VodDelivery.setHlsEnabled(true);
        base = new Base();
    }

    @After
    public void tearDown() {
        VideoInfoServiceBase.setFoldHlsChallenge(false);
        VodDelivery.setHlsEnabled(false);
    }

    @Test
    public void theManifestChallengeRidesTheBulkSolve() {
        VideoInfoServiceBase.setFoldHlsChallenge(true);
        VideoInfo info = sabrOnly();
        decipher(info);
        assertEquals(1, ShadowAppService.bulk.size());
        assertEquals("[null, sabrChallenge, hlsChallenge]", ShadowAppService.bulk.get(0).toString());
        assertTrue(ShadowAppService.single.toString(), ShadowAppService.single.isEmpty());
        assertTrue(info.getHlsManifestUrl(), info.getHlsManifestUrl().contains("/n/solved-hlsChallenge/"));
        // The holders got their own answers, not the manifest's.
        assertTrue(info.getServerAbrStreamingUrl(), info.getServerAbrStreamingUrl().contains("n=solved-sabrChallenge"));
    }

    /** Off (the rollback): the bulk solve as before, then the manifest's own. */
    @Test
    public void offTheManifestIsSolvedOnItsOwn() {
        VideoInfo info = sabrOnly();
        decipher(info);
        assertEquals("[null, sabrChallenge]", ShadowAppService.bulk.get(0).toString());
        assertEquals("[hlsChallenge]", ShadowAppService.single.toString());
        assertTrue(info.getHlsManifestUrl(), info.getHlsManifestUrl().contains("/n/solved-hlsChallenge/"));
    }

    /** A bulk answer of another shape: its holders as before, and the separate solve as a fallback. */
    @Test
    public void aBulkAnswerWithoutItFallsBack() {
        VideoInfoServiceBase.setFoldHlsChallenge(true);
        ShadowAppService.echoSize = false;
        VideoInfo info = sabrOnly();
        decipher(info);
        assertEquals("[hlsChallenge]", ShadowAppService.single.toString());
        assertTrue(info.getHlsManifestUrl(), info.getHlsManifestUrl().contains("/n/solved-hlsChallenge/"));
    }

    private void decipher(VideoInfo info) {
        ReflectionHelpers.callInstanceMethod(VideoInfoServiceBase.class, base, "decipherFormats",
                ClassParameter.from(VideoInfo.class, info));
    }

    private static VideoInfo sabrOnly() {
        try {
            Converter<ResponseBody, ?> converter = JsonPathConverterFactory.create()
                    .responseBodyConverter(VideoInfo.class, new Annotation[0], null);
            VideoInfo info = (VideoInfo) converter.convert(ResponseBody.create(MediaType.get("application/json"),
                    "{\"playabilityStatus\": {\"status\": \"OK\"}, \"videoDetails\": {\"videoId\": \"fold-vid-01\"},"
                            + " \"streamingData\": {\"adaptiveFormats\": [{\"itag\": 137, \"mimeType\": \"video/mp4\"}],"
                            + " \"hlsManifestUrl\": \"" + MANIFEST + "\","
                            + " \"serverAbrStreamingUrl\": \"https://rr1---sn-a.googlevideo.com/videoplayback?itag=0"
                            + "&n=sabrChallenge\"}}"));
            info.setClient(AppClient.WEB_EMBED);
            return info;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Implements(AppService.class)
    public static class ShadowAppService {
        static AppService service;
        static final List<List<String>> bulk = new ArrayList<>();
        static final List<String> single = new ArrayList<>();
        /** true: answers per entry, like the V8 bulk solve; false: one answer for all. */
        static boolean echoSize = true;

        @Implementation
        protected void __constructor__() {
        }

        @Implementation
        protected static AppService instance() {
            return service;
        }

        @Implementation
        protected Pair<List<String>, List<String>> bulkSigExtract(List<String> nParams, List<String> sParams) {
            bulk.add(new ArrayList<>(nParams));
            if (!echoSize) {
                return new Pair<>(null, null);
            }
            List<String> n = new ArrayList<>();
            for (String value : nParams) {
                n.add(value != null ? "solved-" + value : null);
            }
            List<String> s = new ArrayList<>();
            for (String value : sParams) {
                s.add(null);
            }
            return new Pair<>(n, s);
        }

        @Implementation
        protected String extractNSig(String nParam) {
            single.add(nParam);
            return "solved-" + nParam;
        }

        @Implementation
        protected boolean isPlayerJsValidationPending() {
            return false;
        }
    }
}
