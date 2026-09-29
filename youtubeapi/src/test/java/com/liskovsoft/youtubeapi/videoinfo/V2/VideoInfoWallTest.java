package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.youtubeapi.app.PlaybackIdentity;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
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
 * NEWTUBE(wall-memory, playback-identity): the one-minute wall, the service's side - the recovery
 * benches every source whose media 403'd on the video, a wall is remembered per (visitor, source),
 * the VOD order asks TV_TIZEN before ANDROID_VR, and the first wall of a web-session source re-rolls
 * the playback identity (switch, lane, once per video, budget) - and the synthetic wall's model.
 *
 * <p>The regression: r11, 5592, MeJVWBSsPAY (embedding disabled) on a walled visitor. VISIONOS walled
 * at 60.0 s; the recovery asked WEB_EMBED (refused) and ANDROID_VR (walled), then VISIONOS again,
 * alternating to the reload cap. TV_TIZEN, never asked, plays that visitor past the wall.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoBotWallTest.ShadowWalk.class, VideoInfoBotWallTest.ShadowTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoWallTest {
    private static final String WALLED = "walled-visitor";
    private static final String EMBED_DISABLED = "Playback on other websites has been disabled by the video owner";
    private VideoInfoService service;

    @Before
    public void setUp() {
        VideoInfoBotWallTest.ShadowWalk.network = "wifi:100";
        VideoInfoBotWallTest.ShadowWalk.transport = "wifi";
        VideoInfoBotWallTest.ShadowWalk.signedIn = false;
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        VideoInfoBotWallTest.ShadowWalk.appVisitor = WALLED; // the web session adopts the app's visitor
        VideoInfoBotWallTest.ShadowTokenGate.webVisitor = WALLED;
        VideoInfoBotWallTest.ShadowTokenGate.rerolls.clear();
        VideoInfoBotWallTest.ShadowTokenGate.rerollSupported = true;
        VideoInfoBotWallTest.ShadowTokenGate.tokenResets = 0;
        // MeJVWBSsPAY: embedding disabled; every other source serves.
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? unplayable(EMBED_DISABLED, auth) : served(client, auth);
        VideoInfoService.setPreferNoPotClient(true);
        VideoInfoService.setPreferDashManifestForLive(true);
        VideoInfoService.resetPlaybackRerollForTest();
        PlaybackIdentity.resetForTest();
        DebugPlaybackWall.resetForTest();
        VideoInfoService.setWallMemoryEnabled(true);
        VideoInfoService.setVodVrLateEnabled(true);
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
        VideoInfoService.setPreferNoPotClient(false);
        VideoInfoService.setPreferDashManifestForLive(false);
        VideoInfoService.setPlaybackRerollEnabled(false);
        VideoInfoService.setPlaybackRerollSignedIn(false);
        VideoInfoService.setWallMemoryEnabled(false);
        VideoInfoService.setVodVrLateEnabled(false);
        VideoInfoService.setLiveCardHintEnabled(false);
        VideoInfoService.resetPlaybackRerollForTest();
        PlaybackIdentity.resetForTest();
        DebugPlaybackWall.resetForTest();
        VideoInfoBotWallTest.ShadowWalk.appVisitor = "app-visitor";
        VideoInfoBotWallTest.ShadowTokenGate.webVisitor = "web-visitor";
    }

    /**
     * The regression: VISIONOS walls, the recovery skips the refusing WEB_EMBED to TV_TIZEN, and the
     * second 403 (were there one) would not go back to VISIONOS: both are benched for the video.
     */
    @Test
    public void theWalledRecoveryReachesTizenAndNeverAlternates() {
        assertEquals(AppClient.VISIONOS, open("MeJVWBSsPAY").getClient());
        assertEquals(Arrays.asList("VISIONOS"), calls());

        wall("MeJVWBSsPAY");
        assertEquals(AppClient.TV_TIZEN, open("MeJVWBSsPAY").getClient());
        assertEquals(Arrays.asList("WEB_EMBED", "TV_TIZEN"), calls());
        assertTrue(logs().toString(), has("playback-wall video=MeJVWBSsPAY client=VISIONOS"));
        assertTrue(logs().toString(), has("walled-here=[VISIONOS]"));

        // Had TV_TIZEN's links gone stale minutes in: it goes last as the suspect, VISIONOS stays
        // benched, WEB_EMBED (refused moments ago) after the rest.
        mediaForbidden("MeJVWBSsPAY", 130_000, 54_000, 125_000); // a stale link minutes in
        VideoInfo next = open("MeJVWBSsPAY");
        assertEquals(AppClient.ANDROID_REEL, next.getClient());
        assertTrue(logs().toString(), has("walled-here=[VISIONOS]"));
        // WEB_EMBED refused it moments ago (RecentRefusals): after the rest.
        assertEquals(Arrays.asList("ANDROID_REEL"), calls());
    }

    /** The wall is remembered for the visitor: the next video does not start on VISIONOS. */
    @Test
    public void aWalledVisitorsNextOpenAsksTheWalledSourceLast() {
        open("v1");
        wall("v1");
        open("v1");

        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> served(client, auth);
        assertEquals(AppClient.WEB_EMBED, open("ordinary").getClient());
        assertEquals(Arrays.asList("WEB_EMBED"), calls());
        assertTrue(logs().toString(), has("walled=[VISIONOS]"));

        // Not embeddable: TV_TIZEN, the same visitor, next.
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> client == AppClient.WEB_EMBED
                ? unplayable(EMBED_DISABLED, auth) : served(client, auth);
        assertEquals(AppClient.TV_TIZEN, open("no-embed").getClient());
        assertEquals(Arrays.asList("WEB_EMBED", "TV_TIZEN"), calls());
    }

    /** Another visitor (a fresh device, or a re-rolled identity) is not walled: VISIONOS, one request. */
    @Test
    public void aFreshVisitorsOrdinaryOpenIsUnchanged() {
        open("v1");
        wall("v1");
        open("v1"); // its recovery

        VideoInfoBotWallTest.ShadowTokenGate.webVisitor = "fresh-visitor";
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> served(client, auth);
        assertEquals(AppClient.VISIONOS, open("ordinary").getClient());
        assertEquals(Arrays.asList("VISIONOS"), calls());

        // Nothing walled at all: the same.
        VideoInfoService.resetPlaybackRerollForTest();
        VideoInfoBotWallTest.ShadowTokenGate.webVisitor = WALLED;
        assertEquals(AppClient.VISIONOS, open("ordinary2").getClient());
        assertEquals(Arrays.asList("VISIONOS"), calls());
    }

    /**
     * An ordinary 403 (the first links refused at 0 s) is no wall: nothing is remembered, nothing
     * benched for the video - the recovery only asks its suspect last, as before.
     */
    @Test
    public void anOrdinary403IsNotAWall() {
        open("v1");
        mediaForbidden("v1", 3_000, -1, -1); // the first links refused
        assertFalse(has("playback-wall"));
        assertEquals(AppClient.TV_TIZEN, open("v1").getClient());
        assertFalse(logs().toString(), has("walled-here"));

        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> served(client, auth);
        assertEquals(AppClient.VISIONOS, open("other").getClient());
        assertEquals(Arrays.asList("VISIONOS"), calls());
    }

    /**
     * The wall is on stream position (r11 expiry recheck, YQHsXMglC9A on both walled visitors): a
     * jump to 73.7 s is refused at the first request past 60 s - a wall, since 0-10 s were served -
     * and a reload resumed at 73.7 s, nothing before 60 s served, is the known pair again.
     */
    @Test
    public void aJumpPastTheWallIsTheWall() {
        open("YQHsXMglC9A");
        mediaForbidden("YQHsXMglC9A", 70_001, 0, 5_000);
        assertTrue(logs().toString(), has("playback-wall video=YQHsXMglC9A client=VISIONOS visitor="));
        assertTrue(logs().toString(), has("how=served-below forbiddenStartMs=70001"));
        assertEquals(AppClient.TV_TIZEN, open("YQHsXMglC9A").getClient());

        servedBy("resumed", AppClient.VISIONOS);
        mediaForbidden("resumed", 73_699, -1, -1);
        assertTrue(logs().toString(), has("playback-wall video=resumed client=VISIONOS"));
        assertTrue(logs().toString(), has("how=known forbiddenStartMs=73699"));
    }

    /**
     * A resume at 200 s refused with nothing before it served is ambiguous: nothing is remembered
     * until a second refusal past the wall on the video (a reload, another source) confirms it -
     * and then both pairs are.
     */
    @Test
    public void aResumePastTheWallNeedsAConfirmation() {
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> served(client, auth);
        open("resume200");
        mediaForbidden("resume200", 200_000, -1, -1);
        assertTrue(logs().toString(), has("playback-wall-unconfirmed video=resume200 client=VISIONOS"));
        assertFalse(has("playback-wall video="));
        assertTrue(VideoInfoService.wallMemoryForTest().isEmpty(VideoInfoService.nowForTest()));

        // The recovery's WEB_EMBED is refused past the wall too: confirmed, both remembered.
        assertEquals(AppClient.WEB_EMBED, open("resume200").getClient());
        mediaForbidden("resume200", 200_000, -1, -1);
        assertTrue(logs().toString(), has("how=confirmed"));
        assertTrue(logs().toString(), has("confirms=[VISIONOS]"));
        String web = com.liskovsoft.googlecommon.common.helpers.VisitorFingerprint.of(WALLED);
        assertTrue(VideoInfoService.wallMemoryForTest().isWalled(web, AppClient.VISIONOS,
                VideoInfoService.nowForTest()));
    }

    /** Past-the-wall media already served: a later 403 there is not the wall (a stale link). */
    @Test
    public void aStaleLinkPastServedMediaIsNotTheWall() {
        open("long");
        mediaForbidden("long", 600_000, 0, 595_000);
        assertFalse(has("playback-wall"));
        assertTrue(VideoInfoService.wallMemoryForTest().isEmpty(VideoInfoService.nowForTest()));
    }

    /** Live: the walled ANDROID_VR stays the live card's first source (the wall is a VOD one). */
    @Test
    public void aLiveCardKeepsItsSourceFirst() {
        VideoInfoService.wallMemoryForTest().record(
                com.liskovsoft.googlecommon.common.helpers.VisitorFingerprint.of(WALLED), AppClient.ANDROID_VR,
                VideoInfoService.nowForTest());
        VideoInfoService.setLiveCardHintEnabled(true);
        VideoInfoService.noteVideoLive("live", true);
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> liveAnswer(client, auth);
        assertEquals(AppClient.ANDROID_VR, open("live").getClient());
        assertEquals(Arrays.asList("ANDROID_VR"), calls());
    }

    /** Wall memory off (debug.arc.wall_memory=0): the v21 recovery, VISIONOS back at the head. */
    @Test
    public void offTheRecoveryIsAsBefore() {
        VideoInfoService.setWallMemoryEnabled(false);
        VideoInfoService.setVodVrLateEnabled(false);
        open("MeJVWBSsPAY");
        wall("MeJVWBSsPAY");
        assertEquals(AppClient.ANDROID_VR, open("MeJVWBSsPAY").getClient());
        assertEquals(Arrays.asList("WEB_EMBED", "ANDROID_VR"), calls());
        wall("MeJVWBSsPAY");
        assertEquals(AppClient.VISIONOS, open("MeJVWBSsPAY").getClient());
        assertFalse(logs().toString(), has("memory=y"));
    }

    /**
     * Re-roll on: the first wall of VISIONOS re-rolls the web session's identity (no token reset of
     * its own), once per video; the recovery still goes to TV_TIZEN, not to a fresh VISIONOS.
     */
    @Test
    public void theFirstWallReRollsThePlaybackIdentity() {
        VideoInfoService.setPlaybackRerollEnabled(true);
        open("MeJVWBSsPAY");
        wall("MeJVWBSsPAY");
        assertEquals(Arrays.asList("MeJVWBSsPAY/1"), VideoInfoBotWallTest.ShadowTokenGate.rerolls);
        assertTrue(logs().toString(), has("playback-identity reroll-armed video=MeJVWBSsPAY pos=60000"
                + " budgetLeft=1 lane=signed-out"));
        assertEquals(AppClient.TV_TIZEN, open("MeJVWBSsPAY").getClient());
        assertEquals(Arrays.asList("WEB_EMBED", "TV_TIZEN"), calls());

        // The next video: the web session's visitor is the fresh one, VISIONOS first again.
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> served(client, auth);
        assertEquals(AppClient.VISIONOS, open("ordinary").getClient());
        assertEquals(Arrays.asList("VISIONOS"), calls());
    }

    /** A 403 that is not the wall, or from a source that is not the web session's: no re-roll. */
    @Test
    public void onlyAWebSessionWallReRolls() {
        VideoInfoService.setPlaybackRerollEnabled(true);
        open("v1");
        mediaForbidden("v1", 3_000, -1, -1); // the first links refused
        assertEquals(AppClient.TV_TIZEN, open("v1").getClient());
        wall("v1"); // TV_TIZEN at the wall: remembered, not re-rolled
        assertTrue(VideoInfoBotWallTest.ShadowTokenGate.rerolls.isEmpty());
        assertTrue(logs().toString(), has("playback-wall video=v1 client=TV_TIZEN"));
    }

    /** Once per video, the visitor already left, the budget, signed in, no web session. */
    @Test
    public void theReRollsGuards() {
        VideoInfoService.setPlaybackRerollEnabled(true);
        assertTrue(service.rerollPlaybackIdentityOnWall("v1", WALLED, 60_000));
        assertFalse(service.rerollPlaybackIdentityOnWall("v1", null, 60_000));
        assertTrue(has("playback-identity reroll-skip reason=once-per-video video=v1 pos=60000"));
        // v1's re-roll left the walled visitor: a second wall of it (ANDROID_VR's) spends nothing.
        assertFalse(service.rerollPlaybackIdentityOnWall("v2", WALLED, 60_000));
        assertTrue(has("playback-identity reroll-skip reason=visitor-left video=v2 pos=60000"));

        VideoInfoBotWallTest.ShadowTokenGate.webVisitor = "second";
        assertTrue(service.rerollPlaybackIdentityOnWall("v3", "second", 60_000));
        VideoInfoBotWallTest.ShadowTokenGate.webVisitor = "third";
        assertFalse(service.rerollPlaybackIdentityOnWall("v4", "third", 60_000));
        assertTrue(logs().toString(), has("playback-identity reroll-skip reason=budget video=v4 pos=60000"));
        assertEquals(Arrays.asList("v1/1", "v3/0"), VideoInfoBotWallTest.ShadowTokenGate.rerolls);

        VideoInfoService.resetPlaybackRerollForTest();
        PlaybackIdentity.resetForTest();
        VideoInfoBotWallTest.ShadowWalk.signedIn = true;
        assertFalse(service.rerollPlaybackIdentityOnWall("v5", "third", 60_000));
        assertTrue(has("playback-identity reroll-skip reason=signed-in video=v5 pos=60000"));
        VideoInfoService.setPlaybackRerollSignedIn(true);
        assertTrue(service.rerollPlaybackIdentityOnWall("v5", "third", 60_000));
        assertTrue(logs().toString(), has("playback-identity reroll-armed video=v5 pos=60000"
                + " budgetLeft=1 lane=signed-in"));
        assertEquals("after v5", 1, PlaybackIdentity.book().budgetLeft(VideoInfoService.nowForTest()));

        VideoInfoBotWallTest.ShadowWalk.signedIn = false;
        VideoInfoBotWallTest.ShadowTokenGate.rerollSupported = false;
        assertFalse(service.rerollPlaybackIdentityOnWall("v6", null, 60_000));
        assertTrue(has("playback-identity reroll-skip reason=no-web-session video=v6 pos=60000"));
        assertEquals(1, PlaybackIdentity.book().budgetLeft(VideoInfoService.nowForTest()));
    }

    /** Off (the library default): a wall re-rolls nothing. */
    @Test
    public void reRollOffNothingHappens() {
        open("v1");
        wall("v1");
        assertTrue(VideoInfoBotWallTest.ShadowTokenGate.rerolls.isEmpty());
        assertFalse(has("reroll"));
    }

    /** A kept re-rolled identity that walls too is dropped. */
    @Test
    public void aKeptIdentityThatWallsIsDropped() {
        VideoInfoService.setPlaybackKeepEnabled(true);
        // PlaybackIdentity reads the real clock (only the shadowed service's is Robolectric's)
        PlaybackIdentity.book().keep("kept-visitor", System.currentTimeMillis());
        VideoInfoBotWallTest.ShadowTokenGate.webVisitor = "kept-visitor";
        VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> {
            VideoInfo info = served(client, auth);
            info.setRequestVisitorData("kept-visitor");
            return info;
        };
        open("v1");
        wall("v1");
        assertNull(PlaybackIdentity.keptVisitor());
        assertTrue(logs().toString(), has("keptDropped=y"));
    }

    /**
     * The synthetic wall walls the web-session sources' media for every visitor but a re-rolled one
     * (as r11 measured the real one: TV_TIZEN and WEB_EMBED played on the walled visitor); answers
     * are known by their URLs' ei, plain or inside a cipher.
     */
    @Test
    public void theSyntheticWallFollowsTheVisitorAndTheSource() {
        DebugPlaybackWall.setEnabled(true);
        DebugPlaybackWall.noteAnswer(AppClient.VISIONOS, answer("old", "AAA111"));
        assertTrue(DebugPlaybackWall.isWalledEi("AAA111"));
        DebugPlaybackWall.noteAnswer(AppClient.ANDROID_VR, answer("old", "AAA222"));
        assertTrue(DebugPlaybackWall.isWalledEi("AAA222"));
        DebugPlaybackWall.noteAnswer(AppClient.TV_TIZEN, answer("old", "TTT111"));
        assertFalse(DebugPlaybackWall.isWalledEi("TTT111"));
        DebugPlaybackWall.noteAnswer(AppClient.WEB_EMBED, answer("embed", "EEE111"));
        assertFalse(DebugPlaybackWall.isWalledEi("EEE111"));

        DebugPlaybackWall.lift("fresh");
        DebugPlaybackWall.noteAnswer(AppClient.VISIONOS, answer("fresh", "BBB222"));
        assertFalse(DebugPlaybackWall.isWalledEi("BBB222"));

        assertEquals("CCC333", DebugPlaybackWall.eiOf("s=AOq&url=https%3A%2F%2Frr1.googlevideo.com%2Fvideoplayback"
                + "%3Fitag%3D137%26ei%3DCCC333%26id%3Dx"));
        assertNull(DebugPlaybackWall.eiOf("https://rr1.googlevideo.com/videoplayback?itag=137"));

        DebugPlaybackWall.setEnabled(false);
        assertFalse("off: nothing is walled", DebugPlaybackWall.isWalledEi("AAA111"));
    }

    /** The wall as r11 saw it: 0-55 s served, the request at 60 s refused. */
    private void wall(String videoId) {
        mediaForbidden(videoId, 60_000, 0, 55_000);
    }

    /**
     * What ErrorFixerController does on a media 403: anchor, quarantine, note (with what the engine
     * saw of this open's media requests), then the recovery cursor.
     */
    private void mediaForbidden(String videoId, long forbiddenStartMs, long lowestServedMs, long highestServedMs) {
        service.anchorRouteToVideo(videoId);
        service.markCurrentPlaybackRouteForbidden();
        service.notePlaybackMedia403(videoId, forbiddenStartMs, lowestServedMs, highestServedMs);
        service.switchNextFormat();
    }

    /** {@code client}'s answer is the one {@code videoId} plays (as a walk that reached it would leave). */
    private void servedBy(String videoId, AppClient client) {
        VideoInfo info = served(client, false);
        info.setClient(client);
        ReflectionHelpers.callInstanceMethod(service, "rememberVideoWinner",
                ClassParameter.from(String.class, videoId),
                ClassParameter.from(VideoInfo.class, info));
    }

    /** A playable answer, given to the visitor the source sends. */
    private static VideoInfo served(AppClient client, boolean auth) {
        VideoInfo info = VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"OK\"}}", auth);
        info.setRequestVisitorData(client == AppClient.WEB_EMBED ? "embed-visitor"
                : client.isWebPotRequired() || client == AppClient.VISIONOS || client == AppClient.ANDROID_VR
                        ? VideoInfoBotWallTest.ShadowTokenGate.webVisitor : VideoInfoBotWallTest.ShadowWalk.appVisitor);
        return info;
    }

    private static VideoInfo unplayable(String reason, boolean auth) {
        return VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\", \"reason\": \""
                + reason + "\"}}", auth);
    }

    private static VideoInfo liveAnswer(AppClient client, boolean auth) {
        return VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"OK\"}, \"videoDetails\":"
                + " {\"videoId\": \"live\", \"isLive\": true, \"isLiveContent\": true}, \"streamingData\":"
                + " {\"hlsManifestUrl\": \"https://media.invalid/live.m3u8\"" + (client == AppClient.ANDROID_VR
                ? ", \"dashManifestUrl\": \"https://media.invalid/live.mpd\"" : "") + "}}", auth);
    }

    private static VideoInfo answer(String visitor, String ei) {
        VideoInfo info = VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"OK\"},"
                + " \"streamingData\": {\"adaptiveFormats\": [{\"itag\": 137, \"url\":"
                + " \"https://rr1.googlevideo.com/videoplayback?itag=137&ei=" + ei + "&id=x\","
                + " \"mimeType\": \"video/mp4\"}]}}", false);
        info.setRequestVisitorData(visitor);
        return info;
    }

    /** The walk, then what the full getVideoInfo does with a playable answer: its winner. */
    private VideoInfo open(String videoId) {
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        VideoInfo result = ReflectionHelpers.callInstanceMethod(service, "firstPlayable",
                ClassParameter.from(String.class, videoId),
                ClassParameter.from(String.class, null),
                ClassParameter.from(boolean.class, VideoInfoBotWallTest.ShadowWalk.signedIn),
                ClassParameter.from(VideoInfoService.CancellationSignal.class, null));
        // what the full getVideoInfo does after its walk: the recovery cursor is one-shot
        ReflectionHelpers.setField(service, "mNextInfoType", null);
        ReflectionHelpers.setField(service, "mRecoveryWalk", false);
        ReflectionHelpers.setField(service, "mRecoverySuspect", null);
        ReflectionHelpers.callInstanceMethod(service, "rememberVideoWinner",
                ClassParameter.from(String.class, videoId),
                ClassParameter.from(VideoInfo.class, result));
        return result;
    }

    private static List<String> calls() {
        return new ArrayList<>(VideoInfoBotWallTest.ShadowWalk.calls);
    }

    private static boolean has(String fragment) {
        for (String line : logs()) {
            if (line.contains(fragment)) {
                return true;
            }
        }
        return false;
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
}
