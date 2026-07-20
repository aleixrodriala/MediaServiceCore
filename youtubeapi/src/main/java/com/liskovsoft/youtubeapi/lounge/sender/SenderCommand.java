package com.liskovsoft.youtubeapi.lounge.sender;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One outgoing Lounge command and its form encoding:<br/>
 * {@code count=1&ofs=N&req0__sc=<name>&req0_<field>=<value>...}<br/>
 * NOTE: DOUBLE underscore before "sc", single underscore for argument fields.
 * Getting this wrong fails silently.<br/>
 * All times on the wire are SECONDS (fractional allowed); this class converts from ms.
 */
public class SenderCommand {
    private static final String FIELD_COUNT = "count";
    private static final String FIELD_OFS = "ofs";
    private static final String FIELD_COMMAND_NAME = "req0__sc"; // double underscore!
    private static final String FIELD_ARG_PREFIX = "req0_";

    private final String mName;
    private final Map<String, String> mArgs = new LinkedHashMap<>();

    private SenderCommand(String name) {
        mName = name;
    }

    /**
     * Replace the queue with a single video and start playing (ytcast field shape:
     * currentIndex=0 + explicit videoIds).
     */
    public static SenderCommand setPlaylist(String videoId, long positionMs) {
        SenderCommand command = new SenderCommand("setPlaylist");
        command.mArgs.put("videoId", videoId);
        command.mArgs.put("currentTime", toSeconds(positionMs));
        command.mArgs.put("currentIndex", "0");
        command.mArgs.put("videoIds", videoId);
        return command;
    }

    public static SenderCommand play() {
        return new SenderCommand("play");
    }

    public static SenderCommand pause() {
        return new SenderCommand("pause");
    }

    public static SenderCommand seekTo(long positionMs) {
        SenderCommand command = new SenderCommand("seekTo");
        command.mArgs.put("newTime", toSeconds(positionMs));
        return command;
    }

    /**
     * @param volume absolute 0-100
     */
    public static SenderCommand setVolume(int volume) {
        SenderCommand command = new SenderCommand("setVolume");
        command.mArgs.put("volume", String.valueOf(volume));
        return command;
    }

    public static SenderCommand stopVideo() {
        return new SenderCommand("stopVideo");
    }

    public String getName() {
        return mName;
    }

    /**
     * @param ofs index this message occupies in the session-global count of messages
     *            this client has sent (starts at 0, advances by count per POST)
     */
    public Map<String, String> encode(int ofs) {
        Map<String, String> result = new LinkedHashMap<>();

        result.put(FIELD_COUNT, "1");
        result.put(FIELD_OFS, String.valueOf(ofs));
        result.put(FIELD_COMMAND_NAME, mName);

        for (Map.Entry<String, String> entry : mArgs.entrySet()) {
            result.put(FIELD_ARG_PREFIX + entry.getKey(), entry.getValue());
        }

        return result;
    }

    /**
     * ms to seconds string, fractional only when needed (e.g. 95500 -> "95.5", 60000 -> "60").
     */
    static String toSeconds(long ms) {
        if (ms < 0) {
            ms = 0;
        }

        return ms % 1_000 == 0 ? String.valueOf(ms / 1_000) : String.valueOf(ms / 1_000d);
    }
}
