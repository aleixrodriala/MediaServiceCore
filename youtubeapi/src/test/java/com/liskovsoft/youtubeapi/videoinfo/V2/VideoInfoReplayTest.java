package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.app.Application;
import android.os.SystemClock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService.WalkRole;
import com.liskovsoft.youtubeapi.videoinfo.models.VideoInfo;
import com.liskovsoft.youtubeapi.videoinfo.models.VodDelivery;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NEWTUBE(walk-replay): the phone's /player walk replayed against the answers YouTube gave a device.
 *
 * <p>Checking a planner change on the phone costs ~80 s per video and a home IP near YouTube's bot
 * wall, while the device logs already hold the real answers: every request of a walk leaves a
 * {@code player-result} NetPath line, every decision its own line. {@code
 * tools/netbench/appbench/replay_fixtures.py} (main repo) turns appbench's per-open logs into
 * {@link #FIXTURES}; this runs each case through the REAL walk - {@code getVideoInfo}, the
 * PhoneSourcePlanner order, the age-gate and consensus stops, the bot-check circuit, the bot-wall
 * book, the recovery cursor - with only the per-attempt request shadowed ({@link
 * VideoInfoBotWallTest.ShadowWalk}), and asserts, walk by walk, that it asks the clients the device
 * asked, in the same order, returns the same answer (playable or not, same client) and trips the
 * bot-check circuit when the device did. No network, no device.
 *
 * <p>A client the current code asks that the device never asked has no answer to replay: the case
 * FAILS naming it ("needs a device answer for X"), it never invents one. Re-run that video on a
 * device to extend the fixture.
 *
 * <p>A case is one simulated device: its opens in order on one service per app process. A new
 * process (appbench without --keep-process, or the log's own restart) is a new service that
 * restores the bot-wall book the previous one saved, as {@code setBotWallStore} does at app start
 * (a device process that logged no restore had no saved book, and neither has the replay's). Its
 * restored record count must match the device's: records saved before the case's first open cannot
 * be replayed, so a case whose device restored some fails unless its manifest entry says why they
 * cannot change its walks ({@code priorState}); in-memory state (the circuit, the recovery cursor, the anonymous-challenge cooldown, the repeated
 * sign-in memory) carries over only inside a process. The clock advances by the logged time between
 * lines, so the time-bound rules (probe interval, windows, TTLs) see the device's timing. The
 * player's recovery between walks (a media 403) is replayed through the calls
 * ErrorFixerController makes: anchorRouteToVideo, markCurrentPlaybackRouteForbidden (403 only),
 * switchNextFormat. The phone's switches are MobileMainApplication's, plus the debug switches the
 * run started with (appbench --prop, see {@link #WALK_NEUTRAL_SWITCHES}).
 *
 * <p>Fidelity. Each answer is rebuilt as /player JSON and parsed by the production parser, then
 * checked against its log line (status, playable, format counts, manifests, server sign-in, reason
 * as logged): a rebuilt answer that would not log exactly as the device's did fails the case as a
 * fidelity gap, not as a walk regression. What the player-result line does not carry is taken from
 * the walk's own lines or the text, and marked on the answer: {@code live} (the walk held it as
 * live-no-dash, or kept its winner as live), {@code ageGate} (desktopLegacyAgeGateReason: the answer
 * an age-gate-settled walk returned, or YouTube's age-gate sentence), {@code noResponse} (an
 * unparsed answer after the walk's attempt-timeout line; any other unparsed answer replays as an
 * error, so a transport-down stop caused by transport errors cannot replay), and the reason is the
 * logged one - reason and subreason joined (as the walk reads them) and cut at 160 characters
 * ({@code reasonCut}). A cut reason is classified on what is left: a qualifier or region hint past
 * the cut would be missed without the fidelity check noticing (the seed's one cut reason, the
 * removal, has its whole verdict sentence before the cut and only the learn-more sentence after).
 * Not recoverable from these logs: a live signal on a REFUSED answer (isLiveContent, a start time)
 * and a rental trailer. Logs from debug builds with {@code player-playability} lines (PlayabilityLog)
 * carry YouTube's own reason and subreason (bounded at 1000 characters, flagged when cut), age-gate
 * marker, live signals and trailer: such an answer ({@code exact}) is rebuilt from them, and still
 * checked against its player-result line. Walks the log cut short (the cell ended, a cancel) are
 * left out by the extractor.
 *
 * <p>A case that does not match current behaviour for a known reason - a log from a build whose
 * behaviour was changed on purpose - carries {@code exclude} with the reason and is reported as
 * skipped with it, never dropped silently. When the change is one walk asking fewer of the clients
 * the device asked (or the same ones in another order), the case stays: that walk carries {@code
 * expect.changed} (the order the current code asks, and why) beside the device's own record, and is
 * checked against it; the rest of the case is replayed as the device ran it.
 */
@RunWith(ParameterizedRobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoBotWallTest.ShadowWalk.class, VideoInfoBotWallTest.ShadowTokenGate.class,
                VideoInfoWalkRoleTest.ShadowAppService.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoReplayTest {
    static final String FIXTURES = "walk_replay/device_walks.json";
    private static final java.util.regex.Pattern RESTORED =
            java.util.regex.Pattern.compile("botwall restore records=(\\d+)");

    /**
     * Debug switches (appbench --prop) that do not change the walk once its answers are recorded:
     * media and player-side faults and tuning (a media 403 they cause is replayed as its own step),
     * the signature solver, request and identity plumbing (they change YouTube's answers, which are
     * the recorded input, not the walk's rules), when preloads happen (their walks are logged as
     * walks), the benchmark's own ticks, and appbench's old anon_tizen (no longer read by the app).
     * Not here on purpose, so a run with them fails until modeled: the forced client, the debug bot
     * wall, WEB_EMBED with the account, SABR for VOD, the offline benchmark fixture, black holes
     * (their timeouts decide the walk's transport-down rule, and the log cannot tell a timeout).
     */
    private static final java.util.Set<String> WALK_NEUTRAL_SWITCHES = new java.util.HashSet<>(
            java.util.Arrays.asList("debug.arc.poison_once_itag", "debug.arc.poison_itag",
                    "debug.arc.timeout_once_itag", "debug.arc.throttle_kbps", "debug.arc.still_lift",
                    "debug.arc.keep_codec", "debug.arc.start_buffer_ms", "debug.arc.resume_snap",
                    "debug.arc.rebuffer_gate_ms", "debug.arc.media_transport", "debug.arc.media_cache",
                    "debug.arc.startup_budget_ms", "debug.arc.startup_abr", "debug.arc.opus_preroll",
                    "debug.arc.codec_warmup", "debug.arc.dynamic_scheduling", "debug.arc.readiness",
                    "debug.arc.v8_memo", "debug.arc.keep_sig_runtime", "debug.arc.embed_persist",
                    "debug.arc.pot_gen", "debug.arc.player_pot", "debug.arc.support_xhr",
                    "debug.arc.early_preconnect", "debug.arc.player_warmup", "debug.arc.eager_token_warmup",
                    "debug.arc.fresh_app_info", "debug.arc.touch_prefetch_ms", "debug.arc.next_media_preload",
                    "debug.arc.eager_cold", "debug.arc.lazy_home", "debug.arc.home_prefetch",
                    "debug.arc.bench", "debug.arc.bench_seek", "debug.arc.anon_tizen",
                    // The kids channel memory hints only a walk whose channel the app named, and a
                    // replay names none: on or off, its walks are the same.
                    "debug.arc.kids_channel"));

    /** Case name and its JSON: plain strings, so nothing crosses into the sandbox's class loader. */
    @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
    public static List<Object[]> cases() throws IOException {
        List<Object[]> cases = new ArrayList<>();
        for (JsonElement fixture : load().getAsJsonArray("cases")) {
            cases.add(new Object[] {text(fixture.getAsJsonObject(), "name"), fixture.toString()});
        }
        return cases;
    }

    private final String mName;
    private final String mJson;

    public VideoInfoReplayTest(String name, String json) {
        mName = name;
        mJson = json;
    }

    @Before
    public void setUp() {
        VideoInfoBotWallTest.ShadowWalk.calls.clear();
        VideoInfoBotWallTest.ShadowWalk.silent.clear();
        VideoInfoBotWallTest.ShadowWalk.signedIn = false;
        // The phone's switches, as MobileMainApplication sets them (SABR for VOD stays off).
        VideoInfoService.setPreferNoPotClient(true);
        VideoInfoService.setPreferDashManifestForLive(true);
        VideoInfoService.setSkipLiveDashInfoWithManifest(true);
        VideoInfoService.setSkipStoryboardEnrichment(true);
        VodDelivery.setHlsEnabled(true);
    }

    @After
    public void tearDown() {
        VideoInfoService.setAccountRouteFirst(false);
        VideoInfoService.setPreferNoPotClient(false);
        VideoInfoService.setPreferDashManifestForLive(false);
        VideoInfoService.setSkipLiveDashInfoWithManifest(false);
        VideoInfoService.setSkipStoryboardEnrichment(false);
        VodDelivery.setHlsEnabled(false);
    }

    @Test
    public void theWalkDoesWhatTheDeviceDid() {
        JsonObject fixture = JsonParser.parseString(mJson).getAsJsonObject();
        String exclude = text(fixture, "exclude");
        Assume.assumeTrue(mName + " excluded: " + exclude, exclude == null);
        new Replay(mName, fixture).run();
    }

    // ------------------------------------------------------------------------------------------

    /** One case on one simulated device. */
    private static final class Replay {
        private final String mCase;
        private final JsonObject mFixture;
        /** The phone's SharedPreferences for the bot-wall book: it outlives a process. */
        private final VideoInfoBotWallTest.MemoryWallStore mStore = new VideoInfoBotWallTest.MemoryWallStore();
        private final long mStartMs = SystemClock.elapsedRealtime();
        private VideoInfoService mService;

        Replay(String name, JsonObject fixture) {
            mCase = name;
            mFixture = fixture;
        }

        void run() {
            for (JsonElement openElement : mFixture.getAsJsonArray("opens")) {
                JsonObject open = openElement.getAsJsonObject();
                boolean lte = "lte".equals(text(open, "network"));
                VideoInfoBotWallTest.ShadowWalk.network = lte ? "cell:1" : "wifi:1";
                VideoInfoBotWallTest.ShadowWalk.transport = lte ? "cell" : "wifi";
                if (mService == null || flag(open, "newProcess")) {
                    at(number(open, "atMs"));
                    startSwitches(open);
                    newProcess(mCase + " / " + text(open, "log"), open.get("restoredRecords"));
                }
                int walk = 0;
                for (JsonElement stepElement : open.getAsJsonArray("steps")) {
                    JsonObject step = stepElement.getAsJsonObject();
                    at(number(step, "atMs"));
                    switch (text(step, "type")) {
                        case "walk":
                            walk(open, step, ++walk);
                            break;
                        case "media-failure":
                            // ErrorFixerController on a source error of the playing video.
                            mService.anchorRouteToVideo(text(step, "video"));
                            if (flag(step, "http403")) {
                                mService.markCurrentPlaybackRouteForbidden();
                            }
                            mService.switchNextFormat();
                            break;
                        case "adopt":
                            mService.adoptSpeculativeResult(text(step, "video"), false, false);
                            break;
                        case "restart":
                            newProcess(mCase + " / " + text(open, "log") + " (restart)",
                                    step.get("restoredRecords"));
                            break;
                        default:
                            fail(mCase + ": unknown step " + step);
                    }
                }
            }
        }

        /**
         * The debug switches the app started with (appbench --prop; read at process start): the
         * ones that change the walk are set as MobileMainApplication sets them, the ones known not
         * to change it given its recorded answers are ignored, and any other fails the case - say
         * which it is (here) before replaying such a run.
         */
        private void startSwitches(JsonObject open) {
            VideoInfoService.setAccountRouteFirst(false);
            VodDelivery.setHlsEnabled(true);
            JsonObject props = open.has("props") ? open.getAsJsonObject("props") : new JsonObject();
            for (Map.Entry<String, JsonElement> prop : props.entrySet()) {
                String key = prop.getKey();
                String value = prop.getValue().getAsString();
                if ("debug.arc.account_first".equals(key)) {
                    VideoInfoService.setAccountRouteFirst("1".equals(value));
                } else if ("debug.arc.hls_vod".equals(key)) {
                    VodDelivery.setHlsEnabled(!"0".equals(value));
                } else if (!WALK_NEUTRAL_SWITCHES.contains(key)) {
                    fail(mCase + " / " + text(open, "log") + ": the device ran with " + key + "=" + value
                            + ", which the replay does not model: add it to VideoInfoReplayTest"
                            + " (set it, or list it as not changing the walk)");
                }
            }
        }

        /**
         * A cold start: a new service that restores the book the last process saved. A device
         * process that logged no restore had no saved book (nothing walled or benched yet, or its
         * data was cleared: appbench --pm-clear), and neither has this one.
         */
        private void newProcess(String where, JsonElement deviceRestored) {
            int device = deviceRestored == null || deviceRestored.isJsonNull() ? 0 : deviceRestored.getAsInt();
            if (device == 0) {
                mStore.value = null;
            }
            mService = ReflectionHelpers.callConstructor(VideoInfoService.class);
            // The shadowed constructor skips field initializers; give the instance what the walk uses.
            ReflectionHelpers.setField(mService, "mAuthRouteQuarantine", new AuthRouteQuarantineBook());
            ReflectionHelpers.setField(mService, "mBotWall", new BotWallBook());
            ReflectionHelpers.setField(mService, "mVideoWinners", java.util.Collections.synchronizedMap(
                    new LinkedHashMap<String, AppClient>(16, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, AppClient> eldest) {
                            return size() > 8; // VIDEO_WINNER_MEMORY
                        }
                    }));
            ReflectionHelpers.setField(mService, "mRoutingGeneration", new AtomicLong());
            ReflectionHelpers.setField(mService, "mWalkRole", WalkRole.ACTIVE);
            ShadowLog.reset();
            ReflectionHelpers.callInstanceMethod(mService, "restoreBotWall",
                    ClassParameter.from(VideoInfoService.BotWallStore.class, mStore));
            int replayed = 0;
            for (String line : replayLines()) {
                java.util.regex.Matcher m = RESTORED.matcher(line);
                if (m.find()) {
                    replayed = Integer.parseInt(m.group(1));
                }
            }
            if (device != replayed && text(mFixture, "priorState") == null) {
                fail(where + ": the device's new process restored " + device + " bot-wall records, the"
                        + " replay's " + replayed + ". Records saved before the case cannot be replayed:"
                        + " add the opens that saved them to the case, or state in its manifest entry"
                        + " (priorState) why they cannot change these walks");
            }
        }

        private void walk(JsonObject open, JsonObject step, int index) {
            final String video = text(step, "video");
            final String role = text(step, "role");
            String lane = text(step, "lane") != null ? text(step, "lane") : text(open, "lane");
            final JsonObject expect = step.getAsJsonObject("expect");
            final List<String> deviceAsked = strings(expect.getAsJsonArray("asked"));
            final String where = mCase + " / " + text(open, "log") + " / walk " + index + " (" + video
                    + ", " + role + ", " + lane + ", " + text(open, "build") + ")";
            final Map<String, Deque<JsonObject>> answers = new LinkedHashMap<>();
            for (JsonElement answer : step.getAsJsonArray("answers")) {
                String client = text(answer.getAsJsonObject(), "client");
                if (!answers.containsKey(client)) {
                    answers.put(client, new ArrayDeque<JsonObject>());
                }
                answers.get(client).add(answer.getAsJsonObject());
            }

            VideoInfoBotWallTest.ShadowWalk.signedIn = "signed-in".equals(lane);
            VideoInfoBotWallTest.ShadowWalk.calls.clear();
            ShadowLog.reset();
            VideoInfoBotWallTest.ShadowWalk.script = (client, auth) -> {
                Deque<JsonObject> queue = answers.get(client.name());
                JsonObject answer = queue != null ? queue.poll() : null;
                if (answer == null) {
                    throw new NeedsAnswer(client);
                }
                at(number(answer, "atMs"));
                Boolean sent = answer.get("auth").isJsonNull() ? null : answer.get("auth").getAsBoolean();
                if (sent != null && sent != auth) {
                    throw new AssertionError(where + ": the device asked " + client + (sent ? " with" : " without")
                            + " the account, the replay asks " + (auth ? "with" : "without") + " it");
                }
                if (flag(answer, "parsed")) {
                    return Answers.rebuild(answer, video, client, auth, where);
                }
                if (flag(answer, "noResponse")) {
                    VideoInfoBotWallTest.ShadowWalk.silent.add(client); // a timeout, not an error
                }
                return null;
            };

            VideoInfo result;
            try {
                result = mService.getVideoInfo(video, null, null,
                        "speculative".equals(role) ? WalkRole.SPECULATIVE : WalkRole.ACTIVE);
            } catch (NeedsAnswer e) {
                List<String> asked = new ArrayList<>(VideoInfoBotWallTest.ShadowWalk.calls);
                throw new AssertionError(where + ": the walk asked " + e.client + " (request "
                        + asked.size() + ", after " + asked.subList(0, asked.size() - 1)
                        + "), which the device never asked: needs a device answer for " + e.client
                        + ". The device asked " + deviceAsked + context(expect));
            }

            List<String> asked = new ArrayList<>(VideoInfoBotWallTest.ShadowWalk.calls);
            // A walk the current code asks differently on purpose (the manifest's "changed"): its
            // order instead of the device's, with the reason in the message.
            JsonElement changed = expect.get("changed");
            if (changed != null && !changed.isJsonNull()) {
                assertEquals(where + ": the clients asked, changed on purpose from the device's "
                        + deviceAsked + " (" + text(changed.getAsJsonObject(), "why") + ")" + context(expect),
                        strings(changed.getAsJsonObject().getAsJsonArray("asked")), asked);
            } else {
                assertEquals(where + ": the clients asked" + context(expect), deviceAsked, asked);
            }

            String expected = text(expect, "result");
            String got;
            if ("cooldown".equals(expected)) {
                // Answered from the bot-check circuit, before any request.
                got = result != null && asked.isEmpty() && result.isBotCheckRequired() ? "cooldown"
                        : outcome(result);
            } else {
                got = outcome(result);
                expected += " " + text(expect, "client");
            }
            assertEquals(where + ": the answer returned" + context(expect), expected, got);
            assertEquals(where + ": the bot-check circuit tripped" + context(expect),
                    flag(expect, "botCheckTrip"), replayLines().toString().contains("bot-check trip"));
        }

        private static String outcome(VideoInfo result) {
            return result == null ? "none null"
                    : (result.isUnplayable() ? "unplayable " : "playable ") + result.getClient();
        }

        /** The device's settle markers and the replay's own walk lines, for a failure message. */
        private static String context(JsonObject expect) {
            String markers = String.valueOf(expect.get("markers"));
            String hint = markers.contains("transport-down")
                    ? "\n  (the device's walk stopped on a dead link: only its logged timeouts replay as"
                            + " no response, a transport error replays as an error - possibly a fidelity"
                            + " gap, not a regression)" : "";
            return "\n  device markers: " + markers + "\n  replay: " + replayLines() + hint;
        }

        private static List<String> replayLines() {
            List<String> lines = new ArrayList<>();
            for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("NetPath")) {
                if (item.msg.startsWith("player-ring") || item.msg.startsWith("bot-check")) {
                    lines.add(item.msg.replaceAll(" network=\\S+", ""));
                }
            }
            return lines;
        }

        /** Advances the (paused) clock to {@code atMs} after the case's first line; never back. */
        private void at(long atMs) {
            long behindMs = mStartMs + atMs - SystemClock.elapsedRealtime();
            if (behindMs > 0) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(behindMs));
            }
        }
    }

    /** A request the current walk makes that the device never made: nothing to replay. */
    private static final class NeedsAnswer extends RuntimeException {
        final AppClient client;

        NeedsAnswer(AppClient client) {
            super("needs a device answer for " + client);
            this.client = client;
        }
    }

    /** A logged answer back into /player JSON, parsed by the production parser. */
    static final class Answers {
        private Answers() {
        }

        static VideoInfo rebuild(JsonObject a, String videoId, AppClient client, boolean auth, String where) {
            String status = text(a, "status");
            boolean exact = flag(a, "exact");
            JsonObject playability = new JsonObject();
            playability.addProperty("status", status);
            // Without the debug line, the reason as logged: YouTube's reason and subreason joined,
            // which is also how the walk reads them (getPlayabilityStatus).
            String reason = exact ? text(a, "exactReason") : text(a, "reason");
            if (reason != null) {
                playability.addProperty("reason", reason);
            }
            JsonObject errorScreen = new JsonObject();
            if (exact && text(a, "exactSubreason") != null) {
                JsonObject subreason = new JsonObject();
                subreason.addProperty("simpleText", text(a, "exactSubreason"));
                JsonObject renderer = new JsonObject();
                renderer.add("subreason", subreason);
                errorScreen.add("playerErrorMessageRenderer", renderer);
            }
            if (flag(a, "trailer")) {
                JsonObject renderer = new JsonObject();
                renderer.addProperty("trailerVideoId", "trailer");
                errorScreen.add("playerLegacyDesktopYpcTrailerRenderer", renderer);
            }
            if (errorScreen.size() > 0) {
                playability.add("errorScreen", errorScreen);
            }
            if (flag(a, "ageGate")) {
                playability.addProperty("desktopLegacyAgeGateReason", 1);
            }
            JsonObject root = new JsonObject();
            root.add("playabilityStatus", playability);

            boolean live = flag(a, "live");
            boolean liveContent = flag(a, "liveContent");
            if ("OK".equals(status) || live || liveContent) {
                JsonObject details = new JsonObject();
                details.addProperty("videoId", videoId);
                details.addProperty("isLive", live);
                details.addProperty("isLiveContent", liveContent);
                root.add("videoDetails", details);
            }
            if (flag(a, "liveStart")) {
                JsonObject broadcast = new JsonObject();
                broadcast.addProperty("startTimestamp", "2026-01-01T00:00:00+00:00");
                JsonObject renderer = new JsonObject();
                renderer.add("liveBroadcastDetails", broadcast);
                JsonObject microformat = new JsonObject();
                microformat.add("playerMicroformatRenderer", renderer);
                root.add("microformat", microformat);
            }

            int adaptive = (int) number(a, "adaptive");
            int usable = (int) number(a, "usableAdaptive");
            int regular = (int) number(a, "regular");
            JsonObject streaming = new JsonObject();
            if (adaptive > 0) {
                JsonArray formats = new JsonArray();
                for (int i = 0; i < adaptive; i++) {
                    // Usable formats carry a URL; the rest are SABR-only (no URL, no cipher).
                    formats.add(format(100 + i, i % 2 == 0 ? "video/mp4" : "audio/mp4", i < usable));
                }
                streaming.add("adaptiveFormats", formats);
            }
            if (regular > 0) {
                JsonArray formats = new JsonArray();
                for (int i = 0; i < regular; i++) {
                    formats.add(format(18 + i, "video/mp4", true));
                }
                streaming.add("formats", formats);
            }
            if (flag(a, "dash")) {
                streaming.addProperty("dashManifestUrl", "https://media.invalid/manifest.mpd");
            }
            if (flag(a, "hls")) {
                streaming.addProperty("hlsManifestUrl", "https://media.invalid/manifest.m3u8");
            }
            if (flag(a, "sabr")) {
                streaming.addProperty("serverAbrStreamingUrl", "https://media.invalid/sabr");
            }
            if (streaming.size() > 0) {
                root.add("streamingData", streaming);
            }
            JsonElement srvAuth = a.get("srvAuth");
            if (srvAuth != null && !srvAuth.isJsonNull()) {
                JsonObject param = new JsonObject();
                param.addProperty("key", "logged_in");
                param.addProperty("value", srvAuth.getAsBoolean() ? "1" : "0");
                JsonArray params = new JsonArray();
                params.add(param);
                JsonObject service = new JsonObject();
                service.addProperty("service", "GFEEDBACK");
                service.add("params", params);
                JsonArray services = new JsonArray();
                services.add(service);
                JsonObject context = new JsonObject();
                context.add("serviceTrackingParams", services);
                root.add("responseContext", context);
            }

            VideoInfo info = VideoInfoBotWallTest.parse(root.toString(), auth);
            info.setClient(client); // playability reads it (the HLS-for-VOD sources)
            checkFidelity(info, a, client, where);
            return info;
        }

        private static JsonObject format(int itag, String mimeType, boolean withUrl) {
            JsonObject format = new JsonObject();
            format.addProperty("itag", itag);
            format.addProperty("mimeType", mimeType);
            if (withUrl) {
                format.addProperty("url", "https://media.invalid/videoplayback?itag=" + itag);
            }
            return format;
        }

        /** The rebuilt answer must log exactly as the device's did (VideoInfoService.logPlayerOutcome). */
        private static void checkFidelity(VideoInfo info, JsonObject a, AppClient client, String where) {
            List<String> diffs = new ArrayList<>();
            int adaptive = info.getAdaptiveFormats() != null ? info.getAdaptiveFormats().size() : 0;
            int regular = info.getRegularFormats() != null ? info.getRegularFormats().size() : 0;
            int usable = 0;
            if (info.getAdaptiveFormats() != null) {
                for (com.liskovsoft.youtubeapi.videoinfo.models.formats.AdaptiveVideoFormat format
                        : info.getAdaptiveFormats()) {
                    usable += format != null && !format.isBroken() ? 1 : 0;
                }
            }
            same(diffs, "status", text(a, "status"), info.getRawPlayabilityStatus());
            same(diffs, "playable", flag(a, "playable"), !info.isUnplayable());
            same(diffs, "formats", number(a, "adaptive") + "+" + number(a, "regular") + "/" + number(a, "usableAdaptive"),
                    adaptive + "+" + regular + "/" + usable);
            same(diffs, "dash", flag(a, "dash"), info.getDashManifestUrl() != null);
            same(diffs, "hls", flag(a, "hls"), info.getHlsManifestUrl() != null);
            same(diffs, "sabr", flag(a, "sabr"), info.getServerAbrStreamingUrl() != null);
            JsonElement srvAuth = a.get("srvAuth");
            same(diffs, "srvAuth", srvAuth == null || srvAuth.isJsonNull() ? null : srvAuth.getAsBoolean(),
                    info.isServerLoggedIn());
            String logged = ReflectionHelpers.callStaticMethod(VideoInfoService.class, "safeLogValue",
                    ClassParameter.from(String.class, info.getPlayabilityStatus()),
                    ClassParameter.from(int.class, 160));
            same(diffs, "reason", text(a, "reason") != null ? text(a, "reason") : "null", logged);
            if (!diffs.isEmpty()) {
                throw new AssertionError(where + ": fidelity gap, not a walk regression - the rebuilt "
                        + client + " answer would not log as the device's did: " + diffs);
            }
        }

        private static void same(List<String> diffs, String field, Object device, Object rebuilt) {
            if (device == null ? rebuilt != null : !device.equals(rebuilt)) {
                diffs.add(field + " device=" + device + " rebuilt=" + rebuilt);
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    private static JsonObject load() throws IOException {
        InputStream in = VideoInfoReplayTest.class.getClassLoader().getResourceAsStream(FIXTURES);
        if (in == null) {
            throw new IOException("missing test resource " + FIXTURES);
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static boolean flag(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static long number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        for (JsonElement value : array) {
            values.add(value.getAsString());
        }
        return values;
    }
}
