package com.liskovsoft.youtubeapi.app.nsigsolver.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.youtubeapi.app.AppService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.ConscryptMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Synthetic local-cache fixtures only: no player JS, HTTP, token/session data or native runtime. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, application = Application.class,
        shadows = {CacheServiceIntegrityTest.ShadowAppService.class,
                CacheServiceIntegrityTest.ShadowCacheWrites.class,
                CacheServiceIntegrityTest.ShadowAtomicWrites.class})
@ConscryptMode(ConscryptMode.Mode.OFF)
public class CacheServiceIntegrityTest {
    private static final String SECTION = "offline-integrity";
    private static final String FIRST_KEY = "player:https://example.invalid/player-a.js";
    private static final String SECOND_KEY = "player:https://example.invalid/player-b.js";
    private static final CachedData FIRST = new CachedData("synthetic-code-a", "1", "first");
    private static final CachedData SECOND = new CachedData("synthetic-code-b", "2", "second");

    @Before
    public void setUp() {
        ShadowAppService.service = ReflectionHelpers.callConstructor(AppService.class);
        ShadowCacheWrites.failWrites = false;
        ShadowAtomicWrites.failStart = false;
        ShadowAtomicWrites.failAfterPrefix = false;
        ShadowAtomicWrites.startCalls = 0;
        ShadowAtomicWrites.injectedFailures = 0;
        ShadowAtomicWrites.rollbackCalls = 0;
    }

    @Test
    public void failedReplacementCannotPairNewKeyWithOldContent() {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        ShadowCacheWrites.failWrites = true;

        CacheService.INSTANCE.store(SECTION, SECOND_KEY, SECOND);

        assertNull("failed publication must not label old content with the new key",
                CacheService.INSTANCE.load(SECTION, SECOND_KEY));
    }

    @Test
    public void failedReplacementKeepsPreviousValidEntry() {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        ShadowCacheWrites.failWrites = true;

        CacheService.INSTANCE.store(SECTION, SECOND_KEY, SECOND);

        assertEquals(FIRST, CacheService.INSTANCE.load(SECTION, FIRST_KEY));
    }

    @Test
    public void actualAtomicStartFailureIsNonfatalAndPreservesPreviousEntry() {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        ShadowAtomicWrites.failStart = true;

        CacheService.INSTANCE.store(SECTION, SECOND_KEY, SECOND);

        assertEquals(1, ShadowAtomicWrites.injectedFailures);
        assertEquals("the real utility IOException handler must invoke rollback", 1,
                ShadowAtomicWrites.rollbackCalls);
        assertEquals(FIRST, CacheService.INSTANCE.load(SECTION, FIRST_KEY));
        assertNull(CacheService.INSTANCE.load(SECTION, SECOND_KEY));
    }

    @Test
    public void actualPartialWriteFailureRollsBackBytesAndMetadataTogether() {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        ShadowAtomicWrites.failAfterPrefix = true;

        CacheService.INSTANCE.store(SECTION, SECOND_KEY, SECOND);

        assertEquals(1, ShadowAtomicWrites.injectedFailures);
        assertEquals("the real utility must failWrite after the stream throws", 1,
                ShadowAtomicWrites.rollbackCalls);
        assertEquals(FIRST, CacheService.INSTANCE.load(SECTION, FIRST_KEY));
        assertNull(CacheService.INSTANCE.load(SECTION, SECOND_KEY));
        assertFalse(new File(entryFile().getPath() + ".bak").exists());
    }

    @Test
    public void oversizedWriteDoesNotStartAtomicReplacement() {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        int startsBefore = ShadowAtomicWrites.startCalls;

        UtilsKt.persistToCache(SECTION + "/entry-v3.json", "x".repeat(16 * 1024 * 1024 + 1));

        assertEquals(startsBefore, ShadowAtomicWrites.startCalls);
        assertEquals(FIRST, CacheService.INSTANCE.load(SECTION, FIRST_KEY));
    }

    @Test
    public void oversizedReadIsRejectedBeforeParsing() throws IOException {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        try (RandomAccessFile file = new RandomAccessFile(entryFile(), "rw")) {
            file.setLength(16 * 1024 * 1024 + 1);
        }

        assertNull(UtilsKt.loadFromCache(SECTION + "/entry-v3.json"));
        assertNull(CacheService.INSTANCE.load(SECTION, FIRST_KEY));
    }

