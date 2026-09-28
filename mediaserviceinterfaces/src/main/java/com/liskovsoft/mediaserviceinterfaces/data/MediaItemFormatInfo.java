package com.liskovsoft.mediaserviceinterfaces.data;

import io.reactivex.rxjava3.core.Observable;

import java.io.InputStream;
import java.util.List;

public interface MediaItemFormatInfo extends FormatInfoProvision {
    List<MediaFormat> getAdaptiveFormats();
    List<MediaFormat> getUrlFormats();
    List<MediaSubtitle> getSubtitles();
    String getHlsManifestUrl();
    String getDashManifestUrl();
    // video metadata
    String getLengthSeconds();
    String getTitle();
    String getAuthor();
    String getViewCount();
    String getDescription();
    String getVideoId();
    String getChannelId();
    boolean isLive();
    boolean isLiveContent();
    boolean containsMedia();
    boolean containsSabrFormats();
    /** Accepted ordinary VOD metadata a SABR decoder can consume. False for unknown implementations. */
    default boolean isSabrVodEligible() { return false; }

    /**
     * NEWTUBE(readiness): elapsedRealtime (ms) from which this answer's media may be requested - its
     * arrival plus the pre-roll wait it announces - or 0 for at once. googlevideo refuses the media
     * of an answer with pre-roll ads until then.
     */
    default long getMediaReadyAtMs() { return 0; }

    /** NEWTUBE(readiness): the pre-roll wait this answer announced, ms (0 = none). */
    default long getPrerollWaitMs() { return 0; }

    /**
     * NEWTUBE(delivery): this answer, not live and with no usable adaptive formats, is to be played
     * over its HLS manifest (the engine accepted it for that; see VodDelivery).
     */
    default boolean isHlsVodSelected() { return false; }
    boolean containsDashFormats();
    boolean containsHlsUrl();
    boolean containsDashUrl();
    boolean containsUrlFormats();
    boolean hasExtendedHlsFormats();
    float getVolumeLevel();
    InputStream createMpdStream();
    Observable<InputStream> createMpdStreamObservable();
    List<String> createUrlList();
    MediaItemStoryboard createStoryboard();
    boolean isUnplayable();
    boolean isUnknownError();
    boolean isBotCheckRequired();
    String getPlayabilityReason();
    boolean isStreamSeekable();
    /**
     * Stream start time in UTC (!!!).<br/>
     * E.g.: <b>2021-10-06T13:36:25+00:00</b>
     */
    String getStartTimestamp();
    String getUploadDate();
    /**
     * Stream start time in UNIX format.<br/>
     */
    long getStartTimeMs();
    /**
     * Number of the stream first segment
     */
    int getStartSegmentNum();
    /**
     * Precise segment duration.<br/>
     * Used inside live streams
     */
    int getSegmentDurationUs();
    String getPaidContentText();
    String getVideoPlaybackUstreamerConfig();
    String getServerAbrStreamingUrl();
    String getPoToken();
    String getVisitorCookie();
    ClientInfo getClientInfo();

    interface ClientInfo {
        String getClientName();
        String getClientVersion();
        String getOsName();
        String getOsVersion();
    }
}
