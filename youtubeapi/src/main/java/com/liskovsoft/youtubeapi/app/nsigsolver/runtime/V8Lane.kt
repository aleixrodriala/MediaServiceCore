package com.liskovsoft.youtubeapi.app.nsigsolver.runtime

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * NEWTUBE(v8-priority): who gets the kept V8 runtime next.
 *
 * A J2V8 runtime runs one script at a time, and the solver's runtime serves two kinds of work: the
 * solves somebody waits for, and the warm-up that prepares later ones. Behind one monitor, the
 * first solve of a cold process waited for the whole warm-up: the memo's evaluation and cross-check
 * of the player, ~1 s in one hold on a Mi 8 (Snapdragon 845, v17, 2026-09-29), so `player-sig` was
 * 655-792 ms with the memo against 545-563 ms without it, for a solve that then took 2 ms.
 *
 * Two kinds of turns:
 * - [solve]: anything a user waits for (a /player answer's n/sig, the HLS n, a new player's
 *   validation, a release on memory pressure). It waits for the turn in flight only, and goes
 *   before every warm-up step that has not started.
 * - [warmup]: a step starts only when nothing holds the runtime and no solve is pending. A memo
 *   step (a [MemoGate]) is also held back until a solve finished after the warm-up began, or the
 *   gate's grace passed, and until a short quiet after the last solve.
 *
 * So a solve waits at most for one warm-up step, and the warm-up splits its work so that the
 * longest step is one player evaluation (see MemoWarmup). Turns are reentrant for the thread that
 * holds one, and a solve's wait is not interruptible, like the monitor this replaces.
 */
internal class V8Lane(
    private val nowMs: () -> Long,
    private val log: (String) -> Unit
) {
    private val lock = ReentrantLock()
    private val changed: Condition = lock.newCondition()
    private var owner: Thread? = null
    private var holds = 0
    private var ownerLabel = NONE
    // Solves announced and not finished: waiting for a turn, holding one, or between two turns.
    private var solvesPending = 0
    private var solvesFinished = 0L
    private var lastSolveEndMs = 0L
    private var anySolveEnded = false
    // Warm-up steps waiting for their turn (for the tests).
    private var warmupsWaiting = 0

    /**
     * Holds the memo steps back until a solve finished after the gate was made, or [graceMs] passed;
     * then each memo step also waits [quietMs] after the last solve. See [warmup].
     */
    class MemoGate internal constructor(
        internal val solvesAtStart: Long,
        internal val graceUntilMs: Long,
        internal val quietMs: Long
    )

    fun memoGate(graceMs: Long, quietMs: Long): MemoGate = lock.withLock {
        MemoGate(solvesFinished, nowMs() + graceMs, quietMs)
    }

    /** One turn for something a user waits for. [kind] names it in the `v8-queue` line. */
    fun <T> solve(kind: String, block: () -> T): T = announce { turn(kind, block) }

    /**
     * Keeps the warm-up steps back while [block] runs, including between its turns (a solve that
     * downloads the player between two of them).
     */
    fun <T> announce(block: () -> T): T {
        lock.withLock {
            solvesPending++
        }
        try {
            return block()
        } finally {
            lock.withLock {
                solvesPending--
                solvesFinished++
                lastSolveEndMs = nowMs()
                anySolveEnded = true
                changed.signalAll()
            }
        }
    }

    private fun <T> turn(kind: String, block: () -> T): T {
        val me = Thread.currentThread()
        var line: String? = null
        lock.lock()
        try {
            if (owner === me) {
                holds++
            } else {
                val startMs = nowMs()
                var behind: String? = null
                while (owner != null) {
                    if (behind == null) {
                        behind = ownerLabel
                    }
                    changed.awaitUninterruptibly()
                }
                take(me, kind)
                line = "v8-queue kind=$kind waitMs=${nowMs() - startMs} behind=${behind ?: NONE}"
            }
        } finally {
            lock.unlock()
        }
        try {
            line?.let(log)
            return block()
        } finally {
            release()
        }
    }

    /**
     * One warm-up step: [block] runs once nothing holds the runtime and no solve is pending, and,
     * with a [gate], once the gate lets it (see [MemoGate]). Throws InterruptedException when the
     * thread is interrupted while held back by the gate.
     */
    fun <T> warmup(step: String, gate: MemoGate?, block: () -> T): T {
        val me = Thread.currentThread()
        var line: String? = null
        lock.lock()
        try {
            if (owner === me) {
                holds++
            } else {
                val startMs = nowMs()
                val solvesBefore = solvesFinished
                warmupsWaiting++
                try {
                    while (true) {
                        if (owner == null && solvesPending == 0) {
                            val heldMs = gate?.let { heldBackMs(it) } ?: 0L
                            if (heldMs <= 0L) {
                                break
                            }
                            changed.await(heldMs, TimeUnit.MILLISECONDS)
                        } else {
                            changed.awaitUninterruptibly()
                        }
                    }
                } finally {
                    warmupsWaiting--
                }
                val yielded = solvesFinished - solvesBefore
                take(me, "warmup:$step")
                line = "v8-queue kind=warmup step=$step waitMs=${nowMs() - startMs} yielded=$yielded"
            }
        } finally {
            lock.unlock()
        }
        try {
            line?.let(log)
            return block()
        } finally {
            release()
        }
    }

    /** Caller holds [lock]. How much longer [gate] holds a memo step back; 0 or less: go. */
    private fun heldBackMs(gate: MemoGate): Long {
        val now = nowMs()
        val quietLeft = if (anySolveEnded) lastSolveEndMs + gate.quietMs - now else 0L
        val graceLeft = if (solvesFinished > gate.solvesAtStart) 0L else gate.graceUntilMs - now
        return maxOf(quietLeft, graceLeft)
    }

    /** Caller holds [lock]. */
    private fun take(me: Thread, label: String) {
        owner = me
        holds = 1
        ownerLabel = label
    }

    private fun release() {
        lock.withLock {
            holds--
            if (holds == 0) {
                owner = null
                ownerLabel = NONE
                changed.signalAll()
            }
        }
    }

    /** For the tests: solves announced and not finished. */
    internal fun pendingSolves(): Int = lock.withLock { solvesPending }

    /** For the tests: warm-up steps waiting for their turn. */
    internal fun waitingWarmups(): Int = lock.withLock { warmupsWaiting }

    companion object {
        const val NONE = "none"
        const val KIND_SOLVE = "solve"
        const val KIND_RELEASE = "release"
    }
}
