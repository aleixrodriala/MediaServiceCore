package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Offline: the quarantine snapshot's own rules, with both clocks supplied by the test. */
public class AuthRouteQuarantineSnapshotTest {
    private static final AppClient[] ALLOWED = {AppClient.TV_DOWNGRADED, AppClient.TV};
    private static final String NETWORK = "cell:340";
    private static final long COOLDOWN_MS = 600_000;
    private static final long ELAPSED = 5_000;
    private static final long WALL = 1_788_800_000_000L;

    @Test
    public void survivesAProcessRestartWithItsRemainingCooldown() {
        String snapshot = encode(deadlines(AppClient.TV_DOWNGRADED, ELAPSED + 400_000L));

        // New process: elapsedRealtime restarted, wall clock moved on by a minute.
        Map<AppClient, Long> restored = decode(snapshot, NETWORK, 20, WALL + 60_000);

        assertEquals(Collections.singleton(AppClient.TV_DOWNGRADED), restored.keySet());
        assertEquals(20 + 340_000, (long) restored.get(AppClient.TV_DOWNGRADED));
    }

    @Test
    public void keepsEveryQuarantinedHeadIndependently() {
        String snapshot = encode(deadlines(
                AppClient.TV_DOWNGRADED, ELAPSED + 400_000, AppClient.TV, ELAPSED + 200_000L));

        Map<AppClient, Long> restored = decode(snapshot, NETWORK, ELAPSED, WALL);

        assertEquals(2, restored.size());
        assertEquals(ELAPSED + 400_000, (long) restored.get(AppClient.TV_DOWNGRADED));
        assertEquals(ELAPSED + 200_000, (long) restored.get(AppClient.TV));
    }

    @Test
    public void anotherNetworkStartsCleanBecauseTheVerdictWasNeverAboutIt() {
        String snapshot = encode(deadlines(AppClient.TV_DOWNGRADED, ELAPSED + 400_000L));

        assertTrue(decode(snapshot, "wifi:12", ELAPSED, WALL).isEmpty());
        assertTrue(decode(snapshot, null, ELAPSED, WALL).isEmpty());
    }

    @Test
    public void anExpiredCooldownIsNotResurrected() {
        String snapshot = encode(deadlines(AppClient.TV_DOWNGRADED, ELAPSED + 60_000L));

        assertTrue(decode(snapshot, NETWORK, ELAPSED, WALL + 60_001).isEmpty());
    }

    @Test
    public void aForwardClockJumpCannotExtendTheCooldown() {
        // Written when the wall clock was an hour ahead of where it is now.
        String snapshot = NETWORK + "|" + AppClient.TV_DOWNGRADED.name() + ":" + (WALL + 3_600_000);

        Map<AppClient, Long> restored = decode(snapshot, NETWORK, ELAPSED, WALL);

        assertEquals(ELAPSED + COOLDOWN_MS, (long) restored.get(AppClient.TV_DOWNGRADED));
    }

    @Test
    public void aSnapshotCanOnlyEverNameAnAccountBearingHead() {
        String snapshot = NETWORK + "|" + AppClient.VISIONOS.name() + ":" + (WALL + 400_000)
                + ";NOT_A_CLIENT:" + (WALL + 400_000)
                + ";" + AppClient.TV.name() + ":" + (WALL + 400_000);

        Map<AppClient, Long> restored = decode(snapshot, NETWORK, ELAPSED, WALL);

        assertEquals(Collections.singleton(AppClient.TV), restored.keySet());
    }

    @Test
    public void garbageIsDroppedRatherThanTrusted() {
        for (String snapshot : new String[] {
                null, "", "|", NETWORK, NETWORK + "|", NETWORK + "|TV_DOWNGRADED",
                NETWORK + "|TV_DOWNGRADED:", NETWORK + "|TV_DOWNGRADED:soon", "|TV:1"}) {
            assertTrue(String.valueOf(snapshot),
                    decode(snapshot, NETWORK, ELAPSED, WALL).isEmpty());
        }
    }

    @Test
    public void nothingLiveMeansNothingStored() {
        assertNull(encode(Collections.emptyMap()));
        assertNull(AuthRouteQuarantineSnapshot.encode(
                null, deadlines(AppClient.TV, ELAPSED + 400_000L), ELAPSED, WALL));
        assertNull("an already-expired deadline is not worth persisting",
                encode(deadlines(AppClient.TV, ELAPSED - 1L)));
    }

    private static Map<AppClient, Long> deadlines(Object... clientsAndDeadlines) {
        Map<AppClient, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < clientsAndDeadlines.length; i += 2) {
            result.put((AppClient) clientsAndDeadlines[i], (Long) clientsAndDeadlines[i + 1]);
        }
        return result;
    }

    private static String encode(Map<AppClient, Long> deadlines) {
        return AuthRouteQuarantineSnapshot.encode(NETWORK, deadlines, ELAPSED, WALL);
    }

    private static Map<AppClient, Long> decode(String snapshot, String network, long elapsed,
            long wall) {
        return AuthRouteQuarantineSnapshot.decode(
                snapshot, network, ALLOWED, elapsed, wall, COOLDOWN_MS);
    }
}
