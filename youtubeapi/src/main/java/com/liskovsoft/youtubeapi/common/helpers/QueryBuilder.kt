package com.liskovsoft.youtubeapi.common.helpers

import com.liskovsoft.sharedutils.helpers.Helpers
import com.liskovsoft.youtubeapi.app.AppService
import com.liskovsoft.googlecommon.common.locale.LocaleManager
import com.liskovsoft.youtubeapi.innertube.ytcfg.YtCfgService

internal enum class PostDataType { Player, Browse }

// Use protobuf to bypass geo blocking
private const val GEO_PARAMS: String = "CgIQBg%3D%3D"

/**
 * NEWTUBE(bench): request-shape overrides for the in-app source benchmark (debug and benchmark
 * builds only; the app sets them from `debug.arc.*` properties at start). Null = the normal shape.
 */
object DebugRequestOverrides {
    /** devicePlaybackCapabilities.supportXhr: "true" / "false", or "absent" to omit the whole block. */
    @Volatile
    @JvmStatic
    var supportXhr: String? = null
}

internal class QueryBuilder(private val client: AppClient) {
    private val localeManager by lazy { LocaleManager.instance() }
    private val appService by lazy { AppService.instance() }
    private var type: PostDataType = PostDataType.Player
    private var acceptLanguage: String? = null
    private var acceptRegion: String? = null
    private var utcOffsetMinutes: Int? = null
    private var visitorData: String? = null
    private var cpn: String? = null
    private var browseId: String? = null
    private var continuationId: String? = null
    private var videoId: String? = null
    private var playlistId: String? = null
    private var playlistIndex: Int? = null
    private var clickTrackingParams: String? = null
    private var params: String? = null
    private var poToken: String? = null
    private var encryptedHostFlags: String? = null
    private var signatureTimestamp: Int? = null
    private var isGeoFixEnabled: Boolean = false

    fun setType(type: PostDataType) = apply { this.type = type }
    fun setLanguage(lang: String?) = apply { acceptLanguage = lang }
    fun setCountry(country: String?) = apply { acceptRegion = country }
    fun setUtcOffsetMinutes(offset: Int?) = apply { utcOffsetMinutes = offset }
    fun setBrowseId(browseId: String?) = apply { this.browseId = browseId }
    fun setContinuationId(continuationId: String?) = apply { this.continuationId = continuationId }
    fun setVideoId(videoId: String?) = apply { this.videoId = videoId }
    fun setPlaylistId(playlistId: String?) = apply { this.playlistId = playlistId }
    fun setPlaylistIndex(playlistIndex: Int?) = apply { this.playlistIndex = playlistIndex }
    fun setPoToken(poToken: String?) = apply { this.poToken = poToken }
    fun setClientPlaybackNonce(cpn: String?) = apply { this.cpn = cpn }
    fun setSignatureTimestamp(timestamp: Int?) = apply { signatureTimestamp = timestamp }
    fun setClickTrackingParams(params: String?) = apply { clickTrackingParams = params }
    fun setParams(params: String?) = apply { this.params = params }
    fun setVisitorData(visitorData: String?) = apply { this.visitorData = visitorData }
    fun setEncryptedHostFlags(flags: String?) = apply { this.encryptedHostFlags = flags }
    fun enableGeoFix(enableGeoFix: Boolean) = apply { isGeoFixEnabled = enableGeoFix }

    fun build(): String {
        // TODO: need more robust type detection
        if (browseId != null || continuationId != null || params != null)
            type = PostDataType.Browse

        if (acceptLanguage == null)
            acceptLanguage = localeManager.language

        if (acceptRegion == null)
            acceptRegion = localeManager.country

        if (utcOffsetMinutes == null)
            utcOffsetMinutes = localeManager.utcOffsetMinutes

        if (playerDataCheck() || browseDataCheck()) {
            if (visitorData == null)
                visitorData = appService.visitorData
        }

        if (playerDataCheck()) {
            if (cpn == null)
                cpn = appService.clientPlaybackNonce // get it somewhere else?

            // TVHTML5 requests use the TV timestamp format even when the extractor has a Web
            // value. Upstream: five-digit 20522 -> 20522001; already-TV timestamps stay unchanged.
            // Scoped to the Cobalt client (upstream applies it to every "TV*" enum) because
            // TVHTML5_SIMPLY is served working media URLs by the real five-digit value and dead,
            // instantly-403 URLs by the suffixed one -- see [AppClient.usesTvSignatureTimestamp].
            if (signatureTimestamp == null || signatureTimestamp == -1)
                signatureTimestamp = Helpers.parseInt(appService.signatureTimestamp?.let {
                    if (client.usesTvSignatureTimestamp && it.length == 5) it + "001" else it
                })
        }

        val json = """
             {
                "context": {
                     ${createClientChunk()}
                     ${createClickTrackingChunk()}
                     ${createUserChunk()}
                     ${createWebEmbeddedChunk()}
                },
                ${createTimestampChunk()}
                ${createPotChunk()}
                ${createVideoDataChunk()}
                ${createBrowseDataChunk()}
             }
        """

        // Remove all indentations
        val result = buildString {
            json.lineSequence().forEach { append(it.trim()) }
        }

        // Upstream 4d128db8: optional chunks leave commas before closing objects. Remove
        // those syntax commas only; a literal ",}" inside a quoted field is data.
        return removeTrailingObjectCommas(result)
    }

