package com.liskovsoft.youtubeapi.app

import com.liskovsoft.youtubeapi.common.helpers.AppClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure decision-table checks: no WebView, no network, no Android runtime. The property under test
 * is the one that a single collapsed lookup used to get wrong - a Web/BotGuard token is allowed in
 * a non-Web client's /player REQUEST but must never reach that client's MEDIA URLs.
 */
class PoTokenSelectionTest {
    private val webFamily = listOf(
        AppClient.WEB, AppClient.WEB_EMBED, AppClient.WEB_SAFARI,
        AppClient.MWEB, AppClient.INITIAL, AppClient.GEO
    )

    /** Non-Web clients that must never be handed a Web-minted token, for any use. */
    private val foreignPlatformClients = listOf(
        AppClient.TV, AppClient.TV_DOWNGRADED, AppClient.TV_EMBED, AppClient.TV_SIMPLY,
        AppClient.IOS, AppClient.ANDROID, AppClient.ANDROID_REEL, AppClient.VISIONOS
    )

    private fun select(
        client: AppClient,
        use: PoTokenUse,
        hasVideoId: Boolean = true,
        enabled: Boolean = true
    ) = selectPoTokenSource(client, hasVideoId, use, enabled)

    @Test
    fun androidVrAttestsItsPlayerRequestByDefault() {
        assertEquals(
            PoTokenSource.WEB_CONTENT,
            select(AppClient.ANDROID_VR, PoTokenUse.PLAYER_REQUEST)
        )
    }

    /**
     * The regression guard. Enabling the player pot must not also start appending a Web token to
     * ANDROID_VR googlevideo URLs: yt-dlp's `not_required_with_player_token` means the media URLs
     * stop NEEDING a token, not that they should carry one, and cross-platform tokens are
     * rejected (VideoInfoServiceBase.decipherFormats).
     */
    @Test
    fun androidVrMediaUrlsNeverCarryAWebToken() {
        assertEquals(
            PoTokenSource.NONE,
            select(AppClient.ANDROID_VR, PoTokenUse.MEDIA_URL)
        )
        assertEquals(
            PoTokenSource.NONE,
            select(AppClient.ANDROID_VR, PoTokenUse.MEDIA_URL, hasVideoId = false)
        )
    }

    /** debug.arc.player_pot=0 must restore the previous token-free request. */
    @Test
    fun disablingThePlayerPotRestoresTheTokenFreeRequest() {
        assertEquals(
            PoTokenSource.NONE,
            select(AppClient.ANDROID_VR, PoTokenUse.PLAYER_REQUEST, enabled = false)
        )
    }

    /**
     * The live path asks without a videoId (VideoInfoServiceBase appends a manifest pot). A
     * content-bound token cannot be minted there, and a session token would be the cross-platform
     * fallback we refuse to do.
     */
    @Test
    fun androidVrWithoutAVideoIdGetsNothing() {
        assertEquals(
            PoTokenSource.NONE,
            select(AppClient.ANDROID_VR, PoTokenUse.PLAYER_REQUEST, hasVideoId = false)
        )
    }

    @Test
    fun webFamilyIsUnaffectedByThePlayerPotSwitch() {
        for (client in webFamily) {
            for (use in PoTokenUse.values()) {
                for (enabled in listOf(true, false)) {
                    assertEquals(
                        "$client/$use/enabled=$enabled",
                        PoTokenSource.WEB_CONTENT,
                        select(client, use, enabled = enabled)
                    )
                }
            }
        }
    }

    @Test
    fun webFamilyWithoutAVideoIdUsesTheSessionToken() {
        for (client in webFamily) {
            for (use in PoTokenUse.values()) {
                assertEquals(
                    "$client/$use",
                    PoTokenSource.WEB_SESSION,
                    select(client, use, hasVideoId = false)
                )
            }
        }
    }

    /**
     * VISIONOS rides the same web visitorData as ANDROID_VR, which makes it the most plausible
     * candidate for an accidental token. yt-dlp gives `visionos` no PO-token policy at all, so it
     * stays token-free; TV/iOS/Android would need their own attestation we cannot mint.
     */
    @Test
    fun foreignPlatformClientsStayTokenFree() {
        for (client in foreignPlatformClients) {
            for (use in PoTokenUse.values()) {
                for (hasVideoId in listOf(true, false)) {
                    assertEquals(
                        "$client/$use/hasVideoId=$hasVideoId",
                        PoTokenSource.NONE,
                        select(client, use, hasVideoId = hasVideoId)
                    )
                }
            }
        }
    }

    /** ANDROID_VR is the only client the player-pot switch may affect. */
    @Test
    fun onlyAndroidVrIsSensitiveToTheSwitch() {
        val sensitive = AppClient.values().filter { client ->
            PoTokenUse.values().any { use ->
                select(client, use, enabled = true) != select(client, use, enabled = false)
            }
        }
        assertEquals(listOf(AppClient.ANDROID_VR), sensitive)
    }
}
