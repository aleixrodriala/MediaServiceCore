package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.Test;

import java.util.EnumSet;

/** NEWTUBE(wall-memory): per (visitor, source), for the TTL, persisted without the visitor. */
public class PlaybackWallMemoryTest {
    private static final long T0 = 1_780_000_000_000L;

    /**
     * The signature, on stream position: refused at or past 60 s after media before it served (the
     * jump to 73.7 s of r11's recheck included); refused past it with nothing before it served is
     * ambiguous; before it, or past media already served past it, is not the wall.
     */
    @Test
    public void theWallsSignatureIsOnStreamPosition() {
        assertEquals(PlaybackWallMemory.Signature.WALL, PlaybackWallMemory.classify(60_000, 0, 55_000));
        assertEquals(PlaybackWallMemory.Signature.WALL, PlaybackWallMemory.classify(70_001, 0, 5_000));
        assertEquals(PlaybackWallMemory.Signature.WALL, PlaybackWallMemory.classify(60_000, 54_054, 54_054));
        assertEquals(PlaybackWallMemory.Signature.WALL, PlaybackWallMemory.classify(56_000, 0, 50_000));
        assertEquals(PlaybackWallMemory.Signature.AMBIGUOUS, PlaybackWallMemory.classify(73_699, -1, -1));
        assertEquals("media past the wall served", PlaybackWallMemory.Signature.NONE,
                PlaybackWallMemory.classify(200_000, 200_000, 200_000));
        assertEquals(PlaybackWallMemory.Signature.NONE, PlaybackWallMemory.classify(3_000, -1, -1));
        assertEquals(PlaybackWallMemory.Signature.NONE, PlaybackWallMemory.classify(54_000, 0, 50_000));
        assertEquals(PlaybackWallMemory.Signature.NONE, PlaybackWallMemory.classify(600_000, 0, 595_000));
        assertEquals(PlaybackWallMemory.Signature.NONE, PlaybackWallMemory.classify(-1, 0, 30_000));
    }

    /** Keyed by visitor AND source: TV_TIZEN on the same visitor, or another visitor, is not walled. */
    @Test
    public void aWallIsPerVisitorAndSource() {
        PlaybackWallMemory memory = new PlaybackWallMemory();
        assertTrue(memory.isEmpty(T0));
        memory.record("fp1", AppClient.VISIONOS, T0);
        memory.record("fp1", AppClient.ANDROID_VR, T0 + 1);
        assertEquals(EnumSet.of(AppClient.VISIONOS, AppClient.ANDROID_VR), memory.walledFor("fp1", T0 + 2));
        assertFalse(memory.isWalled("fp1", AppClient.TV_TIZEN, T0 + 2));
        assertTrue(memory.walledFor("fp2", T0 + 2).isEmpty());
        assertTrue(memory.walledFor(null, T0 + 2).isEmpty());
        memory.record(null, AppClient.VISIONOS, T0);
        memory.record("fp3", null, T0);
        assertEquals(2, memory.describe(T0 + 2).split(",").length);
    }

    /** Remembered for the TTL (still walled 16.5 min later, r11), then asked again. */
    @Test
    public void aWallLapsesAfterItsTtl() {
        PlaybackWallMemory memory = new PlaybackWallMemory();
        memory.record("fp1", AppClient.VISIONOS, T0);
        assertTrue(memory.isWalled("fp1", AppClient.VISIONOS, T0 + 16_500 * 60));
        assertTrue(memory.isWalled("fp1", AppClient.VISIONOS, T0 + PlaybackWallMemory.DEFAULT_TTL_MS - 1));
        assertFalse(memory.isWalled("fp1", AppClient.VISIONOS, T0 + PlaybackWallMemory.DEFAULT_TTL_MS));
        assertTrue(memory.isEmpty(T0 + PlaybackWallMemory.DEFAULT_TTL_MS));

        memory.setTtlMs(20 * 60_000L);
        memory.record("fp1", AppClient.VISIONOS, T0);
        assertFalse(memory.isWalled("fp1", AppClient.VISIONOS, T0 + 20 * 60_000L));
    }

    /** Across a restart; the snapshot holds the fingerprint, and a damaged one starts empty. */
    @Test
    public void wallsSurviveARestart() {
        final String[] saved = new String[1];
        PlaybackWallMemory.Store store = new PlaybackWallMemory.Store() {
            @Override
            public String load() {
                return saved[0];
            }

            @Override
            public void save(String snapshot) {
                saved[0] = snapshot;
            }
        };
        PlaybackWallMemory first = new PlaybackWallMemory();
        first.setStore(store);
        first.record("fp1", AppClient.VISIONOS, T0);
        assertTrue(saved[0], saved[0].contains("\"f\":\"fp1\"") && saved[0].contains("\"c\":\"VISIONOS\""));

        PlaybackWallMemory second = new PlaybackWallMemory();
        second.setStore(store);
        assertTrue(second.isWalled("fp1", AppClient.VISIONOS, T0 + 60_000));
        // expired on restore: gone, and the empty memory is saved as nothing
        PlaybackWallMemory third = new PlaybackWallMemory();
        third.setStore(store);
        assertTrue(third.isEmpty(T0 + PlaybackWallMemory.DEFAULT_TTL_MS));
        assertNull(saved[0]);

        saved[0] = "{not json";
        PlaybackWallMemory damaged = new PlaybackWallMemory();
        damaged.setStore(store);
        assertTrue(damaged.isEmpty(T0));
    }

    /** Bounded: the oldest wall goes first; a clock set back does not make one last forever. */
    @Test
    public void boundedAndClockSafe() {
        PlaybackWallMemory memory = new PlaybackWallMemory();
        for (int i = 0; i <= PlaybackWallMemory.CAPACITY; i++) {
            memory.record("fp" + i, AppClient.VISIONOS, T0 + i);
        }
        assertFalse(memory.isWalled("fp0", AppClient.VISIONOS, T0 + 100));
        assertTrue(memory.isWalled("fp1", AppClient.VISIONOS, T0 + 100));

        PlaybackWallMemory setBack = new PlaybackWallMemory();
        setBack.record("fp1", AppClient.VISIONOS, T0);
        assertFalse("recorded in the future", setBack.isWalled("fp1", AppClient.VISIONOS,
                T0 - PlaybackWallMemory.DEFAULT_TTL_MS));
    }
}
