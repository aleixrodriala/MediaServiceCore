package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.AuthRouteQuarantineBook.Record;
import com.liskovsoft.youtubeapi.videoinfo.V2.AuthRouteQuarantineBook.Streak;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialization for the account-route quarantine, split out of {@link VideoInfoService} so the
 * decisions it encodes can be tested without a network or a device.
 *
 * <p>Current format (v3): {@code v3|<records>|<streaks>}, each section a {@code ;}-joined list
 * that may be empty.
 * <ul>
 *   <li>record, one per (transport, client) quarantine, strike memory included - the v2 entry,
 *       plus the keys of the videos whose no-media verdicts armed it, comma-separated (one key in
 *       the first v3 builds): {@code transport:CLIENT:expiryWallMs:strikes:armedWallMs[:key,...]}.
 *       An entry whose expiry
 *       is in the past is still worth storing while its strike memory lasts - that is what lets the
 *       next failure escalate instead of starting again from ten minutes, and what puts the client
 *       on probation (see {@link AuthRouteQuarantineBook#noteNoMedia}).</li>
 *   <li>streak, one per (transport, client) no-media streak that has not yet added up to a
 *       quarantine: {@code transport:CLIENT:hits:lastHitWallMs:videoKey}. The video key is a hex
 *       hash of the last counted videoId, never the id: dedupe only needs equality, and the value
 *       then carries no watch history.</li>
 * </ul>
 *
 * <p>Still read: v2 ({@code v2|<records>}, the 2026-09 unreleased round - records only, no
 * streaks), and legacy (1.9.0 and earlier) {@code network|CLIENT:expiryWallMs[;...]}, where
 * network was {@code transport:netIdHash}. Legacy is mapped onto its transport, one strike, and an
 * arming time derived from the fixed 10-minute cooldown every legacy entry was written with. None
 * can be confused: a legacy network key always carries a {@code :}, and never starts with a
 * version tag. (An older build finds no transport named {@code v3} and restores nothing, so a
 * downgrade quarantines less, never more.)
 *
 * <p>Two things are deliberate. Deadlines are held in memory against {@code elapsedRealtime} but
 * stored as WALL clock, because {@code elapsedRealtime} restarts at every boot and a snapshot has
 * to outlive the process that wrote it. And decoding is defensive in one direction only: an entry
 * that is unparsable, forgotten, or for a client outside the allowed set is dropped, and neither a
 * forward clock jump nor a hand-edited value can stretch a cooldown past its strike level's TTL,
 * and a stored streak is at most one hit short of a quarantine and is dropped rather than clamped
 * when its time is in the future. (What a snapshot cannot prevent is a hand-written value that is
 * simply plausible - one fresh hit, one current strike - which is no more than the app itself
 * could have written.)
 */
final class AuthRouteQuarantineSnapshot {
    static final String CURRENT_PREFIX = "v3|";
    static final String V2_PREFIX = "v2|";
    private static final int MAX_VIDEO_KEY_LENGTH = 16;

    /** Everything a snapshot restores. */
    static final class Decoded {
        final List<Record> records;
        final List<Streak> streaks;

        Decoded(List<Record> records, List<Streak> streaks) {
            this.records = records;
            this.streaks = streaks;
        }

        boolean isEmpty() {
            return records.isEmpty() && streaks.isEmpty();
        }
    }

    private AuthRouteQuarantineSnapshot() {
    }

    /** Records only; see {@link #encode(Collection, Collection, long, long)}. */
    @Nullable
    static String encode(Collection<Record> records, long nowElapsedMs, long nowWallMs) {
        return encode(records, Collections.<Streak>emptyList(), nowElapsedMs, nowWallMs);
    }

    /** @return the snapshot, or null when there is nothing left worth storing */
    @Nullable
    static String encode(Collection<Record> records, Collection<Streak> streaks,
            long nowElapsedMs, long nowWallMs) {
        StringBuilder recordEntries = new StringBuilder();
        for (Record record : records) {
            if (!record.isLive(nowElapsedMs) && !record.remembers(nowWallMs)) {
                continue;
            }
            if (recordEntries.length() > 0) {
                recordEntries.append(';');
            }
            long expiryWallMs = nowWallMs + (record.untilElapsedMs - nowElapsedMs);
            recordEntries.append(record.transport).append(':')
                    .append(record.client.name()).append(':')
                    .append(expiryWallMs).append(':')
                    .append(record.strikes).append(':')
                    .append(record.armedWallMs);
            StringBuilder keys = new StringBuilder();
            for (String key : record.armingVideoKeys) {
                if (isVideoKey(key)) {
                    keys.append(keys.length() > 0 ? "," : "").append(key);
                }
            }
            if (keys.length() > 0) {
                recordEntries.append(':').append(keys);
            }
        }

        StringBuilder streakEntries = new StringBuilder();
        for (Streak streak : streaks) {
            if (!streak.isFresh(nowWallMs) || !isVideoKey(streak.lastVideoKey)) {
                continue;
            }
            if (streakEntries.length() > 0) {
                streakEntries.append(';');
            }
            streakEntries.append(streak.transport).append(':')
                    .append(streak.client.name()).append(':')
                    .append(streak.hits).append(':')
                    .append(streak.lastHitWallMs).append(':')
                    .append(streak.lastVideoKey);
        }

        if (recordEntries.length() == 0 && streakEntries.length() == 0) {
            return null;
        }
        return CURRENT_PREFIX + recordEntries + '|' + streakEntries;
    }

    static boolean isCurrentFormat(@Nullable String snapshot) {
        return snapshot != null && snapshot.startsWith(CURRENT_PREFIX);
    }

    /** {@code v3}, {@code v2}, {@code legacy} or {@code none}: for the restore log line. */
    static String formatName(@Nullable String snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return "none";
        }
        if (isCurrentFormat(snapshot)) {
            return "v3";
        }
        return snapshot.startsWith(V2_PREFIX) ? "v2" : "legacy";
    }

    /**
     * Quarantine records only. See {@link #decodeAll}.
     */
    static List<Record> decode(@Nullable String snapshot, AppClient[] allowed, long nowElapsedMs,
            long nowWallMs) {
        return decodeAll(snapshot, allowed, nowElapsedMs, nowWallMs).records;
    }

    /**
     * @param allowed the only clients a snapshot may name
     * @return records with deadlines on the {@code elapsedRealtime} clock and streaks still inside
     *         their window, for every transport; empty when nothing survives. Which transport
     *         applies is the caller's business.
     */
    static Decoded decodeAll(@Nullable String snapshot, AppClient[] allowed, long nowElapsedMs,
            long nowWallMs) {
        if (snapshot == null || snapshot.isEmpty()) {
            return new Decoded(Collections.<Record>emptyList(), Collections.<Streak>emptyList());
        }

        Map<String, Record> records = new LinkedHashMap<>();
        Map<String, Streak> streaks = new LinkedHashMap<>();
        if (isCurrentFormat(snapshot) || snapshot.startsWith(V2_PREFIX)) {
            boolean v3 = isCurrentFormat(snapshot);
            String body = snapshot.substring(v3 ? CURRENT_PREFIX.length() : V2_PREFIX.length());
            String[] sections = v3 ? body.split("\\|", -1) : new String[] {body};
            if (v3 && sections.length > 2) {
                // Not something this build wrote: trust none of it.
                return new Decoded(Collections.<Record>emptyList(),
                        Collections.<Streak>emptyList());
            }
            for (String entry : sections[0].split(";")) {
                Record record = decodeEntry(entry, allowed, nowElapsedMs, nowWallMs);
                if (record != null) {
                    records.put(record.transport + '/' + record.client.name(), record);
                }
            }
            if (sections.length > 1) {
                for (String entry : sections[1].split(";")) {
                    Streak streak = decodeStreak(entry, allowed, nowWallMs);
                    if (streak != null) {
                        streaks.put(streak.transport + '/' + streak.client.name(), streak);
                    }
                }
            }
        } else {
            for (Record record : decodeLegacy(snapshot, allowed, nowElapsedMs, nowWallMs)) {
                records.put(record.transport + '/' + record.client.name(), record);
            }
        }
        return new Decoded(new ArrayList<>(records.values()), new ArrayList<>(streaks.values()));
    }

    @Nullable
    private static Record decodeEntry(String entry, AppClient[] allowed, long nowElapsedMs,
            long nowWallMs) {
        String[] fields = entry.split(":", -1);
        List<String> armingVideoKeys = fields.length == 6
                ? decodeVideoKeys(fields[5]) : Collections.<String>emptyList();
        if ((fields.length != 5 && fields.length != 6) || !isTransport(fields[0])
                || armingVideoKeys == null) {
            return null;
        }
        AppClient client = find(allowed, fields[1]);
        if (client == null) {
            return null;
        }

        long expiryWallMs;
        int strikes;
        long armedWallMs;
        try {
            expiryWallMs = Long.parseLong(fields[2]);
            strikes = Integer.parseInt(fields[3]);
            armedWallMs = Long.parseLong(fields[4]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (strikes < 1) {
            return null;
        }
        return restored(fields[0], client, expiryWallMs,
                AuthRouteQuarantineBook.clampStrikes(strikes), armedWallMs, armingVideoKeys,
                nowElapsedMs, nowWallMs);
    }

    /** 1 to MAX_ARMING_VIDEOS well-formed keys, or null for anything else. */
    @Nullable
    private static List<String> decodeVideoKeys(String field) {
        String[] keys = field.split(",", -1);
        if (keys.length > AuthRouteQuarantineBook.MAX_ARMING_VIDEOS) {
            return null;
        }
        for (String key : keys) {
            if (!isVideoKey(key)) {
                return null;
            }
        }
        return Arrays.asList(keys);
    }

    /**
     * One stored streak, or null if it is unreadable or its last hit is outside the window, in the
     * future or not a plausible time. The hit count is clamped to one short of a quarantine, so no
     * stored streak can quarantine a client on its own.
     */
    @Nullable
    private static Streak decodeStreak(String entry, AppClient[] allowed, long nowWallMs) {
        String[] fields = entry.split(":", -1);
        if (fields.length != 5 || !isTransport(fields[0]) || !isVideoKey(fields[4])) {
            return null;
        }
        AppClient client = find(allowed, fields[1]);
        if (client == null) {
            return null;
        }

        int hits;
        long lastHitWallMs;
        try {
            hits = Integer.parseInt(fields[2]);
            lastHitWallMs = Long.parseLong(fields[3]);
        } catch (NumberFormatException e) {
            return null;
        }
        // A last hit in the future (or at/before the epoch, where the age arithmetic would
        // overflow) is not clamped into fresh evidence - it is dropped.
        if (hits < 1 || lastHitWallMs <= 0 || lastHitWallMs > nowWallMs) {
            return null;
        }
        Streak streak = new Streak(fields[0], client,
                Math.min(hits, AuthRouteQuarantineBook.NO_MEDIA_MIN_HITS - 1), fields[4],
                lastHitWallMs);
        return streak.isFresh(nowWallMs) ? streak : null;
    }

    private static List<Record> decodeLegacy(String snapshot, AppClient[] allowed,
            long nowElapsedMs, long nowWallMs) {
        int split = snapshot.indexOf('|');
        if (split <= 0) {
            return Collections.emptyList();
        }
        String network = snapshot.substring(0, split);
        int mark = network.indexOf(':');
        String transport = mark >= 0 ? network.substring(0, mark) : network;
        if (!isTransport(transport)) {
            return Collections.emptyList();
        }

        List<Record> result = new ArrayList<>();
        for (String entry : snapshot.substring(split + 1).split(";")) {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0) {
                continue;
            }
            AppClient client = find(allowed, entry.substring(0, colon));
            if (client == null) {
                continue;
            }
            long expiryWallMs;
            try {
                expiryWallMs = Long.parseLong(entry.substring(colon + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            // Every legacy entry was armed with the fixed 10-minute cooldown - one strike.
            Record record = restored(transport, client, expiryWallMs, 1,
                    expiryWallMs - AuthRouteQuarantineBook.BASE_TTL_MS,
                    Collections.<String>emptyList(), nowElapsedMs, nowWallMs);
            if (record != null) {
                result.add(record);
            }
        }
        return result;
    }

    /**
     * Rebases one stored entry onto this process's clocks, or null if it is no longer worth
     * holding. The remaining cooldown is clamped to its strike level's TTL and the arming time to
     * now: a clock that jumped while the app was dead can shorten a quarantine or its strike
     * memory, never extend either.
     */
    @Nullable
    private static Record restored(String transport, AppClient client, long expiryWallMs,
            int strikes, long armedWallMs, List<String> armingVideoKeys, long nowElapsedMs,
            long nowWallMs) {
        long remainingMs = expiryWallMs - nowWallMs;
        long untilElapsedMs = remainingMs > 0
                ? nowElapsedMs + Math.min(remainingMs,
                        AuthRouteQuarantineBook.ttlMsForStrikes(strikes))
                : nowElapsedMs;
        Record record = new Record(transport, client, untilElapsedMs, strikes,
                Math.min(armedWallMs, nowWallMs), armingVideoKeys);
        return record.isLive(nowElapsedMs) || record.remembers(nowWallMs) ? record : null;
    }

    /** Lowercase letters only: every transport activeTransportKey() produces, and nothing else. */
    private static boolean isTransport(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    /** Lowercase hex, short: what VideoInfoService#noMediaVideoKey produces, and nothing else. */
    static boolean isVideoKey(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_VIDEO_KEY_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static AppClient find(AppClient[] allowed, String name) {
        for (AppClient client : allowed) {
            if (client.name().equals(name)) {
                return client;
            }
        }
        return null;
    }
}
