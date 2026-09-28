package com.liskovsoft.youtubeapi.videoinfo.models;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo;

import org.junit.After;
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
 * NEWTUBE(delivery): the shape WEB_EMBED answered wGltuo1B1sM with on the Pixel (2026-09-28): OK,
 * adaptive formats without URLs (SABR-only), one progressive stream and an HLS manifest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VodDeliveryTest {
    private static final String SABR_ONLY_WITH_HLS = """
            {"playabilityStatus": {"status": "OK"},
             "videoDetails": {"videoId": "wGltuo1B1sM", "lengthSeconds": "300", "isLiveContent": %s},
             "streamingData": {
               "formats": [{"itag": 18, "mimeType": "video/mp4", "url": "https://media.invalid/progressive"}],
               "adaptiveFormats": [{"itag": 137, "mimeType": "video/mp4"}, {"itag": 140, "mimeType": "audio/mp4"}],
               "hlsManifestUrl": "https://media.invalid/playlist.m3u8",
               "serverAbrStreamingUrl": "https://media.invalid/sabr"}}
            """;

    @After
    public void tearDown() {
        VodDelivery.setHlsEnabled(false);
    }

    @Test
    public void offTheAnswerIsUnplayableForTheWalkAsBefore() throws IOException {
        VideoInfo info = answer(AppClient.WEB_EMBED, false);
        assertTrue(info.isAdaptiveFormatsBroken());
        assertTrue(info.isUnplayable());
        assertFalse(format(info).isHlsVodSelected());
    }

    @Test
    public void onASourceMeasuredToServeHlsPlaysItOverHls() throws IOException {
        VodDelivery.setHlsEnabled(true);
        VideoInfo info = answer(AppClient.WEB_EMBED, false);
        assertFalse(info.isUnplayable());
        MediaItemFormatInfo format = format(info);
        assertFalse(format.isUnplayable());
        assertFalse(format.containsDashFormats());
        assertTrue(format.isHlsVodSelected());
    }

    @Test
    public void aSourceWithoutHlsEvidenceIsNotAccepted() throws IOException {
        VodDelivery.setHlsEnabled(true);
        VideoInfo info = answer(AppClient.WEB_SAFARI, false);
        assertTrue(info.isUnplayable());
        assertFalse(format(info).isHlsVodSelected());
    }

    @Test
    public void liveKeepsItsOwnRoute() throws IOException {
        VodDelivery.setHlsEnabled(true);
        VideoInfo info = answer(AppClient.WEB_EMBED, true);
        assertFalse(VodDelivery.acceptsHls(info));
        assertFalse(format(info).isHlsVodSelected());
    }

    @Test
    public void usableAdaptiveFormatsAreNeverDisplaced() throws IOException {
        VodDelivery.setHlsEnabled(true);
        VideoInfo info = parse("""
                {"playabilityStatus": {"status": "OK"},
                 "videoDetails": {"videoId": "_WB5hh7WOb4", "lengthSeconds": "132"},
                 "streamingData": {
                   "adaptiveFormats": [{"itag": 137, "mimeType": "video/mp4", "url": "https://media.invalid/v",
                     "initRange": {"start": "0", "end": "100"}, "indexRange": {"start": "101", "end": "200"}},
                     {"itag": 140, "mimeType": "audio/mp4", "url": "https://media.invalid/a",
                     "initRange": {"start": "0", "end": "100"}, "indexRange": {"start": "101", "end": "200"}}],
                   "hlsManifestUrl": "https://media.invalid/playlist.m3u8"}}
                """);
        info.setClient(AppClient.WEB_EMBED);
        assertFalse(info.isUnplayable());
        assertFalse(format(info).isHlsVodSelected());
    }

    private static VideoInfo answer(AppClient client, boolean live) throws IOException {
        VideoInfo info = parse(String.format(SABR_ONLY_WITH_HLS, live));
        info.setClient(client);
        return info;
    }

    private static MediaItemFormatInfo format(VideoInfo info) {
        return YouTubeMediaItemFormatInfo.from(info);
    }

    private static VideoInfo parse(String json) throws IOException {
        Converter<ResponseBody, ?> converter = JsonPathConverterFactory.create()
                .responseBodyConverter(VideoInfo.class, new Annotation[0], null);
        VideoInfo info = (VideoInfo) converter.convert(
                ResponseBody.create(MediaType.get("application/json"), json));
        assertNotNull(info);
        return info;
    }
}
