package com.liskovsoft.youtubeapi.common.helpers

import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.youtubeapi.innertube.utils.CLIENTS
import com.liskovsoft.youtubeapi.innertube.utils.CLIENT_NAME_IDS

private const val JSON_POST_DATA_BASE = "{\"context\":{\"client\":{\"clientName\":\"%s\",\"clientVersion\":\"%s\"," +
        "\"clientScreen\":\"%s\",\"userAgent\":\"%s\",%s\"acceptLanguage\":\"%%s\",\"acceptRegion\":\"%%s\"," +
        "\"utcOffsetMinutes\":\"%%s\",\"visitorData\":\"%%s\"},%%s\"user\":{\"enableSafetyMode\":false,\"lockedSafetyMode\":false}}," +
        "\"racyCheckOk\":true,\"contentCheckOk\":true,%%s}"
// Merge Shorts with Subscriptions: TV_APP_QUALITY_LIMITED_ANIMATION
// Separate Shorts from Subscriptions: TV_APP_QUALITY_FULL_ANIMATION
private const val POST_DATA_BROWSE_TV =
    "\"tvAppInfo\":{\"appQuality\":\"TV_APP_QUALITY_FULL_ANIMATION\",\"zylonLeftNav\":true},\"webpSupport\":false,\"animatedWebpSupport\":true,"
private const val POST_DATA_BROWSE_TV_LEGACY =
    "\"tvAppInfo\":{\"appQuality\":\"TV_APP_QUALITY_LIMITED_ANIMATION\",\"zylonLeftNav\":true},\"webpSupport\":false,\"animatedWebpSupport\":true,"
private const val POST_DATA_IOS_MODEL = "\"deviceModel\":\"%s\",\"osVersion\":\"%s\","
private const val POST_DATA_VISIONOS_DEVICE =
    "\"deviceMake\":\"%s\",\"deviceModel\":\"%s\",\"osName\":\"%s\",\"osVersion\":\"%s\","
private const val POST_DATA_ANDROID_OS = "\"osName\":\"Android\",\"osVersion\":\"%s\","
private const val POST_DATA_ANDROID_SDK = "\"androidSdkVersion\":\"%s\","
private const val POST_DATA_ANDROID_MODEL = "\"deviceModel\":\"%s\",\"deviceMake\":\"%s\","
private const val POST_DATA_BROWSER = "\"browserName\":\"%s\",\"browserVersion\":\"%s\","
private const val CLIENT_SCREEN_WATCH = "WATCH" // won't play 18+ restricted videos
private const val CLIENT_SCREEN_EMBED = "EMBED" // no 18+ restriction but not all video embeddable, and no descriptions

/**
 * https://github.com/gamer191/yt-dlp/blob/3ad3676e585d144c16a2c5945eb6e422fb918d44/yt_dlp/extractor/youtube/_base.py#L41
 */
