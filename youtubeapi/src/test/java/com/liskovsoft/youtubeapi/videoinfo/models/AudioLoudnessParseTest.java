package com.liskovsoft.youtubeapi.videoinfo.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Application;

import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Converter;

/**
 * NEWTUBE(loudness): the loudness the phone normalizes with (issue #15) must survive parsing on the
 * answers it actually plays: VISIONOS omits audioConfig.loudnessDb (the legacy field reads 0 there,
 * which the old volume formula turned into a flat -6 dB), and its -14 target is an integral number.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AudioLoudnessParseTest {
    private static final float DELTA = 0.005f;

    @Test
    public void visionosTrackLoudnessComesFromPerceptualMinusTarget() throws Exception {
        MediaItemFormatInfo info = info("video_info/player_2026_09/visionos_dQw4w9WgXcQ.json");

        // perceptualLoudnessDb -13.01 against the -14 target.
        assertEquals(0.99f, info.getAudioLoudnessDb(), DELTA);
        assertEquals(0.99f, audio(info, "140", false).getLoudnessDb(), DELTA);
        assertEquals(0.98f, audio(info, "251", false).getLoudnessDb(), DELTA);
    }

    @Test
    public void stableVolumeVariantsCarryTheirOwnLoudness() throws Exception {
        MediaItemFormatInfo info = info("video_info/player_2026_09/visionos_gFM-BL_0YvI.json");

        assertEquals(0.4f, info.getAudioLoudnessDb(), DELTA);
        assertEquals(0.4f, audio(info, "251", false).getLoudnessDb(), DELTA);
        assertEquals(-1.91f, audio(info, "251", true).getLoudnessDb(), DELTA);
    }

    @Test
    public void anExplicitTrackLoudnessWins() throws Exception {
        // TVHTML5 sends loudnessDb (-2.22) with no target.
        assertEquals(-2.22f, info("video_info/player_2026_09/tvhtml5_Fo89b8zAIE4.json").getAudioLoudnessDb(), DELTA);
    }

    @Test
    public void integralValuesParseAndAbsentStaysNull() throws IOException {
        MediaItemFormatInfo integral = YouTubeMediaItemFormatInfo.from(parse("""
                {"playabilityStatus": {"status": "OK"},
                 "videoDetails": {"videoId": "aaaaaaaaaaa", "lengthSeconds": "60"},
                 "playerConfig": {"audioConfig": {"perceptualLoudnessDb": -11, "loudnessTargetLkfs": -14}},
                 "streamingData": {"adaptiveFormats": [{"itag": 140, "mimeType": "audio/mp4; codecs=\\"mp4a.40.2\\"",
                     "url": "https://media.invalid/140", "loudnessDb": 3}]}}
                """));
        assertEquals(3f, integral.getAudioLoudnessDb(), DELTA);
        assertEquals(3f, audio(integral, "140", false).getLoudnessDb(), DELTA);

        MediaItemFormatInfo absent = YouTubeMediaItemFormatInfo.from(parse("""
                {"playabilityStatus": {"status": "OK"},
                 "videoDetails": {"videoId": "aaaaaaaaaaa", "lengthSeconds": "60"},
                 "streamingData": {"adaptiveFormats": [{"itag": 140, "mimeType": "audio/mp4; codecs=\\"mp4a.40.2\\"",
                     "url": "https://media.invalid/140"}]}}
                """));
        assertNull(absent.getAudioLoudnessDb());
        assertNull(audio(absent, "140", false).getLoudnessDb());
    }

    private static MediaFormat audio(MediaItemFormatInfo info, String itag, boolean drc) {
        for (MediaFormat format : info.getAdaptiveFormats()) {
            if (itag.equals(format.getITag()) && format.isDrc() == drc) {
                return format;
            }
        }
        throw new AssertionError("no " + itag + (drc ? "-drc" : ""));
    }

    private MediaItemFormatInfo info(String fixture) throws Exception {
        return YouTubeMediaItemFormatInfo.from(parse(resource(fixture)));
    }

    private static VideoInfo parse(String json) throws IOException {
        Converter<ResponseBody, ?> converter = JsonPathConverterFactory.create()
                .responseBodyConverter(VideoInfo.class, new Annotation[0], null);
        VideoInfo info = (VideoInfo) converter.convert(
                ResponseBody.create(MediaType.get("application/json"), json));
        assertNotNull(info);
        return info;
    }

    private String resource(String name) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
