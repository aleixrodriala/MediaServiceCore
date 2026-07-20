package com.liskovsoft.mediaserviceinterfaces.data;

/**
 * Session event emitted by the cast sender's status channel.<br/>
 * {@code state} uses {@link com.liskovsoft.mediaserviceinterfaces.RemoteControlService} STATE_* values.
 */
public final class CastEvent {
    public static final int TYPE_CONNECTED = 1;
    public static final int TYPE_NOW_PLAYING = 2;
    public static final int TYPE_STATE_CHANGE = 3;
    public static final int TYPE_VOLUME_CHANGE = 4;
    public static final int TYPE_DISCONNECTED = 5;

    private final int mType;
    private final String mVideoId;
    private final long mPositionMs;
    private final long mDurationMs;
    private final int mState;
    private final int mVolume;
    private final String mReason;

    private CastEvent(int type, String videoId, long positionMs, long durationMs, int state, int volume, String reason) {
        mType = type;
        mVideoId = videoId;
        mPositionMs = positionMs;
        mDurationMs = durationMs;
        mState = state;
        mVolume = volume;
        mReason = reason;
    }

    public static CastEvent connected() {
        return new CastEvent(TYPE_CONNECTED, null, -1, -1, -1, -1, null);
    }

    public static CastEvent nowPlaying(String videoId, long positionMs, long durationMs, int state) {
        return new CastEvent(TYPE_NOW_PLAYING, videoId, positionMs, durationMs, state, -1, null);
    }

    public static CastEvent stateChange(long positionMs, long durationMs, int state) {
        return new CastEvent(TYPE_STATE_CHANGE, null, positionMs, durationMs, state, -1, null);
    }

    public static CastEvent volumeChange(int volume) {
        return new CastEvent(TYPE_VOLUME_CHANGE, null, -1, -1, -1, volume, null);
    }

    public static CastEvent disconnected(String reason) {
        return new CastEvent(TYPE_DISCONNECTED, null, -1, -1, -1, -1, reason);
    }

    public int getType() {
        return mType;
    }

    public String getVideoId() {
        return mVideoId;
    }

    public long getPositionMs() {
        return mPositionMs;
    }

    public long getDurationMs() {
        return mDurationMs;
    }

    public int getState() {
        return mState;
    }

    public int getVolume() {
        return mVolume;
    }

    public String getReason() {
        return mReason;
    }
}
