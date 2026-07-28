package com.liskovsoft.youtubeapi.innertube.utils

internal object URLS {
    const val YT_BASE = "https://www.youtube.com"
    const val YT_MUSIC_BASE = "https://music.youtube.com"
    const val YT_SUGGESTIONS = "https://suggestqueries-clients6.youtube.com"
    const val YT_UPLOAD = "https://upload.youtube.com/"
    const val GOOGLE_SEARCH_BASE = "https://www.google.com/"

    object API {
        const val BASE = "https://youtubei.googleapis.com"
        const val PRODUCTION_1 = "https://www.youtube.com/youtubei/"
        const val PRODUCTION_2 = "https://youtubei.googleapis.com/youtubei/"
        const val STAGING = "https://green-youtubei.sandbox.googleapis.com/youtubei/"
        const val RELEASE = "https://release-youtubei.sandbox.googleapis.com/youtubei/"
        const val TEST = "https://test-youtubei.sandbox.googleapis.com/youtubei/"
        const val CAMI = "http://cami-youtubei.sandbox.googleapis.com/youtubei/"
        const val UYTFE = "https://uytfe.sandbox.google.com/youtubei/"
    }
}

internal object CLIENTS {
    // NEWTUBE(client-freshness): kept in step with yt-dlp's INNERTUBE_CLIENTS (see _base.py). A
    // clientVersion months behind the real app is a cheap bot signal, and on 2026-07-27 the Pixel-9
    // round measured every stale anonymous client under a permanent "confirm you're not a bot"
    // challenge on Telefonica cellular while the two version-current clients (TVHTML5 7.x /
    // TVHTML5 5.x) were never challenged. Bump these together with the matching user agent -- a
    // clientVersion that disagrees with the UA is itself a fingerprint mismatch.
    val IOS = CLIENT(
        NAME = "iOS",
        VERSION = "21.26.4",
        USER_AGENT = "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        DEVICE_MAKE = "Apple",
        DEVICE_MODEL = "iPhone16,2",
        OS_NAME = "iOS",
        OS_VERSION = "18.3.2.22D82"
    )

    //val IOS = CLIENT(
    //    NAME = "iOS",
    //    VERSION = "20.11.6",
    //    USER_AGENT = "com.google.ios.youtube/20.11.6 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)",
    //    DEVICE_MAKE = "Apple",
    //    DEVICE_MODEL = "iPhone16,2",
    //    OS_NAME = "iOS",
    //    OS_VERSION = "17.5.1.21F90"
    //)

    val WEB = CLIENT(
        NAME = "WEB",
        VERSION = "2.20260708.00.00",
        API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
        API_VERSION = "v1",
        STATIC_VISITOR_ID = "6zpwvWUNAco",
        SUGG_EXP_ID = "ytzpb5_e2,ytpo.bo.lqp.elu=1,ytpo.bo.lqp.ecsc=1,ytpo.bo.lqp.mcsc=3,ytpo.bo.lqp.mec=1,ytpo.bo.lqp.rw=0.8,ytpo.bo.lqp.fw=0.2,ytpo.bo.lqp.szp=1,ytpo.bo.lqp.mz=3,ytpo.bo.lqp.al=en_us,ytpo.bo.lqp.zrm=1,ytpo.bo.lqp.er=1,ytpo.bo.ro.erl=1,ytpo.bo.ro.mlus=3,ytpo.bo.ro.erls=3,ytpo.bo.qfo.mlus=3,ytzprp.ppp.e=1,ytzprp.ppp.st=772,ytzprp.ppp.p=5"
    )

    val MWEB = CLIENT(
        NAME = "MWEB",
        VERSION = "2.20260708.05.00",
        API_VERSION = "v1"
    )

    val WEB_KIDS = CLIENT(
        NAME = "WEB_KIDS",
        VERSION = "2.20260205.00.00"
    )

    val YTMUSIC = CLIENT(
        NAME = "WEB_REMIX",
        VERSION = "1.20250219.01.00"
    )

    // yt-dlp pins the ANDROID profile to an Android 11 / SDK 30 device; the SDK, osVersion and UA
    // must agree with each other or the context contradicts itself.
    val ANDROID = CLIENT(
        NAME = "ANDROID",
        VERSION = "21.26.364",
        OS_NAME = "Android",
        SDK_VERSION = 30,
        OS_VERSION = "11",
        USER_AGENT = "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"
    )

    // "Made for kids" videos aren't available with this client
    // Using a clientVersion>1.65 may return SABR streams only
    val ANDROID_VR = CLIENT(
        NAME = "ANDROID_VR",
        VERSION = "1.65.10",
        SDK_VERSION = 32,
        OS_NAME = "Android",
        // Keep the JSON client context consistent with the official VR user agent.
        // YouTube's current ANDROID_VR profile identifies this build as Android 12L.
        OS_VERSION = "12L",
        DEVICE_MAKE = "Oculus",
        DEVICE_MODEL = "Quest 3",
        USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    )

