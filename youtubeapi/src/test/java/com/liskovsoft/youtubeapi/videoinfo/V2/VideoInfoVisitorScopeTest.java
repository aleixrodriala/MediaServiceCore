package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.AppServiceIntCached;
import com.liskovsoft.youtubeapi.app.PoTokenGate;
import com.liskovsoft.youtubeapi.app.models.cached.AppInfoCached;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

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

/**
 * NEWTUBE(visitor): the app's persistent visitor (AppService visitorData, its cookie jar and the
 * persisted app info adopted at cold start) must come out of a bot wall untouched - it is what
 * Home, search, /next and signed-out history accumulate on. The Pixel 9 lost it on every wall
 * while the phone rotated it (seven new persistent visitors in about two minutes on the 09-25 LTE
 * wall, none of which played). The phone no longer rotates at all; the dormant opt-in variant may
 * only ever touch the web-pot session. Offline: no HTTP, no WebView; the token gate is a counting
 * shadow.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {VideoInfoVisitorScopeTest.ShadowVideoInfoService.class,
                VideoInfoVisitorScopeTest.ShadowPoTokenGate.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VideoInfoVisitorScopeTest {
    private static final String COOKIE = "VISITOR_INFO1_LIVE=persistent; YSC=a";
    private static final String VISITOR = "PERSISTENT_VISITOR";
    private VideoInfoService service;
    private AppInfoCached persisted;

    @Before
    public void setUp() {
        ShadowVideoInfoService.network = "cell:wall-test";
        ShadowPoTokenGate.rotations = 0;
        ShadowPoTokenGate.resets = 0;
        ShadowPoTokenGate.rotationResult = true;
        // A fresh singleton that reads the persisted app info (as the phone does at cold start)
        // instead of fetching youtube.com/tv.
        ReflectionHelpers.setStaticField(AppService.class, "sInstance", null);
        AppServiceIntCached.setPersistedAppInfoEnabled(true);
        persisted = AppInfoCached.fromString("https://player.invalid/base.js%aic%"
                + "https://client.invalid/base.js%aic%" + VISITOR + "%aic%" + System.currentTimeMillis());
        MediaServiceData.instance().setVisitorCookie(COOKIE);
        MediaServiceData.instance().setAppInfo(persisted);
        // Most tests exercise the dormant opt-in variant; the default is tested explicitly.
        VideoInfoService.setRotateVisitorOnAnonChallenge(true);
        service = ReflectionHelpers.callConstructor(VideoInfoService.class);
        assertEquals("setup: the persistent visitor is served from the persisted app info",
                VISITOR, AppService.instance().getVisitorData());
    }

    @After
    public void tearDown() {
        VideoInfoService.setRotateVisitorOnAnonChallenge(false);
        AppServiceIntCached.setPersistedAppInfoEnabled(false);
        MediaServiceData.instance().setVisitorCookie(null);
        MediaServiceData.instance().setAppInfo(null);
        ReflectionHelpers.setStaticField(AppService.class, "sInstance", null);
    }

    @Test
    public void defaultWallRotatesNothingAndOnlyArmsTheCooldown() {
        // What both flavors run now: setRotateVisitorOnAnonChallenge is never called.
        VideoInfoService.setRotateVisitorOnAnonChallenge(false);

        noteChallenge(2);
        ShadowVideoInfoService.network = "wifi:wall-test";
        noteChallenge(2);

        assertEquals(0, ShadowPoTokenGate.rotations);
        assertEquals("not even the web-pot token session is thrown away", 0, ShadowPoTokenGate.resets);
        assertTrue("the wall is still remembered, so the ring stops leading with it",
                (boolean) ReflectionHelpers.callInstanceMethod(service, "isAnonPartitionChallenged"));
        assertPersistentIdentityUntouched();
    }

    @Test
    public void wallRotatesTheWebPotSessionAndKeepsThePersistentVisitor() {
        noteChallenge(2);

        assertEquals("the web-pot session is what the challenged anonymous clients carry",
                1, ShadowPoTokenGate.rotations);
        assertPersistentIdentityUntouched();
    }

    @Test
    public void repeatedChallengeInsideTheCooldownRotatesNothingMore() {
        noteChallenge(2);
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30));
        noteChallenge(3);

        assertEquals(1, ShadowPoTokenGate.rotations);
        assertPersistentIdentityUntouched();
    }

    @Test
    public void refusedWebRotationStillLeavesThePersistentVisitorAlone() {
        // Rate-limited (or no WebView): the old fallback was a full identity reset.
        ShadowPoTokenGate.rotationResult = false;

        noteChallenge(2);

        assertEquals(1, ShadowPoTokenGate.rotations);
        assertPersistentIdentityUntouched();
    }

    @Test
    public void aNewNetworkWallStillKeepsThePersistentVisitor() {
        noteChallenge(2);
        ShadowVideoInfoService.network = "wifi:wall-test";
        noteChallenge(2);

        assertEquals("a fresh wall on another network may rotate the session again",
                2, ShadowPoTokenGate.rotations);
        assertPersistentIdentityUntouched();
    }

    private void assertPersistentIdentityUntouched() {
        assertEquals("cookie jar replays the identity on the next youtube.com/tv refresh",
                COOKIE, MediaServiceData.instance().getVisitorCookie());
        assertTrue("persisted app info is what the next cold start adopts",
                persisted == MediaServiceData.instance().getAppInfo());
        assertEquals("Home/browse, search and /next keep the same visitor",
                VISITOR, AppService.instance().getVisitorData());
    }

    private void noteChallenge(int hits) {
        ReflectionHelpers.callInstanceMethod(service, "noteAnonymousChallenge",
                ReflectionHelpers.ClassParameter.from(int.class, hits));
    }

    @Implements(VideoInfoService.class)
    public static class ShadowVideoInfoService {
        static String network;

        @Implementation
        protected void __constructor__() {
            // Challenge accounting does not require Retrofit, preferences or a service singleton.
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
        static boolean rotationResult;

        @Implementation
        protected static void __staticInitializer__() {
            // Keep the real provider/factory and all actual session data out of this test.
        }

        @Implementation
        protected static boolean rotateWebVisitor() {
            rotations++;
            return rotationResult;
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
