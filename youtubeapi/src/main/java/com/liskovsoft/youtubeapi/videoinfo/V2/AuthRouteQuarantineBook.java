package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * NEWTUBE(auth-route): memory and policy of the account-route quarantine, split out of
 * {@link VideoInfoService} so the TTL math and strike accounting are testable with both clocks
 * supplied by the caller.
 *
 * <p><b>Why the cooldown escalates.</b> The quarantine used to be a fixed 10 minutes. On a
 * signed-in phone that is a guaranteed tax: the TVHTML5 family has no working configuration today
 * (HANDOFF section 26 - TV answers SABR-only, TV_DOWNGRADED answers OK with URLs that 403 on the
 * first byte), so every expiry buys a re-probe that fails - a wasted /player, a media 403 and a
 * player reload, measured at 5.15-5.48 s to first frame against 2.80 s on the working client, on
 * two to three consecutive opens. Each re-quarantine of the same client now adds a strike, and the
 * cooldown is {@code 10 min x 4^(strikes-1)}, capped at 24 h: 10 min, 40 min, 2 h 40, 10 h 40,
 * then once a day. A route that is really dead costs a handful of probes on its first day and one
 * a day afterwards instead of six an hour.
 *
 * <p><b>Why strikes decay on time, not on success.</b> A strike is forgotten once the client has
 * gone {@link #STRIKE_MEMORY_MS} (48 h) without being quarantined again. The alternative - reset
 * when the client "genuinely serves playback" - has no sound signal inside this service: the one
 * thing it observes is a /player verdict, and that is exactly the signal that lies here
 * (TV_DOWNGRADED wins the walk with 22 formats and then 403s; TV 7.x historically served ~60 s
 * before its 403). Media success is only visible to the player, and a "not 403'd within this open"
 * rule would race next-video prefetches and a 403 that arrives a minute in. Time decay needs no new
 * plumbing and is safe in both directions: a route that recovers is simply never re-quarantined,
 * so its stale strikes cost nothing until it fails again; 48 h is twice the TTL cap, so a client
 * sitting at the cap that is re-probed on the first open after expiry still escalates.
 *
 * <p><b>Why the key is the transport.</b> Records are held per (transport, client), with transport
 * one of wifi / cell / vpn / ethernet / other. It used to be the Android {@code Network} handle's
 * hashCode, which is a fresh netId on every reconnect, so each Wi-Fi rejoin or radio handover wiped
 * the quarantine and paid the probe again. That is safe to drop because the 403 is caused by the
 * REQUEST, not by the path it takes: section 26 reproduced it off the device, from a laptop on a
 * different IP with a fresh visitor, and traced it to the TVHTML5 signatureTimestamp suffix - same
 * client, same session, same IP, only the timestamp differing, flips 206 to 403. The transport is
 * kept as a conservative partition (it costs at most one probe per transport) and each transport
 * keeps its own records, so moving wifi -> cell -> wifi never evicts the verdict the next open
 * depends on (a single-slot "current network" record did exactly that).
 *
 * <p>Thread-safe: the player thread (media 403) and the /player walk both write it.
 */
final class AuthRouteQuarantineBook {
    /** Cooldown of a first strike - the historical fixed quarantine. */
    static final long BASE_TTL_MS = TimeUnit.MINUTES.toMillis(10);
    /** Growth per additional strike. */
    static final int TTL_GROWTH = 4;
    /** No cooldown is longer than this: a route that recovers is re-probed at least daily. */
    static final long MAX_TTL_MS = TimeUnit.HOURS.toMillis(24);
    /** A client not re-quarantined for this long starts again from one strike. */
    static final long STRIKE_MEMORY_MS = TimeUnit.HOURS.toMillis(48);
    /** Past the TTL cap more strikes change nothing; bounded so a snapshot cannot carry nonsense. */
    static final int MAX_STRIKES = 8;

    /** One (transport, client) quarantine. Immutable; the book replaces it on every change. */
    static final class Record {
        final String transport;
        final AppClient client;
        /** {@code elapsedRealtime} deadline. The quarantine is over once this is not ahead of now. */
        final long untilElapsedMs;
        final int strikes;
        /**
         * WALL-clock time the latest strike was armed. Wall, not elapsed, because the strike memory
         * spans reboots and {@code elapsedRealtime} restarts at every boot.
         */
        final long armedWallMs;

        Record(String transport, AppClient client, long untilElapsedMs, int strikes,
                long armedWallMs) {
            this.transport = transport;
            this.client = client;
            this.untilElapsedMs = untilElapsedMs;
            this.strikes = strikes;
            this.armedWallMs = armedWallMs;
        }

        boolean isLive(long nowElapsedMs) {
            return untilElapsedMs - nowElapsedMs > 0;
        }

        /** Whether a re-quarantine now counts as a further strike rather than a fresh first one. */
        boolean remembers(long nowWallMs) {
            return nowWallMs - armedWallMs <= STRIKE_MEMORY_MS;
        }
    }

    private final Map<String, Record> mRecords = new LinkedHashMap<>();

    /** {@code BASE_TTL_MS x TTL_GROWTH^(strikes-1)}, capped at {@link #MAX_TTL_MS}. */
    static long ttlMsForStrikes(int strikes) {
        long ttl = BASE_TTL_MS;
        for (int strike = 1; strike < strikes && ttl < MAX_TTL_MS; strike++) {
            ttl *= TTL_GROWTH;
        }
        return Math.min(ttl, MAX_TTL_MS);
    }

    static int clampStrikes(int strikes) {
        return Math.max(1, Math.min(strikes, MAX_STRIKES));
    }

    /**
     * Arms (or re-arms) a quarantine and returns the resulting record.
     * <ul>
     *   <li>No record, or one past its strike memory: strike 1, {@link #BASE_TTL_MS}.</li>
     *   <li>An expired record still remembered: one more strike, escalated TTL.</li>
     *   <li>A record still LIVE: the same episode - the second 403 of one failing open, or the
     *       demoted client reached as a fallback and refusing again. The cooldown is refreshed at
     *       the current level, never escalated, so one failure cannot count twice.</li>
     * </ul>
     */
    synchronized Record quarantine(String transport, AppClient client, long nowElapsedMs,
            long nowWallMs) {
        String key = key(transport, client);
        Record previous = mRecords.get(key);
        int strikes;
        long untilElapsedMs;
        if (previous != null && previous.isLive(nowElapsedMs)) {
            strikes = previous.strikes;
            untilElapsedMs = Math.max(previous.untilElapsedMs,
                    nowElapsedMs + ttlMsForStrikes(strikes));
        } else if (previous != null && previous.remembers(nowWallMs)) {
            strikes = clampStrikes(previous.strikes + 1);
            untilElapsedMs = nowElapsedMs + ttlMsForStrikes(strikes);
        } else {
            strikes = 1;
            untilElapsedMs = nowElapsedMs + ttlMsForStrikes(strikes);
        }

        Record record = new Record(transport, client, untilElapsedMs, strikes, nowWallMs);
        mRecords.put(key, record);
        return record;
    }

    /** Clients whose quarantine is live on {@code transport}. Never null. */
    synchronized Set<AppClient> active(@Nullable String transport, long nowElapsedMs) {
        if (transport == null || mRecords.isEmpty()) {
            return Collections.emptySet();
        }

        Set<AppClient> result = new HashSet<>();
        for (Record record : mRecords.values()) {
            if (record.transport.equals(transport) && record.isLive(nowElapsedMs)) {
                result.add(record.client);
            }
        }
        return result;
    }

    /**
     * Drops records that are neither live nor inside their strike memory.
     *
     * @return true if anything was dropped (the caller persists)
     */
    synchronized boolean prune(long nowElapsedMs, long nowWallMs) {
        boolean changed = false;
        for (Iterator<Record> it = mRecords.values().iterator(); it.hasNext(); ) {
            Record record = it.next();
            if (!record.isLive(nowElapsedMs) && !record.remembers(nowWallMs)) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    /** Seeds the book from a decoded snapshot. Anything already armed in this process wins. */
    synchronized void restore(Collection<Record> records) {
        for (Record record : records) {
            String key = key(record.transport, record.client);
            if (!mRecords.containsKey(key)) {
                mRecords.put(key, record);
            }
        }
    }

    synchronized List<Record> records() {
        return new ArrayList<>(mRecords.values());
    }

    synchronized boolean isEmpty() {
        return mRecords.isEmpty();
    }

    /** Credential-free one-liner for NetPath: {@code cell/TV_DOWNGRADED:s2:1320s,...}. */
    synchronized String describe(long nowElapsedMs) {
        if (mRecords.isEmpty()) {
            return "none";
        }

        StringBuilder result = new StringBuilder();
        for (Record record : mRecords.values()) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(record.transport).append('/').append(record.client.name())
                    .append(":s").append(record.strikes).append(':')
                    .append(Math.max(0, record.untilElapsedMs - nowElapsedMs) / 1_000).append('s');
        }
        return result.toString();
    }

    private static String key(String transport, AppClient client) {
        return transport + '/' + client.name();
    }
}
