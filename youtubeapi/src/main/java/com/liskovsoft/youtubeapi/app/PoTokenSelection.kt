package com.liskovsoft.youtubeapi.app

import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.TokenPolicy
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSourceCatalog

/**
 * What a PO token is being asked FOR. The distinction is not cosmetic: these two uses have
 * opposite correctness rules for a non-Web client, and collapsing them into one lookup is how a
 * Web/BotGuard token silently reached ANDROID_VR media URLs.
 */
internal enum class PoTokenUse {
    /**
     * The `serviceIntegrityDimensions.poToken` field of the /player REQUEST body. yt-dlp's
     * `PLAYER_PO_TOKEN_POLICY`; for `android_vr` it is `required=False, recommended=True`, and its
     * GVS policy carries `not_required_with_player_token=True` on all three protocols
     * (yt_dlp/extractor/youtube/_base.py). Presenting the token HERE is what makes the media URLs
     * the server hands back stop needing one.
     */
    PLAYER_REQUEST,

    /**
     * A `pot=` query parameter appended to an already-minted googlevideo URL. Only the Web family
     * may carry one; see [selectPoTokenSource].
     */
    MEDIA_URL
}

internal enum class PoTokenSource { WEB_CONTENT, WEB_SESSION, NONE }

/**
 * Pure client x purpose -> token-source decision. Deliberately a top-level function rather than a
 * member of [PoTokenGate]: that object's initializer wires up the WebView-backed generator
 * factory, so keeping the decision table out of it is what lets this be unit-tested with no
 * WebView, no network and no Android runtime.
 *
 * The rule that matters: a Web/BotGuard token may be presented in a NON-Web client's /player
 * request (it attests the request, and ANDROID_VR is sent the web visitorData the token is bound
 * to - see VideoInfoApiHelper.usesWebVisitorData), but it must NEVER be appended to that client's
 * media URLs. Current enforcement rejects tokens that do not match the originating platform and
 * binding, and an earlier implementation that did exactly this never prevented the observed 403
 * wall (see the note in VideoInfoServiceBase.decipherFormats). Android and iOS would need
 * DroidGuard/iOSGuard, which we cannot mint.
 *
 * VISIONOS also rides the web visitor but is intentionally absent from
 * [AppClient.isPlayerPotSupported]: yt-dlp gives `visionos` no PO-token policy at all, so it stays
 * token-free.
 */
internal fun selectPoTokenSource(
    client: AppClient,
    hasVideoId: Boolean,
    use: PoTokenUse,
    playerPotEnabled: Boolean
): PoTokenSource = when (PlayerSourceCatalog.defaultFor(client).token) {
    // NEWTUBE(source-catalog): the policy is the source's (PlayerSourceCatalog). WEB_EMBED's is NONE:
    // it rides the embed page's own visitor (YtCfgService.EmbedIdentity), not the session a Web
    // token is minted for, and neither its /player request nor its media need one - yt-dlp's
    // web_embedded downloads the full stream with no PO token (2026-09-28). A token bound to
    // another visitor could only get it refused.
    TokenPolicy.WEB ->
        if (hasVideoId) PoTokenSource.WEB_CONTENT else PoTokenSource.WEB_SESSION
    TokenPolicy.PLAYER_REQUEST_OPT_IN ->
        if (use == PoTokenUse.PLAYER_REQUEST && playerPotEnabled && hasVideoId) PoTokenSource.WEB_CONTENT
        else PoTokenSource.NONE
    TokenPolicy.NONE -> PoTokenSource.NONE
}
