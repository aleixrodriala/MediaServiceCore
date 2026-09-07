package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Serialization for the account-route 403 quarantine, split out of {@link VideoInfoService} so the
 * decisions it encodes can be tested without a network or a device.
 *
 * <p>Format: {@code network|CLIENT:absoluteExpiryMs[;CLIENT:absoluteExpiryMs]}.
 *
 * <p>Two things are deliberate. Deadlines are held in memory against {@code elapsedRealtime} but
 * stored as WALL clock, because {@code elapsedRealtime} restarts at every boot and a snapshot has
 * to outlive the process that wrote it. And decoding is defensive in one direction only: an entry
 * that is unparsable, expired, for another network, or for a client outside the allowed set is
 * dropped, so a stale or hand-edited snapshot can quarantine less than intended but never more.
 */
final class AuthRouteQuarantineSnapshot {
    private AuthRouteQuarantineSnapshot() {
    }

    /**
     * @param deadlinesElapsedMs live quarantine deadlines on the {@code elapsedRealtime} clock
     * @return the snapshot, or null when there is nothing live left to store
     */
    @Nullable
    static String encode(@Nullable String network, Map<AppClient, Long> deadlinesElapsedMs,
            long nowElapsedMs, long nowWallMs) {
        if (network == null || deadlinesElapsedMs.isEmpty()) {
            return null;
        }

        StringBuilder entries = new StringBuilder();
        for (Map.Entry<AppClient, Long> entry : deadlinesElapsedMs.entrySet()) {
            long remainingMs = entry.getValue() - nowElapsedMs;
            if (remainingMs <= 0) {
                continue;
            }
            if (entries.length() > 0) {
                entries.append(';');
            }
            entries.append(entry.getKey().name()).append(':').append(nowWallMs + remainingMs);
        }
        return entries.length() == 0 ? null : network + "|" + entries;
    }

    /**
     * @param allowed the only clients a snapshot may name
     * @param maxRemainingMs cooldown ceiling: a clock that jumped forward while the app was dead
     *                       must not be able to extend a quarantine beyond its original length
     * @return deadlines on the {@code elapsedRealtime} clock; empty when nothing survives
     */
    static Map<AppClient, Long> decode(@Nullable String snapshot, @Nullable String currentNetwork,
            AppClient[] allowed, long nowElapsedMs, long nowWallMs, long maxRemainingMs) {
        int split = snapshot != null ? snapshot.indexOf('|') : -1;
        if (split <= 0 || currentNetwork == null
                || !currentNetwork.equals(snapshot.substring(0, split))) {
            return Collections.emptyMap();
        }

        Map<AppClient, Long> result = new HashMap<>();
        for (String entry : snapshot.substring(split + 1).split(";")) {
            int mark = entry.lastIndexOf(':');
            if (mark <= 0) {
                continue;
            }
            AppClient client = find(allowed, entry.substring(0, mark));
            if (client == null) {
                continue;
            }
            long remainingMs;
            try {
                remainingMs = Long.parseLong(entry.substring(mark + 1)) - nowWallMs;
            } catch (NumberFormatException e) {
                continue;
            }
            if (remainingMs <= 0) {
                continue;
            }
            result.put(client, nowElapsedMs + Math.min(remainingMs, maxRemainingMs));
        }
        return result;
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
