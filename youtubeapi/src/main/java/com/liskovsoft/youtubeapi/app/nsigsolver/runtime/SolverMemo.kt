package com.liskovsoft.youtubeapi.app.nsigsolver.runtime

import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeRequest

/**
 * NEWTUBE(v8-memo): which player's n/sig solvers the retained V8 runtime holds, and whether they may
 * answer a request.
 *
 * Today's path reads the ~3.7 MB preprocessed player from the cache, JSON-encodes it into the
 * script and V8 evaluates all of it again before the 2-3 challenges of an answer are solved: 206-240
 * ms on every TV_TIZEN / WEB_EMBED / MWEB answer on the Pixel 9, on the critical path, whatever the
 * number of challenges (netbench ttff-analysis.md 3.1: `stdinKb` 3783-3790 and `solveMs` 105-249 on
 * all 130 `v8-run` lines). The memo evaluates a player once per runtime (newtube.memo.js) and later
 * solves send only their challenges.
 *
 * It fails closed: a wrong n throttles or 403s the media. A player's kept solvers answer nothing
 * until the warm-up has cross-checked them against today's full path ([checkVerdict]); until then,
 * and for good after a mismatch or an exception, that player takes today's path. The check is a
 * sample (one fixed n and one fixed sig, the kept solvers asked twice around a fresh evaluation), not
 * a proof for every challenge. What makes reuse safe is that ejs itself already calls one evaluated
 * solver for every challenge of a request (main(): `input.challenges.map(solver)`, 2-3 distinct n per
 * answer, up to 30); the memo extends that across requests. Offline, three real players answered 120
 * random challenge sets the same from kept solvers as from fresh evaluations. A kept solver's answer
 * with an error or a missing value is never used: today's path answers it, and a disagreement turns
 * the memo off.
 *
 * Its own monitor guards the state, so a solve can ask [worthWaiting] before it takes the provider's
 * V8 lock; the provider changes it only while holding that lock.
 */
internal class SolverMemo {
    enum class Route {
        /** The memo is off: the switch, no retained runtime, or the guard turned it off for this player. */
        OFF,
        /** Not cross-checked yet in this process: today's path. */
        UNCHECKED,
        /** Checked, but not evaluated in the current runtime (released on trim, or another player since). */
        LOAD,
        /** Evaluated in the current runtime: the kept solvers answer. */
        HIT
    }

    /** Rollback switch (debug.arc.v8_memo=0 in debug and benchmark builds). */
    @Volatile
    var enabled = true

    // Per process. A verdict is about the player's code: it records a fingerprint of the code that
    // was checked, and a later load of different code under the same URL is refused ([matchesCheck]).
    private val verified = HashMap<String, Long>()
    private val rejected = HashSet<String>()
    // The warm-ups under way per player (they hold the V8 lock for most of it). A count: two
    // extractors for one player can overlap, and the first to finish must not end the wait.
    private val checking = HashMap<String, Int>()
    // Per runtime. slotUrl: the player newtube.memo.js holds (set when a load starts, so a failed load
    // is still known to occupy it); loadedUrl: that player, once its load finished and may answer.
    private var slotUrl: String? = null
    private var loadedUrl: String? = null

    @Synchronized
    fun route(playerUrl: String, keepAlive: Boolean): Route = when {
        !enabled || !keepAlive || playerUrl in rejected -> Route.OFF
        playerUrl !in verified -> Route.UNCHECKED
        loadedUrl == playerUrl -> Route.HIT
        else -> Route.LOAD
    }

    /**
     * Whether a solve should take the V8 lock to ask the memo: yes when the kept solvers may answer,
     * or when a warm-up is checking this player (waiting for its verdict beats evaluating the player
     * next to it). No when the memo is off or nothing is checking the player, so today's path runs
     * exactly as before, its cache read overlapping whatever V8 is doing.
     */
    @Synchronized
    fun worthWaiting(playerUrl: String, keepAlive: Boolean): Boolean = when (route(playerUrl, keepAlive)) {
        Route.OFF -> false
        Route.UNCHECKED -> checking.containsKey(playerUrl)
        Route.LOAD, Route.HIT -> true
    }

