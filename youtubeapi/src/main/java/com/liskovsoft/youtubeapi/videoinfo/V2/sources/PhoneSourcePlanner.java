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

    /**
     * NEWTUBE(recovery-kids): the only sources measured to serve a made-for-kids video: TV_TIZEN
     * (anonymous signed out, 15/15 on LTE; with the account signed in) and WEB_EMBED when TV_TIZEN
     * cannot. VISIONOS and ANDROID_VR refuse every one ({@link #refusesMadeForKids}); IOS,
     * ANDROID_REEL, MWEB, WEB and WEB_SAFARI answer them SABR-only (v20 on the emulator, both lanes:
     * 6 of 6 answers {@code usableAdaptive=0 hls=n sabr=y}, the recovery that asked them all first
     * took 8.3 s to its first frame).
     */
    private static final Set<AppClient> KIDS_SERVERS =
            Collections.unmodifiableSet(EnumSet.of(ACCOUNT_ROUTE, AppClient.WEB_EMBED));

    /**
     * NEWTUBE(recovery-kids): {@code client} is one of the only sources that serve a made-for-kids
     * video. A recovery whose suspect is another source is not a kids video's (they never serve
     * one), whatever VISIONOS said: the kids order is not applied (a music-only or paid video's
     * refusal has the same shape).
     */
    public static boolean servesMadeForKids(@Nullable AppClient client) {
        return client != null && KIDS_SERVERS.contains(client);
    }

    /** NEWTUBE(live-card): the live-DASH source (see VideoInfoService.isLiveDashCandidate). */
    public static final AppClient LIVE_SOURCE = AppClient.ANDROID_VR;

    /** What a walk knows before its first request. */
    public static final class Context {
        final Lane lane;
        @Nullable
        final AppClient recoverySuspect;
        final boolean anonChallenged;
        final boolean accountRouteBenched;
        final boolean accountRouteFirst;
        final boolean accountRouteHinted;
        final Set<AppClient> recoveryRefused;
        final boolean recoveryKidsRefused;
        final boolean liveCardHinted;
        final boolean vodVrLate;
        final Set<AppClient> benched;
        final Set<AppClient> refreshed;

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
            this(lane, recoverySuspect, anonChallenged, accountRouteBenched, accountRouteFirst,
                    accountRouteHinted, Collections.<AppClient>emptySet());
        }

        /**
         * @param recoveryRefused a recovery walk: the sources that refused this video moments ago
         *                        (RecentRefusals), asked after everything else, the suspect
         *                        included. Ignored outside a recovery walk
         */
        public Context(Lane lane, @Nullable AppClient recoverySuspect, boolean anonChallenged,
                boolean accountRouteBenched, boolean accountRouteFirst, boolean accountRouteHinted,
                Set<AppClient> recoveryRefused) {
            this(lane, recoverySuspect, anonChallenged, accountRouteBenched, accountRouteFirst,
                    accountRouteHinted, recoveryRefused, false, false);
        }

        /**
         * @param recoveryKidsRefused a recovery walk: VISIONOS or ANDROID_VR refused this video on
         *                            its content moments ago (the made-for-kids refusal,
         *                            RecentRefusals), so the sources that never serve such a video
         *                            are asked after the suspect. Ignored outside a recovery walk
         * @param liveCardHinted      the app opened this video from an item that says it is live:
         *                            the live-DASH source first, VISIONOS second (a stale flag costs
         *                            its one request; VideoInfoService sets a non-live answer
         *                            aside). Ignored in a recovery walk
         */
        public Context(Lane lane, @Nullable AppClient recoverySuspect, boolean anonChallenged,
                boolean accountRouteBenched, boolean accountRouteFirst, boolean accountRouteHinted,
                Set<AppClient> recoveryRefused, boolean recoveryKidsRefused, boolean liveCardHinted) {
            this(lane, recoverySuspect, anonChallenged, accountRouteBenched, accountRouteFirst,
                    accountRouteHinted, recoveryRefused, recoveryKidsRefused, liveCardHinted, false);
        }

        /**
         * @param vodVrLate NEWTUBE(vod-vr-late): ANDROID_VR, whose VOD media googlevideo cuts at
         *                  60.0 s for a walled visitor (6 of 6 at home, r11), goes after the VOD
         *                  sources that survive it: TV_TIZEN (planned third signed out, anonymously,
         *                  unless benched) and ANDROID_REEL. Not with a live card (ANDROID_VR is the
         *                  live winner; a live walk skips to it for free anyway)
         */
        public Context(Lane lane, @Nullable AppClient recoverySuspect, boolean anonChallenged,
                boolean accountRouteBenched, boolean accountRouteFirst, boolean accountRouteHinted,
                Set<AppClient> recoveryRefused, boolean recoveryKidsRefused, boolean liveCardHinted,
                boolean vodVrLate) {
            this(lane, recoverySuspect, anonChallenged, accountRouteBenched, accountRouteFirst,
                    accountRouteHinted, recoveryRefused, recoveryKidsRefused, liveCardHinted, vodVrLate,
                    Collections.<AppClient>emptySet(), Collections.<AppClient>emptySet());
        }

        /**
         * @param benched NEWTUBE(wall-memory): sources asked after everything else, the suspect and
         *                the recent refusals included: those whose media met the one-minute wall
         *                for the visitor they would send (PlaybackWallMemory), and in this video's
         *                recovery every source whose media 403'd on it. Kept, not dropped: a live
         *                video may need ANDROID_VR, and a video only a walled source serves still
         *                gets its first minute. A benched account route stays out
         */
        public Context(Lane lane, @Nullable AppClient recoverySuspect, boolean anonChallenged,
                boolean accountRouteBenched, boolean accountRouteFirst, boolean accountRouteHinted,
                Set<AppClient> recoveryRefused, boolean recoveryKidsRefused, boolean liveCardHinted,
                boolean vodVrLate, Set<AppClient> benched) {
            this(lane, recoverySuspect, anonChallenged, accountRouteBenched, accountRouteFirst,
                    accountRouteHinted, recoveryRefused, recoveryKidsRefused, liveCardHinted, vodVrLate,
                    benched, Collections.<AppClient>emptySet());
        }

        /**
         * @param refreshed NEWTUBE(playback-identity): a recovery walk's web-session sources whose
         *                  media 403'd on this video (the wall) but whose visitor has since been
         *                  re-rolled: asked right after WEB_EMBED and TV_TIZEN - which serve the old
         *                  visitor past the wall at no extra cost - and before ANDROID_REEL (360p
         *                  progressive on a walled visitor, r11) and ANDROID_VR. A fresh visitor
         *                  costs the web session's rebuild, and walls 3 in 14 at home
         */
        public Context(Lane lane, @Nullable AppClient recoverySuspect, boolean anonChallenged,
                boolean accountRouteBenched, boolean accountRouteFirst, boolean accountRouteHinted,
                Set<AppClient> recoveryRefused, boolean recoveryKidsRefused, boolean liveCardHinted,
                boolean vodVrLate, Set<AppClient> benched, Set<AppClient> refreshed) {
            this.lane = lane;
            this.recoverySuspect = recoverySuspect;
            this.anonChallenged = anonChallenged;
            this.accountRouteBenched = accountRouteBenched;
            this.accountRouteFirst = accountRouteFirst;
            this.accountRouteHinted = accountRouteHinted;
            this.recoveryRefused = recoveryRefused.isEmpty()
                    ? Collections.<AppClient>emptySet() : EnumSet.copyOf(recoveryRefused);
            this.recoveryKidsRefused = recoveryKidsRefused;
            this.liveCardHinted = liveCardHinted;
            this.vodVrLate = vodVrLate;
            this.benched = benched.isEmpty()
                    ? Collections.<AppClient>emptySet() : EnumSet.copyOf(benched);
            this.refreshed = refreshed.isEmpty()
                    ? Collections.<AppClient>emptySet() : EnumSet.copyOf(refreshed);
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

        // NEWTUBE(vod-vr-late): for VOD, ANDROID_VR is the one planned source whose media dies at the
        // one-minute wall (r11 at home, forced client, fresh visitors, 120 s: ANDROID_VR walled at
        // 60.0 s 6 of 6, same-client reloads never passed; VISIONOS, TV_TIZEN anonymous and
        // WEB_EMBED survived). So it goes after TV_TIZEN and ANDROID_REEL. Signed out TV_TIZEN is then
        // planned third, anonymously - after WEB_EMBED, which settles an age gate before it (it
        // refuses every one), and only reached when VISIONOS and WEB_EMBED did not serve.
        if (context.vodVrLate && !context.liveCardHinted) {
            if (context.lane == Lane.SIGNED_OUT && !context.accountRouteBenched && !order.contains(ACCOUNT_ROUTE)) {
                int embed = order.indexOf(AppClient.WEB_EMBED);
                order.add(embed >= 0 ? embed + 1 : order.size(), ACCOUNT_ROUTE);
            }
            // Then ANDROID_REEL, then ANDROID_VR, right after the later of WEB_EMBED and TV_TIZEN;
            // IOS (no URLs at home) and the Web family stay after them.
            boolean reel = order.remove(AppClient.ANDROID_REEL);
            boolean vr = order.remove(LIVE_SOURCE);
            int anchor = Math.max(order.indexOf(AppClient.WEB_EMBED), order.indexOf(ACCOUNT_ROUTE));
            int at = anchor >= 0 ? anchor + 1 : order.size();
            if (vr) {
                order.add(at, LIVE_SOURCE);
            }
            if (reel) {
                order.add(at, AppClient.ANDROID_REEL);
            }
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

        // NEWTUBE(live-card): the item the app opened says live. Every live walk used to ask
        // VISIONOS for an HLS answer it holds and never plays, then went straight to ANDROID_VR's
        // DASH manifest (11 of 11 live opens, ~110-200 ms on Wi-Fi, ~240-350 on LTE). VISIONOS right
        // after: a stale flag (a stream that just ended) costs one request, and the VOD order goes
        // on. Never a recovery walk (its suspect decides).
        if (context.liveCardHinted && context.recoverySuspect == null) {
            order.remove(LIVE_SOURCE);
            order.add(0, LIVE_SOURCE);
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

        // NEWTUBE(recovery-kids): the video was refused as made for kids moments ago, so only
        // TV_TIZEN and WEB_EMBED can serve it: every other source goes behind the suspect. v20 on
        // the emulator, both lanes: the recovery from WEB_EMBED's 403 with TV_TIZEN benched asked
        // ANDROID_VR (refused), IOS, ANDROID_REEL, MWEB, WEB and WEB_SAFARI (SABR only) before
        // WEB_EMBED served again at the seventh request, 8.3 s to the first frame. Kept, not dropped.
        if (suspect != null && context.recoveryKidsRefused) {
            List<AppClient> neverServe = new ArrayList<>();
            for (Iterator<AppClient> it = order.iterator(); it.hasNext(); ) {
                AppClient client = it.next();
                if (client != suspect && !KIDS_SERVERS.contains(client)) {
                    neverServe.add(client);
                    it.remove();
                }
            }
            order.addAll(neverServe);
        }

        // NEWTUBE(recovery-refusals): and a source that refused this video moments ago is asked
        // after that, only if nothing else serves: it would refuse it again (v16 LTE, the kids
        // video: VISIONOS refused it, TV_TIZEN served it, the recovery from TV_TIZEN's media 403
        // asked VISIONOS first, one refusal before WEB_EMBED served). Behind the suspect, which
        // served the video; kept, not dropped, in case the refusal was a moment's. Signed out that
        // includes the anonymous TV_TIZEN, which only the refusal rule puts in a walk: a recovery
        // does not let the rule re-admit one that refused (VideoInfoService), so it goes here,
        // unless benched.
        if (suspect != null && !context.recoveryRefused.isEmpty()) {
            List<AppClient> refused = new ArrayList<>();
            for (AppClient client : ORDER) {
                if (client == suspect || !context.recoveryRefused.contains(client)) {
                    continue;
                }
                if (order.remove(client) || (client == ACCOUNT_ROUTE && context.lane == Lane.SIGNED_OUT
                        && !context.accountRouteBenched)) {
                    refused.add(client);
                }
            }
            order.addAll(refused);
        }

        // NEWTUBE(playback-identity): a source this recovery found walled, asked again only with the
        // re-rolled visitor: after WEB_EMBED and TV_TIZEN (full quality on the old visitor, no
        // session rebuild), before ANDROID_REEL's 360p and ANDROID_VR.
        if (suspect != null && !context.refreshed.isEmpty()) {
            List<AppClient> again = new ArrayList<>();
            for (AppClient client : ORDER) {
                if (context.refreshed.contains(client) && !context.benched.contains(client)
                        && order.remove(client)) {
                    again.add(client);
                }
            }
            // before the first of the sources behind WEB_EMBED and TV_TIZEN still in their place
            int at = order.size();
            for (AppClient behind : java.util.Arrays.asList(AppClient.ANDROID_REEL, LIVE_SOURCE, AppClient.IOS)) {
                int index = order.indexOf(behind);
                if (index >= 0 && index < at) {
                    at = index;
                }
            }
            order.addAll(at, again);
        }

        // NEWTUBE(wall-memory): last of all, the sources that walled for this visitor or whose media
        // 403'd on this video (r11, MeJVWBSsPAY on a walled visitor: the recovery alternated
        // VISIONOS and ANDROID_VR - each walled at 60.0 s - because only the latest suspect went
        // last, and hit the reload cap; TV_TIZEN, never asked, plays that visitor past the wall).
        // An anonymous TV_TIZEN in the order goes here too; one the order does not hold is not added.
        if (!context.benched.isEmpty()) {
            List<AppClient> last = new ArrayList<>();
            for (AppClient client : ORDER) {
                if (!context.benched.contains(client)
                        // the wall is a VOD one: a live card keeps its live source first
                        || (client == LIVE_SOURCE && context.liveCardHinted && suspect == null)) {
                    continue;
                }
                if (order.remove(client)) { // never adds one the walk would not ask
                    last.add(client);
                }
            }
            order.addAll(last);
        }
        return order;
    }

    /**
     * NEWTUBE(live-card): {@code client} comes after the live-DASH source in the lane's own order
     * (IOS, ANDROID_REEL, MWEB, WEB, WEB_SAFARI). A live-hinted walk that set aside the live source's
     * answer to a video that was not live (a stale flag) plays that answer when it gets here: where
     * the lane would have asked the live source anyway.
     */
    public static boolean isPastLiveSourceTurn(AppClient client) {
        int index = ORDER.indexOf(client);
        return index > ORDER.indexOf(LIVE_SOURCE);
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
