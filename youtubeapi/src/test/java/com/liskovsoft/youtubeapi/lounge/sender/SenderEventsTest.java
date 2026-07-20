package com.liskovsoft.youtubeapi.lounge.sender;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.liskovsoft.mediaserviceinterfaces.RemoteControlService;
import com.liskovsoft.mediaserviceinterfaces.data.CastEvent;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Lounge event payloads (all values are strings, times in seconds) to CastEvent.
 * Canned values, no network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34) // newest SDK that runs on Java 17
public class SenderEventsTest {
    @Before
    public void setUp() {
        ShadowLog.stream = System.out; // catch Log class output
    }

    @Test
    public void testThatNowPlayingIsParsed() {
        CastEvent event = SenderEvents.toCastEvent(message(1, "nowPlaying",
                "{\"videoId\":\"hWKr20RnVM0\",\"currentTime\":\"123.456\",\"duration\":\"300\",\"state\":\"1\"}"));

        assertNotNull(event);
        assertEquals(CastEvent.TYPE_NOW_PLAYING, event.getType());
        assertEquals("hWKr20RnVM0", event.getVideoId());
        assertEquals("Seconds converted to ms", 123_456, event.getPositionMs());
        assertEquals(300_000, event.getDurationMs());
        assertEquals(RemoteControlService.STATE_PLAYING, event.getState());
    }

    @Test
    public void testThatEmptyNowPlayingIsSkipped() {
        // Sent as {} when nothing is playing
        assertNull(SenderEvents.toCastEvent(message(1, "nowPlaying", "{}")));
        assertNull(SenderEvents.toCastEvent(message(1, "nowPlaying", null)));
    }

    @Test
    public void testThatStateChangeIsParsed() {
        CastEvent event = SenderEvents.toCastEvent(message(2, "onStateChange",
                "{\"currentTime\":\"95.5\",\"duration\":\"300\",\"state\":\"2\"}"));

        assertNotNull(event);
        assertEquals(CastEvent.TYPE_STATE_CHANGE, event.getType());
        assertEquals(95_500, event.getPositionMs());
        assertEquals(RemoteControlService.STATE_PAUSED, event.getState());
    }

    @Test
    public void testThatBadNumbersSkipTheEvent() {
        assertNull("Bad currentTime skips the event", SenderEvents.toCastEvent(message(2, "onStateChange",
                "{\"currentTime\":\"oops\",\"duration\":\"300\",\"state\":\"1\"}")));
        assertNull("Missing currentTime skips the event", SenderEvents.toCastEvent(message(2, "onStateChange",
                "{\"duration\":\"300\",\"state\":\"1\"}")));
        assertNull("Bad volume skips the event", SenderEvents.toCastEvent(message(3, "onVolumeChanged",
                "{\"volume\":\"loud\",\"muted\":\"false\"}")));
    }

    @Test
    public void testThatVolumeChangeIsParsed() {
        CastEvent event = SenderEvents.toCastEvent(message(3, "onVolumeChanged",
                "{\"volume\":\"85\",\"muted\":\"false\"}"));

        assertNotNull(event);
        assertEquals(CastEvent.TYPE_VOLUME_CHANGE, event.getType());
        assertEquals(85, event.getVolume());
    }

    @Test
    public void testThatUnknownEventsAreIgnored() {
        assertNull(SenderEvents.toCastEvent(message(4, "onAutoplayModeChanged",
                "{\"autoplayMode\":\"UNSUPPORTED\"}")));
        assertNull(SenderEvents.toCastEvent(message(5, "someFutureEvent", "{\"foo\":\"bar\"}")));
        assertNull(SenderEvents.toCastEvent(message(6, "noop", null)));
    }

    @Test
    public void testStateMapping() {
        assertEquals("playing", RemoteControlService.STATE_PLAYING, SenderEvents.toAppState(1));
        assertEquals("paused", RemoteControlService.STATE_PAUSED, SenderEvents.toAppState(2));
        assertEquals("buffering counts as playing", RemoteControlService.STATE_PLAYING, SenderEvents.toAppState(3));
        assertEquals("stopped", RemoteControlService.STATE_IDLE, SenderEvents.toAppState(0));
        assertEquals("unknown positive", RemoteControlService.STATE_IDLE, SenderEvents.toAppState(17));
        assertEquals("unknown negative", RemoteControlService.STATE_IDLE, SenderEvents.toAppState(-1));
    }

    private static LoungeMessage message(int index, String type, String payloadJson) {
        JsonObject payload = payloadJson != null ? JsonParser.parseString(payloadJson).getAsJsonObject() : null;

        return new LoungeMessage(index, type, payload, null);
    }
}
