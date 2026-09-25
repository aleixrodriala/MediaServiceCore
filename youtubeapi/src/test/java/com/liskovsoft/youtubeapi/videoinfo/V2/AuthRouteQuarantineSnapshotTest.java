package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.AuthRouteQuarantineBook.Record;
import com.liskovsoft.youtubeapi.videoinfo.V2.AuthRouteQuarantineBook.Streak;

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
        assertNull(AuthRouteQuarantineSnapshot.encode(Collections.<Record>emptyList(),
                Collections.singletonList(streak("wifi", AppClient.TV, 1, "a1",
                        WALL - AuthRouteQuarantineBook.NO_MEDIA_STREAK_MS - 1)),
                ELAPSED, WALL));
    }

    // --- v3: no-media streaks ------------------------------------------------------------------

    /** The point of v3: a cold open's single no-media hit reaches the next process. */
    @Test
    public void aStreakSurvivesAProcessRestart() {
        String snapshot = AuthRouteQuarantineSnapshot.encode(
                Collections.singletonList(record("wifi", AppClient.TV_DOWNGRADED,
                        ELAPSED + 400_000, 1, WALL - 100_000)),
                Collections.singletonList(streak("wifi", AppClient.TV, 1, "5f3a9c", WALL - 60_000)),
                ELAPSED, WALL);
        assertTrue(snapshot, snapshot.startsWith("v3|wifi:TV_DOWNGRADED:"));
        assertTrue(snapshot, snapshot.endsWith("|wifi:TV:1:" + (WALL - 60_000) + ":5f3a9c"));

        AuthRouteQuarantineSnapshot.Decoded decoded =
                AuthRouteQuarantineSnapshot.decodeAll(snapshot, ALLOWED, 20, WALL + 60_000);

        assertEquals(1, decoded.records.size());
        assertEquals(20 + 340_000, decoded.records.get(0).untilElapsedMs);
        assertEquals(1, decoded.streaks.size());
        Streak streak = decoded.streaks.get(0);
        assertEquals("wifi", streak.transport);
        assertEquals(AppClient.TV, streak.client);
        assertEquals(1, streak.hits);
        assertEquals("5f3a9c", streak.lastVideoKey);
        assertEquals(WALL - 60_000, streak.lastHitWallMs);
    }

    @Test
    public void aSnapshotMayHoldOnlyStreaks() {
        String snapshot = AuthRouteQuarantineSnapshot.encode(Collections.<Record>emptyList(),
                Collections.singletonList(streak("cell", AppClient.TV, 1, "a1", WALL)),
                ELAPSED, WALL);

        assertEquals("v3||cell:TV:1:" + WALL + ":a1", snapshot);
        AuthRouteQuarantineSnapshot.Decoded decoded =
                AuthRouteQuarantineSnapshot.decodeAll(snapshot, ALLOWED, ELAPSED, WALL);
        assertTrue(decoded.records.isEmpty());
        assertEquals(1, decoded.streaks.size());
    }

    @Test
    public void aRecordsOnlySnapshotHasAnEmptyStreakSection() {
        String snapshot = encode(record("cell", AppClient.TV, ELAPSED + TEN_MIN, 1, WALL));

        assertEquals("v3|cell:TV:" + (WALL + TEN_MIN) + ":1:" + WALL + "|", snapshot);
        assertTrue(AuthRouteQuarantineSnapshot.decodeAll(snapshot, ALLOWED, ELAPSED, WALL)
                .streaks.isEmpty());
    }

    @Test
    public void aStaleStreakIsNeitherStoredNorRestored() {
        long stale = WALL - AuthRouteQuarantineBook.NO_MEDIA_STREAK_MS - 1;
        String written = "v3||cell:TV:1:" + stale + ":a1";

        assertTrue(AuthRouteQuarantineSnapshot.decodeAll(written, ALLOWED, ELAPSED, WALL)
                .isEmpty());
    }

    /**
     * No stored streak can quarantine on its own or stretch its window: hits are clamped to one
     * short of a quarantine and a last hit from the future is read as now.
     */
    @Test
    public void aHandEditedStreakCanNeverQuarantineMore() {
        Streak streak = AuthRouteQuarantineSnapshot.decodeAll("v3||cell:TV:99:" + WALL + ":a1",
                ALLOWED, ELAPSED, WALL).streaks.get(0);
        assertEquals(AuthRouteQuarantineBook.NO_MEDIA_MIN_HITS - 1, streak.hits);

        for (long when : new long[] {WALL + 1, WALL + 365 * 24 * HOUR, 0, Long.MIN_VALUE}) {
            String snapshot = "v3||cell:TV:1:" + when + ":a1";
            assertTrue("not clamped into a fresh hit: " + snapshot,
                    AuthRouteQuarantineSnapshot.decodeAll(snapshot, ALLOWED, ELAPSED, WALL)
                            .isEmpty());
        }
    }

    /** The videos that armed a no-media quarantine travel with its record, as a 6th field. */
    @Test
    public void theArmingVideosSurviveARestartWithTheirRecord() {
        String snapshot = encode(new Record("cell", AppClient.TV, ELAPSED - 1, 2, WALL - HOUR,
                Arrays.asList("b2", "c3")));
        assertTrue(snapshot, snapshot.startsWith("v3|cell:TV:"));
        assertTrue(snapshot, snapshot.endsWith(":2:" + (WALL - HOUR) + ":b2,c3|"));

        Record record = decode(snapshot, ELAPSED, WALL).get(0);

        assertEquals(Arrays.asList("b2", "c3"), record.armingVideoKeys);
        assertEquals(2, record.strikes);
        assertEquals("the single key of the first v3 builds still reads",
                Collections.singletonList("b2"), decode("v3|cell:TV:" + WALL + ":1:" + WALL
                        + ":b2|", ELAPSED, WALL).get(0).armingVideoKeys);
        assertTrue("a 5-field record (media 403, v2, first v3 builds) has none",
                decode("v3|cell:TV:" + WALL + ":1:" + WALL + "|", ELAPSED, WALL)
                        .get(0).armingVideoKeys.isEmpty());
    }

    @Test
    public void streakGarbageIsDroppedRatherThanTrusted() {
        for (String snapshot : new String[] {
                "v3||cell:TV:0:" + WALL + ":a1",               // no hits
                "v3|cell:TV:" + (WALL + 1) + ":1:" + WALL + ":Fo89b8zAIE4|", // raw id as key
                "v3|cell:TV:" + (WALL + 1) + ":1:" + WALL + ":a1,,b2|",
                "v3|cell:TV:" + (WALL + 1) + ":1:" + WALL + ":a1,b2,c3,d4|", // over the bound
                "v3||cell:TV:one:" + WALL + ":a1",
                "v3||cell:TV:1:soon:a1",
                "v3||cell:TV:1:" + WALL + ":",                 // no video key
                "v3||cell:TV:1:" + WALL + ":Fo89b8zAIE4",      // a raw videoId is not a key
                "v3||cell:TV:1:" + WALL + ":0123456789abcdef0", // too long
                "v3||cell:VISIONOS:1:" + WALL + ":a1",         // not an account head
                "v3||Cell:TV:1:" + WALL + ":a1",
                "v3||cell:TV:1:" + WALL,
                "v3||cell:TV:1:" + WALL + ":a1:extra",
                "v3|cell:TV:" + (WALL + 1) + ":1:" + WALL + "|cell:TV:1:" + WALL + ":a1|more",
                "v3|", "v3||", "v3|garbage|garbage"}) {
            assertTrue(snapshot,
                    AuthRouteQuarantineSnapshot.decodeAll(snapshot, ALLOWED, ELAPSED, WALL)
                            .isEmpty());
        }
    }

    // --- backward compatibility ----------------------------------------------------------------

    /**
     * The value the previous build (2026-09-24 round) wrote - {@code v2|} records, no streak
     * section - must restore unchanged, and be read as not-current so it is migrated in place.
     */
    @Test
    public void aV2SnapshotStillRestoresAndIsMigratedToV3() {
        String v2 = "v2|cell:TV_DOWNGRADED:" + (WALL + 400_000) + ":2:" + (WALL - 100_000)
                + ";wifi:TV:" + (WALL - 60_000) + ":1:" + (WALL - TEN_MIN);

        assertFalse(AuthRouteQuarantineSnapshot.isCurrentFormat(v2));
        assertEquals("v2", AuthRouteQuarantineSnapshot.formatName(v2));
        AuthRouteQuarantineSnapshot.Decoded decoded =
                AuthRouteQuarantineSnapshot.decodeAll(v2, ALLOWED, ELAPSED, WALL);

        assertEquals(2, decoded.records.size());
        assertTrue(decoded.streaks.isEmpty());
        Record downgraded = find(decoded.records, "cell", AppClient.TV_DOWNGRADED);
        assertEquals(ELAPSED + 400_000, downgraded.untilElapsedMs);
        assertEquals(2, downgraded.strikes);
        assertFalse(find(decoded.records, "wifi", AppClient.TV).isLive(ELAPSED));

        String migrated = AuthRouteQuarantineSnapshot.encode(decoded.records, decoded.streaks,
                ELAPSED, WALL);
        assertEquals("v3", AuthRouteQuarantineSnapshot.formatName(migrated));
        assertEquals(2, decode(migrated, ELAPSED, WALL).size());
    }

    @Test
    public void formatNamesCoverEveryVersion() {
        assertEquals("none", AuthRouteQuarantineSnapshot.formatName(null));
        assertEquals("none", AuthRouteQuarantineSnapshot.formatName(""));
        assertEquals("v3", AuthRouteQuarantineSnapshot.formatName("v3||"));
        assertEquals("v2", AuthRouteQuarantineSnapshot.formatName("v2|cell:TV:1:1:1"));
        assertEquals("legacy", AuthRouteQuarantineSnapshot.formatName("cell:340|TV:1"));
    }

    private static Record record(String transport, AppClient client, long untilElapsedMs,
            int strikes, long armedWallMs) {
        return new Record(transport, client, untilElapsedMs, strikes, armedWallMs);
    }

    private static Streak streak(String transport, AppClient client, int hits, String videoKey,
            long lastHitWallMs) {
        return new Streak(transport, client, hits, videoKey, lastHitWallMs);
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
