package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;

import java.io.IOException;
import java.lang.annotation.Annotation;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Converter;

/**
 * Offline: which authenticated /player responses count as "this route cannot serve media in this
 * build". Synthetic URLs; nothing is requested or deciphered.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class AuthRouteSabrOnlyVerdictTest {
    private static final String SIGNED_IN = """
            "responseContext": {"serviceTrackingParams": [{"service": "GFEEDBACK",
              "params": [{"key": "logged_in", "value": "1"}]}]}
            """;
    /** Every adaptive format broken: no url and no signatureCipher, which is how SABR arrives. */
    private static final String BROKEN_ADAPTIVE = """
            {"itag": 251, "mimeType": "audio/webm"}, {"itag": 248, "mimeType": "video/webm"}
            """;

    @Test
    public void aSabrOnlyAnswerFromTheAccountHeadIsANoMediaVerdict() throws IOException {
        VideoInfo info = parse("""
                {"playabilityStatus": {"status": "OK"}, %s,
                 "streamingData": {"adaptiveFormats": [%s],
                   "serverAbrStreamingUrl": "https://media.invalid/sabr"}}
                """.formatted(SIGNED_IN, BROKEN_ADAPTIVE), true);

        assertTrue(VideoInfoService.isAuthRouteSabrOnlyVerdict(AppClient.TV, info));
        assertTrue(VideoInfoService.isAuthRouteSabrOnlyVerdict(AppClient.TV_DOWNGRADED, info));
    }

    @Test
    public void aManifestAlongsideSabrIsNotAVerdict() throws IOException {
        String[] playable = {
                "\"dashManifestUrl\": \"https://media.invalid/manifest.mpd\"",
                "\"hlsManifestUrl\": \"https://media.invalid/playlist.m3u8\"",
        };
        for (String delivery : playable) {
            VideoInfo info = parse("""
                    {"playabilityStatus": {"status": "OK"}, %s,
                     "streamingData": {"adaptiveFormats": [%s], %s,
                       "serverAbrStreamingUrl": "https://media.invalid/sabr"}}
                    """.formatted(SIGNED_IN, BROKEN_ADAPTIVE, delivery), true);

            assertFalse(delivery, VideoInfoService.isAuthRouteSabrOnlyVerdict(AppClient.TV, info));
        }
    }

    @Test
    public void usableAdaptiveFormatsAreNotAVerdictEvenWithASabrUrl() throws IOException {
        VideoInfo info = parse("""
                {"playabilityStatus": {"status": "OK"}, %s,
                 "streamingData": {"adaptiveFormats": [{"itag": 251, "mimeType": "audio/webm",
                     "url": "https://media.invalid/audio"}],
                   "serverAbrStreamingUrl": "https://media.invalid/sabr"}}
                """.formatted(SIGNED_IN), true);

        assertFalse(VideoInfoService.isAuthRouteSabrOnlyVerdict(AppClient.TV, info));
    }

    @Test
    public void theRuleIsAboutTheACCOUNTRoute() throws IOException {
        VideoInfo signedIn = parse("""
                {"playabilityStatus": {"status": "OK"}, %s,
                 "streamingData": {"adaptiveFormats": [%s],
                   "serverAbrStreamingUrl": "https://media.invalid/sabr"}}
                """.formatted(SIGNED_IN, BROKEN_ADAPTIVE), true);
        VideoInfo anonymous = parse("""
                {"playabilityStatus": {"status": "OK"},
                 "streamingData": {"adaptiveFormats": [%s],
                   "serverAbrStreamingUrl": "https://media.invalid/sabr"}}
                """.formatted(BROKEN_ADAPTIVE), false);

        // Not an account-bearing client...
        assertFalse(VideoInfoService.isAuthRouteSabrOnlyVerdict(AppClient.VISIONOS, signedIn));
        // ...and not an authenticated response.
        assertFalse(VideoInfoService.isAuthRouteSabrOnlyVerdict(AppClient.TV, anonymous));
        assertFalse(VideoInfoService.isAuthRouteSabrOnlyVerdict(AppClient.TV, null));
    }

    /**
     * The shape actually observed from TVHTML5 on 2026-09-07: 22 broken adaptive formats, ONE
     * progressive format and a SABR url. The walk rejects it (usableAdaptive=0, playable=n), so
     * the lone progressive entry does not make the route usable.
     */
    @Test
    public void oneProgressiveFormatBesideBrokenAdaptiveIsStillAVerdict() throws IOException {
        VideoInfo info = parse("""
                {"playabilityStatus": {"status": "OK"}, %s,
                 "streamingData": {"adaptiveFormats": [%s],
                   "formats": [{"itag": 18, "mimeType": "video/mp4"}],
                   "serverAbrStreamingUrl": "https://media.invalid/sabr"}}
                """.formatted(SIGNED_IN, BROKEN_ADAPTIVE), true);

        assertTrue(VideoInfoService.isAuthRouteSabrOnlyVerdict(AppClient.TV, info));
    }

    /** A gated video must never be read as evidence about the route. */
    @Test
    public void aRestrictedVideoIsNotARouteVerdict() throws IOException {
        VideoInfo info = parse("""
                {"playabilityStatus": {"status": "UNPLAYABLE",
                   "reason": "Sign in to confirm your age"}, %s,
                 "streamingData": {"adaptiveFormats": [%s],
                   "serverAbrStreamingUrl": "https://media.invalid/sabr"}}
                """.formatted(SIGNED_IN, BROKEN_ADAPTIVE), true);

        assertFalse(VideoInfoService.isAuthRouteSabrOnlyVerdict(AppClient.TV, info));
    }

    /** @param auth what the REQUEST carried, exactly as VideoInfoService stamps it. */
    private static VideoInfo parse(String json, boolean auth) throws IOException {
        Converter<ResponseBody, ?> converter = JsonPathConverterFactory.create()
                .responseBodyConverter(VideoInfo.class, new Annotation[0], null);
        VideoInfo info = (VideoInfo) converter.convert(
                ResponseBody.create(MediaType.get("application/json"), json));
        info.setAuth(auth);
        return info;
    }
}