    private fun removeTrailingObjectCommas(json: String): String = buildString(json.length) {
        var quoted = false
        var escaped = false
        for (index in json.indices) {
            val char = json[index]
            if (quoted) {
                append(char)
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    quoted = false
                }
                continue
            }
            if (char == '"') {
                quoted = true
            } else if (char == ',') {
                var next = index + 1
                while (next < json.length && json[next].isWhitespace()) next++
                if (next < json.length && json[next] == '}') continue
            }
            append(char)
        }
    }

    private fun createClientChunk(): String {
        val clientVars = """
            "clientName": "${client.clientName}",
            "clientVersion": "${client.clientVersion}",
            "clientScreen": "${client.clientScreen}",
            "userAgent": "${client.userAgent}",
        """
        val browserVars = if (client.browserName != null && client.browserVersion != null)
            """
                "browserName": "${client.browserName}",
                "browserVersion": "${client.browserVersion}",
            """
            else ""
        val postVars = client.postData ?: ""
        val postBrowseVars = if (requireNotNull(type) == PostDataType.Browse)
                client.postDataBrowse ?: ""
            else ""
        val regionVars = """
            "acceptLanguage": "${requireNotNull(acceptLanguage)}",
            "acceptRegion": "${requireNotNull(acceptRegion)}",
            "utcOffsetMinutes": "${requireNotNull(utcOffsetMinutes)}",
        """
        val visitorVar = visitorData?.let { """ "visitorData": "$visitorData" """ } ?: ""
        return """
             "client": {
                $clientVars
                $browserVars
                $postVars
                $postBrowseVars
                $regionVars
                $visitorVar
             },
        """
    }

    private fun createClickTrackingChunk(): String {
        return clickTrackingParams?.let {
            """
                "clickTracking": {
                    "clickTrackingParams": "$it"
                },
            """
        } ?: ""
    }

    private fun createWebEmbeddedChunk(): String {
        return if (client.isEmbedded)
            // https://github.com/yt-dlp/yt-dlp/commit/f2bd3202c0ffa3f0c0069c44ca53b625dca568bc
            //"""
            //    "thirdParty": {
            //        "embedUrl": "https://www.youtube.com/embed/${requireNotNull(videoId)}"
            //    },
            //"""
            // Can be any valid non-YouTube URL
            """
                "thirdParty": {
                    "embedUrl": "https://www.reddit.com/"
                },
            """
           else ""
    }

    private fun createUserChunk(): String {
        return """
           "user":{
                "enableSafetyMode": false,
                "lockedSafetyMode":false
           }, 
        """
    }

    private fun createPotChunk(): String {
        return poToken?.let {
            """
               "serviceIntegrityDimensions": {
                    "poToken": "$it"
               }, 
            """
        } ?: ""
    }

    private fun createVideoDataChunk(): String {
        val data = """
                    "racyCheckOk": true,
                    "contentCheckOk": true,
                    ${createVideoIdChunk()}
                    ${createCPNChunk()}
                """
        return if (client.isReelClient)
            """
                "playerRequest": {
                    $data
                },
            """
        else
            """
                $data
            """
    }

    private fun createBrowseDataChunk(): String {
        return """
                    ${createBrowseIdChunk()}
                    ${createContinuationIdChunk()}
                    ${createPlaylistIdChunk()}
                    ${createParamsChunk()}
                """
    }

    private fun createVideoIdChunk(): String {
        return videoId?.let {
            """
                "videoId": "$it",
            """
        } ?: ""
    }

    private fun createBrowseIdChunk(): String {
        return browseId?.let {
            """
                "browseId": "$it",
            """
        } ?: ""
    }

    private fun createContinuationIdChunk(): String {
        return continuationId?.let {
            """
                "continuation": "$it",
            """
        } ?: ""
    }

    private fun createPlaylistIdChunk(): String {
        // Note, that negative playlistIndex values produce error
        return playlistId?.let {
            """
                "playlistId": "$it",
                "playlistIndex": "${playlistIndex?.coerceAtLeast(0) ?: 0}",
            """
        } ?: ""
    }

    private fun createCPNChunk(): String {
        return cpn?.let {
            """
                "cpn": "$it",
            """
        } ?: ""
    }

    private fun createParamsChunk(): String {
        val params = if (isGeoFixEnabled) GEO_PARAMS else params ?: client.params
        return params?.let {
            """
                "params": "$it",
            """
        } ?: ""
    }

    private fun createTimestampChunk(): String {
        // isInlinePlaybackNoAd https://iter.ca/post/yt-adblock/
        // According to someone in the YouTube.js Discord server, setting supportXhr to false brings the URLs back for TV (matrix chat)
        // NEWTUBE(web-embed-identity): the same holds for WEB_EMBED - with supportXhr=true it is answered
        // SABR-only (26 formats, none with a URL), with false it gets direct URLs and HLS (made-for-kids
        // _WB5hh7WOb4, 2026-09-28; supportsVp9Encoding makes no difference). MWEB behaves the same way,
        // but it is left as upstream has it until measured in the ring.
        // use_ad_playback_context`: Skip preroll ads to eliminate the mandatory wait period before download.
        // Do NOT use this when passing premium account cookies to yt-dlp, as it will result in a loss of premium formats.
        // Only effective with the `web`, `web_safari`, `web_music` and `mweb` player clients. Either `true` or `false`
        // use_ad_playback_context extractor-arg: https://github.com/yt-dlp/yt-dlp/commit/f7acf3c1f42cc474927ecc452205d7877af36731
        return signatureTimestamp?.let {
            """
                "playbackContext": {
                    "contentPlaybackContext": {
                        "html5Preference": "HTML5_PREF_WANTS",
                        "lactMilliseconds": 60000,
                        "isInlinePlaybackNoAd": true,
                        "signatureTimestamp": $it,
                        ${createEncryptedHostFlags()}
                    }${createDevicePlaybackCapabilities()}
                },
            """
        } ?: ""
    }

    private fun createDevicePlaybackCapabilities(): String {
        val supportXhr = when (DebugRequestOverrides.supportXhr) {
            "absent" -> return ""
            "true" -> true
            "false" -> false
            else -> !client.isTVClient && !client.isEmbedded
        }
        return """,
                    "devicePlaybackCapabilities": {
                        "supportsVp9Encoding": true,
                        "supportXhr": $supportXhr
                    }"""
    }

    /**
     * web_embedded player requests may need to include encryptedHostFlags in its contentPlaybackContext.
     *
     * This can be detected with the embeds_enable_encrypted_host_flags_enforcement experiemnt flag,
     *
     * but there is no harm in including encryptedHostFlags with all web_embedded player requests.
     *
     * ```
     * traverse_obj(player_ytcfg, (
     *                 'WEB_PLAYER_CONTEXT_CONFIGS', 'WEB_PLAYER_CONTEXT_CONFIG_ID_EMBEDDED_PLAYER', 'encryptedHostFlags'))
     * ```
     */
    private fun createEncryptedHostFlags(): String {
        if (!client.isEmbedded)
            return ""

        // NEWTUBE(web-embed-identity): WEB_EMBED sends only the flags its caller passes together
        // with the visitor they are bound to (YtCfgService.EmbedIdentity). Looking them up here
        // when the caller had none could pair a freshly fetched page's flags with another visitor:
        // the 152-18 refusal. TV_EMBED (not on the phone ring) keeps upstream's lookup.
        val flags = if (client == AppClient.WEB_EMBED) encryptedHostFlags
            else encryptedHostFlags ?: YtCfgService.getEmbedIdentity(videoId)?.encryptedHostFlags

        return flags?.let {
            """
               "encryptedHostFlags":"$it",
            """
        } ?: ""
    }

    private fun playerDataCheck() = videoId != null && type == PostDataType.Player
    private fun browseDataCheck() = type == PostDataType.Browse
}
