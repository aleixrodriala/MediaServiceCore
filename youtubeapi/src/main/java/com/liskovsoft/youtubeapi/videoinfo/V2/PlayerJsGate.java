package com.liskovsoft.youtubeapi.videoinfo.V2;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * NEWTUBE(player-js-gate): which /player sources may be asked before a new player JS is validated.
 * <p>
 * When YouTube ships a new player (every few days; also whenever the preprocessed-player cache is
 * empty), building the extractor reads the ~2.1 MB JS and then validates it in V8 (preprocess +
 * checkSigData's fixed n/sig solve): 3.0 s on the emulator (v17-emu-si-ring, dQw4w9WgXcQ,
 * 2026-09-29: {@code v8-run initMs=423 solveMs=2583}). Every /player request waited for all of it,
 * because QueryBuilder asks AppService for the cpn and the signatureTimestamp, and both went through
 * the extractor. On that open the VISIONOS request left 4.6 s after its player-context line and was
 * answered in 89 ms, with nothing to decipher.
 * <p>
 * A source listed here asks AppService for the read-ahead values instead (AppService
 * getReadAheadPlayerData): the same signatureTimestamp, read from the same JS, and a cpn, as soon as
 * the JS is read. The request is otherwise the one it was. Everything else waits exactly as before:
 * the other sources' requests (their QueryBuilder still asks AppService, which waits for the
 * validation), and any transform with a signature or n parameter to solve (it asks the extractor
 * itself; see VideoInfoServiceBase.decipherFormats).
 * <p>
 * The evidence for each listed source is two-sided - its answers carry nothing to solve, and the
 * reference extractor does not need the player JS for it:
 * <ul>
 *     <li>VISIONOS: yt-dlp {@code 'visionos': {'REQUIRE_JS_PLAYER': False}}; every VISIONOS answer
 *     in the netbench and issue-5 logs (69 of 69 transforms) logged {@code player-sig n=0/0 s=0/0}.
 *     NewPipeExtractor (dev eb53b79, 2026-09-27) asks VISIONOS with no playbackContext and no
 *     signatureTimestamp at all.</li>
 *     <li>ANDROID_VR: yt-dlp {@code 'android_vr': {'REQUIRE_JS_PLAYER': False}}; 4 of 4 transforms
 *     {@code n=0/0 s=0/0}.</li>
 * </ul>
 * Left waiting: IOS and ANDROID_REEL (yt-dlp needs no JS for ios/android either, but the app has
 * no answer of theirs on record, and they come late in a walk, after a source that validates the
 * player anyway), and everything yt-dlp gives {@code REQUIRE_JS_PLAYER: True} (TV family, web
 * family, WEB_EMBED), whose answers were measured ciphered (TV_TIZEN n in 121 of 121, WEB_EMBED 73
 * of 73, MWEB 20 of 20).
 * <p>
 * The list is decided per source, before the answer is seen. Should one of them ever answer with
 * something to solve (an n or s parameter, an HLS manifest challenge), nothing is lost: its request
 * carried the same timestamp the finished extractor carries, and the transform waits for the
 * validated player before solving it, exactly as for any other source.
 * <p>
 * The signatureTimestamp is still sent (the player's own, read from its JS): yt-dlp sends one to
 * these clients whenever it knows the player, and in this app's request it also carries the
 * playbackContext (supportXhr, isInlinePlaybackNoAd), which has not been measured without it. So
 * the request still waits for the JS download (204 ms on that open), only not for V8.
 */
final class PlayerJsGate {
    private static final Set<AppClient> NO_SOLVE = Collections.unmodifiableSet(
            EnumSet.of(AppClient.VISIONOS, AppClient.ANDROID_VR));

    private PlayerJsGate() {
    }

    /**
     * Whether {@code client}'s /player may go out before the player is validated.
     *
     * @param phone   the phone path (VideoInfoService's sPreferNoPotClient); TV never does
     * @param enabled the gate (the debug rollback turns it off)
     */
    static boolean skipsValidation(AppClient client, boolean phone, boolean enabled) {
        return phone && enabled && client != null && NO_SOLVE.contains(client);
    }

    /** The sources that may; for the tests. */
    static Set<AppClient> sources() {
        return NO_SOLVE;
    }
}
