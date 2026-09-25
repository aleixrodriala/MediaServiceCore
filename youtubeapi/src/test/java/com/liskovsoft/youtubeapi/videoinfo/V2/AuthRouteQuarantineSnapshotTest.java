package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.AuthRouteQuarantineBook.Record;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Offline: the quarantine snapshot's own rules, with both clocks supplied by the test. */
public class AuthRouteQuarantineSnapshotTest {
    private static final AppClient[] ALLOWED = {AppClient.TV_DOWNGRADED, AppClient.TV};
    private static final long TEN_MIN = 600_000;
    private static final long HOUR = 3_600_000;
    private static final long ELAPSED = 5_000;
    private static final long WALL = 1_788_800_000_000L;

    // --- v2 (current) -----------------------------------------------------------------------

    @Test
    public void survivesAProcessRestartWithItsRemainingCooldownAndStrikes() {
        String snapshot = encode(record("cell", AppClient.TV_DOWNGRADED, ELAPSED + 400_000, 2,
                WALL - 100_000));

        // New process: elapsedRealtime restarted, wall clock moved on by a minute.
        List<Record> restored = decode(snapshot, 20, WALL + 60_000);

        assertEquals(1, restored.size());
        Record record = restored.get(0);
        assertEquals("cell", record.transport);
        assertEquals(AppClient.TV_DOWNGRADED, record.client);
        assertEquals(20 + 340_000, record.untilElapsedMs);
        assertEquals(2, record.strikes);
        assertEquals(WALL - 100_000, record.armedWallMs);
    }

    @Test
    public void keepsEveryTransportAndClientIndependently() {
        String snapshot = encode(
                record("cell", AppClient.TV_DOWNGRADED, ELAPSED + 400_000, 1, WALL),
                record("cell", AppClient.TV, ELAPSED + 200_000, 3, WALL),
                record("wifi", AppClient.TV_DOWNGRADED, ELAPSED + 300_000, 2, WALL));

        List<Record> restored = decode(snapshot, ELAPSED, WALL);

        assertEquals(3, restored.size());
        assertEquals(ELAPSED + 400_000, find(restored, "cell", AppClient.TV_DOWNGRADED).untilElapsedMs);
        assertEquals(3, find(restored, "cell", AppClient.TV).strikes);
        assertEquals(2, find(restored, "wifi", AppClient.TV_DOWNGRADED).strikes);
    }

    /** An expired cooldown is still worth storing: its strike is what makes the next one longer. */
    @Test
    public void anExpiredCooldownKeepsItsStrikeButIsNotResurrected() {
        String snapshot = encode(record("cell", AppClient.TV_DOWNGRADED, ELAPSED - 1, 2,
                WALL - TEN_MIN));

        List<Record> restored = decode(snapshot, ELAPSED, WALL + 60_000);

        assertEquals(1, restored.size());
        assertFalse(restored.get(0).isLive(ELAPSED));
        assertEquals(2, restored.get(0).strikes);
    }

    @Test
    public void aForgottenStrikeIsNotStoredOrRestored() {
        Record forgotten = record("cell", AppClient.TV, ELAPSED - 1, 4,
                WALL - AuthRouteQuarantineBook.STRIKE_MEMORY_MS - 1);

        assertNull(encode(forgotten));
        String written = "v2|cell:TV:" + (WALL - 1) + ":4:"
                + (WALL - AuthRouteQuarantineBook.STRIKE_MEMORY_MS - 1);
        assertTrue(decode(written, ELAPSED, WALL).isEmpty());
    }

    /** Neither a forward clock jump nor an edited expiry can outlast the strike level's TTL. */
    @Test
    public void aForwardClockJumpCannotExtendTheCooldownBeyondItsStrikeLevel() {
        String snapshot = "v2|cell:TV_DOWNGRADED:" + (WALL + 48 * HOUR) + ":2:" + WALL;

        Record record = decode(snapshot, ELAPSED, WALL).get(0);

        assertEquals(ELAPSED + 4 * TEN_MIN, record.untilElapsedMs);
    }

    @Test
    public void anArmingTimeFromTheFutureCannotStretchTheStrikeMemory() {
        String snapshot = "v2|cell:TV:" + (WALL + TEN_MIN) + ":1:" + (WALL + 365 * 24 * HOUR);

        Record record = decode(snapshot, ELAPSED, WALL).get(0);

        assertEquals(WALL, record.armedWallMs);
    }

    @Test
    public void strikesAreClampedToAPlausibleRange() {
        String snapshot = "v2|cell:TV:" + (WALL + TEN_MIN) + ":999:" + WALL
                + ";wifi:TV:" + (WALL + TEN_MIN) + ":0:" + WALL;

        List<Record> restored = decode(snapshot, ELAPSED, WALL);

        assertEquals(1, restored.size());
        assertEquals(AuthRouteQuarantineBook.MAX_STRIKES, restored.get(0).strikes);
    }

    // --- legacy (1.9.0) -------------------------------------------------------------------