    /** The `memo=` of a `v8-run` line for a solve that took today's path. */
    fun fullPathLabel(playerUrl: String, keepAlive: Boolean): String =
        if (route(playerUrl, keepAlive) == Route.OFF) LABEL_OFF else LABEL_MISS

    @Synchronized
    fun onCheckStarted(playerUrl: String) {
        checking[playerUrl] = (checking[playerUrl] ?: 0) + 1
    }

    @Synchronized
    fun onCheckFinished(playerUrl: String) {
        val left = (checking[playerUrl] ?: 0) - 1
        if (left > 0) {
            checking[playerUrl] = left
        } else {
            checking.remove(playerUrl)
        }
    }

    /** A script that evaluates [playerUrl] into the memo is about to run: it replaces whatever is held. */
    @Synchronized
    fun onLoadStarted(playerUrl: String) {
        slotUrl = playerUrl
        loadedUrl = null
    }

    /** The load of [playerUrl] finished in the runtime that is still alive. */
    @Synchronized
    fun onLoaded(playerUrl: String) {
        if (slotUrl == playerUrl) {
            loadedUrl = playerUrl
        }
    }

    @Synchronized
    fun onVerified(playerUrl: String, codeFingerprint: Long) {
        if (playerUrl !in rejected) {
            verified[playerUrl] = codeFingerprint
        }
    }

    /** The code about to be loaded for a checked player is the code that was checked. */
    @Synchronized
    fun matchesCheck(playerUrl: String, codeFingerprint: Long): Boolean = verified[playerUrl] == codeFingerprint

    /**
     * Today's path is about to evaluate [playerUrl] in the runtime. A player's code may write globals
     * that its solvers read, so another player's kept solvers are not trusted past it. True when the
     * slot held another player: the caller drops it (it would only be evaluated again before
     * answering, a LOAD at today's cost). Offline, three real players showed no such interference;
     * with one player in use, the normal case, this never fires.
     */
    @Synchronized
    fun onFullEvaluation(playerUrl: String): Boolean {
        if (slotUrl == null || slotUrl == playerUrl) {
            return false
        }
        slotUrl = null
        loadedUrl = null
        return true
    }

    @Synchronized
    fun holdsSlot(playerUrl: String): Boolean = slotUrl == playerUrl

    /** The runtime's newtube.memo.js slot was emptied (__ntDrop). */
    @Synchronized
    fun onSlotDropped() {
        slotUrl = null
        loadedUrl = null
    }

    @Synchronized
    fun onRuntimeDisposed() {
        slotUrl = null
        loadedUrl = null
    }

    /**
     * Turns the memo off for [playerUrl] for the rest of the process. True the first time, to log it
     * once. The slot is left to the caller ([holdsSlot]): it may hold another player that is fine.
     */
    @Synchronized
    fun reject(playerUrl: String): Boolean {
        verified.remove(playerUrl)
        if (loadedUrl == playerUrl) {
            loadedUrl = null
        }
        return rejected.add(playerUrl)
    }

