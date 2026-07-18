package com.liskovsoft.googlecommon.common.helpers

import com.google.net.cronet.okhttptransport.CronetInterceptor
import com.liskovsoft.sharedutils.cronet.CronetManager
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.youtubeapi.common.helpers.AppConstants
import com.liskovsoft.youtubeapi.app.AppService
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

internal object RetrofitOkHttpHelper {
    // Open-path stall bound: /player + /next gate every video open (and each failover-ring step),
    // so a stalled host must fail in 8s instead of the shared 20s OkHttp defaults.
    private const val OPEN_PATH_TIMEOUT_MS = 8_000
    private const val PLAYER_PATH = "/youtubei/v1/player"
    private const val NEXT_PATH = "/youtubei/v1/next"
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"
    private val authSkipList = mutableListOf<Request>()
    private val playerRequestIds = AtomicLong()
    private val nextRequestIds = AtomicLong()

    @JvmStatic
    val authHeaders = mutableMapOf<String, String>()

    @JvmStatic
    val client: OkHttpClient by lazy { createClient() }

    @JvmStatic
    var disableCompression: Boolean = false

    @JvmStatic
    fun addAuthSkip(request: Request) {
        if (!authSkipList.contains(request))
            authSkipList.add(request)
    }

    private val commonHeaders = mapOf(
        // Enable compression in production
        "Accept-Encoding" to DefaultHeaders.ACCEPT_ENCODING,
    )

    private val apiHeaders = mapOf(
        "User-Agent" to DefaultHeaders.APP_USER_AGENT,
    )

    private val apiPrefixes = arrayOf(
        "https://www.googleapis.com/upload/drive/v3",
        "https://www.googleapis.com/drive/v3",
        "https://m.youtube.com/youtubei/v1/",
        "https://www.youtube.com/youtubei/v1/",
        "https://youtubei.googleapis.com/youtubei/v1",
        "https://www.youtube.com/api/stats/",
        "https://clients1.google.com/complete/"
    )

    /**
     * NOTE: visitor header could broke many apis. E.g. VisitorService
     */
    private val visitorApiSuffixes = arrayOf(
        "/youtubei/v1/browse",
        "/youtubei/v1/search",
        "/youtubei/v1/player",
        "/youtubei/v1/reel/",
        "/youtubei/v1/next",
        "/api/stats/",
    )

    private val tParamSuffixes = listOf("/browse", "/next", "/reel", "/playlist")

    private fun createClient(): OkHttpClient {
        val builder = OkHttpManager.instance().client.newBuilder()
        addCommonHeaders(builder)
        addOpenPathTimeout(builder)
        //addCronetInterceptor(builder)
        return builder.build()
    }

