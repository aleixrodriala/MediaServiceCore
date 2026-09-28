package com.liskovsoft.youtubeapi.videoinfo.V2.sources;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.liskovsoft.youtubeapi.common.helpers.AppClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * NEWTUBE(planner): the phone's signed-out /player order, written down. It replaces the order that
 * emerged from upstream's ring and a stack of phone gates (netbench PLANNER.md section 1) with one
 * chosen from measurements (PLANNER.md section 2, netbench recap): one head that serves almost
 * everything, the sources measured to serve what it refuses, then an unproven tail.
 *
 * <p>Signed out only: there is no signed-in measurement at all, so a signed-in walk keeps today's
 * order, account head and recovery rules untouched. TV_TIZEN is not in the list: the walk asks it,
 * without the account, right after an anonymous refusal (the rule behind
 * VideoInfoService.setAnonTizenAfterRefusal, with its wall and route-failure guards) - measured for
 * made-for-kids videos, and never sent for a timeout, an age gate or a removed video.
 *
 * <p>Pure: the walk (VideoInfoService.firstPlayable) still owns everything that happens between
 * requests - the bot wall's plan, the TV_TIZEN rule, live-DASH skipping, consensus, budgets and the
 * bot check. This only answers "in which order", for the walk that is about to start.
 */
public final class PhoneSourcePlanner {
    /**
     * VISIONOS serves every ordinary category; WEB_EMBED serves embeddable age-restricted videos and
     * made-for-kids videos TV_TIZEN does not; ANDROID_VR is the live-DASH client (its VOD media hits
     * the deep-range 403 wall on the device, so it is no real fallback). The rest were never
     * measured to serve anything VISIONOS refuses (IOS, ANDROID_REEL: SABR-only or 360p progressive)
     * or need a PO token the app cannot mint for them yet (MWEB, WEB, WEB_SAFARI); they are kept
     * last rather than dropped. Out: GEO (refused everywhere), TV 7.x (media refused; its
     * made-for-kids answer is an explicit "not a bot"), the TV fallback clients.
     */
    static final List<AppClient> SIGNED_OUT = Collections.unmodifiableList(java.util.Arrays.asList(
            AppClient.VISIONOS, AppClient.WEB_EMBED, AppClient.ANDROID_VR,
            AppClient.IOS, AppClient.ANDROID_REEL, AppClient.MWEB, AppClient.WEB, AppClient.WEB_SAFARI));

    /** What a signed-out walk knows before its first request. */
    public static final class Context {
        @Nullable
        final AppClient recoverySuspect;
        final boolean anonChallenged;

        /**
         * @param recoverySuspect the client that served the watched video when its media failed,
         *                        for that video's recovery walk only; null otherwise
         * @param anonChallenged  the anonymous web identity is challenged on this network
         */
        public Context(@Nullable AppClient recoverySuspect, boolean anonChallenged) {
            this.recoverySuspect = recoverySuspect;
            this.anonChallenged = anonChallenged;
        }
    }

    private PhoneSourcePlanner() {
    }

    /** The order for a signed-out walk that starts now. Never empty. */
    @NonNull
    public static List<AppClient> order(@NonNull Context context) {
        List<AppClient> order = new ArrayList<>(SIGNED_OUT);

        // The anonymous web identity is challenged here: every web client is a round trip to a
        // known refusal, so they go last (stable), as in the ring.
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

        // A recovery walk asks the client whose media just failed last, not never: it may be the
        // only one that serves this video. Applied last, so nothing moves in behind it.
        AppClient suspect = context.recoverySuspect;
        if (suspect != null && order.remove(suspect)) {
            order.add(suspect);
        }
        return order;
    }
}
