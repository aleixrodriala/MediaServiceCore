package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.SystemClock;

import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;

/** Real denial accounting, with no HTTP, app initialization, WebView or real session data. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoAnonymousChallengeTest.ShadowVideoInfoService.class,
                VideoInfoAnonymousChallengeTest.ShadowPoTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoAnonymousChallengeTest {
    private static final long COOLDOWN_MS = 15 * 60_000L;
    private VideoInfoService service;

    @Before
    public void setUp() {
        ShadowVideoInfoService.network = "wifi:offline-test";
        ShadowPoTokenGate.rotations = 0;
        ShadowPoTokenGate.resets = 0;
        service = ReflectionHelpers.callConstructor(VideoInfoService.class);
    }

    @Test
    public void freshDenialPreservesSessionAndArmsExistingCooldown() {
        long now = SystemClock.elapsedRealtime();

        noteChallenge(2);

        assertEquals("wifi:offline-test", network());
        assertEquals(now + COOLDOWN_MS, until());
        assertTrue(isChallenged());
        assertNoSessionMutation();
    }

    @Test
    public void repeatedDenialExtendsCooldownWithoutMutatingSession() {
        noteChallenge(2);
        long firstUntil = until();
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10));

        noteChallenge(3);

        assertEquals(firstUntil + 10_000, until());
        assertTrue(isChallenged());
        assertNoSessionMutation();
    }

    @Test
    public void expiredOrDifferentNetworkDenialStillPreservesSession() {
        noteChallenge(2);
        ShadowSystemClock.advanceBy(Duration.ofMillis(COOLDOWN_MS));
        assertFalse(isChallenged());

        noteChallenge(2);
        assertEquals(SystemClock.elapsedRealtime() + COOLDOWN_MS, until());
        assertTrue(isChallenged());
        ShadowVideoInfoService.network = "cell:offline-test";
        noteChallenge(2);

        assertEquals("cell:offline-test", network());
        assertEquals(SystemClock.elapsedRealtime() + COOLDOWN_MS, until());
        assertTrue(isChallenged());
        assertNoSessionMutation();
    }

    @Test
    public void belowThresholdOrMissingNetworkDoesNotArmCooldown() {
        noteChallenge(1);
        assertNull(network());
        assertEquals(0, until());

        ShadowVideoInfoService.network = null;
        noteChallenge(2);

        assertNull(network());
        assertEquals(0, until());
        assertFalse(isChallenged());
        assertNoSessionMutation();
    }

    @Test
    public void offlineMutationSpiesActuallyInterceptEveryPublicMutationEntry() {
        // Canary: a broken shadow must fail this suite, not silently exercise a live token gate.
        assertTrue(PoTokenGate.rotateWebVisitor());
        PoTokenGate.resetCache();
        assertTrue(PoTokenGate.resetCache(AppClient.WEB));

        assertEquals(1, ShadowPoTokenGate.rotations);
        assertEquals(2, ShadowPoTokenGate.resets);
    }

    private void noteChallenge(int hits) {
        ReflectionHelpers.callInstanceMethod(service, "noteAnonymousChallenge",
                ReflectionHelpers.ClassParameter.from(int.class, hits));
    }

    private boolean isChallenged() {
        return ReflectionHelpers.callInstanceMethod(service, "isAnonPartitionChallenged");
    }

    private String network() {
        return ReflectionHelpers.getField(service, "mAnonChallengeNetwork");
    }

    private long until() {
        return ReflectionHelpers.getField(service, "mAnonChallengeUntilMs");
    }

    private void assertNoSessionMutation() {
        assertEquals("denial must not rotate the visitor", 0, ShadowPoTokenGate.rotations);
        assertEquals("denial must not clear tokens", 0, ShadowPoTokenGate.resets);
    }

    @Implements(VideoInfoService.class)
    public static class ShadowVideoInfoService {
        static String network;

        @Implementation
        protected void __constructor__() {
            // Denial accounting does not require Retrofit, preferences or a service singleton.
        }

        @Implementation
        protected static String activeNetworkKey() {
            return network;
        }
    }

    @Implements(PoTokenGate.class)
    public static class ShadowPoTokenGate {
        static int rotations;
        static int resets;

        @Implementation
        protected static void __staticInitializer__() {
            // Keep the real provider/factory and all actual session data out of this test.
        }

        @Implementation
        protected static boolean rotateWebVisitor() {
            rotations++;
            return true;
        }

        @Implementation
        protected static void resetCache() {
            resets++;
        }

        @Implementation
        protected static boolean resetCache(AppClient client) {
            resets++;
            return true;
        }
    }
}
