package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import static org.junit.Assert.assertEquals;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The phone's lanes, spelled out (netbench PLANNER.md section 3.1). */
public class PhoneSourcePlannerTest {
    private static final Set<AppClient> NONE = EnumSet.noneOf(AppClient.class);

    @Test
    public void signedOut() {
        assertOrder("VISIONOS TV_TIZEN WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                ctx(false, null, NONE, false));
    }

    /** A 403 on VISIONOS: the lane's next sources first, VISIONOS last instead of never. */
    @Test
    public void signedOutRecovery() {
        assertOrder("TV_TIZEN WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS",
                ctx(false, AppClient.VISIONOS, NONE, false));
    }

    @Test
    public void signedOutAnonymousWebChallenged() {
        assertOrder("VISIONOS TV_TIZEN ANDROID_VR IOS ANDROID_REEL WEB_EMBED MWEB WEB WEB_SAFARI",
                ctx(false, null, NONE, true));
    }

    @Test
    public void signedIn() {
        assertOrder("TV_DOWNGRADED TV VISIONOS TV_TIZEN WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                ctx(true, null, NONE, false));
    }

    /** One head refused on this transport: its sibling keeps attempt 1, it waits behind VISIONOS. */
    @Test
    public void signedInOneHeadQuarantined() {
        assertOrder("TV VISIONOS TV_DOWNGRADED TV_TIZEN WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                ctx(true, null, EnumSet.of(AppClient.TV_DOWNGRADED), false));
    }

    /** Both heads refused: VISIONOS leads, the heads are re-tested after the measured sources. */
    @Test
    public void signedInBothHeadsQuarantined() {
        assertOrder("VISIONOS TV_TIZEN WEB_EMBED ANDROID_VR TV_DOWNGRADED TV IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                ctx(true, null, EnumSet.of(AppClient.TV_DOWNGRADED, AppClient.TV), false));
    }

    @Test
    public void signedInBothHeadsQuarantinedWithTheAccountOnWebEmbed() {
        assertOrder("WEB_EMBED VISIONOS TV_TIZEN ANDROID_VR TV_DOWNGRADED TV IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                new PhoneSourcePlanner.Context(true, null,
                        EnumSet.of(AppClient.TV_DOWNGRADED, AppClient.TV), false, true));
    }

    /** TV_DOWNGRADED's media failed (and it was quarantined for it): the account's other head next. */
    @Test
    public void signedInRecovery() {
        assertOrder("VISIONOS TV TV_TIZEN WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI TV_DOWNGRADED",
                ctx(true, AppClient.TV_DOWNGRADED, EnumSet.of(AppClient.TV_DOWNGRADED), false));
    }

    /** A suspect the lane does not ask is not added. */
    @Test
    public void aSuspectOutsideTheLaneIsNotAsked() {
        assertOrder("VISIONOS TV_TIZEN WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                ctx(false, AppClient.GEO, NONE, false));
    }

    @Test
    public void everyPlannedClientHasAReviewedCatalogEntry() {
        for (AppClient client : PhoneSourcePlanner.SIGNED_OUT) {
            assertEquals(client + " in the catalog", true, PlayerSourceCatalog.covers(client));
        }
        for (AppClient client : PhoneSourcePlanner.ACCOUNT_HEAD) {
            assertEquals(client + " in the catalog", true, PlayerSourceCatalog.covers(client));
        }
    }

    private static PhoneSourcePlanner.Context ctx(boolean signedIn, AppClient suspect, Set<AppClient> quarantined,
            boolean anonChallenged) {
        return new PhoneSourcePlanner.Context(signedIn, suspect, quarantined, anonChallenged, false);
    }

    private static void assertOrder(String expected, PhoneSourcePlanner.Context context) {
        List<AppClient> order = PhoneSourcePlanner.order(context);
        StringBuilder actual = new StringBuilder();
        for (AppClient client : order) {
            actual.append(actual.length() > 0 ? " " : "").append(client);
        }
        assertEquals(expected, actual.toString());
    }
}
