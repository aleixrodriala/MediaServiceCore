package com.liskovsoft.youtubeapi.app.nsigsolver.impl

import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8ScriptExecutionException
import com.liskovsoft.youtubeapi.app.nsigsolver.common.loadScript
import com.liskovsoft.youtubeapi.app.nsigsolver.common.withLock
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderError
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeRequest
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.JsRuntimeChalBaseJCP
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.MemoWarmup
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.Script
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptSource
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptType
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptVariant
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.SolverMemo
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.SolverOutput
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.V8Lane

internal object V8ChallengeProvider: JsRuntimeChalBaseJCP() {
    private val tag = V8ChallengeProvider::class.simpleName
    private val v8NpmLibFilename = listOf("${libPrefix}polyfill.js", "${libPrefix}meriyah-6.1.4.min.js", "${libPrefix}astring-1.9.0.min.js")
    private var v8Runtime: V8? = null
    /**
     * NEWTUBE(v8-priority): every use of the runtime takes a turn of this lane (it replaces a plain
     * monitor). Solves go first and wait only for the turn in flight; the warm-up takes the runtime
     * between them, one step at a time (see V8Lane, MemoWarmup).
     */
    private val lane = V8Lane(
        { android.os.SystemClock.elapsedRealtime() },
        { android.util.Log.d("NetPath", it) })
    private val memoWarmup = MemoWarmup(lane)
    // Counts the runtimes created and released, so a warm-up never re-creates one released after
    // it began (see MemoWarmup.run).
    @Volatile
    private var runtimeGeneration = 0L
    @Volatile
    private var runtimeReleases = 0L
    // newtube.memo.js is defined in the current runtime (the memo and the staged code need it).
    @Volatile
    private var shimReady = false
    /**
     * Mobile TTFF: keep the V8 runtime alive between opens instead of tearing it down after every
     * solve. Rebuilding costs a fresh V8 heap plus a re-eval of the solver lib (meriyah + astring +
     * polyfill) on the critical path of EVERY video open, and it also throws away V8's JIT state
     * for the solver functions. Off by default so TV boxes keep their historical low-water memory
     * behaviour; the mobile flavor opts in and releases it on memory pressure.
     */
    @Volatile
    private var keepAlive = false
    /** NEWTUBE(v8-memo): the player whose solvers the runtime keeps, and the guard's verdicts. */
    private val memo = SolverMemo()
    private val memoShim: String? by lazy {
        try {
            loadScript(SolverMemo.SHIM_FILE)
        } catch (e: Exception) {
            android.util.Log.w("NetPath", "v8-memo off reason=shim-" + e.javaClass.simpleName)
            null
        }
    }

    override fun iterScriptSources(): Sequence<Pair<ScriptSource, (ScriptType) -> Script?>> = sequence {
        for ((source, func) in super.iterScriptSources()) {
            if (source == ScriptSource.WEB || source == ScriptSource.BUILTIN)
                yield(Pair(ScriptSource.BUILTIN, ::v8NpmSource))
            yield(Pair(source, func))
        }
    }

    private fun v8NpmSource(scriptType: ScriptType): Script? {
        if (scriptType != ScriptType.LIB)
            return null
        // V8-specific lib scripts that uses Deno NPM imports
        val code = loadScript(v8NpmLibFilename, "Failed to read v8 challenge solver lib script")
        return Script(scriptType, ScriptVariant.V8_NPM, ScriptSource.BUILTIN, scriptVersion, code)
    }

    /** Today's path, when the memo is off or the runtime is not kept (see [solvePlayer]). */
    override fun runJsRuntime(stdin: String, playerUrl: String): String =
        lane.solve(V8Lane.KIND_SOLVE) {
            if (memo.onFullEvaluation(playerUrl)) {
                dropMemoSlot() // another player's solvers: no longer trusted past this evaluation
            }
            runTimed(stdin, memo.fullPathLabel(playerUrl, keepAlive), false, null)
        }

