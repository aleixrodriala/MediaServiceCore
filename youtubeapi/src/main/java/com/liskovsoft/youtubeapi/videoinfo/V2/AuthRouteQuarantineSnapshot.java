package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;
import com.liskovsoft.youtubeapi.videoinfo.V2.AuthRouteQuarantineBook.Record;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialization for the account-route quarantine, split out of {@link VideoInfoService} so the
 * decisions it encodes can be tested without a network or a device.
 *
 * <p>Current format (v2), one entry per (transport, client) record, strike memory included:
 * {@code v2|transport:CLIENT:expiryWallMs:strikes:armedWallMs[;...]}. An entry whose expiry is in
 * the past is still worth storing while its strike memory lasts - that is what lets the next
 * failure escalate instead of starting again from ten minutes.
 *
 * <p>Legacy format (1.9.0 and earlier), still read: {@code network|CLIENT:expiryWallMs[;...]},
 * where network was {@code transport:netIdHash}. It is mapped onto its transport, one strike, and
 * an arming time derived from the fixed 10-minute cooldown every legacy entry was written with.
 * The two cannot be confused: a legacy network key always carries a {@code :}, never {@code v2}.
 * (A build that only knows the legacy format finds no client named {@code transport:CLIENT} in a
 * v2 value and restores nothing, so a downgrade quarantines less, never more.)
 *
 * <p>Two things are deliberate. Deadlines are held in memory against {@code elapsedRealtime} but
 * stored as WALL clock, because {@code elapsedRealtime} restarts at every boot and a snapshot has
 * to outlive the process that wrote it. And decoding is defensive in one direction only: an entry
 * that is unparsable, forgotten, or for a client outside the allowed set is dropped, and neither a
 * forward clock jump nor a hand-edited value can stretch a cooldown past its strike level's TTL,
 * so a stale or hand-edited snapshot can quarantine less than intended but never more.
 */
final class AuthRouteQuarantineSnapshot {
    static final String CURRENT_PREFIX = "v2|";

    private AuthRouteQuarantineSnapshot() {
    }

    /** @return the snapshot, or null when there is nothing left worth storing */
    @Nullable
    static String encode(Collection<Record> records, long nowElapsedMs, long nowWallMs) {
        StringBuilder entries = new StringBuilder();
        for (Record record : records) {
            if (!record.isLive(nowElapsedMs) && !record.remembers(nowWallMs)) {
                continue;
            }
            if (entries.length() > 0) {
                entries.append(';');
            }
            long expiryWallMs = nowWallMs + (record.untilElapsedMs - nowElapsedMs);
            entries.append(record.transport).append(':')
                    .append(record.client.name()).append(':')
                    .append(expiryWallMs).append(':')
                    .append(record.strikes).append(':')
                    .append(record.armedWallMs);
        }
        return entries.length() == 0 ? null : CURRENT_PREFIX + entries;
    }

    static boolean isCurrentFormat(@Nullable String snapshot) {
        return snapshot != null && snapshot.startsWith(CURRENT_PREFIX);
    }

    /**
     * @param allowed the only clients a snapshot may name
     * @return records with deadlines on the {@code elapsedRealtime} clock, for every transport;
     *         empty when nothing survives. Which transport applies is the caller's business.
     */
    static List<Record> decode(@Nullable String snapshot, AppClient[] allowed, long nowElapsedMs,
            long nowWallMs) {
        if (snapshot == null || snapshot.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Record> result = new LinkedHashMap<>();
        if (isCurrentFormat(snapshot)) {
            for (String entry : snapshot.substring(CURRENT_PREFIX.length()).split(";")) {
                Record record = decodeEntry(entry, allowed, nowElapsedMs, nowWallMs);
                if (record != null) {
                    result.put(record.transport + '/' + record.client.name(), record);
                }
            }
        } else {
            for (Record record : decodeLegacy(snapshot, allowed, nowElapsedMs, nowWallMs)) {
                result.put(record.transport + '/' + record.client.name(), record);
            }
        }
        return new ArrayList<>(result.values());
    }

    @Nullable
    private static Record decodeEntry(String entry, AppClient[] allowed, long nowElapsedMs,
            long nowWallMs) {
        String[] fields = entry.split(":", -1);
        if (fields.length != 5 || !isTransport(fields[0])) {
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
                AuthRouteQuarantineBook.clampStrikes(strikes), armedWallMs, nowElapsedMs,
                nowWallMs);
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
                    expiryWallMs - AuthRouteQuarantineBook.BASE_TTL_MS, nowElapsedMs, nowWallMs);
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
            int strikes, long armedWallMs, long nowElapsedMs, long nowWallMs) {
        long remainingMs = expiryWallMs - nowWallMs;
        long untilElapsedMs = remainingMs > 0
                ? nowElapsedMs + Math.min(remainingMs,
                        AuthRouteQuarantineBook.ttlMsForStrikes(strikes))
                : nowElapsedMs;
        Record record = new Record(transport, client, untilElapsedMs, strikes,
                Math.min(armedWallMs, nowWallMs));
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
