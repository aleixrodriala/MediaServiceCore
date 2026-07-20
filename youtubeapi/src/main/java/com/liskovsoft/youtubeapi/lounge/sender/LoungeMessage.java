package com.liskovsoft.youtubeapi.lounge.sender;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * A single browserchannel message: {@code [index, ["<eventName>", <payload?>]]}.<br/>
 * The payload is either a JSON object (regular events) or a bare string
 * (session ids {@code "c"}/{@code "S"}).
 */
public class LoungeMessage {
    // Session ids (only at session start / rebind)
    public static final String TYPE_SESSION_ID = "c";
    public static final String TYPE_G_SESSION_ID = "S";
    // Keepalive
    public static final String TYPE_NOOP = "noop";
    // Status events
    public static final String TYPE_NOW_PLAYING = "nowPlaying";
    public static final String TYPE_ON_STATE_CHANGE = "onStateChange";
    public static final String TYPE_ON_VOLUME_CHANGED = "onVolumeChanged";
    // Session-terminating (name unverified by the reference sources — handled defensively)
    public static final String TYPE_LOUNGE_SCREEN_DISCONNECTED = "loungeScreenDisconnected";

    private final int mIndex;
    private final String mType;
    private final JsonObject mPayload;
    private final String mStringArg;

    public LoungeMessage(int index, String type, @Nullable JsonObject payload, @Nullable String stringArg) {
        mIndex = index;
        mType = type;
        mPayload = payload;
        mStringArg = stringArg;
    }

    public int getIndex() {
        return mIndex;
    }

    public String getType() {
        return mType;
    }

    @Nullable
    public JsonObject getPayload() {
        return mPayload;
    }

    @Nullable
    public String getStringArg() {
        return mStringArg;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format("Index: %s, Type: %s", mIndex, mType);
    }
}
