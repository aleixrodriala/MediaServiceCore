package com.liskovsoft.youtubeapi.service.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/** A failed history fetch is retried a few times instead of losing the video's history for good. */
public class FormatInfoHistorySyncTest {
    @Test
    public void aFetchWithoutTrackingDataIsRetried() throws Exception {
        YouTubeMediaItemFormatInfo anonymous = create();
        for (int i = 1; i < YouTubeMediaItemFormatInfo.MAX_SYNC_ATTEMPTS; i++) {
            anonymous.sync(null);
            assertFalse("attempt " + i, anonymous.isSynced());
        }
        anonymous.sync(create()); // an answer without tracking data
        assertTrue("gives up after the last attempt", anonymous.isSynced());
        assertFalse(anonymous.isAuth());
    }

    @Test
    public void aFetchWithTrackingDataSyncs() throws Exception {
        YouTubeMediaItemFormatInfo anonymous = create();
        anonymous.sync(null);
        assertFalse(anonymous.isSynced());

        YouTubeMediaItemFormatInfo account = create();
        set(account, "mEventId", "ei");
        set(account, "mVisitorMonitoringData", "vm");
        set(account, "mOfParam", "of");
        set(account, "mIsAuth", true);
        anonymous.sync(account);
        assertTrue(anonymous.isSynced());
        assertTrue(anonymous.isAuth());
        assertEquals("ei", anonymous.getEventId());
    }

    private static YouTubeMediaItemFormatInfo create() throws Exception {
        Constructor<YouTubeMediaItemFormatInfo> c = YouTubeMediaItemFormatInfo.class.getDeclaredConstructor();
        c.setAccessible(true);
        return c.newInstance();
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = YouTubeMediaItemFormatInfo.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
