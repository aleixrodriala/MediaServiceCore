package com.liskovsoft.youtubeapi.videoinfo.models;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;

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

/** Offline response parsing only: synthetic URLs are never requested or deciphered. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoParsingTest {
    private static final String MEDIA_FIELDS = """
            "streamingData": {
              "formats": [{"itag": 18, "mimeType": "video/mp4",
                "url": "https://media.invalid/progressive"}],
              "adaptiveFormats": [{"itag": 137, "mimeType": "video/mp4",
                "url": "https://media.invalid/video",
                "initRange": {"start": "0", "end": "100"},
                "indexRange": {"start": "101", "end": "200"}},
                {"itag": 140, "mimeType": "audio/mp4",
                "url": "https://media.invalid/audio"}],
              "dashManifestUrl": "https://media.invalid/manifest.mpd",
              "hlsManifestUrl": "https://media.invalid/playlist.m3u8",
              "serverAbrStreamingUrl": "https://media.invalid/sabr"
            }
            """;

    @Test
    public void authenticatedReloadVerdictRemainsDeniedWithoutMedia() throws IOException {
        VideoInfo info = parse("""
                {
                  "playabilityStatus": {"status": "UNPLAYABLE",
                    "reason": "Es necesario volver a cargar la página."},
                  "responseContext": {"serviceTrackingParams": [{"service": "GFEEDBACK",
                    "params": [{"key": "logged_in", "value": "1"}]}]}
                }
                """);

        assertEquals("UNPLAYABLE", info.getRawPlayabilityStatus());
        assertTrue(info.isUnplayable());
        assertTrue(info.isUnknownRestricted());
        assertEquals(Boolean.TRUE, info.isServerLoggedIn());
        assertTrue(info.getPlayabilityStatus().contains("volver a cargar"));
        assertNoMediaFields(info);
    }

    @Test
    public void explicitLoginVerdictRemainsDeniedWithoutMedia() throws IOException {
        VideoInfo info = parse("""
                {
                  "playabilityStatus": {"status": "LOGIN_REQUIRED",
                    "reason": "Sign in to confirm you're not a bot"},
                  "responseContext": {"serviceTrackingParams": [{"service": "GFEEDBACK",
                    "params": [{"key": "logged_in", "value": "0"}]}]}
                }
                """);

        assertEquals("LOGIN_REQUIRED", info.getRawPlayabilityStatus());
        assertTrue(info.isLoginRequired());
        assertTrue(info.isUnplayable());
        assertTrue(info.isBotCheckRequired());
        assertEquals(Boolean.FALSE, info.isServerLoggedIn());
        assertNoMediaFields(info);
    }

    @Test
    public void emptyMediaArraysDoNotEraseDenial() throws IOException {
        VideoInfo info = parse("""
                {"playabilityStatus": {"status": "LOGIN_REQUIRED"},
                 "streamingData": {"formats": [], "adaptiveFormats": []}}
                """);

        assertTrue(info.isUnplayable());
        assertNoMediaFields(info);
    }

    @Test
    public void ordinaryOkResponseRetainsFormatsAndEveryManifestField() throws IOException {
        VideoInfo info = parse("{\"playabilityStatus\":{\"status\":\"OK\"}," + MEDIA_FIELDS + "}");

        assertEquals("OK", info.getRawPlayabilityStatus());
        assertFalse(info.isUnplayable());
        assertTrue(info.containsRegularVideoInfo());
        assertTrue(info.containsAdaptiveVideoInfo());
        assertEquals(1, info.getRegularFormats().size());
        assertEquals(2, info.getAdaptiveFormats().size());
        assertEquals("https://media.invalid/progressive", info.getRegularFormats().get(0).getUrl());
        assertEquals("https://media.invalid/video", info.getAdaptiveFormats().get(0).getUrl());
        assertEquals("0-100", info.getAdaptiveFormats().get(0).getInit());
        assertEquals("101-200", info.getAdaptiveFormats().get(0).getIndex());
        assertFalse(info.isAdaptiveFormatsBroken());
        assertEquals("https://media.invalid/manifest.mpd", info.getDashManifestUrl());
        assertEquals("https://media.invalid/playlist.m3u8", info.getHlsManifestUrl());
        assertEquals("https://media.invalid/sabr", info.getServerAbrStreamingUrl());
    }

    @Test
    public void manifestOnlyOkResponseDoesNotRequireAdaptiveFormats() throws IOException {
        for (String field : new String[]{"dashManifestUrl", "hlsManifestUrl"}) {
            VideoInfo info = parse("{\"playabilityStatus\":{\"status\":\"OK\"},"
                    + "\"streamingData\":{\"" + field + "\":\"https://media.invalid/manifest\"}}");

            assertFalse(field, info.isUnplayable());
            assertNull(info.getAdaptiveFormats());
            assertEquals("https://media.invalid/manifest", "dashManifestUrl".equals(field)
                    ? info.getDashManifestUrl() : info.getHlsManifestUrl());
        }
    }

    @Test
    public void denialStatusIsNotClearedByStrayFormatsOrManifests() throws IOException {
        for (String status : new String[]{"UNPLAYABLE", "LOGIN_REQUIRED", "ERROR"}) {
            VideoInfo info = parse("{\"playabilityStatus\":{\"status\":\"" + status + "\"},"
                    + MEDIA_FIELDS + "}");

            assertEquals(status, info.getRawPlayabilityStatus());
            assertTrue(status, info.isUnplayable());
            assertEquals(1, info.getRegularFormats().size());
            assertEquals(2, info.getAdaptiveFormats().size());
            assertNotNull(info.getDashManifestUrl());
            assertNotNull(info.getHlsManifestUrl());
            assertNotNull(info.getServerAbrStreamingUrl());
        }
    }

    @Test
    public void sabrOnlyResponseIsVisibleToDiagnosticsRatherThanParsedAsEmpty() throws IOException {
        VideoInfo info = parse("""
                {"playabilityStatus": {"status": "OK"},
                 "streamingData": {
                   "adaptiveFormats": [{"itag": 137, "mimeType": "video/mp4"}],
                   "serverAbrStreamingUrl": "https://media.invalid/sabr"},
                 "playerConfig": {"mediaCommonConfig": {"mediaUstreamerRequestConfig": {
                   "videoPlaybackUstreamerConfig": "synthetic-config"}}}}
                """);

        assertEquals("OK", info.getRawPlayabilityStatus());
        assertEquals(1, info.getAdaptiveFormats().size());
        assertEquals("https://media.invalid/sabr", info.getServerAbrStreamingUrl());
        assertEquals("synthetic-config", info.getVideoPlaybackUstreamerConfig());
        // Existing SABR support policy is separate from whether the JSON fields were parsed.
        assertTrue(info.isAdaptiveFormatsBroken());
    }

    private static VideoInfo parse(String json) throws IOException {
        Converter<ResponseBody, ?> converter = JsonPathConverterFactory.create()
                .responseBodyConverter(VideoInfo.class, new Annotation[0], null);
        VideoInfo info = (VideoInfo) converter.convert(
                ResponseBody.create(MediaType.get("application/json"), json));
        assertNotNull(info);
        return info;
    }

    private static void assertNoMediaFields(VideoInfo info) {
        assertNull(info.getRegularFormats());
        assertNull(info.getAdaptiveFormats());
        assertNull(info.getDashManifestUrl());
        assertNull(info.getHlsManifestUrl());
        assertNull(info.getServerAbrStreamingUrl());
    }
}
