package com.liskovsoft.youtubeapi.playlist;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Application;

import com.liskovsoft.mediaserviceinterfaces.data.ItemGroup;
import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.youtubeapi.playlistgroups.PlaylistGroupServiceImpl;
import com.liskovsoft.youtubeapi.track.TrackingHistoryCacheTest.OfflineRetrofit;

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
import java.util.Collections;
import java.util.List;

/** No network or account state: exercises the actual remote-title cache with no local groups. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class,
        shadows = {OfflineRetrofit.class, PlaylistInfoCacheTest.EmptyLocalGroups.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class PlaylistInfoCacheTest {
    @Test
    public void firstRemoteListIsCachedWithoutAnyLocalPlaylist() {
        PlaylistServiceWrapper service = new PlaylistServiceWrapper();
        List<PlaylistInfo> remote = Collections.singletonList(info());
        assertSame(remote, cache(service, remote));
        assertSame(remote, ReflectionHelpers.getField(service, "mCachedPlaylistInfos"));
    }

    @Test
    public void emptyRemoteListReplacesStaleCachedTitles() {
        PlaylistServiceWrapper service = new PlaylistServiceWrapper();
        cache(service, Collections.singletonList(info()));
        List<PlaylistInfo> empty = Collections.emptyList();
        cache(service, empty);
        assertSame(empty, ReflectionHelpers.getField(service, "mCachedPlaylistInfos"));
    }

    @Test
    public void absentRemoteListClearsStaleCachedTitles() {
        PlaylistServiceWrapper service = new PlaylistServiceWrapper();
        cache(service, Collections.singletonList(info()));
        assertNull(cache(service, null));
        assertNull(ReflectionHelpers.getField(service, "mCachedPlaylistInfos"));
    }

    private List<PlaylistInfo> cache(PlaylistServiceWrapper service, List<PlaylistInfo> remote) {
        return ReflectionHelpers.callInstanceMethod(service, "getCachedPlaylistInfo",
                ClassParameter.from(List.class, remote), ClassParameter.from(String.class, "offline-video"));
    }

    private PlaylistInfo info() {
        return (PlaylistInfo) Proxy.newProxyInstance(PlaylistInfo.class.getClassLoader(),
                new Class<?>[] {PlaylistInfo.class}, (proxy, method, args) -> null);
    }

    @Implements(PlaylistGroupServiceImpl.class)
    public static class EmptyLocalGroups {
        @Implementation
        protected static void __staticInitializer__() {}

        @Implementation
        protected static List<ItemGroup> getPlaylistGroups() {
            return Collections.emptyList();
        }
    }
}
