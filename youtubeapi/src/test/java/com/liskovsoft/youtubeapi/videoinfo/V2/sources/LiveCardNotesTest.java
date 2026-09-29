package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** NEWTUBE(live-card): the notes on their own - a note, a later "not live", the bound. */
public class LiveCardNotesTest {
    private final LiveCardNotes notes = new LiveCardNotes();

    @Test
    public void aLiveItemIsNotedAndANotLiveOneTakesItBack() {
        assertTrue("a change", notes.note("v", true));
        assertTrue(notes.isLive("v"));
        assertFalse("the same again is no change", notes.note("v", true));
        assertTrue(notes.note("v", false));
        assertFalse(notes.isLive("v"));
        assertFalse("never noted: nothing to take back", notes.note("w", false));
        assertFalse(notes.note(null, true));
        assertFalse(notes.isLive(null));
    }

    @Test
    public void itHoldsThirtyTwoVideos() {
        for (int i = 0; i < LiveCardNotes.CAPACITY; i++) {
            notes.note("v" + i, true);
        }
        notes.isLive("v0");
        notes.note("v0", true); // noted again: the eldest is now v1
        notes.note("new", true);
        assertTrue(notes.isLive("v0"));
        assertFalse(notes.isLive("v1"));
        assertTrue(notes.isLive("new"));
        notes.clear();
        assertFalse(notes.isLive("new"));
    }
}
