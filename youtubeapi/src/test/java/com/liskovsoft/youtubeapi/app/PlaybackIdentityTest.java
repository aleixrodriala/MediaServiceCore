package com.liskovsoft.youtubeapi.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * NEWTUBE(playback-identity): the session build completes a re-roll; keeping (the second switch)
 * makes the fresh visitor the playback identity for the next session builds.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public class PlaybackIdentityTest {
    @Before
    public void setUp() {
        PlaybackIdentity.resetForTest();
    }

    @After
    public void tearDown() {
        PlaybackIdentity.resetForTest();
    }

    /** Keeping off (the default): the fresh visitor lives only as long as that session. */
    @Test
    public void withoutKeepingNothingIsKept() {
        PlaybackIdentity.arm("v1", "old-visitor", 1);
        PlaybackIdentity.onFreshVisitorAdopted("fresh-visitor");
        assertNull(PlaybackIdentity.keptVisitor());
        assertNull("nothing on disk either", PlaybackIdentity.book().kept(System.currentTimeMillis()));
    }

    @Test
    public void keepingMakesTheFreshVisitorThePlaybackIdentity() {
        PlaybackIdentity.setKeepEnabled(true);
        PlaybackIdentity.arm("v1", "old-visitor", 1);
        PlaybackIdentity.onFreshVisitorAdopted("fresh-visitor");
        assertEquals("fresh-visitor", PlaybackIdentity.keptVisitor());
        // It met the wall again: dropped, back to the app's visitor.
        PlaybackIdentity.dropKept();
        assertNull(PlaybackIdentity.keptVisitor());
    }

    /** A rotation nobody armed for a wall (the dormant challenge rotation) keeps nothing. */
    @Test
    public void anUnarmedRotationKeepsNothing() {
        PlaybackIdentity.setKeepEnabled(true);
        PlaybackIdentity.onFreshVisitorAdopted("fresh-visitor");
        assertNull(PlaybackIdentity.keptVisitor());
    }

    /** The visitor_id API failed: the app's visitor was adopted again; nothing to keep. */
    @Test
    public void noFreshVisitorNoKeep() {
        PlaybackIdentity.setKeepEnabled(true);
        long now = System.currentTimeMillis();
        assertTrue(PlaybackIdentity.book().spend(now));
        PlaybackIdentity.arm("v1", "old-visitor", 1);
        PlaybackIdentity.onFreshVisitorAdopted(null);
        assertNull(PlaybackIdentity.keptVisitor());
        assertEquals("nothing re-rolled, nothing spent", 2, PlaybackIdentity.book().budgetLeft(now));
        assertFalse("taken", PlaybackIdentity.isRerollPending());
    }

    /** Switching keeping off forgets a kept identity. */
    @Test
    public void switchingKeepingOffForgetsIt() {
        PlaybackIdentity.setKeepEnabled(true);
        PlaybackIdentity.arm("v1", "old-visitor", 1);
        PlaybackIdentity.onFreshVisitorAdopted("fresh-visitor");
        PlaybackIdentity.setKeepEnabled(false);
        PlaybackIdentity.setKeepEnabled(true);
        assertNull(PlaybackIdentity.keptVisitor());
    }

    /**
     * The background mint (v22b): minted right after the wall, it completes the re-roll and keeps the
     * fresh visitor, so no later session build has anything to mint; a session that got there first
     * wins, and a failed mint leaves the re-roll pending for the session build.
     */
    @Test
    public void aBackgroundMintCompletesTheReRoll() {
        PlaybackIdentity.setKeepEnabled(true);
        PlaybackIdentity.arm("v1", "old-visitor", 1);
        assertFalse("failed mint", PlaybackIdentity.onFreshVisitorMinted(null));
        assertTrue("still pending", PlaybackIdentity.isRerollPending());
        assertTrue(PlaybackIdentity.onFreshVisitorMinted("fresh-visitor"));
        assertFalse(PlaybackIdentity.isRerollPending());
        assertEquals("fresh-visitor", PlaybackIdentity.keptVisitor());
        assertFalse("nothing left to complete", PlaybackIdentity.onFreshVisitorMinted("second"));
        assertEquals("fresh-visitor", PlaybackIdentity.keptVisitor());

        PlaybackIdentity.arm("v2", "fresh-visitor", 0);
        PlaybackIdentity.onFreshVisitorAdopted("third"); // the session build got there first
        assertFalse(PlaybackIdentity.onFreshVisitorMinted("late"));
        assertEquals("third", PlaybackIdentity.keptVisitor());

        PlaybackIdentity.setKeepEnabled(false);
        PlaybackIdentity.arm("v3", "third", 0);
        assertFalse("keeping off: the session build mints", PlaybackIdentity.onFreshVisitorMinted("x"));
        assertTrue(PlaybackIdentity.isRerollPending());
    }
}