    /**
     * NEWTUBE(v8-memo, v8-priority): one player's answer, in one turn of the lane: the kept solvers
     * when the guard passed them, else today's path, run on the player's code staged in the runtime
     * (staged by the warm-up, or by this solve for the next ones). It never waits for the memo's
     * check: only for the lane's turn in flight, and never behind a warm-up step that has not
     * started. With the memo off, or a runtime that is not kept, today's path exactly as before.
     */
    override fun solvePlayer(playerUrl: String, requests: List<JsChallengeRequest>): SolverOutput {
        if (!memo.enabled || !keepAlive) {
            return solveFull(playerUrl, requests)
        }
        // Announced for both turns: the warm-up does not slip in while the player is downloaded.
        val output = lane.announce {
            var player: String? = null
            var answer: SolverOutput? = null
            for (attempt in 0..1) {
                answer = lane.solve(V8Lane.KIND_SOLVE) { solveInLane(playerUrl, requests, player) }
                if (answer != null) {
                    break
                }
                // Neither staged nor cached (a new player): read it outside the lane, as today's
                // path does (a download, or the extractor's copy of the body it just read).
                player = getPlayer(playerUrl)
            }
            answer ?: throw JsChallengeProviderError("No player to solve with: $playerUrl")
        }
        storePreprocessed(playerUrl, output) // a new player's ~3.7 MB write, outside the lane
        return output
    }

    /** Holding the lane. Null: nothing staged or cached to solve with, and no [player] given. */
    private fun solveInLane(playerUrl: String, requests: List<JsChallengeRequest>, player: String?): SolverOutput? {
        val memoAnswer = answerFromMemo(playerUrl, requests)
        if (memoAnswer != null && SolverMemo.isClean(memoAnswer, requests)) {
            return memoAnswer
        }
        val source = fullSource(playerUrl, player) ?: return null
        // A kept solver's answer with an error or a missing value is never used: today's path
        // answers, and the memo stays on only if both said the same thing.
        return SolverMemo.answer(memoAnswer, requests,
            { runFull(playerUrl, requests, source) },
            { memoOutput, fullOutput ->
                if (!SolverMemo.sameAnswer(memoOutput, fullOutput)) {
                    turnMemoOff(playerUrl, "mismatch-live")
                }
            })
    }

    /** What today's path runs on. */
    private sealed class FullSource {
        /** The runtime keeps the player's preprocessed code: only the challenges are sent. */
        object Staged : FullSource()
        /** The cache's preprocessed code: sent once, and staged. */
        class Cached(val code: String) : FullSource()
        /** The player as served: jsc() preprocesses it, and its output is staged. */
        class Raw(val player: String) : FullSource()
    }

    /** Holding the lane. Null: nothing to solve with until [player] is read. */
    private fun fullSource(playerUrl: String, player: String?): FullSource? {
        if (shimReady && memo.isStaged(playerUrl)) {
            return FullSource.Staged
        }
        cachedPlayer(playerUrl)?.let { return FullSource.Cached(it) }
        return player?.let { FullSource.Raw(it) }
    }

    /** Holding the lane: today's path (jsc) for one player. */
    private fun runFull(playerUrl: String, requests: List<JsChallengeRequest>, source: FullSource): SolverOutput {
        if (memo.onFullEvaluation(playerUrl)) {
            dropMemoSlot() // another player's solvers: no longer trusted past this evaluation
        }
        val init = timedInit()
        val stage = shimReady
        val stdin = when (source) {
            FullSource.Staged -> SolverMemo.fullStdin(playerUrl, requests)
            is FullSource.Cached -> if (stage) SolverMemo.stageAndFullStdin(playerUrl, source.code, requests)
                else constructStdin(source.code, true, requests)
            is FullSource.Raw -> if (stage) SolverMemo.fullPlayerStdin(playerUrl, source.player, requests)
                else constructStdin(source.player, false, requests)
        }
        // A miss here means the whole player JS is re-preprocessed (parsed by meriyah, printed
        // by astring) inside V8 before a single challenge is solved -- by far the most expensive
        // thing this path can do, and invisible in the transform total without this line.
        android.util.Log.d("NetPath", "v8-player cached=" + (if (source is FullSource.Raw) "n" else "y")
                + " challenges=" + requests.sumOf { it.input.challenges.size }
                + " staged=" + stagedLabel(source === FullSource.Staged))
        val output = try {
            parseSolverOutput(runTimed(stdin, memo.fullPathLabel(playerUrl, keepAlive),
                source === FullSource.Staged, init))
        } catch (e: Exception) {
            // The staged copy may be what failed: the next solve stages the player again.
            memo.onStageLost()
            throw e
        }
        if (stage && v8Runtime != null) {
            when (source) {
                FullSource.Staged -> {}
                is FullSource.Cached -> memo.onStaged(playerUrl, SolverMemo.fingerprint(source.code))
                is FullSource.Raw -> output.preprocessed_player?.let { memo.onStaged(playerUrl, SolverMemo.fingerprint(it)) }
            }
        }
        return output
    }

