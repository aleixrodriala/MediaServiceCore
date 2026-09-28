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
    public void prerollWaitFollowsYtDlpsRule() throws IOException {
        // The shape of a real WEB_EMBED answer (_WB5hh7WOb4, 2026-09-28): one skippable pre-roll
        // (5 s skip, 26 s long) plus a slot that is not a pre-roll.
        VideoInfo skippable = parse("""
                {"playabilityStatus": {"status": "OK"}, "adSlots": [
                  {"adSlotRenderer": {"adSlotMetadata": {"triggerEvent": "SLOT_TRIGGER_EVENT_BEFORE_CONTENT"},
                    "fulfillmentContent": {"fulfilledLayout": {"playerBytesAdLayoutRenderer": {
                      "renderingContent": {"instreamVideoAdRenderer": {"skipOffsetMilliseconds": 5000,
                        "playerVars": "length_seconds=26&video_id=ad"}}}}}}},
                  {"adSlotRenderer": {"adSlotMetadata": {"triggerEvent": "SLOT_TRIGGER_EVENT_LAYOUT_ID_ENTERED"},
                    "fulfillmentContent": {"fulfilledLayout": {"playerBytesAdLayoutRenderer": {
                      "renderingContent": {"instreamVideoAdRenderer": {"skipOffsetMilliseconds": 9000}}}}}}}]}
                """);
        assertEquals(5_000, skippable.getPrerollWaitMs());

        // An unskippable ad counts its length; a sequential pair counts both; the older
        // adPlacements START shape counts too.
        VideoInfo mixed = parse("""
                {"playabilityStatus": {"status": "OK"}, "adSlots": [
                  {"adSlotRenderer": {"adSlotMetadata": {"triggerEvent": "SLOT_TRIGGER_EVENT_BEFORE_CONTENT"},
                    "fulfillmentContent": {"fulfilledLayout": {"playerBytesAdLayoutRenderer": {
                      "renderingContent": {"playerBytesSequentialLayoutRenderer": {"sequentialLayouts": [
                        {"playerBytesAdLayoutRenderer": {"renderingContent": {"instreamVideoAdRenderer":
                          {"playerVars": "length_seconds=15"}}}},
                        {"playerBytesAdLayoutRenderer": {"renderingContent": {"instreamVideoAdRenderer":
                          {"skipOffsetMilliseconds": 5000, "playerVars": "length_seconds=30"}}}}]}}}}}}}],
                  "adPlacements": [{"adPlacementRenderer": {"config": {"adPlacementConfig":
                    {"kind": "AD_PLACEMENT_KIND_START"}}, "renderer": {"instreamVideoAdRenderer":
                    {"skipOffsetMilliseconds": 5000}}}},
                    {"adPlacementRenderer": {"config": {"adPlacementConfig":
                    {"kind": "AD_PLACEMENT_KIND_MILLISECONDS"}}, "renderer": {"instreamVideoAdRenderer":
                    {"skipOffsetMilliseconds": 5000}}}}]}
                """);
        assertEquals(15_000 + 5_000 + 5_000, mixed.getPrerollWaitMs());

        // An ad whose wait cannot be read is not free.
        VideoInfo unknown = parse("""
                {"playabilityStatus": {"status": "OK"}, "adSlots": [
                  {"adSlotRenderer": {"adSlotMetadata": {"triggerEvent": "SLOT_TRIGGER_EVENT_BEFORE_CONTENT"},
                    "fulfillmentContent": {"fulfilledLayout": {"playerBytesAdLayoutRenderer": {
                      "renderingContent": {"instreamVideoAdRenderer": {"layoutId": "x"}}}}}}}]}
                """);
        assertEquals(PrerollAds.UNKNOWN_AD_WAIT_MS, unknown.getPrerollWaitMs());

        // Nor is a pre-content slot in a shape the parser does not map.
        VideoInfo unmapped = parse("""
                {"playabilityStatus": {"status": "OK"}, "adSlots": [
                  {"adSlotRenderer": {"adSlotMetadata": {"triggerEvent": "SLOT_TRIGGER_EVENT_BEFORE_CONTENT"},
                    "fulfillmentContent": {"fulfilledLayout": {"someNewAdLayoutRenderer": {"layoutId": "x"}}}}}]}
                """);
        assertEquals(PrerollAds.UNKNOWN_AD_WAIT_MS, unmapped.getPrerollWaitMs());

        assertEquals(0, parse("{\"playabilityStatus\": {\"status\": \"OK\"}}").getPrerollWaitMs());
    }

    @Test
    public void ageGateIsToldApartFromABotCheck() throws IOException {
        VideoInfo age = parse("""
                {"playabilityStatus": {"status": "LOGIN_REQUIRED",
                  "reason": "Sign in to confirm your age", "desktopLegacyAgeGateReason": 1}}
                """);
        VideoInfo bot = parse("""
                {"playabilityStatus": {"status": "LOGIN_REQUIRED",
                  "reason": "Sign in to confirm you\u2019re not a bot",
                  "skip": {"playabilityErrorSkipConfig": {"skipOnPlayabilityError": false}}}}
                """);

        assertTrue(age.isAgeGate());
        assertTrue(age.isAgeRestricted());
        assertFalse(age.isBotCheckRequired());
        assertFalse(bot.isAgeGate());
        assertTrue(bot.isBotCheckRequired());
    }

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