    @Test
    public void sameKeyFailedUpdateKeepsCodeAndMetadataTogether() {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        ShadowCacheWrites.failWrites = true;

        CacheService.INSTANCE.store(SECTION, FIRST_KEY, SECOND);

        assertEquals("version and variant must describe the code that was actually committed",
                FIRST, CacheService.INSTANCE.load(SECTION, FIRST_KEY));
    }

    @Test
    public void successfulReplacementKeepsKeyAndContentCoherent() {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        CacheService.INSTANCE.store(SECTION, SECOND_KEY, SECOND);

        assertNull(CacheService.INSTANCE.load(SECTION, FIRST_KEY));
        assertEquals(SECOND, CacheService.INSTANCE.load(SECTION, SECOND_KEY));
    }

    @Test
    public void independentSectionsDoNotEvictEachOther() {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        CacheService.INSTANCE.store("offline-other", SECOND_KEY, SECOND);

        assertEquals(FIRST, CacheService.INSTANCE.load(SECTION, FIRST_KEY));
        assertEquals(SECOND, CacheService.INSTANCE.load("offline-other", SECOND_KEY));
    }

    @Test
    public void interruptedWriteRecoversPreviousCompleteEnvelope() throws IOException {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        AtomicFile file = new AtomicFile(entryFile());

        // Simulate a process stopping between startWrite and finishWrite, using real AtomicFile.
        try (FileOutputStream stream = file.startWrite()) {
            stream.write("{partial".getBytes(StandardCharsets.UTF_8));
        }

        assertEquals(FIRST, CacheService.INSTANCE.load(SECTION, FIRST_KEY));
        assertNull(CacheService.INSTANCE.load(SECTION, SECOND_KEY));
    }

    @Test
    public void malformedOrTruncatedEnvelopeIsOnlyACacheMiss() throws IOException {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        for (String broken : new String[] {
                "{", "[]", "null", "{\"schema\":2}",
                "{\"schema\":1,\"key\":\"" + FIRST_KEY + "\",\"code\":3}",
                "{\"schema\":1,\"key\":\"" + FIRST_KEY
                        + "\",\"code\":\"code\",\"version\":[]}"}) {
            Files.writeString(entryFile().toPath(), broken, StandardCharsets.UTF_8);

            assertNull(CacheService.INSTANCE.load(SECTION, FIRST_KEY));
        }
    }

    @Test
    public void legacySplitMetadataIsIgnoredWithoutErasingIt() throws IOException {
        File legacy = legacyFile();
        Files.createDirectories(legacy.getParentFile().toPath());
        Files.writeString(legacy.toPath(), FIRST.getCode(), StandardCharsets.UTF_8);
        SharedPreferences preferences = legacyPreferences();
        preferences.edit().putString(SECOND_KEY + "%KEY%code", SECTION + "/player")
                .putString(SECOND_KEY + "%KEY%version", SECOND.getVersion()).commit();

        assertNull(CacheService.INSTANCE.load(SECTION, SECOND_KEY));
        assertEquals(FIRST.getCode(), Files.readString(legacy.toPath(), StandardCharsets.UTF_8));
        assertEquals(SECTION + "/player", preferences.getString(SECOND_KEY + "%KEY%code", null));
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        assertEquals(FIRST, CacheService.INSTANCE.load(SECTION, FIRST_KEY));
        assertTrue("writing the new format must not sweep legacy files", legacy.exists());
        assertTrue(preferences.contains(SECOND_KEY + "%KEY%code"));
    }

    @Test
    public void nullableMetadataAndUnicodeRoundTripWithoutInventedDefaults() {
        CachedData content = new CachedData("synthetic-\"quoted\"-\n-\u00e9-\u2603", null, null);

        CacheService.INSTANCE.store(SECTION, FIRST_KEY, content);

        assertEquals(content, CacheService.INSTANCE.load(SECTION, FIRST_KEY));
    }

    @Test
    public void repeatedKeysKeepOnlyOneNewEnvelopePerSection() {
        for (int index = 0; index < 25; index++) {
            CacheService.INSTANCE.store(SECTION, "player:synthetic-" + index, FIRST);
        }

        assertEquals(FIRST, CacheService.INSTANCE.load(SECTION, "player:synthetic-24"));
        assertNull(CacheService.INSTANCE.load(SECTION, "player:synthetic-0"));
        assertEquals("successful atomic publication must not accumulate per-key files", 1,
                entryFile().getParentFile().list().length);
    }

