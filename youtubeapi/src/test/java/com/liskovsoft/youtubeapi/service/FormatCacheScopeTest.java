package com.liskovsoft.youtubeapi.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService;

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

import java.lang.reflect.Proxy;

/**
 * NEWTUBE(recovery): the mobile format caches only answer on the attachment their URLs were
 * minted on, and the legacy one-slot cache can no longer bypass the 5-minute TTL (Codex C4).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {FormatCacheScopeTest.ShadowNetworkKey.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class FormatCacheScopeTest {
    private YouTubeMediaItemService service;

    @Before
    public void setUp() {
        ShadowNetworkKey.network = "wifi:100";
        YouTubeMediaItemService.setSingleFlightEnabled(true);
        service = ReflectionHelpers.callConstructor(YouTubeMediaItemService.class);
    }

    @After
    public void tearDown() {
        YouTubeMediaItemService.setSingleFlightEnabled(false);
    }

    @Test
    public void theLegacySlotCannotServeWhatTheMobileCacheRejected() {
        MediaItemFormatInfo playable = formatInfo("v", false, false);
        ReflectionHelpers.setField(service, "mCachedFormatInfo", playable);

        assertNull("mobile: a miss in the working set is a miss", cached("v"));

        YouTubeMediaItemService.setSingleFlightEnabled(false);
        assertSame("TV keeps its one-slot cache unchanged", playable, cached("v"));
    }

    @Test
    public void urlsMintedOnAnotherAttachmentAreStale() {
        MediaItemFormatInfo playable = formatInfo("v", false, false);
        store("v", playable);
        assertSame(playable, cached("v"));

        ShadowNetworkKey.network = "cell:106";
        assertNull("the URLs carry the Wi-Fi IP", cached("v"));
    }

    @Test
    public void onlyABotCheckVerdictIsScopedToItsAttachment() {
        MediaItemFormatInfo walled = formatInfo("w", true, true);
        store("w", walled);
        assertSame(walled, cached("w"));
        ShadowNetworkKey.network = "cell:106";
        assertNull("another attachment is not walled", cached("w"));

        MediaItemFormatInfo removed = formatInfo("r", true, false);
        store("r", removed);
        ShadowNetworkKey.network = "wifi:100";
        assertSame("a removed video is removed on every network", removed, cached("r"));
    }

    @Test
    public void unknownAttachmentsHaveNoOpinion() {
        assertTrue(YouTubeMediaItemService.isSameAttachment(null, "cell:1"));
        assertTrue(YouTubeMediaItemService.isSameAttachment("cell:1", null));
        assertTrue(YouTubeMediaItemService.isSameAttachment("cell:1", "cell:1"));
        assertFalse(YouTubeMediaItemService.isSameAttachment("cell:1", "wifi:2"));
    }

    private MediaItemFormatInfo cached(String videoId) {
        return ReflectionHelpers.callInstanceMethod(service, "getCachedFormatInfo",
                ClassParameter.from(String.class, videoId));
    }

    /** What fetchFormatInfo does with a fresh answer: the positive or the negative cache. */
    private void store(String videoId, MediaItemFormatInfo info) {
        if (info.isUnplayable()) {
            Object entry = ReflectionHelpers.callConstructor(
                    ReflectionHelpers.loadClass(getClass().getClassLoader(),
                            YouTubeMediaItemService.class.getName() + "$UnplayableEntry"),
                    ClassParameter.from(String.class, videoId),
                    ClassParameter.from(MediaItemFormatInfo.class, info));
            ReflectionHelpers.setField(service, "mUnplayableEntry", entry);
        } else {
            ReflectionHelpers.callInstanceMethod(service, "setCachedFormatInfo",
                    ClassParameter.from(String.class, videoId),
                    ClassParameter.from(MediaItemFormatInfo.class, info),
                    ClassParameter.from(String.class, null));
        }
    }

    private static MediaItemFormatInfo formatInfo(String videoId, boolean unplayable,
            boolean botCheck) {
        return (MediaItemFormatInfo) Proxy.newProxyInstance(
                MediaItemFormatInfo.class.getClassLoader(), new Class<?>[] {MediaItemFormatInfo.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getVideoId": return videoId;
                        case "isCacheActual": return !unplayable;
                        case "containsMedia": return !unplayable;
                        case "isUnplayable": return unplayable;
                        case "isBotCheckRequired": return botCheck;
                        case "hashCode": return System.identityHashCode(proxy);
                        case "equals": return proxy == args[0];
                        default:
                            Class<?> type = method.getReturnType();
                            if (type == boolean.class) return false;
                            if (type == int.class) return 0;
                            if (type == long.class) return 0L;
                            if (type == float.class) return 0f;
                            return null;
                    }
                });
    }

    @Implements(VideoInfoService.class)
    public static class ShadowNetworkKey {
        static String network;

        @Implementation
        protected static String activeNetworkKey() {
            return network;
        }
    }
}