    private class Init(val reused: Boolean, val ms: Long)

    /** Caller holds the lane: the runtime exists afterwards. */
    private fun timedInit(): Init {
        val startMs = android.os.SystemClock.elapsedRealtime()
        val reused = v8Runtime != null
        initRuntime()
        return Init(reused, android.os.SystemClock.elapsedRealtime() - startMs)
    }

    /**
     * Caller holds the lane. [memoLabel]: hit, miss or off, for the benchmark parser. [init]: the
     * runtime set up by the caller already, else it is set up and timed here.
     */
    private fun runTimed(stdin: String, memoLabel: String, staged: Boolean, init: Init?): String {
        val setup = init ?: timedInit()
        val solveStartMs = android.os.SystemClock.elapsedRealtime()

        val result = runV8(stdin)

        val doneMs = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("NetPath", "v8-run reused=" + (if (setup.reused) "y" else "n")
                + " initMs=" + setup.ms
                + " solveMs=" + (doneMs - solveStartMs)
                + " stdinKb=" + (stdin.length / 1024)
                + " memo=" + memoLabel
                + " staged=" + stagedLabel(staged))

        shutdownIfNeeded()

        return result
    }

    private fun stagedLabel(staged: Boolean): String = if (staged) "y" else "n"

    /**
     * Holding the lane. NEWTUBE(v8-memo): answers from the solvers the runtime keeps for
     * [playerUrl] once the guard has passed them ([warmup]). A hit sends the challenges only: no
     * cache read, no JSON of the player, no re-evaluation (the 206-240 ms of `player-sig` on the
     * Pixel 9). A checked player that the runtime no longer holds (released on memory trim, or
     * another player evaluated since) is evaluated once more at today's cost and kept. Null: today's
     * path answers (a player not checked yet among them: this solve does not wait for its check).
     */
    private fun answerFromMemo(playerUrl: String, requests: List<JsChallengeRequest>): SolverOutput? {
        val route = memo.route(playerUrl, keepAlive)
        if (route != SolverMemo.Route.HIT && route != SolverMemo.Route.LOAD) {
            return null
        }
        try {
            var staged = false
            val stdin = if (route == SolverMemo.Route.HIT) {
                SolverMemo.solveStdin(playerUrl, requests)
            } else if (shimReady && memo.stagedFingerprint(playerUrl)?.let { memo.matchesCheck(playerUrl, it) } == true) {
                // The checked code is still staged: evaluate it again without sending it.
                staged = true
                memo.onLoadStarted(playerUrl)
                SolverMemo.loadAndSolveStagedStdin(playerUrl, requests)
            } else {
                val code = cachedPlayer(playerUrl) ?: return null
                if (!memo.matchesCheck(playerUrl, SolverMemo.fingerprint(code))) {
                    turnMemoOff(playerUrl, "code-changed")
                    return null
                }
                memo.onLoadStarted(playerUrl)
                SolverMemo.loadAndSolveStdin(playerUrl, code, requests)
            }
            val label = if (route == SolverMemo.Route.HIT) SolverMemo.LABEL_HIT else SolverMemo.LABEL_MISS
            android.util.Log.d("NetPath", "v8-player cached=y"
                    + " challenges=" + requests.sumOf { it.input.challenges.size }
                    + " memo=" + label)
            val output = SolverMemo.parseOutput(runTimed(stdin, label, staged, null))
            if (output == null) {
                turnMemoOff(playerUrl, "parse")
                return null
            }
            // runTimed disposes the runtime when keep-alive was switched off meanwhile: then
            // nothing is held, and disposeRuntime has already cleared the slot.
            if (route == SolverMemo.Route.LOAD && v8Runtime != null) {
                memo.onLoaded(playerUrl)
            }
            return output
        } catch (e: Exception) {
            memoFailed(playerUrl, e)
            return null
        }
    }

