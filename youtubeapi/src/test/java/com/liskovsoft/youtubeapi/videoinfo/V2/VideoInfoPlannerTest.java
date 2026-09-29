package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.googlecommon.common.converters.jsonpath.converter.JsonPathConverterFactory;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Converter;

/**
 * NEWTUBE(planner): the phone's walk, both lanes, with the order from PhoneSourcePlanner (netbench
 * LANES.md), as MobileMainApplication configures it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoBotWallTest.ShadowWalk.class, VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoPlannerTest {
    private static final String NOT_AVAILABLE = "This video is not available";
    private VideoInfoService service;

    @Before
    public void setUp() {
        VideoInfoBotWallTest.ShadowWalk.network = "wifi:100";
        VideoInfoBotWallTest.ShadowWalk.transport = "wifi";
        VideoInfoBotWallTest.ShadowWalk.signedIn = false;
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> playable(auth);
        VideoInfoService.setPreferNoPotClient(true);
        VideoInfoService.setPreferDashManifestForLive(true);
        service = ReflectionHelpers.callConstructor(VideoInfoService.class);
        initIfNull("mAuthRouteQuarantine", new AuthRouteQuarantineBook());
        initIfNull("mBotWall", new BotWallBook());
        initIfNull("mVideoWinners", Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, AppClient>()));
        initIfNull("mRoutingGeneration", new java.util.concurrent.atomic.AtomicLong());
    }

    @After
    public void tearDown() {
        VideoInfoService.setPreferNoPotClient(false);
        VideoInfoService.setPreferDashManifestForLive(false);
        VideoInfoService.setLiveCardHintEnabled(false);
        VideoInfoService.setRecoveryKidsOrderEnabled(true);
        VideoInfoService.liveCards().clear();
    }

    @Test
    public void aHealthyOpenIsOneRequest() {
        assertFalse(open("normal").isUnplayable());
        assertEquals(Collections.singletonList("VISIONOS"), calls());
    }

    /** Issue #5: VISIONOS refuses a kids video, TV_TIZEN (no account) is asked next and serves it. */
    @Test
    public void aKidsVideoIsServedSecond() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN
                ? playable(auth) : unplayable(NOT_AVAILABLE, auth);
        VideoInfo result = open("kids");
        assertEquals(AppClient.TV_TIZEN, result.getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN"), calls());
    }

    @Test
    public void whenTizenRefusesWebEmbedIsThird() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : unplayable(NOT_AVAILABLE, auth);
        assertEquals(AppClient.WEB_EMBED, open("kids").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN", "WEB_EMBED"), calls());
    }

    /** Nothing serves it: the video's own refusal, the whole lane asked once, no TV 7.x or GEO. */
    @Test
    public void aVideoNothingServesAsksTheLaneOnce() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> unplayable(NOT_AVAILABLE, auth);
        VideoInfo result = open("gone");
        assertTrue(result.isUnplayable());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN", "WEB_EMBED", "ANDROID_VR", "IOS", "ANDROID_REEL",
                "MWEB", "WEB", "WEB_SAFARI"), calls());
    }

    /** An age gate is no refusal TV_TIZEN can help with (it answers the same gate): WEB_EMBED is next. */
    @Test
    public void anAgeGateGoesStraightToWebEmbed() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                        + " \"reason\": \"Sign in to confirm your age\", \"desktopLegacyAgeGateReason\": 1}}", auth);
        assertEquals(AppClient.WEB_EMBED, open("adult").getClient());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED"), calls());
    }

    /**
     * A removed video (ERROR, so no TV_TIZEN): the same allowlisted reason from the three visitors
     * (web session, embed page, app) is the verdict - the fourth request, not the eighth.
     */
    @Test
    public void aRemovedVideoIsSettledByThreeIdentities() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse("{\"playabilityStatus\":"
                + " {\"status\": \"ERROR\", \"reason\": \"This video has been removed by the uploader\"}}", auth);
        assertTrue(open("removed").isUnplayable());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED", "ANDROID_VR", "IOS"), calls());
    }

    /**
     * The same removal as the Pixel saw it: a policy sentence with a "learn more" tail from the
     * app and web sources, the terms of service from the embed page. Still the fourth request.
     */
    @Test
    public void aRemovalWordedDifferentlyByTheEmbedPageIsStillSettledAtTheFourthRequest() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse("{\"playabilityStatus\":"
                + " {\"status\": \"ERROR\", \"reason\": \"" + (client == AppClient.WEB_EMBED
                ? "Este vídeo se ha retirado porque infringía los Términos del Servicio de YouTube"
                : "Este vídeo se ha retirado por infringir la política de YouTube sobre la incitación"
                        + " al odio. Obtén más información sobre cómo combatir la incitación al odio"
                        + " en tu país.") + "\"}}", auth);
        assertTrue(open("removed").isUnplayable());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED", "ANDROID_VR", "IOS"), calls());
    }

    /**
     * Three identities refusing with a removal qualified by the site are no verdict: the walk
     * goes on past the fourth request to the source that serves the video.
     */
    @Test
    public void aQualifiedRemovalFromThreeIdentitiesDoesNotStopTheWalk() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.ANDROID_REEL
                ? playable(auth) : parse("{\"playabilityStatus\": {\"status\": \"ERROR\","
                        + " \"reason\": \"This video has been removed for violating YouTube's"
                        + " embedding policy on this website\"}}", auth);
        assertEquals(AppClient.ANDROID_REEL, open("qualified").getClient());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED", "ANDROID_VR", "IOS", "ANDROID_REEL"),
                calls());
    }

    /** A generic reason is not a verdict: the lane is asked to the end. */
    @Test
    public void aGenericRefusalIsNotSettledEarly() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse("{\"playabilityStatus\":"
                + " {\"status\": \"ERROR\", \"reason\": \"Video unavailable\"}}", auth);
        open("gone");
        assertEquals(8, calls().size());
    }

    /** Live: VISIONOS's HLS answer is held and only the live-DASH client is asked. */
    @Test
    public void liveGoesStraightToTheDashClient() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse(
                "{\"playabilityStatus\": {\"status\": \"OK\"}, \"videoDetails\": {\"videoId\": \"live\","
                        + " \"isLive\": true, \"isLiveContent\": true}, \"streamingData\": {\"hlsManifestUrl\":"
                        + " \"https://media.invalid/live.m3u8\"" + (client == AppClient.ANDROID_VR
                        ? ", \"dashManifestUrl\": \"https://media.invalid/live.mpd\"" : "") + "}}", auth);
        VideoInfo result = open("live");
        assertEquals(AppClient.ANDROID_VR, result.getClient());
        assertEquals(Arrays.asList("VISIONOS", "ANDROID_VR"), calls());
    }

    /**
     * Members only, not a member (the wording the TV clients gave the owner's account, 2026-09-29):
     * no TV_TIZEN, and the three identities settle it at the fourth request.
     */
    @Test
    public void aMembersOnlyVideoIsSettledWithoutTizen() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> unplayable("Join this channel from your"
                + " computer or mobile app to get access to members-only content like this video.", auth);
        assertTrue(open("members").isUnplayable());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED", "ANDROID_VR", "IOS"), calls());
    }

    /** A member signed in: the account route serves it second. */
    @Test
    public void signedInAMemberIsServedByTheAccountRoute() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN && auth
                ? playable(auth) : unplayable("Join this channel to get access to members-only content"
                        + " like this video, and other exclusive perks.", auth);
        assertEquals(AppClient.TV_TIZEN, open("members").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), calls());
    }

    /** An ended stream whose recording is gone answers UNPLAYABLE too; TV_TIZEN cannot help it. */
    @Test
    public void aLiveRefusalIsNotSentToTizen() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse("{\"playabilityStatus\":"
                + " {\"status\": \"UNPLAYABLE\", \"reason\": \"This live stream recording is not available\"},"
                + " \"videoDetails\": {\"videoId\": \"ended\", \"isLiveContent\": true}}", auth);
        open("ended");
        assertFalse(calls().toString(), calls().contains("TV_TIZEN"));
    }

    /**
     * Signed out, an age gate WEB_EMBED answers too (not embeddable) is settled there: no other
     * anonymous source serves an age gate, so the ring's six more requests are skipped.
     */
    @Test
    public void anAgeGateNothingServesIsSettledAtTheSecondRequest() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> ageGate(auth);
        VideoInfo result = open("adult");
        assertTrue(result.isAgeGate());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED"), calls());
    }

    /**
     * 18+ and not embeddable: WEB_EMBED answers with the embed refusal, not the gate. Still
     * settled at the second request, and the verdict shown is the age gate.
     */
    @Test
    public void anAgeGateWebEmbedRefusesForItsEmbedPolicyIsSettledToo() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? unplayable("Playback on other websites has been disabled by the video owner", auth)
                : ageGate(auth);
        VideoInfo result = open("adult");
        assertTrue(result.isAgeGate());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED"), calls());
    }

    /** WEB_EMBED silent (a timeout) refused nothing: the walk goes on. */
    @Test
    public void anAgeGateIsNotSettledByATimeout() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? null : ageGate(auth);
        open("adult");
        assertEquals(calls().toString(), 8, calls().size());
    }

    /**
     * Signed in with the account route benched, three anonymous identities settle nothing: the
     * account was never heard on this video.
     */
    @Test
    public void signedInARemovalNeedsTheAccountWitness() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        BotWallBook book = ReflectionHelpers.getField(service, "mBotWall");
        book.noteRouteFailed("wifi:100", VideoInfoService.noMediaVideoKey("removed"), "media-403",
                android.os.SystemClock.elapsedRealtime());
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse("{\"playabilityStatus\":"
                + " {\"status\": \"ERROR\", \"reason\": \"This video has been removed by the uploader\"}}", auth);
        open("removed");
        assertEquals(calls().toString(), 8, calls().size());
    }

    /**
     * Signed out, TV_TIZEN served a kids video and its media failed: the recovery walk does not put
     * it straight back next after VISIONOS's refusal; it is asked last.
     */
    @Test
    public void aKidsRecoveryFromTizenAsksItLast() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> unplayable(NOT_AVAILABLE + " " + client, auth);
        ReflectionHelpers.setField(service, "mRecoveryWalk", true);
        ReflectionHelpers.setField(service, "mRecoverySuspect", AppClient.TV_TIZEN);
        open("kids");
        List<String> calls = calls();
        assertEquals(calls.toString(), "WEB_EMBED", calls.get(1));
        assertEquals(calls.toString(), "TV_TIZEN", calls.get(calls.size() - 1));
        assertEquals(calls.toString(), 1, java.util.Collections.frequency(calls, "TV_TIZEN"));
    }

    /**
     * NEWTUBE(recovery-refusals): v16 LTE, the kids video. VISIONOS refused it, TV_TIZEN served it,
     * its media 403'd, and the recovery walk asked VISIONOS first again: one more refusal before
     * WEB_EMBED served. A source that refused the video moments ago is asked after everything else.
     */
    @Test
    public void aRecoveryDoesNotReaskWhatJustRefusedTheVideo() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) ->
                client == AppClient.TV_TIZEN || client == AppClient.WEB_EMBED
                        ? playable(auth) : unplayable(NOT_AVAILABLE, auth);
        assertEquals(AppClient.TV_TIZEN, open("kids").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN"), calls());

        recoverFrom(AppClient.TV_TIZEN);
        assertEquals(AppClient.WEB_EMBED, open("kids").getClient());
        assertEquals(Arrays.asList("WEB_EMBED"), calls());
    }

    /** The refusal lapses: a recovery half an hour later asks the lane's order again. */
    @Test
    public void aRefusalFromLongAgoIsAskedInItsPlace() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) ->
                client == AppClient.TV_TIZEN || client == AppClient.WEB_EMBED
                        ? playable(auth) : unplayable(NOT_AVAILABLE, auth);
        open("kids");
        org.robolectric.shadows.ShadowSystemClock.advanceBy(
                java.time.Duration.ofMillis(com.liskovsoft.youtubeapi.videoinfo.V2.sources.RecentRefusals.TTL_MS));
        recoverFrom(AppClient.TV_TIZEN);
        open("kids");
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED"), calls());
    }

    /**
     * Signed out, TV_TIZEN asked anonymously refused the video too (WEB_EMBED served it): the
     * recovery neither leads with VISIONOS nor lets the refusal rule admit TV_TIZEN again.
     */
    @Test
    public void aRecoveryDoesNotReadmitATizenThatJustRefused() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : unplayable(NOT_AVAILABLE + " " + client, auth);
        assertEquals(AppClient.WEB_EMBED, open("kids").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN", "WEB_EMBED"), calls());

        recoverFrom(AppClient.WEB_EMBED);
        assertEquals(AppClient.WEB_EMBED, open("kids").getClient());
        List<String> calls = calls();
        assertFalse(calls.toString(), calls.contains("VISIONOS"));
        assertFalse(calls.toString(), calls.contains("TV_TIZEN"));
        assertEquals(calls.toString(), "WEB_EMBED", calls.get(calls.size() - 1));
    }

    /**
     * Signed in, an 18+ video: VISIONOS answered the age gate, the account route served it, and its
     * media 403 benched the route for the video. The recovery goes to WEB_EMBED, not back to the gate.
     */
    @Test
    public void signedInAnAgeGateRecoveryDoesNotReaskTheGate() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) ->
                (client == AppClient.TV_TIZEN && auth) || client == AppClient.WEB_EMBED
                        ? playable(auth) : ageGate(auth);
        assertEquals(AppClient.TV_TIZEN, open("adult").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), calls());

        BotWallBook book = ReflectionHelpers.getField(service, "mBotWall");
        book.noteRouteFailed("wifi:100", VideoInfoService.noMediaVideoKey("adult"), "media-403",
                android.os.SystemClock.elapsedRealtime());
        recoverFrom(AppClient.TV_TIZEN);
        assertEquals(AppClient.WEB_EMBED, open("adult").getClient());
        assertEquals(Arrays.asList("WEB_EMBED"), calls());
    }

    /** A bot check is the identity's, not the video's: the recovery still asks that source first. */
    @Test
    public void aChallengeIsNotARefusalOfTheVideo() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.VISIONOS
                ? parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                        + " \"reason\": \"Sign in to confirm you're not a bot\"}}", auth)
                : playable(auth);
        assertEquals(AppClient.WEB_EMBED, open("challenged").getClient());
        recoverFrom(AppClient.WEB_EMBED);
        open("challenged");
        assertEquals("VISIONOS", calls().get(0));
    }

    /**
     * Kept, not dropped: TV_TIZEN refused anonymously a moment ago, but if nothing else serves the
     * reload it is still asked, last of all. (v21: VISIONOS's refusal was the made-for-kids one, so
     * WEB_EMBED, the one other source that serves such a video, is asked first.)
     */
    @Test
    public void aRecoveryStillAsksATizenThatRefusedLastOfAll() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : unplayable(NOT_AVAILABLE + " " + client, auth);
        open("kids");
        recoverFrom(AppClient.WEB_EMBED);
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN
                ? playable(auth) : unplayable(NOT_AVAILABLE + " " + client, auth);
        assertEquals(AppClient.TV_TIZEN, open("kids").getClient());
        assertEquals(Arrays.asList("WEB_EMBED", "ANDROID_VR", "IOS", "ANDROID_REEL", "MWEB", "WEB",
                "WEB_SAFARI", "VISIONOS", "TV_TIZEN"), calls());
    }

    /**
     * Signed in, the account refused the video (an unverified account's age gate) and WEB_EMBED
     * served it. Its recovery asks the account route last: the anonymous sign-in rule that puts the
     * account route next after a LOGIN_REQUIRED answer does not move it back up.
     */
    @Test
    public void signedInARecoveryDoesNotPutARefusingAccountRouteNext() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : ageGate(auth);
        assertEquals(AppClient.WEB_EMBED, open("adult").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth", "WEB_EMBED"), calls());

        recoverFrom(AppClient.WEB_EMBED);
        assertEquals(AppClient.WEB_EMBED, open("adult").getClient());
        assertEquals(Arrays.asList("ANDROID_VR", "IOS", "ANDROID_REEL", "MWEB", "WEB", "WEB_SAFARI",
                "WEB_EMBED"), calls());
    }

    /** What a recovery remembers as a refusal of the video, and what it does not. */
    @Test
    public void whatCountsAsARefusalOfTheVideo() {
        assertTrue(VideoInfoService.isRefusalOfTheVideo(AppClient.VISIONOS, unplayable(NOT_AVAILABLE, false)));
        assertTrue(VideoInfoService.isRefusalOfTheVideo(AppClient.VISIONOS, ageGate(false)));
        assertTrue(VideoInfoService.isRefusalOfTheVideo(AppClient.TV_TIZEN, ageGate(true)));
        assertTrue("a sign-in request", VideoInfoService.isRefusalOfTheVideo(AppClient.VISIONOS, parse(
                "{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\", \"reason\": \"Private video\"}}", false)));
        assertTrue("an embed policy", VideoInfoService.isRefusalOfTheVideo(AppClient.WEB_EMBED,
                unplayable("Playback on other websites has been disabled by the video owner", false)));
        assertTrue("152 means a stale identity only from WEB_EMBED", VideoInfoService.isRefusalOfTheVideo(
                AppClient.VISIONOS, unplayable("Error code: 152", false)));

        assertFalse("WEB_EMBED's stale identity", VideoInfoService.isRefusalOfTheVideo(AppClient.WEB_EMBED,
                unplayable("Error code: 152 - 4", false)));
        assertFalse("unavailable: another source serves it", VideoInfoService.isRefusalOfTheVideo(
                AppClient.VISIONOS, parse("{\"playabilityStatus\": {\"status\": \"ERROR\","
                        + " \"reason\": \"Video unavailable\"}}", false)));
        assertFalse("a bot check", VideoInfoService.isRefusalOfTheVideo(AppClient.VISIONOS, parse(
                "{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                        + " \"reason\": \"Sign in to confirm you're not a bot\"}}", false)));
        assertFalse("a reload-page answer", VideoInfoService.isRefusalOfTheVideo(AppClient.TV_TIZEN,
                unplayable("The page needs to be reloaded.", true)));
        assertFalse("an ended stream", VideoInfoService.isRefusalOfTheVideo(AppClient.VISIONOS, parse(
                "{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"reason\": \"This live stream"
                        + " recording is not available.\"}, \"videoDetails\": {\"videoId\": \"x\","
                        + " \"isLiveContent\": true}}", false)));
        assertFalse("served", VideoInfoService.isRefusalOfTheVideo(AppClient.VISIONOS, playable(false)));
        assertFalse("SABR only: not a refusal", VideoInfoService.isRefusalOfTheVideo(AppClient.WEB_EMBED, parse(
                "{\"playabilityStatus\": {\"status\": \"OK\"}, \"streamingData\": {\"serverAbrStreamingUrl\":"
                        + " \"https://media.invalid/sabr\", \"adaptiveFormats\": [{\"itag\": 137,"
                        + " \"mimeType\": \"video/mp4\"}]}}", false)));
    }

    /** NEWTUBE(live-card): the item said live: ANDROID_VR's DASH answer at the first request. */
    @Test
    public void aLiveCardIsServedAtTheFirstRequest() {
        VideoInfoService.setLiveCardHintEnabled(true);
        VideoInfoService.noteVideoLive("live", true);
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> liveAnswer(client, auth);
        assertEquals(AppClient.ANDROID_VR, open("live").getClient());
        assertEquals(Arrays.asList("ANDROID_VR"), calls());
    }

    /**
     * A stale flag (the stream ended: a VOD now) costs one request: ANDROID_VR's answer is set
     * aside and VISIONOS serves it, as the lane would have.
     */
    @Test
    public void aStaleLiveCardCostsOneRequest() {
        VideoInfoService.setLiveCardHintEnabled(true);
        VideoInfoService.noteVideoLive("ended", true);
        assertEquals(AppClient.VISIONOS, open("ended").getClient());
        assertEquals(Arrays.asList("ANDROID_VR", "VISIONOS"), calls());
    }

    /**
     * ...and when the lane's own sources before ANDROID_VR refuse the video, the answer set aside
     * plays where the lane would have asked ANDROID_VR: no request more than without the flag.
     */
    @Test
    public void aStaleLiveCardsAnswerPlaysAtItsTurnInTheLane() {
        VideoInfoService.setLiveCardHintEnabled(true);
        VideoInfoService.noteVideoLive("vod", true);
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.ANDROID_VR
                ? playable(auth) : unplayable(NOT_AVAILABLE + " " + client, auth);
        assertEquals(AppClient.ANDROID_VR, open("vod").getClient());
        assertEquals(Arrays.asList("ANDROID_VR", "VISIONOS", "TV_TIZEN", "WEB_EMBED"), calls());
    }

    /** Off (the rollback), or never noted, or noted not live: today's live walk. */
    @Test
    public void withoutTheLiveCardTheWalkIsTodays() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> liveAnswer(client, auth);
        VideoInfoService.setLiveCardHintEnabled(true);
        VideoInfoService.noteVideoLive("live", true);
        VideoInfoService.setLiveCardHintEnabled(false);
        open("live");
        assertEquals(Arrays.asList("VISIONOS", "ANDROID_VR"), calls());

        VideoInfoService.setLiveCardHintEnabled(true);
        VideoInfoService.noteVideoLive("live", false);
        open("live");
        assertEquals(Arrays.asList("VISIONOS", "ANDROID_VR"), calls());
    }

    /** A recovery walk keeps its own order, live card or not. */
    @Test
    public void aRecoveryIgnoresTheLiveCard() {
        VideoInfoService.setLiveCardHintEnabled(true);
        VideoInfoService.noteVideoLive("live", true);
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> liveAnswer(client, auth);
        recoverFrom(AppClient.ANDROID_VR);
        open("live");
        assertEquals("VISIONOS", calls().get(0));
    }

    /**
     * NEWTUBE(recovery-kids): v20 on the emulator. VISIONOS refused the kids video, TV_TIZEN was
     * benched, WEB_EMBED served it and its media 403'd: the recovery asked six sources that never
     * serve kids videos before WEB_EMBED again. Now WEB_EMBED is asked first.
     */
    @Test
    public void aKidsRecoveryGoesStraightBackToWhatServesKids() {
        BotWallBook book = ReflectionHelpers.getField(service, "mBotWall");
        book.noteRouteFailed("wifi:100", VideoInfoService.noMediaVideoKey("kids"), "media-403",
                android.os.SystemClock.elapsedRealtime());
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : unplayable(NOT_AVAILABLE, auth);
        assertEquals(AppClient.WEB_EMBED, open("kids").getClient());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED"), calls());

        recoverFrom(AppClient.WEB_EMBED);
        assertEquals(AppClient.WEB_EMBED, open("kids").getClient());
        assertEquals(Arrays.asList("WEB_EMBED"), calls());
    }

    /** The rollback (debug.arc.recovery_kids=0): the v20 recovery order. */
    @Test
    public void withoutTheKidsRecoveryOrderTheRecoveryIsV20s() {
        VideoInfoService.setRecoveryKidsOrderEnabled(false);
        BotWallBook book = ReflectionHelpers.getField(service, "mBotWall");
        book.noteRouteFailed("wifi:100", VideoInfoService.noMediaVideoKey("kids"), "media-403",
                android.os.SystemClock.elapsedRealtime());
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : unplayable(NOT_AVAILABLE, auth);
        open("kids");
        recoverFrom(AppClient.WEB_EMBED);
        open("kids");
        assertEquals(Arrays.asList("ANDROID_VR", "IOS", "ANDROID_REEL", "MWEB", "WEB", "WEB_SAFARI",
                "WEB_EMBED"), calls());
    }

    /** Only a made-for-kids refusal moves the tail: an age gate's recovery keeps the lane's order. */
    @Test
    public void anAgeGatesRecoveryKeepsTheLanesOrder() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : ageGate(auth);
        open("adult");
        recoverFrom(AppClient.WEB_EMBED);
        open("adult");
        assertEquals("ANDROID_VR", calls().get(0));
    }

    private static VideoInfo liveAnswer(AppClient client, boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"OK\"}, \"videoDetails\": {\"videoId\": \"live\","
                + " \"isLive\": true, \"isLiveContent\": true}, \"streamingData\": {\"hlsManifestUrl\":"
                + " \"https://media.invalid/live.m3u8\"" + (client == AppClient.ANDROID_VR
                ? ", \"dashManifestUrl\": \"https://media.invalid/live.mpd\"" : "") + "}}", auth);
    }

    /** What the player's error path leaves for the reload: a recovery walk past {@code suspect}. */
    private void recoverFrom(AppClient suspect) {
        ReflectionHelpers.setField(service, "mRecoveryWalk", true);
        ReflectionHelpers.setField(service, "mRecoverySuspect", suspect);
    }

    /**
     * A plain sign-in request (no age marker) is not an age gate: the lane is asked to the end.
     * (Worded differently per client here: the same LOGIN_REQUIRED text from two clients is read
     * as a localized bot check, BotCheckDetector.isRepeatedLoginRequired.)
     */
    @Test
    public void aSignInRequestWithoutTheAgeMarkerKeepsWalking() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse("{\"playabilityStatus\":"
                + " {\"status\": \"LOGIN_REQUIRED\", \"reason\": \"Private video " + client + "\"}}", auth);
        open("private");
        assertEquals(calls().toString(), 8, calls().size());
    }

    /**
     * A private video as the Pixel saw it (2026-09-29, yZIXLfi8CZQ): VISIONOS and ANDROID_VR say only
     * "Inicia sesión", the others that it is private. The two identical answers used to read as a
     * localized bot check and arm the fifteen-minute circuit, which then answered the next open
     * without a request. Sign-in requests that differ by client are about the video, however many
     * such videos are opened.
     */
    @Test
    public void privateVideosAreNotABotCheck() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse("{\"playabilityStatus\":"
                + " {\"status\": \"LOGIN_REQUIRED\", \"reason\": \"" + (client == AppClient.VISIONOS
                || client == AppClient.ANDROID_VR ? "Inicia sesión" : "Este vídeo es privado") + "\"}}", auth);
        VideoInfo verdict = open("private");
        assertTrue(verdict.isUnplayable());
        assertFalse(verdict.isBotCheckRequired());
        assertEquals(calls().toString(), 8, calls().size());
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        assertFalse(open("private2").isBotCheckRequired());

        // The circuit is not armed: the next video is asked for.
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> playable(auth);
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        assertFalse(open("normal").isUnplayable());
        assertEquals(Collections.singletonList("VISIONOS"), calls());
    }

    /**
     * The same sign-in request from every client (a localized wall with no known bot text) is a
     * challenge of the identity once a second video repeats it; the first is the video's refusal.
     */
    @Test
    public void aUniformSignInRequestIsABotCheckOnTheSecondVideo() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse("{\"playabilityStatus\":"
                + " {\"status\": \"LOGIN_REQUIRED\", \"reason\": \"Inicia sesión\"}}", auth);
        assertFalse(open("first").isBotCheckRequired());
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        assertFalse("the same video again is still one video", open("first").isBotCheckRequired());
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        assertTrue(open("second").isBotCheckRequired());
    }

    /** Signed in, an ordinary video is still one anonymous VISIONOS request. */
    @Test
    public void signedInAnOrdinaryVideoIsOneRequest() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        assertFalse(open("normal").isUnplayable());
        assertEquals(Collections.singletonList("VISIONOS"), calls());
    }

    /**
     * Signed in, what VISIONOS refuses goes to TV_TIZEN with the account (netbench 2026-09-29: it
     * served ordinary, both kinds of 18+ and made-for-kids videos).
     */
    @Test
    public void signedInAKidsVideoIsServedByTheAccountRoute() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN && auth
                ? playable(auth) : unplayable(NOT_AVAILABLE, auth);
        VideoInfo result = open("kids");
        assertEquals(AppClient.TV_TIZEN, result.getClient());
        assertTrue(result.isAuth());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), calls());
    }

    @Test
    public void signedInAnAgeGateIsServedByTheAccountRoute() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN && auth
                ? playable(auth) : ageGate(auth);
        assertEquals(AppClient.TV_TIZEN, open("adult").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), calls());
    }

    /** An account that may not watch it (not age-verified): settled once WEB_EMBED answers the gate too. */
    @Test
    public void signedInAnAgeGateNothingServesIsSettledAtTheThirdRequest() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> ageGate(auth);
        assertTrue(open("adult").isAgeGate());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth", "WEB_EMBED"), calls());
    }

    /** The dead TVHTML5 heads (media 403 / SABR-only with the account) are never asked. */
    @Test
    public void signedInTheTvHeadsAreNeverAsked() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> unplayable(NOT_AVAILABLE + " " + client, auth);
        open("gone");
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth", "WEB_EMBED", "ANDROID_VR", "IOS",
                "ANDROID_REEL", "MWEB", "WEB", "WEB_SAFARI"), calls());
    }

    /**
     * Signed in, the account the server confirmed is the third identity: a removal is settled at
     * the third request (VISIONOS, TV_TIZEN with the account, WEB_EMBED).
     */
    @Test
    public void signedInARemovedVideoIsSettledAtTheThirdRequest() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> parse("{\"playabilityStatus\":"
                + " {\"status\": \"ERROR\", \"reason\": \"This video has been removed by the uploader\"}"
                + (auth ? ", \"responseContext\": {\"serviceTrackingParams\": [{\"service\": \"GFEEDBACK\","
                        + " \"params\": [{\"key\": \"logged_in\", \"value\": \"1\"}]}]}" : "") + "}", auth);
        assertTrue(open("removed").isUnplayable());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth", "WEB_EMBED"), calls());
    }

    /** The account route benched for this video (its media 403'd) is not planned for it. */
    @Test
    public void signedInABenchedAccountRouteIsNotPlanned() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        BotWallBook book = ReflectionHelpers.getField(service, "mBotWall");
        book.noteRouteFailed("wifi:100", VideoInfoService.noMediaVideoKey("kids"), "media-403",
                android.os.SystemClock.elapsedRealtime());
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth) : unplayable(NOT_AVAILABLE, auth);
        assertEquals(AppClient.WEB_EMBED, open("kids").getClient());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED"), calls());
    }

    /** Signed in, a VISIONOS media 403 recovers on the account route and asks VISIONOS last. */
    @Test
    public void signedInRecoveryFromVisionOsStartsOnTheAccountRoute() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> unplayable(NOT_AVAILABLE + " " + client, auth);
        ReflectionHelpers.setField(service, "mRecoveryWalk", true);
        ReflectionHelpers.setField(service, "mRecoverySuspect", AppClient.VISIONOS);
        open("recover");
        List<String> calls = calls();
        assertEquals(calls.toString(), "TV_TIZEN+auth", calls.get(0));
        assertEquals(calls.toString(), "VISIONOS", calls.get(calls.size() - 1));
    }

    // ------------------------------------------------------------------------------------------

    private VideoInfo open(String videoId) {
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        return ReflectionHelpers.callInstanceMethod(service, "firstPlayable",
                ClassParameter.from(String.class, videoId),
                ClassParameter.from(String.class, null),
                ClassParameter.from(boolean.class, VideoInfoBotWallTest.ShadowWalk.signedIn),
                ClassParameter.from(VideoInfoService.CancellationSignal.class, null));
    }

    private static List<String> calls() {
        return new ArrayList<>(VideoInfoBotWallTest.ShadowWalk.calls);
    }

    private void initIfNull(String field, Object value) {
        if (ReflectionHelpers.getField(service, field) == null) {
            ReflectionHelpers.setField(service, field, value);
        }
    }

    private static VideoInfo playable(boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"OK\"}}", auth);
    }

    private static VideoInfo ageGate(boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                + " \"reason\": \"Sign in to confirm your age\", \"desktopLegacyAgeGateReason\": 1}}", auth);
    }

    private static VideoInfo unplayable(String reason, boolean auth) {
        return parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"reason\": \""
                + reason + "\"}}", auth);
    }

    private static VideoInfo parse(String json, boolean auth) {
        try {
            Converter<ResponseBody, ?> converter = JsonPathConverterFactory.create()
                    .responseBodyConverter(VideoInfo.class, new Annotation[0], null);
            VideoInfo info = (VideoInfo) converter.convert(
                    ResponseBody.create(MediaType.get("application/json"), json));
            info.setAuth(auth);
            return info;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
