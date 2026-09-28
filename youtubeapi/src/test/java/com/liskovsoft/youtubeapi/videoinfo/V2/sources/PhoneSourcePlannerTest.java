package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PhoneSourcePlanner.Lane.SIGNED_IN;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PhoneSourcePlanner.Lane.SIGNED_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/** The phone's two lanes, spelled out (netbench LANES.md section 2). */
public class PhoneSourcePlannerTest {
    @Test
    public void signedOut() {
        assertOrder("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI", SIGNED_OUT, null, false);
    }

    /** Signed in, the lane is the same with the account route second. */
    @Test
    public void signedIn() {
        assertOrder("VISIONOS TV_TIZEN WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                SIGNED_IN, null, false);
    }

    /** A 403 on VISIONOS: the lane's next sources first, VISIONOS last instead of never. */
    @Test
    public void recovery() {
        assertOrder("WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS", SIGNED_OUT,
                AppClient.VISIONOS, false);
        assertOrder("TV_TIZEN WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS", SIGNED_IN,
                AppClient.VISIONOS, false);
    }

    /** Signed in, the account route's own 403 puts it last (it is benched on top: see below). */
    @Test
    public void signedInRecoveryFromTheAccountRoute() {
        assertOrder("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI TV_TIZEN", SIGNED_IN,
                AppClient.TV_TIZEN, false);
    }

    @Test
    public void aBenchedAccountRouteIsNotPlanned() {
        List<AppClient> order = PhoneSourcePlanner.order(
                new PhoneSourcePlanner.Context(SIGNED_IN, null, false, true));
        assertFalse(order.toString(), order.contains(AppClient.TV_TIZEN));
    }

    @Test
    public void anonymousWebChallenged() {
        assertOrder("VISIONOS ANDROID_VR IOS ANDROID_REEL WEB_EMBED MWEB WEB WEB_SAFARI", SIGNED_OUT, null, true);
        assertOrder("VISIONOS TV_TIZEN ANDROID_VR IOS ANDROID_REEL WEB_EMBED MWEB WEB WEB_SAFARI", SIGNED_IN,
                null, true);
    }

    /** The failed client stays last even when the challenged web clients move behind the rest. */
    @Test
    public void theSuspectStaysLastBehindTheChallengedWeb() {
        assertOrder("VISIONOS IOS ANDROID_REEL WEB_EMBED MWEB WEB WEB_SAFARI ANDROID_VR", SIGNED_OUT,
                AppClient.ANDROID_VR, true);
    }

    /** Signed out, a TV_TIZEN suspect (admitted after a refusal, never planned) is not added. */
    @Test
    public void aSuspectOutsideTheLaneIsNotAdded() {
        assertOrder("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI", SIGNED_OUT,
                AppClient.TV_TIZEN, false);
    }

    /** Only an anonymous non-web refusal signed out admits TV_TIZEN without the account. */
    @Test
    public void theAccountRouteIsAdmittedAfterAContentRefusalSignedOutOnly() {
        assertTrue(PhoneSourcePlanner.admitsAccountRouteAfter(SIGNED_OUT, AppClient.VISIONOS));
        assertTrue(PhoneSourcePlanner.admitsAccountRouteAfter(SIGNED_OUT, AppClient.ANDROID_VR));
        assertFalse(PhoneSourcePlanner.admitsAccountRouteAfter(SIGNED_OUT, AppClient.WEB_EMBED));
        assertFalse(PhoneSourcePlanner.admitsAccountRouteAfter(SIGNED_OUT, AppClient.TV_TIZEN));
        assertFalse(PhoneSourcePlanner.admitsAccountRouteAfter(SIGNED_IN, AppClient.VISIONOS));
    }

    @Test
    public void anAgeGateIsSettledOnceEverySourceThatServesOneAnsweredIt() {
        List<AppClient> signedOutRest = Arrays.asList(AppClient.WEB_EMBED, AppClient.ANDROID_VR, AppClient.IOS);
        assertFalse(PhoneSourcePlanner.isAgeGateSettled(SIGNED_OUT, EnumSet.of(AppClient.VISIONOS), signedOutRest));
        assertTrue(PhoneSourcePlanner.isAgeGateSettled(SIGNED_OUT,
                EnumSet.of(AppClient.VISIONOS, AppClient.WEB_EMBED), signedOutRest.subList(1, 3)));

        List<AppClient> signedInRest = Arrays.asList(AppClient.TV_TIZEN, AppClient.WEB_EMBED, AppClient.ANDROID_VR);
        assertFalse(PhoneSourcePlanner.isAgeGateSettled(SIGNED_IN, EnumSet.of(AppClient.VISIONOS), signedInRest));
        assertFalse(PhoneSourcePlanner.isAgeGateSettled(SIGNED_IN,
                EnumSet.of(AppClient.VISIONOS, AppClient.TV_TIZEN), signedInRest.subList(1, 3)));
        assertTrue(PhoneSourcePlanner.isAgeGateSettled(SIGNED_IN,
                EnumSet.of(AppClient.VISIONOS, AppClient.TV_TIZEN, AppClient.WEB_EMBED), signedInRest.subList(2, 3)));
        // The account route benched (not in the walk): WEB_EMBED's gate settles it.
        assertTrue(PhoneSourcePlanner.isAgeGateSettled(SIGNED_IN,
                EnumSet.of(AppClient.VISIONOS, AppClient.WEB_EMBED), Arrays.asList(AppClient.ANDROID_VR)));
        assertFalse(PhoneSourcePlanner.isAgeGateSettled(SIGNED_OUT, EnumSet.noneOf(AppClient.class),
                Arrays.<AppClient>asList()));
    }

    @Test
    public void everyPlannedClientHasAReviewedCatalogEntry() {
        for (AppClient client : PhoneSourcePlanner.ORDER) {
            assertTrue(client + " in the catalog", PlayerSourceCatalog.covers(client));
        }
    }

    private static void assertOrder(String expected, PhoneSourcePlanner.Lane lane, AppClient suspect,
            boolean anonChallenged) {
        List<AppClient> order = PhoneSourcePlanner.order(
                new PhoneSourcePlanner.Context(lane, suspect, anonChallenged, false));
        StringBuilder actual = new StringBuilder();
        for (AppClient client : order) {
            actual.append(actual.length() > 0 ? " " : "").append(client);
        }
        assertEquals(expected, actual.toString());
    }
}
