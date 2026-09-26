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
 * <p><b>Why the no-media evidence lives here too.</b> The other way a route earns a quarantine is
 * the walk's no-media verdict (an authenticated head answering SABR-only or empty for a video that
 * another client then served - see VideoInfoService.AuthRouteWalkState). That needs
 * {@link #NO_MEDIA_MIN_HITS} different videos, and the streak used to be a field of the service, so
 * it died with the process. A cold open (share link, notification) opens ONE video and exits, so it
 * never reached two hits and paid the dead head on every launch: measured 2026-09-25 on the owner's
 * signed-in Pixel 9, six cold opens in a row each spent ~0.4-0.65 s of first frame on a TV
 * {@code /player} that came back SABR-only, each ending at {@code hits=1/2}. The streak is now held
 * per (transport, client) beside the quarantine records, persisted with them, and only counts hits
 * inside {@link #NO_MEDIA_STREAK_MS}.
 *
 * <p><b>Why probation.</b> A client whose quarantine expired still has its strike record for
 * {@link #STRIKE_MEMORY_MS}. Asking it for two fresh proven hits again would make every expiry cost
 * two dead probes; one proven no-media verdict from a client with a remembered strike re-quarantines
 * it at once (and escalates, through {@link #quarantine}). The proven rule is untouched: only a
 * verdict on a video some other client served in the same walk ever reaches this book, so a video
 * that is unavailable to everyone still says nothing about the route. Probation also acts only on
 * route-shaped evidence - SABR-only, or the empty UNPLAYABLE of the reload-page outage - never on
 * an empty answer that carries a sign-in/age/visibility gate: an age-gated video this account may
 * not watch can still be served by anonymous WEB_EMBED (on TV; the phone skips WEB_EMBED and sends
 * age gates to the account route, TV_TIZEN), and that alone must not demote a route that is
 * otherwise healthy. Such evidence joins the ordinary two-video streak, as it always did.
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
    /** Arming videos kept per record: the first one and the latest two. */
    static final int MAX_ARMING_VIDEOS = 3;
    /**
     * DIFFERENT videos a client must be proven to refuse with no media before its FIRST quarantine.
     * One is not enough on a clean record: a per-video SABR rollout must not demote the account on
     * a single observation. A client on probation needs only one (see {@link #noteNoMedia}).
     */
    static final int NO_MEDIA_MIN_HITS = 2;
    /**
     * A no-media hit older than this no longer counts toward a streak. Long enough that two cold
     * opens of one sitting (minutes apart) or of one evening (hours apart) add up; short enough that
     * a hit from yesterday cannot combine with one today into a quarantine of a route nobody has
     * seen failing since.
     */
    static final long NO_MEDIA_STREAK_MS = TimeUnit.HOURS.toMillis(6);

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
        /**
         * Keys of the videos whose no-media verdicts armed this client's quarantines while its
         * strike is remembered - the first one and the latest ones, at most
         * {@link #MAX_ARMING_VIDEOS}; empty for media 403s only. Held for as long as the record -
         * the strike memory - so re-opening one of those videos after the expiry is never read as
         * fresh probation evidence (a per-video SABR exception would otherwise re-arm ever longer
         * strikes on a route that works for everything else). Never null.
         */
        final List<String> armingVideoKeys;

        Record(String transport, AppClient client, long untilElapsedMs, int strikes,
                long armedWallMs) {
            this(transport, client, untilElapsedMs, strikes, armedWallMs,
                    Collections.<String>emptyList());
        }

        Record(String transport, AppClient client, long untilElapsedMs, int strikes,
                long armedWallMs, List<String> armingVideoKeys) {
            this.transport = transport;
            this.client = client;
            this.untilElapsedMs = untilElapsedMs;
            this.strikes = strikes;
            this.armedWallMs = armedWallMs;
            this.armingVideoKeys = Collections.unmodifiableList(new ArrayList<>(armingVideoKeys));
        }

        boolean isLive(long nowElapsedMs) {
            return untilElapsedMs - nowElapsedMs > 0;
        }

        /** Whether a re-quarantine now counts as a further strike rather than a fresh first one. */
        boolean remembers(long nowWallMs) {
            return nowWallMs - armedWallMs <= STRIKE_MEMORY_MS;
        }
    }

    /**
     * Proven no-media verdicts of one (transport, client) that have not yet added up to a
     * quarantine. Immutable; the book replaces it on every change.
     */
    static final class Streak {
        final String transport;
        final AppClient client;
        /** Distinct videos counted so far; always below {@link #NO_MEDIA_MIN_HITS} while held. */
        final int hits;
        /**
         * Key of the last counted video (see VideoInfoService#noMediaVideoKey: a hash, never the
         * id itself). The same video twice - a reload, a retry - is one piece of evidence.
         */
        final String lastVideoKey;
        /** WALL-clock time of the last counted hit: the streak spans process restarts. */
        final long lastHitWallMs;

        Streak(String transport, AppClient client, int hits, String lastVideoKey,
                long lastHitWallMs) {
            this.transport = transport;
            this.client = client;
            this.hits = hits;
            this.lastVideoKey = lastVideoKey;
            this.lastHitWallMs = lastHitWallMs;
        }

        /** Whether this streak still counts, see {@link #NO_MEDIA_STREAK_MS}. */
        boolean isFresh(long nowWallMs) {
            return nowWallMs - lastHitWallMs <= NO_MEDIA_STREAK_MS;
        }
    }

    /** What one proven no-media verdict did. */
    static final class NoMediaOutcome {
        enum Kind {
            /** Same video as the last counted hit: nothing changed. */
            DUPLICATE,
            /** Counted toward the streak; {@link #hits} is the new count. */
            COUNTED,
            /** The client is now quarantined; {@link #record} says for how long. */
            QUARANTINED
        }

        final Kind kind;
        /** Streak length after this verdict (for a probation quarantine: 1, the verdict itself). */
        final int hits;
        /** Quarantined on the first verdict because the client still had strike memory. */
        final boolean probation;
        /** Set only for {@link Kind#QUARANTINED}. */
        @Nullable
        final Record record;
        /** Strikes the client carried before this verdict (0 = clean record). */
        final int previousStrikes;
        /** The quarantine was still live, so it was refreshed rather than escalated. */
        final boolean wasLive;

        private NoMediaOutcome(Kind kind, int hits, boolean probation, @Nullable Record record,
                int previousStrikes, boolean wasLive) {
            this.kind = kind;
            this.hits = hits;
            this.probation = probation;
            this.record = record;
            this.previousStrikes = previousStrikes;
            this.wasLive = wasLive;
        }
    }

    private final Map<String, Record> mRecords = new LinkedHashMap<>();
    private final Map<String, Streak> mStreaks = new LinkedHashMap<>();

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
        return quarantine(transport, client, nowElapsedMs, nowWallMs, null);
    }

    /**
     * @param armingVideoKey the video whose no-media verdict armed it (see
     *                       {@link Record#armingVideoKeys}); null for a media 403. The keys of a
     *                       record still remembered are carried forward either way.
     */
    synchronized Record quarantine(String transport, AppClient client, long nowElapsedMs,
            long nowWallMs, @Nullable String armingVideoKey) {
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

        List<String> armingVideoKeys = new ArrayList<>();
        if (previous != null && (previous.isLive(nowElapsedMs) || previous.remembers(nowWallMs))) {
            armingVideoKeys.addAll(previous.armingVideoKeys);
        }
        if (armingVideoKey != null && !armingVideoKeys.contains(armingVideoKey)) {
            armingVideoKeys.add(armingVideoKey);
        }
        while (armingVideoKeys.size() > MAX_ARMING_VIDEOS) {
            armingVideoKeys.remove(1); // keep the first; the latest are at the end
        }
        Record record = new Record(transport, client, untilElapsedMs, strikes, nowWallMs,
                armingVideoKeys);
        mRecords.put(key, record);
        // Whatever the evidence was (a media 403 or the streak itself), it has been acted on; the
        // next no-media verdict is judged by probation, not by a leftover partial streak.
        mStreaks.remove(key);
        return record;
    }

    /**
     * Records one PROVEN no-media verdict (the caller has already established that another client
     * served the video in the same walk) and decides what it means.
     * <ul>
     *   <li>The same video as the last counted hit, or as the one that armed the client's
     *       remembered quarantines ({@link Record#armingVideoKeys}): nothing changes (a reload, a
     *       retry, or re-opening that video later).</li>
     *   <li>The client has a strike record inside {@link #STRIKE_MEMORY_MS} - live, or expired
     *       and remembered: <b>probation</b>. Quarantined at once through {@link #quarantine}, so
     *       an expired record escalates and a live one is refreshed at its level.</li>
     *   <li>Otherwise the verdict joins the client's streak (a stale streak, older than
     *       {@link #NO_MEDIA_STREAK_MS}, starts over). The {@link #NO_MEDIA_MIN_HITS}-th distinct
     *       video quarantines at strike 1.</li>
     * </ul>
     *
     * @param videoKey stable key of the video (a hash is enough - only equality is used)
     */
    synchronized NoMediaOutcome noteNoMedia(String transport, AppClient client, String videoKey,
            long nowElapsedMs, long nowWallMs) {
        return noteNoMedia(transport, client, videoKey, true, nowElapsedMs, nowWallMs);
    }

    /**
     * @param probationEligible false for evidence too ambiguous to act on alone (an empty answer
     *                          carrying a sign-in/age/visibility gate, see
     *                          VideoInfoService.AuthRouteWalkState.Held#gated): it still joins
     *                          the streak, but never triggers probation.
     */
    synchronized NoMediaOutcome noteNoMedia(String transport, AppClient client, String videoKey,
            boolean probationEligible, long nowElapsedMs, long nowWallMs) {
        String key = key(transport, client);
        Streak streak = mStreaks.get(key);
        if (streak != null && !streak.isFresh(nowWallMs)) {
            streak = null;
        }
        if (streak != null && streak.lastVideoKey.equals(videoKey)) {
            return new NoMediaOutcome(NoMediaOutcome.Kind.DUPLICATE, streak.hits, false, null, 0,
                    false);
        }

        Record previous = mRecords.get(key);
        // Checked BEFORE probation: the video that armed the client's quarantine, re-opened while
        // its strike is remembered, is the same evidence again and must not escalate a route that
        // may have recovered everywhere but on that one video.
        if (previous != null && previous.armingVideoKeys.contains(videoKey)
                && (previous.isLive(nowElapsedMs) || previous.remembers(nowWallMs))) {
            return new NoMediaOutcome(NoMediaOutcome.Kind.DUPLICATE,
                    streak != null ? streak.hits : 0, false, null, 0, false);
        }
        if (probationEligible && previous != null
                && (previous.isLive(nowElapsedMs) || previous.remembers(nowWallMs))) {
            boolean wasLive = previous.isLive(nowElapsedMs);
            Record record = quarantine(transport, client, nowElapsedMs, nowWallMs, videoKey);
            return new NoMediaOutcome(NoMediaOutcome.Kind.QUARANTINED, 1, true, record,
                    previous.strikes, wasLive);
        }

        int hits = (streak != null ? streak.hits : 0) + 1;
        if (hits >= NO_MEDIA_MIN_HITS) {
            // Normally a clean record (strike 1); after ambiguous evidence on a client with strike
            // memory, the quarantine escalates exactly as a second media 403 would.
            boolean wasLive = previous != null && previous.isLive(nowElapsedMs);
            int previousStrikes = previous != null && (wasLive || previous.remembers(nowWallMs))
                    ? previous.strikes : 0;
            Record record = quarantine(transport, client, nowElapsedMs, nowWallMs, videoKey);
            return new NoMediaOutcome(NoMediaOutcome.Kind.QUARANTINED, hits, false, record,
                    previousStrikes, wasLive);
        }

        mStreaks.put(key, new Streak(transport, client, hits, videoKey, nowWallMs));
        return new NoMediaOutcome(NoMediaOutcome.Kind.COUNTED, hits, false, null, 0, false);
    }

    /**
     * The client answered {@code transport} with something other than a no-media verdict: direct
     * evidence the route serves, so its partial streak is dropped. Strikes are NOT touched - they
     * decay on time only (see the class comment for why a /player answer cannot clear them).
     *
     * @return true if a streak was dropped (the caller persists)
     */
    synchronized boolean clearNoMedia(@Nullable String transport, AppClient client) {
        return transport != null && mStreaks.remove(key(transport, client)) != null;
    }

    /**
     * Drops every partial streak: they were counted for the account that was signed in, and one
     * hit under account A plus one under account B is not two failures of either. Quarantine
     * records stay - the route failure they describe is client-level (HANDOFF section 26).
     *
     * @return true if anything was dropped (the caller persists)
     */
    synchronized boolean clearAllNoMedia() {
        boolean changed = !mStreaks.isEmpty();
        mStreaks.clear();
        return changed;
    }

    /** Cheap pre-check for {@link #clearNoMedia}: whether {@code client} has a streak anywhere. */
    synchronized boolean hasNoMediaStreak(AppClient client) {
        for (Streak streak : mStreaks.values()) {
            if (streak.client == client) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clients on {@code transport} whose quarantine has expired but whose strike is remembered: the
     * next proven no-media verdict re-quarantines them at once. Never null.
     */
    synchronized Set<AppClient> probation(@Nullable String transport, long nowElapsedMs,
            long nowWallMs) {
        if (transport == null || mRecords.isEmpty()) {
            return Collections.emptySet();
        }

        Set<AppClient> result = new HashSet<>();
        for (Record record : mRecords.values()) {
            if (record.transport.equals(transport) && !record.isLive(nowElapsedMs)
                    && record.remembers(nowWallMs)) {
                result.add(record.client);
            }
        }
        return result;
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
     * Drops records that are neither live nor inside their strike memory, and streaks whose last
     * hit is older than {@link #NO_MEDIA_STREAK_MS}.
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
        for (Iterator<Streak> it = mStreaks.values().iterator(); it.hasNext(); ) {
            if (!it.next().isFresh(nowWallMs)) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    /** Seeds the book from a decoded snapshot. Anything already armed in this process wins. */
    synchronized void restore(Collection<Record> records) {
        restore(records, Collections.<Streak>emptyList());
    }

    /**
     * Seeds records and streaks from a decoded snapshot. Anything already armed or counted in this
     * process wins; a restored streak is dropped only where this process has since armed a
     * quarantine for that client (which consumes it, see {@link #quarantine}).
     */
    synchronized void restore(Collection<Record> records, Collection<Streak> streaks) {
        java.util.Set<String> armedHere = new HashSet<>(mRecords.keySet());
        for (Record record : records) {
            String key = key(record.transport, record.client);
            if (!mRecords.containsKey(key)) {
                mRecords.put(key, record);
            }
        }
        for (Streak streak : streaks) {
            String key = key(streak.transport, streak.client);
            if (!armedHere.contains(key) && !mStreaks.containsKey(key)) {
                mStreaks.put(key, streak);
            }
        }
    }

    synchronized List<Record> records() {
        return new ArrayList<>(mRecords.values());
    }

    synchronized List<Streak> streaks() {
        return new ArrayList<>(mStreaks.values());
    }

    synchronized boolean isEmpty() {
        return mRecords.isEmpty() && mStreaks.isEmpty();
    }

    /**
     * Credential-free one-liner for NetPath, {@code wifi/TV:h1:age312s,...}: transport, client,
     * streak length and seconds since its last hit. {@code none} when there is no streak.
     */
    synchronized String describeStreaks(long nowWallMs) {
        if (mStreaks.isEmpty()) {
            return "none";
        }

        StringBuilder result = new StringBuilder();
        for (Streak streak : mStreaks.values()) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(streak.transport).append('/').append(streak.client.name())
                    .append(":h").append(streak.hits).append(":age")
                    .append(Math.max(0, nowWallMs - streak.lastHitWallMs) / 1_000).append('s');
        }
        return result.toString();
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
