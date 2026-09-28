package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import androidx.annotation.NonNull;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.Set;

/**
 * NEWTUBE(source-catalog): one way of asking /player - an {@link AppClient} (upstream's identity:
 * client name, version, user agent, device fields) plus the decisions NewTube makes about how that
 * client is asked. See {@link PlayerSourceCatalog} and netbench DESIGN.md section 3.1.
 *
 * <p>Immutable and free of runtime state: tokens, credentials, the signed-in state and debug
 * overrides are inputs to the code that reads a profile, never part of it. The {@link #id} is the
 * durable name of a source; one client may later have several sources (TV_TIZEN asked anonymously
 * and as the account route), so nothing durable may key on the client alone.
 */
public final class PlayerSource {
    /** Where the request's visitor (context.client.visitorData, X-Goog-Visitor-Id) comes from. */
    public enum Identity {
        /** The app's persistent visitor from youtube.com/tv (AppService). */
        APP_VISITOR,
        /** The web BotGuard session's visitor (PoTokenGate); the one a web token is minted for. */
        WEB_SESSION,
        /**
         * The embed page's visitor together with the encryptedHostFlags bound to it
         * (YtCfgService.EmbedIdentity); both or neither. When the page cannot be fetched the request
         * goes out on the web session's visitor with no flags (and is refused, 152-18).
         */
        EMBED_PAGE
    }

    /** Which PO token this source may carry. Evaluated per use by PoTokenSelection. */
    public enum TokenPolicy {
        /** A web token: bound to the video when there is one, else the session token. */
        WEB,
        /** None on media URLs; a video-bound web token in the /player body only when opted in. */
        PLAYER_REQUEST_OPT_IN,
        NONE
    }

    /** playbackContext.devicePlaybackCapabilities.supportXhr, unless a debug override applies. */
    public enum Xhr {
        TRUE,
        FALSE,
        /** The devicePlaybackCapabilities block is left out (yt-dlp's shape). */
        ABSENT
    }

    /** The request shape. */
    public enum Endpoint {
        /** POST youtubei/v1/player. */
        PLAYER,
        /** POST youtubei/v1/reel/reel_item_watch, the player request wrapped in playerRequest. */
        REEL_ITEM_WATCH,
        /** GET watch?v=..., ytInitialPlayerResponse scraped from the page. Not a /player call. */
        WATCH_PAGE
    }

    /**
     * The per-attempt deadline class (VideoInfoService.attemptTimeoutMsFor). The account route and
     * the authenticated head get a longer one first, by walk role, whatever this says.
     */
    public enum Budget {
        /** A fast speculative client: fail over early. */
        SPECULATIVE,
        /**
         * Long enough for a cold BotGuard mint or the embed-page fetch that precede the request
         * (WEB_EMBED has this budget for its page although it carries no token).
         */
        WEB_POT
    }

    /**
     * A way to play an answer whose adaptive formats carry no URLs (SABR-only), without SABR. Which
     * of these a source's answers were measured to play is an observation; whether the app uses it
     * is a switch (VodDelivery).
     */
    public enum Delivery {
        /** The answer's hlsManifestUrl, for a video that is not live. */
        HLS,
        /** The answer's formats (muxed, 360p at most): the "legacy codecs" url list. */
        PROGRESSIVE
    }

    @NonNull public final String id;
    @NonNull public final AppClient client;
    @NonNull public final Identity identity;
    @NonNull public final TokenPolicy token;
    @NonNull public final Xhr xhr;
    @NonNull public final Endpoint endpoint;
    @NonNull public final Budget budget;
    /** Deliveries this source's SABR-only answers were measured to play (unmodifiable). */
    @NonNull public final Set<Delivery> fallbacks;
    /** Why this profile is what it is: runs, captures, commits. Not read by code. */
    @NonNull public final String evidence;

    PlayerSource(@NonNull String id, @NonNull AppClient client, @NonNull Identity identity,
            @NonNull TokenPolicy token, @NonNull Xhr xhr, @NonNull Endpoint endpoint,
            @NonNull Budget budget, @NonNull Set<Delivery> fallbacks, @NonNull String evidence) {
        this.id = id;
        this.client = client;
        this.identity = identity;
        this.token = token;
        this.xhr = xhr;
        this.endpoint = endpoint;
        this.budget = budget;
        this.fallbacks = fallbacks;
        this.evidence = evidence;
    }

    @NonNull
    @Override
    public String toString() {
        return id;
    }
}
