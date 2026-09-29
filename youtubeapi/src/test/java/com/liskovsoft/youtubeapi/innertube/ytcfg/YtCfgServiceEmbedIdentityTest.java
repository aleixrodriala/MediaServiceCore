package com.liskovsoft.youtubeapi.innertube.ytcfg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.app.Application;

import androidx.annotation.Nullable;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.concurrent.TimeUnit;

/**
 * NEWTUBE(embed-persist): YtCfgService's side of the persisted embed identity. Every ask here passes
 * a null video id, so a missing identity returns null instead of fetching the embed page: these
 * tests never touch the network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public class YtCfgServiceEmbedIdentityTest {
    private final MemoryStore mStore = new MemoryStore();

    private static final class MemoryStore implements EmbedIdentityPersistence.Store {
        @Nullable
        String snapshot;

        @Nullable
        @Override
        public String load() {
            return snapshot;
        }

        @Override
        public void save(@Nullable String snapshot) {
            this.snapshot = snapshot;
        }
    }

    @Before
    public void setUp() {
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity", null);
        EmbedIdentityPersistence.setStore(mStore);
    }

    @After
    public void tearDown() {
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity", null);
        EmbedIdentityPersistence.setStore(null);
    }

    private static String snapshot(long fetchedAtMs) {
        return EmbedIdentityPersistence.encode(
                new YtCfgService.EmbedIdentity("persisted-flags", "persisted-visitor", fetchedAtMs));
    }

    @Test
    public void aNewProcessUsesThePersistedPairWithoutAFetch() {
        long fetchedAtMs = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(5);
        mStore.snapshot = snapshot(fetchedAtMs);

        YtCfgService.EmbedIdentity identity = YtCfgService.getEmbedIdentity(null);

        assertNotNull(identity);
        assertEquals("persisted-flags", identity.encryptedHostFlags);
        assertEquals("persisted-visitor", identity.visitorData);
        assertEquals(fetchedAtMs, identity.fetchedAtMs);
        // Served from memory from here on; the store is not read twice.
        mStore.snapshot = null;
        assertSame(identity, YtCfgService.getEmbedIdentity(null));
    }

    @Test
    public void aPairOlderThanSixHoursIsNotUsed() {
        mStore.snapshot = snapshot(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(6) - 1_000);

        assertNull(YtCfgService.getEmbedIdentity(null));
        assertNull(mStore.snapshot);
    }

    @Test
    public void anInvalidationDuringTheRestoreKeepsThePairOutOfTheCache() {
        mStore.snapshot = snapshot(System.currentTimeMillis() - 1_000);
        EmbedIdentityPersistence.setStore(new EmbedIdentityPersistence.Store() {
            @Nullable
            @Override
            public String load() {
                String snapshot = mStore.load();
                YtCfgService.invalidateEmbedIdentity(); // another request's "Error code: 152" lands now
                return snapshot;
            }

            @Override
            public void save(@Nullable String snapshot) {
                mStore.save(snapshot);
            }
        });

        assertNotNull(YtCfgService.getEmbedIdentity(null)); // the ask in flight still uses it
        assertNull(mStore.snapshot);
        assertNull(YtCfgService.getEmbedIdentity(null)); // but it was neither cached nor persisted
    }

    @Test
    public void invalidationDropsBothCopies() {
        mStore.snapshot = snapshot(System.currentTimeMillis() - 1_000);
        assertNotNull(YtCfgService.getEmbedIdentity(null));

        YtCfgService.invalidateEmbedIdentity(); // the "Error code: 152" path

        assertNull(mStore.snapshot);
        assertNull(YtCfgService.getEmbedIdentity(null)); // the next real ask fetches a fresh page
    }
}
