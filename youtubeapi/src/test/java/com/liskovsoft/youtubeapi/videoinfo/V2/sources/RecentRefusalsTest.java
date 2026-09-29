package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PhoneSourcePlanner.Lane.SIGNED_IN;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PhoneSourcePlanner.Lane.SIGNED_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.Test;

import java.util.Collections;
import java.util.EnumSet;

/** NEWTUBE(recovery-refusals): the memory on its own - time, identity, a serve, the bounds. */
public class RecentRefusalsTest {
    private final RecentRefusals refusals = new RecentRefusals();

    @Test
    public void aRefusalHoldsForTheTtlThenLapses() {
        refused("kids", AppClient.VISIONOS, false, 1_000);
        assertEquals(Collections.singletonMap(AppClient.VISIONOS, 500L),
                refusals.recent("kids", SIGNED_OUT, 1_500));
        assertEquals(Collections.singletonMap(AppClient.VISIONOS, RecentRefusals.TTL_MS - 1),
                refusals.recent("kids", SIGNED_OUT, 1_000 + RecentRefusals.TTL_MS - 1));
        assertTrue(refusals.recent("kids", SIGNED_OUT, 1_000 + RecentRefusals.TTL_MS).isEmpty());
        assertTrue("another video", refusals.recent("other", SIGNED_OUT, 1_500).isEmpty());
        assertTrue(refusals.recent(null, SIGNED_OUT, 1_500).isEmpty());
    }

    /** The account route is anonymous signed out and carries the account signed in. */
    @Test
    public void aRefusalCountsOnlyForTheIdentityThatHeardIt() {
        refused("v", AppClient.TV_TIZEN, false, 0);
        assertEquals(Collections.singleton(AppClient.TV_TIZEN), refusals.recent("v", SIGNED_OUT, 1).keySet());
        assertTrue("the account was never heard", refusals.recent("v", SIGNED_IN, 1).isEmpty());

        refused("w", AppClient.TV_TIZEN, true, 0);
        refused("w", AppClient.VISIONOS, false, 0);
        assertEquals(EnumSet.of(AppClient.TV_TIZEN, AppClient.VISIONOS), refusals.recent("w", SIGNED_IN, 1).keySet());
        assertEquals(Collections.singleton(AppClient.VISIONOS), refusals.recent("w", SIGNED_OUT, 1).keySet());
    }

    @Test
    public void aServeForgetsThatSourcesRefusal() {
        refused("v", AppClient.WEB_EMBED, false, 0);
        refused("v", AppClient.VISIONOS, false, 0);
        refusals.noteServed("v", AppClient.WEB_EMBED);
        assertEquals(Collections.singleton(AppClient.VISIONOS), refusals.recent("v", SIGNED_OUT, 1).keySet());
        refusals.noteServed("v", AppClient.VISIONOS);
        assertTrue(refusals.recent("v", SIGNED_OUT, 1).isEmpty());
        refusals.noteServed("never-refused", AppClient.VISIONOS);
        refusals.noteServed(null, null);
    }

    @Test
    public void itHoldsSixteenVideosAndAnAccountChangeForgetsThem() {
        for (int i = 0; i < RecentRefusals.VIDEO_CAPACITY; i++) {
            refused("v" + i, AppClient.VISIONOS, false, 0);
        }
        refusals.recent("v0", SIGNED_OUT, 1); // used: the eldest is now v1
        refused("new", AppClient.VISIONOS, false, 0);
        assertEquals(1, refusals.recent("v0", SIGNED_OUT, 1).size());
        assertTrue(refusals.recent("v1", SIGNED_OUT, 1).isEmpty());
        assertEquals(1, refusals.recent("new", SIGNED_OUT, 1).size());

        assertEquals(RecentRefusals.VIDEO_CAPACITY, refusals.clear());
        assertTrue(refusals.recent("new", SIGNED_OUT, 1).isEmpty());
    }

    /** A walk that began before an account change cannot write: its identity may be the old one's. */
    @Test
    public void aWalkFromBeforeAnAccountChangeWritesNothing() {
        long before = refusals.generation();
        refusals.clear();
        refusals.noteRefused("v", AppClient.TV_TIZEN, true, 0, before);
        assertTrue(refusals.recent("v", SIGNED_IN, 1).isEmpty());
        refusals.noteRefused("v", AppClient.TV_TIZEN, true, 0, refusals.generation());
        assertEquals(Collections.singleton(AppClient.TV_TIZEN), refusals.recent("v", SIGNED_IN, 1).keySet());
    }

    /** NEWTUBE(recovery-kids): a made-for-kids refusal is remembered as such, for its TTL. */
    @Test
    public void aMadeForKidsRefusalIsKnownForTheTtl() {
        refused("v", AppClient.WEB_EMBED, false, 0);
        assertFalse("an ordinary refusal", refusals.hasMadeForKids("v", 1));
        refusals.noteRefused("v", AppClient.VISIONOS, false, 1_000, refusals.generation(), true);
        assertTrue(refusals.hasMadeForKids("v", 2_000));
        assertFalse(refusals.hasMadeForKids("v", 1_000 + RecentRefusals.TTL_MS));
        assertFalse("another video", refusals.hasMadeForKids("w", 2_000));
        refusals.noteServed("v", AppClient.VISIONOS);
        assertFalse("VISIONOS served it since", refusals.hasMadeForKids("v", 2_000));
    }

    private void refused(String videoId, AppClient client, boolean auth, long nowMs) {
        refusals.noteRefused(videoId, client, auth, nowMs, refusals.generation());
    }
}