    /**
     * Warms the runtime up for [playerUrl] on the calling thread, one lane turn per step, each
     * taken only when no solve is pending (see MemoWarmup): the runtime, then the player's code
     * staged in it, then NEWTUBE(v8-memo) the guard, so that later solves are hits. The guard
     * evaluates the staged code into the memo and keeps the kept solvers' answers on [checkRequests]
     * (fixed challenges), then today's full path (jsc) answers them, then the kept solvers again;
     * all three must agree, else the memo stays off for this player (`v8-memo off reason=`) and
     * every solve takes today's path. Once per player per process. The memo steps wait for the
     * first solve after the warm-up began (or MemoWarmup.GRACE_MS), so a cold start's first solve
     * never waits for them. Runtime errors in the first step propagate, as before.
     */
    fun warmup(playerUrl: String, checkRequests: List<JsChallengeRequest>) {
        if (!memoWarmup.run(playerUrl, WarmupSteps(checkRequests))) {
            // Solves kept undoing its steps (another player staged in between each time): this
            // player stays on today's path until the next warm-up.
            android.util.Log.d("NetPath", "v8-memo skip reason=steps")
        }
    }

    private class WarmupSteps(private val checkRequests: List<JsChallengeRequest>) : MemoWarmup.Steps {
        // For the v8-memo line: the stage's cache read, and the check's two evaluations.
        var stageLoadMs = 0L
        var checkMs = 0L

        override fun releases(): Long = runtimeReleases

        override fun init(): Long {
            initRuntime()
            return runtimeGeneration
        }

        override fun next(playerUrl: String): MemoWarmup.Step? = nextWarmupStep(playerUrl)

        override fun run(playerUrl: String, step: MemoWarmup.Step, generation: Long): Boolean {
            if (v8Runtime == null || generation != runtimeGeneration) {
                // Released since (memory trim): the warm-up does not bring it back.
                android.util.Log.d("NetPath", "v8-memo skip reason=released")
                return false
            }
            if (nextWarmupStep(playerUrl) != step) {
                return true // a solve moved things on while this step waited: plan again
            }
            return when (step) {
                MemoWarmup.Step.STAGE -> warmStage(playerUrl, this)
                MemoWarmup.Step.CHECK_LOAD -> warmCheckLoad(playerUrl, checkRequests, this)
                MemoWarmup.Step.CHECK -> warmCheck(playerUrl, checkRequests, this)
                MemoWarmup.Step.LOAD -> warmLoad(playerUrl)
            }
        }
    }

    /** What the warm-up has left to do for [playerUrl]; nothing without newtube.memo.js in the runtime. */
    private fun nextWarmupStep(playerUrl: String): MemoWarmup.Step? =
        if (shimReady) memo.warmupStep(playerUrl, keepAlive) else null

    /** Holding the lane: the cache's preprocessed player, staged. False: none cached (the first solve reads it). */
    private fun warmStage(playerUrl: String, steps: WarmupSteps): Boolean {
        val startMs = android.os.SystemClock.elapsedRealtime()
        val code = cachedPlayer(playerUrl)
        if (code == null) {
            // The first solve for this player evaluates and caches it; the next process checks it.
            android.util.Log.d("NetPath", "v8-memo skip reason=no-cached-player")
            return false
        }
        val fingerprint = SolverMemo.fingerprint(code)
        steps.stageLoadMs = android.os.SystemClock.elapsedRealtime() - startMs
        try {
            runV8(SolverMemo.stageStdin(playerUrl, code))
        } catch (e: Exception) {
            memo.onStageLost()
            android.util.Log.w("NetPath", "v8-stage failed reason=" + reasonOf(e))
            return false
        }
        memo.onStaged(playerUrl, fingerprint)
        android.util.Log.d("NetPath", "v8-stage by=warmup loadMs=" + steps.stageLoadMs
                + " ms=" + (android.os.SystemClock.elapsedRealtime() - startMs))
        return true
    }

