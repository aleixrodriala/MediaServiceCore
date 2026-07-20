package com.liskovsoft.youtubeapi.lounge.sender;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Command form encoding: count/ofs/req0__sc scheme, ms-to-seconds conversion.
 * Canned values, no network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34) // newest SDK that runs on Java 17
public class SenderCommandTest {
    @Test
    public void testThatSetPlaylistIsEncoded() {
        Map<String, String> fields = SenderCommand.setPlaylist("hWKr20RnVM0", 95_500).encode(0);

        assertEquals("1", fields.get("count"));
        assertEquals("0", fields.get("ofs"));
        // DOUBLE underscore before sc — getting this wrong fails silently
        assertEquals("setPlaylist", fields.get("req0__sc"));
        assertFalse("No single-underscore command name", fields.containsKey("req0_sc"));
        assertEquals("hWKr20RnVM0", fields.get("req0_videoId"));
        assertEquals("Time in seconds", "95.5", fields.get("req0_currentTime"));
        assertEquals("0", fields.get("req0_currentIndex"));
        assertEquals("hWKr20RnVM0", fields.get("req0_videoIds"));
        assertEquals("No extra fields", 7, fields.size());
    }

    @Test
    public void testThatSeekToIsEncodedWithOfs() {
        Map<String, String> fields = SenderCommand.seekTo(60_000).encode(3);

        assertEquals("1", fields.get("count"));
        assertEquals("Session offset advances", "3", fields.get("ofs"));
        assertEquals("seekTo", fields.get("req0__sc"));
        assertEquals("Whole seconds stay integer", "60", fields.get("req0_newTime"));
        assertEquals(4, fields.size());
    }

    @Test
    public void testThatSetVolumeIsEncoded() {
        Map<String, String> fields = SenderCommand.setVolume(85).encode(1);

        assertEquals("setVolume", fields.get("req0__sc"));
        assertEquals("85", fields.get("req0_volume"));
        assertEquals(4, fields.size());
    }

    @Test
    public void testThatArglessCommandsAreEncoded() {
        for (SenderCommand command : new SenderCommand[]{
                SenderCommand.play(), SenderCommand.pause(), SenderCommand.stopVideo()}) {
            Map<String, String> fields = command.encode(2);

            assertEquals("1", fields.get("count"));
            assertEquals("2", fields.get("ofs"));
            assertEquals(command.getName(), fields.get("req0__sc"));
            assertEquals("No argument fields for " + command.getName(), 3, fields.size());
        }
    }

    @Test
    public void testThatNegativeTimeIsClamped() {
        Map<String, String> fields = SenderCommand.setPlaylist("abc", -1).encode(0);

        assertEquals("0", fields.get("req0_currentTime"));
    }

    @Test
    public void testThatZxIsTwelveLowercaseLetters() {
        for (int i = 0; i < 20; i++) {
            String zx = SenderParams.generateZx();

            assertEquals("zx length", 12, zx.length());
            assertTrue("zx is lowercase a-z: " + zx, zx.matches("[a-z]{12}"));
        }
    }

    @Test
    public void testThatPairingCodeIsNormalized() {
        assertEquals("123456789012", SenderParams.normalizePairingCode(" 123 456-789 012\t"));
        assertEquals("", SenderParams.normalizePairingCode("  "));
    }
}
