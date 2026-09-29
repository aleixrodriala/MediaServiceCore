package com.liskovsoft.youtubeapi.videoinfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;
import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoBotWallTest;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import kotlin.Pair;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Converter;

/**
 * NEWTUBE(player-js-gate): the format transform asks for the signature/n solve (and so waits for a
 * player being validated) only when an answer has something to solve; TV asks every time, as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class, shadows = {
        SolveWithoutChallengesTest.ShadowAppService.class, VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SolveWithoutChallengesTest {
    private static final String PLAIN = "https://rr1---sn-a.googlevideo.com/videoplayback?itag=251&mime=audio%2Fwebm";
    private Base base;

    static final class Base extends VideoInfoServiceBase {
    }

    @Before
    public void setUp() {
        ShadowAppService.service = ReflectionHelpers.callConstructor(AppService.class);
        ShadowAppService.solves = 0;
        base = new Base();
    }

    @After
    public void tearDown() {
        VideoInfoServiceBase.setSkipSolveWithoutChallenges(false);
    }

    @Test
    public void hasChallengeLooksAtEveryHolder() {
        assertFalse(VideoInfoServiceBase.hasChallenge(Arrays.asList(null, null), Arrays.asList(null, null)));
        assertFalse(VideoInfoServiceBase.hasChallenge(Collections.<String>emptyList(), Collections.<String>emptyList()));
        assertTrue(VideoInfoServiceBase.hasChallenge(Arrays.asList(null, "nnnn"), Arrays.asList(null, null)));
        assertTrue(VideoInfoServiceBase.hasChallenge(Arrays.asList(null, null), Arrays.asList("ssss", null)));
    }

    @Test
    public void anAnswerWithNothingToSolveAsksNothingOnThePhone() {
        VideoInfoServiceBase.setSkipSolveWithoutChallenges(true);
        decipher(answer(PLAIN));
        assertEquals(0, ShadowAppService.solves);
    }

    @Test
    public void anAnswerWithAnNParameterStillAsks() {
        VideoInfoServiceBase.setSkipSolveWithoutChallenges(true);
        decipher(answer(PLAIN + "&n=abcdefghijklmnop"));
        assertEquals(1, ShadowAppService.solves);
    }

    @Test
    public void anAnswerWithASignatureStillAsks() {
        VideoInfoServiceBase.setSkipSolveWithoutChallenges(true);
        decipher(answerWithCipher("s=AOq0QJ8wRQIhAKfake&sp=sig&url=" + PLAIN.replace("&", "%26")));
        assertEquals(1, ShadowAppService.solves);
    }

    @Test
    public void tvAndTheRollbackAskForEveryAnswer() {
        decipher(answer(PLAIN));
        assertEquals(1, ShadowAppService.solves);
    }

    private void decipher(VideoInfo info) {
        ReflectionHelpers.callInstanceMethod(VideoInfoServiceBase.class, base, "decipherFormats",
                ClassParameter.from(VideoInfo.class, info));
    }

    private static VideoInfo answer(String url) {
        return parse("{\"playabilityStatus\": {\"status\": \"OK\"}, \"videoDetails\": {\"videoId\": \"gate-vid-01\"},"
                + " \"streamingData\": {\"adaptiveFormats\": [{\"itag\": 251, \"url\": \"" + url + "\","
                + " \"mimeType\": \"audio/webm; codecs=\\\"opus\\\"\"}]}}");
    }

    private static VideoInfo answerWithCipher(String cipher) {
        return parse("{\"playabilityStatus\": {\"status\": \"OK\"}, \"videoDetails\": {\"videoId\": \"gate-vid-01\"},"
                + " \"streamingData\": {\"adaptiveFormats\": [{\"itag\": 251, \"signatureCipher\": \"" + cipher + "\","
                + " \"mimeType\": \"audio/webm; codecs=\\\"opus\\\"\"}]}}");
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

    @Implements(AppService.class)
    public static class ShadowAppService {
        static AppService service;
        static int solves;

        @Implementation
        protected void __constructor__() {
        }

        @Implementation
        protected static AppService instance() {
            return service;
        }

        /** The real one builds (and, for a new player, validates) the extractor first. */
        @Implementation
        protected Pair<List<String>, List<String>> bulkSigExtract(List<String> nParams, List<String> sParams) {
            solves++;
            return new Pair<>(null, null);
        }

        @Implementation
        protected boolean isPlayerJsValidationPending() {
            return true;
        }
    }
}