    /** Holding the lane: the guard's first half. Any failure turns the memo off for this player. */
    private fun warmCheckLoad(playerUrl: String, checkRequests: List<JsChallengeRequest>, steps: WarmupSteps): Boolean {
        val fingerprint = memo.stagedFingerprint(playerUrl) ?: return true
        val startMs = android.os.SystemClock.elapsedRealtime()
        try {
            memo.onLoadStarted(playerUrl)
            runV8(SolverMemo.checkLoadStdin(playerUrl, checkRequests))
            memo.onCheckLoaded(playerUrl, fingerprint)
        } catch (e: Exception) {
            memoFailed(playerUrl, e)
            return false
        }
        steps.checkMs = android.os.SystemClock.elapsedRealtime() - startMs
        return true
    }

    /**
     * Holding the lane: the guard's second half and its verdict. Only this passes a player: until
     * then it answers nothing (fail closed), and any failure turns the memo off for it.
     */
    private fun warmCheck(playerUrl: String, checkRequests: List<JsChallengeRequest>, steps: WarmupSteps): Boolean {
        val fingerprint = memo.stagedFingerprint(playerUrl) ?: return true
        val startMs = android.os.SystemClock.elapsedRealtime()
        try {
            val reason = SolverMemo.checkVerdict(
                runV8(SolverMemo.checkFinishStdin(playerUrl, checkRequests)), checkRequests)
            if (reason != null) {
                turnMemoOff(playerUrl, reason)
                return false
            }
        } catch (e: Exception) {
            memoFailed(playerUrl, e)
            return false
        }
        memo.onVerified(playerUrl, fingerprint)
        steps.checkMs += android.os.SystemClock.elapsedRealtime() - startMs
        android.util.Log.d("NetPath", "v8-memo on check=pass loadMs=" + steps.stageLoadMs
                + " ms=" + steps.checkMs)
        return true
    }

    /**
     * Holding the lane: a player checked earlier in this process, evaluated into the memo again
     * (the runtime was released, or another player was evaluated since), if it is still the code
     * that was checked.
     */
    private fun warmLoad(playerUrl: String): Boolean {
        val startMs = android.os.SystemClock.elapsedRealtime()
        try {
            val stagedFingerprint = memo.stagedFingerprint(playerUrl)
            if (stagedFingerprint != null && memo.matchesCheck(playerUrl, stagedFingerprint)) {
                memo.onLoadStarted(playerUrl)
                runV8(SolverMemo.loadAndSolveStagedStdin(playerUrl, emptyList()))
            } else {
                val code = cachedPlayer(playerUrl)
                if (code == null) {
                    android.util.Log.d("NetPath", "v8-memo skip reason=no-cached-player")
                    return false
                }
                if (!memo.matchesCheck(playerUrl, SolverMemo.fingerprint(code))) {
                    turnMemoOff(playerUrl, "code-changed")
                    return false
                }
                memo.onLoadStarted(playerUrl)
                runV8(SolverMemo.loadAndSolveStdin(playerUrl, code, emptyList()))
            }
            memo.onLoaded(playerUrl)
        } catch (e: Exception) {
            memoFailed(playerUrl, e)
            return false
        }
        android.util.Log.d("NetPath", "v8-memo on check=earlier ms="
                + (android.os.SystemClock.elapsedRealtime() - startMs))
        return true
    }

    /**
     * Caller holds the lane: a memo script threw. The player takes today's path for the rest of the
     * process, and not on the staged code, which may be what failed (a player that does not
     * evaluate): the next solve reads it again, from the cache or, once cleared, the network.
     */
    private fun memoFailed(playerUrl: String, e: Exception) {
        memo.onStageLost()
        turnMemoOff(playerUrl, reasonOf(e))
    }

