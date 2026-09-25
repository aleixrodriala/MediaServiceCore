package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.AuthRouteQuarantineBook.NoMediaOutcome;
import com.liskovsoft.youtubeapi.videoinfo.V2.AuthRouteQuarantineBook.Record;
import com.liskovsoft.youtubeapi.videoinfo.V2.AuthRouteQuarantineBook.Streak;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/**
 * Offline: escalation, strike memory and transport keying of the account-route quarantine, and
 * the no-media streak / probation that feed it.
 */
public class AuthRouteQuarantineBookTest {
    private static final long MIN = 60_000;
    private static final long HOUR = 60 * MIN;
    private static final long ELAPSED = 5_000;
    private static final long WALL = 1_788_800_000_000L;

    /** 10 min x 4^(strikes-1), capped at 24 h. */
    @Test
    public void ttlGrowsFourfoldPerStrikeUpToADay() {
        assertEquals(10 * MIN, AuthRouteQuarantineBook.ttlMsForStrikes(1));
        assertEquals(40 * MIN, AuthRouteQuarantineBook.ttlMsForStrikes(2));
        assertEquals(160 * MIN, AuthRouteQuarantineBook.ttlMsForStrikes(3));
        assertEquals(640 * MIN, AuthRouteQuarantineBook.ttlMsForStrikes(4));
        assertEquals(24 * HOUR, AuthRouteQuarantineBook.ttlMsForStrikes(5));
        assertEquals(24 * HOUR, AuthRouteQuarantineBook.ttlMsForStrikes(6));
        assertEquals(24 * HOUR, AuthRouteQuarantineBook.ttlMsForStrikes(Integer.MAX_VALUE));
    }

    @Test
    public void aNonsenseStrikeCountNeverShortensTheBaseCooldown() {
        assertEquals(10 * MIN, AuthRouteQuarantineBook.ttlMsForStrikes(0));
        assertEquals(10 * MIN, AuthRouteQuarantineBook.ttlMsForStrikes(-3));
    }

    @Test
    public void firstQuarantineIsTheHistoricalTenMinutes() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();

        Record record = book.quarantine("cell", AppClient.TV_DOWNGRADED, ELAPSED, WALL);

