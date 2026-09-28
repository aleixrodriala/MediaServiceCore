package com.liskovsoft.youtubeapi.app.potokennp2.misc

import com.grack.nanojson.JsonParser
import java.util.regex.Pattern

// Upstream PoTokenWebView4 (476357b4) / bgutil PR #243 patterns, compiled once.
private val YTCFG_PATTERN = Pattern.compile("""ytcfg\.set\((\{.+?\})\);""", Pattern.DOTALL)
private val YT_ATN_PATTERN = Pattern.compile("""window\.ytAtN\(\s*(\{[\s\S]*?\})\s*\)""")
// The flag rides serializedExperimentFlags, where the page escapes '=' as \u003d
// (issue5/embed.html, 2026-09-28: "html5_generate_content_po_token\u003dtrue").
private val CONTENT_FLAG_PATTERN =
    Pattern.compile("""html5_generate_content_po_token(?:=|\\u003[dD])(true|false)""")

/**
 * NEWTUBE(pot-wv4): what [extractHomepageChallenge] found in a youtube.com page. Holds page
 * content only (never a token), and only [rawChallenge]/[ytcfg] are passed on to BotGuard; the
 * rest are the secret-free facts the `web-pot-session` NetPath line reports.
 */
internal class HomepageChallenge(
    /** `window.ytAtN({R: ...}).R` as JSON text holding `bgChallenge`; null when unusable, see [failure]. */
    val rawChallenge: String?,
    /** The page's first `ytcfg.set({...})` object as JSON text, or null when the page has none. */
    val ytcfg: String?,
    /** Whether [ytcfg] carries a non-empty `EVENT_ID`, the value BotGuard reads from `yt.config_`. */
    val hasEventId: Boolean,
    /** `html5_generate_content_po_token` in the page: "y", "n", or "?" when the page does not say. */
    val contentFlag: String,
    /** Why [rawChallenge] is null (`no-atn`, `atn-parse`, `no-bgchallenge`, `ytcfg-parse`); null when usable. */
    val failure: String?
)

/**
 * NEWTUBE(pot-wv4): the HTML half of upstream's `getChallengeFromHomepage`, pure so it can be tested
 * without a WebView or a network (doc potoken-port.md §5.1 step 1.10). Never throws.
 *
 * Mirrors bgutil PR #243 (session_manager.ts `getChallengeFromHomepage`):
 * - the FIRST `ytcfg.set({...})` is the page config; no match means no `EVENT_ID`, and the challenge
 *   is still used (bgutil only warns). A match that is not JSON fails the whole page, as bgutil's
 *   `JSON.parse` throw does, so the caller moves on to `/att/get`.
 * - the challenge is `window.ytAtN({...}).R`, and it must mention `bgChallenge`, `program` and
 *   `interpreterUrl`.
 * A consent interstitial or any other page without `ytAtN` yields `failure=no-atn`.
 */
internal fun extractHomepageChallenge(html: String): HomepageChallenge {
    val contentFlag = contentPoTokenFlag(html)
    var ytcfg: String? = null
    var hasEventId = false

    try {
        val ytcfgMatcher = YTCFG_PATTERN.matcher(html)

        if (ytcfgMatcher.find()) {
            val candidate = ytcfgMatcher.group(1)!!
            val config = JsonParser.`object`().from(candidate)
            ytcfg = candidate
            hasEventId = !config.getString("EVENT_ID", null).isNullOrEmpty()
        }
    } catch (e: Exception) {
        // JsonParserException, or a type mismatch on EVENT_ID
        return HomepageChallenge(null, null, false, contentFlag, "ytcfg-parse")
    }

    val attMatcher = YT_ATN_PATTERN.matcher(html)

    if (!attMatcher.find()) {
        return HomepageChallenge(null, ytcfg, hasEventId, contentFlag, "no-atn")
    }

    val rawChallenge = try {
        parseLooseJSON(attMatcher.group(1)!!)["R"]
    } catch (e: Exception) {
        return HomepageChallenge(null, ytcfg, hasEventId, contentFlag, "atn-parse")
    }

    if (rawChallenge == null || !rawChallenge.contains("bgChallenge")
            || !rawChallenge.contains("program")
            || !rawChallenge.contains("interpreterUrl")) {
        return HomepageChallenge(null, ytcfg, hasEventId, contentFlag, "no-bgchallenge")
    }

    return HomepageChallenge(rawChallenge, ytcfg, hasEventId, contentFlag, null)
}

/** NEWTUBE(pot-wv4): a 200 answer and the host that finally gave it (after redirects). */
internal class PageAnswer(val body: String, val host: String)

/** NEWTUBE(pot-wv4): a BotGuard/homepage request answered with something other than 200. */
internal class HttpCodeException(val code: Int) : RuntimeException("Invalid response code: $code")

/**
 * NEWTUBE(pot-wv4): the outcome of [attemptHomepageChallenge]: the challenge ready for
 * `runBotGuard` and the page's ytcfg, or null data and an [info] that says `challenge=att-get` and
 * why (`homepageFail=`).
 */
