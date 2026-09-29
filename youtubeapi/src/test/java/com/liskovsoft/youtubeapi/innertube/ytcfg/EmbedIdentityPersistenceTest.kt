package com.liskovsoft.youtubeapi.innertube.ytcfg

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * NEWTUBE(embed-persist): the persisted embed identity is handed out once per process, only inside
 * the TTL of its original fetch, and an invalidation removes it for good. Plain JVM, no network.
 */
class EmbedIdentityPersistenceTest {
    private val ttlMs = TimeUnit.HOURS.toMillis(6)
    private val fetchedAtMs = 1_800_000_000_000L

    private class MemoryStore(var snapshot: String? = null) : EmbedIdentityPersistence.Store {
        var loads = 0
        val saves = mutableListOf<String?>()

        override fun load(): String? {
            loads++
            return snapshot
        }

        override fun save(snapshot: String?) {
            saves.add(snapshot)
            this.snapshot = snapshot
        }
    }

    private fun identity(atMs: Long = fetchedAtMs) =
        YtCfgService.EmbedIdentity("host-flags", "visitor%3D%3D", atMs)

    @After
    fun tearDown() {
        EmbedIdentityPersistence.setStore(null)
    }

    @Test
    fun aSavedIdentityRoundTripsIntoTheNextProcess() {
        val store = MemoryStore()
        EmbedIdentityPersistence.setStore(store)
        EmbedIdentityPersistence.save(identity())

        EmbedIdentityPersistence.setStore(store) // a new process, same preferences
        val restored = EmbedIdentityPersistence.restore(fetchedAtMs + ttlMs - 1, ttlMs)

        assertNotNull(restored)
        assertEquals("host-flags", restored!!.encryptedHostFlags)
        assertEquals("visitor%3D%3D", restored.visitorData)
        assertEquals(fetchedAtMs, restored.fetchedAtMs) // the TTL keeps counting from the page fetch
    }

    @Test
    fun theTtlCountsFromTheOriginalFetchNotFromTheRestore() {
        val store = MemoryStore(EmbedIdentityPersistence.encode(identity()))
        EmbedIdentityPersistence.setStore(store)

        assertNull(EmbedIdentityPersistence.restore(fetchedAtMs + ttlMs, ttlMs))
        assertEquals(listOf<String?>(null), store.saves) // the stale copy is dropped from the store
    }

    @Test
    fun aFutureDatedCopyIsNotTrusted() {
        val store = MemoryStore(EmbedIdentityPersistence.encode(identity()))
        EmbedIdentityPersistence.setStore(store)

        assertNull(EmbedIdentityPersistence.restore(fetchedAtMs - 1, ttlMs))
        assertNull(store.snapshot)
    }

    @Test
    fun theCopyIsReadAndHandedOutOncePerProcess() {
        val store = MemoryStore(EmbedIdentityPersistence.encode(identity()))
        EmbedIdentityPersistence.setStore(store)

        assertNotNull(EmbedIdentityPersistence.restore(fetchedAtMs + 1, ttlMs))
        // From here the in-memory identity is authoritative: a later ask (its TTL ran out) fetches.
        assertNull(EmbedIdentityPersistence.restore(fetchedAtMs + 2, ttlMs))
        assertEquals(1, store.loads)
    }

    @Test
    fun anInvalidationClearsThePersistedCopyAndItNeverComesBack() {
        val store = MemoryStore(EmbedIdentityPersistence.encode(identity()))
        EmbedIdentityPersistence.setStore(store)

        EmbedIdentityPersistence.clear() // "Error code: 152" before this process restored anything
        assertNull(store.snapshot)
        assertNull(EmbedIdentityPersistence.restore(fetchedAtMs + 1, ttlMs))
        assertEquals(0, store.loads)

        // The one refetch is persisted again for the next process.
        EmbedIdentityPersistence.save(identity(fetchedAtMs + 5))
        EmbedIdentityPersistence.setStore(store)
        assertEquals(fetchedAtMs + 5, EmbedIdentityPersistence.restore(fetchedAtMs + 6, ttlMs)!!.fetchedAtMs)
    }

    @Test
    fun damagedOrForeignSnapshotsRestoreNothing() {
        for (snapshot in listOf("", "not json", "[]", "{}", """{"v":2,"flags":"f","visitor":"v","fetchedAtMs":1}""",
            """{"v":1,"flags":"","visitor":"v","fetchedAtMs":1}""", """{"v":1,"flags":"f","fetchedAtMs":1}""",
            """{"v":1,"flags":"f","visitor":"v","fetchedAtMs":"soon"}""", """{"v":1,"flags":null,"visitor":"v","fetchedAtMs":1}""")) {
            val store = MemoryStore(snapshot)
            EmbedIdentityPersistence.setStore(store)
            assertNull(snapshot, EmbedIdentityPersistence.restore(2, ttlMs))
            assertTrue(snapshot, store.saves == listOf<String?>(null))
        }
    }

    @Test
    fun withoutAStoreNothingIsPersisted() {
        EmbedIdentityPersistence.setStore(null)
        EmbedIdentityPersistence.save(identity())
        EmbedIdentityPersistence.clear()
        assertNull(EmbedIdentityPersistence.restore(fetchedAtMs + 1, ttlMs))
    }

    @Test
    fun aFailingStoreIsOnlyAMissedOptimisation() {
        EmbedIdentityPersistence.setStore(object : EmbedIdentityPersistence.Store {
            override fun load(): String? = throw IllegalStateException("disk")
            override fun save(snapshot: String?) = throw IllegalStateException("disk")
        })
        EmbedIdentityPersistence.save(identity())
        assertNull(EmbedIdentityPersistence.restore(fetchedAtMs + 1, ttlMs))
        EmbedIdentityPersistence.clear()
    }
}