    // NEWTUBE(visionos): yt-dlp's current first-choice anonymous client - its _DEFAULT_CLIENTS is
    // ('visionos', 'android_vr', 'web'). Alone in that whole client table it declares neither a GVS
    // nor a player PO-token policy AND no JS player, i.e. no pot to mint and no cipher to solve.
    // Probed 2026-07-28 from an IP that answered LOGIN_REQUIRED ("Sign in to confirm you're not a
    // bot") to ANDROID_VR and TVHTML5 5.x in the same second: VISIONOS returned 32 adaptive formats
    // with direct URLs and served init / mid / tail ranges at HTTP 206.
    // "Made for kids" videos aren't available with this client (same limitation as ANDROID_VR).
    val VISIONOS = CLIENT(
        NAME = "VISIONOS",
        VERSION = "1.02",
        DEVICE_MAKE = "Apple",
        DEVICE_MODEL = "RealityDevice17,1",
        OS_NAME = "visionOS",
        OS_VERSION = "26.5.23O471",
        USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    )

    val YTSTUDIO_ANDROID = CLIENT(
        NAME = "ANDROID_CREATOR",
        VERSION = "22.43.101"
    )

    val YTMUSIC_ANDROID = CLIENT(
        NAME = "ANDROID_MUSIC",
        VERSION = "5.34.51"
    )

    val TV = CLIENT(
        NAME = "TVHTML5",
        VERSION = "7.20260707.07.00",
        USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
    )

    val TV_SIMPLY = CLIENT(
        NAME = "TVHTML5_SIMPLY",
        VERSION = "1.0"
    )

    val TV_EMBEDDED = CLIENT(
        NAME = "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        VERSION = "2.0"
    )

    val WEB_EMBEDDED = CLIENT(
        NAME = "WEB_EMBEDDED_PLAYER",
        VERSION = "2.20260708.00.00",
        API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
        API_VERSION = "v1",
        STATIC_VISITOR_ID = "6zpwvWUNAco"
    )

    val WEB_CREATOR = CLIENT(
        NAME = "WEB_CREATOR",
        VERSION = "1.20241203.01.00",
        API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8",
        API_VERSION = "v1",
        STATIC_VISITOR_ID = "6zpwvWUNAco"
    )

    val ALl = mapOf(
        "IOS" to IOS,
        "WEB" to WEB,
        "MWEB" to MWEB,
        "WEB_KIDS" to WEB_KIDS,
        "YTMUSIC" to YTMUSIC,
        "ANDROID" to ANDROID,
        "ANDROID_VR" to ANDROID_VR,
        "VISIONOS" to VISIONOS,
        "YTSTUDIO_ANDROID" to YTSTUDIO_ANDROID,
        "YTMUSIC_ANDROID" to YTMUSIC_ANDROID,
        "TV" to TV,
        "TV_SIMPLY" to TV_SIMPLY,
        "TV_EMBEDDED" to TV_EMBEDDED,
        "WEB_EMBEDDED" to WEB_EMBEDDED,
        "WEB_CREATOR" to WEB_CREATOR
    )
}

internal data class CLIENT(
    val NAME: String,
    val VERSION: String,
    val SDK_VERSION: Int? = null,
    val DEVICE_MAKE: String? = null,
    val DEVICE_MODEL: String? = null,
    val USER_AGENT: String? = null,
    val OS_NAME: String? = null,
    val OS_VERSION: String? = null,
    val API_KEY: String? = null,
    val API_VERSION: String = "v1",
    val STATIC_VISITOR_ID: String? = null,
    val SUGG_EXP_ID: String? = null
)

/**
 * The keys correspond to the `NAME` fields in [CLIENTS] constant
 */
internal val CLIENT_NAME_IDS: Map<String, String> = mapOf(
    "iOS" to "5",
    "WEB" to "1",
    "MWEB" to "2",
    "WEB_KIDS" to "76",
    "WEB_REMIX" to "67",
    "ANDROID" to "3",
    "ANDROID_CREATOR" to "14",
    "ANDROID_MUSIC" to "21",
    "ANDROID_VR" to "28",
    "VISIONOS" to "101",
    "TVHTML5" to "7",
    "TVHTML5_SIMPLY" to "74",
    "TVHTML5_SIMPLY_EMBEDDED_PLAYER" to "85",
    "WEB_EMBEDDED_PLAYER" to "56",
    "WEB_CREATOR" to "62"
)

internal val STREAM_HEADERS: Map<String, String> = mapOf(
    "accept" to "*/*",
    "origin" to "https://www.youtube.com",
    "referer" to "https://www.youtube.com",
    "DNT" to "?1"
)

internal val INNERTUBE_HEADERS_BASE: Map<String, String> = mapOf(
    "accept" to "*/*",
    "accept-encoding" to "gzip, deflate",
    "content-type" to "application/json"
)

internal val SUPPORTED_CLIENTS = listOf(
    "IOS",
    "WEB",
    "MWEB",
    "YTKIDS",
    "YTMUSIC",
    "ANDROID",
    "ANDROID_VR",
    "YTSTUDIO_ANDROID",
    "YTMUSIC_ANDROID",
    "TV",
    "TV_SIMPLY",
    "TV_EMBEDDED",
    "WEB_EMBEDDED",
    "WEB_CREATOR"
)