    @Test
    public void clearRemovesOnlyOwnEnvelopeAndRecoveryFiles() throws IOException {
        CacheService.INSTANCE.store(SECTION, FIRST_KEY, FIRST);
        CacheService.INSTANCE.store("offline-other", SECOND_KEY, SECOND);
        Files.writeString(legacyFile().toPath(), "legacy-unrelated", StandardCharsets.UTF_8);
        legacyPreferences().edit().putString("unrelated", "preserve").commit();
        try (FileOutputStream stream = new AtomicFile(entryFile()).startWrite()) {
            stream.write("partial".getBytes(StandardCharsets.UTF_8));
        }

        CacheService.INSTANCE.clear(SECTION);

        assertNull(CacheService.INSTANCE.load(SECTION, FIRST_KEY));
        assertFalse(entryFile().exists());
        assertFalse(new File(entryFile().getPath() + ".bak").exists());
        assertFalse(new File(entryFile().getPath() + ".new").exists());
        assertEquals(SECOND, CacheService.INSTANCE.load("offline-other", SECOND_KEY));
        assertEquals("legacy-unrelated", Files.readString(legacyFile().toPath(), StandardCharsets.UTF_8));
        assertEquals("preserve", legacyPreferences().getString("unrelated", null));
    }

    private File entryFile() {
        return new File(FileHelpers.getCacheDir(RuntimeEnvironment.getApplication()),
                SECTION + "/entry-v3.json");
    }

    private File legacyFile() {
        return new File(entryFile().getParentFile(), "player");
    }

    private SharedPreferences legacyPreferences() {
        return RuntimeEnvironment.getApplication().getSharedPreferences(
                "yt_cache_service2%KEY%" + SECTION, Context.MODE_PRIVATE);
    }

    @Implements(AppService.class)
    public static class ShadowAppService {
        static AppService service;

        @Implementation
        protected void __constructor__() {
            // No Retrofit, preferences outside these synthetic fixtures, or player extraction.
        }

        @Implementation
        protected static AppService instance() {
            return service;
        }

        @Implementation
        protected Context getContext() {
            return RuntimeEnvironment.getApplication();
        }
    }

    @Implements(UtilsKt.class)
    public static class ShadowCacheWrites {
        static boolean failWrites;

        @Implementation
        protected static void persistToCache(String fileName, String content) {
            if (failWrites) {
                // Reproduce FileHelpers' existing silent I/O failure before bytes are replaced.
                return;
            }
            Shadow.directlyOn(UtilsKt.class, "persistToCache",
                    ReflectionHelpers.ClassParameter.from(String.class, fileName),
                    ReflectionHelpers.ClassParameter.from(String.class, content));
        }
    }

    @Implements(AtomicFile.class)
    public static class ShadowAtomicWrites {
        @RealObject private AtomicFile file;
        static boolean failStart;
        static boolean failAfterPrefix;
        static int startCalls;
        static int injectedFailures;
        static int rollbackCalls;

        @Implementation
        protected FileOutputStream startWrite() throws IOException {
            startCalls++;
            if (failStart) {
                injectedFailures++;
                throw new IOException("synthetic startWrite failure");
            }
            FileOutputStream stream = Shadow.directlyOn(file, AtomicFile.class, "startWrite");
            return failAfterPrefix ? new PartialFailureStream(stream) : stream;
        }

        @Implementation
        protected void failWrite(FileOutputStream stream) {
            rollbackCalls++;
            Shadow.directlyOn(file, AtomicFile.class, "failWrite",
                    ReflectionHelpers.ClassParameter.from(FileOutputStream.class, stream));
        }
    }

    private static final class PartialFailureStream extends FileOutputStream {
        private final FileOutputStream delegate;

        PartialFailureStream(FileOutputStream delegate) throws IOException {
            super(delegate.getFD());
            this.delegate = delegate;
        }

        @Override
        public void write(byte[] bytes) throws IOException {
            delegate.write(bytes, 0, Math.min(bytes.length, 8));
            ShadowAtomicWrites.injectedFailures++;
            throw new IOException("synthetic failure after partial write");
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                super.close();
            }
        }
    }
}