    /**
     * Per-request timeout bound for the video-open critical path. Every youtubeapi Retrofit
     * instance rides this one client (auth and non-auth requests alike - auth is applied per
     * request in [addCommonHeaders]), so tightening connect/read here covers both. Write timeout
     * and every other endpoint keep the shared OkHttpCommons defaults untouched.
     */
    private fun addOpenPathTimeout(builder: OkHttpClient.Builder) {
        builder.addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            if (path.contains("/youtubei/v1/player") || path.contains("/youtubei/v1/next")) {
                chain.withConnectTimeout(OPEN_PATH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .withReadTimeout(OPEN_PATH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .proceed(chain.request())
            } else {
                chain.proceed(chain.request())
            }
        }
    }

    private fun addCommonHeaders(builder: OkHttpClient.Builder) {
        builder.addInterceptor { chain ->
            val request = chain.request()
            val headers = request.headers
            val requestBuilder = request.newBuilder()

            applyHeaders(this.commonHeaders, headers, requestBuilder)

            val url = request.url.toString()

            if (apiPrefixes.any { url.startsWith(it) }) {
                val doSkipAuth = authSkipList.remove(request)
                val isPlayer = isPlayerEndpoint(request)

                // Empty Home fix (anonymous user) and improve Recommendations for everyone
                if (visitorApiSuffixes.any { url.contains(it) })
                    headers["X-Goog-Visitor-Id"] ?: AppService.instance().visitorData?.let { requestBuilder.header("X-Goog-Visitor-Id", it) }

                applyHeaders(this.apiHeaders, headers, requestBuilder)
                // A native /player identity must not inherit the global TV referer. yt-dlp sends
                // Origin plus the selected client's UA/name/version and no Referer for this call.
                // Endpoint annotations provide Origin explicitly; every other API keeps the
                // historical default referer unless it declared a client-specific one.
                if (!isPlayer) {
                    applyHeaders(mapOf("Referer" to DefaultHeaders.REFERER), headers, requestBuilder)
                }

                val tParam = if (tParamSuffixes.any { url.contains(it) }) YouTubeHelper.generateTParameter() else null

                if (authHeaders.isEmpty() || doSkipAuth) {
                    // Match yt-dlp's anonymous player call: it is keyless. Authenticated TV calls
                    // retain their existing key/auth behavior and all non-player APIs are unchanged.
                    val apiKey = if (isPlayer) null else AppConstants.API_KEY
                    applyQueryKeys(mapOf("key" to apiKey, "prettyPrint" to "false", "t" to tParam),
                        request, requestBuilder)
                } else {
                    applyQueryKeys(mapOf("prettyPrint" to "false", "t" to tParam), request, requestBuilder)
                    applyHeaders(authHeaders, headers, requestBuilder)
                }
            }

            val finalRequest = requestBuilder.build()
            val response = when {
                isPlayerEndpoint(finalRequest) -> proceedLoggedPlayerRequest(chain, finalRequest)
                isNextEndpoint(finalRequest) -> proceedLoggedNextRequest(chain, finalRequest)
                else -> chain.proceed(finalRequest)
            }
            logBrotliOnce(response)
            retryOnceIfAuthRejected(chain, finalRequest, response)
        }
    }

    @Volatile
    private var brotliSeen = false

    /**
     * One line per process confirming the server actually negotiated br after the phone
     * gate flips it on (this interceptor sees the raw Content-Encoding — UnzippingInterceptor
     * sits earlier in the chain and strips it on the way out).
     */
    private fun logBrotliOnce(response: okhttp3.Response) {
        if (!brotliSeen && response.header("Content-Encoding") == "br") {
            brotliSeen = true
            android.util.Log.d("NetPath", "brotli active: first br response ${response.request.url.encodedPath}")
        }
    }

    /**
     * A 401 on an authed API call means the access token died before its 60-min window
     * (server-side revoke — possible now that the header is persisted across process starts,
     * see YouTubeSignInService). Mint a fresh header and replay the request ONCE with it.
     * Unauthed/skip-auth requests pass through untouched.
     */
    private fun retryOnceIfAuthRejected(
        chain: okhttp3.Interceptor.Chain,
        request: Request,
        response: okhttp3.Response,
    ): okhttp3.Response {
        if (response.code != 401) return response
        val rejected = request.header("Authorization") ?: return response

        val refreshed = try {
            com.liskovsoft.youtubeapi.service.YouTubeSignInService.instance().refreshRejectedAuthHeader(rejected)
        } catch (e: Exception) {
            false
        }
        val newAuth = authHeaders["Authorization"]
        if (!refreshed || newAuth == null || newAuth == rejected) return response

        android.util.Log.d("NetPath", "auth-retry: 401 with stale header, replaying with fresh token")
        response.close()
        val retryBuilder = request.newBuilder()
        for ((name, value) in authHeaders) retryBuilder.header(name, value)
        return chain.proceed(retryBuilder.build())
    }

    internal fun isPlayerEndpoint(request: Request): Boolean =
        request.url.encodedPath.endsWith(PLAYER_PATH)

    internal fun isNextEndpoint(request: Request): Boolean =
        request.url.encodedPath.endsWith(NEXT_PATH)

    /** Safe /player request/response fingerprint: useful in field logs, contains no credentials. */
    private fun proceedLoggedPlayerRequest(
        chain: okhttp3.Interceptor.Chain,
        request: Request,
    ): okhttp3.Response {
        val id = playerRequestIds.incrementAndGet()
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val body = inspectPlayerBody(request)
        val visitor = request.header("X-Goog-Visitor-Id")
        val origin = request.header("Origin")
        val referer = request.header("Referer")
        android.util.Log.d(
            "NetPath",
            "player-http[S] rid=$id video=${body.videoId} client=${request.header("X-Youtube-Client-Name")}" +
                " cver=${request.header("X-Youtube-Client-Version")}" +
                " visitor=${fingerprint(visitor)} pot=${yn(body.hasPoToken)}" +
                " auth=${yn(request.header("Authorization") != null)}" +
                " cookie=${yn(request.header("Cookie") != null)}" +
                " authUser=${yn(request.header("X-Goog-AuthUser") != null)}" +
                " contentOk=${yn(body.contentCheckOk)} racyOk=${yn(body.racyCheckOk)}" +
                " sts=${yn(body.hasSignatureTimestamp)} ua=${fingerprint(request.header("User-Agent"))}" +
                " origin=${if (origin == YOUTUBE_ORIGIN) "youtube" else if (origin == null) "none" else "other"}" +
                " referer=${if (referer == null) "none" else "present"}" +
                " key=${yn(request.url.queryParameter("key") != null)}",
        )

        try {
            val response = chain.proceed(request)
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedMs
            android.util.Log.d(
                "NetPath",
                "player-http[C] rid=$id video=${body.videoId} code=${response.code}" +
                    " ms=$elapsed ${responseSummary(response)}",
            )
            if (!response.isSuccessful) {
                val errorText = try {
                    response.peekBody(512).string()
                } catch (_: Exception) {
                    ""
                }
                android.util.Log.w(
                    "NetPath",
                    "player-http[E] rid=$id code=${response.code} bodyHash=${fingerprint(errorText)}" +
                        " body=${printable(errorText, 240)}",
                )
            }
            return response
        } catch (error: IOException) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedMs
            android.util.Log.w(
                "NetPath",
                "player-http[E] rid=$id video=${body.videoId} ms=$elapsed " +
                    "${error.javaClass.simpleName}: ${printable(error.message ?: "", 160)}",
            )
            throw error
        }
    }

