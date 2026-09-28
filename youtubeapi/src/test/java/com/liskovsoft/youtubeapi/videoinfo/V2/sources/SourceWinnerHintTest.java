package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.Test;

public class SourceWinnerHintTest {
    private static final class FakePrefs implements SourceWinnerHint.Prefs {
        String stored;
        int ordinal;
        int ordinalReads;

        FakePrefs(String stored, int ordinal) {
            this.stored = stored;
            this.ordinal = ordinal;
        }

        @Override public String get() {
            return stored;
        }

        @Override public void put(String id) {
            stored = id;
        }

        @Override public int legacyOrdinal() {
            ordinalReads++;
            return ordinal;
        }
    }

    @Test
    public void aPersistedOrdinalIsMigratedOnceThroughTheFrozenTable() {
        FakePrefs prefs = new FakePrefs(null, 19); // VISIONOS in the 2026-09-28 enum
        assertSame(PlayerSourceCatalog.defaultFor(AppClient.VISIONOS), SourceWinnerHint.read(prefs));
        assertEquals("VISIONOS@1", prefs.stored);

        prefs.ordinal = 5; // whatever the old field says later is never read again
        assertSame(PlayerSourceCatalog.defaultFor(AppClient.VISIONOS), SourceWinnerHint.read(prefs));
        assertEquals(1, prefs.ordinalReads);
    }

    @Test
    public void noOrdinalOrAnOutOfRangeOneMeansNoHintForGood() {
        for (int ordinal : new int[] {-1, 21, 99}) {
            FakePrefs prefs = new FakePrefs(null, ordinal);
            assertNull(SourceWinnerHint.read(prefs));
            assertEquals(SourceWinnerHint.NONE, prefs.stored);
            assertNull(SourceWinnerHint.read(prefs));
            assertEquals(1, prefs.ordinalReads);
        }
    }

    @Test
    public void aClearedHintDoesNotBringTheOrdinalBack() {
        FakePrefs prefs = new FakePrefs(null, 19);
        SourceWinnerHint.write(prefs, null);
        assertNull(SourceWinnerHint.read(prefs));
        assertEquals(0, prefs.ordinalReads);
    }

    @Test
    public void anUnknownIdIsNoHint() {
        FakePrefs prefs = new FakePrefs("VISIONOS@7", 19);
        assertNull(SourceWinnerHint.read(prefs));
        assertEquals(0, prefs.ordinalReads);
    }

    @Test
    public void theWinnerIsStoredByItsSourceId() {
        FakePrefs prefs = new FakePrefs(null, -1);
        SourceWinnerHint.write(prefs, AppClient.ANDROID_REEL);
        assertEquals("ANDROID_REEL@1", prefs.stored);
        assertSame(PlayerSourceCatalog.defaultFor(AppClient.ANDROID_REEL), SourceWinnerHint.read(prefs));
    }

    /** Every name in the frozen table is a client this build knows, so no persisted value is lost. */
    @Test
    public void theFrozenTableNamesKnownClients() {
        for (String name : SourceWinnerHint.LEGACY_ORDINALS) {
            AppClient client = AppClient.valueOf(name);
            assertEquals(name + "@1", PlayerSourceCatalog.defaultFor(client).id);
        }
    }
}
