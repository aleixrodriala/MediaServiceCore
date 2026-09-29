package com.liskovsoft.youtubeapi.app.nsigsolver.runtime

/**
 * NEWTUBE(v8-priority): the warm-up of the kept V8 runtime, as steps that a real solve can go
 * between (see [V8Lane]).
 *
 * The steps, each one turn of the lane:
 * - `init`: the runtime and the solver lib (~233 ms on a Mi 8). Any solve needs it.
 * - `stage`: the player's preprocessed code, read from the cache once and kept in the runtime
 *   (read ~103 ms + transfer on a Mi 8). Any full-path solve of that player then runs on it
 *   instead of reading, JSON-encoding and sending ~3.7 MB again.
 * - memo steps, behind the [V8Lane.MemoGate]: `check-load` evaluates the player into the memo
 *   and keeps its first answers, `check` runs today's full path next to the kept solvers again and
 *   gives the verdict (one player evaluation each); `load` evaluates a player checked earlier in
 *   the process again (after a release). Until the verdict passes, the memo answers nothing.
 *
 * `init` and `stage` are work a solve would do itself, so a solve that waits for one in flight
 * loses nothing. A memo step is not: it is held back until the first solve after the warm-up
 * began has finished (or the grace passed), so on a cold start the first solve does not wait for
 * the memo at all, and a later one waits at most for the one evaluation in flight.
 */
internal class MemoWarmup(
    private val lane: V8Lane,
    private val graceMs: Long = GRACE_MS,
    private val quietMs: Long = QUIET_MS
) {
    enum class Step(val label: String, val memo: Boolean) {
        STAGE("stage", false),
        CHECK_LOAD("check-load", true),
        CHECK("check", true),
        LOAD("load", true)
    }

    interface Steps {
        /** How many runtimes were released so far (memory trim, keep-alive off). */
        fun releases(): Long

        /** Holding the lane: the runtime exists afterwards; returns its generation. */
        fun init(): Long

        /** Not holding the lane: what is left to do for [playerUrl], or null when nothing is. */
        fun next(playerUrl: String): Step?

        /**
         * Holding the lane: runs [step] if it is still the one due and the runtime is still
         * [generation]. False stops the warm-up (released runtime, nothing to stage from, an
         * error); true plans the next step.
         */
        fun run(playerUrl: String, step: Step, generation: Long): Boolean
    }

    /** False when it gave up with steps still due (see [MAX_STEPS]). */
    fun run(playerUrl: String, steps: Steps): Boolean {
        // Made first: a solve that finishes while the runtime is being set up already counts.
        val gate = lane.memoGate(graceMs, quietMs)
        // A release that takes the lane while this waits for its first turn wins: the warm-up
        // does not bring back a runtime freed on memory pressure. Decided holding the lane.
        val releases = steps.releases()
        val generation = lane.warmup("init", null) {
            if (steps.releases() == releases) steps.init() else null
        } ?: return true
        for (i in 0 until MAX_STEPS) {
            val step = steps.next(playerUrl) ?: return true
            val more = lane.warmup(step.label, if (step.memo) gate else null) {
                steps.run(playerUrl, step, generation)
            }
            if (!more) {
                return true
            }
        }
        return steps.next(playerUrl) == null
    }

    companion object {
        /**
         * How long the memo steps wait for the first solve after the warm-up began. A cold start's
         * first solve came 345 ms after the warm-up on the Mi 8 (TV_TIZEN), ~130 ms on the Pixel 9.
         * With no solve at all (an answer with nothing to decipher) the memo is checked after this.
         */
        const val GRACE_MS = 2_000L

        /** After a solve, the memo steps wait this long for the next one (the HLS n, a second answer). */
        const val QUIET_MS = 150L

        /** Plans at most this many steps (a stage lost to another player's solve is planned again). */
        const val MAX_STEPS = 8
    }
}
