package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService.AuthRouteWalkState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * When an account-bearing client answers "no media at all", this decides whether that is evidence
 * about the ROUTE or about the VIDEO.
 *
 * <p>Regression source: the Pixel 9 soak of 2026-09-07 13:54. Opening the lofi 24/7 stream
 * (jfKfPfyJRdk), whose recording is not published, walked all eleven clients - every one refused
 * with the same reason - and both authenticated heads scored a quarantine hit anyway
 * ({@code auth-route no-media client=TV hits=1/2 reloadPage=n}). Two such videos in a row would
 * have demoted a perfectly healthy account route for the rest of the session, silently serving
 * every later video anonymously.
 *
 * <p>The rule these tests encode: an observation counts only once some other client has served the
 * same video in the same walk.
 */
public class AuthRouteWalkStateTest {
    private static final String LOFI = "jfKfPfyJRdk";     // recording not available to anyone
    private static final String RUSOWSKY = "Fo89b8zAIE4"; // played on VISIONOS the same walk
    private static final boolean RELOAD_PAGE = true;
    private static final boolean NO_RELOAD_PAGE = false;

    /** The regression: nobody served the video, so nothing may be counted. */
    @Test
    public void videoNoClientCanPlayIsNotEvidenceAboutTheRoute() {
        AuthRouteWalkState state = new AuthRouteWalkState();

        assertFalse(state.hold(AppClient.TV, LOFI, NO_RELOAD_PAGE));
        assertFalse(state.hold(AppClient.TV_DOWNGRADED, LOFI, NO_RELOAD_PAGE));

        // The walk ends here - no onPlayable() call. Both observations are dropped with it.
        assertEquals(2, state.heldCount());
    }

    /** The case the quarantine exists for: the heads refuse, another client plays it. */
    @Test
    public void routeRefusingAVideoThatPlaysElsewhereIsCounted() {
        AuthRouteWalkState state = new AuthRouteWalkState();
        state.hold(AppClient.TV_DOWNGRADED, RUSOWSKY, RELOAD_PAGE);
        state.hold(AppClient.TV, RUSOWSKY, RELOAD_PAGE);

        Map<AppClient, AuthRouteWalkState.Held> proven = state.onPlayable();

        assertEquals(2, proven.size());
        assertEquals(RUSOWSKY, proven.get(AppClient.TV).videoId);
        assertTrue(proven.get(AppClient.TV).reloadPage);
        assertEquals(0, state.heldCount());
    }

    /** Walk order is the reading order of the evidence; keep it stable for the log line. */
    @Test
    public void provenObservationsKeepWalkOrder() {
        AuthRouteWalkState state = new AuthRouteWalkState();
        state.hold(AppClient.TV_DOWNGRADED, RUSOWSKY, RELOAD_PAGE);
        state.hold(AppClient.TV, RUSOWSKY, RELOAD_PAGE);

        List<AppClient> seen = new ArrayList<>(state.onPlayable().keySet());

        assertEquals(java.util.Arrays.asList(AppClient.TV_DOWNGRADED, AppClient.TV), seen);
    }

    /**
     * A live result held for a dash manifest keeps walking past clients that have already been
     * outvoted, so an observation made after the video played counts straight away.
     */
    @Test
    public void observationAfterTheVideoPlayedCountsImmediately() {
        AuthRouteWalkState state = new AuthRouteWalkState();
        assertTrue(state.onPlayable().isEmpty());

        assertTrue(state.hold(AppClient.TV, RUSOWSKY, NO_RELOAD_PAGE));
        assertEquals(0, state.heldCount());
    }

    /** Draining twice must not replay evidence the caller has already counted. */
    @Test
    public void provenObservationsAreDrainedOnce() {
        AuthRouteWalkState state = new AuthRouteWalkState();
        state.hold(AppClient.TV, RUSOWSKY, RELOAD_PAGE);

        assertEquals(1, state.onPlayable().size());
        assertTrue(state.onPlayable().isEmpty());
    }

    /** The flag travels with the observation: it is read after its VideoInfo is out of scope. */
    @Test
    public void reloadPageFlagSurvivesTheHold() {
        AuthRouteWalkState state = new AuthRouteWalkState();
        state.hold(AppClient.TV, RUSOWSKY, RELOAD_PAGE);
        state.hold(AppClient.TV_DOWNGRADED, RUSOWSKY, NO_RELOAD_PAGE);

        Map<AppClient, AuthRouteWalkState.Held> proven = state.onPlayable();

        assertTrue(proven.get(AppClient.TV).reloadPage);
        assertFalse(proven.get(AppClient.TV_DOWNGRADED).reloadPage);
    }
}
