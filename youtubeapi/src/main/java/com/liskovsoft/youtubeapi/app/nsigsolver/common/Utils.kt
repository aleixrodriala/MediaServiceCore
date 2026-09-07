package com.liskovsoft.youtubeapi.app.nsigsolver.common

import android.util.AtomicFile
import com.eclipsesource.v8.V8
import com.liskovsoft.sharedutils.helpers.FileHelpers
import com.liskovsoft.youtubeapi.app.AppService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal class ScriptLoaderError(message: String, cause: Exception? = null): Exception(message, cause)

private const val MAX_CACHE_ENTRY_BYTES = 16 * 1024 * 1024

internal fun loadScript(filename: String, errorMsg: String? = null): String {
    val context = try {
        AppService.instance().context
    } catch (e: Exception) {
        throw ScriptLoaderError(formatError(errorMsg, "Context isn't available"), e)
    }

    try {
        return context.assets.open(filename).bufferedReader()
            .use { it.readText() }
    } catch (e: Exception) {
        throw ScriptLoaderError(formatError(errorMsg, "Error reading file: $filename"), e)
    }
}

internal fun loadScript(filenames: List<String>, errorMsg: String? = null): String {
    return buildString {
        for (filename in filenames) {
            append(loadScript(filename, errorMsg))
        }
    }
}

internal fun loadFromCache(fileName: String?): String? {
    if (fileName == null || fileName.length > 50)
        return null

    val cache = FileHelpers.getCacheDir(AppService.instance().context)

    return try {
        AtomicFile(File(cache, fileName)).openRead().use { stream ->
            if (stream.channel.size() > MAX_CACHE_ENTRY_BYTES) return null
            stream.bufferedReader(Charsets.UTF_8).readText()
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }
}

internal fun persistToCache(fileName: String, content: String) {
    if (fileName.length > 50)
        return

    val cache = FileHelpers.getCacheDir(AppService.instance().context)

    if (content.length > MAX_CACHE_ENTRY_BYTES) return
    val bytes = content.toByteArray(Charsets.UTF_8)
    if (bytes.size > MAX_CACHE_ENTRY_BYTES) return

    val file = AtomicFile(File(cache, fileName))
    var stream: FileOutputStream? = null
    try {
        stream = file.startWrite()
        stream.write(bytes)
        file.finishWrite(stream)
    } catch (e: IOException) {
        file.failWrite(stream)
    } catch (e: SecurityException) {
        file.failWrite(stream)
    }
}

internal fun clearCacheFile(fileName: String) {
    if (fileName.length > 50) return
    val cache = FileHelpers.getCacheDir(AppService.instance().context)
    try {
        // Delete only this entry and AtomicFile's own recovery files, never the section directory.
        AtomicFile(File(cache, fileName)).delete()
    } catch (e: SecurityException) {
        // Cache maintenance is optional; a failed clear must not become a playback failure.
    }
}

internal fun formatError(firstMsg: String?, secondMsg: String) = firstMsg?.let { "$it: $secondMsg" } ?: secondMsg

internal inline fun <T> V8.withLock(block: (V8) -> T): T {
    locker.acquire()
    try {
        return block(this)
    } finally {
        locker.release()
    }
}