internal enum class AppClient(
    @JvmField val clientName: String, @JvmField val clientVersion: String, val innerTubeName: String?, val userAgent: String, val referer: String?,
    val clientScreen: String = CLIENT_SCREEN_WATCH, val params: String? = null, val postData: String? = null, val postDataBrowse: String? = null
): MediaItemFormatInfo.ClientInfo {
    // Doesn't support 8AEB2AMB param if X-Goog-Pageid is set!
    TV(CLIENTS.TV.NAME, CLIENTS.TV.VERSION, CLIENT_NAME_IDS[CLIENTS.TV.NAME],
        userAgent = DefaultHeaders.USER_AGENT_COBALT_CURRENT, referer = "https://www.youtube.com/tv", postDataBrowse = POST_DATA_BROWSE_TV),
    TV_LEGACY(TV, postDataBrowse = POST_DATA_BROWSE_TV_LEGACY),
    TV_EMBED(CLIENTS.TV_EMBEDDED.NAME, CLIENTS.TV_EMBEDDED.VERSION, CLIENT_NAME_IDS[CLIENTS.TV_EMBEDDED.NAME],
        userAgent = DefaultHeaders.USER_AGENT_TV, referer = "https://www.youtube.com/tv", clientScreen = CLIENT_SCREEN_EMBED, postDataBrowse = POST_DATA_BROWSE_TV),
    // Can't use authorization
    TV_SIMPLY(CLIENTS.TV_SIMPLY.NAME, CLIENTS.TV_SIMPLY.VERSION, CLIENT_NAME_IDS[CLIENTS.TV_SIMPLY.NAME],
        userAgent = DefaultHeaders.USER_AGENT_TV, referer = "https://www.youtube.com/tv", postDataBrowse = POST_DATA_BROWSE_TV),
    TV_KIDS("TVHTML5_KIDS", "3.20231113.03.00", null, userAgent = DefaultHeaders.USER_AGENT_TV,
        referer = "https://www.youtube.com/tv/kids", postDataBrowse = POST_DATA_BROWSE_TV),
    TV_DOWNGRADED(TV, clientVersion = "5.20260707", userAgent = DefaultHeaders.USER_AGENT_COBALT_DOWNGRADED),
    // 8AEB2AMB - web client premium formats?
    WEB(CLIENTS.WEB.NAME, CLIENTS.WEB.VERSION, CLIENT_NAME_IDS[CLIENTS.WEB.NAME],
        userAgent = DefaultHeaders.USER_AGENT_WEB, referer = "https://www.youtube.com"),
    // Use WEB_EMBEDDED_PLAYER instead of WEB. Some videos have 403 error on WEB.
    WEB_EMBED(CLIENTS.WEB_EMBEDDED.NAME, CLIENTS.WEB_EMBEDDED.VERSION, CLIENT_NAME_IDS[CLIENTS.WEB_EMBEDDED.NAME],
        userAgent = DefaultHeaders.USER_AGENT_WEB, referer = "https://www.youtube.com"),
    // Request contains an invalid argument.
    WEB_CREATOR(CLIENTS.WEB_CREATOR.NAME, CLIENTS.WEB_CREATOR.VERSION, CLIENT_NAME_IDS[CLIENTS.WEB_CREATOR.NAME],
        userAgent = DefaultHeaders.USER_AGENT_WEB, referer = "https://studio.youtube.com"),
    WEB_MUSIC(CLIENTS.YTMUSIC.NAME, CLIENTS.YTMUSIC.VERSION, CLIENT_NAME_IDS[CLIENTS.YTMUSIC.NAME],
        userAgent = DefaultHeaders.USER_AGENT_WEB, referer = "https://music.youtube.com"),
    WEB_SAFARI(CLIENTS.WEB.NAME, CLIENTS.WEB.VERSION, CLIENT_NAME_IDS[CLIENTS.WEB.NAME],
        userAgent = DefaultHeaders.USER_AGENT_SAFARI, referer = "https://www.youtube.com"),
    MWEB(CLIENTS.MWEB.NAME, CLIENTS.MWEB.VERSION, CLIENT_NAME_IDS[CLIENTS.MWEB.NAME],
        userAgent = DefaultHeaders.USER_AGENT_MOBILE_WEB, referer = "https://m.youtube.com"),
    ANDROID(CLIENTS.ANDROID.NAME, CLIENTS.ANDROID.VERSION, CLIENT_NAME_IDS[CLIENTS.ANDROID.NAME],
        userAgent = DefaultHeaders.USER_AGENT_ANDROID, referer = null,
        postData = String.format(POST_DATA_ANDROID_SDK, CLIENTS.ANDROID.SDK_VERSION) + String.format(POST_DATA_ANDROID_OS, CLIENTS.ANDROID.OS_VERSION)),
    ANDROID_SDK_LESS(baseClient = ANDROID, postData = String.format(POST_DATA_ANDROID_OS, CLIENTS.ANDROID.OS_VERSION)),
    ANDROID_REEL(ANDROID),
    ANDROID_VR(CLIENTS.ANDROID_VR.NAME, CLIENTS.ANDROID_VR.VERSION, CLIENT_NAME_IDS[CLIENTS.ANDROID_VR.NAME],
        userAgent = CLIENTS.ANDROID_VR.USER_AGENT!!, referer = null, postData = String.format(POST_DATA_ANDROID_SDK, CLIENTS.ANDROID_VR.SDK_VERSION)
                + String.format(POST_DATA_ANDROID_OS, CLIENTS.ANDROID_VR.OS_VERSION)
                + String.format(POST_DATA_ANDROID_MODEL, CLIENTS.ANDROID_VR.DEVICE_MODEL, CLIENTS.ANDROID_VR.DEVICE_MAKE)),
    IOS(CLIENTS.IOS.NAME, CLIENTS.IOS.VERSION, CLIENT_NAME_IDS[CLIENTS.IOS.NAME],
        userAgent = CLIENTS.IOS.USER_AGENT!!, referer = null, postData = String.format(POST_DATA_IOS_MODEL, CLIENTS.IOS.DEVICE_MODEL, CLIENTS.IOS.OS_VERSION)),
    INITIAL(WEB),
    GEO(WEB),
    // No pot and no cipher, and unchallenged where ANDROID_VR is challenged - see CLIENTS.VISIONOS.
    // Appended at the END of the enum on purpose: the winning client is persisted by ORDINAL
    // (getData().setVideoInfoType), so inserting mid-enum would silently re-point every value
    // saved by an older build.
    VISIONOS(CLIENTS.VISIONOS.NAME, CLIENTS.VISIONOS.VERSION, CLIENT_NAME_IDS[CLIENTS.VISIONOS.NAME],
        userAgent = CLIENTS.VISIONOS.USER_AGENT!!, referer = null,
        postData = String.format(POST_DATA_VISIONOS_DEVICE, CLIENTS.VISIONOS.DEVICE_MAKE,
            CLIENTS.VISIONOS.DEVICE_MODEL, CLIENTS.VISIONOS.OS_NAME, CLIENTS.VISIONOS.OS_VERSION));

    constructor(baseClient: AppClient, clientVersion: String? = null, userAgent: String? = null, postData: String? = null, postDataBrowse: String? = null):
            this(baseClient.clientName, clientVersion ?: baseClient.clientVersion, baseClient.innerTubeName,
        userAgent ?: baseClient.userAgent, baseClient.referer, baseClient.clientScreen, baseClient.params,
        postData ?: baseClient.postData, postDataBrowse ?: baseClient.postDataBrowse)

    override fun getClientName() = clientName
    override fun getClientVersion() = clientVersion
    override fun getOsName() = "Macintosh" // TODO: change later
    override fun getOsVersion() = "10_15_7" // TODO: change later

    fun getRefererUrl(videoId: String?): String? {
        if (videoId == null || referer == null)
            return referer

        return when {
            this == WEB_EMBED -> "$referer/embed/$videoId?html5=1"
            isTVClient -> "$referer/watch#/watch?v=$videoId"
            else -> "$referer/watch?v=$videoId"
        }
    }

    private val browserInfo by lazy { extractBrowserInfo(userAgent) }
    private val postDataBrowser by lazy { if (browserName != null && browserVersion != null) String.format(POST_DATA_BROWSER, browserName, browserVersion) else null }

    val browserName by lazy { browserInfo?.first }
    val browserVersion by lazy { browserInfo?.second }
    val browseTemplate by lazy { String.format(JSON_POST_DATA_BASE, clientName, clientVersion, clientScreen, userAgent,
        (postDataBrowser ?: "") + (postData ?: "") + (postDataBrowse ?: "")) }
    val baseTemplate by lazy { String.format(JSON_POST_DATA_BASE, clientName, clientVersion, clientScreen, userAgent,
        (postDataBrowser ?: "") + (postData ?: "")) }

    val isAuthSupported by lazy { Helpers.equalsAny(this, TV, TV_LEGACY, TV_EMBED, TV_KIDS, TV_DOWNGRADED) } // NOTE: TV_SIMPLY doesn't support auth

    /**
     * Clients allowed to CARRY the account on a /player request. Identical to [isAuthSupported]
     * unless the phone flavor has opted WEB_EMBED in (see [setWebEmbedAuthEnabled]).
     *
     * Deliberately a getter and not a `by lazy` val: the gate is flipped at process start from a
     * debug property, and a lazy val would freeze whichever value was read first. [isAuthSupported]
     * itself is left exactly as it was, so TV - which never calls the setter - keeps the cached
     * lazy path and byte-identical behaviour.
     *
     * NOT interchangeable with [isAuthSupported] at every call site. The TV-family predicate still
     * governs the account-route quarantines, whose arithmetic is sized to the TV head; see the
     * call-site notes in VideoInfoService.
     */
    val isAuthCapable: Boolean
        get() = isAuthSupported || this == webAuthClient()
    /**
     * Clients whose GVS policy carries yt-dlp's `not_required_with_player_token`: a PO token in
     * the /player REQUEST removes the requirement from the media URLs it returns. That list is
     * android, android_vr and ios - but only ANDROID_VR can use OURS. A web-minted token is bound
     * to the web visitorData, and ANDROID_VR is the one non-web client we send that identity with
     * (see VideoInfoApiHelper.usesWebVisitorData); ANDROID and IOS carry the app visitor, so the
     * binding would not match and the token would be rejected.
     */
    val isPlayerPotSupported by lazy { Helpers.equalsAny(this, ANDROID_VR) }
    val isWebPotRequired by lazy { Helpers.equalsAny(this, WEB, MWEB, WEB_EMBED, WEB_SAFARI, INITIAL, GEO) }
    // TODO: remove after implement SABR
    val isPlaybackBroken by lazy { Helpers.equalsAny(this, INITIAL, WEB, WEB_CREATOR, WEB_MUSIC, WEB_SAFARI, ANDROID_VR, GEO, MWEB, WEB_EMBED, TV_EMBED, IOS) }
    val isReelClient by lazy { Helpers.equalsAny(this, ANDROID_REEL) }
    val isTVClient by lazy { name.startsWith("TV") }
    /**
     * Clients that must send the Cobalt TV form of the signature timestamp (the five-digit web
     * value with a "001" suffix) instead of the extractor's own value.
     *
     * NOT the same set as [isTVClient], and the difference is measured, not stylistic. Three
     * arms on one video, one session, one network, 2026-09-07, only the timestamp differing:
     *
     *  - TVHTML5 (TV, TV_LEGACY, TV_DOWNGRADED): five-digit -> UNPLAYABLE "the page needs to be
     *    reloaded", suffixed -> OK with formats. The suffix is the only way to get a response.
     *  - TVHTML5_SIMPLY: five-digit -> OK and its media URLs serve HTTP 206; suffixed -> OK with
     *    the same 22 formats, every one of which 403s on its FIRST byte range. Suffixing this
     *    client silently poisons the URLs it hands back.
     *  - TVHTML5_SIMPLY_EMBEDDED_PLAYER and TVHTML5_KIDS answer identically either way (they fail
     *    for unrelated reasons), so they follow the true player value.
     */
    val usesTvSignatureTimestamp by lazy { clientName == CLIENTS.TV.NAME }
    val isWebClient by lazy { Helpers.startsWithAny(name, "WEB", "MWEB", "INITIAL", "GEO") }
    val isEmbedded by lazy { Helpers.equalsAny(this, WEB_EMBED, TV_EMBED) }

    private fun extractBrowserInfo(userAgent: String): Pair<String, String>? {
        // Include Shorts: "browserName":"Cobalt"
        //val browserName = "Cobalt"
        //val browserVersion = "22.lts.3.306369-gold"

        for (name in listOf("SamsungBrowser", "LG Browser", "Cobalt", "Chrome", "Safari")) {
            val version = extractBrowserVersion(userAgent, name)
            if (version != null)
                return Pair(name, version)
        }

        //return Pair(browserName, browserVersion)
        return null
    }

    private fun extractBrowserVersion(userAgent: String, name: String): String? {
        if (userAgent.contains(name, ignoreCase = true)) {
            val browserVersionMatch = "$name/([a-zA-Z0-9.-]+)".toRegex().find(userAgent)
            return browserVersionMatch?.groupValues?.getOrNull(1)
        }

        return null
    }

    companion object {
        fun hasName(name: String): Boolean = values().any { it.name == name }

        /**
         * NEWTUBE(auth-probe, mobile-only, OFF by default): let WEB_EMBED carry the account on a
         * /player request.
         *
         * Motivation: as of 2026-09-07 YouTube answers every authenticated TVHTML5 request with
         * UNPLAYABLE "the page needs to be reloaded" (yt-dlp issue #17389), so the phone's ring
         * falls through the whole TV head and is served by an ANONYMOUS client - verified on the
         * Pixel 9, where every winning line reads `client=VISIONOS ... auth=n`. The account never
         * reaches /player at all, which silently costs age-restricted and members-only videos and
         * server-side watch history. yt-dlp's answer is `_DEFAULT_AUTHED_CLIENTS =
         * ('web_embedded', 'tv_downgraded', 'web')` (commit 5d5b634, 2026-08-18) - web_embedded
         * FIRST, ahead of the broken TV client.
         *
         * MEASURED ON THE PIXEL 9, 2026-09-07: our credential is REJECTED here, so this stays off.
         * Three opens, three identical responses, before any playability verdict:
         *
         *     HTTP 400, {"error":{"code":400,"message":"Request contains an invalid argument.",
         *                ... "reason":"badRequest"}}
         *
         * Not 401, and not "accepted but ignored" - InnerTube refuses the request outright. The
         * same string is already recorded against [WEB_CREATOR] a few lines above: upstream met
         * this long ago. The reason yt-dlp's web_embedded head works is that it authenticates with
         * cookie-derived SAPISIDHASH; it REMOVED OAuth support outright ("Login with OAuth is no
         * longer supported", _base.py) and drops every client lacking SUPPORTS_COOKIES when
         * authenticated, so it never mixes account and anonymous clients in one walk as we do.
         *
         * CONFIRMED 2026-09-07 by a three-arm run that isolated the bearer as the only variable
         * (forced client, same video, same network, `scratchpad/webauth.py`):
         *
         *     WEB_EMBED + bearer   HTTP 400   body hash 7b125bdfc2
         *     WEB       + bearer   HTTP 400   body hash 7b125bdfc2   <- identical body
         *     WEB       anonymous  HTTP 200   40 formats
         *
         * The 400 follows the CREDENTIAL, not the embed context: the same WEB client answers 200
         * without the bearer and 400 with it, and WEB and WEB_EMBED fail with a byte-identical
         * body. Ours is a TV device-flow OAuth bearer, and InnerTube will not take one on a web
         * client - that is now measured rather than inferred. (For contrast, the same bearer gets
         * HTTP 200 from TV in the same run; TVHTML5's refusal is a playability verdict, not a
         * transport-level rejection. See srvAuth= and HANDOFF S17.)
         *
         * So restoring authenticated playback is a CREDENTIAL problem, not a client-ordering one,
         * and no reordering of web clients can route around it. The gate stays because it is cheap
         * and self-correcting, and re-running the three arms is the cheapest way to notice if
         * YouTube's auth handling moves.
         *
         * Never called on TV.
         */
        @Volatile
        private var mWebAuthClient: AppClient? = null

        /**
         * Which web-family client carries the account, or null for none (the shipped default).
         *
         * Widened from a WEB_EMBED boolean on 2026-09-07 to answer the one question the 400 above
         * leaves open: it was only ever measured on WEB_EMBED, so "InnerTube refuses an OAuth
         * bearer on a web client" and "InnerTube refuses this bearer in an EMBED context" both fit
         * the evidence. Pointing the same gate at plain [WEB] separates them in one round trip,
         * which is worth knowing before anyone writes SAPISIDHASH plumbing on the strength of the
         * first reading.
         */
        @JvmStatic
        fun setWebAuthClient(client: AppClient?) {
            mWebAuthClient = client
        }

        @JvmStatic
        fun webAuthClient(): AppClient? = mWebAuthClient

        /** Back-compat shim: `debug.arc.web_auth=1` still means exactly WEB_EMBED. */
        @JvmStatic
        fun setWebEmbedAuthEnabled(enabled: Boolean) {
            mWebAuthClient = if (enabled) WEB_EMBED else null
        }

        @JvmStatic
        fun isWebEmbedAuthEnabled(): Boolean = mWebAuthClient == WEB_EMBED
    }
}
