package com.liskovsoft.mediaserviceinterfaces;

import com.liskovsoft.mediaserviceinterfaces.data.CastEvent;
import com.liskovsoft.mediaserviceinterfaces.data.CastScreen;

import io.reactivex.rxjava3.core.Observable;

/**
 * Sender side of the YouTube Lounge protocol: pair with a TV's YouTube app
 * (or a SmartTube receiver) and drive playback on it.<br/>
 * Counterpart of {@link RemoteControlService}, which is the receiver side.
 */
public interface CastSenderService {
    /**
     * Exchange a manual "Link with TV code" (12 digits, dashes optional) for a screen.
     * One-shot; emits the screen or errors.
     */
    Observable<CastScreen> pairWithCodeObserve(String tvCode);

    /**
     * Open a session on the screen and stream its status events.
     * Long-lived: emits {@link CastEvent}s until disposed; disposing tears the session down.
     * Command observables below are only valid while a connect subscription is active.
     */
    Observable<CastEvent> connectObserve(CastScreen screen);

    Observable<Void> loadVideoObserve(String videoId, long positionMs);

    Observable<Void> playObserve();

    Observable<Void> pauseObserve();

    Observable<Void> seekToObserve(long positionMs);

    Observable<Void> setVolumeObserve(int volume);

    Observable<Void> stopVideoObserve();

    boolean isConnected();
}
