package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService.BotCheckWalkState;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * The state machine behind P1: what a /player walk does when an anonymous client answers with a
 * bot challenge. Replays the 2026-09-07 Rusowsky failure (Fo89b8zAIE4, Pixel 9, cell) - the walk
 * used to trip the circuit and RETURN at attempt 3 of 10, before ANDROID_VR, which served the same
 * account on the same network minutes later (aqz-KE-bpKQ, playable=y, 28 usable formats).
 * <p>
 * These tests are written against the state machine's OUTPUTS, so restoring the old
 * abort-and-return behaviour fails them. The convention they encode, which mirrors the loop:
 * {@code recordChallenge} returning false is the caller's instruction to trip and return NOW;
 * returning true means the verdict is held and the walk continues; {@code finish} returning
 * non-null is the only other way the circuit ever arms.
 */
public class BotCheckWalkStateTest {
    private static final boolean MOBILE = true;
    private static final boolean TV = false;
    private static final boolean AUTHED = true;
    private static final boolean NO_TRANSPORT_FAILURE = false;

    /** The order the signed-in Rusowsky walk actually used, trimmed to the clients that matter. */
    private static final List<AppClient> RUSOWSKY_ORDER = Arrays.asList(
            AppClient.TV_DOWNGRADED,   // 0 - UNPLAYABLE, reload page
            AppClient.TV,              // 1 - UNPLAYABLE, reload page
            AppClient.WEB_EMBED,       // 2 - LOGIN_REQUIRED, "...no eres un bot"
            AppClient.ANDROID_VR,      // 3 - never reached; the client that works
            AppClient.IOS);            // 4

    private static final int CHALLENGED_AT = 2;

    /**
     * 1. A challenge mid-ring with a non-web client behind it: the walk carries on, nothing is
     * published yet, and the circuit is not armed.
     */
    @Test
    public void midRingChallengeWalksOnWithoutArmingTheCircuit() {
        BotCheckWalkState state = new BotCheckWalkState();
        VideoInfo challenge = new VideoInfo();

        boolean walkOn = state.recordChallenge(challenge, AppClient.WEB_EMBED, "explicit",
                AUTHED, RUSOWSKY_ORDER, CHALLENGED_AT, MOBILE);

        assertTrue("ANDROID_VR is still behind WEB_EMBED - the walk must not stop here", walkOn);
        assertTrue("the verdict is held, not thrown away", state.hasHeldChallenge());
    }

    /**
     * 2. ...and when a later client serves the video, the held challenge is discarded: the user
     * gets their video and the circuit never arms.
     */
    @Test
    public void aLaterPlayableClientDiscardsTheHeldChallenge() {
        BotCheckWalkState state = new BotCheckWalkState();

        assertTrue(state.recordChallenge(new VideoInfo(), AppClient.WEB_EMBED, "explicit",
                AUTHED, RUSOWSKY_ORDER, CHALLENGED_AT, MOBILE));

        // ANDROID_VR answers playable=y, exactly as it did for aqz-KE-bpKQ.
        state.discardOnPlayable();

        assertFalse(state.hasHeldChallenge());
        assertNull("a walk that ended in playback must never arm the circuit",
                state.finish(NO_TRANSPORT_FAILURE));
    }

    /**
     * 3. ...but when the ring then ends with nothing playable, the HELD challenge is what gets
     * published - the user still sees YouTube's own reason rather than a bare "could not load" -
     * and the circuit arms with ringExhausted, because every client was asked.
     */
    @Test
    public void exhaustedRingPublishesTheHeldChallengeAndArmsTheCircuit() {
        BotCheckWalkState state = new BotCheckWalkState();
        VideoInfo challenge = new VideoInfo();

        assertTrue(state.recordChallenge(challenge, AppClient.WEB_EMBED, "explicit",
                AUTHED, RUSOWSKY_ORDER, CHALLENGED_AT, MOBILE));

        BotCheckWalkState.Outcome outcome = state.finish(NO_TRANSPORT_FAILURE);

        assertNotNull("the walk learned something and must publish it", outcome);
        assertSame("the reason shown is the challenge the server actually stated",
                challenge, outcome.result);
        assertEquals(AppClient.WEB_EMBED, outcome.client);
        assertEquals("explicit", outcome.signal);
        assertTrue(outcome.authAttempted);
        assertTrue("every client was asked, so suppression is earned", outcome.ringExhausted);
    }

