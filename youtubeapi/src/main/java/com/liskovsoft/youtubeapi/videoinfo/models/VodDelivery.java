package com.liskovsoft.youtubeapi.videoinfo.models;

import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSource;
import com.liskovsoft.youtubeapi.videoinfo.V2.sources.PlayerSourceCatalog;

/**
 * NEWTUBE(delivery): what the app may play instead when an answer's adaptive formats carry no URLs
 * (SABR-only) - the sibling of {@link SabrVodCapability}, and like it a decoder capability rather
 * than a way to override the server's playability.
 *
 * <p>Some answers are SABR-only for adaptive yet carry an HLS manifest for the same video: WEB_EMBED
 * answered wGltuo1B1sM (made for kids) that way on the Pixel on 2026-09-28, and the app, which only
 * used HLS for live, fell to the one progressive stream. HLS is accepted only from a source whose
 * catalog entry lists it ({@link PlayerSource#fallbacks}, measured), and only while the switch is on
 * (phase 5 of netbench DESIGN.md: off until decoded playback on the device passes). Accepted, the
 * answer is playable for the walk and the loader opens its HLS manifest (behind the readiness gate).
 */
public final class VodDelivery {
    private static volatile boolean sHls;

    private VodDelivery() {
    }

    /** Phone switch, set from MobileMainApplication. */
    public static void setHlsEnabled(boolean enabled) {
        sHls = enabled;
    }

    public static boolean isHlsEnabled() {
        return sHls;
    }

    /**
     * Whether {@code info} - an OK answer for a video that is not live, from a source measured to
     * play its HLS - plays over its HLS manifest when its adaptive formats cannot be used.
     */
    public static boolean acceptsHls(VideoInfo info) {
        if (!sHls || info == null || !"OK".equals(info.getRawPlayabilityStatus())
                || info.isBotCheckRequired() || info.getHlsManifestUrl() == null
                || info.getClient() == null || info.isLive()
                || info.getVideoDetails() != null && info.getVideoDetails().isLiveContent()) {
            return false;
        }
        return PlayerSourceCatalog.defaultFor(info.getClient()).fallbacks.contains(PlayerSource.Delivery.HLS);
    }
}
