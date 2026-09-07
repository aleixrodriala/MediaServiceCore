package com.liskovsoft.youtubeapi.common.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.google.gson.JsonParser;
import com.liskovsoft.youtubeapi.app.AppService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

/** Real request serialization with synthetic session fields; no HTTP or stored credentials. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = QueryBuilderTimestampTest.ShadowAppService.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class QueryBuilderTimestampTest {
    private static final String VISITOR = "offline-visitor";
    private static final String CPN = "offline-cpn";
    private static final String POT = "offline-pot";

    @Before
    public void setUp() {
        ShadowAppService.service = ReflectionHelpers.callConstructor(AppService.class);
        ShadowAppService.timestamp = "20697";
        ShadowAppService.timestampReads = 0;
        ShadowAppService.visitorReads = 0;
        ShadowAppService.cpnReads = 0;
    }

    @Test
    public void bothAuthenticatedTvClientsNormalizeAutomaticallyFetchedWebTimestamp() {
        for (AppClient client : new AppClient[] {AppClient.TV, AppClient.TV_DOWNGRADED}) {
            String actual = request(client).build();
            String expected = request(client).setSignatureTimestamp(20_697_001).build();

            assertEquals("only the automatic timestamp format changes", expected, actual);
            assertTimestamp(actual, 20_697_001);
            assertSessionFields(actual);
        }
        assertEquals(2, ShadowAppService.timestampReads);
        assertEquals("normalization must not rewrite the cached scalar", "20697",
                ShadowAppService.timestamp);
    }

    @Test
    public void alreadyTvTimestampIsNotSuffixedAgain() {
        ShadowAppService.timestamp = "20697001";

        String actual = request(AppClient.TV).build();

        assertEquals(request(AppClient.TV).setSignatureTimestamp(20_697_001).build(), actual);
        assertTimestamp(actual, 20_697_001);
        assertEquals("20697001", ShadowAppService.timestamp);
    }

    /**
     * Measured 2026-09-07, one video and one session per arm, only the timestamp differing:
     * TVHTML5_SIMPLY at the real five-digit value is served media URLs that answer HTTP 206, and
     * at the suffixed value is served 22 formats whose every URL 403s on its first byte range.
     * The suffix belongs to the Cobalt TVHTML5 client alone.
     */
    @Test
    public void tvSiblingsThatAreNotCobaltKeepThePlayerTimestamp() {
        // TV_EMBED is deliberately not compared whole: it is the only embedded client here, and
        // its encryptedHostFlags chunk comes from a per-video cache that another build can
        // populate between the two calls. The timestamp is what this test is about.
        for (AppClient client : new AppClient[] {
                AppClient.TV_SIMPLY, AppClient.TV_EMBED, AppClient.TV_KIDS}) {
            String actual = request(client).build();

            assertTimestamp(actual, 20_697);
            assertSessionFields(actual);
        }
        assertEquals(3, ShadowAppService.timestampReads);

        assertEquals("only TVHTML5 takes the TV timestamp form",
                request(AppClient.TV_SIMPLY).setSignatureTimestamp(20_697).build(),
                request(AppClient.TV_SIMPLY).build());
    }

    @Test
    public void everyCobaltTvClientStillNormalizes() {
        for (AppClient client : new AppClient[] {
                AppClient.TV, AppClient.TV_LEGACY, AppClient.TV_DOWNGRADED}) {
            assertTimestamp(request(client).build(), 20_697_001);
        }
        assertEquals(3, ShadowAppService.timestampReads);
    }

    @Test
    public void nonTvClientsKeepOriginalTimestamp() {
        for (AppClient client : new AppClient[] {
                AppClient.WEB, AppClient.VISIONOS, AppClient.ANDROID_VR, AppClient.IOS}) {
            String actual = request(client).build();

            assertEquals(request(client).setSignatureTimestamp(20_697).build(), actual);
            assertTimestamp(actual, 20_697);
            assertSessionFields(actual);
        }
        assertEquals(4, ShadowAppService.timestampReads);
    }

    @Test
    public void explicitTimestampOverrideKeepsItsExistingMeaning() {
        String actual = request(AppClient.TV_DOWNGRADED).setSignatureTimestamp(20_697).build();

        assertTimestamp(actual, 20_697);
        assertEquals("explicit overrides must not read or normalize the cached timestamp", 0,
                ShadowAppService.timestampReads);
        assertSessionFields(actual);
    }

    @Test
    public void negativeOneOverrideStillRequestsAutomaticTimestamp() {
        String actual = request(AppClient.TV).setSignatureTimestamp(-1).build();

        assertEquals(request(AppClient.TV).setSignatureTimestamp(20_697_001).build(), actual);
        assertTimestamp(actual, 20_697_001);
        assertEquals(1, ShadowAppService.timestampReads);
    }

    @Test
    public void missingOrInvalidCachedTimestampRetainsPreviousSentinelWithoutCrashing() {
        for (String timestamp : new String[] {null, "", "invalid", "abcde"}) {
            ShadowAppService.timestamp = timestamp;

            String actual = request(AppClient.TV).build();

            assertTimestamp(actual, -1);
            assertSessionFields(actual);
        }
        assertEquals(4, ShadowAppService.timestampReads);
    }

    @Test
    public void otherTimestampLengthsRemainUnchanged() {
        ShadowAppService.timestamp = "2069";
        assertTimestamp(request(AppClient.TV).build(), 2069);
        ShadowAppService.timestamp = "206970";
        assertTimestamp(request(AppClient.TV).build(), 206970);
    }

    @Test
    public void automaticVisitorAndNonceUseExistingSessionWithoutOtherChanges() {
        String actual = request(AppClient.TV_DOWNGRADED)
                .setVisitorData(null).setClientPlaybackNonce(null).build();
        String expected = request(AppClient.TV_DOWNGRADED)
                .setSignatureTimestamp(20_697_001).build();

        assertEquals("all non-timestamp request fields retain their existing serialization",
                expected, actual);
        assertEquals(1, ShadowAppService.timestampReads);
        assertEquals(1, ShadowAppService.visitorReads);
        assertEquals(1, ShadowAppService.cpnReads);
        assertEquals("20697", ShadowAppService.timestamp);
        assertSessionFields(actual);
    }

    private QueryBuilder request(AppClient client) {
        return new QueryBuilder(client)
                .setLanguage("en").setCountry("US").setUtcOffsetMinutes(0)
                .setVideoId("offline-video").setPlaylistId("offline-playlist")
                .setPlaylistIndex(3).setClickTrackingParams("offline-click")
                .setVisitorData(VISITOR).setClientPlaybackNonce(CPN).setPoToken(POT);
    }

    private void assertTimestamp(String query, int timestamp) {
        assertEquals(timestamp, JsonParser.parseString(query).getAsJsonObject()
                .getAsJsonObject("playbackContext").getAsJsonObject("contentPlaybackContext")
                .get("signatureTimestamp").getAsInt());
    }

    private void assertSessionFields(String query) {
        assertTrue(query.contains("\"visitorData\": \"" + VISITOR + "\""));
        assertTrue(query.contains("\"cpn\": \"" + CPN + "\""));
        assertTrue(query.contains("\"poToken\": \"" + POT + "\""));
        assertTrue(query.contains("\"clickTrackingParams\": \"offline-click\""));
        assertTrue(query.contains("\"videoId\": \"offline-video\""));
    }

    @Implements(AppService.class)
    public static class ShadowAppService {
        static AppService service;
        static String timestamp;
        static int timestampReads;
        static int visitorReads;
        static int cpnReads;

        @Implementation
        protected void __constructor__() {
            // No Retrofit, preferences or player-JS extraction in these serialization tests.
        }

        @Implementation
        protected static AppService instance() {
            return service;
        }

        @Implementation
        protected String getSignatureTimestamp() {
            timestampReads++;
            return timestamp;
        }

        @Implementation
        protected String getVisitorData() {
            visitorReads++;
            return VISITOR;
        }

        @Implementation
        protected String getClientPlaybackNonce() {
            cpnReads++;
            return CPN;
        }

        @Implementation
        protected void invalidateCache() {
            throw new AssertionError("serialization must not invalidate app state");
        }

        @Implementation
        protected void invalidateVisitorData() {
            throw new AssertionError("serialization must not replace the visitor");
        }

        @Implementation
        protected void resetClientPlaybackNonce() {
            throw new AssertionError("serialization must not reset the existing nonce");
        }

        @Implementation
        protected void refreshCacheIfNeeded() {
            throw new AssertionError("serialization must not force a cache refresh");
        }
    }
}
