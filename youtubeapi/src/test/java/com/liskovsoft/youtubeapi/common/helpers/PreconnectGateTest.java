package com.liskovsoft.youtubeapi.common.helpers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** Deterministic time/network transitions; no external requests or Android runtime required. */
public class PreconnectGateTest {
    private final PreconnectGate mGate = new PreconnectGate();

    @Test
    public void failedWarmDoesNotPermanentlySuppressTheSameHost() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt failed = mGate.tryStart("media.invalid", 100);
        assertTrue(mGate.complete(failed, false, 500));
        assertNull(mGate.tryStart("media.invalid", 5_499));

        PreconnectGate.Attempt retry = mGate.tryStart("media.invalid", 5_500);
        assertNotNull(retry);
        assertNotSame(failed, retry);
    }

    @Test
    public void successfulWarmExpiresFromCompletionTime() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt attempt = mGate.tryStart("media.invalid", 100);
        mGate.complete(attempt, true, 4_500);
        assertNull(mGate.tryStart("media.invalid", 64_499));
        assertNotNull(mGate.tryStart("media.invalid", 64_500));
    }

    @Test
    public void earlyAndLateCallsDeduplicateWhileInFlightAndAfterSuccess() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt attempt = mGate.tryStart("media.invalid", 100);
        assertNotNull(attempt);
        assertNull(mGate.tryStart("media.invalid", 500));
        assertNull(mGate.tryStart("media.invalid", 4_000));
        mGate.complete(attempt, true, 4_500);
        assertNull(mGate.tryStart("media.invalid", 5_000));
    }

    @Test
    public void replacementNetworkImmediatelyRewarmsTheSameHost() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt old = mGate.tryStart("media.invalid", 100);
        mGate.complete(old, true, 500);
        mGate.changeNetwork("cellular");
        assertNotNull(mGate.tryStart("media.invalid", 600));
    }

    @Test
    public void equalNetworkReplayPreservesFreshness() {
        mGate.changeNetwork(new String("wifi"));
        PreconnectGate.Attempt attempt = mGate.tryStart("media.invalid", 100);
        mGate.complete(attempt, true, 500);
        assertTrue(mGate.changeNetwork(new String("wifi")).isEmpty());
        assertNull(mGate.tryStart("media.invalid", 600));
    }

    @Test
    public void networkReplacementReturnsAllLiveRequestsForCancellationAndFreesCapacity() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt first = mGate.tryStart("one.invalid", 100);
        PreconnectGate.Attempt second = mGate.tryStart("two.invalid", 100);
        assertEquals(List.of(first, second), mGate.changeNetwork("cellular"));
        assertNotNull(mGate.tryStart("one.invalid", 200));
        assertNotNull(mGate.tryStart("two.invalid", 200));
    }

    @Test
    public void staleCompletionCannotExtendAReplacementNetworksFreshness() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt old = mGate.tryStart("media.invalid", 100);
        mGate.changeNetwork("cellular");
        PreconnectGate.Attempt current = mGate.tryStart("media.invalid", 200);
        mGate.complete(current, true, 500);

        assertFalse(mGate.complete(old, true, 60_000));
        assertNotNull(mGate.tryStart("media.invalid", 60_500));
    }

    @Test
    public void staleFailureAndSuccessCannotCompleteANewerAttemptForTheSameHost() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt old = mGate.tryStart("media.invalid", 100);
        mGate.complete(old, false, 500);
        PreconnectGate.Attempt current = mGate.tryStart("media.invalid", 5_500);
        assertFalse(mGate.complete(old, false, 6_000));
        assertFalse(mGate.complete(old, true, 6_001));
        assertNull(mGate.tryStart("media.invalid", 90_000)); // current is still in flight
        assertTrue(mGate.complete(current, true, 90_000));
    }

    @Test
    public void duplicateSuccessDoesNotKeepExtendingFreshness() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt attempt = mGate.tryStart("media.invalid", 100);
        assertTrue(mGate.complete(attempt, true, 500));
        assertFalse(mGate.complete(attempt, true, 60_000));
        assertNotNull(mGate.tryStart("media.invalid", 60_500));
    }

    @Test
    public void concurrentRequestsAreBoundedAndFailureReleasesASlot() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt first = mGate.tryStart("one.invalid", 100);
        assertNotNull(mGate.tryStart("two.invalid", 100));
        assertNull(mGate.tryStart("three.invalid", 100));
        mGate.complete(first, false, 8_100); // the request's deadline takes the failure path
        assertNotNull(mGate.tryStart("three.invalid", 8_100));
    }

    @Test
    public void hostMemoryIsBoundedWithoutEvictingALiveRequest() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt live = mGate.tryStart("live.invalid", 100);
        for (String host : List.of("one.invalid", "two.invalid", "three.invalid", "four.invalid")) {
            PreconnectGate.Attempt attempt = mGate.tryStart(host, 200);
            assertNotNull(attempt);
            mGate.complete(attempt, true, 200);
        }
        assertNull(mGate.tryStart("live.invalid", 300));
        assertNull(mGate.tryStart("four.invalid", 300));
        assertNotNull(mGate.tryStart("one.invalid", 300)); // oldest completed host was evicted
        assertTrue(mGate.complete(live, true, 300));
    }

    @Test
    public void observedDisconnectInvalidatesWarmthEvenIfTheSameNetworkReturns() {
        mGate.changeNetwork("wifi");
        PreconnectGate.Attempt attempt = mGate.tryStart("media.invalid", 100);
        mGate.complete(attempt, true, 500);
        mGate.changeNetwork(null);
        mGate.changeNetwork("wifi");
        assertNotNull(mGate.tryStart("media.invalid", 600));
    }
}
