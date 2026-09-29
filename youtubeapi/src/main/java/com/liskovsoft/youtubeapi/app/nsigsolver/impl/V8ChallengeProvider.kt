package com.liskovsoft.youtubeapi.app.nsigsolver.impl

import com.eclipsesource.v8.V8
import com.eclipsesource.v8.V8ScriptExecutionException
import com.liskovsoft.youtubeapi.app.nsigsolver.common.loadScript
import com.liskovsoft.youtubeapi.app.nsigsolver.common.withLock
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderError
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeRequest
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.JsRuntimeChalBaseJCP
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.Script
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptSource
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptType
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.ScriptVariant
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.SolverMemo
import com.liskovsoft.youtubeapi.app.nsigsolver.runtime.SolverOutput

internal object V8ChallengeProvider: JsRuntimeChalBaseJCP() {
    private val tag = V8ChallengeProvider::class.simpleName
    private val v8NpmLibFilename = listOf("${libPrefix}polyfill.js", "${libPrefix}meriyah-6.1.4.min.js", "${libPrefix}astring-1.9.0.min.js")
    private var v8Runtime: V8? = null
    private val v8Lock = Any()
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

    override fun runJsRuntime(stdin: String, playerUrl: String): String {
        synchronized(v8Lock) {
            if (memo.onFullEvaluation(playerUrl)) {
                dropMemoSlot() // another player's solvers: no longer trusted past this evaluation
            }
            return runTimed(stdin, memo.fullPathLabel(playerUrl, keepAlive))
        }
    }

    /** Caller holds [v8Lock]. [memoLabel]: hit, miss or off, for the benchmark parser. */
    private fun runTimed(stdin: String, memoLabel: String): String {
        val initStartMs = android.os.SystemClock.elapsedRealtime()
        val reusedRuntime = v8Runtime != null
        initRuntime()
        val solveStartMs = android.os.SystemClock.elapsedRealtime()

        val result = runV8(stdin)

        val doneMs = android.os.SystemClock.elapsedRealtime()
        android.util.Log.d("NetPath", "v8-run reused=" + (if (reusedRuntime) "y" else "n")
                + " initMs=" + (solveStartMs - initStartMs)
                + " solveMs=" + (doneMs - solveStartMs)
                + " stdinKb=" + (stdin.length / 1024)
                + " memo=" + memoLabel)

        shutdownIfNeeded()

        return result
    }