    /** Caller holds the lane. Fails closed: this player takes today's path for the rest of the process. */
    private fun turnMemoOff(playerUrl: String, reason: String) {
        if (memo.reject(playerUrl)) {
            android.util.Log.w("NetPath", "v8-memo off reason=$reason")
        }
        // Free its evaluated player, only if the slot is still this player's (it may hold another
        // player that is fine, loaded by another thread since).
        if (memo.holdsSlot(playerUrl)) {
            dropMemoSlot()
        }
    }

    /** Caller holds the lane. Empties newtube.memo.js's slot; the runtime and its staged code stay. */
    private fun dropMemoSlot() {
        memo.onSlotDropped()
        val runtime = v8Runtime ?: return
        try {
            runtime.withLock { it.executeStringScript(SolverMemo.DROP_STDIN) }
        } catch (e: Exception) {
            // The helpers may be missing (shim not installed); nothing is held then.
        }
    }

    /** For a log line: the exception's class only. Messages can quote player code or challenges. */
    private fun reasonOf(e: Exception): String = "error-" + (e.cause ?: e).javaClass.simpleName

    /** Rollback switch for debug and benchmark builds; on by default. */
    @JvmStatic
    fun setPlayerMemoEnabled(enabled: Boolean) {
        memo.enabled = enabled
    }

    private fun runV8(stdin: String): String {
        val runtime = v8Runtime ?: throw JsChallengeProviderError("V8 runtime not initialized yet")
        try {
            return runtime.withLock {
                it.executeStringScript(stdin) ?: throw JsChallengeProviderError("V8 runtime error: empty response")
            }
        } catch (e: V8ScriptExecutionException) {
            if (e.message?.contains("Invalid or unexpected token") ?: false) {
                ie.cache.clear(cacheSection) // cached data broken?
                memo.onStageLost() // NEWTUBE(v8-priority): and so is what was staged from it
            }
            throw JsChallengeProviderError("V8 runtime error: ${e.message}", e)
        }
    }

    /** Caller holds the lane. */
    private fun initRuntime() {
        if (v8Runtime != null)
            return
        v8Runtime = V8.createV8Runtime()
        runtimeGeneration++
        shimReady = false
        runV8(constructCommonStdin()) // ignore the result, just warm up
        installMemoShim()
    }

    /**
     * NEWTUBE(v8-memo): define the newtube.memo.js helpers (no player yet). Only where the memo can
     * be used. A failure leaves them undefined: the memo and the staged code stay unused in this
     * runtime, and every solve takes today's path.
     */
    private fun installMemoShim() {
        if (!memo.enabled || !keepAlive) {
            return
        }
        val shim = memoShim ?: return
        try {
            runV8("$shim\n\"\";") // executeStringScript needs a string completion value
            shimReady = true
        } catch (e: Exception) {
            android.util.Log.w("NetPath", "v8-memo shim failed reason=" + reasonOf(e))
        }
    }

    /** Caller holds the lane. */
    private fun disposeRuntime() {
        val runtime = v8Runtime ?: return
        memo.onRuntimeDisposed() // first: a failed release must not leave a player marked as held
        shimReady = false

        // NOTE: getting lock fixes "Invalid V8 thread access: the locker has been released!"
        runtime.withLock {
            it.release(false)
        }
        v8Runtime = null
        runtimeReleases++
    }

    fun warmup() {
        lane.warmup("init", null) {
            initRuntime()
        }
    }

    fun shutdown() {
        lane.solve(V8Lane.KIND_RELEASE) {
            disposeRuntime()
        }
    }

    @JvmStatic
    fun setKeepRuntimeAlive(enabled: Boolean) {
        keepAlive = enabled
        if (!enabled) {
            shutdown()
        }
    }

    /** Java-callable alias for [shutdown]: an object's own members are not @JvmStatic. */
    @JvmStatic
    fun releaseRuntime() {
        shutdown()
    }

    fun forceRecreate() {
        lane.solve(V8Lane.KIND_RELEASE) {
            disposeRuntime()

            initRuntime()
        }
    }

    private fun shutdownIfNeeded() {
        // NOTE: Possible Invalid thread access if using RxHelper runAsync
        // NOTE: Shutdown should run on the same thread that created V8 engine.
        if (keepAlive) {
            return
        }
        disposeRuntime()
    }
}
