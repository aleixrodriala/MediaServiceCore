package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * NEWTUBE(kids-channel): the channels whose videos VISIONOS and ANDROID_VR refuse and the account
 * route (TV_TIZEN) serves - a route pattern, whose one known cause is "made for kids" (a paid
 * movie or a music-only video is refused the same way, but TV_TIZEN refuses those too, so they
 * prove nothing) - so that the next video of such a channel
 * asks TV_TIZEN first ({@link PhoneSourcePlanner.Context#accountRouteHinted}) and skips the refusal
 * VISIONOS would give it: one /player request and its round trip (Pixel 9 first frame ~0.80 s
 * against ~0.55 s for an ordinary open; issue #5 was a child watching these videos).
 *
 * <p>Why a channel: no signed-out /player field says "made for kids" (netbench corpus.md: only the
 * watch page's miniplayer mode does), and VISIONOS words the refusal exactly like a paid movie or a
 * music-only video. The evidence is the walk itself - VISIONOS (or ANDROID_VR) refused the video on
 * its content and TV_TIZEN served it ({@link #remember}) - and "made for kids" is a channel
 * setting that videos inherit, so it predicts the channel's next video.
 *
 * <p>A channel can be mixed (a video set otherwise). A wrong hint asks TV_TIZEN first for an
 * ordinary video: it serves it (anonymously too: netbench recap, every ordinary category), one
 * request as today but a slower first frame (its signature solve) and one anonymous TV_TIZEN ask
 * the walk would not have made. So a record is good for {@link #HINTS_PER_PROOF} hinted opens; the
 * next open of the channel walks the lane's own order again ({@link Hint#REPROOF}), which either
 * proves it again (VISIONOS refuses, TV_TIZEN serves) or drops it (VISIONOS serves). Any answer
 * other than a serve from the hinted TV_TIZEN drops the record at once.
 *
 * <p>Process-local and bounded: {@link #CAPACITY} channels and {@link #VIDEO_CAPACITY} video notes,
 * least recently used out. Records are per lane (the account route is anonymous signed out and
 * carries the account signed in; the evidence of one lane is not the other's). {@link #clear}
 * forgets everything on an account change, and a walk that began before it cannot write
 * ({@link #generation}). Thread-safe: the app names channels from its own threads while a walk
 * reads them under VideoInfoService's monitor. Pure: no requests, no logging.
 */
public final class KidsChannelMemory {
    /** Channels remembered, both lanes together (one lane is live between account changes). */
    public static final int CAPACITY = 64;
    /** The channels the app named for the videos it is opening, preloading or just opened. */
    static final int VIDEO_CAPACITY = 32;
    /**
     * Hinted opens a proof buys. Bounds a wrong hint on a mixed channel to this many extra anonymous
     * TV_TIZEN asks per video that proved the channel (which asked TV_TIZEN once itself, as today),
     * at the cost of one VISIONOS round trip every {@code HINTS_PER_PROOF + 1} opens of a channel
     * that really is made for kids.
     */
    public static final int HINTS_PER_PROOF = 4;

    /** What an open of a video of a known channel does. */
    public enum Hint {
        /** Not remembered in this lane: the lane's order. */
        NONE,
        /** Remembered with hints left: the account route first (health permitting). */
        FIRST,
        /** Remembered, hints spent: the lane's order, whose outcome proves or drops the record. */
        REPROOF
    }

    /** A video's channel as the app named it, and a proof waiting for that name. */
    private static final class VideoNote {
        @Nullable
        String channelId;
        @Nullable
        PhoneSourcePlanner.Lane pendingLane;
        long pendingGeneration;
    }

    /** lane + channel id -> hints left. */
    private final Map<String, Integer> mChannels = lru(CAPACITY);
    private final Map<String, VideoNote> mVideos = lru(VIDEO_CAPACITY);
    private long mGeneration;

    /**
     * The app names {@code videoId}'s channel: before its /player (the card tapped, the next video)
     * or after it (/next). A later name replaces an earlier one. If a walk proved this video's
     * channel without being able to name it ({@link #rememberWhenNamed}), the channel is
     * remembered now, and its lane returned; otherwise null.
     */
    @Nullable
    public synchronized PhoneSourcePlanner.Lane noteVideoChannel(@Nullable String videoId,
            @Nullable String channelId) {
        if (isEmpty(videoId) || isEmpty(channelId)) {
            return null;
        }
        VideoNote note = mVideos.get(videoId);
        if (note == null) {
            note = new VideoNote();
            mVideos.put(videoId, note);
        }
        note.channelId = channelId;
        PhoneSourcePlanner.Lane lane = note.pendingLane;
        note.pendingLane = null;
        if (lane == null || note.pendingGeneration != mGeneration) {
            return null;
        }
        mChannels.put(key(lane, channelId), HINTS_PER_PROOF);
        return lane;
    }

    /** The channel the app named for {@code videoId}, or null. */
    @Nullable
    public synchronized String channelOf(@Nullable String videoId) {
        VideoNote note = videoId != null ? mVideos.get(videoId) : null;
        return note != null ? note.channelId : null;
    }

    /** Taken when a walk begins; {@link #remember} refuses a proof from before an account change. */
    public synchronized long generation() {
        return mGeneration;
    }

    /** What an open of a video of {@code channelId} does in {@code lane}; NONE for an unknown channel. */
    public synchronized Hint hintFor(PhoneSourcePlanner.Lane lane, @Nullable String channelId) {
        Integer left = isEmpty(channelId) ? null : mChannels.get(key(lane, channelId));
        if (left == null) {
            return Hint.NONE;
        }
        return left > 0 ? Hint.FIRST : Hint.REPROOF;
    }

    /**
     * A proof: in a walk of {@code lane} that began at {@code generation}, VISIONOS or ANDROID_VR
     * refused a video of {@code channelId} on its content and the account route served it. The
     * record gets {@link #HINTS_PER_PROOF} hints. False (nothing written) if the account changed
     * since the walk began.
     */
    public synchronized boolean remember(PhoneSourcePlanner.Lane lane, String channelId, long generation) {
        if (generation != mGeneration || isEmpty(channelId)) {
            return false;
        }
        mChannels.put(key(lane, channelId), HINTS_PER_PROOF);
        return true;
    }

    /**
     * A proof whose answers named no channel and whose channel the app has not named yet: kept on
     * the video until {@link #noteVideoChannel} names it (the /next answer, usually).
     */
    public synchronized void rememberWhenNamed(String videoId, PhoneSourcePlanner.Lane lane,
            long generation) {
        if (isEmpty(videoId) || generation != mGeneration) {
            return;
        }
        VideoNote note = mVideos.get(videoId);
        if (note == null) {
            note = new VideoNote();
            mVideos.put(videoId, note);
        }
        note.pendingLane = lane;
        note.pendingGeneration = generation;
    }

    /**
     * The hinted account route served, in a walk that began at {@code generation}: one hint spent.
     * The hints left, or -1 if not remembered (or forgotten since the walk began).
     */
    public synchronized int spendHint(PhoneSourcePlanner.Lane lane, @Nullable String channelId,
            long generation) {
        String key = key(lane, channelId);
        Integer left = isEmpty(channelId) || generation != mGeneration ? null : mChannels.get(key);
        if (left == null) {
            return -1;
        }
        int now = Math.max(0, left - 1);
        mChannels.put(key, now);
        return now;
    }

    /**
     * Forgets {@code channelId} in {@code lane}, on the evidence of a walk that began at
     * {@code generation} (a walk from before an account change has nothing left to drop); true if
     * it was remembered.
     */
    public synchronized boolean drop(PhoneSourcePlanner.Lane lane, @Nullable String channelId,
            long generation) {
        return !isEmpty(channelId) && generation == mGeneration
                && mChannels.remove(key(lane, channelId)) != null;
    }

    public synchronized boolean isRemembered(PhoneSourcePlanner.Lane lane, @Nullable String channelId) {
        return !isEmpty(channelId) && mChannels.containsKey(key(lane, channelId));
    }

    /** Hints left for {@code channelId} in {@code lane}, or -1 if not remembered. */
    public synchronized int hintsLeft(PhoneSourcePlanner.Lane lane, @Nullable String channelId) {
        Integer left = isEmpty(channelId) ? null : mChannels.get(key(lane, channelId));
        return left != null ? left : -1;
    }

    /**
     * A sign-in, account switch or sign-out: everything is forgotten (the records, the names, the
     * waiting proofs), and walks already in flight can no longer write. Returns the number of
     * channels that were remembered.
     */
    public synchronized int clear() {
        mGeneration++;
        int remembered = mChannels.size();
        mChannels.clear();
        mVideos.clear();
        return remembered;
    }

    /**
     * The NetPath name of a channel: the first 5 bytes of its SHA-256, like the visitor
     * fingerprint (VisitorFingerprint) - a child's channels are not written to the log in clear.
     */
    public static String tag(@Nullable String channelId) {
        if (isEmpty(channelId)) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(channelId.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                result.append(String.format("%02x", digest[i]));
            }
            return result.toString();
        } catch (Exception e) {
            return Integer.toHexString(channelId.hashCode());
        }
    }

    private static String key(PhoneSourcePlanner.Lane lane, @Nullable String channelId) {
        return lane.name() + "|" + channelId;
    }

    private static boolean isEmpty(@Nullable String value) {
        return value == null || value.isEmpty();
    }

    private static <V> Map<String, V> lru(final int capacity) {
        return new LinkedHashMap<String, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > capacity;
            }
        };
    }
}
