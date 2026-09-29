package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * NEWTUBE(planner): the phone's /player order, signed in and signed out (netbench LANES.md). It
 * replaces the order that emerged from upstream's ring and a stack of phone gates (the audit behind
 * LANES.md counts about fifteen) with one chosen from measurements: one head that serves almost
 * everything, the routes measured to serve what it refuses, then an unproven tail.
 *
 * <p>A route is a source plus an identity. Only TV_TIZEN ever carries the account: it is the one
 * account shape that serves (netbench 2026-09-29, the owner's account: ordinary, both kinds of 18+
 * and made for kids, 4 of 4), where TVHTML5 7.x answers SABR-only and 5.x hands out media that 403s
 * on the first byte, 5 of 5. Every other source is anonymous by construction. So the two lanes
 * differ in one route only:
 * <ul>
 *   <li>signed in, TV_TIZEN carries the account and is always second;</li>
 *   <li>signed out, TV_TIZEN is asked without one, and only after an anonymous source refused the
 *   video on its content ({@link #admitsAccountRouteAfter}): anonymously it serves made-for-kids
 *   videos (11 of 11 on LTE) but refuses every age gate, and it is the bot wall's single probe of
 *   that identity.</li>
 * </ul>
 *
 * <p>Pure: the walk (VideoInfoService.firstPlayable) owns everything between requests - the bot
 * wall's plan, live-DASH skipping, consensus, budgets and the bot check - and asks this class the
 * questions that depend on the lane.
 */
public final class PhoneSourcePlanner {
    /** Whether the walk's requests may carry the account (read once per walk). */
    public enum Lane { SIGNED_OUT, SIGNED_IN }

    /** The one source that is asked with the account when there is one. */
    public static final AppClient ACCOUNT_ROUTE = AppClient.TV_TIZEN;

    /**
     * VISIONOS serves every ordinary category, fastest (no cipher, no ads). TV_TIZEN: see the class
     * comment. WEB_EMBED serves embeddable age-restricted videos signed out (the only anonymous
     * source that does) and made-for-kids videos when TV_TIZEN cannot. ANDROID_VR is the live-DASH
     * source (its VOD media hits the deep-range 403 on the device, so it is no real fallback). The
     * rest were never measured to serve anything the first four refuse (IOS, ANDROID_REEL: SABR-only
     * or 360p progressive) or need a PO token the app cannot mint for them yet (MWEB, WEB,
     * WEB_SAFARI); they are kept last rather than dropped. Out: GEO (refused everywhere), TV 7.x and
     * TV_DOWNGRADED (media dead, signed in and out), TV_EMBED ("no longer supported"), the other TV
     * fallback clients.
     */
    static final List<AppClient> ORDER = Collections.unmodifiableList(java.util.Arrays.asList(
            AppClient.VISIONOS, ACCOUNT_ROUTE, AppClient.WEB_EMBED, AppClient.ANDROID_VR,
            AppClient.IOS, AppClient.ANDROID_REEL, AppClient.MWEB, AppClient.WEB, AppClient.WEB_SAFARI));

    /**
     * The sources that serve an age-gated video in each lane: every other one answered the age gate
     * on both networks (netbench recap, signed out), and WEB_EMBED only serves the embeddable ones.
     * Signed in, the account route serves both kinds (2 of 2).
     */
    private static final Set<AppClient> AGE_GATE_SERVERS_SIGNED_OUT =
            Collections.unmodifiableSet(EnumSet.of(AppClient.WEB_EMBED));
    private static final Set<AppClient> AGE_GATE_SERVERS_SIGNED_IN =
            Collections.unmodifiableSet(EnumSet.of(ACCOUNT_ROUTE, AppClient.WEB_EMBED));

    /** What a walk knows before its first request. */
    public static final class Context {
        final Lane lane;
        @Nullable
        final AppClient recoverySuspect;
        final boolean anonChallenged;
        final boolean accountRouteBenched;
        final boolean accountRouteFirst;
        final boolean accountRouteHinted;

        /**
         * @param recoverySuspect     the source that served the watched video when its media
         *                            failed, for that video's recovery walk only; null otherwise
         * @param anonChallenged      the anonymous web identity is challenged on this network
         * @param accountRouteBenched signed in: the account route failed for this video or on this
         *                            network attachment (BotWallBook's route record)
         */
        public Context(Lane lane, @Nullable AppClient recoverySuspect, boolean anonChallenged,
                boolean accountRouteBenched) {
            this(lane, recoverySuspect, anonChallenged, accountRouteBenched, false);
        }

        /**
         * @param accountRouteFirst signed in: ask the account route first instead of second (the
         *                          A/B of LANES.md section 2.1; a debug switch, off by default)
         */
        public Context(Lane lane, @Nullable AppClient recoverySuspect, boolean anonChallenged,
                boolean accountRouteBenched, boolean accountRouteFirst) {
            this(lane, recoverySuspect, anonChallenged, accountRouteBenched, accountRouteFirst, false);
        }

        /**
         * @param accountRouteHinted either lane: this video's channel is remembered as one the
         *                           account route serves and VISIONOS refuses (KidsChannelMemory),
         *                           so the account route is asked first - anonymously signed out.
         *                           Ignored when benched and in a recovery walk: health and the
         *                           recovery order outrank it
         */
        public Context(Lane lane, @Nullable AppClient recoverySuspect, boolean anonChallenged,
                boolean accountRouteBenched, boolean accountRouteFirst, boolean accountRouteHinted) {
            this.lane = lane;
            this.recoverySuspect = recoverySuspect;
            this.anonChallenged = anonChallenged;
            this.accountRouteBenched = accountRouteBenched;
            this.accountRouteFirst = accountRouteFirst;
            this.accountRouteHinted = accountRouteHinted;
        }
    }

    private PhoneSourcePlanner() {
    }

    /** The order for a walk that starts now. Never empty. */
    @NonNull
    public static List<AppClient> order(@NonNull Context context) {
        List<AppClient> order = new ArrayList<>(ORDER);

        // Signed out the account route is admitted by a content refusal, not planned; signed in it
        // is planned unless it has just failed here.
        if (context.lane == Lane.SIGNED_OUT || context.accountRouteBenched) {
            order.remove(ACCOUNT_ROUTE);
        } else if (context.accountRouteFirst) {
            // Under test: the account's own answer first (Premium formats, the account's policy,
            // history credited without a second request), at the cost of its signature solve.
            order.remove(ACCOUNT_ROUTE);
            order.add(0, ACCOUNT_ROUTE);
        }

        // NEWTUBE(kids-channel): a video of this channel was refused by VISIONOS and served by the
        // account route, so this one asks the account route first - the refusal it skips is the
        // request it saves. Signed out that is the same anonymous ask the refusal would have
        // admitted next; signed in, the account route's own place. Never a benched route (health
        // outranks the hint) nor a recovery walk (its suspect decides).
        if (context.accountRouteHinted && !context.accountRouteBenched
                && context.recoverySuspect == null) {
            order.remove(ACCOUNT_ROUTE);
            order.add(0, ACCOUNT_ROUTE);
        }

        // The anonymous web identity is challenged here: every web client is a round trip to a
        // known refusal, so they go last (stable).
        if (context.anonChallenged) {
            List<AppClient> web = new ArrayList<>();
            for (Iterator<AppClient> it = order.iterator(); it.hasNext(); ) {
                AppClient client = it.next();
                if (client.isWebPotRequired()) {
                    web.add(client);
                    it.remove();
                }
            }
            order.addAll(web);
        }

        // A recovery walk asks the source whose media just failed last, not never: it may be the
        // only one that serves this video. Applied last, so nothing moves in behind it. Signed out
        // that includes TV_TIZEN (it served the video after a refusal, and the refusal rule must
        // not put it straight back next); a benched account route stays out.
        AppClient suspect = context.recoverySuspect;
        if (suspect != null && order.remove(suspect)) {
            order.add(suspect);
        } else if (suspect == ACCOUNT_ROUTE && context.lane == Lane.SIGNED_OUT
                && !context.accountRouteBenched) {
            order.add(suspect);
        }
        return order;
    }

    /**
     * Whether an anonymous answer from {@code client} that refused the video on its content
     * (UNPLAYABLE, "not available": made-for-kids videos on VISIONOS and ANDROID_VR) puts the account
     * route, without the account, next. Signed out only: signed in it is already in the order, with
     * the account. Web sources are excluded: their refusals are about the web identity or the embed
     * policy, which TV_TIZEN's answer does not share.
     */
    public static boolean admitsAccountRouteAfter(Lane lane, AppClient client) {
        return lane == Lane.SIGNED_OUT && client != ACCOUNT_ROUTE && !client.isWebPotRequired();
    }

    /**
     * NEWTUBE(kids-channel): the sources measured to refuse every made-for-kids video ("This video
     * is not available", 6 of 6 each on the harness, and every kids walk on the Pixel) while
     * serving ordinary ones: their content refusal before an account-route serve is
     * KidsChannelMemory's proof, and their serve is evidence against a remembered channel. Not
     * IOS or ANDROID_REEL: they answer kids videos with SABR only or progressive formats rather
     * than refuse them (netbench recap), so neither their refusal nor their serve says "kids".
     */
    public static boolean refusesMadeForKids(AppClient client) {
        return client == AppClient.VISIONOS || client == AppClient.ANDROID_VR;
    }

    /**
     * An age gate is settled - no source left in this walk can serve it - once every source of the
     * lane that serves age-gated videos has answered without serving it, or is not in this walk
     * (benched). Its answer need not be the age gate itself: for a video that is also not
     * embeddable WEB_EMBED answers with the embed refusal. A source that was asked and did not
     * answer (a timeout) has refused nothing, so the walk goes on.
     *
     * @param ageGated  whether any answer of this walk was an age gate
     * @param refused   the sources that answered this walk without serving the video
     * @param attempted the sources asked so far
     * @param remaining the sources the walk would still ask
     */
    public static boolean isAgeGateSettled(Lane lane, boolean ageGated, Set<AppClient> refused,
            Set<AppClient> attempted, List<AppClient> remaining) {
        if (!ageGated) {
            return false;
        }
        for (AppClient server : lane == Lane.SIGNED_IN
                ? AGE_GATE_SERVERS_SIGNED_IN : AGE_GATE_SERVERS_SIGNED_OUT) {
            if (refused.contains(server)) {
                continue;
            }
            if (attempted.contains(server) || remaining.contains(server)) {
                return false;
            }
        }
        return true;
    }
}