    /**
     * 4. A challenge on the FINAL client has nothing behind it: no walk-on, and the caller trips
     * immediately with the ring genuinely exhausted.
     */
    @Test
    public void challengeOnTheFinalClientTripsImmediately() {
        BotCheckWalkState state = new BotCheckWalkState();

        boolean walkOn = state.recordChallenge(new VideoInfo(), AppClient.IOS, "explicit",
                AUTHED, RUSOWSKY_ORDER, RUSOWSKY_ORDER.size() - 1, MOBILE);

        assertFalse("nothing is left to try - trip and return now", walkOn);
        assertTrue("and the ring really was finished", state.ringExhausted());
    }

    /**
     * 5. A walk cut short (cancel, walk budget, dead link) still publishes the reason per the
     * existing rules, but must NOT arm P1b's suppression: the clients it never reached establish
     * nothing, and silencing the next fifteen minutes on that is the bug this whole change exists
     * to remove.
     */
    @Test
    public void aWalkCutShortPublishesTheReasonButDoesNotEarnSuppression() {
        BotCheckWalkState state = new BotCheckWalkState();
        VideoInfo challenge = new VideoInfo();

        assertTrue(state.recordChallenge(challenge, AppClient.WEB_EMBED, "explicit",
                AUTHED, RUSOWSKY_ORDER, CHALLENGED_AT, MOBILE));
        state.markCutShort(); // budget exhausted before ANDROID_VR could answer

        BotCheckWalkState.Outcome outcome = state.finish(NO_TRANSPORT_FAILURE);

        assertNotNull("the user still gets the server's reason", outcome);
        assertSame(challenge, outcome.result);
        assertFalse("...but an unfinished walk cannot silence the next open",
                outcome.ringExhausted);
        assertFalse(state.ringExhausted());
    }

    /**
     * 6. Web-pot clients all answer from the SAME challenged guest identity, so a tail made only
     * of them is exhausted - walking it would be guaranteed-dead round trips.
     */
    @Test
    public void aTailOfOnlyWebPotClientsCountsAsExhausted() {
        BotCheckWalkState state = new BotCheckWalkState();
        List<AppClient> webOnlyTail = Arrays.asList(
                AppClient.WEB_EMBED, AppClient.WEB, AppClient.WEB_SAFARI, AppClient.MWEB);

        assertFalse("every client behind it shares the challenged identity",
                state.recordChallenge(new VideoInfo(), AppClient.WEB_EMBED, "explicit",
                        AUTHED, webOnlyTail, 0, MOBILE));
    }

    /** A dead link establishes nothing about anything, so nothing is published. */
    @Test
    public void aDeadLinkPublishesNothingEvenAfterAChallenge() {
        BotCheckWalkState state = new BotCheckWalkState();

        assertTrue(state.recordChallenge(new VideoInfo(), AppClient.WEB_EMBED, "explicit",
                AUTHED, RUSOWSKY_ORDER, CHALLENGED_AT, MOBILE));
        state.markCutShort();

        assertNull(state.finish(true));
    }

    /** TV keeps the historical abort-and-return: it never walks on. */
    @Test
    public void tvNeverWalksOnPastAChallenge() {
        BotCheckWalkState state = new BotCheckWalkState();

        assertFalse(state.recordChallenge(new VideoInfo(), AppClient.WEB_EMBED, "explicit",
                AUTHED, RUSOWSKY_ORDER, CHALLENGED_AT, TV));
        assertFalse("nothing is held on the TV path either", state.hasHeldChallenge());
    }

    /**
     * The error screen should name the first client that was refused, not the last. Later
     * challenges in one walk are the same guest identity being refused again.
     */
    @Test
    public void onlyTheFirstChallengeOfAWalkIsHeld() {
        BotCheckWalkState state = new BotCheckWalkState();
        VideoInfo first = new VideoInfo();
        VideoInfo second = new VideoInfo();

        assertTrue(state.recordChallenge(first, AppClient.WEB_EMBED, "explicit",
                AUTHED, RUSOWSKY_ORDER, CHALLENGED_AT, MOBILE));
        assertTrue(state.recordChallenge(second, AppClient.WEB, "repeated-login",
                AUTHED, RUSOWSKY_ORDER, CHALLENGED_AT, MOBILE));

        BotCheckWalkState.Outcome outcome = state.finish(NO_TRANSPORT_FAILURE);

        assertNotNull(outcome);
        assertSame(first, outcome.result);
        assertEquals(AppClient.WEB_EMBED, outcome.client);
        assertEquals("explicit", outcome.signal);
    }

    /** A walk with no challenge at all publishes nothing through this path. */
    @Test
    public void anUnchallengedWalkPublishesNothing() {
        assertNull(new BotCheckWalkState().finish(NO_TRANSPORT_FAILURE));
    }
}
