package com.liskovsoft.youtubeapi.app.nsigsolver.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * NEWTUBE(v8-priority): who gets the kept V8 runtime next. No V8 here: each turn is a block that
 * records its name, and latches hold a turn "in flight" for as long as a test needs. Every
 * interleaving below is forced (a turn is released only once the other thread is seen waiting), so
 * the orders asserted are the only ones the lane allows.
 */
class V8LaneTest {
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val logs: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val clock = AtomicLong(1_000)
    private val lane = V8Lane({ clock.get() }, { logs.add(it) })
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    @Test
    fun aSolveWaitsOnlyForTheStepInFlightAndGoesBeforeTheNextOne() {
        val stageRunning = CountDownLatch(1)
        val stageDone = CountDownLatch(1)
        val warmup = pool.submit {
            lane.warmup("stage", null) {
                events.add("stage")
                stageRunning.countDown()
                await(stageDone)
            }
            lane.warmup("next", null) { events.add("next") }
        }
        await(stageRunning)
        clock.addAndGet(40)
        val solve = pool.submit { lane.solve(V8Lane.KIND_SOLVE) { events.add("solve") } }
        waitUntil("the solve waits") { lane.pendingSolves() == 1 }

        clock.addAndGet(60)
        stageDone.countDown()
        get(solve)
        get(warmup)

        assertEquals(listOf("stage", "solve", "next"), events)
        assertTrue(logs.toString(), logs.any { Regex("v8-queue kind=solve waitMs=(0|60) behind=warmup:stage").matches(it) })
        assertTrue(logs.toString(), logs.any { it.startsWith("v8-queue kind=warmup step=next waitMs=0 yielded=") })
    }

    @Test
    fun everyPendingSolveGoesBeforeTheNextWarmupStep() {
        val stageRunning = CountDownLatch(1)
        val stageDone = CountDownLatch(1)
        val warmup = pool.submit {
            lane.warmup("stage", null) {
                stageRunning.countDown()
                await(stageDone)
            }
            lane.warmup("check", null) { events.add("check") }
        }
        await(stageRunning)
        val solves = (1..3).map { i -> pool.submit { lane.solve(V8Lane.KIND_SOLVE) { events.add("solve") } } }
        waitUntil("three solves wait") { lane.pendingSolves() == 3 }

        stageDone.countDown()
        solves.forEach { get(it) }
        get(warmup)

        assertEquals(listOf("solve", "solve", "solve", "check"), events)
    }

    @Test
    fun aWarmupStepNeverStartsWhileASolveIsAnnounced() {
        // A solve between two turns (it downloads the player): nothing holds the runtime, yet the
        // warm-up may not take it.
        val betweenTurns = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val solve = pool.submit {
            lane.announce {
                lane.solve(V8Lane.KIND_SOLVE) { events.add("solve-1") }
                betweenTurns.countDown()
                await(resume)
                lane.solve(V8Lane.KIND_SOLVE) { events.add("solve-2") }
            }
        }
        await(betweenTurns)
        val warmup = pool.submit { lane.warmup("init", null) { events.add("init") } }
        waitUntil("the warm-up step waits") { lane.waitingWarmups() == 1 }
        assertEquals(listOf("solve-1"), events)

        resume.countDown()
        get(solve)
        get(warmup)
        assertEquals(listOf("solve-1", "solve-2", "init"), events)
    }

    @Test
    fun aMemoStepWaitsForTheFirstSolveNotForTheRuntime() {
        val gate = lane.memoGate(graceMs = 60_000, quietMs = 0)
        val checked = AtomicBoolean()
        val warmup = pool.submit { lane.warmup("check-load", gate) { checked.set(true); events.add("check-load") } }
        waitUntil("the memo step is held back") { lane.waitingWarmups() == 1 }
        assertFalse("nothing holds the runtime, but no solve has run yet", checked.get())

        // A solve that comes now does not wait at all: the memo step has not started.
        lane.solve(V8Lane.KIND_SOLVE) { events.add("solve") }
        get(warmup)

        assertEquals(listOf("solve", "check-load"), events)
        assertTrue(logs.toString(), logs.contains("v8-queue kind=solve waitMs=0 behind=none"))
    }

    @Test
    fun aSolveThatEndedBeforeTheGateWasMadeDoesNotOpenIt() {
        lane.solve(V8Lane.KIND_SOLVE) { events.add("earlier") }
        val gate = lane.memoGate(graceMs = 60_000, quietMs = 0)
        val warmup = pool.submit { lane.warmup("check", gate) { events.add("check") } }
        waitUntil("held back") { lane.waitingWarmups() == 1 }
        assertEquals(listOf("earlier"), events)

        lane.solve(V8Lane.KIND_SOLVE) { events.add("solve") }
        get(warmup)
        assertEquals(listOf("earlier", "solve", "check"), events)
    }

