package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.*;
import android.app.Application;
import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaFormat;
import com.liskovsoft.youtubeapi.videoinfo.models.SabrVodCapability;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import java.lang.annotation.Annotation;
import okhttp3.MediaType;
import okhttp3.ResponseBody;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class SabrVodCapabilityTest {
    private static final String JSON = """
        {"playabilityStatus":{"status":"OK"},
         "responseContext":{"serviceTrackingParams":[{"service":"GFEEDBACK","params":[{"key":"logged_in","value":"1"}]}]},
         "videoDetails":{"videoId":"Fo89b8zAIE4","lengthSeconds":"120","isLive":false,"isLiveContent":false},
         "playerConfig":{"mediaCommonConfig":{"mediaUstreamerRequestConfig":{"videoPlaybackUstreamerConfig":"AA"}}},
         "streamingData":{"serverAbrStreamingUrl":"https://fixture.googlevideo.com/videoplayback",
         "adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4; codecs=\\"mp4a.40.2\\"","lastModified":"123456",
         "xtags":"lang=en","audioTrack":{"id":"en.1"}},
         {"itag":136,"mimeType":"video/mp4; codecs=\\"avc1.4d401f\\"","lastModified":"123457"}]}}
        """;
    @After public void restore() { SabrVodCapability.setEnabled(false); }
    @Test public void defaultDoesNotChangeClassification() throws Exception {
        VideoInfo info = parse(JSON);
        assertTrue(SabrVodCapability.isEligible(info));
        assertFalse(SabrVodCapability.accepts(info));
        assertTrue(info.isUnplayable());
    }
    @Test public void explicitCapabilityAcceptsAnAnonymousResponseFromADeliveringClient() throws Exception {
        SabrVodCapability.setEnabled(true);
        VideoInfo info = parse(JSON);
        assertFalse(info.isAuth());
        assertTrue(SabrVodCapability.accepts(info));
        assertFalse(info.isUnplayable());
    }
    /**
     * The TV family is excluded because its SABR media is dead, not because of the account: every
     * TVHTML5 SABR POST answers HTTP 403 with an empty body, reproduced off-device on 2026-09-08
     * from a different network and an anonymous identity while VISIONOS served the same request.
     * Gating on the account instead selected exactly this failing route and nothing else.
     */
    @Test public void theTvRouteIsNotAcceptedEvenWhenAuthenticatedAndServerLoggedIn() throws Exception {
        SabrVodCapability.setEnabled(true);
        VideoInfo info = parse(JSON);
        info.setClient(AppClient.TV);
        info.setAuth(true);
        assertEquals(Boolean.TRUE, info.isServerLoggedIn());
        assertFalse(SabrVodCapability.accepts(info));
        assertTrue(info.isUnplayable());
    }
    /** ...so the existing TV quarantine keeps firing on a SABR-only authenticated TV answer. */
    @Test public void theTvSabrOnlyQuarantineVerdictIsUnchanged() throws Exception {
        SabrVodCapability.setEnabled(true);
        VideoInfo info = parse(JSON);
        info.setClient(AppClient.TV);
        info.setAuth(true);
        assertTrue(info.isAdaptiveFormatsBroken());
        assertTrue(VideoInfoService.isAuthRouteSabrOnlyVerdict(AppClient.TV, info));
    }
    @Test public void everyAcceptedClientIsOneMeasuredToServeSabrMedia() throws Exception {
        SabrVodCapability.setEnabled(true);
        for (AppClient client : new AppClient[]{AppClient.VISIONOS, AppClient.IOS,
                AppClient.ANDROID, AppClient.ANDROID_VR}) {
            VideoInfo info = parse(JSON);
            info.setClient(client);
            assertTrue(client.name(), SabrVodCapability.accepts(info));
        }
        for (AppClient client : new AppClient[]{AppClient.TV, AppClient.TV_DOWNGRADED,
                AppClient.TV_SIMPLY, AppClient.TV_EMBED, AppClient.WEB, AppClient.WEB_EMBED,
                AppClient.MWEB}) {
            VideoInfo info = parse(JSON);
            info.setClient(client);
            assertFalse(client.name(), SabrVodCapability.accepts(info));
        }
    }
    @Test public void playabilityDenialsCannotBeOverridden() throws Exception {
        SabrVodCapability.setEnabled(true);
        for (String status : new String[]{"UNPLAYABLE", "LOGIN_REQUIRED", "ERROR", "CONTENT_CHECK_REQUIRED"}) {
            assertFalse(status, SabrVodCapability.accepts(parse(JSON.replace("\"OK\"", "\"" + status + "\""))));
        }
    }
    /**
     * Deliberately the opposite of the original gate. The phone's ring is served anonymously (the
     * account route is quarantined), so requiring a signed-in response would leave SABR reachable
     * only through the TV client, whose media returns 403.
     */
    @Test public void serverAuthenticationIsNotRequired() throws Exception {
        SabrVodCapability.setEnabled(true);
        assertTrue(SabrVodCapability.accepts(parse(JSON.replace("\"value\":\"1\"", "\"value\":\"0\""))));
        assertTrue(SabrVodCapability.accepts(parse(JSON.replace("logged_in", "unknown"))));
    }
    @Test public void noWebClientIsActivated() throws Exception {
        SabrVodCapability.setEnabled(true);
        VideoInfo info = parse(JSON);
        info.setClient(AppClient.WEB);
        assertFalse(SabrVodCapability.accepts(info));
    }
    @Test public void liveAndPostLiveRemainOnTheirExistingPaths() throws Exception {
        SabrVodCapability.setEnabled(true);
        assertFalse(SabrVodCapability.accepts(parse(JSON.replace("\"isLive\":false", "\"isLive\":true"))));
        assertFalse(SabrVodCapability.accepts(parse(JSON.replace("\"isLiveContent\":false", "\"isLiveContent\":true"))));
    }
    @Test public void missingConfigurationOrDurationDoesNotClaimSupport() throws Exception {
        SabrVodCapability.setEnabled(true);
        assertFalse(SabrVodCapability.accepts(parse(JSON.replace("videoPlaybackUstreamerConfig", "unknown"))));
        assertFalse(SabrVodCapability.accepts(parse(JSON.replace("lengthSeconds", "unknown"))));
    }
    @Test public void formatIdentitySurvivesTheExistingDtoAdapter() throws Exception {
        com.liskovsoft.mediaserviceinterfaces.data.MediaFormat format =
                YouTubeMediaFormat.from(parse(JSON).getAdaptiveFormats().get(0));
        assertEquals("lang=en", format.getXtags());
        assertEquals("en.1", format.getAudioTrackId());
        assertEquals("123456", format.getLmt());
    }
    @Test public void incompleteAvOrEmptyConfigurationDoesNotClaimSupport() throws Exception {
        SabrVodCapability.setEnabled(true);
        assertFalse(SabrVodCapability.accepts(parse(JSON.replace("video/mp4;", "unknown/mp4;"))));
        assertFalse(SabrVodCapability.accepts(parse(JSON.replace("audio/mp4;", "unknown/mp4;"))));
        assertFalse(SabrVodCapability.accepts(parse(JSON.replace("\"AA\"", "\"\""))));
        assertFalse(SabrVodCapability.accepts(parse(JSON.replace("\"123457\"", "\"0\""))));
    }
    /**
     * The decoder is useless if the response never reaches it. A link-less answer is "broken" to
     * {@code containsAdaptiveVideoInfo()}, which left the player DTO with no adaptive formats at
     * all: {@code isUnplayable()} already said "playable" (it consults this capability) while
     * {@code containsSabrFormats()} said there was nothing to open, so the SABR route was
     * unreachable and playback simply stopped. Measured on the Pixel 9 on 2026-09-08 with the
     * client pinned to IOS: 23 formats, 0 with URLs, a SABR endpoint, and no source ever built.
     */
    @Test public void anAcceptedLinklessResponseReachesThePlayerAsSabrFormats() throws Exception {
        SabrVodCapability.setEnabled(true);
        VideoInfo info = parse(JSON);
        assertTrue(info.isAdaptiveFormatsBroken());
        assertFalse(info.containsAdaptiveVideoInfo());

        com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo formatInfo =
                com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo.from(info);
        assertTrue(formatInfo.containsSabrFormats());
        assertFalse(formatInfo.containsDashFormats());
        assertTrue(formatInfo.isSabrVodEligible());
        assertFalse(formatInfo.isUnplayable());
        assertEquals(2, formatInfo.getAdaptiveFormats().size());
    }

    /** With the capability off nothing about the existing classification moves. */
    @Test public void aLinklessResponseStaysUnroutedWhileTheCapabilityIsOff() throws Exception {
        com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo formatInfo =
                com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo.from(parse(JSON));
        assertFalse(formatInfo.containsSabrFormats());
        assertFalse(formatInfo.containsDashFormats());
        assertTrue(formatInfo.isUnplayable());
    }

    /** A TV answer is accepted by neither gate, so it cannot be routed into a 403. */
    @Test public void aLinklessTvResponseIsStillNotRoutedToSabr() throws Exception {
        SabrVodCapability.setEnabled(true);
        VideoInfo info = parse(JSON);
        info.setClient(AppClient.TV);
        info.setAuth(true);
        com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo formatInfo =
                com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemFormatInfo.from(info);
        assertFalse(formatInfo.containsSabrFormats());
        assertTrue(formatInfo.isUnplayable());
    }

    private static VideoInfo parse(String json) throws Exception {
        VideoInfo result = (VideoInfo) JsonPathConverterFactory.create()
                .responseBodyConverter(VideoInfo.class, new Annotation[0], null)
                .convert(ResponseBody.create(MediaType.get("application/json"), json));
        result.setClient(AppClient.VISIONOS);
        return result;
    }
}
