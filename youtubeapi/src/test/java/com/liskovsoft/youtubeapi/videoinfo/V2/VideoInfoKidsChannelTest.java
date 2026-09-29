package com.liskovsoft.youtubeapi.videoinfo.V2;

import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PhoneSourcePlanner.Lane.SIGNED_IN;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PhoneSourcePlanner.Lane.SIGNED_OUT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.KidsChannelMemory;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * NEWTUBE(kids-channel): the phone's walk with the kids channel memory, as MobileMainApplication
 * configures it. A channel whose video VISIONOS refused and TV_TIZEN served leads its next video
 * (when the app named that video's channel) with TV_TIZEN, and any answer but a serve from it
 * drops the channel.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoBotWallTest.ShadowWalk.class, VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoKidsChannelTest {
    private static final String KIDS = "UCB_5Rmp-wUdVxmCLG6fnNkQ";
    private static final String NOT_AVAILABLE = "This video is not available";
    private VideoInfoService service;
    private KidsChannelMemory memory;

    @Before
    public void setUp() {
        VideoInfoBotWallTest.ShadowWalk.network = "wifi:100";
        VideoInfoBotWallTest.ShadowWalk.transport = "wifi";
        VideoInfoBotWallTest.ShadowWalk.signedIn = false;
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        VideoInfoBotWallTest.ShadowWalk.silent.clear();
        VideoInfoBotWallTest.ShadowWalk.script = VideoInfoKidsChannelTest::kidsChannel;
        VideoInfoService.setPreferNoPotClient(true);
        VideoInfoService.setPreferDashManifestForLive(true);
        VideoInfoService.setKidsChannelHintEnabled(true);
        memory = VideoInfoService.kidsChannels();
        memory.clear();
        service = ReflectionHelpers.callConstructor(VideoInfoService.class);
        initIfNull("mAuthRouteQuarantine", new AuthRouteQuarantineBook());
        initIfNull("mBotWall", new BotWallBook());
        initIfNull("mVideoWinners", Collections.synchronizedMap(
                new java.util.LinkedHashMap<String, AppClient>()));
        initIfNull("mRoutingGeneration", new java.util.concurrent.atomic.AtomicLong());
        ShadowLog.reset();
    }

    @After
    public void tearDown() {
        memory.clear();
        VideoInfoService.setKidsChannelHintEnabled(true);
        VideoInfoService.setPreferNoPotClient(false);
        VideoInfoService.setPreferDashManifestForLive(false);
    }

    /**
     * Issue #5's session: two videos of one kids channel. The first proves the channel (VISIONOS
     * refuses, anonymous TV_TIZEN serves: 2 requests); the second, whose channel the app named,
     * asks TV_TIZEN first and is served at the FIRST request. TV_TIZEN is asked exactly as often
     * as without the memory (once per kids video).
     */
    @Test
    public void theNextVideoOfAKidsChannelIsOneRequest() {
        assertEquals(AppClient.TV_TIZEN, open("k1").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN"), calls());
        assertTrue(memory.isRemembered(SIGNED_OUT, KIDS));
        assertTrue(logs().toString(), logs().contains("kids-channel remember channel="
                + KidsChannelMemory.tag(KIDS) + " video=k1 lane=signed-out src=answer refusedBy=VISIONOS"
                + " answerChannel=" + KidsChannelMemory.tag(KIDS) + " refusalChannel=none"
                + " hintsLeft=" + KidsChannelMemory.HINTS_PER_PROOF));

        VideoInfoService.noteVideoChannel("k2", KIDS);
        VideoInfoService.noteVideoChannel("k2", KIDS); // the tap, then the player: logged once
        VideoInfo second = open("k2");
        assertEquals(AppClient.TV_TIZEN, second.getClient());
        assertFalse(second.isAuth());
        assertEquals(1, java.util.Collections.frequency(logs(), "kids-channel named video=k2 channel="
                + KidsChannelMemory.tag(KIDS)));
        assertEquals(Collections.singletonList("TV_TIZEN"), calls());
        assertTrue(logs().toString(), logs().contains("kids-channel hint video=k2 channel="
                + KidsChannelMemory.tag(KIDS) + " lane=signed-out hintsLeft="
                + KidsChannelMemory.HINTS_PER_PROOF + " order=[TV_TIZEN, VISIONOS, WEB_EMBED, ANDROID_VR,"
                + " IOS, ANDROID_REEL, MWEB, WEB, WEB_SAFARI]"));
    }

    /** An open whose channel the app did not name (a share link) walks exactly as today. */
    @Test
    public void anOpenWithoutANamedChannelIsTodays() {
        open("k1");
        assertTrue(memory.isRemembered(SIGNED_OUT, KIDS));
        assertEquals(AppClient.TV_TIZEN, open("k2").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN"), calls());
    }

    /** The answers name no channel (a stripped videoDetails): the one the app named is recorded. */
    @Test
    public void theChannelTheAppNamedIsRecordedWhenTheAnswersNameNone() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN
                ? VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"OK\"}}", auth)
                : unplayable(NOT_AVAILABLE, auth);
        VideoInfoService.noteVideoChannel("k1", KIDS);
        open("k1");
        assertTrue(logs().toString(), logs().toString().contains("src=named"));
        VideoInfoService.noteVideoChannel("k2", KIDS);
        open("k2");
        assertEquals(Collections.singletonList("TV_TIZEN"), calls());
    }

    /** A share link's proof with no channel anywhere waits for /next to name it. */
    @Test
    public void aProofWithoutAChannelIsRecordedOnceNextNamesIt() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN
                ? VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"OK\"}}", auth)
                : unplayable(NOT_AVAILABLE, auth);
        open("k1");
        assertFalse(memory.isRemembered(SIGNED_OUT, KIDS));
        assertTrue(logs().toString(), logs().contains("kids-channel pending video=k1 lane=signed-out"
                + " refusedBy=VISIONOS"));

        VideoInfoService.noteVideoChannel("k1", KIDS); // /next answered
        assertTrue(memory.isRemembered(SIGNED_OUT, KIDS));
        VideoInfoService.noteVideoChannel("k2", KIDS);
        open("k2");
        assertEquals(Collections.singletonList("TV_TIZEN"), calls());
    }

    /**
     * A wrong hint TV_TIZEN refuses: the channel is dropped and the walk goes on in the lane's
     * order, VISIONOS next (2 requests, the second the request of today's walk); the channel's
     * next video is VISIONOS first again.
     */
    @Test
    public void aRefusalFromTheHintedTizenDropsTheChannel() {
        open("k1");
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN
                ? unplayable(NOT_AVAILABLE, auth) : playable(auth, KIDS);
        VideoInfoService.noteVideoChannel("o1", KIDS);
        assertEquals(AppClient.VISIONOS, open("o1").getClient());
        assertEquals(Arrays.asList("TV_TIZEN", "VISIONOS"), calls());
        assertFalse(memory.isRemembered(SIGNED_OUT, KIDS));
        assertTrue(logs().toString(), logs().contains("kids-channel drop channel="
                + KidsChannelMemory.tag(KIDS) + " video=o1 lane=signed-out reason=refused"));

        VideoInfoService.noteVideoChannel("o2", KIDS);
        open("o2");
        assertEquals(Collections.singletonList("VISIONOS"), calls());
    }

    /**
     * The worst case: a mixed channel whose next videos are ordinary, which anonymous TV_TIZEN
     * serves. Each is one request (TV_TIZEN instead of VISIONOS) for at most HINTS_PER_PROOF opens;
     * the next open re-checks in the lane's order, VISIONOS serves it, and the channel is dropped.
     */
    @Test
    public void aMixedChannelCostsAtMostTheHintsOfOneProof() {
        open("k1");
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> playable(auth, KIDS);
        int tizen = 0;
        for (int i = 1; i <= KidsChannelMemory.HINTS_PER_PROOF; i++) {
            VideoInfoService.noteVideoChannel("o" + i, KIDS);
            assertEquals(AppClient.TV_TIZEN, open("o" + i).getClient());
            assertEquals(Collections.singletonList("TV_TIZEN"), calls());
            tizen++;
        }
        VideoInfoService.noteVideoChannel("recheck", KIDS);
        assertEquals(AppClient.VISIONOS, open("recheck").getClient());
        assertEquals(Collections.singletonList("VISIONOS"), calls());
        assertTrue(logs().toString(), logs().contains("kids-channel reproof video=recheck channel="
                + KidsChannelMemory.tag(KIDS) + " lane=signed-out"));
        assertTrue(logs().toString(), logs().contains("kids-channel drop channel=" + KidsChannelMemory.tag(KIDS)
                + " video=recheck lane=signed-out reason=served-by-VISIONOS"));
        assertFalse(memory.isRemembered(SIGNED_OUT, KIDS));
        assertEquals(KidsChannelMemory.HINTS_PER_PROOF, tizen);

        VideoInfoService.noteVideoChannel("after", KIDS);
        open("after");
        assertEquals(Collections.singletonList("VISIONOS"), calls());
    }

    /** A made-for-kids channel re-proves itself: one VISIONOS round trip per HINTS_PER_PROOF + 1 opens. */
    @Test
    public void aKidsChannelIsRecheckedAndKept() {
        open("k0");
        for (int i = 1; i <= KidsChannelMemory.HINTS_PER_PROOF; i++) {
            VideoInfoService.noteVideoChannel("k" + i, KIDS);
            open("k" + i);
            assertEquals(Collections.singletonList("TV_TIZEN"), calls());
        }
        VideoInfoService.noteVideoChannel("recheck", KIDS);
        open("recheck");
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN"), calls());
        assertEquals(KidsChannelMemory.HINTS_PER_PROOF, memory.hintsLeft(SIGNED_OUT, KIDS));
    }

    /** A challenge from the hinted TV_TIZEN drops the channel; the walk carries on without it. */
    @Test
    public void aChallengeOnTheHintedTizenDropsTheChannel() {
        open("k1");
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN
                ? VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                        + " \"reason\": \"Sign in to confirm you’re not a bot\"}}", auth)
                : client == AppClient.WEB_EMBED ? playable(auth, KIDS) : unplayable(NOT_AVAILABLE, auth);
        VideoInfoService.noteVideoChannel("k2", KIDS);
        assertEquals(AppClient.WEB_EMBED, open("k2").getClient());
        assertEquals(Arrays.asList("TV_TIZEN", "VISIONOS", "WEB_EMBED"), calls());
        assertFalse(memory.isRemembered(SIGNED_OUT, KIDS));
        assertTrue(logs().toString(), logs().toString().contains("reason=challenged"));
    }

    /** No answer from the hinted TV_TIZEN (a timeout) drops it too, and VISIONOS is next. */
    @Test
    public void noAnswerFromTheHintedTizenDropsTheChannel() {
        open("k1");
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN
                ? null : client == AppClient.VISIONOS ? playable(auth, KIDS) : unplayable(NOT_AVAILABLE, auth);
        VideoInfoBotWallTest.ShadowWalk.silent.add(AppClient.TV_TIZEN);
        VideoInfoService.noteVideoChannel("k2", KIDS);
        assertEquals(AppClient.VISIONOS, open("k2").getClient());
        assertEquals(Arrays.asList("TV_TIZEN", "VISIONOS"), calls());
        assertFalse(memory.isRemembered(SIGNED_OUT, KIDS));
        assertTrue(logs().toString(), logs().toString().contains("reason=no-response"));
    }

    /** Health outranks the hint: a TV_TIZEN benched for this video is not led with (nor admitted). */
    @Test
    public void aBenchedTizenIsNeverLedWith() {
        open("k1");
        BotWallBook book = ReflectionHelpers.getField(service, "mBotWall");
        book.noteRouteFailed("wifi:100", VideoInfoService.noMediaVideoKey("k2"), "media-403",
                android.os.SystemClock.elapsedRealtime());
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth, KIDS) : unplayable(NOT_AVAILABLE, auth);
        VideoInfoService.noteVideoChannel("k2", KIDS);
        assertEquals(AppClient.WEB_EMBED, open("k2").getClient());
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED"), calls());
        assertTrue(logs().toString(), logs().toString().contains("kids-channel hint-skip reason=benched"));
        assertTrue("a skipped hint is no evidence against the channel", memory.isRemembered(SIGNED_OUT, KIDS));
    }

    /**
     * A recovery walk keeps its own order: the suspect TV_TIZEN is asked last, hint or not (and
     * VISIONOS, which refused k1 moments ago, after it: RecentRefusals).
     */
    @Test
    public void aRecoveryWalkIgnoresTheHint() {
        open("k1");
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth, KIDS) : unplayable(NOT_AVAILABLE, auth);
        ReflectionHelpers.setField(service, "mRecoveryWalk", true);
        ReflectionHelpers.setField(service, "mRecoverySuspect", AppClient.TV_TIZEN);
        VideoInfoService.noteVideoChannel("k1", KIDS);
        open("k1");
        assertEquals(Arrays.asList("WEB_EMBED"), calls());
    }

    /**
     * A live answer from the hinted TV_TIZEN (the app names no live video's channel, but a card can
     * be stale) is set aside: the live sources answer as they do today and the channel is kept.
     */
    @Test
    public void aLiveAnswerFromTheHintedTizenIsSetAside() {
        open("k1");
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> VideoInfoBotWallTest.parse(
                "{\"playabilityStatus\": {\"status\": \"OK\"}, \"videoDetails\": {\"videoId\": \"live\","
                        + " \"channelId\": \"" + KIDS + "\", \"isLive\": true, \"isLiveContent\": true},"
                        + " \"streamingData\": {\"hlsManifestUrl\": \"https://media.invalid/live.m3u8\""
                        + (client == AppClient.ANDROID_VR
                        ? ", \"dashManifestUrl\": \"https://media.invalid/live.mpd\"" : "") + "}}", auth);
        VideoInfoService.noteVideoChannel("live", KIDS);
        assertEquals(AppClient.ANDROID_VR, open("live").getClient());
        assertEquals(Arrays.asList("TV_TIZEN", "VISIONOS", "ANDROID_VR"), calls());
        assertTrue(memory.isRemembered(SIGNED_OUT, KIDS));
    }

    /**
     * A refusing live answer from the hinted TV_TIZEN (an ended stream's recording gone) is an
     * answer like any other: it drops the channel and the walk goes on, VISIONOS next.
     */
    @Test
    public void aRefusingLiveAnswerFromTheHintedTizenIsAnAnswer() {
        open("k1");
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> VideoInfoBotWallTest.parse(
                "{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"reason\": \"This live stream"
                        + " recording is not available\"}, \"videoDetails\": {\"videoId\": \"ended\","
                        + " \"isLiveContent\": true}}", auth);
        VideoInfoService.noteVideoChannel("ended", KIDS);
        open("ended");
        List<String> calls = calls();
        assertEquals(calls.toString(), Arrays.asList("TV_TIZEN", "VISIONOS"), calls.subList(0, 2));
        assertEquals(1, Collections.frequency(calls, "TV_TIZEN"));
        assertFalse(memory.isRemembered(SIGNED_OUT, KIDS));
    }

    /**
     * Signed out, a bot-wall suspicion keeps the anonymous TV_TIZEN from the head: the open walks
     * as today (VISIONOS, then TV_TIZEN by the refusal rule), and the channel is kept.
     */
    @Test
    public void aSuspectedWallKeepsTheAnonymousHintOut() {
        open("k1");
        BotWallBook book = ReflectionHelpers.getField(service, "mBotWall");
        assertEquals(BotWallBook.Challenge.SUSPECT, book.noteChallenge("wifi:100",
                new BotWallBook.WalkEvidence(), AppClient.VISIONOS, VideoInfoService.noMediaVideoKey("other"),
                android.os.SystemClock.elapsedRealtime()));
        VideoInfoService.noteVideoChannel("k2", KIDS);
        assertEquals(AppClient.TV_TIZEN, open("k2").getClient());
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN"), calls());
        assertTrue(logs().toString(), logs().toString().contains("kids-channel hint-skip reason=suspicion"));
        assertTrue(memory.isRemembered(SIGNED_OUT, KIDS));
    }

    /**
     * Two silences in a row are still a dead link, whoever the first was (today a kids walk's
     * second and third asks, TV_TIZEN and WEB_EMBED, count the same): the walk stops, the channel
     * is dropped.
     */
    @Test
    public void twoSilencesAfterTheHintAreStillADeadLink() {
        open("k1");
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> null;
        VideoInfoBotWallTest.ShadowWalk.silent.add(AppClient.TV_TIZEN);
        VideoInfoBotWallTest.ShadowWalk.silent.add(AppClient.VISIONOS);
        VideoInfoService.noteVideoChannel("k2", KIDS);
        assertEquals(null, open("k2"));
        assertEquals(Arrays.asList("TV_TIZEN", "VISIONOS"), calls());
        assertFalse(memory.isRemembered(SIGNED_OUT, KIDS));
    }

    /** An account change while the hinted TV_TIZEN is answering leaves nothing behind. */
    @Test
    public void anAccountChangeDuringTheHintedAskLeavesNothingBehind() {
        open("k1");
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> {
            service.onAccountChanged();
            memory.remember(SIGNED_OUT, KIDS, memory.generation()); // the new account's own evidence
            return playable(auth, KIDS);
        };
        VideoInfoService.noteVideoChannel("k2", KIDS);
        assertEquals(AppClient.TV_TIZEN, open("k2").getClient());
        assertEquals("not spent by the walk from before the change", KidsChannelMemory.HINTS_PER_PROOF,
                memory.hintsLeft(SIGNED_OUT, KIDS));
        assertTrue(logs().toString(), logs().toString().contains("hintsLeft=-1"));
    }

    /** The card named one channel, the answer another: the hint is spent and the answer's logged. */
    @Test
    public void aHintServedForAnotherChannelIsLogged() {
        open("k1");
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> playable(auth, "UCother");
        VideoInfoService.noteVideoChannel("k2", KIDS);
        open("k2");
        assertEquals(Collections.singletonList("TV_TIZEN"), calls());
        assertTrue(logs().toString(), logs().toString().contains("answerChannel="
                + KidsChannelMemory.tag("UCother")));
    }

    /** Signed in, the hint leads with the account route: one request, with the account. */
    @Test
    public void signedInTheNextKidsVideoIsOneAccountRequest() {
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN && auth
                ? playable(auth, KIDS) : unplayable(NOT_AVAILABLE, auth);
        open("k1");
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), calls());
        assertTrue(memory.isRemembered(SIGNED_IN, KIDS));

        VideoInfoService.noteVideoChannel("k2", KIDS);
        VideoInfo second = open("k2");
        assertTrue(second.isAuth());
        assertEquals(Collections.singletonList("TV_TIZEN+auth"), calls());

        // Benched for the next video: VISIONOS first, and the account route stays out.
        BotWallBook book = ReflectionHelpers.getField(service, "mBotWall");
        book.noteRouteFailed("wifi:100", VideoInfoService.noMediaVideoKey("k3"), "media-403",
                android.os.SystemClock.elapsedRealtime());
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? playable(auth, KIDS) : unplayable(NOT_AVAILABLE, auth);
        VideoInfoService.noteVideoChannel("k3", KIDS);
        open("k3");
        assertEquals(Arrays.asList("VISIONOS", "WEB_EMBED"), calls());
    }

    /** A record is its lane's: signed out's does not lead a signed-in walk, nor the reverse. */
    @Test
    public void recordsDoNotCrossLanes() {
        open("k1");
        assertTrue(memory.isRemembered(SIGNED_OUT, KIDS));
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.TV_TIZEN && auth
                ? playable(auth, KIDS) : unplayable(NOT_AVAILABLE, auth);
        VideoInfoService.noteVideoChannel("k2", KIDS);
        open("k2");
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN+auth"), calls());

        memory.drop(SIGNED_OUT, KIDS, memory.generation());
        assertTrue(memory.isRemembered(SIGNED_IN, KIDS));
        VideoInfoBotWallTest.ShadowWalk.signedIn = false;
        VideoInfoBotWallTest.ShadowWalk.script = VideoInfoKidsChannelTest::kidsChannel;
        VideoInfoService.noteVideoChannel("k3", KIDS);
        open("k3");
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN"), calls());
    }

    /** A sign-in, switch or sign-out forgets every channel. */
    @Test
    public void anAccountChangeForgetsTheChannels() {
        open("k1");
        service.onAccountChanged();
        assertFalse(memory.isRemembered(SIGNED_OUT, KIDS));
        assertTrue(logs().toString(), logs().contains("kids-channel cleared records=1 reason=account-change"));
        VideoInfoService.noteVideoChannel("k2", KIDS);
        open("k2");
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN"), calls());
    }

    /** The rollback (debug.arc.kids_channel=0): today's walk, and nothing is remembered. */
    @Test
    public void switchedOffTheWalkIsTodays() {
        VideoInfoService.setKidsChannelHintEnabled(false);
        open("k1");
        assertFalse(memory.isRemembered(SIGNED_OUT, KIDS));
        memory.remember(SIGNED_OUT, KIDS, memory.generation());
        memory.noteVideoChannel("k2", KIDS);
        open("k2");
        assertEquals(Arrays.asList("VISIONOS", "TV_TIZEN"), calls());
        for (String line : logs()) {
            assertFalse(line, line.startsWith("kids-channel"));
        }
    }

    // ------------------------------------------------------------------------------------------

    /** A made-for-kids channel: VISIONOS and ANDROID_VR refuse, TV_TIZEN serves and names it. */
    private static VideoInfo kidsChannel(AppClient client, boolean auth) {
        return client == AppClient.TV_TIZEN ? playable(auth, KIDS) : unplayable(NOT_AVAILABLE, auth);
    }

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

    private static List<String> logs() {
        List<String> lines = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("NetPath")) {
            lines.add(item.msg);
        }
        return lines;
    }

    private void initIfNull(String field, Object value) {
        if (ReflectionHelpers.getField(service, field) == null) {
            ReflectionHelpers.setField(service, field, value);
        }
    }

    private static VideoInfo playable(boolean auth, String channel) {
        return VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"OK\"},"
                + " \"videoDetails\": {\"videoId\": \"v\", \"channelId\": \"" + channel + "\"}}", auth);
    }

    private static VideoInfo unplayable(String reason, boolean auth) {
        return VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"reason\": \""
                + reason + "\"}}", auth);
    }
}
