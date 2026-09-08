package com.liskovsoft.youtubeapi.videoinfo.models;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

/** Opt-in decoder capability, not a new metadata route or a way to override server playability. */
public final class SabrVodCapability {
    private static volatile boolean enabled;
    private SabrVodCapability() {}

    public static void setEnabled(boolean supported) { enabled = supported; }
    public static boolean isEnabled() { return enabled; }
    public static boolean accepts(VideoInfo info) { return enabled && isEligible(info); }

    /**
     * Whether this already-permitted response can be played over SABR.
     *
     * Gated on {@link AppClient#isSabrSupported()} - the clients whose SABR endpoint was MEASURED
     * to serve media - and deliberately NOT on the account. The first implementation required an
     * authenticated, server-logged-in TV response, which restricted SABR to exactly the one route
     * whose media is dead: every TVHTML5 SABR POST returns HTTP 403 with an empty body, reproduced
     * off-device on 2026-09-08 from a different network and an anonymous identity, so it is a
     * property of the client rather than of the phone, its account or its carrier. Anonymous
     * VISIONOS - the client the phone's ring actually wins with - serves the same request at
     * HTTP 200. Requiring auth here could therefore only ever select the failing route.
     */
    public static boolean isEligible(VideoInfo info) {
        if (info == null || !"OK".equals(info.getRawPlayabilityStatus()) || info.isBotCheckRequired()
                || info.getClient() == null || !info.getClient().isSabrSupported()
                || info.getVideoDetails() == null || info.isLive() || info.getVideoDetails().isLiveContent()
                || info.getAdaptiveFormats() == null || info.getAdaptiveFormats().isEmpty()
                || info.getServerAbrStreamingUrl() == null || info.getServerAbrStreamingUrl().isEmpty()
                || info.getVideoPlaybackUstreamerConfig() == null || info.getVideoPlaybackUstreamerConfig().isEmpty()) {
            return false;
        }
        boolean audio = false;
        boolean video = false;
        for (com.liskovsoft.youtubeapi.videoinfo.models.formats.VideoFormat format : info.getAdaptiveFormats()) {
            if (format == null || format.isOTF() || format.getITag() <= 0 || format.getMimeType() == null) continue;
            try { if (Long.parseLong(format.getLastModified()) <= 0) continue; }
            catch (RuntimeException ignored) { continue; }
            String mime = format.getMimeType();
            audio |= mime.startsWith("audio/mp4;") || mime.startsWith("audio/webm;");
            video |= mime.startsWith("video/mp4;") || mime.startsWith("video/webm;");
        }
        if (!audio || !video) return false;
        String id = info.getVideoDetails().getVideoId();
        if (id == null || !id.matches("[A-Za-z0-9_-]{11}")) return false;
        try {
            long duration = Long.parseLong(info.getVideoDetails().getLengthSeconds());
            return duration > 0 && duration <= 7 * 24 * 60 * 60;
        } catch (RuntimeException ignored) { return false; }
    }
}
