package com.liskovsoft.youtubeapi.app.nsigsolver.common

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

internal class CacheError(message: String, cause: Exception? = null): Exception(message, cause)

internal data class CachedData(
    val code: String,
    val version: String? = null,
    val variant: String? = null
)

internal object CacheService {
    private const val ENTRY_SCHEMA = 1
    private const val ENTRY_FILE = "entry-v3.json"

    // AtomicFile handles interrupted writes, not concurrent access. Keep reads, writes and
    // clears under the same monitor so a reader cannot roll back an active writer's backup.
    @Synchronized
    fun load(section: String, key: String): CachedData? {
        return loadFromStorage(section, key)
    }

    @Synchronized
    fun store(section: String, key: String, content: CachedData) {
        persistToStorage(section, key, content)
    }

    @Synchronized
    fun clear(section: String) {
        clearCacheFile(getEntryFile(section))
    }

    private fun loadFromStorage(section: String, key: String): CachedData? {
        // Legacy prefs and the shared player file were published separately, so their association
        // cannot be verified. Leave them untouched, but require a coherent envelope on cache hits.
        val stored = loadFromCache(getEntryFile(section)) ?: return null
        return try {
            val parsed = JsonParser.parseString(stored)
            if (!parsed.isJsonObject) return null
            val entry = parsed.asJsonObject
            val schema = entry.get("schema")
            if (schema == null || !schema.isJsonPrimitive || !schema.asJsonPrimitive.isNumber
                    || schema.asString != ENTRY_SCHEMA.toString() || readString(entry, "key") != key) {
                return null
            }
            val code = readString(entry, "code") ?: return null
            CachedData(code, readString(entry, "version"), readString(entry, "variant"))
        } catch (e: JsonParseException) {
            null // A malformed/truncated cache entry is a miss, never a playback failure.
        }
    }

    private fun persistToStorage(section: String, key: String, content: CachedData) {
        // Retain the existing one-entry-per-section bound, including lib/core/player eviction.
        // The full logical key and all metadata commit with the code, never ahead of it.
        val entry = JsonObject().apply {
            addProperty("schema", ENTRY_SCHEMA)
            addProperty("key", key)
            addProperty("code", content.code)
            addProperty("version", content.version)
            addProperty("variant", content.variant)
        }
        persistToCache(getEntryFile(section), entry.toString())
    }

    private fun readString(entry: JsonObject, field: String): String? {
        val value = entry.get(field) ?: return null
        if (value.isJsonNull) return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw JsonParseException("Invalid cache field type")
        }
        return value.asString
    }

    private fun getEntryFile(section: String): String {
        if (section.isEmpty() || section.length > 32 || section == "." || section == ".."
                || section.contains('/') || section.contains('\\')) {
            throw CacheError("Invalid cache section")
        }
        return "$section/$ENTRY_FILE"
    }
}
