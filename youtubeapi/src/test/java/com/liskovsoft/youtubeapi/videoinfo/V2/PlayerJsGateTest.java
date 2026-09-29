package com.liskovsoft.youtubeapi.videoinfo.V2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.youtubeapi.app.AppService;
import com.liskovsoft.youtubeapi.app.AppServiceIntCached;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.common.helpers.DebugRequestOverrides;
import com.liskovsoft.youtubeapi.innertube.ytcfg.YtCfgService;
import com.liskovsoft.youtubeapi.videoinfo.VideoInfoServiceBase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * NEWTUBE(player-js-gate): which /player requests wait for a new player's validation. On the phone
 * VISIONOS and ANDROID_VR take the read-ahead values and send the same bytes; every other source
 * asks AppService the way it always did (which waits); TV and the rollback change nothing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class, shadows = {
        PlayerJsGateTest.ShadowAppService.class,
        PlayerRequestGoldenTest.ShadowPoTokenProvider.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlayerJsGateTest {
    private static final Set<AppClient> SKIPS = EnumSet.of(AppClient.VISIONOS, AppClient.ANDROID_VR);

    private Locale mLocale;
    private TimeZone mTimeZone;

    @Before
    public void setUp() {
        mLocale = Locale.getDefault();
        mTimeZone = TimeZone.getDefault();
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        ShadowAppService.service = ReflectionHelpers.callConstructor(AppService.class);
        ShadowAppService.reset();
        DebugRequestOverrides.setSupportXhr(null);
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity",
                new YtCfgService.EmbedIdentity("golden-host-flags", "golden-embed-visitor",
                        System.currentTimeMillis()));
    }

    @After
    public void tearDown() {
        VideoInfoService.setPlayerJsGateEnabled(true);
        VideoInfoService.setPreferNoPotClient(false);
        Locale.setDefault(mLocale);
        TimeZone.setDefault(mTimeZone);
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity", null);
    }

    @Test
    public void onlySourcesWithNothingToSolveSkipOnThePhone() {
        assertEquals(SKIPS, PlayerJsGate.sources());
        for (AppClient client : AppClient.values()) {
            assertEquals(client.name(), SKIPS.contains(client),
                    PlayerJsGate.skipsValidation(client, true, true));
        }
    }

    @Test
    public void cipheredSourcesKeepWaiting() {
        for (AppClient client : new AppClient[] {AppClient.TV_TIZEN, AppClient.WEB_EMBED,
                AppClient.MWEB, AppClient.WEB, AppClient.TV, AppClient.TV_DOWNGRADED,
                AppClient.WEB_SAFARI, AppClient.IOS, AppClient.ANDROID_REEL}) {
            assertFalse(client.name(), PlayerJsGate.skipsValidation(client, true, true));
        }
    }

    @Test
    public void tvAndTheRollbackAlwaysWait() {
        for (AppClient client : AppClient.values()) {
            assertFalse("tv " + client, PlayerJsGate.skipsValidation(client, false, true));
            assertFalse("rollback " + client, PlayerJsGate.skipsValidation(client, true, false));
        }
        assertFalse(PlayerJsGate.skipsValidation(null, true, true));
    }

    @Test
    public void thePhonePathTurnsTheGateOnAndTheRollbackOff() {
        assertFalse("off until the phone path is set", VideoInfoService.skipsPlayerJsValidation(AppClient.VISIONOS));
        assertGateReachesTheBuild(false);

        VideoInfoService.setPreferNoPotClient(true);
        assertTrue(VideoInfoService.skipsPlayerJsValidation(AppClient.VISIONOS));
        assertTrue(VideoInfoService.skipsPlayerJsValidation(AppClient.ANDROID_VR));
        assertFalse(VideoInfoService.skipsPlayerJsValidation(AppClient.TV_TIZEN));
        assertGateReachesTheBuild(true);

        VideoInfoService.setPlayerJsGateEnabled(false);
        assertFalse(VideoInfoService.skipsPlayerJsValidation(AppClient.VISIONOS));
        assertGateReachesTheBuild(false);

        VideoInfoService.setPlayerJsGateEnabled(true);
        assertGateReachesTheBuild(true);
        VideoInfoService.setPreferNoPotClient(false);
        assertGateReachesTheBuild(false);
    }

    /** The two sources send the bytes they sent before; only where the cpn/sts come from moves. */
    @Test
    public void aSkippingRequestIsTheRequestItWas() {
        VideoInfoService.setPreferNoPotClient(true);
        for (AppClient client : SKIPS) {
            ShadowAppService.reset();
            String gated = query(client);
            assertEquals(client + ": read ahead once", 1, ShadowAppService.readAheadCalls);
            assertEquals(client + ": never asks the waiting path", 0, ShadowAppService.waitingCalls);

            VideoInfoService.setPlayerJsGateEnabled(false);
            ShadowAppService.reset();
            String waited = query(client);
            VideoInfoService.setPlayerJsGateEnabled(true);
            assertEquals(client + ": the rollback reads nothing ahead", 0, ShadowAppService.readAheadCalls);
            assertTrue(client + ": the rollback waits", ShadowAppService.waitingCalls > 0);

            assertEquals(client + ": same request", waited, gated);
        }
    }

    @Test
    public void everyOtherSourceWaitsAsBefore() {
        VideoInfoService.setPreferNoPotClient(true);
        for (AppClient client : AppClient.values()) {
            if (SKIPS.contains(client) || client == AppClient.INITIAL) {
                continue;
            }
            ShadowAppService.reset();
            query(client);
            assertEquals(client.name(), 0, ShadowAppService.readAheadCalls);
            assertTrue(client.name(), ShadowAppService.waitingCalls > 0);
        }
    }

    @Test
    public void onTvNoSourceReadsAhead() {
        for (AppClient client : AppClient.values()) {
            if (client == AppClient.INITIAL) {
                continue;
            }
            ShadowAppService.reset();
            query(client);
            assertEquals(client.name(), 0, ShadowAppService.readAheadCalls);
        }
    }

    /** A built player (or nothing read ahead): the request asks AppService, as before. */
    @Test
    public void nothingReadAheadFallsBackToWaiting() {
        VideoInfoService.setPreferNoPotClient(true);
        ShadowAppService.readAhead = false;
        String fallback = query(AppClient.VISIONOS);
        assertEquals(1, ShadowAppService.readAheadCalls);
        assertTrue(ShadowAppService.waitingCalls > 0);

        ShadowAppService.reset();
        assertEquals(fallback, query(AppClient.VISIONOS));
    }

    private static void assertGateReachesTheBuild(boolean on) {
        assertEquals(on, (boolean) ReflectionHelpers.getStaticField(AppServiceIntCached.class, "sPlayerJsReadAhead"));
        assertEquals(on, (boolean) ReflectionHelpers.getStaticField(VideoInfoServiceBase.class, "sSkipSolveWithoutChallenges"));
    }

    private static String query(AppClient client) {
        return VideoInfoApiHelper.getVideoInfoRequest(client, "gate-vid-01", null).query;
    }

    @Implements(AppService.class)
    public static class ShadowAppService {
        static AppService service;
        static int readAheadCalls;
        static int waitingCalls;
        static boolean readAhead;

        static void reset() {
            readAheadCalls = 0;
            waitingCalls = 0;
            readAhead = true;
        }

        @Implementation
        protected void __constructor__() {
            // No Retrofit, preferences or player-JS extraction.
        }

        @Implementation
        protected static AppService instance() {
            return service;
        }

        /** The waiting path: the extractor, validated. */
        @Implementation
        protected String getSignatureTimestamp() {
            waitingCalls++;
            return "20697";
        }

        @Implementation
        protected String getClientPlaybackNonce() {
            waitingCalls++;
            return "gate-cpn-0001";
        }

        /** The read-ahead: the same player's timestamp and this video's cpn, before validation. */
        @Implementation
        protected AppService.ReadAheadPlayerData getReadAheadPlayerData() {
            readAheadCalls++;
            if (!readAhead) {
                return null;
            }
            return ReflectionHelpers.callConstructor(AppService.ReadAheadPlayerData.class,
                    ClassParameter.from(String.class, "gate-cpn-0001"),
                    ClassParameter.from(String.class, "20697"));
        }

        @Implementation
        protected String getVisitorData() {
            return "gate-app-visitor";
        }
    }
}
