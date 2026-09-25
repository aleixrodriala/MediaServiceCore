package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import org.junit.After;
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
import java.util.Collections;
import java.util.Set;

/**
 * The account-route quarantine through the real service entry points - keying, persistence across
 * a restart, escalation and the legacy snapshot - with no HTTP, prefs or app initialization.
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
        assertTrue(store.value, store.value.startsWith("v2|cell:TV_DOWNGRADED:"));
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
        assertTrue(store.value, store.value.startsWith("v2|cell:TV_DOWNGRADED:"));
        ShadowVideoInfoService.transport = "wifi";
        assertTrue(forbidden(service).isEmpty());
    }

    @Test
    public void anUnreadableSnapshotIsClearedRatherThanTrusted() {
        store.value = "v2|garbage";

        assertTrue(forbidden(newService()).isEmpty());
        assertNull(store.value);
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
