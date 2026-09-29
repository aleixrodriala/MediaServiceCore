package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PhoneSourcePlanner.Lane.SIGNED_IN;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PhoneSourcePlanner.Lane.SIGNED_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** NEWTUBE(kids-channel): the memory on its own - bounds, lanes, the account, the hint budget. */
public class KidsChannelMemoryTest {
    private final KidsChannelMemory memory = new KidsChannelMemory();

    @Test
    public void aProvenChannelLeadsWithTheAccountRouteInItsLaneOnly() {
        assertEquals(KidsChannelMemory.Hint.NONE, memory.hintFor(SIGNED_OUT, "UCkids"));
        assertTrue(memory.remember(SIGNED_OUT, "UCkids", memory.generation()));
        assertEquals(KidsChannelMemory.Hint.FIRST, memory.hintFor(SIGNED_OUT, "UCkids"));
        assertEquals("a signed-out record says nothing signed in",
                KidsChannelMemory.Hint.NONE, memory.hintFor(SIGNED_IN, "UCkids"));

        assertTrue(memory.remember(SIGNED_IN, "UCother", memory.generation()));
        assertEquals("and the other way round",
                KidsChannelMemory.Hint.NONE, memory.hintFor(SIGNED_OUT, "UCother"));
        assertEquals(KidsChannelMemory.Hint.NONE, memory.hintFor(SIGNED_OUT, null));
    }

    @Test
    public void itHoldsSixtyFourChannelsAndForgetsTheLeastRecentlyUsed() {
        long generation = memory.generation();
        for (int i = 0; i < KidsChannelMemory.CAPACITY; i++) {
            memory.remember(SIGNED_OUT, "UC" + i, generation);
        }
        memory.hintFor(SIGNED_OUT, "UC0"); // used: the eldest is now UC1
        memory.remember(SIGNED_OUT, "UCnew", generation);
        assertTrue(memory.isRemembered(SIGNED_OUT, "UC0"));
        assertFalse(memory.isRemembered(SIGNED_OUT, "UC1"));
        assertTrue(memory.isRemembered(SIGNED_OUT, "UC2"));
        assertTrue(memory.isRemembered(SIGNED_OUT, "UCnew"));
    }

    @Test
    public void anAccountChangeForgetsEverythingAndWalksBeforeItCannotWrite() {
        long before = memory.generation();
        memory.remember(SIGNED_OUT, "UCkids", before);
        memory.noteVideoChannel("v1", "UCkids");
        memory.rememberWhenNamed("v2", SIGNED_OUT, before);

        assertEquals(1, memory.clear());
        assertFalse(memory.isRemembered(SIGNED_OUT, "UCkids"));
        assertNull("the names go too", memory.channelOf("v1"));
        assertFalse("a walk that began before the change", memory.remember(SIGNED_OUT, "UCkids", before));
        assertNull("its waiting proof is gone", memory.noteVideoChannel("v2", "UCkids"));
        assertFalse(memory.isRemembered(SIGNED_OUT, "UCkids"));

        assertTrue(memory.remember(SIGNED_OUT, "UCkids", memory.generation()));
    }

    @Test
    public void aProofBuysAFixedNumberOfHintsThenARecheck() {
        memory.remember(SIGNED_OUT, "UCkids", memory.generation());
        long generation = memory.generation();
        for (int i = KidsChannelMemory.HINTS_PER_PROOF - 1; i >= 0; i--) {
            assertEquals(KidsChannelMemory.Hint.FIRST, memory.hintFor(SIGNED_OUT, "UCkids"));
            assertEquals(i, memory.spendHint(SIGNED_OUT, "UCkids", generation));
        }
        assertEquals(KidsChannelMemory.Hint.REPROOF, memory.hintFor(SIGNED_OUT, "UCkids"));
        assertEquals("never below zero", 0, memory.spendHint(SIGNED_OUT, "UCkids", generation));

        memory.remember(SIGNED_OUT, "UCkids", memory.generation()); // proved again
        assertEquals(KidsChannelMemory.HINTS_PER_PROOF, memory.hintsLeft(SIGNED_OUT, "UCkids"));
        assertEquals(-1, memory.spendHint(SIGNED_OUT, "UCunknown", generation));
    }

