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

    /** The A/B switch: signed in, the account route leads; signed out and benched, it changes nothing. */
    @Test
    public void theAccountRouteFirstSwitch() {
        assertEquals("TV_TIZEN VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(PhoneSourcePlanner.order(new PhoneSourcePlanner.Context(SIGNED_IN, null, false, false, true))));
        assertEquals("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI TV_TIZEN",
                join(PhoneSourcePlanner.order(new PhoneSourcePlanner.Context(SIGNED_IN, AppClient.TV_TIZEN, false, false, true))));
        assertEquals("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(PhoneSourcePlanner.order(new PhoneSourcePlanner.Context(SIGNED_IN, null, false, true, true))));
        assertEquals("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(PhoneSourcePlanner.order(new PhoneSourcePlanner.Context(SIGNED_OUT, null, false, false, true))));
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

    /**
     * Signed out, TV_TIZEN (admitted after a refusal, never planned) that just failed is asked last
     * rather than re-admitted next by the refusal rule; benched, it stays out.
     */
    @Test
    public void aSignedOutTizenSuspectIsAskedLast() {
        assertOrder("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI TV_TIZEN", SIGNED_OUT,
                AppClient.TV_TIZEN, false);
        List<AppClient> benched = PhoneSourcePlanner.order(
                new PhoneSourcePlanner.Context(SIGNED_OUT, AppClient.TV_TIZEN, false, true));
        assertFalse(benched.toString(), benched.contains(AppClient.TV_TIZEN));
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

    /**
     * NEWTUBE(kids-channel): a remembered kids channel puts the account route first in either lane
     * (anonymously signed out: the ask a refusal would have admitted next), then the lane's order,
     * each source once.
     */
    @Test
    public void aKidsChannelHintLeadsWithTheAccountRoute() {
        assertEquals("TV_TIZEN VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(hinted(SIGNED_OUT, null, false, false)));
        assertEquals("TV_TIZEN VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(hinted(SIGNED_IN, null, false, false)));
        // The challenged web identity still goes last behind it.
        assertEquals("TV_TIZEN VISIONOS ANDROID_VR IOS ANDROID_REEL WEB_EMBED MWEB WEB WEB_SAFARI",
                join(hinted(SIGNED_OUT, null, true, false)));
    }

    /** Health and recovery outrank the hint: a benched route stays out, a suspect stays last. */
    @Test
    public void aKidsChannelHintNeverLeadsWithABenchedRouteOrInARecovery() {
        assertEquals("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(hinted(SIGNED_OUT, null, false, true)));
        assertEquals("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(hinted(SIGNED_IN, null, false, true)));
        assertEquals("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI TV_TIZEN",
                join(hinted(SIGNED_OUT, AppClient.TV_TIZEN, false, false)));
        assertEquals("TV_TIZEN WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS",
                join(hinted(SIGNED_IN, AppClient.VISIONOS, false, false)));
    }

    /** Only the sources measured to refuse every kids video are its witnesses (not IOS, ANDROID_REEL). */
    @Test
    public void theKidsWitnessesAreVisionOsAndAndroidVr() {
        assertTrue(PhoneSourcePlanner.refusesMadeForKids(AppClient.VISIONOS));
        assertTrue(PhoneSourcePlanner.refusesMadeForKids(AppClient.ANDROID_VR));
        for (AppClient client : new AppClient[] {AppClient.TV_TIZEN, AppClient.WEB_EMBED, AppClient.IOS,
                AppClient.ANDROID_REEL, AppClient.MWEB, AppClient.WEB, AppClient.WEB_SAFARI}) {
            assertFalse(client.toString(), PhoneSourcePlanner.refusesMadeForKids(client));
        }
    }

    /**
     * NEWTUBE(recovery-refusals): a recovery asks a source that refused this video moments ago after
     * everything else, behind the suspect. The case is v16 LTE's kids video: VISIONOS refused it,
     * TV_TIZEN served it, its media 403'd, and the recovery asked VISIONOS first again.
     */
    @Test
    public void aRecoveryAsksWhatJustRefusedTheVideoLast() {
        assertEquals("WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI TV_TIZEN VISIONOS",
                join(recovering(SIGNED_OUT, AppClient.TV_TIZEN, false, EnumSet.of(AppClient.VISIONOS))));
        // Signed in, an 18+ video: VISIONOS answered the age gate, the account route served it and
        // its 403 benched it for the video: WEB_EMBED first.
        assertEquals("WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS",
                join(recovering(SIGNED_IN, AppClient.TV_TIZEN, true, EnumSet.of(AppClient.VISIONOS))));
        // Several, in the lane's order, all behind the suspect.
        assertEquals("IOS ANDROID_REEL MWEB WEB WEB_SAFARI WEB_EMBED VISIONOS ANDROID_VR",
                join(recovering(SIGNED_OUT, AppClient.WEB_EMBED, false,
                        EnumSet.of(AppClient.ANDROID_VR, AppClient.VISIONOS))));
        // The suspect served the video: an older refusal of its own does not move it.
        assertEquals("WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS",
                join(recovering(SIGNED_OUT, AppClient.VISIONOS, false, EnumSet.of(AppClient.VISIONOS))));
        // Outside a recovery walk the refusals are not read.
        assertEquals("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(recovering(SIGNED_OUT, null, false, EnumSet.of(AppClient.VISIONOS))));
        // Nothing refused: the recovery order as before.
        assertEquals("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI TV_TIZEN",
                join(recovering(SIGNED_OUT, AppClient.TV_TIZEN, false, EnumSet.noneOf(AppClient.class))));
        // Signed out, the anonymous TV_TIZEN refused too: only the refusal rule would put it in a
        // walk, and a recovery does not let it re-admit a refuser, so it is kept here, last.
        assertEquals("ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI WEB_EMBED VISIONOS TV_TIZEN",
                join(recovering(SIGNED_OUT, AppClient.WEB_EMBED, false,
                        EnumSet.of(AppClient.VISIONOS, AppClient.TV_TIZEN))));
        // ...unless benched.
        assertEquals("ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI WEB_EMBED VISIONOS",
                join(recovering(SIGNED_OUT, AppClient.WEB_EMBED, true,
                        EnumSet.of(AppClient.VISIONOS, AppClient.TV_TIZEN))));
        // Signed in, the account route that refused (an unverified account's age gate) goes last too.
        assertEquals("ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI WEB_EMBED VISIONOS TV_TIZEN",
                join(recovering(SIGNED_IN, AppClient.WEB_EMBED, false,
                        EnumSet.of(AppClient.VISIONOS, AppClient.TV_TIZEN))));
    }

    /**
     * NEWTUBE(recovery-kids): v20 on the emulator, both lanes: a kids video VISIONOS refused,
     * WEB_EMBED served (TV_TIZEN benched), its media 403'd, and the recovery asked ANDROID_VR, IOS,
     * ANDROID_REEL, MWEB, WEB and WEB_SAFARI (a refusal, five SABR-only answers) before WEB_EMBED
     * again. Only TV_TIZEN and WEB_EMBED serve such a video: the rest go behind the suspect.
     */
    @Test
    public void aKidsRecoveryAsksOnlyWhatServesKidsFirst() {
        EnumSet<AppClient> visionOs = EnumSet.of(AppClient.VISIONOS);
        assertEquals("WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS",
                join(kidsRecovery(SIGNED_OUT, AppClient.WEB_EMBED, true, visionOs)));
        assertEquals("WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS",
                join(kidsRecovery(SIGNED_IN, AppClient.WEB_EMBED, true, visionOs)));
        // TV_TIZEN served, not benched: WEB_EMBED, then TV_TIZEN again before the never-servers.
        assertEquals("WEB_EMBED TV_TIZEN ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS",
                join(kidsRecovery(SIGNED_OUT, AppClient.TV_TIZEN, false, visionOs)));
        // Signed in, the account route not benched and WEB_EMBED the suspect: TV_TIZEN first.
        assertEquals("TV_TIZEN WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS",
                join(kidsRecovery(SIGNED_IN, AppClient.WEB_EMBED, false, visionOs)));
        // The refusal came from ANDROID_VR: VISIONOS (a kids witness too) goes behind the suspect.
        assertEquals("WEB_EMBED VISIONOS IOS ANDROID_REEL MWEB WEB WEB_SAFARI ANDROID_VR",
                join(kidsRecovery(SIGNED_OUT, AppClient.WEB_EMBED, true, EnumSet.of(AppClient.ANDROID_VR))));
        // Outside a recovery walk the flag changes nothing.
        assertEquals("VISIONOS WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(kidsRecovery(SIGNED_OUT, null, false, EnumSet.noneOf(AppClient.class))));
    }

    /**
     * NEWTUBE(live-card): the item says live: the live-DASH source first, VISIONOS second, then the
     * lane. Never in a recovery walk.
     */
    @Test
    public void aLiveCardAsksTheLiveSourceFirst() {
        assertEquals("ANDROID_VR VISIONOS WEB_EMBED IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(liveCard(SIGNED_OUT, null)));
        assertEquals("ANDROID_VR VISIONOS TV_TIZEN WEB_EMBED IOS ANDROID_REEL MWEB WEB WEB_SAFARI",
                join(liveCard(SIGNED_IN, null)));
        assertEquals("WEB_EMBED ANDROID_VR IOS ANDROID_REEL MWEB WEB WEB_SAFARI VISIONOS",
                join(liveCard(SIGNED_OUT, AppClient.VISIONOS)));
        // A stale flag's set-aside answer plays where the lane would have asked the live source.
        assertTrue(PhoneSourcePlanner.isPastLiveSourceTurn(AppClient.IOS));
        assertTrue(PhoneSourcePlanner.isPastLiveSourceTurn(AppClient.WEB_SAFARI));
        for (AppClient client : new AppClient[] {AppClient.VISIONOS, AppClient.TV_TIZEN, AppClient.WEB_EMBED,
                AppClient.ANDROID_VR}) {
            assertFalse(client.toString(), PhoneSourcePlanner.isPastLiveSourceTurn(client));
        }
    }

    private static List<AppClient> kidsRecovery(PhoneSourcePlanner.Lane lane, AppClient suspect,
            boolean benched, EnumSet<AppClient> refused) {
        return PhoneSourcePlanner.order(new PhoneSourcePlanner.Context(lane, suspect, false, benched,
                false, false, refused, true, false));
    }

    private static List<AppClient> liveCard(PhoneSourcePlanner.Lane lane, AppClient suspect) {
        return PhoneSourcePlanner.order(new PhoneSourcePlanner.Context(lane, suspect, false, false,
                false, false, EnumSet.noneOf(AppClient.class), false, true));
    }

    private static List<AppClient> recovering(PhoneSourcePlanner.Lane lane, AppClient suspect,
            boolean benched, EnumSet<AppClient> refused) {
        return PhoneSourcePlanner.order(new PhoneSourcePlanner.Context(lane, suspect, false, benched,
                false, false, refused));
    }

    private static List<AppClient> hinted(PhoneSourcePlanner.Lane lane, AppClient suspect,
            boolean anonChallenged, boolean benched) {
        return PhoneSourcePlanner.order(new PhoneSourcePlanner.Context(lane, suspect, anonChallenged,
                benched, false, true));
    }

    @Test
    public void anAgeGateIsSettledOnceEverySourceThatServesOneAnsweredIt() {
        EnumSet<AppClient> none = EnumSet.noneOf(AppClient.class);
        List<AppClient> signedOutRest = Arrays.asList(AppClient.WEB_EMBED, AppClient.ANDROID_VR);
        // VISIONOS's gate alone: WEB_EMBED is still to come.
        assertFalse(PhoneSourcePlanner.isAgeGateSettled(SIGNED_OUT, true, EnumSet.of(AppClient.VISIONOS),
                EnumSet.of(AppClient.VISIONOS), signedOutRest));
        // WEB_EMBED answered without serving (its gate, or the embed refusal): settled.
        assertTrue(PhoneSourcePlanner.isAgeGateSettled(SIGNED_OUT, true,
                EnumSet.of(AppClient.VISIONOS, AppClient.WEB_EMBED),
                EnumSet.of(AppClient.VISIONOS, AppClient.WEB_EMBED), Arrays.asList(AppClient.ANDROID_VR)));
        // WEB_EMBED asked but silent (a timeout) has refused nothing.
        assertFalse(PhoneSourcePlanner.isAgeGateSettled(SIGNED_OUT, true, EnumSet.of(AppClient.VISIONOS),
                EnumSet.of(AppClient.VISIONOS, AppClient.WEB_EMBED), Arrays.asList(AppClient.ANDROID_VR)));
        // No age gate seen: never.
        assertFalse(PhoneSourcePlanner.isAgeGateSettled(SIGNED_OUT, false,
                EnumSet.of(AppClient.VISIONOS, AppClient.WEB_EMBED),
                EnumSet.of(AppClient.VISIONOS, AppClient.WEB_EMBED), Arrays.<AppClient>asList()));

        List<AppClient> signedInRest = Arrays.asList(AppClient.WEB_EMBED, AppClient.ANDROID_VR);
        assertFalse(PhoneSourcePlanner.isAgeGateSettled(SIGNED_IN, true,
                EnumSet.of(AppClient.VISIONOS, AppClient.TV_TIZEN),
                EnumSet.of(AppClient.VISIONOS, AppClient.TV_TIZEN), signedInRest));
        assertTrue(PhoneSourcePlanner.isAgeGateSettled(SIGNED_IN, true,
                EnumSet.of(AppClient.VISIONOS, AppClient.TV_TIZEN, AppClient.WEB_EMBED),
                EnumSet.of(AppClient.VISIONOS, AppClient.TV_TIZEN, AppClient.WEB_EMBED),
                Arrays.asList(AppClient.ANDROID_VR)));
        // The account route benched (never in the walk): WEB_EMBED's answer settles it.
        assertTrue(PhoneSourcePlanner.isAgeGateSettled(SIGNED_IN, true,
                EnumSet.of(AppClient.VISIONOS, AppClient.WEB_EMBED),
                EnumSet.of(AppClient.VISIONOS, AppClient.WEB_EMBED), Arrays.asList(AppClient.ANDROID_VR)));
        assertFalse(PhoneSourcePlanner.isAgeGateSettled(SIGNED_OUT, true, none, none,
                Arrays.asList(AppClient.WEB_EMBED)));
    }

    @Test
    public void everyPlannedClientHasAReviewedCatalogEntry() {
        for (AppClient client : PhoneSourcePlanner.ORDER) {
            assertTrue(client + " in the catalog", PlayerSourceCatalog.covers(client));
        }
    }

    private static String join(List<AppClient> order) {
        StringBuilder actual = new StringBuilder();
        for (AppClient client : order) {
            actual.append(actual.length() > 0 ? " " : "").append(client);
        }
        return actual.toString();
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