        assertEquals(1, record.strikes);
        assertEquals(ELAPSED + 10 * MIN, record.untilElapsedMs);
        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED), book.active("cell", ELAPSED));
    }

    /** The re-probe after each expiry fails again (section 26), so each costs a longer cooldown. */
    @Test
    public void eachReQuarantineAfterExpiryEscalates() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        long elapsed = ELAPSED;
        long wall = WALL;
        long[] expected = {10 * MIN, 40 * MIN, 160 * MIN, 640 * MIN, 24 * HOUR, 24 * HOUR};

        for (int i = 0; i < expected.length; i++) {
            Record record = book.quarantine("cell", AppClient.TV_DOWNGRADED, elapsed, wall);
            assertEquals("strike " + (i + 1), i + 1, record.strikes);
            assertEquals("strike " + (i + 1), elapsed + expected[i], record.untilElapsedMs);

            // Expire it; the next open re-probes one minute later and fails again.
            elapsed = record.untilElapsedMs + MIN;
            wall += expected[i] + MIN;
            assertTrue(book.active("cell", elapsed).isEmpty());
        }
    }

    /** A second 403 from the same failing open (or the demoted fallback refusing) is one episode. */
    @Test
    public void reArmingALiveQuarantineRefreshesWithoutEscalating() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("cell", AppClient.TV, ELAPSED, WALL);

        Record again = book.quarantine("cell", AppClient.TV, ELAPSED + 2 * MIN, WALL + 2 * MIN);

        assertEquals(1, again.strikes);
        assertEquals(ELAPSED + 12 * MIN, again.untilElapsedMs);
    }

    /** Strikes decay on time: 48 h without a quarantine and the client starts over at 10 min. */
    @Test
    public void strikesAreForgottenAfterTwoQuietDays() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("cell", AppClient.TV_DOWNGRADED, ELAPSED, WALL);
        Record second = book.quarantine("cell", AppClient.TV_DOWNGRADED, ELAPSED + 11 * MIN,
                WALL + 11 * MIN);
        assertEquals(2, second.strikes);

        long later = WALL + 11 * MIN + AuthRouteQuarantineBook.STRIKE_MEMORY_MS + 1;
        Record fresh = book.quarantine("cell", AppClient.TV_DOWNGRADED, ELAPSED + 50 * HOUR,
                later);

        assertEquals(1, fresh.strikes);
        assertEquals(ELAPSED + 50 * HOUR + 10 * MIN, fresh.untilElapsedMs);
    }

    /** Twice the cap: a client at 24 h re-probed right after expiry must still escalate. */
    @Test
    public void aClientAtTheCapReProbedAfterExpiryKeepsItsStrikes() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.restore(Collections.singletonList(new Record("cell", AppClient.TV, ELAPSED - 1, 5,
                WALL - 25 * HOUR)));

        Record record = book.quarantine("cell", AppClient.TV, ELAPSED, WALL);

        assertEquals(6, record.strikes);
        assertEquals(ELAPSED + 24 * HOUR, record.untilElapsedMs);
    }

    /**
     * Keyed on the transport, not the attachment: a reconnect is the same key. And each transport
     * keeps its own record, so Wi-Fi -> cell -> Wi-Fi never evicts what the next open depends on.
     */
    @Test
    public void transportsAreIndependentAndNeverEvictEachOther() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("wifi", AppClient.TV_DOWNGRADED, ELAPSED, WALL);

        assertTrue("never observed on cell", book.active("cell", ELAPSED).isEmpty());
        Record cell = book.quarantine("cell", AppClient.TV_DOWNGRADED, ELAPSED + MIN, WALL + MIN);

        assertEquals("cell starts its own count", 1, cell.strikes);
        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED),
                book.active("wifi", ELAPSED + 2 * MIN));
        assertTrue(book.active(null, ELAPSED).isEmpty());
    }

    @Test
    public void clientsAreIndependent() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("cell", AppClient.TV, ELAPSED, WALL);
        book.quarantine("cell", AppClient.TV, ELAPSED + 11 * MIN, WALL + 11 * MIN);

        Record downgraded = book.quarantine("cell", AppClient.TV_DOWNGRADED, ELAPSED + 12 * MIN,
                WALL + 12 * MIN);

        assertEquals(1, downgraded.strikes);
        assertEquals(2, book.active("cell", ELAPSED + 12 * MIN).size());
    }

    @Test
    public void pruneKeepsRememberedStrikesAndDropsForgottenOnes() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("cell", AppClient.TV, ELAPSED, WALL);

        assertFalse("expired but remembered", book.prune(ELAPSED + HOUR, WALL + HOUR));
        assertEquals(1, book.records().size());

        assertTrue(book.prune(ELAPSED + 49 * HOUR, WALL + 49 * HOUR));
        assertTrue(book.isEmpty());
    }

    @Test
    public void restoreNeverOverwritesWhatThisProcessAlreadyArmed() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("cell", AppClient.TV, ELAPSED, WALL);

        book.restore(Collections.singletonList(
                new Record("cell", AppClient.TV, ELAPSED + HOUR, 4, WALL - HOUR)));

        assertEquals(1, book.records().get(0).strikes);
    }

    // --- no-media streak and probation -------------------------------------------------------

    private static final String VIDEO_A = "a1";
    private static final String VIDEO_B = "b2";
    private static final String VIDEO_C = "c3";

    /** A clean record still needs two different videos: one hit is not a route verdict. */
    @Test
    public void aCleanClientIsQuarantinedOnlyByTheSecondDistinctVideo() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();

        NoMediaOutcome first = book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);
        assertEquals(NoMediaOutcome.Kind.COUNTED, first.kind);
        assertEquals(1, first.hits);
        assertTrue(book.active("wifi", ELAPSED).isEmpty());
        assertEquals(1, book.streaks().size());

        NoMediaOutcome second = book.noteNoMedia("wifi", AppClient.TV, VIDEO_B, ELAPSED + MIN,
                WALL + MIN);
        assertEquals(NoMediaOutcome.Kind.QUARANTINED, second.kind);
        assertFalse(second.probation);
        assertEquals(2, second.hits);
        assertEquals(1, second.record.strikes);
        assertEquals(ELAPSED + MIN + 10 * MIN, second.record.untilElapsedMs);
        assertEquals(Collections.singleton(AppClient.TV), book.active("wifi", ELAPSED + MIN));
        assertTrue("acted on, so no partial streak is left", book.streaks().isEmpty());
        assertEquals(Collections.singletonList(VIDEO_B), second.record.armingVideoKeys);
    }

    /** A reload or retry of the same video is one piece of evidence, not two. */
    @Test
    public void theSameVideoTwiceIsOneHit() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);

        NoMediaOutcome again = book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED + MIN,
                WALL + MIN);

        assertEquals(NoMediaOutcome.Kind.DUPLICATE, again.kind);
        assertEquals(1, again.hits);
        assertTrue(book.active("wifi", ELAPSED + MIN).isEmpty());
    }

    /** A hit from hours ago cannot combine with one today into a quarantine. */
    @Test
    public void aStaleStreakStartsOver() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);

        long later = WALL + AuthRouteQuarantineBook.NO_MEDIA_STREAK_MS + 1;
        NoMediaOutcome outcome = book.noteNoMedia("wifi", AppClient.TV, VIDEO_B, ELAPSED + 7 * HOUR,
                later);

        assertEquals(NoMediaOutcome.Kind.COUNTED, outcome.kind);
        assertEquals(1, outcome.hits);
        assertTrue(book.active("wifi", ELAPSED + 7 * HOUR).isEmpty());
    }

    /**
     * Probation: a client whose quarantine EXPIRED but whose strike is remembered is re-quarantined
     * by its first proven verdict - with escalation - instead of paying two more dead probes.
     */
    @Test
    public void anExpiredClientOnProbationIsReQuarantinedByItsFirstVerdictAndEscalates() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_B, ELAPSED, WALL); // strike 1, 10 min

        long elapsed = ELAPSED + 11 * MIN;
        long wall = WALL + 11 * MIN;
        assertTrue(book.active("wifi", elapsed).isEmpty());
        assertEquals(Collections.singleton(AppClient.TV), book.probation("wifi", elapsed, wall));

        NoMediaOutcome outcome = book.noteNoMedia("wifi", AppClient.TV, VIDEO_C, elapsed, wall);

        assertEquals(NoMediaOutcome.Kind.QUARANTINED, outcome.kind);
        assertTrue(outcome.probation);
        assertFalse(outcome.wasLive);
        assertEquals(1, outcome.previousStrikes);
        assertEquals(2, outcome.record.strikes);
        assertEquals(elapsed + 40 * MIN, outcome.record.untilElapsedMs);
        assertTrue(book.probation("wifi", elapsed, wall).isEmpty());
    }

    /**
     * Re-opening the video that armed the quarantine is the same evidence again: it must not
     * escalate a route that may have recovered everywhere but on that one video (a per-video SABR
     * rollout). A different video still does.
     */
    @Test
    public void theVideoThatArmedTheQuarantineIsNotProbationEvidence() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_B, ELAPSED, WALL);
        long elapsed = ELAPSED + 11 * MIN;
        long wall = WALL + 11 * MIN;

        NoMediaOutcome again = book.noteNoMedia("wifi", AppClient.TV, VIDEO_B, elapsed, wall);
        assertEquals(NoMediaOutcome.Kind.DUPLICATE, again.kind);
        assertTrue(book.active("wifi", elapsed).isEmpty());

        NoMediaOutcome other = book.noteNoMedia("wifi", AppClient.TV, VIDEO_C, elapsed, wall);
        assertEquals(NoMediaOutcome.Kind.QUARANTINED, other.kind);
        assertEquals(2, other.record.strikes);
        // ...and now C is the arming video.
        assertEquals(NoMediaOutcome.Kind.DUPLICATE, book.noteNoMedia("wifi", AppClient.TV,
                VIDEO_C, elapsed + 41 * MIN, wall + 41 * MIN).kind);
    }

    /**
     * The arming video is remembered as long as the strike - not the 6 h partial-streak window -
     * and a healthy answer from the client (which drops partial streaks) does not forget it.
     */
    @Test
    public void theArmingVideoIsRememberedForTheWholeStrikeMemory() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_B, ELAPSED, WALL);
        book.clearNoMedia("wifi", AppClient.TV); // TV then answered another video fine

        long elapsed = ELAPSED + 30 * HOUR;
        long wall = WALL + 30 * HOUR;
        assertEquals("30 h later: past the streak window, inside the strike memory",
                NoMediaOutcome.Kind.DUPLICATE,
                book.noteNoMedia("wifi", AppClient.TV, VIDEO_B, elapsed, wall).kind);
        assertTrue(book.active("wifi", elapsed).isEmpty());

        long forgotten = WALL + AuthRouteQuarantineBook.STRIKE_MEMORY_MS + 1;
        assertEquals("once the strike is forgotten, so is the video",
                NoMediaOutcome.Kind.COUNTED, book.noteNoMedia("wifi", AppClient.TV, VIDEO_B,
                        ELAPSED + 49 * HOUR, forgotten).kind);
    }

    /**
     * A live refresh by a DIFFERENT video must not make the original arming video fresh evidence
     * again: every arming video is kept while the strike is remembered (a media 403 adds none).
     */
    @Test
    public void aRefreshByAnotherVideoKeepsTheOriginalArmingVideo() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_B, ELAPSED, WALL); // armed by B
        NoMediaOutcome refresh = book.noteNoMedia("wifi", AppClient.TV, VIDEO_C,
                ELAPSED + MIN, WALL + MIN); // still live: refreshed by C
        assertTrue(refresh.wasLive);
        Record afterMedia403 = book.quarantine("wifi", AppClient.TV, ELAPSED + 2 * MIN,
                WALL + 2 * MIN);
        assertEquals(Arrays.asList(VIDEO_B, VIDEO_C), afterMedia403.armingVideoKeys);

        long elapsed = ELAPSED + 20 * MIN; // expired, remembered
        long wall = WALL + 20 * MIN;
        assertEquals(NoMediaOutcome.Kind.DUPLICATE,
                book.noteNoMedia("wifi", AppClient.TV, VIDEO_B, elapsed, wall).kind);
        assertEquals(NoMediaOutcome.Kind.DUPLICATE,
                book.noteNoMedia("wifi", AppClient.TV, VIDEO_C, elapsed, wall).kind);
        assertTrue(book.active("wifi", elapsed).isEmpty());
    }

    /** Bounded: the first arming video and the latest two. */
    @Test
    public void armingVideosAreBoundedToTheFirstAndTheLatestTwo() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        String[] videos = {"a1", "b2", "c3", "d4", "e5"};
        Record record = null;
        for (String video : videos) {
            record = book.quarantine("wifi", AppClient.TV, ELAPSED, WALL, video);
        }

        assertEquals(Arrays.asList("a1", "d4", "e5"), record.armingVideoKeys);
    }

    /** One hit under account A plus one under account B is not two failures of either. */
    @Test
    public void anAccountChangeDropsEveryStreakButNoRecord() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("wifi", AppClient.TV_DOWNGRADED, ELAPSED, WALL);
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);
        book.noteNoMedia("cell", AppClient.TV, VIDEO_A, ELAPSED, WALL);

        assertTrue(book.clearAllNoMedia());
        assertFalse(book.clearAllNoMedia());

        assertTrue(book.streaks().isEmpty());
        assertEquals(1, book.records().size());
        assertEquals(NoMediaOutcome.Kind.COUNTED,
                book.noteNoMedia("wifi", AppClient.TV, VIDEO_B, ELAPSED, WALL).kind);
    }

    /** A demoted client reached as a fallback and refusing again is the same episode. */
    @Test
    public void aVerdictWhileStillQuarantinedRefreshesWithoutEscalating() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("wifi", AppClient.TV, ELAPSED, WALL);

        NoMediaOutcome outcome = book.noteNoMedia("wifi", AppClient.TV, VIDEO_A,
                ELAPSED + 2 * MIN, WALL + 2 * MIN);

        assertEquals(NoMediaOutcome.Kind.QUARANTINED, outcome.kind);
        assertTrue(outcome.probation);
        assertTrue(outcome.wasLive);
        assertEquals(1, outcome.record.strikes);
        assertEquals(ELAPSED + 12 * MIN, outcome.record.untilElapsedMs);
    }

    /** A strike forgotten after two quiet days is a clean record again: back to two videos. */
    @Test
    public void forgottenStrikesMeanNoProbation() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("wifi", AppClient.TV, ELAPSED, WALL);

        long later = WALL + AuthRouteQuarantineBook.STRIKE_MEMORY_MS + 1;
        assertTrue(book.probation("wifi", ELAPSED + 50 * HOUR, later).isEmpty());
        NoMediaOutcome outcome = book.noteNoMedia("wifi", AppClient.TV, VIDEO_A,
                ELAPSED + 50 * HOUR, later);

        assertEquals(NoMediaOutcome.Kind.COUNTED, outcome.kind);
    }

    /** A media-403 strike puts the client on probation for the no-media path too. */
    @Test
    public void aMedia403StrikeAlsoPutsTheClientOnProbation() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("wifi", AppClient.TV_DOWNGRADED, ELAPSED, WALL);

        NoMediaOutcome outcome = book.noteNoMedia("wifi", AppClient.TV_DOWNGRADED, VIDEO_A,
                ELAPSED + 11 * MIN, WALL + 11 * MIN);

        assertEquals(NoMediaOutcome.Kind.QUARANTINED, outcome.kind);
        assertEquals(2, outcome.record.strikes);
    }

    /**
     * An empty answer carrying an age/sign-in gate is too ambiguous for probation (anonymous
     * WEB_EMBED can serve some age-gated videos the account may not): it joins the ordinary
     * two-video streak instead, and a second one escalates like any re-quarantine.
     */
    @Test
    public void gatedEvidenceNeverTriggersProbationButStillCounts() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("wifi", AppClient.TV_DOWNGRADED, ELAPSED, WALL);
        long elapsed = ELAPSED + 11 * MIN;
        long wall = WALL + 11 * MIN;
        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED),
                book.probation("wifi", elapsed, wall));

        NoMediaOutcome first = book.noteNoMedia("wifi", AppClient.TV_DOWNGRADED, VIDEO_A, false,
                elapsed, wall);
        assertEquals(NoMediaOutcome.Kind.COUNTED, first.kind);
        assertTrue(book.active("wifi", elapsed).isEmpty());

        NoMediaOutcome second = book.noteNoMedia("wifi", AppClient.TV_DOWNGRADED, VIDEO_B, false,
                elapsed, wall);
        assertEquals(NoMediaOutcome.Kind.QUARANTINED, second.kind);
        assertFalse(second.probation);
        assertEquals(1, second.previousStrikes);
        assertEquals(2, second.record.strikes);
    }

    /** An armed quarantine consumes the partial streak that preceded it. */
    @Test
    public void aQuarantineDropsThePartialStreak() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);

        book.quarantine("wifi", AppClient.TV, ELAPSED + MIN, WALL + MIN); // e.g. a media 403

        assertTrue(book.streaks().isEmpty());
    }

    /** A healthy answer drops the partial streak - and only it: strikes decay on time alone. */
    @Test
    public void clearingDropsTheStreakButNeverTheStrikes() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.quarantine("wifi", AppClient.TV_DOWNGRADED, ELAPSED, WALL);
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);
        assertTrue(book.hasNoMediaStreak(AppClient.TV));

        assertFalse("other transport", book.clearNoMedia("cell", AppClient.TV));
        assertFalse("no network", book.clearNoMedia(null, AppClient.TV));
        assertTrue(book.clearNoMedia("wifi", AppClient.TV));
        assertFalse("already gone", book.clearNoMedia("wifi", AppClient.TV));
        assertFalse(book.hasNoMediaStreak(AppClient.TV));
        assertFalse(book.clearNoMedia("wifi", AppClient.TV_DOWNGRADED));
        assertEquals(1, book.records().size());
    }

    /** Keyed like the quarantine: a Wi-Fi hit and a cellular hit are not a streak. */
    @Test
    public void streaksAreKeptPerTransport() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);

        NoMediaOutcome cell = book.noteNoMedia("cell", AppClient.TV, VIDEO_B, ELAPSED, WALL);

        assertEquals(NoMediaOutcome.Kind.COUNTED, cell.kind);
        assertEquals(1, cell.hits);
        assertEquals(2, book.streaks().size());
        assertTrue(book.active("wifi", ELAPSED).isEmpty());
        assertTrue(book.active("cell", ELAPSED).isEmpty());
    }

    @Test
    public void pruneDropsStaleStreaks() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);

        assertFalse(book.prune(ELAPSED + HOUR, WALL + HOUR));
        assertFalse(book.isEmpty());
        assertTrue(book.prune(ELAPSED + 7 * HOUR, WALL + 7 * HOUR));
        assertTrue(book.isEmpty());
    }

    /**
     * Restored streaks seed the book and never override what this process counted, nor survive a
     * quarantine this process has since armed for the same client (that consumed the streak).
     */
    @Test
    public void restoredStreaksNeverOverrideThisProcess() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);
        book.quarantine("wifi", AppClient.TV_DOWNGRADED, ELAPSED, WALL);

        book.restore(Collections.singletonList(
                        new Record("cell", AppClient.TV_DOWNGRADED, ELAPSED - 1, 1, WALL - HOUR)),
                Arrays.asList(
                        new Streak("wifi", AppClient.TV, 1, VIDEO_B, WALL - MIN),
                        new Streak("wifi", AppClient.TV_DOWNGRADED, 1, VIDEO_B, WALL - MIN),
                        new Streak("cell", AppClient.TV_DOWNGRADED, 1, VIDEO_B, WALL - MIN),
                        new Streak("cell", AppClient.TV, 1, VIDEO_C, WALL - MIN)));

        // wifi/TV (this process's own), cell/TV_DOWNGRADED (beside a RESTORED record: a gated
        // streak may legitimately sit there) and cell/TV; wifi/TV_DOWNGRADED was consumed here.
        assertEquals(3, book.streaks().size());
        NoMediaOutcome duplicate = book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL);
        assertEquals("this process's own last video still dedupes",
                NoMediaOutcome.Kind.DUPLICATE, duplicate.kind);
        NoMediaOutcome restored = book.noteNoMedia("cell", AppClient.TV, VIDEO_A, ELAPSED, WALL);
        assertEquals("a restored hit counts toward the streak",
                NoMediaOutcome.Kind.QUARANTINED, restored.kind);
    }

    @Test
    public void describeStreaksIsACredentialFreeOneLiner() {
        AuthRouteQuarantineBook book = new AuthRouteQuarantineBook();
        assertEquals("none", book.describeStreaks(WALL));

        book.noteNoMedia("wifi", AppClient.TV, VIDEO_A, ELAPSED, WALL - 312_000);

        assertEquals("wifi/TV:h1:age312s", book.describeStreaks(WALL));
    }
}
