package com.liskovsoft.youtubeapi.app.potokennp2.generators

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.annotation.MainThread
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonWriter
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.youtubeapi.app.potokennp2.core.BadWebViewException
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenException
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenGenerator
import com.liskovsoft.youtubeapi.app.potokennp2.core.awaitOrThrow
import com.liskovsoft.youtubeapi.app.potokennp2.core.buildExceptionForJsError
import com.liskovsoft.youtubeapi.app.potokennp2.misc.HttpCodeException
import com.liskovsoft.youtubeapi.app.potokennp2.misc.PageAnswer
import com.liskovsoft.youtubeapi.app.potokennp2.misc.PoTokenChallengeInfo
import com.liskovsoft.youtubeapi.app.potokennp2.misc.attemptHomepageChallenge
import com.liskovsoft.youtubeapi.app.potokennp2.misc.evaluateJavascriptLegacy
import com.liskovsoft.youtubeapi.app.potokennp2.misc.extractHomepageChallenge
import com.liskovsoft.youtubeapi.app.potokennp2.misc.hasThermalServiceBug
import com.liskovsoft.youtubeapi.app.potokennp2.misc.hasUsbServiceBug
import com.liskovsoft.youtubeapi.app.potokennp2.misc.parseDescrambledChallengeData
import com.liskovsoft.youtubeapi.app.potokennp2.misc.parseIntegrityTokenData
import com.liskovsoft.youtubeapi.app.potokennp2.misc.potLibPrefix
import com.liskovsoft.youtubeapi.app.potokennp2.misc.stringToU8
import com.liskovsoft.youtubeapi.app.potokennp2.misc.u8ToBase64
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import io.reactivex.rxjava3.core.SingleEmitter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * V2 version of https://github.com/Brainicism/bgutil-ytdlp-pot-provider generator with my changes that fix request hanging.
 *
 * The changes compared to V1: https://github.com/Brainicism/bgutil-ytdlp-pot-provider/pull/243/changes
 *
 * NEWTUBE(pot-wv4): a path-limited port of upstream 476357b4 (a722df75 ... 3d87d39e), not a
 * cherry-pick: those commits also carry VIDEO_INFO_TYPE_LIST, QueryBuilder and client-constant
 * hunks. Selected by PoTokenGeneratorSwitch; PoTokenWebView stays the default. Local changes are
 * marked NEWTUBE(pot-wv4) and need a manual reconcile on the next upstream edit of this file:
 * - compile: OkHttp 4 accessors, the RxJava 3 import, and doGetRequest/doPostRequest in place of
 *   the 4-argument OkHttpManager.doRequest our SharedModules fork does not have;
 * - (a) the init timeout is checked before the half-built generator is read, and that generator is
 *   closed (795091f7 did the first half for PoTokenWebView);
 * - (b) a mint that does not answer fails with a PoTokenException instead of returning an
 *   unassigned `lateinit`, and a mint error releases its waiter at once;
 * - (c) nothing on the homepage path throws: any failure there moves on to /att/get, as bgutil's
 *   try/catch does; /att/get and GenerateIT failures are PoTokenExceptions, so the provider falls
 *   back to PoTokenWebView instead of reading them as a broken WebView;
 * - (d) the homepage GET sends browser headers and yt-dlp's consent cookie, not the gRPC ones;
 * - (e) [diagnostics] and one NetPath line when the build fails;
 * - /att/get gets strict JSON; BotGuard answers and tokens are logged by length only.
 */