internal class HomepageAttempt(
    val challengeData: String?,
    val ytcfg: String?,
    val info: PoTokenChallengeInfo
)

/**
 * NEWTUBE(pot-wv4): fix (c), the homepage step of PoTokenWebView4 without its WebView: GET the page
 * ([fetchPage], which throws [HttpCodeException] on a non-200), extract the challenge, then
 * [descramble] it (parseDescrambledChallengeData, which downloads the interpreter). Never throws:
 * any failure returns null data so the caller moves on to `/att/get`, as bgutil's try/catch does.
 * Upstream caught nothing here, so a transient network error (OkHttpManager rethrows every
 * IOException as IllegalStateException), a non-200 or an unparseable page skipped `/att/get` and
 * went straight to the old generator.
 *
 * `homepageFail=` values: `http-<code>`, `page-<Exception>` (the GET), the [HomepageChallenge.failure]
 * of the HTML (`no-atn` for a consent interstitial or any page without the challenge), or
 * `interpreter-<Exception>`.
 */
internal fun attemptHomepageChallenge(
    fetchPage: () -> PageAnswer,
    descramble: (String) -> String
): HomepageAttempt {
    val startedMs = System.nanoTime() / 1_000_000
    var pageHost: String? = null
    var pageMs = -1L
    var contentFlag = "?"
    var step = "page"

    val failure = try {
        val page = fetchPage()
        pageMs = System.nanoTime() / 1_000_000 - startedMs
        pageHost = page.host
        step = "parse"
        val homepage = extractHomepageChallenge(page.body)
        contentFlag = homepage.contentFlag
        val rawChallenge = homepage.rawChallenge

        if (rawChallenge != null) {
            step = "interpreter"
            val challengeData = descramble(rawChallenge)

            return HomepageAttempt(
                challengeData, homepage.ytcfg,
                PoTokenChallengeInfo(
                    PoTokenChallengeInfo.HOMEPAGE, homepage.ytcfg != null, homepage.hasEventId,
                    contentFlag, pageHost, pageMs
                )
            )
        }

        homepage.failure ?: "no-challenge"
    } catch (e: HttpCodeException) {
        "http-${e.code}"
    } catch (e: Exception) {
        "$step-${e.javaClass.simpleName}"
    }

    return HomepageAttempt(
        null, null,
        PoTokenChallengeInfo(
            PoTokenChallengeInfo.ATT_GET, contentFlag = contentFlag, pageHost = pageHost,
            pageMs = pageMs, homepageFailure = failure
        )
    )
}

private fun contentPoTokenFlag(html: String): String {
    val matcher = CONTENT_FLAG_PATTERN.matcher(html)
    return when {
        !matcher.find() -> "?"
        matcher.group(1) == "true" -> "y"
        else -> "n"
    }
}

/**
 * NEWTUBE(pot-wv4): how a generator obtained its BotGuard challenge, as `web-pot-session` NetPath
 * fields. Secret-free by construction: kinds, y/n flags, a host name, durations and a TTL; never a
 * token, a visitor or a page value.
 *
 * @param challenge `homepage` (youtube.com `ytAtN` + `ytcfg`, upstream PoTokenWebView4), `att-get`
 * (`/youtubei/v1/att/get`, no page and so no `EVENT_ID`) or `legacy` (the WAA `Create` call of
 * PoTokenWebView)
 * @param pageHost the host that finally answered the homepage GET, after redirects, so a consent
 * interstitial (`consent.youtube.com`) is visible; null when no page was read
 * @param pageMs the homepage GET, -1 when none
 * @param homepageFailure why the homepage challenge was not used (`no-atn`, `http-429`,
 * `IllegalStateException`...), null when it was
 * @param ttlSecs GenerateIT's estimated TTL, -1 when unknown
 */
internal class PoTokenChallengeInfo(
    val challenge: String,
    val ytcfg: Boolean = false,
    val eventId: Boolean = false,
    val contentFlag: String = "?",
    val pageHost: String? = null,
    val pageMs: Long = -1,
    val homepageFailure: String? = null,
    val ttlSecs: Long = -1
) {
    fun toLogFields(): String = buildString {
        append(" challenge=").append(challenge)
        append(" ytcfg=").append(if (ytcfg) "y" else "n")
        append(" eventId=").append(if (eventId) "y" else "n")
        append(" contentFlag=").append(contentFlag)
        pageHost?.let { append(" pageHost=").append(it) }
        if (pageMs >= 0) append(" pageMs=").append(pageMs)
        homepageFailure?.let { append(" homepageFail=").append(it) }
        if (ttlSecs >= 0) append(" ttl=").append(ttlSecs)
    }

    fun withTtl(ttlSecs: Long) =
        PoTokenChallengeInfo(challenge, ytcfg, eventId, contentFlag, pageHost, pageMs, homepageFailure, ttlSecs)

    companion object {
        const val HOMEPAGE = "homepage"
        const val ATT_GET = "att-get"
        const val LEGACY = "legacy"
    }
}
