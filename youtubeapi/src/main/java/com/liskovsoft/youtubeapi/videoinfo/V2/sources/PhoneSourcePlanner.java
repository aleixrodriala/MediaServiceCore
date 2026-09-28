package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * NEWTUBE(planner): the phone's /player order, written down. It replaces the order that emerged
 * from upstream's ring and a stack of phone gates (netbench PLANNER.md section 1) with lanes chosen
 * from measurements (PLANNER.md section 2, netbench recap): one head that serves almost everything,
 * then the sources measured to serve what it refuses, then an unproven tail.
 *
 * <p>Pure: the walk (VideoInfoService.firstPlayable) still owns everything that happens between
 * requests - the bot wall's plan, live-DASH skipping, the age-gate skip, consensus, budgets and
 * the bot check. This only answers "in which order", for the walk that is about to start.
 */
public final class PhoneSourcePlanner {
    /**
     * Signed out, and the tail of every signed-in walk. VISIONOS serves every ordinary category;
     * TV_TIZEN (the account route, asked without the account when signed out) serves made-for-kids
     * videos; WEB_EMBED serves embeddable age-restricted videos and kids when TV_TIZEN does not;
     * ANDROID_VR is the live-DASH client and the one other general client. The rest were never
     * measured to serve anything VISIONOS refuses (IOS, ANDROID_REEL) or need a PO token the app
     * cannot mint for them yet (MWEB, WEB, WEB_SAFARI), and are kept last rather than dropped.
     */
    static final List<AppClient> SIGNED_OUT = Collections.unmodifiableList(java.util.Arrays.asList(
            AppClient.VISIONOS, AppClient.TV_TIZEN, AppClient.WEB_EMBED, AppClient.ANDROID_VR,
            AppClient.IOS, AppClient.ANDROID_REEL, AppClient.MWEB, AppClient.WEB, AppClient.WEB_SAFARI));

    /** The account-bearing TVHTML5 head (VideoInfoService.AUTHENTICATED_HEAD), first to last. */
    static final List<AppClient> ACCOUNT_HEAD = Collections.unmodifiableList(java.util.Arrays.asList(
            AppClient.TV_DOWNGRADED, AppClient.TV));

    /** What the walk knows before its first request. */
    public static final class Context {
        final boolean authenticated;
        @Nullable
        final AppClient recoverySuspect;
        final Set<AppClient> quarantinedHeads;
        final boolean anonChallenged;
        final boolean webEmbedCarriesAccount;

        /**
         * @param recoverySuspect the client that served the watched video when its media failed,
         *                        for that video's recovery walk only; null otherwise
         * @param quarantinedHeads account heads refused on this transport (AuthRouteQuarantineBook)
         * @param anonChallenged   the anonymous web identity is challenged on this network
         * @param webEmbedCarriesAccount WEB_EMBED carries the account (debug switch)
         */
        public Context(boolean authenticated, @Nullable AppClient recoverySuspect,
                @Nullable Set<AppClient> quarantinedHeads, boolean anonChallenged,
                boolean webEmbedCarriesAccount) {
            this.authenticated = authenticated;
            this.recoverySuspect = recoverySuspect;
            this.quarantinedHeads = quarantinedHeads != null
                    ? quarantinedHeads : Collections.<AppClient>emptySet();
            this.anonChallenged = anonChallenged;
            this.webEmbedCarriesAccount = webEmbedCarriesAccount;
        }
    }

    private PhoneSourcePlanner() {
    }

    /** The order for a walk that starts now. Never empty. */
    @NonNull
    public static List<AppClient> order(@NonNull Context context) {
        List<AppClient> order = context.authenticated ? signedIn(context) : new ArrayList<>(SIGNED_OUT);

        // A recovery walk asks the client whose media just failed last, not never: it may be the
        // only one that serves this video.
        AppClient suspect = context.recoverySuspect;
        if (suspect != null && order.remove(suspect)) {
            order.add(suspect);
        }

        // The anonymous web identity is challenged here: every web client asked without the
        // account is a round trip to a known refusal, so they go last (stable).
        if (context.anonChallenged) {
            List<AppClient> web = new ArrayList<>();
            for (java.util.Iterator<AppClient> it = order.iterator(); it.hasNext(); ) {
                AppClient client = it.next();
                if (client.isWebPotRequired()) {
                    web.add(client);
                    it.remove();
                }
            }
            order.addAll(web);
        }
        return order;
    }

    /**
     * The account head first (healthy heads, then - behind VISIONOS - a quarantined one whose
     * sibling is healthy), as VideoInfoService orders it today; then the signed-out lane, where
     * TV_TIZEN carries the account. With every head quarantined the walk leads with VISIONOS (or
     * WEB_EMBED when it carries the account) and asks the heads again only after the measured lane.
     * A recovery walk starts with VISIONOS: the account still gets its turn through TV_TIZEN and the
     * healthy head, and the suspect goes last.
     */
    private static List<AppClient> signedIn(Context context) {
        List<AppClient> healthy = new ArrayList<>();
        List<AppClient> quarantined = new ArrayList<>();
        for (AppClient head : ACCOUNT_HEAD) {
            (context.quarantinedHeads.contains(head) ? quarantined : healthy).add(head);
        }

        List<AppClient> order = new ArrayList<>();
        boolean recovery = context.recoverySuspect != null;
        if (!recovery && !healthy.isEmpty()) {
            order.addAll(healthy);
            order.add(AppClient.VISIONOS);
            order.addAll(quarantined);
            appendMissing(order, SIGNED_OUT);
            return order;
        }

        if (!recovery && context.webEmbedCarriesAccount) {
            order.add(AppClient.WEB_EMBED);
        }
        order.add(AppClient.VISIONOS);
        if (recovery) {
            // The account's healthy head right behind the token-free client, as before.
            order.addAll(healthy);
        }
        List<AppClient> tail = new ArrayList<>(SIGNED_OUT);
        // The heads are re-tested after the measured part of the lane, before the unproven tail.
        int unproven = tail.indexOf(AppClient.IOS);
        tail.addAll(unproven, recovery ? quarantined : withAll(healthy, quarantined));
        appendMissing(order, tail);
        return order;
    }

    private static List<AppClient> withAll(List<AppClient> first, List<AppClient> second) {
        List<AppClient> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static void appendMissing(List<AppClient> order, List<AppClient> more) {
        Set<AppClient> present = order.isEmpty()
                ? EnumSet.noneOf(AppClient.class) : EnumSet.copyOf(order);
        for (AppClient client : more) {
            if (present.add(client)) {
                order.add(client);
            }
        }
    }
}
