package com.liskovsoft.youtubeapi.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Application;

import com.liskovsoft.youtubeapi.app.models.cached.AppInfoCached;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.util.ReflectionHelpers;

/**
 * Offline: rotating the anonymous identity has to drop every place the old visitorData is kept,
 * or the next request replays the identity that was just challenged. No network, no credentials.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
@ConscryptMode(ConscryptMode.Mode.OFF)
public class VisitorRotationTest {
    private AppServiceIntCached mService;

    @Before
    public void setUp() {
        mService = ReflectionHelpers.callConstructor(AppServiceIntCached.class);
        MediaServiceData.instance().setVisitorCookie("VISITOR_INFO1_LIVE=challenged");
        MediaServiceData.instance().setAppInfo(cachedInfo());
        ReflectionHelpers.setField(mService, "mAppInfo", cachedInfo());
        ReflectionHelpers.setField(mService, "mAppInfoUpdateTimeMs", System.currentTimeMillis());
    }

    /** The shape a real cached entry has: a player url, a client url and the visitorData. */
    private static AppInfoCached cachedInfo() {
        return AppInfoCached.fromString("https://player.invalid/base.js%aic%"
                + "https://client.invalid/desktop_polymer.js%aic%CHALLENGED_VISITOR%aic%"
                + System.currentTimeMillis());
    }

    @Test
    public void rotationDropsTheInMemoryThePersistedAndTheCookieCopies() {
        assertNotNull(ReflectionHelpers.getField(mService, "mAppInfo"));

        mService.rotateVisitorData();

        assertNull("in-memory app info still carries the old visitorData",
                ReflectionHelpers.getField(mService, "mAppInfo"));
        assertNull("the persisted copy is adopted at cold start, so it has to go too",
                MediaServiceData.instance().getAppInfo());
        assertNull("replaying the cookie would mint the same visitor again",
                MediaServiceData.instance().getVisitorCookie());
    }

    @Test
    public void invalidatingTheCookieAloneIsNotRotation() {
        mService.invalidateVisitorData();

        assertNull(MediaServiceData.instance().getVisitorCookie());
        assertNotNull("the visitorData itself lives in the cached app info",
                ReflectionHelpers.getField(mService, "mAppInfo"));
    }
}
