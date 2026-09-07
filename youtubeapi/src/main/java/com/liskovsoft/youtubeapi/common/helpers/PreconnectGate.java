package com.liskovsoft.youtubeapi.common.helpers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Small, monotonic-clock gate; its owner serializes calls and cancels discarded requests. */
final class PreconnectGate {
    private static final int MAX_HOSTS = 4;
    private static final int MAX_IN_FLIGHT = 2;
    private static final long SUCCESS_FRESH_MS = 60_000;
    private static final long FAILURE_COOLDOWN_MS = 5_000;

    static final class Attempt {
        final String host;
        private boolean inFlight = true;
        private long retryAtMs;

        private Attempt(String host) {
            this.host = host;
        }
    }

    private final Map<String, Attempt> mHosts = new LinkedHashMap<>();
    private Object mNetwork;

    /** A warm connection belongs to its network. Returns old live attempts for cancellation. */
    List<Attempt> changeNetwork(Object network) {
        List<Attempt> discarded = new ArrayList<>();
        if (!Objects.equals(mNetwork, network)) {
            for (Attempt attempt : mHosts.values()) {
                if (attempt.inFlight) {
                    discarded.add(attempt);
                }
            }
            mHosts.clear();
            mNetwork = network;
        }
        return discarded;
    }

    Attempt tryStart(String host, long nowMs) {
        Attempt previous = mHosts.get(host);
        if (previous != null && (previous.inFlight || nowMs < previous.retryAtMs)) {
            return null;
        }
        int inFlight = 0;
        for (Attempt attempt : mHosts.values()) {
            if (attempt.inFlight) {
                inFlight++;
            }
        }
        if (inFlight >= MAX_IN_FLIGHT) {
            return null;
        }
        if (previous == null && mHosts.size() >= MAX_HOSTS) {
            Iterator<Attempt> iterator = mHosts.values().iterator();
            while (iterator.hasNext()) {
                if (!iterator.next().inFlight) {
                    iterator.remove();
                    break;
                }
            }
        }
        Attempt attempt = new Attempt(host);
        mHosts.remove(host);
        mHosts.put(host, attempt);
        return attempt;
    }

    /** Duplicate and late completions cannot refresh another attempt's lifetime. */
    boolean complete(Attempt attempt, boolean succeeded, long nowMs) {
        if (mHosts.get(attempt.host) != attempt || !attempt.inFlight) {
            return false;
        }
        attempt.inFlight = false;
        attempt.retryAtMs = nowMs + (succeeded ? SUCCESS_FRESH_MS : FAILURE_COOLDOWN_MS);
        return true;
    }
}
