package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.AuthRouteQuarantineBook.Record;

import org.junit.Test;

import java.util.Collections;

/** Offline: escalation, strike memory and transport keying of the account-route quarantine. */
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
}
