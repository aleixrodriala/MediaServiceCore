package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.annotation.Nullable;

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
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Converter;

/**
 * The account-route quarantine through the real service entry points - keying, persistence across
 * a restart, escalation, the no-media streak and probation, the proven rule, and the older
 * snapshots - with no HTTP, prefs or app initialization.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoAuthRouteQuarantineTest.ShadowVideoInfoService.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoAuthRouteQuarantineTest {
    private static final long MIN = 60_000;
    private MemoryStore store;

    @Before
    public void setUp() {
        ShadowVideoInfoService.transport = "cell";
        ShadowVideoInfoService.network = "cell:340";
        store = new MemoryStore();
        VideoInfoService.setAuthRouteQuarantineStore(store);
    }

    @After
    public void tearDown() {
        VideoInfoService.setAuthRouteQuarantineStore(null);
    }

    /**
     * The regression this keying fixes: every reconnect is a new Network handle, and the old
     * {@code transport:hashCode} key wiped the quarantine each time.
     */
    @Test
    public void aReconnectOnTheSameTransportKeepsTheQuarantine() {
        VideoInfoService service = newService();
        quarantine(service, AppClient.TV_DOWNGRADED);

        ShadowVideoInfoService.network = "cell:341"; // radio handover: new netId, same transport

        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED), forbidden(service));
    }

    @Test
    public void anotherTransportNeitherInheritsNorWipesTheQuarantine() {
        VideoInfoService service = newService();
        quarantine(service, AppClient.TV_DOWNGRADED);

        ShadowVideoInfoService.transport = "wifi";
        assertTrue(forbidden(service).isEmpty());

        ShadowVideoInfoService.transport = "cell";
        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED), forbidden(service));
    }

    @Test
    public void noActiveNetworkQuarantinesNothingAndForgetsNothing() {
        VideoInfoService service = newService();
        quarantine(service, AppClient.TV);

        ShadowVideoInfoService.transport = null;
        assertTrue(forbidden(service).isEmpty());
        quarantine(service, AppClient.TV_DOWNGRADED); // nowhere to key it: ignored

        ShadowVideoInfoService.transport = "cell";
        assertEquals(Collections.singleton(AppClient.TV), forbidden(service));
    }

    /**
     * The whole TTFF point: the re-probe after an expiry fails again, and that failure must buy a
     * LONGER cooldown - one that survives a process restart with its strike count.
     */
    @Test
    public void reQuarantineEscalatesAndTheStrikeSurvivesARestart() {
        VideoInfoService service = newService();
        quarantine(service, AppClient.TV_DOWNGRADED);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11));
        assertTrue("strike 1 is the historical 10 minutes", forbidden(service).isEmpty());

        quarantine(service, AppClient.TV_DOWNGRADED);
        assertNotNull(store.value);
        assertTrue(store.value, store.value.startsWith("v3|cell:TV_DOWNGRADED:"));
        assertTrue(store.value, store.value.contains(":2:"));
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30));
        assertEquals("strike 2 holds 40 minutes",
                Collections.singleton(AppClient.TV_DOWNGRADED), forbidden(service));

        VideoInfoService restarted = newService();
        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED), forbidden(restarted));
        // Well past the restored remainder whether or not the sandbox's wall clock follows its
        // elapsed clock (the restored remainder is 10 min if it does, at most 40 if it does not).
        ShadowSystemClock.advanceBy(Duration.ofMinutes(90));
        assertTrue(forbidden(restarted).isEmpty());

        quarantine(restarted, AppClient.TV_DOWNGRADED);
        assertTrue(store.value, store.value.contains(":3:"));
        ShadowSystemClock.advanceBy(Duration.ofMinutes(150));
        assertEquals("strike 3 holds 160 minutes",
                Collections.singleton(AppClient.TV_DOWNGRADED), forbidden(restarted));
    }

    /** 1.9.0 stored {@code transport:netIdHash|CLIENT:expiry}; upgrading must not lose it. */
    @Test
    public void aLegacySnapshotIsRestoredOntoItsTransportAndMigrated() {
        // Far-future expiry: clamped to the legacy 10-minute cooldown whatever the wall clock says.
        store.value = "cell:340|" + AppClient.TV_DOWNGRADED.name() + ":" + (Long.MAX_VALUE / 2);

        VideoInfoService service = newService();

        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED), forbidden(service));
        assertTrue(store.value, store.value.startsWith("v3|cell:TV_DOWNGRADED:"));
        ShadowVideoInfoService.transport = "wifi";
        assertTrue(forbidden(service).isEmpty());
    }

    @Test
    public void anUnreadableSnapshotIsClearedRatherThanTrusted() {
        store.value = "v2|garbage";

        assertTrue(forbidden(newService()).isEmpty());
        assertNull(store.value);
    }

    // --- no-media streak across restarts, probation, and the proven rule ----------------------

    /**
     * The 2026-09-25 Pixel 9 trace: every cold open (one video, then process death) ended at
     * {@code auth-route no-media client=TV hits=1/2}, so TV was never quarantined and every cold
     * open paid for it. The first hit must now reach the next process, and the second distinct
     * video - in another process - must quarantine.
     */
    @Test
    public void aColdOpensNoMediaHitSurvivesARestartAndTheNextColdOpenQuarantines() {
        VideoInfoService first = newService();
        countNoMedia(first, AppClient.TV, "u_vnA6nlDvs");
        assertTrue(forbidden(first).isEmpty());
        assertTrue(storedRecords().isEmpty());
        assertEquals(1, storedStreaks().size());
        String[] streak = storedStreaks().get(0); // transport:CLIENT:hits:lastHitWallMs:videoKey
        assertEquals("cell", streak[0]);
        assertEquals("TV", streak[1]);
        assertEquals("1", streak[2]);
        assertEquals(VideoInfoService.noMediaVideoKey("u_vnA6nlDvs"), streak[4]);
        assertFalse("the value never carries the videoId", store.value.contains("u_vnA6nlDvs"));

        VideoInfoService second = newService(); // process death, then the next share link
        countNoMedia(second, AppClient.TV, "yi0PiY1i3XU");

        assertEquals(Collections.singleton(AppClient.TV), forbidden(second));
        assertTrue(storedStreaks().isEmpty());
        assertEquals(1, storedRecords().size());
        assertEquals("the arming video travels with the record",
                VideoInfoService.noMediaVideoKey("yi0PiY1i3XU"), storedRecords().get(0)[5]);
        assertEquals("TV", storedRecords().get(0)[1]);
        assertEquals("strike 1", "1", storedRecords().get(0)[3]);
    }

    /** A cold open of the SAME video again is still one piece of evidence, restart or not. */
    @Test
    public void theSameVideoAfterARestartIsStillOneHit() {
        countNoMedia(newService(), AppClient.TV, "u_vnA6nlDvs");

        VideoInfoService restarted = newService();
        countNoMedia(restarted, AppClient.TV, "u_vnA6nlDvs");

        assertTrue(forbidden(restarted).isEmpty());
        assertEquals("1", storedStreaks().get(0)[2]);
    }

    /**
     * Probation across a restart: once the first quarantine has expired, the first proven verdict
     * re-quarantines TV at once, one strike up (40 minutes) - not after two more dead probes.
     */
    @Test
    public void anExpiredQuarantineIsReArmedByTheFirstVerdictAfterARestartAndEscalates() {
        VideoInfoService first = newService();
        countNoMedia(first, AppClient.TV, "u_vnA6nlDvs");
        countNoMedia(first, AppClient.TV, "yi0PiY1i3XU");
        assertEquals(Collections.singleton(AppClient.TV), forbidden(first));

        ShadowSystemClock.advanceBy(Duration.ofMinutes(60));
        VideoInfoService restarted = newService();
        forbidden(restarted); // restore now...
        // ...then go past the restored remainder whether or not the sandbox's wall clock follows
        // its elapsed clock (see reQuarantineEscalatesAndTheStrikeSurvivesARestart).
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11));
        assertTrue(forbidden(restarted).isEmpty());

        // Re-opening the video that armed it is not new evidence, restart or not.
        countNoMedia(restarted, AppClient.TV, "yi0PiY1i3XU");
        assertTrue(forbidden(restarted).isEmpty());

        countNoMedia(restarted, AppClient.TV, "Fo89b8zAIE4");

        assertEquals(Collections.singleton(AppClient.TV), forbidden(restarted));
        assertEquals("escalated to strike 2", "2", storedRecords().get(0)[3]);
        assertEquals("both arming videos are remembered",
                VideoInfoService.noMediaVideoKey("yi0PiY1i3XU") + ","
                        + VideoInfoService.noMediaVideoKey("Fo89b8zAIE4"),
                storedRecords().get(0)[5]);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(30));
        assertEquals("strike 2 holds 40 minutes",
                Collections.singleton(AppClient.TV), forbidden(restarted));
    }

    /**
     * The proven rule survives persistence: a no-media answer for a video nobody served - here the
     * shape a signed-in head gives a private video the account cannot open - is held and dropped
     * with its walk, never stored, in this process or the next.
     */
    @Test
    public void anUnprovenVerdictIsNeverCountedOrStored() throws IOException {
        VideoInfo privateVideo = parse("""
                {"playabilityStatus": {"status": "LOGIN_REQUIRED",
                   "reason": "Private video"}, %s}
                """.formatted(SIGNED_IN), true);

        VideoInfoService first = newService();
        VideoInfoService.AuthRouteWalkState walk = new VideoInfoService.AuthRouteWalkState();
        noteVerdict(first, AppClient.TV, "PrivateVid1", privateVideo, walk);
        noteVerdict(first, AppClient.TV_DOWNGRADED, "PrivateVid1", privateVideo, walk);
        assertEquals(2, walk.heldCount()); // the walk ends with nothing playable: dropped
        assertNull(store.value);

        VideoInfoService restarted = newService();
        noteVerdict(restarted, AppClient.TV, "PrivateVid2", privateVideo,
                new VideoInfoService.AuthRouteWalkState());
        assertNull(store.value);
        assertTrue(forbidden(restarted).isEmpty());
    }

    /** ...and the same observation IS counted once another client serves the video. */
    @Test
    public void aVerdictProvenByAnotherClientIsCountedAndStored() throws IOException {
        VideoInfo sabrOnly = parse("""
                {"playabilityStatus": {"status": "OK"}, %s,
                 "streamingData": {"adaptiveFormats": [%s],
                   "serverAbrStreamingUrl": "https://media.invalid/sabr"}}
                """.formatted(SIGNED_IN, BROKEN_ADAPTIVE), true);
        VideoInfoService service = newService();
        VideoInfoService.AuthRouteWalkState walk = new VideoInfoService.AuthRouteWalkState();

        noteVerdict(service, AppClient.TV, "u_vnA6nlDvs", sabrOnly, walk);
        assertNull("held until something plays", store.value);
        for (java.util.Map.Entry<AppClient, VideoInfoService.AuthRouteWalkState.Held> held
                : walk.onPlayable().entrySet()) { // VISIONOS served it
            countProven(service, held.getKey(), held.getValue(), walk);
        }

        assertEquals(1, storedStreaks().size());
        // Once the walk has been served, a later observation counts straight away.
        noteVerdict(service, AppClient.TV, "yi0PiY1i3XU", sabrOnly, walk);
        assertEquals(Collections.singleton(AppClient.TV), forbidden(service));
    }

    /**
     * Age-restricted content, signed in: a head with strike memory that refuses an age-gated video
     * the account may not watch - while anonymous WEB_EMBED serves it - must not be re-quarantined
     * by that one video. Route-shaped evidence (SABR-only) on the next video still is.
     */
    @Test
    public void anAgeGatedRefusalCannotPutAClientOnProbationButASabrOnlyOneCan()
            throws IOException {
        VideoInfo ageGated = parse("""
                {"playabilityStatus": {"status": "LOGIN_REQUIRED",
                   "reason": "Sign in to confirm your age"}, %s}
                """.formatted(SIGNED_IN), true);
        VideoInfo sabrOnly = parse("""
                {"playabilityStatus": {"status": "OK"}, %s,
                 "streamingData": {"adaptiveFormats": [%s],
                   "serverAbrStreamingUrl": "https://media.invalid/sabr"}}
                """.formatted(SIGNED_IN, BROKEN_ADAPTIVE), true);
        VideoInfoService service = newService();
        quarantine(service, AppClient.TV_DOWNGRADED);
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11)); // expired, strike remembered
        assertTrue(forbidden(service).isEmpty());

        VideoInfoService.AuthRouteWalkState walk = new VideoInfoService.AuthRouteWalkState();
        walk.onPlayable(); // WEB_EMBED served the age-gated video anonymously
        noteVerdict(service, AppClient.TV_DOWNGRADED, "AgeGated001", ageGated, walk);

        assertTrue("one gated refusal is not enough", forbidden(service).isEmpty());
        assertEquals(1, storedStreaks().size());

        VideoInfoService.AuthRouteWalkState next = new VideoInfoService.AuthRouteWalkState();
        next.onPlayable();
        noteVerdict(service, AppClient.TV_DOWNGRADED, "u_vnA6nlDvs", sabrOnly, next);

        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED), forbidden(service));
        assertEquals("2", storedRecords().get(0)[3]);
    }

    /**
     * The persisted streak belongs to the account that earned it: after a sign-in, switch or
     * removal (YouTubeAccountManager calls onAccountChanged) the next account starts from zero,
     * in this process and the next.
     */
    @Test
    public void anAccountChangeForgetsThePersistedStreak() {
        VideoInfoService service = newService();
        quarantine(service, AppClient.TV_DOWNGRADED);
        countNoMedia(service, AppClient.TV, "u_vnA6nlDvs"); // account A
        assertEquals(1, storedStreaks().size());

        service.onAccountChanged();

        assertTrue(storedStreaks().isEmpty());
        assertEquals("quarantine records are client-level and stay", 1, storedRecords().size());
        VideoInfoService restarted = newService();
        countNoMedia(restarted, AppClient.TV, "yi0PiY1i3XU"); // account B
        assertFalse(forbidden(restarted).contains(AppClient.TV));
    }

    /**
     * A walk that began under account A and finishes after the switch to B must not hand A's
     * verdict to B - neither as B's first streak hit nor as a probation escalation.
     */
    @Test
    public void aWalkFromBeforeAnAccountSwitchCannotCountItsVerdict() {
        VideoInfoService service = newService();
        quarantine(service, AppClient.TV); // strike memory: the next verdict would be probation
        ShadowSystemClock.advanceBy(Duration.ofMinutes(11));
        VideoInfoService.AuthRouteWalkState walkUnderA = new VideoInfoService.AuthRouteWalkState(
                ReflectionHelpers.<Long>getField(service, "mAccountGeneration"));
        walkUnderA.hold(AppClient.TV, "u_vnA6nlDvs", false);
        walkUnderA.hold(AppClient.TV_DOWNGRADED, "u_vnA6nlDvs", false);

        service.onAccountChanged(); // switch to B while the walk is still in flight
        for (java.util.Map.Entry<AppClient, VideoInfoService.AuthRouteWalkState.Held> held
                : walkUnderA.onPlayable().entrySet()) {
            countProven(service, held.getKey(), held.getValue(), walkUnderA);
        }

        assertTrue("no probation escalation", forbidden(service).isEmpty());
        assertEquals("strike 1 untouched", "1", storedRecords().get(0)[3]);
        assertTrue("no streak for B", storedStreaks().isEmpty());
        // A walk that starts under B counts normally.
        countNoMedia(service, AppClient.TV_DOWNGRADED, "yi0PiY1i3XU");
        assertEquals(1, storedStreaks().size());
    }

    /** A head that answers with something usable drops its persisted streak; a timeout does not. */
    @Test
    public void aHealthyAnswerClearsThePersistedStreakButATimeoutDoesNot() throws IOException {
        VideoInfo healthy = parse("""
                {"playabilityStatus": {"status": "OK"}, %s,
                 "streamingData": {"adaptiveFormats": [{"itag": 251, "mimeType": "audio/webm",
                     "url": "https://media.invalid/audio"}]}}
                """.formatted(SIGNED_IN), true);
        VideoInfoService service = newService();
        countNoMedia(service, AppClient.TV, "u_vnA6nlDvs");

        noteVerdict(service, AppClient.TV, "yi0PiY1i3XU", null,
                new VideoInfoService.AuthRouteWalkState());
        assertEquals("no answer is no evidence", 1, storedStreaks().size());

        noteVerdict(service, AppClient.TV, "yi0PiY1i3XU", healthy,
                new VideoInfoService.AuthRouteWalkState());
        assertNull(store.value);
        VideoInfoService restarted = newService();
        countNoMedia(restarted, AppClient.TV, "Fo89b8zAIE4");
        assertTrue("the streak started over", forbidden(restarted).isEmpty());
    }

    /** The restore line must say what came back: records, probation and streaks. */
    @Test
    public void theRestoreLineSaysWhatWasRestored() {
        VideoInfoService first = newService();
        quarantine(first, AppClient.TV_DOWNGRADED);
        countNoMedia(first, AppClient.TV, "u_vnA6nlDvs");
        ShadowLog.clear();

        forbidden(newService());

        String line = restoreLine();
        assertTrue(line, line.contains("network=cell quarantined=1/2 format=v3"));
        assertTrue(line, line.contains("records=cell/TV_DOWNGRADED:s1:"));
        assertTrue(line, line.contains("probation=[]"));
        assertTrue(line, line.contains("streaks=cell/TV:h1:age"));
    }

    /**
     * An expired-but-remembered record is named as on probation. The record's stored times are
     * clock-proof: an expiry at the epoch is past on any clock, and an arming time far in the
     * future is clamped to "now" by the decoder, so the strike is remembered on any clock. The
     * streak is counted by the service itself, on its own clock.
     */
    @Test
    public void theRestoreLineNamesClientsOnProbation() {
        store.value = "v3|cell:TV:0:1:" + (Long.MAX_VALUE / 4) + "|";
        countNoMedia(newService(), AppClient.TV_DOWNGRADED, "u_vnA6nlDvs");
        ShadowLog.clear();

        assertTrue(forbidden(newService()).isEmpty());

        String line = restoreLine();
        assertTrue(line, line.contains("quarantined=0/2 format=v3"));
        assertTrue(line, line.contains("records=cell/TV:s1:0s"));
        assertTrue(line, line.contains("probation=[TV]"));
        assertTrue(line, line.contains("streaks=cell/TV_DOWNGRADED:h1:age0s"));
    }

    @Test
    public void anEmptyRestoreIsLoggedToo() {
        ShadowLog.clear();

        forbidden(newService());

        String line = restoreLine();
        assertTrue(line, line.contains("quarantined=0/2 format=none records=none streaks=none"));
    }

    /**
     * Backward compatibility through the service: the v2 value the previous build persisted
     * restores its records (strikes included) and is rewritten as v3 on the spot. Times are chosen
     * clock-proof (the shadowed service does not read this test's wall clock): a far-future expiry
     * is clamped to the strike-2 cooldown of 40 minutes, which is then asserted exactly.
     */
    @Test
    public void thePreviousBuildsV2ValueRestoresAndIsMigrated() {
        long far = Long.MAX_VALUE / 4;
        store.value = "v2|cell:TV_DOWNGRADED:" + far + ":2:" + far;

        VideoInfoService service = newService();

        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED), forbidden(service));
        assertTrue(store.value, store.value.startsWith("v3|cell:TV_DOWNGRADED:"));
        assertEquals("2", storedRecords().get(0)[3]);
        assertTrue(storedStreaks().isEmpty());
        ShadowSystemClock.advanceBy(Duration.ofMinutes(39));
        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED), forbidden(service));
        ShadowSystemClock.advanceBy(Duration.ofMinutes(2));
        assertTrue("strike 2 = 40 minutes, no more", forbidden(service).isEmpty());
    }

    private static final String SIGNED_IN = """
            "responseContext": {"serviceTrackingParams": [{"service": "GFEEDBACK",
              "params": [{"key": "logged_in", "value": "1"}]}]}
            """;
    private static final String BROKEN_ADAPTIVE = """
            {"itag": 251, "mimeType": "audio/webm"}, {"itag": 248, "mimeType": "video/webm"}
            """;

    /** Stored quarantine records as {@code transport:CLIENT:expiry:strikes:armed} fields. */
    private List<String[]> storedRecords() {
        return storedSection(0);
    }

    /** Stored streaks as {@code transport:CLIENT:hits:lastHit:videoKey} fields. */
    private List<String[]> storedStreaks() {
        return storedSection(1);
    }

    /**
     * Parsed as text rather than decoded: the shadowed service reads a sandbox wall clock that is
     * not this test's, so decoding here would judge its times against the wrong "now".
     */
    private List<String[]> storedSection(int index) {
        assertNotNull(store.value);
        assertTrue(store.value, store.value.startsWith("v3|"));
        String[] sections = store.value.substring(3).split("\\|", -1);
        assertEquals(store.value, 2, sections.length);
        List<String[]> result = new ArrayList<>();
        if (!sections[index].isEmpty()) {
            for (String entry : sections[index].split(";")) {
                result.add(entry.split(":", -1));
            }
        }
        return result;
    }

    private static String restoreLine() {
        for (ShadowLog.LogItem item : ShadowLog.getLogsForTag("NetPath")) {
            if (item.msg.contains("restore-auth-route-quarantine")) {
                return item.msg;
            }
        }
        throw new AssertionError("no restore-auth-route-quarantine line");
    }

    private static void countNoMedia(VideoInfoService service, AppClient client, String videoId) {
        countProven(service, client, new VideoInfoService.AuthRouteWalkState.Held(videoId, false),
                new VideoInfoService.AuthRouteWalkState(
                        ReflectionHelpers.<Long>getField(service, "mAccountGeneration")));
    }

    /** What the walk does with a proven observation: count it under the walk's generation. */
    private static void countProven(VideoInfoService service, AppClient client,
            VideoInfoService.AuthRouteWalkState.Held held,
            VideoInfoService.AuthRouteWalkState walk) {
        ReflectionHelpers.callInstanceMethod(service, "countAuthRouteVerdict",
                ReflectionHelpers.ClassParameter.from(AppClient.class, client),
                ReflectionHelpers.ClassParameter.from(VideoInfoService.AuthRouteWalkState.Held.class,
                        held),
                ReflectionHelpers.ClassParameter.from(long.class, walk.accountGeneration));
    }

    private static void noteVerdict(VideoInfoService service, AppClient client, String videoId,
            @Nullable VideoInfo result, VideoInfoService.AuthRouteWalkState walk) {
        ReflectionHelpers.callInstanceMethod(service, "noteAuthRouteVerdict",
                ReflectionHelpers.ClassParameter.from(AppClient.class, client),
                ReflectionHelpers.ClassParameter.from(String.class, videoId),
                ReflectionHelpers.ClassParameter.from(VideoInfo.class, result),
                ReflectionHelpers.ClassParameter.from(VideoInfoService.AuthRouteWalkState.class,
                        walk));
    }

    /** @param auth what the REQUEST carried, exactly as VideoInfoService stamps it. */
    private static VideoInfo parse(String json, boolean auth) throws IOException {
        Converter<ResponseBody, ?> converter = JsonPathConverterFactory.create()
                .responseBodyConverter(VideoInfo.class, new Annotation[0], null);
        VideoInfo info = (VideoInfo) converter.convert(
                ResponseBody.create(MediaType.get("application/json"), json));
        info.setAuth(auth);
        return info;
    }

    private static VideoInfoService newService() {
        VideoInfoService service = ReflectionHelpers.callConstructor(VideoInfoService.class);
        // The shadowed constructor may skip field initializers; give the instance its own book.
        if (ReflectionHelpers.getField(service, "mAuthRouteQuarantine") == null) {
            ReflectionHelpers.setField(service, "mAuthRouteQuarantine",
                    new AuthRouteQuarantineBook());
        }
        return service;
    }

    private static void quarantine(VideoInfoService service, AppClient client) {
        ReflectionHelpers.callInstanceMethod(service, "quarantineAuthRoute",
                ReflectionHelpers.ClassParameter.from(AppClient.class, client),
                ReflectionHelpers.ClassParameter.from(String.class, "test"));
    }

    private static Set<AppClient> forbidden(VideoInfoService service) {
        return ReflectionHelpers.callInstanceMethod(service, "forbiddenAuthClients");
    }

    private static final class MemoryStore implements VideoInfoService.AuthRouteQuarantineStore {
        @Nullable
        String value;

        @Nullable
        @Override
        public String load() {
            return value;
        }

        @Override
        public void save(@Nullable String snapshot) {
            value = snapshot;
        }
    }

    @Implements(VideoInfoService.class)
    public static class ShadowVideoInfoService {
        static String transport;
        static String network;

        @Implementation
        protected void __constructor__() {
            // Quarantine bookkeeping does not require Retrofit, preferences or a singleton.
        }

        @Implementation
        protected static String activeTransportKey() {
            return transport;
        }

        @Implementation
        protected static String activeNetworkKey() {
            return network;
        }
    }
}
