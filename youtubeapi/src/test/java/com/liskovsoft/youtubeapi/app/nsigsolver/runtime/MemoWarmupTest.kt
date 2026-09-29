package com.liskovsoft.youtubeapi.app.nsigsolver.runtime

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * NEWTUBE(v8-priority): the warm-up's steps against real solves, on the order a cold start sees
 * them (Mi 8, v17, 2026-09-29: the warm-up began 88 ms after the tap, the first answer to solve,
 * TV_TIZEN, came at +433 while the warm-up was still working). The runtime is a fake: each step
 * records its name, and [FakeSteps] plans them the way SolverMemo.warmupStep does (stage, then the
 * check's two halves).
 */
class MemoWarmupTest {
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val clock = AtomicLong(1_000)
    private val lane = V8Lane({ clock.get() }, {})
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val player = "https://example.invalid/s/player/aaaa/player.js"

    @After
    fun tearDown() {
        pool.shutdownNow()
    }

    /** Plans stage -> check-load -> check -> done; a step can be held "in flight" by a latch. */
    private inner class FakeSteps(
        private val hold: MemoWarmup.Step? = null,
        private val generationAtRun: () -> Long = { 1L }
    ) : MemoWarmup.Steps {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val releaseCount = AtomicLong()
        private val done = mutableListOf<MemoWarmup.Step>()

        override fun releases(): Long = releaseCount.get()

        override fun init(): Long {
            events.add("init")
            return 1L
        }

        override fun next(playerUrl: String): MemoWarmup.Step? =
            listOf(MemoWarmup.Step.STAGE, MemoWarmup.Step.CHECK_LOAD, MemoWarmup.Step.CHECK).firstOrNull { it !in done }

        override fun run(playerUrl: String, step: MemoWarmup.Step, generation: Long): Boolean {
            if (generation != generationAtRun()) {
                events.add("stopped")
                return false
            }
            events.add(step.label)
            if (step == hold) {
                running.countDown()
                await(release)
            }
            done.add(step)
            return true
        }
    }

    private fun solve(name: String) = pool.submit { lane.solve(V8Lane.KIND_SOLVE) { events.add(name) } }

    @Test
    fun theFirstSolveWaitsOnlyForTheStageInFlightNotForTheMemo() {
        val steps = FakeSteps(hold = MemoWarmup.Step.STAGE)
        val warmup = pool.submit { MemoWarmup(lane, graceMs = 60_000, quietMs = 0).run(player, steps) }
        await(steps.running)

        val first = solve("solve")
        waitUntil("the solve waits for the stage") { lane.pendingSolves() == 1 }
        steps.release.countDown()
        get(first)
        get(warmup)

        assertEquals(listOf("init", "stage", "solve", "check-load", "check"), events)
    }

    /**
     * The key property: with nothing in the runtime's way, the memo still does not start before the
     * open's first solve, so that solve never finds an evaluation in flight.
     */
    @Test
    fun theMemoIsHeldBackUntilTheFirstSolveHasRun() {
        val steps = FakeSteps()
        val warmup = pool.submit { MemoWarmup(lane, graceMs = 60_000, quietMs = 0).run(player, steps) }
        waitUntil("the first memo step waits") { lane.waitingWarmups() == 1 && events.size == 2 }
        assertEquals("the shared steps ran, the memo did not", listOf("init", "stage"), events)

        get(solve("solve"))
        get(warmup)
        assertEquals(listOf("init", "stage", "solve", "check-load", "check"), events)
    }

    @Test
    fun aLaterSolveWaitsForOneEvaluationAtMost() {
        val steps = FakeSteps(hold = MemoWarmup.Step.CHECK_LOAD)
        val warmup = pool.submit { MemoWarmup(lane, graceMs = 60_000, quietMs = 0).run(player, steps) }
        waitUntil("held back") { lane.waitingWarmups() == 1 && events.size == 2 }
        get(solve("first"))
        await(steps.running)

        val second = solve("second")
        waitUntil("the second solve waits") { lane.pendingSolves() == 1 }
        steps.release.countDown()
        get(second)
        get(warmup)

        assertEquals("it goes between the check's halves",
            listOf("init", "stage", "first", "check-load", "second", "check"), events)
    }

    @Test
    fun withoutASolveTheMemoIsCheckedAfterTheGrace() {
        val realLane = V8Lane({ System.nanoTime() / 1_000_000 }, {})
        assertTrue(get(pool.submit(Callable { MemoWarmup(realLane, graceMs = 30, quietMs = 0).run(player, FakeSteps()) })))
        assertEquals(listOf("init", "stage", "check-load", "check"), events)
    }

    @Test
    fun aSolveDuringTheSetUpCountsAsTheFirst() {
        // The gate is made before the runtime is set up: a solve that waited for that opens it.
        val initRunning = CountDownLatch(1)
        val initRelease = CountDownLatch(1)
        val base = FakeSteps()
        val steps = object : MemoWarmup.Steps by base {
            override fun init(): Long {
                val generation = base.init()
                initRunning.countDown()
                await(initRelease)
                return generation
            }
        }
        val warmup = pool.submit { MemoWarmup(lane, graceMs = 60_000, quietMs = 0).run(player, steps) }
        await(initRunning)
        val first = solve("solve")
        waitUntil("the solve waits for the init in flight") { lane.pendingSolves() == 1 }
        initRelease.countDown()
        get(first)
        get(warmup)
        assertEquals(listOf("init", "solve", "stage", "check-load", "check"), events)
    }

    @Test
    fun aReleasedRuntimeStopsTheWarmup() {
        var generation = 1L
        val steps = FakeSteps(generationAtRun = { generation })
        generation = 2L // released and re-created by a solve before the first step
        get(pool.submit { MemoWarmup(lane, graceMs = 0, quietMs = 0).run(player, steps) })
        assertEquals(listOf("init", "stopped"), events)
    }

    /**
     * A memory-trim release that takes the lane while the warm-up waits for its first turn wins:
     * the warm-up does not create the runtime again (Codex review, 2026-09-29).
     */
    @Test
    fun aReleaseAheadOfTheInitIsNotUndone() {
        val steps = FakeSteps()
        val releasing = CountDownLatch(1)
        val releaseGo = CountDownLatch(1)
        val release = pool.submit {
            lane.solve(V8Lane.KIND_RELEASE) {
                releasing.countDown()
                await(releaseGo)
                steps.releaseCount.incrementAndGet()
            }
        }
        await(releasing)
        val warmup = pool.submit(Callable { MemoWarmup(lane, graceMs = 0, quietMs = 0).run(player, steps) })
        waitUntil("the init waits behind the release") { lane.waitingWarmups() == 1 }
        releaseGo.countDown()
        get(release)

        assertTrue(get(warmup))
        assertEquals("no runtime set up, no step run", emptyList<String>(), events)

        // A release before the warm-up began is not one: a new open warms the runtime up again.
        assertTrue(get(pool.submit(Callable { MemoWarmup(lane, graceMs = 0, quietMs = 0).run(player, steps) })))
        assertEquals(listOf("init", "stage", "check-load", "check"), events)
    }

    @Test
    fun planningIsBounded() {
        val steps = object : MemoWarmup.Steps {
            override fun releases(): Long = 0L
            override fun init(): Long = 1L
            override fun next(playerUrl: String): MemoWarmup.Step = MemoWarmup.Step.STAGE
            override fun run(playerUrl: String, step: MemoWarmup.Step, generation: Long): Boolean {
                events.add(step.label) // a stage that another player's solve keeps replacing
                return true
            }
        }
        val finished = get(pool.submit(Callable { MemoWarmup(lane).run(player, steps) }))
        assertFalse("it gave up with a step still due", finished)
        assertEquals(MemoWarmup.MAX_STEPS, events.size)
        assertTrue(events.all { it == "stage" })
    }

    private fun await(latch: CountDownLatch) {
        if (!latch.await(5, TimeUnit.SECONDS)) {
            throw AssertionError("latch timed out; events=$events")
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
