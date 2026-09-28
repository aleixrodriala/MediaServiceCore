package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Budget.SPECULATIVE;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Budget.WEB_POT;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Endpoint.PLAYER;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Endpoint.REEL_ITEM_WATCH;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Endpoint.WATCH_PAGE;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Identity.APP_VISITOR;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Identity.EMBED_PAGE;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Identity.WEB_SESSION;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.TokenPolicy.NONE;
import static com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.TokenPolicy.PLAYER_REQUEST_OPT_IN;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NEWTUBE(source-catalog): every /player source the app knows and how each one is asked - the
 * single table behind the visitor, PO-token, supportXhr, endpoint and attempt-budget decisions that
 * used to be predicates spread over VideoInfoApiHelper, PoTokenSelection, QueryBuilder and
 * VideoInfoService (netbench DESIGN.md section 3.1, phase 3).
 *
 * <p>Phase 3 is behaviour-preserving: one source per {@link AppClient}, each exactly what that
 * client sent before (PlayerRequestGoldenTest pins the requests byte for byte). Upstream's identity
 * facts stay in AppClient; the ring order and walk state stay in VideoInfoService. A client that an
 * upstream merge adds has no entry until someone decides its profile: PlayerSourceCatalogTest fails,
 * and until then {@link #defaultFor} answers the most conservative profile rather than crashing.
 */
public final class PlayerSourceCatalog {
    private static final String TAG = PlayerSourceCatalog.class.getSimpleName();
    private static final Map<AppClient, PlayerSource> DEFAULTS = new EnumMap<>(AppClient.class);
    private static final Map<String, PlayerSource> BY_ID = new HashMap<>();
    private static final List<PlayerSource> ALL = new ArrayList<>();

    static {
        // The TV family: the app visitor, no token, supportXhr false.
        add(AppClient.TV, APP_VISITOR, NONE, PlayerSource.Xhr.FALSE, PLAYER, SPECULATIVE,
                "TVHTML5 7.x; the signed-in account client (Cobalt sts suffix, QueryBuilderTimestampTest)");
        add(AppClient.TV_LEGACY, APP_VISITOR, NONE, PlayerSource.Xhr.FALSE, PLAYER, SPECULATIVE,
                "same /player as TV; phone-skipped (setSkipTvFallbackClients)");
        add(AppClient.TV_EMBED, APP_VISITOR, NONE, PlayerSource.Xhr.FALSE, PLAYER, SPECULATIVE,
                "embedded TV client; phone-skipped; its host flags come from the WEB embed page");
        add(AppClient.TV_SIMPLY, APP_VISITOR, NONE, PlayerSource.Xhr.FALSE, PLAYER, SPECULATIVE,
                "phone-skipped; netbench 2026-09-28: media stops ~60 s without a GVS token");
        add(AppClient.TV_KIDS, APP_VISITOR, NONE, PlayerSource.Xhr.FALSE, PLAYER, SPECULATIVE,
                "not in the ring; no X-Youtube-Client-Name header");
        add(AppClient.TV_DOWNGRADED, APP_VISITOR, NONE, PlayerSource.Xhr.FALSE, PLAYER, SPECULATIVE,
                "TVHTML5 5.x; the signed-in head; its old-Cobalt shape drew bot walls first (netbench)");
        add(AppClient.TV_TIZEN, APP_VISITOR, NONE, PlayerSource.Xhr.FALSE, PLAYER, SPECULATIVE,
                "the account route; anonymous it plays made-for-kids videos (netbench 2026-09-28, "
                        + "Pixel LTE first frame 1.0 s)");

        // The web family: the web session's visitor and token.
        add(AppClient.WEB, WEB_SESSION, PlayerSource.TokenPolicy.WEB, PlayerSource.Xhr.TRUE, PLAYER, WEB_POT,
                "BotGuard token in the body and on media URLs");
        add(AppClient.WEB_SAFARI, WEB_SESSION, PlayerSource.TokenPolicy.WEB, PlayerSource.Xhr.TRUE, PLAYER,
                WEB_POT, "WEB with the Safari user agent");
        add(AppClient.MWEB, WEB_SESSION, PlayerSource.TokenPolicy.WEB, PlayerSource.Xhr.TRUE, PLAYER, WEB_POT,
                "media held back for its pre-roll wait; stops at ~0:59 in the app with today's token");
        add(AppClient.GEO, WEB_SESSION, PlayerSource.TokenPolicy.WEB, PlayerSource.Xhr.TRUE, PLAYER, WEB_POT,
                "WEB plus params CgIQBg== (QueryBuilder geo fix)");
        add(AppClient.INITIAL, WEB_SESSION, PlayerSource.TokenPolicy.WEB, PlayerSource.Xhr.TRUE, WATCH_PAGE,
                WEB_POT, "the watch page's ytInitialPlayerResponse; not a /player request");
        // WEB_EMBED rides the embed page's own visitor and needs no token (web-embed-identity).
        add(AppClient.WEB_EMBED, EMBED_PAGE, NONE, PlayerSource.Xhr.FALSE, PLAYER, WEB_POT,
                EnumSet.of(PlayerSource.Delivery.HLS),
                "serves made-for-kids and embeddable age-gated videos; media held back for the "
                        + "pre-roll wait (ReadinessGate); Pixel LTE 2026-09-28 PLAY-OK; its answers "
                        + "carry HLS (netbench sustain1-wifi-hls: played to EOF on 2 of 2 kids videos)");

        // Web clients that ride the app visitor without a token.
        add(AppClient.WEB_CREATOR, APP_VISITOR, NONE, PlayerSource.Xhr.TRUE, PLAYER, SPECULATIVE,
                "not in the ring");
        add(AppClient.WEB_MUSIC, APP_VISITOR, NONE, PlayerSource.Xhr.TRUE, PLAYER, SPECULATIVE,
                "not in the ring; netbench: media stops ~60 s without a GVS token");

        // Platform clients.
        add(AppClient.ANDROID, APP_VISITOR, NONE, PlayerSource.Xhr.TRUE, PLAYER, SPECULATIVE,
                "not in the ring");
        add(AppClient.ANDROID_SDK_LESS, APP_VISITOR, NONE, PlayerSource.Xhr.TRUE, PLAYER, SPECULATIVE,
                "not in the ring (\"hangs on cronet\")");
        add(AppClient.ANDROID_REEL, APP_VISITOR, NONE, PlayerSource.Xhr.TRUE, REEL_ITEM_WATCH, SPECULATIVE,
                EnumSet.of(PlayerSource.Delivery.PROGRESSIVE),
                "the Shorts endpoint on youtubei.googleapis.com; made-for-kids answers are SABR-only "
                        + "adaptive plus 360p progressive (netbench sustain1-wifi: played to EOF on 1 of "
                        + "2; the other stream had no content length)");
        add(AppClient.IOS, APP_VISITOR, NONE, PlayerSource.Xhr.TRUE, PLAYER, SPECULATIVE,
                "mostly SABR-only answers (netbench)");
        // Token-free, but on the web session's visitor (PoTokenGate.getWebVisitorDataForPlayer).
        add(AppClient.ANDROID_VR, WEB_SESSION, PLAYER_REQUEST_OPT_IN, PlayerSource.Xhr.TRUE, PLAYER,
                SPECULATIVE, "the live DASH route; body token only with debug.arc.player_pot=1");
        add(AppClient.VISIONOS, WEB_SESSION, NONE, PlayerSource.Xhr.TRUE, PLAYER, SPECULATIVE,
                "the phone's head: one request for almost everything; refuses made-for-kids videos");
    }

    private PlayerSourceCatalog() {
    }

    private static void add(AppClient client, PlayerSource.Identity identity, PlayerSource.TokenPolicy token,
            PlayerSource.Xhr xhr, PlayerSource.Endpoint endpoint, PlayerSource.Budget budget, String evidence) {
        add(client, identity, token, xhr, endpoint, budget, EnumSet.noneOf(PlayerSource.Delivery.class),
                evidence);
    }

    private static void add(AppClient client, PlayerSource.Identity identity, PlayerSource.TokenPolicy token,
            PlayerSource.Xhr xhr, PlayerSource.Endpoint endpoint, PlayerSource.Budget budget,
            EnumSet<PlayerSource.Delivery> fallbacks, String evidence) {
        PlayerSource source = new PlayerSource(client.name() + "@1", client, identity, token, xhr,
                endpoint, budget, Collections.unmodifiableSet(fallbacks), evidence);
        if (DEFAULTS.put(client, source) != null || BY_ID.put(source.id, source) != null) {
            throw new IllegalStateException("duplicate source " + source.id);
        }
        ALL.add(source);
    }

    /**
     * The source the walk uses for {@code client} (phase 3: every client has exactly one). A client
     * with no entry - one an upstream merge added - gets the most conservative profile: the app
     * visitor, no token, the plain /player shape and the short budget.
     */
    @NonNull
    public static PlayerSource defaultFor(@NonNull AppClient client) {
        PlayerSource source = DEFAULTS.get(client);
        if (source != null) {
            return source;
        }
        android.util.Log.w(TAG, "no catalog entry for " + client + "; using the conservative profile");
        return new PlayerSource(client.name() + "@0", client, APP_VISITOR, NONE,
                client.isTVClient() || client.isEmbedded() ? PlayerSource.Xhr.FALSE : PlayerSource.Xhr.TRUE,
                client.isReelClient() ? REEL_ITEM_WATCH : PLAYER, SPECULATIVE,
                Collections.<PlayerSource.Delivery>emptySet(), "unreviewed");
    }

    /** A source by its durable id ("VISIONOS@1"), or null for an id this build does not know. */
    @Nullable
    public static PlayerSource byId(@Nullable String id) {
        return id != null ? BY_ID.get(id) : null;
    }

    /** Every declared source, in declaration order. */
    @NonNull
    public static List<PlayerSource> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** Whether {@code client} has a reviewed entry. */
    public static boolean covers(@NonNull AppClient client) {
        return DEFAULTS.containsKey(client);
    }
}
