package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

/**
 * NEWTUBE(walk-replay): the debug line that makes walk-replay fixtures exact. The expected lines are
 * also the input of tools/netbench/appbench/test_replay_fixtures.py (main repo): change both.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayabilityLogTest {
    @After
    public void tearDown() {
        PlayabilityLog.setEnabled(false);
    }

    /** Release builds never call setEnabled: nothing is logged. */
    @Test
    public void silentUnlessEnabled() {
        ShadowLog.reset();
        PlayabilityLog.log("v", AppClient.VISIONOS, 1, ageGate());
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("NetPath")) {
            assertTrue(item.msg, !item.msg.startsWith("player-playability"));
        }
    }

    /** An age gate: the reason and subreason apart, the marker the walk reads, a URL removed. */
    @Test
    public void anAgeGate() {
        PlayabilityLog.setEnabled(true);
        ShadowLog.reset();
        PlayabilityLog.log("qkO6iBwcoe4", AppClient.VISIONOS, 1, ageGate());
        assertEquals("player-playability video=qkO6iBwcoe4 client=VISIONOS attempt=1 {\"status\":\"LOGIN_REQUIRED\","
                        + "\"reason\":\"Inicia sesión y confirma tu edad\",\"subreason\":\"Puede que este vídeo no sea"
                        + " adecuado para algunos usuarios. <url> \\\"más\\\"\",\"desktopLegacyAgeGateReason\":1,"
                        + "\"live\":false,\"liveContent\":false,\"liveStart\":false,\"trailer\":false,\"cut\":false}",
                ShadowLog.getLogsForTag("NetPath").get(0).msg);
    }

    /** An ended stream whose recording is gone: a refusal that still carries its live signals. */
    @Test
    public void anEndedStream() {
        VideoInfo info = VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\","
                + " \"reason\": \"This live stream recording is not available.\"}, \"videoDetails\":"
                + " {\"videoId\": \"x\", \"isLiveContent\": true}, \"microformat\": {\"playerMicroformatRenderer\":"
                + " {\"liveBroadcastDetails\": {\"startTimestamp\": \"2026-09-25T10:00:00+00:00\"}}}}", false);
        assertEquals("{\"status\":\"UNPLAYABLE\",\"reason\":\"This live stream recording is not available.\","
                + "\"subreason\":null,\"desktopLegacyAgeGateReason\":0,\"live\":false,\"liveContent\":true,"
                + "\"liveStart\":true,\"trailer\":false,\"cut\":false}", PlayabilityLog.json(info));
    }

    /** A rental: the trailer isRent reads; a long reason is bounded at 1000, and says it was cut. */
    @Test
    public void aRental() {
        StringBuilder longReason = new StringBuilder("This video requires payment to watch.");
        while (longReason.length() < 1100) {
            longReason.append(" More text.");
        }
        VideoInfo info = VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\","
                + " \"reason\": \"" + longReason + "\", \"errorScreen\": {\"playerLegacyDesktopYpcTrailerRenderer\":"
                + " {\"trailerVideoId\": \"t\"}}}}", false);
        String json = PlayabilityLog.json(info);
        assertTrue(json, json.contains("\"reason\":\"" + longReason.substring(0, 1000) + "…\""));
        assertTrue(json, json.endsWith("\"trailer\":true,\"cut\":true}"));
    }

    /**
     * The replay's side: an answer replay_fixtures.py builds from these lines (an "exact" one)
     * rebuilds, through VideoInfoReplayTest, into an answer that logs the same two lines.
     */
    @Test
    public void anExactAnswerRebuildsIntoTheSameLines() {
        assertRoundTrip(ageGate());
        assertRoundTrip(VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"UNPLAYABLE\","
                + " \"reason\": \"La grabación de esta emisión en directo no está disponible.\", \"errorScreen\":"
                + " {\"playerLegacyDesktopYpcTrailerRenderer\": {\"trailerVideoId\": \"t\"}}}, \"videoDetails\":"
                + " {\"videoId\": \"x\", \"isLiveContent\": true}, \"microformat\": {\"playerMicroformatRenderer\":"
                + " {\"liveBroadcastDetails\": {\"startTimestamp\": \"2026-09-25T10:00:00+00:00\"}}}}", false));
    }

    private static void assertRoundTrip(VideoInfo original) {
        original.setClient(AppClient.VISIONOS);
        String line = PlayabilityLog.json(original);
        JsonObject logged = JsonParser.parseString(line).getAsJsonObject();
        // replay_fixtures.answer_of (the player-result line, its URLs removed) + exact().
        JsonObject answer = new JsonObject();
        answer.addProperty("status", original.getRawPlayabilityStatus());
        answer.addProperty("reason", ((String) ReflectionHelpers.callStaticMethod(VideoInfoService.class,
                "safeLogValue", ClassParameter.from(String.class, original.getPlayabilityStatus()),
                ClassParameter.from(int.class, 160))).replaceAll("https?://\\S+", "<url>"));
        answer.addProperty("playable", !original.isUnplayable());
        answer.addProperty("exact", true);
        answer.add("exactReason", logged.get("reason"));
        answer.add("exactSubreason", logged.get("subreason"));
        answer.addProperty("ageGate", logged.get("desktopLegacyAgeGateReason").getAsInt() != 0);
        for (String key : new String[] {"live", "liveContent", "liveStart", "trailer"}) {
            answer.add(key, logged.get(key));
        }
        VideoInfo rebuilt = VideoInfoReplayTest.Answers.rebuild(answer, "x", AppClient.VISIONOS, false, "round trip");
        assertEquals(line, PlayabilityLog.json(rebuilt));
        assertEquals(original.isAgeGate(), rebuilt.isAgeGate());
        assertEquals(VideoInfoService.hasLiveSignal(original), VideoInfoService.hasLiveSignal(rebuilt));
        assertEquals(original.isRent(), rebuilt.isRent());
    }

    private static VideoInfo ageGate() {
        return VideoInfoBotWallTest.parse("{\"playabilityStatus\": {\"status\": \"LOGIN_REQUIRED\","
                + " \"reason\": \"Inicia sesión y confirma tu edad\", \"errorScreen\":"
                + " {\"playerErrorMessageRenderer\": {\"subreason\": {\"simpleText\": \"Puede que este vídeo"
                + " no sea adecuado para algunos usuarios. https://support.google.com/youtube/answer/2802167"
                + " \\\"más\\\"\"}}}, \"desktopLegacyAgeGateReason\": 1}}", false);
    }
}