internal class PoTokenWebView4 private constructor(
    context: Context,
    private var onInitDone: () -> Unit
) : PoTokenGenerator {
    private val webView = WebView(context)
    // NEWTUBE(pot-wv4): a null result means "failed, read initError" (see onInitializationErrorCloseAndCancel)
    private val poTokenEmitters = mutableListOf<Pair<String, (String?) -> Unit>>()
    private var expirationMs: Long = -1
    @Volatile
    var initError: Throwable? = null
    // NEWTUBE(pot-wv4): how the challenge was obtained, for the web-pot-session NetPath line, and
    // which step the build is in, for the failure line. Written on the JavaBridge thread.
    @Volatile
    private var challengeInfo: PoTokenChallengeInfo? = null
    @Volatile
    private var stage = STAGE_LOAD
    // NEWTUBE(pot-wv4): close() is reached from the error path, from the provider and from the init
    // timeout. The WebView is destroyed once, and nothing is evaluated on it afterwards.
    @Volatile
    private var closed = false

    //region Initialization
    init {
        val webViewSettings = webView.settings
        //noinspection SetJavaScriptEnabled we want to use JavaScript!
        webViewSettings.javaScriptEnabled = true
        // MOD: fix AbstractMethodError (Android 8/9)
        //if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
        //    WebSettingsCompat.setSafeBrowsingEnabled(webViewSettings, false)
        //}
        setSafeBrowsingEnabled(webViewSettings, false)

        webViewSettings.userAgentString = USER_AGENT
        webViewSettings.blockNetworkLoads = true // the WebView does not need internet access

        // so that we can run async functions and get back the result
        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    // There should not be any uncaught errors while executing the code, because
                    // everything that can fail is guarded by try-catch. Therefore, this likely
                    // indicates that there was a syntax error in the code, i.e. the WebView only
                    // supports a really old version of JS.

                    val message = m.message()
                        .removePrefix("Uncaught (in promise) ")
                    val fmt = "\"$message\", source: ${m.sourceId()} (${m.lineNumber()})"
                    Log.e(TAG, "This WebView implementation is broken: $fmt")

                    // TODO: not needed anymore?
                    //isBroken = true

                    // Next line cause crashes
                    onInitializationErrorCloseAndCancel(BadWebViewException(fmt))
                }
                return super.onConsoleMessage(m)
            }
        }
    }

    private fun setSafeBrowsingEnabled(settings: WebSettings, enabled: Boolean) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            try {
                WebSettingsCompat.setSafeBrowsingEnabled(settings, enabled)
            } catch (e: AbstractMethodError) { // Sometimes happens on Android 8/9
                e.printStackTrace()
                //getAdapter(settings).setSafeBrowsingEnabled(enabled); // try alt approach from WebSettingsCompat
            }
        }
    }

    /**
     * Must be called right after instantiating [PoTokenWebView4] to perform the actual
     * initialization. This will asynchronously go through all the steps needed to load BotGuard,
     * run it, and obtain an `integrityToken`.
     */
    private fun loadHtmlAndObtainBotguard(context: Context) {
        Log.d(TAG, "loadHtmlAndObtainBotguard() called")

        val html = context.assets.open("${potLibPrefix}po_token2.html").bufferedReader()
            .use { it.readText() }

        webView.loadDataWithBaseURL(
            "https://www.youtube.com",
            html.replaceFirst(
                "</script>",
                // calls downloadAndRunBotguard() when the page has finished loading
                "\n$JS_INTERFACE.downloadAndRunBotguard()</script>"
            ),
            "text/html",
            "utf-8",
            null,
        )
    }

    /**
     * Called during initialization by the JavaScript snippet appended to the HTML page content in
     * [loadHtmlAndObtainBotguard] after the WebView content has been loaded.
     */
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        Log.d(TAG, "downloadAndRunBotguard() called")

        // PATCH(unstem 2026-08): always mint from the homepage's
        // (ytcfg, ytAtN) pair — plugin-passed challenges lack their
        // page's ytcfg/EVENT_ID and /att/get tokens are rejected.
        // BotGuard reads yt.config_.EVENT_ID
        // NOTE: with ytcfg pot becomes smaller: 120 chars instead of regular 124
        // NEWTUBE(pot-wv4): (c) getChallengeFromHomepage never throws; an /att/get failure is caught
        // here. Upstream let both escape this @JavascriptInterface method.
        val (parsedChallengeData, ytcfg) = try {
            getChallengeFromHomepage() ?: getLegacyChallengeData()
        } catch (e: Throwable) {
            onInitializationErrorCloseAndCancel(asInitError(e))
            return
        }

        stage = STAGE_BOTGUARD

        // NEWTUBE(pot-wv4): the page config goes into the script once; upstream's
        // `if ($ytcfg) yt = { config_: $ytcfg }` put the whole ytcfg literal in it twice.
        val ytInit = if (ytcfg != null) "yt = { config_: $ytcfg }" else ""

        runOnMainThread {
            if (closed) return@runOnMainThread
            webView.evaluateJavascriptLegacy(
                """try {
                    $ytInit
                    runBotGuard($parsedChallengeData).then(function (result) {
                        webPoSignalOutput = result.webPoSignalOutput
                        if (!webPoSignalOutput.length)
                            $JS_INTERFACE.onJsInitializationError("webPoSignalOutput is empty")
                        else
                            $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                    }, function (error) {
                        $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                    })
                } catch (error) {
                    $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                }""",
                null
            )
        }
    }

    /**
     * ```text
     * PATCH(unstem 2026-08): fetch the YT homepage (through the caller's
     * proxy) and extract a self-consistent (ytcfg, ytAtN challenge) pair.
     * Injects yt.config_ into the BotGuard global object so the snapshot
     * sees EVENT_ID. Returns undefined on any failure (caller falls back).
     * ```
     *
     * NEWTUBE(pot-wv4): (c) returns null on ANY failure, as bgutil's try/catch does, so the chain
     * goes on to /att/get. The steps (page GET, [extractHomepageChallenge], interpreter download)
     * and the failure names are [attemptHomepageChallenge], which is unit-tested without a WebView.
     */
    private fun getChallengeFromHomepage(): Pair<String, String?>? {
        stage = STAGE_HOMEPAGE

        val attempt = attemptHomepageChallenge(
            fetchPage = { fetch(HOMEPAGE_URL, null, HOMEPAGE_HEADERS) },
            // downloads the interpreter from the challenge's interpreterUrl
            descramble = { parseDescrambledChallengeData(it) }
        )

        challengeInfo = attempt.info
        val parsedChallengeData = attempt.challengeData

        if (parsedChallengeData == null) {
            Log.w(TAG, "homepage-challenge: ${attempt.info.homepageFailure}, falling back to /att/get")
            return null
        }

        if (attempt.ytcfg == null) {
            Log.w(TAG, "homepage-challenge: no ytcfg found (EVENT_ID missing)")
        }

        Log.d(TAG, "Using challenge from the homepage (patched)")

        return Pair(parsedChallengeData, attempt.ytcfg)
    }

    /**
     * Using challenge from /att/get (legacy fallback)
     *
     * NEWTUBE(pot-wv4): a strict JSON body (upstream's had unquoted keys; bgutil sends JSON). No page
     * is read, so there is no ytcfg and no EVENT_ID: the challenge BgUtils #44 reports rejected for
     * sessions in YouTube's experiment on WEB/MWEB. Failures throw; see [downloadAndRunBotguard].
     */
    private fun getLegacyChallengeData(): Pair<String, String?> {
        stage = STAGE_ATT_GET
        val client = AppClient.WEB

        val body = JsonWriter.string(
            JsonObject.builder()
                .`object`("context")
                    .`object`("client")
                        .value("clientName", client.clientName)
                        .value("clientVersion", client.clientVersion)
                    .end()
                .end()
                .value("engagementType", "ENGAGEMENT_TYPE_UNBOUND")
                .done()
        )

        val responseBody = fetch(
            "https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false",
            body,
            BOTGUARD_HEADERS + ("Content-Type" to "application/json")
        ).body

        Log.d(TAG, "Using challenge from /att/get (legacy fallback)")

        return Pair(parseDescrambledChallengeData(responseBody), null)
    }

    /**
     * Called during initialization by the JavaScript snippets from either
     * [downloadAndRunBotguard] or [onRunBotguardResult].
     */
    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        Log.e(TAG, error)
        onInitializationErrorCloseAndCancel(buildExceptionForJsError(error))
    }

    /**
     * Called during initialization by the JavaScript snippet from [downloadAndRunBotguard] after
     * obtaining the BotGuard execution output [botguardResponse].
     */
    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        // NEWTUBE(pot-wv4): lengths only, here and below; upstream logs the values
        Log.d(TAG, "botguardResponse: ${botguardResponse.length} chars")
        stage = STAGE_GENERATE_IT

        // NOTE: both urls can be used to get botguard response.
        // The first on produces more reliable token (without 403 error) but may hang upon request.
        // "$BASE_URL/\$rpc/google.internal.waa.v1.Waa/GenerateIT"
        // "https://www.youtube.com/api/jnn/v1/GenerateIT"
        val (integrityToken, expirationTimeInSeconds) = try {
            val responseBody = fetch(
                "https://www.youtube.com/api/jnn/v1/GenerateIT",
                "[ \"${REQUEST_KEY}\", \"$botguardResponse\" ]",
                BOTGUARD_HEADERS
            ).body

            Log.d(TAG, "GenerateIT response: ${responseBody.length} chars")
            parseIntegrityTokenData(responseBody)
        } catch (e: Exception) {
            onInitializationErrorCloseAndCancel(asInitError(e))
            return
        }

        // MOD: backport Instant.now().plusSeconds
        // leave 10 minutes of margin just to be sure
        //expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600)
        expirationMs = System.currentTimeMillis() + ((expirationTimeInSeconds - 600) * 1_000)
        challengeInfo = challengeInfo?.withTtl(expirationTimeInSeconds)
        stage = STAGE_MINTER

        runOnMainThread {
            if (closed) return@runOnMainThread
            // NEWTUBE(pot-wv4): `else` - upstream also reported success after the error
            webView.evaluateJavascriptLegacy(
                """try {
                        getMinter = webPoSignalOutput[0]
                        mintCallback = getMinter($integrityToken)
                        if (typeof mintCallback === 'undefined')
                            $JS_INTERFACE.onJsInitializationError("mintCallback is not defined")
                        else
                            ${JS_INTERFACE}.onJsInitializationDone($expirationTimeInSeconds)
                        webPoSignalOutput = null
                        getMinter = null
                    } catch (error) {
                        ${JS_INTERFACE}.onJsInitializationError(error + "\n" + error.stack)
                    }""",
                null
            )
        }
    }

    @JavascriptInterface
    fun onJsInitializationDone(expirationTimeInSeconds: Long) {
        Log.d(TAG, "initialization finished, expiration=${expirationTimeInSeconds}s")
        stage = STAGE_DONE
        onInitDone()
    }
    //endregion

    //region Obtaining poTokens
    override fun generatePoToken(identifier: String): String {
        // NEWTUBE(pot-wv4): no identifier in the log (a visitor or a video id)
        Log.d(TAG, "generatePoToken() called")

        if (closed) {
            throw initError ?: PoTokenException("$TAG: generator is closed")
        }

        val latch = CountDownLatch(1)
        var pot: String? = null

        addPoTokenEmitter(identifier) {
            pot = it
            latch.countDown()
        }

        val u8Identifier = stringToU8(identifier)

        runOnMainThread {
            if (closed) return@runOnMainThread
            webView.evaluateJavascriptLegacy(
                """try {
                        poTokenU8 = obtainPoToken($u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult("$identifier", poTokenU8String)
                        poTokenU8 = null
                        poTokenU8String = null
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError("$identifier", error + "\n" + error.stack)
                    }""",
                null
            )
        }

        // NEWTUBE(pot-wv4): (b) upstream waited 10 s and then returned the unassigned lateinit
        // (UninitializedPropertyAccessException). Same bound, a PoTokenException, and the emitter
        // is dropped so a late answer has nobody to wake.
        try {
            awaitOrThrow(latch, MINT_TIMEOUT_MS, "$TAG mint")
        } catch (e: PoTokenException) {
            popPoTokenEmitter(identifier)
            throw initError ?: e
        }

        initError?.let { throw it }

        return pot ?: throw PoTokenException("$TAG: mint returned no token")
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] when an error occurs in calling the
     * JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        val msg = "onObtainPoTokenError: error=$error"
        Log.e(TAG, msg)
        onInitializationErrorCloseAndCancel(buildExceptionForJsError(msg))
    }

    /**
     * Called by the JavaScript snippet from [generatePoToken] with the original identifier and the
     * result of the JavaScript `obtainPoToken()` function.
     */
    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        val poToken = u8ToBase64(poTokenU8)

        Log.d(TAG, "Generated poToken: ${poToken.length} chars")
        popPoTokenEmitter(identifier)?.invoke(poToken)
    }

    override fun isExpired(): Boolean {
        // MOD: java.time backport
        //return Instant.now().isAfter(expirationInstant)
        return System.currentTimeMillis() > expirationMs
    }

    override fun diagnostics(): String = challengeInfo?.toLogFields() ?: ""

    //endregion

    //region Handling multiple emitters
    /**
     * Adds the ([identifier], [emitter]) pair to the [poTokenEmitters] list. This makes it so that
     * multiple poToken requests can be generated invparallel, and the results will be notified to
     * the right emitters.
     */
    private fun addPoTokenEmitter(identifier: String, emitter: (String?) -> Unit) {
        synchronized(poTokenEmitters) {
            poTokenEmitters.add(Pair(identifier, emitter))
        }
    }

    /**
     * Extracts and removes from the [poTokenEmitters] list a [SingleEmitter] based on its
     * [identifier]. The emitter is supposed to be used immediately after to either signal a success
     * or an error.
     */
    private fun popPoTokenEmitter(identifier: String): ((String?) -> Unit)? {
        return synchronized(poTokenEmitters) {
            poTokenEmitters.indexOfFirst { it.first == identifier }.takeIf { it >= 0 }?.let {
                poTokenEmitters.removeAt(it).second
            }
        }
    }

    /**
     * Clears [poTokenEmitters] and returns its previous contents. The emitters are supposed to be
     * used immediately after to either signal a success or an error.
     */
    private fun popAllPoTokenEmitters(): List<Pair<String, (String?) -> Unit>> {
        return synchronized(poTokenEmitters) {
            val result = poTokenEmitters.toList()
            poTokenEmitters.clear()
            result
        }
    }
    //endregion

    //region Utils
    /**
     * NEWTUBE(pot-wv4): replaces upstream's makeBotguardServiceRequest. Upstream merged the gRPC
     * headers into every request (so the homepage GET carried `x-goog-api-key`, a Content-Type and
     * both `Accept` and `accept`), called the 4-argument OkHttpManager.doRequest that our SharedModules
     * fork lacks, and on a non-200 closed the generator but still returned the error body to be
     * parsed. Here every non-200 or empty answer throws, and each caller decides what that means.
     */
    private fun fetch(url: String, data: String?, headers: Map<String, String>): PageAnswer {
        val manager = OkHttpManager.instance()
        val response = if (data == null)
            manager.doGetRequest(url, headers)
        else
            manager.doPostRequest(url, headers, data, null)

        return response.use {
            val httpCode = it.code

            if (httpCode != 200) {
                throw HttpCodeException(httpCode)
            }

            val body = it.body?.string()
                ?: throw PoTokenException("Response body is empty. Response code: $httpCode")

            PageAnswer(body, it.request.url.host)
        }
    }

    /**
     * Handles any error happening during initialization, releasing resources and sending the error
     * to [generatorEmitter].
     */
    private fun onInitializationErrorCloseAndCancel(error: Throwable) {
        initError = error
        // NEWTUBE(pot-wv4): wake the waiting mints now; they read initError. Upstream dropped them
        // and each one sat out its timeout.
        popAllPoTokenEmitters().forEach { it.second(null) }
        runOnMainThread {
            // NEWTUBE(pot-wv4): a throwing cleanup (PoTokenProviderImpl already guards an NPE in
            // clearHistory on old WebViews) would crash the main thread and skip onInitDone.
            try {
                close()
            } catch (e: Exception) {
                Log.e(TAG, "close() failed: ${e.javaClass.simpleName}")
            } finally {
                // throw error
                onInitDone()
            }
        }
    }

    /**
     * Releases all [webView] and [disposables] resources.
     */
    @MainThread
    override fun close() {
        if (closed) return
        closed = true

        webView.clearHistory()
        // clears RAM cache and disk cache (globally for all WebViews)
        webView.clearCache(true)

        // ensures that the WebView isn't doing anything when destroying it
        webView.loadUrl("about:blank")

        webView.onPause()
        webView.removeAllViews()
        webView.destroy()
    }
    //endregion

    companion object : PoTokenGenerator.Factory {
        private val TAG = PoTokenWebView4::class.simpleName
        // Public API key used by BotGuard, which has been got by looking at BotGuard requests
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)"
        //private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        //    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"
        private const val BASE_URL = "https://jnn-pa.googleapis.com"
        private const val HOMEPAGE_URL = "https://www.youtube.com"
        private const val INIT_TIMEOUT_MS = 20_000L
        private const val MINT_TIMEOUT_MS = 10_000L

        private const val STAGE_LOAD = "load"
        private const val STAGE_HOMEPAGE = "homepage"
        private const val STAGE_ATT_GET = "att-get"
        private const val STAGE_BOTGUARD = "botguard"
        private const val STAGE_GENERATE_IT = "generate-it"
        private const val STAGE_MINTER = "minter"
        private const val STAGE_DONE = "done"

        // The BotGuard/InnerTube endpoints (GenerateIT, /att/get): upstream's defaults.
        private val BOTGUARD_HEADERS = mapOf(
            // replace the downloader user agent
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json",
            "Content-Type" to "application/json+protobuf",
            "x-goog-api-key" to GOOGLE_API_KEY,
            "x-user-agent" to "grpc-web-javascript/0.1",
        )

        /**
         * NEWTUBE(pot-wv4): (d) the homepage GET as a browser page load: bgutil PR #243's three
         * headers, plus yt-dlp's consent cookie. Without a consent cookie an EU cookieless load may
         * be answered by a consent interstitial with no `ytAtN`, which would silently mean
         * challenge=att-get for the mostly-Spanish users - a hypothesis (potoken-port.md §5.1 step
         * 1.6); `pageHost=`/`homepageFail=` on the NetPath line show it on the first device run. No
         * visitor cookie: bgutil's homepage carries none either (22/24), and the challenge is bound
         * to the page's EVENT_ID, not to the /player visitor [inferred; arm C of §5.5].
         */
        private val HOMEPAGE_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.7",
            "Cookie" to "SOCS=CAI",
        )

        override fun newPoTokenGenerator(context: Context): PoTokenGenerator {
            if (hasThermalServiceBug(context)) {
                throw BadWebViewException("ThermalService isn't available")
            }

            if (hasUsbServiceBug(context)) {
                throw BadWebViewException("Usb service isn't available")
            }

            val latch = CountDownLatch(1)

            // NEWTUBE(pot-wv4): a nullable holder instead of upstream's lateinit, see (a) below
            val potWvRef = AtomicReference<PoTokenWebView4?>()
            var initError: Throwable? = null

            runOnMainThread {
                val potWv = try {
                    PoTokenWebView4(context) { latch.countDown() }
                } catch (e: Throwable) {
                    initError = BadWebViewException("${e::class.simpleName}: ${e.message}")
                    latch.countDown()
                    return@runOnMainThread
                }
                potWvRef.set(potWv)
                // NEWTUBE(pot-wv4): upstream left this outside the try, so an exception here escaped
                // a main-thread runnable (an app crash) instead of failing the build.
                try {
                    potWv.loadHtmlAndObtainBotguard(context)
                } catch (e: Exception) {
                    potWv.onInitializationErrorCloseAndCancel(
                        BadWebViewException("${e::class.simpleName}: ${e.message}"))
                }
            }

            val completed = latch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            initError?.let { throw it }

            val potWv = potWvRef.get()

            // NEWTUBE(pot-wv4): (a) upstream read potWv.initError before checking `completed`, so a
            // timeout before the main thread had built the generator threw
            // UninitializedPropertyAccessException - the bug 795091f7 fixed in PoTokenWebView.
            // A timed-out generator is also closed: its JavaBridge thread may still finish the
            // homepage or BotGuard later and would otherwise keep a live WebView nobody owns. The
            // close is posted behind the construction above, so it always finds the instance.
            if (!completed || potWv == null) {
                val error = PoTokenException("${TAG}: failed to initialize within ${INIT_TIMEOUT_MS / 1_000} s")
                logInitFailure(potWv, error, timedOut = true)
                runOnMainThread {
                    try {
                        potWvRef.get()?.close()
                    } catch (e: Exception) {
                        Log.e(TAG, "close() failed: ${e.javaClass.simpleName}")
                    }
                }
                throw error
            }

            potWv.initError?.let {
                logInitFailure(potWv, it, timedOut = false)
                throw it
            }

            return potWv
        }

        /**
         * NEWTUBE(pot-wv4): one secret-free NetPath line when a build fails, so a fallback to
         * PoTokenWebView (`fallbackFrom=PoTokenWebView4` on the web-pot-session line) can be read
         * against the step that failed and what the homepage returned.
         */
        private fun logInitFailure(potWv: PoTokenWebView4?, error: Throwable, timedOut: Boolean) {
            android.util.Log.w("NetPath", "web-pot-wv4 failed stage=" + (potWv?.stage ?: STAGE_LOAD)
                    + " timeout=" + (if (timedOut) "y" else "n")
                    + " error=" + error.javaClass.simpleName
                    + (potWv?.diagnostics() ?: ""))
        }

        /**
         * NEWTUBE(pot-wv4): the error an init step failed with, as the provider expects it: a
         * PoTokenException (it then falls back to PoTokenWebView), never a raw network exception.
         */
        private fun asInitError(e: Throwable): Throwable =
            if (e is PoTokenException || e is BadWebViewException) e
            else PoTokenException("${e.javaClass.simpleName}: ${e.message}")

        /**
         * Runs [runnable] on the main thread using `Handler(Looper.getMainLooper()).post()`, and
         * if the `post` fails emits an error on [emitterIfPostFails].
         */
        private fun runOnMainThread(
            runnable: Runnable
        ) {
            if (!Handler(Looper.getMainLooper()).post(runnable)) {
                throw PoTokenException("Could not run on main thread")
            }
        }
    }
}
