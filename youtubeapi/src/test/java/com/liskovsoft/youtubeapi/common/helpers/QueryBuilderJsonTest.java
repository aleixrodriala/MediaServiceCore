package com.liskovsoft.youtubeapi.common.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.app.Application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.MalformedJsonException;
import com.liskovsoft.youtubeapi.app.AppService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.io.StringReader;

/** Strict parsing of actual request bodies with synthetic fields; never sends a request. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = QueryBuilderTimestampTest.ShadowAppService.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class QueryBuilderJsonTest {
    @Before
    public void setUp() {
        QueryBuilderTimestampTest.ShadowAppService.service =
                ReflectionHelpers.callConstructor(AppService.class);
        QueryBuilderTimestampTest.ShadowAppService.timestamp = "20697";
    }

    @Test
    public void playerRequestsAreStrictJsonForEveryExistingClient() throws IOException {
        for (AppClient client : AppClient.values()) {
            JsonObject request = strictObject(request(client).build());

            assertEquals(client.name(), "offline-visitor", request.getAsJsonObject("context")
                    .getAsJsonObject("client").get("visitorData").getAsString());
        }
    }

    @Test
    public void browseRequestsAreStrictJsonWithOptionalPlayerFieldsAbsent() throws IOException {
        for (AppClient client : AppClient.values()) {
            QueryBuilder builder = request(client).setVideoId(null).setBrowseId("offline-browse")
                    .setClientPlaybackNonce(null).setSignatureTimestamp(null);
            JsonObject request = strictObject(builder.build());

            assertEquals(client.name(), "offline-browse", request.get("browseId").getAsString());
        }
    }

    @Test
    public void optionalRequestChunksKeepTheirOriginalValues() throws IOException {
        JsonObject request = strictObject(request(AppClient.TV_DOWNGRADED)
                .setSignatureTimestamp(null).setPlaylistId("offline-playlist")
                .setPlaylistIndex(3).setClickTrackingParams("offline-click")
                .setPoToken("offline-pot").build());

        assertEquals(20_697_001, request.getAsJsonObject("playbackContext")
                .getAsJsonObject("contentPlaybackContext").get("signatureTimestamp").getAsInt());
        assertEquals("offline-pot", request.getAsJsonObject("serviceIntegrityDimensions")
                .get("poToken").getAsString());
        assertEquals("offline-click", request.getAsJsonObject("context")
                .getAsJsonObject("clickTracking").get("clickTrackingParams").getAsString());
        assertEquals("offline-playlist", request.get("playlistId").getAsString());
        assertEquals("3", request.get("playlistIndex").getAsString());
    }

    @Test
    public void commaAndClosingBraceInsideRequestStringArePreserved() throws IOException {
        JsonObject request = strictObject(request(AppClient.IOS)
                .setClickTrackingParams("literal,}suffix").build());

        assertEquals("literal,}suffix", request.getAsJsonObject("context")
                .getAsJsonObject("clickTracking").get("clickTrackingParams").getAsString());
    }

    @Test
    public void escapedQuotesAndBackslashesDoNotHideObjectBoundaries() throws IOException {
        String json = "{\"value\":\"quote\\\",} slash\\\\\",\"child\":{\"n\":1, },}";

        JsonObject request = strictObject(normalize(json));

        assertEquals("quote\",} slash\\", request.get("value").getAsString());
        assertEquals(1, request.getAsJsonObject("child").get("n").getAsInt());
    }

    @Test
    public void alreadyValidJsonIsUnchanged() {
        String json = "{\"value\":\",}\",\"escaped\":\"\\\\\\\",}\",\"array\":[1,2]}";

        assertEquals(json, normalize(json));
    }

    @Test
    public void unterminatedStringIsNotSilentlyRepaired() {
        String json = "{\"value\":\"unfinished,}";

        assertEquals(json, normalize(json));
        assertThrows(MalformedJsonException.class, () -> strictObject(normalize(json)));
    }

    private QueryBuilder request(AppClient client) {
        return new QueryBuilder(client).setLanguage("en").setCountry("US")
                .setUtcOffsetMinutes(0).setVideoId("offline-video")
                .setVisitorData("offline-visitor").setClientPlaybackNonce("offline-cpn")
                .setSignatureTimestamp(20_697);
    }

    private String normalize(String json) {
        return ReflectionHelpers.callInstanceMethod(new QueryBuilder(AppClient.IOS),
                "removeTrailingObjectCommas", ReflectionHelpers.ClassParameter.from(String.class, json));
    }

    private JsonObject strictObject(String json) throws IOException {
        // JsonParser.parseString deliberately accepts lenient input; validate with the streaming
        // reader first so a reintroduced trailing comma fails this test.
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.skipValue();
            assertEquals(JsonToken.END_DOCUMENT, reader.peek());
        }
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