    companion object {
        const val LABEL_HIT = "hit"
        const val LABEL_MISS = "miss"
        const val LABEL_OFF = "off"

        const val SHIM_FILE = "nsigsolver/newtube.memo.js"
        const val DROP_STDIN = "__ntDrop();"

        private val gson = Gson()

        /**
         * Length and String.hashCode of the preprocessed player: tells a LOAD whether the cache
         * still holds the code the guard checked. Not cryptographic: the cache is the app's own and
         * player URLs are versioned, so this is against a mix-up, not an attacker. One pass over
         * ~3.7M chars, on a check or a LOAD only, never on a hit.
         */
        fun fingerprint(code: String): Long = (code.length.toLong() shl 32) or (code.hashCode().toLong() and 0xffffffffL)

        /** The request list exactly as JsRuntimeChalBaseJCP.constructStdin sends it to jsc(). */
        fun requestsJson(requests: List<JsChallengeRequest>): String = gson.toJson(requests.map { request ->
            mapOf(
                "type" to request.type.value,
                "challenges" to request.input.challenges
            )
        })

        /** A hit: the challenges only, no player. */
        fun solveStdin(playerUrl: String, requests: List<JsChallengeRequest>): String =
            "JSON.stringify(__ntSolve(${gson.toJson(playerUrl)}, ${requestsJson(requests)}));"

        /** One evaluation (today's cost) that the runtime then keeps. */
        fun loadAndSolveStdin(playerUrl: String, code: String, requests: List<JsChallengeRequest>): String =
            "JSON.stringify(__ntLoadAndSolve(${gson.toJson(playerUrl)}, ${gson.toJson(code)}, ${requestsJson(requests)}));"

        /** The guard: kept solvers, jsc() and the kept solvers again, on the same challenges. */
        fun checkStdin(playerUrl: String, code: String, requests: List<JsChallengeRequest>): String =
            "JSON.stringify(__ntCheck(${gson.toJson(playerUrl)}, ${gson.toJson(code)}, ${requestsJson(requests)}));"

        fun parseOutput(stdout: String?): SolverOutput? = try {
            gson.fromJson(stdout, solverOutputType)
        } catch (e: JsonParseException) {
            null
        }

        /** Every request answered, none with an error, a value for every challenge. */
        fun isClean(output: SolverOutput?, requests: List<JsChallengeRequest>): Boolean {
            // Gson fills Kotlin non-null fields with null when the JSON lacks them.
            val responses: List<ResponseData?>? = output?.responses
            if (output?.type != "result" || responses == null || responses.size != requests.size) {
                return false
            }
            for ((request, response) in requests.zip(responses)) {
                val data: Map<String, String?>? = response?.data
                if (response?.type != "result" || data == null) {
                    return false
                }
                if (request.input.challenges.any { data[it] == null }) {
                    return false
                }
            }
            return true
        }

        /**
         * The same answer for the caller: the same result values, or an error in the same place. Error
         * texts are not compared, they carry a stack that differs between the two call paths.
         */
        fun sameAnswer(a: SolverOutput?, b: SolverOutput?): Boolean {
            val ar: List<ResponseData?>? = a?.responses
            val br: List<ResponseData?>? = b?.responses
            if (a == null || b == null || a.type != b.type || ar?.size != br?.size) {
                return false
            }
            if (ar == null || br == null) {
                return true
            }
            for ((x, y) in ar.zip(br)) {
                if (x == null || y == null || x.type != y.type) {
                    return false
                }
                val xData: Map<String, String?>? = x.data
                val yData: Map<String, String?>? = y.data
                if (x.type == "result" && xData != yData) {
                    return false
                }
            }
            return true
        }

        /**
         * The guard's verdict on [checkStdin]'s output: null when the kept solvers answered exactly
         * what today's path did, twice, else why they may not answer for this player.
         */
        fun checkVerdict(stdout: String?, requests: List<JsChallengeRequest>): String? {
            val check = try {
                gson.fromJson(stdout, CheckOutput::class.java)
            } catch (e: JsonParseException) {
                null
            } ?: return "parse"
            if (!isClean(check.full, requests)) {
                return "full-error"
            }
            val memo: List<SolverOutput?>? = check.memo
            if (memo == null || memo.size != 2) {
                return "parse"
            }
            if (memo.any { !sameAnswer(check.full, it) }) {
                return "mismatch"
            }
            return null
        }

        /**
         * One player's answer: the kept solvers' when it is clean, else today's path's. [onUnclean]
         * sees both, so a memo that disagrees with today's path can be turned off.
         */
        inline fun answer(
            memo: SolverOutput?,
            requests: List<JsChallengeRequest>,
            full: () -> SolverOutput,
            onUnclean: (memo: SolverOutput, full: SolverOutput) -> Unit
        ): SolverOutput {
            if (memo != null && isClean(memo, requests)) {
                return memo
            }
            val fullOutput = full()
            if (memo != null) {
                onUnclean(memo, fullOutput)
            }
            return fullOutput
        }
    }

    private class CheckOutput(
        val full: SolverOutput?,
        val memo: List<SolverOutput?>?
    )
}