    /**
     * NEWTUBE(v8-memo): answers from the solvers the runtime keeps for [playerUrl] once the guard
     * has passed them ([warmup]). A hit sends the challenges only: no cache read, no JSON of the
     * player, no re-evaluation (the 206-240 ms of `player-sig` on the Pixel 9). A checked player
     * that the runtime no longer holds (released on memory trim, or another player evaluated since)
     * is evaluated once more at today's cost and kept. Null: today's path answers.
     *
     * Deciding under [v8Lock] makes a solve that arrives while the warm-up still checks this player
     * wait for the verdict and hit, instead of paying today's path next to it. With the memo off,
     * or no check under way, it does not wait at all: today's path, timed as before.
     */
    override fun solveFromMemo(playerUrl: String, requests: List<JsChallengeRequest>): SolverOutput? {
        if (!memo.worthWaiting(playerUrl, keepAlive)) {
            return null
        }
        synchronized(v8Lock) {
            val route = memo.route(playerUrl, keepAlive)
            if (route != SolverMemo.Route.HIT && route != SolverMemo.Route.LOAD) {
                return null
            }
            try {
                val stdin = if (route == SolverMemo.Route.HIT) {
                    SolverMemo.solveStdin(playerUrl, requests)
                } else {
                    val code = ie.cache.load(cacheSection, "player:$playerUrl")?.code ?: return null
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
                val output = SolverMemo.parseOutput(runTimed(stdin, label))
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
                turnMemoOff(playerUrl, reasonOf(e))
                return null
            }
        }
    }

    /**
     * The memo's answer had an error or a missing value, and today's path answered [fullAnswer] for
     * the same request. The memo stays on only if both said the same thing.
     */
    override fun onMemoUnclean(playerUrl: String, memoAnswer: SolverOutput, fullAnswer: SolverOutput) {
        if (!SolverMemo.sameAnswer(memoAnswer, fullAnswer)) {
            synchronized(v8Lock) {
                turnMemoOff(playerUrl, "mismatch-live")
            }
        }
    }

    /**
     * [warmup], then NEWTUBE(v8-memo): evaluate [playerUrl] into the runtime and run the guard, on
     * the warm-up thread, so the first solve of a process is usually a hit. The kept solvers answer
     * [checkRequests] (fixed challenges), then today's full path (jsc) answers them, then the kept
     * solvers again; all three must agree, else the memo stays off for this player
     * (`v8-memo off reason=`) and every solve takes today's path. Once per player per process.
     *
     * One [v8Lock] hold for both halves (the runtime, then ~2 player evaluations): a solve for this
     * player that arrives meanwhile waits for the verdict and then hits, where today it would have
     * evaluated the player itself. Released between the halves, a solve could slip in, take today's
     * path, and then wait behind the check as well. Runtime errors propagate as from [warmup].
     */
    fun warmup(playerUrl: String, checkRequests: List<JsChallengeRequest>) {
        val route = memo.route(playerUrl, keepAlive)
        val check = route == SolverMemo.Route.UNCHECKED || route == SolverMemo.Route.LOAD
        if (check) {
            memo.onCheckStarted(playerUrl)
        }
        try {
            synchronized(v8Lock) {
                initRuntime()
                if (check) {
                    loadAndCheck(playerUrl, checkRequests)
                }
            }
        } finally {
            if (check) {
                memo.onCheckFinished(playerUrl)
            }
        }
    }

    /** Caller holds [v8Lock]; the runtime exists. Never throws: any failure turns the memo off. */
    private fun loadAndCheck(playerUrl: String, checkRequests: List<JsChallengeRequest>) {
        val route = memo.route(playerUrl, keepAlive)
        if (route != SolverMemo.Route.UNCHECKED && route != SolverMemo.Route.LOAD) {
            return
        }
        val startMs = android.os.SystemClock.elapsedRealtime()
        try {
            val code = ie.cache.load(cacheSection, "player:$playerUrl")?.code
            if (code == null) {
                // The first solve for this player evaluates and caches it; the next process checks it.
                android.util.Log.d("NetPath", "v8-memo skip reason=no-cached-player")
                return
            }
            val fingerprint = SolverMemo.fingerprint(code)
            val loadMs = android.os.SystemClock.elapsedRealtime() - startMs
            if (route == SolverMemo.Route.LOAD) {
                // Checked earlier in this process: evaluate it again, no second check, if it is
                // still the code that was checked.
                if (!memo.matchesCheck(playerUrl, fingerprint)) {
                    turnMemoOff(playerUrl, "code-changed")
                    return
                }
                memo.onLoadStarted(playerUrl)
                runV8(SolverMemo.loadAndSolveStdin(playerUrl, code, emptyList()))
                memo.onLoaded(playerUrl)
                android.util.Log.d("NetPath", "v8-memo on check=earlier loadMs=" + loadMs
                        + " ms=" + (android.os.SystemClock.elapsedRealtime() - startMs))
                return
            }
            memo.onLoadStarted(playerUrl)
            val reason = SolverMemo.checkVerdict(
                runV8(SolverMemo.checkStdin(playerUrl, code, checkRequests)), checkRequests)
            if (reason != null) {
                turnMemoOff(playerUrl, reason)
                return
            }
            memo.onVerified(playerUrl, fingerprint)
            memo.onLoaded(playerUrl)
            android.util.Log.d("NetPath", "v8-memo on check=pass loadMs=" + loadMs
                    + " ms=" + (android.os.SystemClock.elapsedRealtime() - startMs))
        } catch (e: Exception) {
            turnMemoOff(playerUrl, reasonOf(e))
        }
    }

    /** Caller holds [v8Lock]. Fails closed: this player takes today's path for the rest of the process. */
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

    /** Caller holds [v8Lock]. Empties newtube.memo.js's slot; the runtime itself stays. */
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
            if (e.message?.contains("Invalid or unexpected token") ?: false)
                ie.cache.clear(cacheSection) // cached data broken?
            throw JsChallengeProviderError("V8 runtime error: ${e.message}", e)
        }
    }

    private fun initRuntime() {
        if (v8Runtime != null)
            return
        v8Runtime = V8.createV8Runtime()
        runV8(constructCommonStdin()) // ignore the result, just warm up
        installMemoShim()
    }

    /**
     * NEWTUBE(v8-memo): define the newtube.memo.js helpers (no player yet). Only where the memo can
     * be used. A failure leaves them undefined, which the memo paths turn into `v8-memo off`;
     * today's path never calls them.
     */
    private fun installMemoShim() {
        if (!memo.enabled || !keepAlive) {
            return
        }
        val shim = memoShim ?: return
        try {
            runV8("$shim\n\"\";") // executeStringScript needs a string completion value
        } catch (e: Exception) {
            android.util.Log.w("NetPath", "v8-memo shim failed reason=" + reasonOf(e))
        }
    }

    private fun disposeRuntime() {
        val runtime = v8Runtime ?: return
        memo.onRuntimeDisposed() // first: a failed release must not leave a player marked as held

        // NOTE: getting lock fixes "Invalid V8 thread access: the locker has been released!"
        runtime.withLock {
            it.release(false)
        }
        v8Runtime = null
    }
    
    fun warmup() {
        synchronized(v8Lock) {
            initRuntime()
        }
    }

    fun shutdown() {
        synchronized(v8Lock) {
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
        synchronized(v8Lock) {
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