    /** Safe /next timing shows whether an apparent playback loop is fetching suggestions. */
    private fun proceedLoggedNextRequest(
        chain: okhttp3.Interceptor.Chain,
        request: Request,
    ): okhttp3.Response {
        val id = nextRequestIds.incrementAndGet()
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val body = inspectPlayerBody(request)
        android.util.Log.d(
            "NetPath",
            "next-http[S] nid=$id video=${body.videoId}" +
                " client=${request.header("X-Youtube-Client-Name")}" +
                " cver=${request.header("X-Youtube-Client-Version")}" +
                " visitor=${fingerprint(request.header("X-Goog-Visitor-Id"))}" +
                " auth=${yn(request.header("Authorization") != null)}" +
                " cookie=${yn(request.header("Cookie") != null)}",
        )
        try {
            val response = chain.proceed(request)
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedMs
            android.util.Log.d(
                "NetPath",
                "next-http[C] nid=$id video=${body.videoId} code=${response.code}" +
                    " ms=$elapsed ${responseSummary(response)}",
            )
            return response
        } catch (error: IOException) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - startedMs
            android.util.Log.w(
                "NetPath",
                "next-http[E] nid=$id video=${body.videoId} ms=$elapsed " +
                    "${error.javaClass.simpleName}: ${printable(error.message ?: "", 160)}",
            )
            throw error
        }
    }

    private fun responseSummary(response: okhttp3.Response): String {
        var redirects = 0
        var prior = response.priorResponse
        while (prior != null) {
            redirects++
            prior = prior.priorResponse
        }
        val contentType = response.header("Content-Type")?.substringBefore(';') ?: "none"
        val contentLength = response.header("Content-Length") ?: "unknown"
        val encoding = response.header("Content-Encoding") ?: "identity"
        val tls = response.handshake?.tlsVersion?.javaName ?: "none"
        return "protocol=${response.protocol} tls=$tls host=${response.request.url.host}" +
            " redirects=$redirects ctype=$contentType clen=$contentLength encoding=$encoding"
    }

    private data class PlayerBodyInfo(
        val videoId: String,
        val hasPoToken: Boolean,
        val contentCheckOk: Boolean,
        val racyCheckOk: Boolean,
        val hasSignatureTimestamp: Boolean,
    )

    private fun inspectPlayerBody(request: Request): PlayerBodyInfo {
        return try {
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val body = buffer.readUtf8()
            val videoId = Regex("\\\"videoId\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(body)?.groupValues?.getOrNull(1) ?: "?"
            PlayerBodyInfo(
                videoId,
                Regex("\\\"poToken\\\"\\s*:").containsMatchIn(body),
                Regex("\\\"contentCheckOk\\\"\\s*:\\s*true").containsMatchIn(body),
                Regex("\\\"racyCheckOk\\\"\\s*:\\s*true").containsMatchIn(body),
                Regex("\\\"signatureTimestamp\\\"\\s*:").containsMatchIn(body),
            )
        } catch (_: Exception) {
            PlayerBodyInfo("?", false, false, false, false)
        }
    }

    private fun fingerprint(value: String?): String {
        if (value.isNullOrEmpty()) {
            return "none"
        }
        return try {
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .take(5).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            Integer.toHexString(value.hashCode())
        }
    }

    private fun printable(value: String, max: Int): String {
        val safe = value.replace(Regex("[^\\x20-\\x7E]"), ".")
        return if (safe.length <= max) safe else safe.substring(0, max) + "..."
    }

    private fun yn(value: Boolean) = if (value) "y" else "n"

    private fun applyHeaders(newHeaders: Map<String, String?>, oldHeaders: Headers, builder: Request.Builder) {
        for (header in newHeaders) {
            if (header.key == "Accept-Encoding") {
                if (disableCompression) {
                    continue
                }
                // Resolved at request time so the phone flavor's brotli gate (set after this
                // object's static init) takes effect — commonHeaders itself is built once.
                oldHeaders[header.key] ?: builder.header(header.key, DefaultHeaders.acceptEncoding())
                continue
            }

            // Don't override existing headers
            oldHeaders[header.key] ?: header.value?.let { builder.header(header.key, it) } // NOTE: don't remove null check
        }
    }

    private fun applyQueryKeys(keys: Map<String, String?>, request: Request, builder: Request.Builder) {
        val originUrl = request.url

        var newUrlBuilder: HttpUrl.Builder? = null

        for (entry in keys) {
            // Don't override existing keys
            originUrl.queryParameter(entry.key) ?: run {
                if (entry.value == null)
                    return@run

                if (newUrlBuilder == null) {
                    newUrlBuilder = originUrl.newBuilder()
                }

                newUrlBuilder?.addQueryParameter(entry.key, entry.value)
            }
        }

        newUrlBuilder?.run {
            builder.url(build())
        }
    }

    private fun addCronetInterceptor(builder: OkHttpClient.Builder) {
        val engine = CronetManager.getEngine(AppService.instance().context)
        if (engine != null) {
            builder.addInterceptor(CronetInterceptor.newBuilder(engine).build())
        }
    }
}
