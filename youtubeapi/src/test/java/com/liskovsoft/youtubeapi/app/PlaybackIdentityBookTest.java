package com.liskovsoft.youtubeapi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;

import org.junit.Test;

/** NEWTUBE(playback-identity): the re-roll budget and the kept identity, persisted. */
public class PlaybackIdentityBookTest {
    private static final long HOUR = 60 * 60 * 1000L;
    private final MemoryStore store = new MemoryStore();

    private static final class MemoryStore implements PlaybackIdentityBook.Store {
        @Nullable
        String snapshot;

        @Nullable
        @Override
        public String load() {
            return snapshot;
        }

        @Override
        public void save(@Nullable String snapshot) {
            this.snapshot = snapshot;
        }
    }

    private PlaybackIdentityBook book() {
        PlaybackIdentityBook book = new PlaybackIdentityBook();
        book.setStore(store);
        return book;
    }

    /** Two per 6 h by default; a spent budget spends nothing. */
    @Test
    public void theBudgetIsTwoPerSixHours() {
        PlaybackIdentityBook book = book();
        long now = 100 * HOUR;
        assertEquals(2, book.budgetLeft(now));
        assertTrue(book.spend(now));
        assertTrue(book.spend(now + HOUR));
        assertEquals(0, book.budgetLeft(now + HOUR));
        assertFalse("spent", book.spend(now + 2 * HOUR));
        // The first re-roll leaves the window after 6 h, the second an hour later.
        assertEquals(1, book.budgetLeft(now + 6 * HOUR));
        assertEquals(2, book.budgetLeft(now + 7 * HOUR));
    }

    /** A restart does not refill it: the spends are on disk. */
    @Test
    public void theBudgetSurvivesARestart() {
        long now = 100 * HOUR;
        PlaybackIdentityBook first = book();
        first.spend(now);
        first.spend(now + 1);
        PlaybackIdentityBook restarted = book();
        assertEquals(0, restarted.budgetLeft(now + HOUR));
        assertFalse(restarted.spend(now + HOUR));
    }

    @Test
    public void anotherBudgetCanBeSet() {
        PlaybackIdentityBook book = book();
        book.setBudget(1);
        assertTrue(book.spend(0));
        assertFalse(book.spend(1));
        book.setBudget(0);
        assertEquals(0, book.budgetLeft(7 * HOUR));
    }

    /** The kept identity lasts the window from its re-roll, persisted, and can be dropped. */
    @Test
    public void aKeptIdentityLastsSixHours() {
        long now = 100 * HOUR;
        PlaybackIdentityBook book = book();
        assertNull(book.kept(now));
        book.keep("fresh-visitor", now);
        assertEquals("fresh-visitor", book.kept(now + 5 * HOUR));
        assertEquals("fresh-visitor", book().kept(now + 5 * HOUR)); // a restart restores it
        assertNull(book.kept(now + 6 * HOUR));
        assertNull("expired, and removed from disk", book().kept(now + HOUR));

        book.keep("again", now);
        assertTrue(book.dropKept());
        assertNull(book.kept(now));
        assertFalse(book.dropKept());
    }

    /** A damaged or foreign snapshot restores nothing and fails nothing. */
    @Test
    public void aDamagedSnapshotRestoresNothing() {
        store.snapshot = "{not json";
        assertEquals(2, book().budgetLeft(0));
        store.snapshot = "{\"v\":9,\"rerolls\":[1,2,3]}";
        assertEquals(2, book().budgetLeft(3));
    }

    /**
     * A re-roll armed but not minted survives a restart (the wall's recovery went to TV_TIZEN, the
     * web session was never rebuilt before the process died), is taken once, and lapses.
     */
    @Test
    public void aPendingReRollSurvivesARestart() {
        PlaybackIdentityBook first = book();
        assertNull(first.pending(10));
        first.setPending("v1", "walledfp", 1, 10);
        PlaybackIdentityBook second = book();
        PlaybackIdentityBook.Pending pending = second.pending(20);
        assertEquals("v1", pending.videoId);
        assertEquals("walledfp", pending.fromFingerprint);
        assertEquals(1, pending.budgetLeft);
        assertEquals("v1", second.takePending(30).videoId);
        assertNull(second.takePending(40));
        assertNull("taken: gone from the store too", book().pending(50));

        book().setPending("v2", "fp", 0, 10);
        assertNull("lapsed", book().pending(10 + PlaybackIdentityBook.WINDOW_MS));
    }
}
