package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.Test;

import java.util.List;

/** The phone's signed-out lane, spelled out (netbench PLANNER.md section 3.1). */
public class PhoneSourcePlannerTest {
    @Test
    public void signedOut() {
        assertOrder("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI", null, false);
    }

    /** A 403 on VISIONOS: the lane's next sources first, VISIONOS last instead of never. */
    @Test
    public void recovery() {
        assertOrder("WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS", AppClient.VISIONOS, false);
    }

    @Test
    public void anonymousWebChallenged() {
        assertOrder("VISIONOS ANDROID_VR IOS ANDROID_REEL WEB_EMBED MWEB WEB WEB_SAFARI", null, true);
    }

    /** The failed client stays last even when the challenged web clients move behind the rest. */
    @Test
    public void theSuspectStaysLastBehindTheChallengedWeb() {
        assertOrder("VISIONOS IOS ANDROID_REEL WEB_EMBED MWEB WEB WEB_SAFARI ANDROID_VR", AppClient.ANDROID_VR, true);
    }

    /** A suspect the lane does not ask (TV_TIZEN, reached only after a refusal) is not added. */
    @Test
    public void aSuspectOutsideTheLaneIsNotAdded() {
        assertOrder("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI", AppClient.TV_TIZEN, false);
    }

    @Test
    public void everyPlannedClientHasAReviewedCatalogEntry() {
        for (AppClient client : PhoneSourcePlanner.SIGNED_OUT) {
            assertTrue(client + " in the catalog", PlayerSourceCatalog.covers(client));
        }
    }

    private static void assertOrder(String expected, AppClient suspect, boolean anonChallenged) {
        List<AppClient> order = PhoneSourcePlanner.order(new PhoneSourcePlanner.Context(suspect, anonChallenged));
        StringBuilder actual = new StringBuilder();
        for (AppClient client : order) {
            actual.append(actual.length() > 0 ? " " : "").append(client);
        }
        assertEquals(expected, actual.toString());
    }
}