    /** A walk from before an account change neither spends nor drops the new account's records. */
    @Test
    public void aWalkFromBeforeAnAccountChangeTouchesNothing() {
        long before = memory.generation();
        memory.clear();
        memory.remember(SIGNED_OUT, "UCkids", memory.generation());
        assertEquals(-1, memory.spendHint(SIGNED_OUT, "UCkids", before));
        assertFalse(memory.drop(SIGNED_OUT, "UCkids", before));
        assertEquals(KidsChannelMemory.HINTS_PER_PROOF, memory.hintsLeft(SIGNED_OUT, "UCkids"));
    }

    @Test
    public void aDropForgetsTheChannelInThatLane() {
        long generation = memory.generation();
        memory.remember(SIGNED_OUT, "UCkids", generation);
        memory.remember(SIGNED_IN, "UCkids", generation);
        assertTrue(memory.drop(SIGNED_OUT, "UCkids", generation));
        assertFalse(memory.drop(SIGNED_OUT, "UCkids", generation));
        assertEquals(KidsChannelMemory.Hint.NONE, memory.hintFor(SIGNED_OUT, "UCkids"));
        assertTrue(memory.isRemembered(SIGNED_IN, "UCkids"));
        assertFalse(memory.drop(SIGNED_OUT, null, generation));
    }

    @Test
    public void theAppNamesAVideosChannelAndTheLatestNameWins() {
        assertNull(memory.channelOf("v1"));
        assertNull(memory.noteVideoChannel("v1", "UCcard"));
        assertEquals("UCcard", memory.channelOf("v1"));
        memory.noteVideoChannel("v1", "UCnext");
        assertEquals("UCnext", memory.channelOf("v1"));
        memory.noteVideoChannel("v1", null);
        memory.noteVideoChannel("v1", "");
        assertEquals("an empty name changes nothing", "UCnext", memory.channelOf("v1"));
        memory.noteVideoChannel(null, "UCx");
        assertNull(memory.channelOf(null));
    }

    @Test
    public void videoNamesAreBounded() {
        for (int i = 0; i <= KidsChannelMemory.VIDEO_CAPACITY; i++) {
            memory.noteVideoChannel("v" + i, "UC" + i);
        }
        assertNull(memory.channelOf("v0"));
        assertEquals("UC1", memory.channelOf("v1"));
    }

    /** A proof whose answers named no channel is kept on the video until the app names it (/next). */
    @Test
    public void aProofWithoutAChannelWaitsForItsName() {
        memory.rememberWhenNamed("v1", SIGNED_OUT, memory.generation());
        assertEquals(KidsChannelMemory.Hint.NONE, memory.hintFor(SIGNED_OUT, "UCkids"));
        assertEquals(SIGNED_OUT, memory.noteVideoChannel("v1", "UCkids"));
        assertEquals(KidsChannelMemory.Hint.FIRST, memory.hintFor(SIGNED_OUT, "UCkids"));
        assertNull("once", memory.noteVideoChannel("v1", "UCkids"));
    }

    @Test
    public void theLogTagIsAShortHashNotTheId() {
        String tag = KidsChannelMemory.tag("UCB_5Rmp-wUdVxmCLG6fnNkQ");
        assertTrue(tag, tag.matches("[0-9a-f]{10}"));
        assertEquals(tag, KidsChannelMemory.tag("UCB_5Rmp-wUdVxmCLG6fnNkQ"));
        assertFalse(tag.equals(KidsChannelMemory.tag("UCbCmjCuTUZos6Inko4u57UQ")));
        assertEquals("none", KidsChannelMemory.tag(null));
    }
}
