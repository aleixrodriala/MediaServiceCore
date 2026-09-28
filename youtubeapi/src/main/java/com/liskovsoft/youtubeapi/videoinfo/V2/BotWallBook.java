package com.liskovsoft.youtubeapi.videoinfo.V2;

import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NEWTUBE(botwall): per-network-attachment memory that YouTube is answering the ANONYMOUS clients
 * with its "confirm you're not a bot" check, and the one route that still plays meanwhile.
 *
 * <p>What it is for. Pixel 9, Movistar LTE, 2026-09-25, after ~2 h of testing on that network:
 * every anonymous /player client - VISIONOS, ANDROID_VR, ANDROID_REEL, IOS and the whole Web
 * family, the latter with a freshly rotated visitor and a content PO token - answered
 * LOGIN_REQUIRED "Inicia sesion para confirmar que no eres un bot". Only the signed-in TVHTML5
 * heads answered OK, and neither had playable media. Every open then walked all eleven clients,
 * won on TV_DOWNGRADED's dead URLs, got a media 403, and each of the three recovery reloads walked
 * six more: ~20 /player calls per open and no frame. Wi-Fi was untouched. What that establishes is
 * "the anonymous clients were challenged in this network context" - not WHY (the public IP, the
 * guest identity, the client fingerprint, or their combination; HANDOFF section 26 reads it as a
 * joint verdict on (client, identity, network)). Nothing here depends on the answer.
 *
 * <p>The decisions, all pure so they are unit-tested (BotWallBookTest):
 * <ul>
 *   <li><b>Establishing</b> a wall needs either {@link #MIN_WALK_CHALLENGES} distinct anonymous
 *   clients answering the EXPLICIT bot check inside one walk, at least
 *   {@link #MIN_WALK_PLATFORM_CHALLENGES} of them non-Web platform identities (the Web partition
 *   alone was challenged on 2026-09-07 while ANDROID_VR served, HANDOFF section 17); or a platform
 *   client challenged on a SECOND different video while a first sighting is still on probation
 *   ({@link #SUSPECT_WINDOW_MS}). One transient LOGIN_REQUIRED can therefore never strand a
 *   network, and an age gate never counts (it is not the explicit bot text).</li>
 *   <li><b>While walled</b> the walk is {@link #plan}: the account route ({@link #ACCOUNT_ROUTE})
 *   and, when due, ONE re-probe of the anonymous side placed first. Nothing else from the ring is
 *   asked. An empty plan means "nothing on this network can serve right now" and costs zero
 *   requests.</li>
 *   <li><b>Re-probes back off and rotate.</b> The interval doubles with every probe of the wall
 *   ({@link #PROBE_BASE_MS} up to {@link #PROBE_MAX_MS}); signed in with a working account route
 *   the anonymous side is only a speed question, so the cadence is far slower
 *   ({@link #AUTH_PROBE_BASE_MS} up to {@link #AUTH_PROBE_MAX_MS}) and only the token-free
 *   VISIONOS head is probed. Signed out, the probe rotates across client FAMILIES
 *   ({@link #PROBE_FAMILIES}), so one client that keeps being refused cannot keep the others from
 *   ever being asked. A probe is spent when it is SENT ({@link #consumeProbe}), so a canceled
 *   tap-time prefetch cannot use one up.</li>
 *   <li><b>It ends</b> the moment any anonymous client serves a video on that attachment
 *   ({@link #noteAnonServed}); when {@link #WALL_TTL_MS} passes without a fresh challenge (each
 *   challenged probe re-confirms it); and at the latest {@link #MAX_WALL_MS} after it was
 *   established, however often it was re-confirmed - then WITHOUT probation, so the next walk is a
 *   full ring walk that gives every family its turn again. A TTL expiry leaves
 *   {@link #PROBATION_MS} of probation during which one platform challenge re-establishes it.
 *   Another attachment is simply not walled.</li>
 *   <li><b>Budgets.</b> Every request a walled open sends counts against that video's budget
 *   ({@link #WALLED_VIDEO_BUDGET} per {@link #VIDEO_BUDGET_WINDOW_MS}), recovery reloads of the
 *   same video included; past it the open is answered without asking anyone. The anonymous
 *   traffic of a whole wall is bounded by the probe backoff and the {@link #MAX_WALL_MS} cap.</li>
 *   <li><b>The account route</b> is benched per ATTACHMENT when it proves it cannot serve
 *   (challenged while signed in, a reload-page or SABR-only answer, a media 403). One failure
 *   benches it for THAT video only - so a recovery reload of the same video does not re-buy it,
 *   while one bad URL cannot take the route away from every other video; a failure on a second,
 *   different video within {@link #ROUTE_FAILURE_TTL_MS} benches it for the attachment. Signed
 *   out, it is one more anonymous identity and is asked at most once per wall, whatever it
 *   answers (a timeout included).</li>
 *   <li><b>Persisted within a boot</b> ({@link #encode}/{@link #restore}; network attachment ids
 *   restart with a reboot, so another boot restores nothing). A cold restart under a wall resumes
 *   its plan, its probe backoff and its route benches instead of re-walking the ring.</li>
 * </ul>
 *
 * <p>Thread-safe: the walk consults it under VideoInfoService's monitor, the player's media 403
 * writes it from the main thread and the restore runs on its own thread. Every mutation hands a
 * fresh snapshot to the {@link Persister}, encoded under the lock so saves cannot reorder.
 */
final class BotWallBook {
    /** A wall that no challenge has re-confirmed for this long is forgotten. */
    static final long WALL_TTL_MS = 15 * 60_000L;
    /** However often it is re-confirmed, a wall ends this long after it was established. */
    static final long MAX_WALL_MS = 60 * 60_000L;
    /** First re-probe of the anonymous side (signed out); doubles per probe of the wall. */
    static final long PROBE_BASE_MS = 60_000L;
    static final long PROBE_MAX_MS = 15 * 60_000L;
    /** Signed in with a working account route the anonymous side is only a speed question. */
    static final long AUTH_PROBE_BASE_MS = 5 * 60_000L;
    static final long AUTH_PROBE_MAX_MS = 30 * 60_000L;
    /** How long a single platform-client challenge waits for a second video to confirm it. */
    static final long SUSPECT_WINDOW_MS = 10 * 60_000L;
    /** After a wall expires, one platform challenge re-establishes it for this long. */
    static final long PROBATION_MS = 30 * 60_000L;
    /** How long a failed account route stays benched (per video, then per attachment). */
    static final long ROUTE_FAILURE_TTL_MS = 30 * 60_000L;
    /** Requests one video may cost on a walled attachment, its recovery reloads included... */
    static final int WALLED_VIDEO_BUDGET = 3;
    /** ...within this window. */
    static final long VIDEO_BUDGET_WINDOW_MS = 3 * 60_000L;
    static final int MIN_WALK_CHALLENGES = 3;
    static final int MIN_WALK_PLATFORM_CHALLENGES = 2;
    /** Walls are per attachment; a handful covers any realistic Wi-Fi/cell/VPN back-and-forth. */
    static final int MAX_NETWORKS = 4;
    /** The cheapest anonymous identity (no token, no cipher) and the ring's own head. */
    static final AppClient PROBE_CLIENT = AppClient.VISIONOS;
    /**
     * Signed-out probe rotation, one client per family: the token-free Apple head and the Android
     * platform identity. A probe ends the wall only by SERVING the video (noteAnonServed), so each
     * family is represented by a client whose answers this build plays. The attested Web family
     * (WEB) was the third until the planner (netbench LANES.md): its answers are SABR-only or
     * progressive-only, which the walk never accepts, so a WEB probe could re-confirm a wall but
     * never end one - and with WEB at the end of the lane, "the family the establishing walk did
     * not see refused" made it the FIRST probe. WEB_EMBED is not a probe either: it has never been
     * measured against a wall.
     */
    static final AppClient[] PROBE_FAMILIES = {
            AppClient.VISIONOS, AppClient.ANDROID_VR
    };
    /** The one request shape that carries the account AND hands out URLs that serve. */
    static final AppClient ACCOUNT_ROUTE = AppClient.TV_TIZEN;
    /** Snapshot format; see {@link #encode}. */
    static final String FORMAT = "bw1";
    /** The boot wall time moves with clock corrections; beyond this it is another boot. */
    static final long BOOT_WALL_TOLERANCE_MS = 10 * 60_000L;
    /** Per-video benches kept per attachment; the route is benched outright long before this. */
    private static final int MAX_BENCHED_VIDEOS = 8;
    private static final int MAX_BUDGET_VIDEOS = 8;

    /** Receives every new snapshot (null = nothing left to keep). */
    interface Persister {
        void save(@Nullable String snapshot);
    }

    /** Evidence gathered by ONE walk. Not thread-safe; it never leaves its walk. */
    static final class WalkEvidence {
        private final Set<AppClient> mChallenged = EnumSet.noneOf(AppClient.class);
        private int mPlatform;
        private boolean mShortcut;

        /**
         * Whether THIS walk's own evidence is strong enough to stop asking the rest of the ring:
         * {@link #MIN_WALK_CHALLENGES} identities including {@link #MIN_WALK_PLATFORM_CHALLENGES}
         * platform ones. A wall established by the two-video or probation rule applies to LATER
         * opens only; the walk that established it still gives the other client families their
         * turn, so a video-specific answer cannot wall a healthy attachment unseen (and a serve
         * later in that walk clears the wall at once).
         */
        boolean isStrong() {
            return mChallenged.size() >= MIN_WALK_CHALLENGES
                    && mPlatform >= MIN_WALK_PLATFORM_CHALLENGES;
        }

        /** Whether this walk already switched to the walled plan. */
        boolean hasShortcut() {
            return mShortcut;
        }

        /** Marks the one shortcut a walk may take; false if it was already taken. */
        boolean takeShortcut() {
            if (mShortcut) {
                return false;
            }
            mShortcut = true;
            return true;
        }

        void add(AppClient client) {
            if (mChallenged.add(client) && !client.isWebPotRequired()) {
                mPlatform++;
            }
        }

        int challenged() {
            return mChallenged.size();
        }

        int platform() {
            return mPlatform;
        }

        Set<AppClient> clients() {
            return Collections.unmodifiableSet(mChallenged);
        }
    }

    /** What a walk on a walled network should do. */
    static final class Plan {
        static final Plan NONE = new Plan(false, null, Collections.<AppClient>emptyList(), false);

        final boolean walled;
        /** This walk re-tests the anonymous side with {@link #probeClient}, placed first. */
        final boolean probe;
        @Nullable
        final AppClient probeClient;
        /** The only clients worth a round trip; empty = answer without asking anyone. */
        final List<AppClient> order;
        /** The order was cut short by this video's walled budget. */
        final boolean budgetCapped;

        Plan(boolean walled, @Nullable AppClient probeClient, List<AppClient> order,
                boolean budgetCapped) {
            this.walled = walled;
            this.probeClient = probeClient;
            this.probe = probeClient != null;
            this.order = Collections.unmodifiableList(order);
            this.budgetCapped = budgetCapped;
        }
    }

    /** What {@link #noteChallenge} did with a challenge. */
    enum Challenge {
        /** Not enough evidence yet (or no network to key it on). */
        HELD,
        /** Remembered as a first platform sighting, waiting for a second video. */
        SUSPECT,
        /** This challenge established the wall. */
        ESTABLISHED,
        /** The network was already walled; the wall was re-confirmed. */
        CONFIRMED
    }

    /** What {@link #noteRouteFailed} concluded. */
    enum RouteFailure {
        /** Benched for this one video (a first failure, or a repeat on the same video). */
        VIDEO,
        /** A second different video failed too: benched for the whole attachment. */
        ROUTE,
        /** No attachment to key it on. */
        NONE
    }

    private static final class Wall {
        final long establishedAtMs;
        final String cause;
        final Set<AppClient> challenged = EnumSet.noneOf(AppClient.class);
        long untilMs;
        /** Establishment, then the last probe SENT: the backoff counts from here. */
        long lastProbeAtMs;
        /** Probes sent during this wall: the backoff exponent. */
        int probes;
        /** Next signed-out probe family (index into PROBE_FAMILIES). */
        int familyCursor;
        /** videoKey -> {requests, windowStartMs}; not persisted (minutes-scale). */
        final Map<String, long[]> videoSpend = new LinkedHashMap<>();

        Wall(long nowMs, String cause) {
            this.establishedAtMs = nowMs;
            this.cause = cause;
            this.lastProbeAtMs = nowMs;
        }

        long capMs() {
            return establishedAtMs + MAX_WALL_MS;
        }
    }

    private static final class Suspect {
        /** Hash of the video that raised it, or null for probation (any video confirms). */
        @Nullable
        final String videoKey;
        final long untilMs;

        Suspect(@Nullable String videoKey, long untilMs) {
            this.videoKey = videoKey;
            this.untilMs = untilMs;
        }
    }

    /** The account route's health on one attachment. */
    private static final class RouteRecord {
        /** Videos the route failed on (hashed), each benched for that video only. */
        final Map<String, Long> benchedVideos = new LinkedHashMap<>();
        @Nullable
        String failedReason;
        long failedUntilMs;
    }

    private final Map<String, Wall> mWalls = new LinkedHashMap<>();
    private final Map<String, Suspect> mSuspects = new LinkedHashMap<>();
    private final Map<String, RouteRecord> mRoutes = new LinkedHashMap<>();
    @Nullable
    private Persister mPersister;
    private long mBootCount = -1;
    private long mBootWallMs;
    private boolean mRestored;

    /**
     * Where snapshots go, and the boot they belong to ({@code Settings.Global.BOOT_COUNT} or -1,
     * and {@code currentTimeMillis - elapsedRealtime}). Nothing is saved before the restore ran
     * ({@link #restore}), so a cold start cannot overwrite the stored state with an empty book.
     */
    synchronized void setPersister(@Nullable Persister persister, long bootCount, long bootWallMs) {
        mPersister = persister;
        mBootCount = bootCount;
        mBootWallMs = bootWallMs;
    }

    /**
     * The walk a walled network should make, or {@link Plan#NONE}. Does NOT spend the probe: the
     * walk that actually sends it calls {@link #consumeProbe} (walks are serialized by
     * VideoInfoService's monitor, so no second walk can plan in between).
     *
     * @param videoKey the hashed video about to be opened: its route bench and its budget
     */
    synchronized Plan plan(@Nullable String network, boolean authenticated,
            @Nullable String videoKey, long nowMs) {
        Wall wall = activeWall(network, nowMs);
        if (wall == null) {
            return Plan.NONE;
        }

        // Signed in, the route carries the account and is only skipped once it has proven dead
        // (for this video or for the attachment). Signed out it is just one more anonymous
        // identity: worth one ask per wall, no more.
        boolean route = !isRouteFailedLocked(network, videoKey, nowMs)
                && (authenticated || !wall.challenged.contains(ACCOUNT_ROUTE));
        boolean accountServes = authenticated && route;
        AppClient probe = null;
        if (nowMs - wall.lastProbeAtMs >= probeIntervalMs(wall.probes, accountServes)) {
            probe = accountServes ? PROBE_CLIENT
                    : PROBE_FAMILIES[wall.familyCursor % PROBE_FAMILIES.length];
        }

        List<AppClient> order = new ArrayList<>(2);
        if (probe != null) {
            order.add(probe);
        }
        if (route) {
            order.add(ACCOUNT_ROUTE);
        }

        // The per-video budget: the probe goes first when there is not room for both, the
        // account route is kept - playing this video matters more than re-testing the wall.
        int left = videoKey != null ? remainingBudget(wall, videoKey, nowMs) : order.size();
        boolean capped = false;
        while (order.size() > Math.max(0, left)) {
            capped = true;
            if (probe != null && order.get(0) == probe) {
                order.remove(0);
                probe = null;
            } else {
                order.remove(order.size() - 1);
            }
        }
        return new Plan(true, probe, order, capped);
    }

    /** The anonymous re-probe interval after {@code probes} probes of the same wall. */
    static long probeIntervalMs(int probes, boolean accountServes) {
        long base = accountServes ? AUTH_PROBE_BASE_MS : PROBE_BASE_MS;
        long max = accountServes ? AUTH_PROBE_MAX_MS : PROBE_MAX_MS;
        int shift = Math.min(Math.max(probes, 0), 20);
        return Math.min(base << shift, max);
    }

    /**
     * The planned re-probe with {@code client} is being SENT now: the backoff grows, and a
     * signed-out rotation moves on to the next family.
     */
    synchronized void consumeProbe(@Nullable String network, AppClient client, long nowMs) {
        Wall wall = activeWall(network, nowMs);
        if (wall == null) {
            return;
        }
        wall.lastProbeAtMs = nowMs;
        wall.probes++;
        if (client == PROBE_FAMILIES[wall.familyCursor % PROBE_FAMILIES.length]) {
            wall.familyCursor++;
        }
        persistLocked();
    }

    /** A request of a walled open for {@code videoKey} is being sent: count it (see budgets). */
    synchronized void noteWalledRequest(@Nullable String network, @Nullable String videoKey,
            long nowMs) {
        Wall wall = activeWall(network, nowMs);
        if (wall == null || videoKey == null) {
            return;
        }
        long[] spend = wall.videoSpend.remove(videoKey);
        if (spend == null || nowMs - spend[1] >= VIDEO_BUDGET_WINDOW_MS) {
            spend = new long[] {0, nowMs};
        }
        spend[0]++;
        wall.videoSpend.put(videoKey, spend);
        Iterator<String> eldest = wall.videoSpend.keySet().iterator();
        while (wall.videoSpend.size() > MAX_BUDGET_VIDEOS && eldest.hasNext()) {
            eldest.next();
            eldest.remove();
        }
    }

    private static int remainingBudget(Wall wall, String videoKey, long nowMs) {
        long[] spend = wall.videoSpend.get(videoKey);
        if (spend == null || nowMs - spend[1] >= VIDEO_BUDGET_WINDOW_MS) {
            return WALLED_VIDEO_BUDGET;
        }
        return (int) Math.max(0, WALLED_VIDEO_BUDGET - spend[0]);
    }

    /**
     * Signed out, the account route was asked on a walled attachment and did not serve - whatever
     * it answered, including nothing at all. It is not asked again for the rest of this wall.
     */
    synchronized void noteAnonRouteSpent(@Nullable String network, long nowMs) {
        Wall wall = activeWall(network, nowMs);
        if (wall != null && wall.challenged.add(ACCOUNT_ROUTE)) {
            persistLocked();
        }
    }

    /**
     * Records an EXPLICIT bot check answered to an ANONYMOUS request (the caller filters both).
     *
     * @param walk     this walk's evidence, updated here
     * @param videoKey a hash of the video, so the probation rule can tell two videos apart
     */
    synchronized Challenge noteChallenge(@Nullable String network, WalkEvidence walk,
            AppClient client, String videoKey, long nowMs) {
        walk.add(client);
        if (network == null) {
            return Challenge.HELD;
        }

        Wall wall = activeWall(network, nowMs);
        if (wall != null) {
            wall.challenged.add(client);
            // Re-confirmed - but never past the cap: a wall that only one refused client keeps
            // confirming must still end, so every other family gets a full walk again.
            wall.untilMs = Math.min(nowMs + WALL_TTL_MS, wall.capMs());
            persistLocked();
            return Challenge.CONFIRMED;
        }

        boolean platform = !client.isWebPotRequired();
        Suspect suspect = activeSuspect(network, nowMs);
        String cause = null;
        if (walk.isStrong()) {
            cause = "walk";
        } else if (platform && suspect != null
                && (suspect.videoKey == null || !suspect.videoKey.equals(videoKey))) {
            cause = suspect.videoKey == null ? "probation" : "second-video";
        }

        if (cause != null) {
            Wall established = new Wall(nowMs, cause);
            established.challenged.addAll(walk.clients());
            established.untilMs = Math.min(nowMs + WALL_TTL_MS, established.capMs());
            // The walk that establishes the wall has just watched the anonymous side fail; the
            // first re-test is due one interval from now (lastProbeAtMs = establishment), and
            // the signed-out rotation starts with the family that was NOT just refused.
            established.familyCursor = firstUnchallengedFamily(established.challenged);
            mSuspects.remove(network);
            mWalls.remove(network);
            mWalls.put(network, established);
            trim(mWalls);
            persistLocked();
            return Challenge.ESTABLISHED;
        }

        if (platform && (suspect == null || suspect.videoKey != null)) {
            mSuspects.remove(network);
            mSuspects.put(network, new Suspect(videoKey, nowMs + SUSPECT_WINDOW_MS));
            trim(mSuspects);
            persistLocked();
            return Challenge.SUSPECT;
        }
        return Challenge.HELD;
    }

    private static int firstUnchallengedFamily(Set<AppClient> challenged) {
        for (int i = 0; i < PROBE_FAMILIES.length; i++) {
            if (!challenged.contains(PROBE_FAMILIES[i])) {
                return i;
            }
        }
        return 0;
    }

    /**
     * An anonymous client served a video on this network, so the anonymous side is not walled
     * there: forget the wall AND any pending suspicion, with no probation.
     *
     * @return whether anything was remembered
     */
    synchronized boolean noteAnonServed(@Nullable String network) {
        if (network == null) {
            return false;
        }
        boolean hadWall = mWalls.remove(network) != null;
        boolean hadSuspect = mSuspects.remove(network) != null;
        if (hadWall || hadSuspect) {
            persistLocked();
        }
        return hadWall || hadSuspect;
    }

    /**
     * The account route failed to serve {@code videoKey} on this attachment. The first failure
     * benches it for that video; a failure on a DIFFERENT video while an earlier bench is still
     * live benches it for the attachment. A null video counts as a distinct, unknown one.
     */
    synchronized RouteFailure noteRouteFailed(@Nullable String network, @Nullable String videoKey,
            String reason, long nowMs) {
        if (network == null) {
            return RouteFailure.NONE;
        }
        RouteRecord record = mRoutes.remove(network);
        if (record == null) {
            record = new RouteRecord();
        }
        mRoutes.put(network, record);
        trim(mRoutes);
        pruneBenches(record, nowMs);

        String key = videoKey != null ? videoKey : "?";
        boolean otherVideo = false;
        for (String benched : record.benchedVideos.keySet()) {
            otherVideo |= !benched.equals(key) || "?".equals(key);
        }
        record.benchedVideos.remove(key);
        record.benchedVideos.put(key, nowMs + ROUTE_FAILURE_TTL_MS);
        Iterator<String> eldest = record.benchedVideos.keySet().iterator();
        while (record.benchedVideos.size() > MAX_BENCHED_VIDEOS && eldest.hasNext()) {
            eldest.next();
            eldest.remove();
        }
        RouteFailure outcome = RouteFailure.VIDEO;
        if (otherVideo) {
            record.failedReason = reason;
            record.failedUntilMs = nowMs + ROUTE_FAILURE_TTL_MS;
            outcome = RouteFailure.ROUTE;
        }
        persistLocked();
        return outcome;
    }

    /**
     * A sign-in, account switch or sign-out: "the account route is dead here" was a verdict on the
     * previous credential (a challenged or reload-page answer can be about the account itself).
     * The wall is about the anonymous side and survives.
     */
    synchronized boolean clearRouteFailures() {
        boolean had = !mRoutes.isEmpty();
        mRoutes.clear();
        if (had) {
            persistLocked();
        }
        return had;
    }

    /** Forgets everything (debug reset only); the stored value is cleared too. */
    synchronized boolean clearAll() {
        boolean had = !mWalls.isEmpty() || !mSuspects.isEmpty() || !mRoutes.isEmpty();
        mWalls.clear();
        mSuspects.clear();
        mRoutes.clear();
        persistLocked();
        return had;
    }

    /** Benched on this attachment, for this video or for every video. */
    synchronized boolean isRouteFailed(@Nullable String network, @Nullable String videoKey,
            long nowMs) {
        return isRouteFailedLocked(network, videoKey, nowMs);
    }

    /** Why the route is benched for the whole attachment, or null. */
    @Nullable
    synchronized String routeFailureReason(@Nullable String network, long nowMs) {
        return isRouteFailedLocked(network, null, nowMs) ? mRoutes.get(network).failedReason : null;
    }

    synchronized boolean isWalled(@Nullable String network, long nowMs) {
        return activeWall(network, nowMs) != null;
    }

    /**
     * Cheap pre-check for the signed-in plan (PhoneSourcePlanner): false means the account route is
     * benched nowhere, so a healthy walk never has to look up its network key.
     */
    synchronized boolean hasRouteRecords() {
        return !mRoutes.isEmpty();
    }

    /**
     * Cheap pre-check so a healthy walk never has to look up its network key: false means no
     * attachment is walled (lapsed walls are expired here too).
     */
    synchronized boolean hasWalls(long nowMs) {
        for (String network : new ArrayList<>(mWalls.keySet())) {
            activeWall(network, nowMs);
        }
        return !mWalls.isEmpty();
    }

    /** Whether any attachment has a wall or a pending suspicion that a serve could clear. */
    synchronized boolean hasSuspicion(long nowMs) {
        for (String network : new ArrayList<>(mSuspects.keySet())) {
            activeSuspect(network, nowMs);
        }
        return hasWalls(nowMs) || !mSuspects.isEmpty();
    }

    /** One credential-free description for the NetPath line. */
    synchronized String describe(@Nullable String network, long nowMs) {
        Wall wall = activeWall(network, nowMs);
        if (wall == null) {
            Suspect suspect = activeSuspect(network, nowMs);
            return suspect == null ? "none"
                    : (suspect.videoKey == null ? "probation" : "suspect")
                            + " remainingMs=" + (suspect.untilMs - nowMs);
        }
        return "walled cause=" + wall.cause + " ageMs=" + (nowMs - wall.establishedAtMs)
                + " remainingMs=" + (wall.untilMs - nowMs)
                + " capInMs=" + (wall.capMs() - nowMs)
                + " probes=" + wall.probes
                + " nextFamily=" + PROBE_FAMILIES[wall.familyCursor % PROBE_FAMILIES.length]
                + " challenged=" + wall.challenged;
    }

    // --------------------------------------------------------------------------------------------
    // Persistence

    /**
     * {@code bw1,<bootCount>,<bootWallMs>} then one line per record, times in this boot's
     * elapsedRealtime:
     * <pre>
     *   W,&lt;network&gt;,&lt;establishedAt&gt;,&lt;until&gt;,&lt;lastProbeAt&gt;,&lt;probes&gt;,&lt;familyCursor&gt;,&lt;cause&gt;,&lt;CLIENT;CLIENT&gt;
     *   S,&lt;network&gt;,&lt;videoKey or -&gt;,&lt;until&gt;
     *   R,&lt;network&gt;,&lt;reason or -&gt;,&lt;failedUntil&gt;,&lt;videoKey:until;...&gt;
     * </pre>
     * Credential- and content-free: attachment ids, client names and 8-hex video hashes.
     */
    synchronized String encode(long bootCount, long bootWallMs) {
        StringBuilder out = new StringBuilder(FORMAT).append(',').append(bootCount)
                .append(',').append(bootWallMs);
        for (Map.Entry<String, Wall> entry : mWalls.entrySet()) {
            Wall wall = entry.getValue();
            StringBuilder clients = new StringBuilder();
            for (AppClient client : wall.challenged) {
                clients.append(clients.length() > 0 ? ";" : "").append(client.name());
            }
            out.append("\nW,").append(entry.getKey()).append(',').append(wall.establishedAtMs)
                    .append(',').append(wall.untilMs).append(',').append(wall.lastProbeAtMs)
                    .append(',').append(wall.probes).append(',').append(wall.familyCursor)
                    .append(',').append(wall.cause).append(',').append(clients);
        }
        for (Map.Entry<String, Suspect> entry : mSuspects.entrySet()) {
            Suspect suspect = entry.getValue();
            out.append("\nS,").append(entry.getKey()).append(',')
                    .append(suspect.videoKey != null ? suspect.videoKey : "-")
                    .append(',').append(suspect.untilMs);
        }
        for (Map.Entry<String, RouteRecord> entry : mRoutes.entrySet()) {
            RouteRecord record = entry.getValue();
            StringBuilder benches = new StringBuilder();
            for (Map.Entry<String, Long> bench : record.benchedVideos.entrySet()) {
                benches.append(benches.length() > 0 ? ";" : "").append(bench.getKey())
                        .append(':').append(bench.getValue());
            }
            out.append("\nR,").append(entry.getKey()).append(',')
                    .append(record.failedReason != null ? record.failedReason : "-")
                    .append(',').append(record.failedUntilMs).append(',').append(benches);
        }
        return out.toString();
    }

    /**
     * Restores a snapshot written in THIS boot (attachment ids restart with a reboot, so any other
     * boot restores nothing). What this process already learnt for an attachment is newer and
     * wins; expired and damaged records are dropped and named in {@code dropped}. Marks the book
     * restored, which is what allows it to save from now on.
     *
     * @return the number of records restored
     */
    synchronized int restore(@Nullable String snapshot, long bootCount, long bootWallMs,
            long nowMs, List<String> dropped) {
        mRestored = true;
        if (snapshot == null || snapshot.isEmpty()) {
            return 0;
        }
        String[] lines = snapshot.split("\n");
        String[] header = lines[0].split(",", -1);
        if (header.length != 3 || !FORMAT.equals(header[0])) {
            dropped.add("format");
            return 0;
        }
        if (!sameBoot(parseLong(header[1], Long.MIN_VALUE), parseLong(header[2], Long.MIN_VALUE),
                bootCount, bootWallMs)) {
            dropped.add("other-boot");
            return 0;
        }
        int restored = 0;
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split(",", -1);
            String kind = f.length > 1 ? f[0] : "";
            String network = f.length > 1 ? f[1] : null;
            if (!isNetworkKey(network)) {
                dropped.add("corrupt");
                continue;
            }
            int outcome;
            switch (kind) {
                case "W":
                    outcome = f.length == 9 ? restoreWall(network, f, nowMs) : CORRUPT;
                    break;
                case "S":
                    outcome = f.length == 4 ? restoreSuspect(network, f, nowMs) : CORRUPT;
                    break;
                case "R":
                    outcome = f.length == 5 ? restoreRoute(network, f, nowMs) : CORRUPT;
                    break;
                default:
                    outcome = CORRUPT;
            }
            if (outcome == RESTORED) {
                restored++;
            } else {
                dropped.add(outcome == EXPIRED ? "expired:" + kind : "corrupt");
            }
        }
        return restored;
    }

    private static final int RESTORED = 1;
    private static final int EXPIRED = 0;
    private static final int CORRUPT = -1;

    private int restoreWall(String network, String[] f, long nowMs) {
        long established = parseLong(f[2], Long.MIN_VALUE);
        long until = parseLong(f[3], Long.MIN_VALUE);
        long lastProbe = parseLong(f[4], Long.MIN_VALUE);
        long probes = parseLong(f[5], -1);
        long cursor = parseLong(f[6], -1);
        if (established == Long.MIN_VALUE || established > nowMs || until < established
                || until > established + MAX_WALL_MS || lastProbe < established
                || lastProbe > nowMs || probes < 0 || probes > 64 || cursor < 0 || cursor > 1024
                || !f[7].matches("[a-z-]{1,16}")) {
            return CORRUPT;
        }
        if (nowMs - until >= 0) {
            return EXPIRED;
        }
        if (mWalls.containsKey(network)) {
            return RESTORED; // this process's own verdict is newer and stays
        }
        Wall wall = new Wall(established, f[7]);
        wall.untilMs = until;
        wall.lastProbeAtMs = lastProbe;
        wall.probes = (int) probes;
        wall.familyCursor = (int) cursor;
        for (String name : f[8].split(";")) {
            try {
                wall.challenged.add(AppClient.valueOf(name));
            } catch (IllegalArgumentException ignored) {
                // an empty list, or a client this build does not know: nothing to skip for it
            }
        }
        mWalls.put(network, wall);
        trim(mWalls);
        return RESTORED;
    }

    private int restoreSuspect(String network, String[] f, long nowMs) {
        long until = parseLong(f[3], Long.MIN_VALUE);
        String video = "-".equals(f[2]) ? null : f[2];
        if (until == Long.MIN_VALUE || (video != null && !isVideoKey(video))
                || until - nowMs > Math.max(SUSPECT_WINDOW_MS, PROBATION_MS)) {
            return CORRUPT;
        }
        if (nowMs - until >= 0) {
            return EXPIRED;
        }
        if (!mSuspects.containsKey(network) && !mWalls.containsKey(network)) {
            mSuspects.put(network, new Suspect(video, until));
            trim(mSuspects);
        }
        return RESTORED;
    }

    private int restoreRoute(String network, String[] f, long nowMs) {
        String reason = "-".equals(f[2]) ? null : f[2];
        long failedUntil = parseLong(f[3], Long.MIN_VALUE);
        if (failedUntil == Long.MIN_VALUE || failedUntil - nowMs > ROUTE_FAILURE_TTL_MS
                || (reason != null && !reason.matches("[a-z0-9-]{1,16}"))) {
            return CORRUPT;
        }
        RouteRecord record = new RouteRecord();
        if (reason != null && nowMs - failedUntil < 0) {
            record.failedReason = reason;
            record.failedUntilMs = failedUntil;
        }
        for (String bench : f[4].split(";")) {
            if (bench.isEmpty()) {
                continue;
            }
            int colon = bench.indexOf(':');
            String video = colon > 0 ? bench.substring(0, colon) : null;
            long until = colon > 0 ? parseLong(bench.substring(colon + 1), Long.MIN_VALUE)
                    : Long.MIN_VALUE;
            if (video == null || !(isVideoKey(video) || "?".equals(video))
                    || until == Long.MIN_VALUE || until - nowMs > ROUTE_FAILURE_TTL_MS) {
                return CORRUPT;
            }
            if (nowMs - until < 0 && record.benchedVideos.size() < MAX_BENCHED_VIDEOS) {
                record.benchedVideos.put(video, until);
            }
        }
        if (record.failedReason == null && record.benchedVideos.isEmpty()) {
            return EXPIRED;
        }
        if (!mRoutes.containsKey(network)) {
            mRoutes.put(network, record);
            trim(mRoutes);
        }
        return RESTORED;
    }

    /**
     * Same boot when BOOT_COUNT is known on both sides and equal (and the boot wall times, when
     * both are sane, agree); with an unknown BOOT_COUNT the wall times alone must agree.
     */
    static boolean sameBoot(long storedCount, long storedWall, long currentCount, long currentWall) {
        boolean wallsKnown = storedWall > 0 && currentWall > 0;
        boolean wallsAgree = wallsKnown
                && Math.abs(storedWall - currentWall) <= BOOT_WALL_TOLERANCE_MS;
        if (storedCount >= 0 && currentCount >= 0) {
            return storedCount == currentCount && (!wallsKnown || wallsAgree);
        }
        return wallsAgree;
    }

    /** VideoInfoService's attachment key: {@code <transport>:<netId hash>}. */
    static boolean isNetworkKey(@Nullable String network) {
        return network != null && network.length() <= 32
                && network.matches("(wifi|cell|vpn|ethernet|other):-?\\d{1,11}");
    }

    private static boolean isVideoKey(String key) {
        return key.length() <= 8 && key.matches("[0-9a-f]{1,8}");
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Only once restored (a cold start must not overwrite the stored state with nothing). */
    private void persistLocked() {
        Persister persister = mPersister;
        if (persister == null || !mRestored) {
            return;
        }
        try {
            boolean empty = mWalls.isEmpty() && mSuspects.isEmpty() && mRoutes.isEmpty();
            persister.save(empty ? null : encode(mBootCount, mBootWallMs));
        } catch (RuntimeException ignored) {
            // Best effort: the in-memory book still steers this process.
        }
    }

    // --------------------------------------------------------------------------------------------

    @Nullable
    private Wall activeWall(@Nullable String network, long nowMs) {
        if (network == null) {
            return null;
        }
        Wall wall = mWalls.get(network);
        if (wall == null) {
            return null;
        }
        if (nowMs - wall.untilMs >= 0) {
            mWalls.remove(network);
            mSuspects.remove(network);
            // A TTL expiry stays suspicious for a while, so a returning wall is recognized by its
            // first platform challenge. The CAP does not: the next walk must give every client
            // family a full turn, so no probation re-establishes it off one refused client.
            if (wall.untilMs < wall.capMs()) {
                mSuspects.put(network, new Suspect(null, nowMs + PROBATION_MS));
                trim(mSuspects);
            }
            persistLocked();
            return null;
        }
        return wall;
    }

    @Nullable
    private Suspect activeSuspect(@Nullable String network, long nowMs) {
        if (network == null) {
            return null;
        }
        Suspect suspect = mSuspects.get(network);
        if (suspect != null && nowMs - suspect.untilMs >= 0) {
            mSuspects.remove(network);
            return null;
        }
        return suspect;
    }

    private boolean isRouteFailedLocked(@Nullable String network, @Nullable String videoKey,
            long nowMs) {
        if (network == null) {
            return false;
        }
        RouteRecord record = mRoutes.get(network);
        if (record == null) {
            return false;
        }
        pruneBenches(record, nowMs);
        if (record.failedReason != null && nowMs - record.failedUntilMs >= 0) {
            record.failedReason = null;
        }
        if (record.failedReason == null && record.benchedVideos.isEmpty()) {
            mRoutes.remove(network);
            return false;
        }
        return record.failedReason != null
                || (videoKey != null && record.benchedVideos.containsKey(videoKey));
    }

    private static void pruneBenches(RouteRecord record, long nowMs) {
        Iterator<Map.Entry<String, Long>> benches = record.benchedVideos.entrySet().iterator();
        while (benches.hasNext()) {
            if (nowMs - benches.next().getValue() >= 0) {
                benches.remove();
            }
        }
    }

    private static <V> void trim(Map<String, V> map) {
        Iterator<String> eldest = map.keySet().iterator();
        while (map.size() > MAX_NETWORKS && eldest.hasNext()) {
            eldest.next();
            eldest.remove();
        }
    }
}
