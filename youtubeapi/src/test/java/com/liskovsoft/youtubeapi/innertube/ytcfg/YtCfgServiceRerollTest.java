package com.liskovsoft.youtubeapi.innertube.ytcfg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * NEWTUBE(embed-reroll): YtCfgService's re-roll of an embed identity whose visitor gets SABR-only
 * answers: the identity goes (both copies), a new one is fetched off the walk, at most once per 6 h.
 * The embed page is a fake here: no network.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class)
public class YtCfgServiceRerollTest {
    private static final long HOUR = TimeUnit.HOURS.toMillis(1);
    private final List<Runnable> background = new ArrayList<>();
    private final List<String> pages = new ArrayList<>();
    private final MemoryStore store = new MemoryStore();
    private Object savedExecutor;
    private Object savedFetcher;

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
        savedExecutor = ReflectionHelpers.getStaticField(YtCfgService.class, "rerollExecutor");
        savedFetcher = ReflectionHelpers.getStaticField(YtCfgService.class, "embedPageFetcher");
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity", null);
        ReflectionHelpers.setStaticField(YtCfgService.class, "lastRerollAtMs", 0L);
        ReflectionHelpers.setStaticField(YtCfgService.class, "rerollExecutor",
                (Function1<Runnable, Unit>) task -> {
                    background.add(task);
                    return Unit.INSTANCE;
                });
        ReflectionHelpers.setStaticField(YtCfgService.class, "embedPageFetcher",
                (Function1<String, JsonObject>) videoId -> {
                    pages.add(videoId);
                    return JsonParser.parseString("{\"WEB_PLAYER_CONTEXT_CONFIGS\": {"
                            + "\"WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER\": {\"encryptedHostFlags\": \"new-flags\"}},"
                            + " \"VISITOR_DATA\": \"new-visitor-" + pages.size() + "\"}").getAsJsonObject();
                });
        EmbedIdentityPersistence.setStore(store);
    }

    @After
    public void tearDown() {
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity", null);
        ReflectionHelpers.setStaticField(YtCfgService.class, "lastRerollAtMs", 0L);
        ReflectionHelpers.setStaticField(YtCfgService.class, "rerollExecutor", savedExecutor);
        ReflectionHelpers.setStaticField(YtCfgService.class, "embedPageFetcher", savedFetcher);
        EmbedIdentityPersistence.setStore(null);
    }

    private void cache(YtCfgService.EmbedIdentity identity) {
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity", identity);
        store.snapshot = EmbedIdentityPersistence.encode(identity);
    }

    @Nullable
    private static YtCfgService.EmbedIdentity cached() {
        return ReflectionHelpers.getStaticField(YtCfgService.class, "cachedEmbedIdentity");
    }

    /** The bucketed identity goes, both copies; a new visitor is fetched in the background. */
    @Test
    public void aSabrOnlyIdentityIsReplacedOffTheWalk() {
        cache(new YtCfgService.EmbedIdentity("old-flags", "old-visitor", System.currentTimeMillis() - HOUR));
        assertTrue(YtCfgService.rerollEmbedIdentity("v1"));
        assertNull(cached());
        assertNull("the persisted pair goes too", EmbedIdentityPersistence.decode(store.snapshot));
        assertTrue("its budget stays", EmbedIdentityPersistence.decodeRerolledAtMs(store.snapshot) > 0);
        assertTrue("nothing fetched in the walk", pages.isEmpty());
        assertEquals(1, background.size());

        background.get(0).run();
        assertEquals(1, pages.size());
        YtCfgService.EmbedIdentity fresh = cached();
        assertNotNull(fresh);
        assertEquals("new-visitor-1", fresh.visitorData);
        assertTrue("carries the re-roll's budget", fresh.rerolledAtMs > 0);
        assertEquals(fresh.rerolledAtMs,
                EmbedIdentityPersistence.decode(store.snapshot).rerolledAtMs);
    }

    /** The new visitor is bucketed too: it keeps HLS for its lifetime. No loop. */
    @Test
    public void aReRolledIdentityIsNeverReRolledAgain() {
        cache(new YtCfgService.EmbedIdentity("old-flags", "old-visitor", System.currentTimeMillis()));
        assertTrue(YtCfgService.rerollEmbedIdentity("v1"));
        background.get(0).run();
        assertFalse(YtCfgService.rerollEmbedIdentity("v2"));
        assertNotNull("kept", cached());
        assertEquals(1, background.size());
    }

    /** A new process restores the re-rolled identity with its budget: still no second re-roll. */
    @Test
    public void theBudgetSurvivesARestart() {
        long now = System.currentTimeMillis();
        store.snapshot = EmbedIdentityPersistence.encode(
                new YtCfgService.EmbedIdentity("flags", "visitor", now - HOUR, now - HOUR));
        assertNotNull(YtCfgService.getEmbedIdentity(null));
        assertFalse(YtCfgService.rerollEmbedIdentity("v1"));
        assertTrue(background.isEmpty());
    }

    /** ...and a 152 refetch in the same process inherits it too. */
    @Test
    public void theBudgetSurvivesA152Refetch() {
        cache(new YtCfgService.EmbedIdentity("old-flags", "old-visitor", System.currentTimeMillis()));
        assertTrue(YtCfgService.rerollEmbedIdentity("v1"));
        background.get(0).run();
        YtCfgService.invalidateEmbedIdentity(); // a 152
        YtCfgService.EmbedIdentity refetched = YtCfgService.getEmbedIdentity("v2");
        assertNotNull(refetched);
        assertTrue(refetched.rerolledAtMs > 0);
        assertFalse(YtCfgService.rerollEmbedIdentity("v3"));
    }

    /** Six hours after the last re-roll, a bucketed identity may be re-rolled again. */
    @Test
    public void theBudgetIsSixHours() {
        long now = System.currentTimeMillis();
        ReflectionHelpers.setStaticField(YtCfgService.class, "lastRerollAtMs", now - 6 * HOUR - 1);
        cache(new YtCfgService.EmbedIdentity("flags", "visitor", now - HOUR));
        assertTrue(YtCfgService.rerollEmbedIdentity("v1"));
    }

    /**
     * The Codex review's cases. A SABR-only answer that comes back after the identity was already
     * replaced (a 152, another walk) re-rolls nothing: the newer pair has said nothing yet.
     */
    @Test
    public void onlyTheIdentityThatGotTheAnswerIsReRolled() {
        cache(new YtCfgService.EmbedIdentity("flags", "newer-visitor", System.currentTimeMillis()));
        assertFalse(YtCfgService.rerollEmbedIdentity("v1", "older-visitor"));
        assertNotNull(cached());
        assertTrue(YtCfgService.rerollEmbedIdentity("v1", "newer-visitor"));
    }

    /** The budget is on disk before the new pair: a process that dies in between still knows. */
    @Test
    public void theBudgetSurvivesARestartBeforeTheNewPair() {
        cache(new YtCfgService.EmbedIdentity("old-flags", "old-visitor", System.currentTimeMillis()));
        assertTrue(YtCfgService.rerollEmbedIdentity("v1", "old-visitor"));
        assertNotNull(store.snapshot);
        assertNull("a mark, no pair", EmbedIdentityPersistence.decode(store.snapshot));
        assertTrue(EmbedIdentityPersistence.decodeRerolledAtMs(store.snapshot) > 0);

        // The process dies before the background fetch; a new one starts.
        restart();
        assertNull("no pair to restore", YtCfgService.getEmbedIdentity(null));
        assertNotNull("the mark stays while its budget holds", store.snapshot);
        cache(new YtCfgService.EmbedIdentity("fetched-flags", "fetched-visitor", System.currentTimeMillis()));
        assertFalse(YtCfgService.rerollEmbedIdentity("v2", "fetched-visitor"));
    }

    /** A 152 after a re-roll drops the pair and keeps the budget on disk. */
    @Test
    public void a152KeepsTheBudgetOnDisk() {
        cache(new YtCfgService.EmbedIdentity("old-flags", "old-visitor", System.currentTimeMillis()));
        assertTrue(YtCfgService.rerollEmbedIdentity("v1", "old-visitor"));
        background.get(0).run();
        YtCfgService.invalidateEmbedIdentity();
        assertNull(EmbedIdentityPersistence.decode(store.snapshot));
        assertTrue(EmbedIdentityPersistence.decodeRerolledAtMs(store.snapshot) > 0);
        restart();
        YtCfgService.getEmbedIdentity(null);
        cache(new YtCfgService.EmbedIdentity("f", "fetched-visitor", System.currentTimeMillis()));
        assertFalse(YtCfgService.rerollEmbedIdentity("v2", "fetched-visitor"));
    }

    /** Without a re-roll, a 152 clears the disk as before. */
    @Test
    public void a152WithoutAReRollClearsTheDisk() {
        cache(new YtCfgService.EmbedIdentity("flags", "visitor", System.currentTimeMillis()));
        YtCfgService.invalidateEmbedIdentity();
        assertNull(store.snapshot);
    }

    private void restart() {
        ReflectionHelpers.setStaticField(YtCfgService.class, "cachedEmbedIdentity", null);
        ReflectionHelpers.setStaticField(YtCfgService.class, "lastRerollAtMs", 0L);
        EmbedIdentityPersistence.setStore(store);
    }

    /** Nothing cached (already dropped by a 152, or never fetched): nothing to re-roll. */
    @Test
    public void noIdentityNoReRoll() {
        assertFalse(YtCfgService.rerollEmbedIdentity("v1"));
        assertFalse(YtCfgService.rerollEmbedIdentity(null));
        assertTrue(background.isEmpty());
    }
}