    /**
     * 1.9.0 wrote {@code transport:netIdHash|CLIENT:expiry}. It must restore onto its transport -
     * the netId part is exactly what no longer matters - as one strike.
     */
    @Test
    public void legacySnapshotRestoresOntoItsTransportAsOneStrike() {
        String legacy = "cell:340|" + AppClient.TV_DOWNGRADED.name() + ":" + (WALL + 400_000)
                + ";" + AppClient.TV.name() + ":" + (WALL + 200_000);

        List<Record> restored = decode(legacy, ELAPSED, WALL);

        assertEquals(2, restored.size());
        Record downgraded = find(restored, "cell", AppClient.TV_DOWNGRADED);
        assertEquals(ELAPSED + 400_000, downgraded.untilElapsedMs);
        assertEquals(1, downgraded.strikes);
        // Armed when its fixed 10-minute cooldown started.
        assertEquals(WALL + 400_000 - TEN_MIN, downgraded.armedWallMs);
        assertEquals(ELAPSED + 200_000, find(restored, "cell", AppClient.TV).untilElapsedMs);
    }

    @Test
    public void legacyForwardClockJumpCannotExtendTheCooldown() {
        // Written when the wall clock was an hour ahead of where it is now.
        String legacy = "cell:340|" + AppClient.TV_DOWNGRADED.name() + ":" + (WALL + HOUR);

        Record record = decode(legacy, ELAPSED, WALL).get(0);

        assertEquals(ELAPSED + TEN_MIN, record.untilElapsedMs);
        assertTrue(record.armedWallMs <= WALL);
    }

    @Test
    public void legacyExpiredEntryIsRememberedButNotLive() {
        String legacy = "wifi:12|" + AppClient.TV.name() + ":" + (WALL - 60_000);

        List<Record> restored = decode(legacy, ELAPSED, WALL);

        assertEquals(1, restored.size());
        assertEquals("wifi", restored.get(0).transport);
        assertFalse(restored.get(0).isLive(ELAPSED));
    }

    @Test
    public void legacyValueIsMigratedToTheCurrentFormatOnTheNextWrite() {
        String legacy = "cell:340|" + AppClient.TV_DOWNGRADED.name() + ":" + (WALL + 400_000);
        assertFalse(AuthRouteQuarantineSnapshot.isCurrentFormat(legacy));

        String migrated = AuthRouteQuarantineSnapshot.encode(decode(legacy, ELAPSED, WALL),
                ELAPSED, WALL);

        assertTrue(AuthRouteQuarantineSnapshot.isCurrentFormat(migrated));
        assertEquals(ELAPSED + 400_000,
                decode(migrated, ELAPSED, WALL).get(0).untilElapsedMs);
    }

    // --- both --------------------------------------------------------------------------------

    @Test
    public void aSnapshotCanOnlyEverNameAnAccountBearingHead() {
        String legacy = "cell:340|" + AppClient.VISIONOS.name() + ":" + (WALL + 400_000)
                + ";NOT_A_CLIENT:" + (WALL + 400_000)
                + ";" + AppClient.TV.name() + ":" + (WALL + 400_000);
        String current = "v2|cell:VISIONOS:" + (WALL + 400_000) + ":1:" + WALL
                + ";cell:NOT_A_CLIENT:" + (WALL + 400_000) + ":1:" + WALL
                + ";cell:TV:" + (WALL + 400_000) + ":1:" + WALL;

        for (String snapshot : new String[] {legacy, current}) {
            List<Record> restored = decode(snapshot, ELAPSED, WALL);
            assertEquals(snapshot, 1, restored.size());
            assertEquals(AppClient.TV, restored.get(0).client);
        }
    }

    @Test
    public void garbageIsDroppedRatherThanTrusted() {
        for (String snapshot : new String[] {
                null, "", "|", "cell:340", "cell:340|", "cell:340|TV_DOWNGRADED",
                "cell:340|TV_DOWNGRADED:", "cell:340|TV_DOWNGRADED:soon", "|TV:1",
                "CELL:1|TV:" + (WALL + 1), "v2|", "v2|cell", "v2|cell:TV",
                "v2|cell:TV:" + (WALL + 1), "v2|cell:TV:soon:1:" + WALL,
                "v2|cell:TV:" + (WALL + 1) + ":one:" + WALL,
                "v2|:TV:" + (WALL + 1) + ":1:" + WALL,
                "v2|Cell:TV:" + (WALL + 1) + ":1:" + WALL,
                "v2|cell:TV:" + (WALL + 1) + ":1:" + WALL + ":extra"}) {
            assertTrue(String.valueOf(snapshot), decode(snapshot, ELAPSED, WALL).isEmpty());
        }
    }

    @Test
    public void nothingWorthKeepingMeansNothingStored() {
        assertNull(AuthRouteQuarantineSnapshot.encode(Collections.emptyList(), ELAPSED, WALL));
    }

    private static Record record(String transport, AppClient client, long untilElapsedMs,
            int strikes, long armedWallMs) {
        return new Record(transport, client, untilElapsedMs, strikes, armedWallMs);
    }

    private static Record find(List<Record> records, String transport, AppClient client) {
        for (Record record : records) {
            if (record.transport.equals(transport) && record.client == client) {
                return record;
            }
        }
        throw new AssertionError("no " + transport + "/" + client + " in " + records.size());
    }

    private static String encode(Record... records) {
        return AuthRouteQuarantineSnapshot.encode(Arrays.asList(records), ELAPSED, WALL);
    }

    private static List<Record> decode(String snapshot, long elapsed, long wall) {
        return AuthRouteQuarantineSnapshot.decode(snapshot, ALLOWED, elapsed, wall);
    }
}