    @Test
    fun withoutASolveTheMemoStepGoesAfterTheGrace() {
        val realLane = V8Lane({ System.nanoTime() / 1_000_000 }, { logs.add(it) })
        val gate = realLane.memoGate(graceMs = 30, quietMs = 0)
        val warmup = pool.submit { realLane.warmup("check-load", gate) { events.add("check-load") } }
        get(warmup)
        assertEquals(listOf("check-load"), events)
    }

    @Test
    fun aMemoStepWaitsForAQuietAfterTheLastSolve() {
        // The fake clock only moves when the test moves it: the step cannot go before.
        val gate = lane.memoGate(graceMs = 0, quietMs = 50)
        lane.solve(V8Lane.KIND_SOLVE) { events.add("solve") }
        val warmup = pool.submit { lane.warmup("check", gate) { events.add("check") } }
        waitUntil("held back by the quiet") { lane.waitingWarmups() == 1 }
        clock.addAndGet(49)
        Thread.sleep(120) // two of its timed waits: still 1 ms of quiet left
        assertEquals(listOf("solve"), events)

        clock.addAndGet(1)
        get(warmup)
        assertEquals(listOf("solve", "check"), events)
    }

    @Test
    fun turnsAreReentrantForTheirThread() {
        lane.solve(V8Lane.KIND_SOLVE) {
            lane.solve(V8Lane.KIND_SOLVE) { events.add("inner solve") }
        }
        lane.warmup("init", null) {
            lane.solve(V8Lane.KIND_SOLVE) { events.add("solve in a step") }
        }
        assertEquals(listOf("inner solve", "solve in a step"), events)
        assertEquals(0, lane.pendingSolves())
    }

    @Test
    fun aFailingTurnFreesTheRuntime() {
        try {
            lane.solve(V8Lane.KIND_SOLVE) { throw IllegalStateException("boom") }
            fail()
        } catch (e: IllegalStateException) {
            // expected
        }
        try {
            lane.warmup("stage", null) { throw IllegalStateException("boom") }
            fail()
        } catch (e: IllegalStateException) {
            // expected
        }
        get(pool.submit { lane.warmup("next", null) { events.add("next") } })
        get(pool.submit { lane.solve(V8Lane.KIND_SOLVE) { events.add("solve") } })
        assertEquals(listOf("next", "solve"), events)
    }

    /**
     * NEWTUBE(player-js-gate): a new player's validation is a solve that runs while its thread
     * holds AppServiceIntCached's player lock, and requests wait on that lock. The warm-up never
     * takes that lock, so the three cannot wait for each other in a circle.
     */
    @Test
    fun aValidationUnderThePlayerLockCannotDeadlockWithTheWarmup() {
        val playerLock = Any()
        val validating = CountDownLatch(1)
        val validationGo = CountDownLatch(1)
        val stageRunning = CountDownLatch(1)
        val stageDone = CountDownLatch(1)
        // The fake clock holds the memo step in its quiet until the request has solved too.
        val gate = lane.memoGate(graceMs = 60_000, quietMs = 10)
        val warmup = pool.submit {
            lane.warmup("stage", null) {
                events.add("stage")
                stageRunning.countDown()
                await(stageDone)
            }
            lane.warmup("check-load", gate) { events.add("check-load") }
        }
        await(stageRunning)
        val validation = pool.submit {
            synchronized(playerLock) {
                validating.countDown()
                await(validationGo)
                lane.solve(V8Lane.KIND_SOLVE) { events.add("validation") }
            }
        }
        await(validating)
        val request = pool.submit {
            synchronized(playerLock) { events.add("extractor") }
            lane.solve(V8Lane.KIND_SOLVE) { events.add("request-solve") }
        }
        validationGo.countDown()
        waitUntil("the validation waits for the step in flight") { lane.pendingSolves() == 1 }
        stageDone.countDown()

        get(validation)
        get(request)
        clock.addAndGet(10)
        get(warmup)
        assertEquals(listOf("stage", "validation", "extractor", "request-solve", "check-load"), events)
    }

    private fun await(latch: CountDownLatch) {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw AssertionError("latch timed out")
        }
    }

    private fun <T> get(future: Future<T>): T = future.get(5, TimeUnit.SECONDS)

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) {
                fail("timed out: $what; events=$events")
            }
            Thread.sleep(1)
        }
    }
}
