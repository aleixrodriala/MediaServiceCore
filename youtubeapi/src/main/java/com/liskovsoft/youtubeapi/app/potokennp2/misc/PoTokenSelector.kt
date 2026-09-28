package com.liskovsoft.youtubeapi.app.potokennp2.misc

import com.liskovsoft.youtubeapi.app.potokennp2.PoTokenProviderImpl
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenGenerator
import com.liskovsoft.youtubeapi.app.potokennp2.generators.PoTokenWebView
import com.liskovsoft.youtubeapi.app.potokennp2.generators.PoTokenWebView4
import java.util.Locale

// NEWTUBE(pot-wv4): upstream made PoTokenWebView4 the default here (3d9521ff). The fork keeps
// PoTokenWebView until the new generator has been verified on a device; see PoTokenGeneratorSwitch.
internal fun selectFactory(): PoTokenGenerator.Factory =
    if (PoTokenGeneratorSwitch.isV4Selected()) PoTokenWebView4 else PoTokenWebView

/**
 * NEWTUBE(pot-wv4): which BotGuard generator builds the web-pot session.
 *
 * - [LEGACY], the default: PoTokenWebView, NewPipe's WAA `Create` challenge. No page is read, so
 *   BotGuard runs without `ytcfg`/`EVENT_ID`. Upstream labels it "outdated and probably not working
 *   at all" (12b7957e); on the phone it still mints (web-pot-session lines of 2026-09-28).
 * - [V4]: PoTokenWebView4, upstream's WebView port of bgutil PR #243: the youtube.com homepage's
 *   `ytAtN` challenge with that page's `ytcfg` injected as `yt.config_`, falling back to
 *   `/att/get`, then to PoTokenWebView. bgutil's 14/24 -> 22/24 (yt-dlp audio downloads through
 *   residential proxies) is background for this switch, not a prediction.
 *
 * Set once from MobileMainApplication (debug/benchmark `debug.arc.pot_gen=v4`) before the token
 * warm-up. It also updates PoTokenProviderImpl's factory directly, so it takes effect on the next
 * generator build even if PoTokenGate was initialized first.
 */
object PoTokenGeneratorSwitch {
    const val LEGACY = "legacy"
    const val V4 = "v4"

    @Volatile
    private var selection = LEGACY

    /** @return false for an unknown name, which leaves the selection unchanged */
    @JvmStatic
    fun select(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase(Locale.US)

        if (normalized != LEGACY && normalized != V4) {
            return false
        }

        selection = normalized
        PoTokenProviderImpl.poTokenFactory = selectFactory()
        return true
    }

    @JvmStatic
    fun getSelected(): String = selection

    internal fun isV4Selected() = selection == V4
}