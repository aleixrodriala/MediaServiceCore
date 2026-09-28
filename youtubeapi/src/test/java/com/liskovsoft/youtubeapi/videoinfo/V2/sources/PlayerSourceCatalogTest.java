package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.liskovsoft.youtubeapi.app.PoTokenSelectionKt;
import com.liskovsoft.youtubeapi.app.PoTokenSource;
import com.liskovsoft.youtubeapi.app.PoTokenUse;
import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Budget;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Endpoint;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Identity;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.TokenPolicy;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource.Xhr;

import org.junit.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The catalog in three ways: it covers every client once; it equals a table written out by hand
 * (the reviewed intent); and it equals the predicates the request code used before the catalog, so
 * redirecting a consumer to it cannot change a request (PlayerRequestGoldenTest checks the bytes).
 */
public class PlayerSourceCatalogTest {
    /** client -> identity token xhr endpoint budget, as reviewed on 2026-09-28. */
    private static final Map<AppClient, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put(AppClient.TV, "APP_VISITOR NONE FALSE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.TV_LEGACY, "APP_VISITOR NONE FALSE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.TV_EMBED, "APP_VISITOR NONE FALSE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.TV_SIMPLY, "APP_VISITOR NONE FALSE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.TV_KIDS, "APP_VISITOR NONE FALSE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.TV_DOWNGRADED, "APP_VISITOR NONE FALSE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.WEB, "WEB_SESSION WEB TRUE PLAYER WEB_POT");
        EXPECTED.put(AppClient.WEB_EMBED, "EMBED_PAGE NONE FALSE PLAYER WEB_POT");
        EXPECTED.put(AppClient.WEB_CREATOR, "APP_VISITOR NONE TRUE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.WEB_MUSIC, "APP_VISITOR NONE TRUE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.WEB_SAFARI, "WEB_SESSION WEB TRUE PLAYER WEB_POT");
        EXPECTED.put(AppClient.MWEB, "WEB_SESSION WEB TRUE PLAYER WEB_POT");
        EXPECTED.put(AppClient.ANDROID, "APP_VISITOR NONE TRUE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.ANDROID_SDK_LESS, "APP_VISITOR NONE TRUE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.ANDROID_REEL, "APP_VISITOR NONE TRUE REEL_ITEM_WATCH SPECULATIVE");
        EXPECTED.put(AppClient.ANDROID_VR, "WEB_SESSION PLAYER_REQUEST_OPT_IN TRUE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.IOS, "APP_VISITOR NONE TRUE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.INITIAL, "WEB_SESSION WEB TRUE WATCH_PAGE WEB_POT");
        EXPECTED.put(AppClient.GEO, "WEB_SESSION WEB TRUE PLAYER WEB_POT");
        EXPECTED.put(AppClient.VISIONOS, "WEB_SESSION NONE TRUE PLAYER SPECULATIVE");
        EXPECTED.put(AppClient.TV_TIZEN, "APP_VISITOR NONE FALSE PLAYER SPECULATIVE");
    }

    @Test
    public void everyClientHasExactlyOneReviewedSource() {
        Set<String> ids = new HashSet<>();
        for (AppClient client : AppClient.values()) {
            assertTrue(client + " has no catalog entry: decide its profile (a new upstream client?)",
                    PlayerSourceCatalog.covers(client));
            PlayerSource source = PlayerSourceCatalog.defaultFor(client);
            assertSame(client, source.client);
            assertEquals(client.name() + "@1", source.id);
            assertTrue("duplicate id " + source.id, ids.add(source.id));
            assertSame(source, PlayerSourceCatalog.byId(source.id));
        }
        assertEquals(AppClient.values().length, PlayerSourceCatalog.all().size());
        assertEquals(null, PlayerSourceCatalog.byId("VISIONOS@0"));
        assertEquals(null, PlayerSourceCatalog.byId(null));
    }

    @Test
    public void theCatalogIsTheReviewedTable() {
        assertEquals("the table covers every client", AppClient.values().length, EXPECTED.size());
        for (Map.Entry<AppClient, String> row : EXPECTED.entrySet()) {
            PlayerSource source = PlayerSourceCatalog.defaultFor(row.getKey());
            assertEquals(row.getKey().name(), row.getValue(), source.identity + " " + source.token
                    + " " + source.xhr + " " + source.endpoint + " " + source.budget);
        }
    }

    /** The expressions the request code used before the catalog existed. */
    @Test
    public void theCatalogEqualsThePredicatesItReplaces() {
        for (AppClient client : AppClient.values()) {
            PlayerSource source = PlayerSourceCatalog.defaultFor(client);

            // VideoInfoApiHelper: the embed identity for WEB_EMBED, else usesWebVisitorData.
            Identity identity = client == AppClient.WEB_EMBED ? Identity.EMBED_PAGE
                    : client.isWebPotRequired() || client == AppClient.ANDROID_VR
                            || client == AppClient.VISIONOS ? Identity.WEB_SESSION : Identity.APP_VISITOR;
            assertEquals(client.name(), identity, source.identity);

            // QueryBuilder.createDevicePlaybackCapabilities without a debug override.
            assertEquals(client.name(), !client.isTVClient() && !client.isEmbedded() ? Xhr.TRUE : Xhr.FALSE,
                    source.xhr);

            // VideoInfoService: the reel endpoint; INITIAL's page scrape.
            assertEquals(client.name(), client.isReelClient() ? Endpoint.REEL_ITEM_WATCH
                    : client == AppClient.INITIAL ? Endpoint.WATCH_PAGE : Endpoint.PLAYER, source.endpoint);

            // VideoInfoService.attemptTimeoutMsFor after the account-head rules.
            assertEquals(client.name(), client.isWebPotRequired() ? Budget.WEB_POT : Budget.SPECULATIVE,
                    source.budget);

            // PoTokenSelection, for every purpose, video id and opt-in.
            for (PoTokenUse use : PoTokenUse.values()) {
                for (boolean hasVideoId : new boolean[] {true, false}) {
                    for (boolean optIn : new boolean[] {true, false}) {
                        assertEquals(client + " " + use + " video=" + hasVideoId + " optIn=" + optIn,
                                legacyTokenSource(client, hasVideoId, use, optIn),
                                PoTokenSelectionKt.selectPoTokenSource(client, hasVideoId, use, optIn));
                    }
                }
            }
        }
    }

    /** PoTokenSelection.selectPoTokenSource as it read before the catalog. */
    private static PoTokenSource legacyTokenSource(AppClient client, boolean hasVideoId, PoTokenUse use,
            boolean playerPotEnabled) {
        if (client == AppClient.WEB_EMBED) {
            return PoTokenSource.NONE;
        }
        if (client.isWebPotRequired()) {
            return hasVideoId ? PoTokenSource.WEB_CONTENT : PoTokenSource.WEB_SESSION;
        }
        if (use == PoTokenUse.PLAYER_REQUEST && client.isPlayerPotSupported() && playerPotEnabled && hasVideoId) {
            return PoTokenSource.WEB_CONTENT;
        }
        return PoTokenSource.NONE;
    }
}
