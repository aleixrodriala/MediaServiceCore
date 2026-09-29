package com.liskovsoft.youtubeapi.app.nsigsolver.runtime

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.ChallengeInput
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeRequest
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NEWTUBE(v8-memo): the JVM-testable half of the solver memo - which path a solve takes, what it
 * sends to V8, and the guard's verdicts. J2V8 does not run here, so nothing below evaluates a player;
 * newtube.memo.js is checked against the vendored solver by text (the last two tests). Synthetic
 * values only: no player JS, no network.
 */
class SolverMemoTest {
    private val playerA = "https://example.invalid/s/player/aaaa/player.js"
    private val playerB = "https://example.invalid/s/player/bbbb/player.js"

    private fun requests(url: String = playerA, n: List<String> = listOf("n1", "n2"), sig: List<String> = listOf("s1")) =
        listOf(
            JsChallengeRequest(JsChallengeType.N, ChallengeInput(url, n)),
            JsChallengeRequest(JsChallengeType.SIG, ChallengeInput(url, sig))
        )

    private val codeA = SolverMemo.fingerprint("player A code")
    private val codeB = SolverMemo.fingerprint("player B code")

    private fun hitRoute(memo: SolverMemo = SolverMemo(), url: String = playerA): SolverMemo {
        memo.onLoadStarted(url)
        memo.onVerified(url, codeA)
        memo.onLoaded(url)
        assertEquals(SolverMemo.Route.HIT, memo.route(url, true))
        return memo
    }

    // region routing

    @Test
    fun uncheckedPlayerTakesTodaysPath() {
        val memo = SolverMemo()
        assertEquals(SolverMemo.Route.UNCHECKED, memo.route(playerA, true))
        assertEquals("miss", memo.fullPathLabel(playerA, true))
    }

    @Test
    fun loadedButUncheckedPlayerStillTakesTodaysPath() {
        // Fail closed: only the guard's pass lets the kept solvers answer.
        val memo = SolverMemo()
        memo.onLoadStarted(playerA)
        memo.onLoaded(playerA)
        assertEquals(SolverMemo.Route.UNCHECKED, memo.route(playerA, true))
    }

    @Test
    fun checkedPlayerLoadsOnceThenHits() {
        val memo = hitRoute()
        memo.onRuntimeDisposed()
        assertEquals(SolverMemo.Route.LOAD, memo.route(playerA, true))
        memo.onLoadStarted(playerA)
        assertEquals("a load in flight answers nothing", SolverMemo.Route.LOAD, memo.route(playerA, true))
        memo.onLoaded(playerA)
        assertEquals(SolverMemo.Route.HIT, memo.route(playerA, true))
        assertEquals("miss", memo.fullPathLabel(playerA, true))
    }

    @Test
    fun releasedRuntimeKeepsTheVerdictNotTheSolvers() {
        val memo = hitRoute()
        memo.onRuntimeDisposed()
        assertEquals(SolverMemo.Route.LOAD, memo.route(playerA, true))
        assertFalse(memo.holdsSlot(playerA))
    }

    @Test
    fun aLoadThatOutlivedItsRuntimeIsNotRecorded() {
        // keep-alive switched off during a LOAD: runTimed disposed the runtime, then onLoaded ran.
        val memo = hitRoute()
        memo.onRuntimeDisposed()
        memo.onLoadStarted(playerA)
        memo.onRuntimeDisposed()
        memo.onLoaded(playerA)
        assertEquals(SolverMemo.Route.LOAD, memo.route(playerA, true))
    }

    @Test
    fun aLoadOfDifferentCodeIsRefused() {
        val memo = hitRoute()
        assertTrue(memo.matchesCheck(playerA, codeA))
        assertFalse(memo.matchesCheck(playerA, SolverMemo.fingerprint("player A code, other build")))
        assertFalse(memo.matchesCheck(playerB, codeA))
        assertEquals(SolverMemo.fingerprint("same"), SolverMemo.fingerprint(String("same".toCharArray())))
    }

    @Test
    fun rejectedPlayerIsOffForTheProcess() {
        val memo = hitRoute()
        assertTrue(memo.reject(playerA))
        assertFalse("logged once", memo.reject(playerA))
        assertEquals(SolverMemo.Route.OFF, memo.route(playerA, true))
        assertEquals("off", memo.fullPathLabel(playerA, true))
        assertNull("no warm-up work left", memo.warmupStep(playerA, true))
        memo.onVerified(playerA, codeA)
        memo.onLoadStarted(playerA)
        memo.onLoaded(playerA)
        assertEquals(SolverMemo.Route.OFF, memo.route(playerA, true))
        // Other players are unaffected.
        assertEquals(SolverMemo.Route.UNCHECKED, memo.route(playerB, true))
    }

    @Test
    fun rejectingLeavesAnotherPlayersSlotAlone() {
        // A's unclean answer is compared after B was loaded by another thread: B stays held.
        val memo = hitRoute(url = playerA)
        hitRoute(memo, playerB)
        assertTrue(memo.reject(playerA))
        assertFalse(memo.holdsSlot(playerA))
        assertTrue(memo.holdsSlot(playerB))
        assertEquals(SolverMemo.Route.HIT, memo.route(playerB, true))
    }

    @Test
    fun aFailedLoadStillOccupiesTheSlot() {
        // So turnMemoOff drops what the failed script left in the runtime.
        val memo = SolverMemo()
        memo.onLoadStarted(playerA)
        memo.reject(playerA)
        assertTrue(memo.holdsSlot(playerA))
        memo.onSlotDropped()
        assertFalse(memo.holdsSlot(playerA))
    }

    @Test
    fun switchAndDisposableRuntimeTurnItOff() {
        val memo = hitRoute()
        assertEquals(SolverMemo.Route.OFF, memo.route(playerA, false))
        assertEquals("off", memo.fullPathLabel(playerA, false))
        assertNull(memo.warmupStep(playerA, false))
        memo.enabled = false
        assertEquals(SolverMemo.Route.OFF, memo.route(playerA, true))
        assertEquals("off", memo.fullPathLabel(playerA, true))
        assertNull(memo.warmupStep(playerA, true))
        memo.enabled = true
        assertEquals(SolverMemo.Route.HIT, memo.route(playerA, true))
    }

    @Test
    fun anotherPlayersEvaluationDropsTheHeldSolvers() {
        val memo = hitRoute()
        assertFalse("the same player again: its own globals", memo.onFullEvaluation(playerA))
        assertEquals(SolverMemo.Route.HIT, memo.route(playerA, true))
        assertTrue("the caller drops the slot", memo.onFullEvaluation(playerB))
        assertEquals(SolverMemo.Route.LOAD, memo.route(playerA, true))
        assertFalse(memo.holdsSlot(playerA))
        assertFalse("nothing held, nothing to drop", memo.onFullEvaluation(playerB))
    }

    @Test
    fun onePlayerPerRuntime() {
        val memo = hitRoute()
        memo.onVerified(playerB, codeA)
        memo.onLoadStarted(playerB)
        assertEquals(SolverMemo.Route.LOAD, memo.route(playerA, true))
        assertEquals("a load in flight answers nothing", SolverMemo.Route.LOAD, memo.route(playerB, true))
        memo.onLoaded(playerA) // a stale completion for the evicted player
        assertEquals(SolverMemo.Route.LOAD, memo.route(playerA, true))
        memo.onLoaded(playerB)
        assertEquals(SolverMemo.Route.HIT, memo.route(playerB, true))
        assertEquals(SolverMemo.Route.LOAD, memo.route(playerA, true))
    }

    // endregion

    // region NEWTUBE(v8-priority): the staged code and the check in two halves

    @Test
    fun theStagedCodeIsOnePlayerPerRuntime() {
        val memo = SolverMemo()
        assertFalse(memo.isStaged(playerA))
        assertNull(memo.stagedFingerprint(playerA))
        memo.onStaged(playerA, codeA)
        assertTrue(memo.isStaged(playerA))
        assertEquals(codeA, memo.stagedFingerprint(playerA))
        assertNull("another player", memo.stagedFingerprint(playerB))

        memo.onSlotDropped()
        assertTrue("dropping the memo's slot keeps today's input", memo.isStaged(playerA))
        assertFalse("another player's evaluation does not unstage it", memo.onFullEvaluation(playerB))
        assertTrue(memo.isStaged(playerA))

        memo.onStaged(playerB, codeB)
        assertFalse("staging another player replaces it", memo.isStaged(playerA))
        assertTrue(memo.isStaged(playerB))
        memo.onStageLost()
        assertFalse(memo.isStaged(playerB))
        memo.onStaged(playerA, codeA)
        memo.onRuntimeDisposed()
        assertFalse("a new runtime keeps nothing", memo.isStaged(playerA))
    }

    @Test
    fun theCheckInTwoHalvesStillFailsClosed() {
        val memo = SolverMemo()
        memo.onStaged(playerA, codeA)
        memo.onLoadStarted(playerA)
        memo.onCheckLoaded(playerA, codeA)
        assertTrue(memo.checkLoaded(playerA, codeA))
        assertEquals("loaded by the first half, it answers nothing", SolverMemo.Route.UNCHECKED, memo.route(playerA, true))
        assertEquals("miss", memo.fullPathLabel(playerA, true))

        memo.onVerified(playerA, codeA)
        assertEquals("only the verdict lets it answer", SolverMemo.Route.HIT, memo.route(playerA, true))
        assertFalse("the halves are spent", memo.checkLoaded(playerA, codeA))
        assertTrue(memo.matchesCheck(playerA, codeA))
    }

    @Test
    fun theSecondHalfRunsOnlyOnWhatTheFirstLoaded() {
        fun firstHalf(memo: SolverMemo) {
            memo.onStaged(playerA, codeA)
            memo.onLoadStarted(playerA)
            memo.onCheckLoaded(playerA, codeA)
            assertTrue(memo.checkLoaded(playerA, codeA))
        }

        var memo = SolverMemo()
        firstHalf(memo)
        assertTrue("another player's evaluation in between drops the slot", memo.onFullEvaluation(playerB))
        assertFalse(memo.checkLoaded(playerA, codeA))

        memo = SolverMemo()
        firstHalf(memo)
        memo.onLoadStarted(playerB)
        assertFalse("another player loaded in between", memo.checkLoaded(playerA, codeA))

        memo = SolverMemo()
        firstHalf(memo)
        memo.onStaged(playerA, codeB)
        assertFalse("other code staged under the same URL in between", memo.checkLoaded(playerA, codeA))
        assertFalse(memo.checkLoaded(playerA, codeB))

        memo = SolverMemo()
        firstHalf(memo)
        memo.onStaged(playerB, codeB)
        assertFalse("the code today's path runs on is gone", memo.checkLoaded(playerA, codeA))

        memo = SolverMemo()
        firstHalf(memo)
        memo.onStageLost()
        assertFalse(memo.checkLoaded(playerA, codeA))

        memo = SolverMemo()
        firstHalf(memo)
        memo.onRuntimeDisposed()
        assertFalse(memo.checkLoaded(playerA, codeA))

        memo = SolverMemo()
        firstHalf(memo)
        memo.reject(playerA)
        assertFalse(memo.checkLoaded(playerA, codeA))
        assertEquals(SolverMemo.Route.OFF, memo.route(playerA, true))

        memo = SolverMemo()
        memo.onStaged(playerA, codeA)
        memo.onCheckLoaded(playerA, codeA)
        assertFalse("a first half that did not start a load of this player", memo.checkLoaded(playerA, codeA))
    }

    @Test
    fun theWarmupPlansStageThenBothHalves() {
        val memo = SolverMemo()
        assertEquals(MemoWarmup.Step.STAGE, memo.warmupStep(playerA, true))
        memo.onStaged(playerA, codeA)
        assertEquals(MemoWarmup.Step.CHECK_LOAD, memo.warmupStep(playerA, true))
        memo.onLoadStarted(playerA)
        assertEquals("a load in flight is not a first half", MemoWarmup.Step.CHECK_LOAD, memo.warmupStep(playerA, true))
        memo.onCheckLoaded(playerA, codeA)
        assertEquals(MemoWarmup.Step.CHECK, memo.warmupStep(playerA, true))
        memo.onVerified(playerA, codeA)
        assertNull("it answers: nothing left", memo.warmupStep(playerA, true))

        // Checked, then released: the warm-up evaluates it again, no second check.
        memo.onRuntimeDisposed()
        assertEquals(MemoWarmup.Step.LOAD, memo.warmupStep(playerA, true))
        memo.onLoadStarted(playerA)
        memo.onLoaded(playerA)
        assertNull(memo.warmupStep(playerA, true))
    }

    @Test
    fun theWarmupPlansAgainWhatASolveUndid() {
        val memo = SolverMemo()
        memo.onStaged(playerA, codeA)
        memo.onLoadStarted(playerA)
        memo.onCheckLoaded(playerA, codeA)
        // A solve for another player stages it and evaluates it between the halves.
        memo.onStaged(playerB, codeB)
        memo.onFullEvaluation(playerB)
        assertEquals(MemoWarmup.Step.STAGE, memo.warmupStep(playerA, true))
        memo.onStaged(playerA, codeA)
        assertEquals("the first half again", MemoWarmup.Step.CHECK_LOAD, memo.warmupStep(playerA, true))
        memo.reject(playerA)
        assertNull("off for the process", memo.warmupStep(playerA, true))
        assertEquals("the other player's warm-up stages it again", MemoWarmup.Step.STAGE, memo.warmupStep(playerB, true))
    }

    // endregion

    // region what V8 is sent

    @Test
    fun hitSendsTheChallengesOnly() {
        val player = "x".repeat(1_000_000)
        val reqs = "[{\"type\":\"n\",\"challenges\":[\"n1\",\"n2\"]},{\"type\":\"sig\",\"challenges\":[\"s1\"]}]"
        val hit = SolverMemo.solveStdin(playerA, requests())
        val miss = SolverMemo.loadAndSolveStdin(playerA, player, requests())

        assertEquals("JSON.stringify(__ntSolve(\"$playerA\", $reqs));", hit)
        assertTrue(hit.length < 256)
        assertTrue(miss.startsWith("JSON.stringify(__ntLoadAndSolve(\"$playerA\", \"xxx"))
        assertTrue(miss.length > player.length)

        // NEWTUBE(v8-priority): on the staged code, today's path and the check send no player either.
        assertEquals("JSON.stringify(__ntFull(\"$playerA\", $reqs));", SolverMemo.fullStdin(playerA, requests()))
        assertEquals("__ntCheckLoad(\"$playerA\", $reqs);", SolverMemo.checkLoadStdin(playerA, requests()))
        assertEquals("JSON.stringify(__ntCheckFinish(\"$playerA\", $reqs));", SolverMemo.checkFinishStdin(playerA, requests()))
        assertEquals("JSON.stringify(__ntLoadAndSolveStaged(\"$playerA\", $reqs));",
            SolverMemo.loadAndSolveStagedStdin(playerA, requests()))

        // Staging sends it once; a new player's path sends the player as served.
        val stage = SolverMemo.stageStdin(playerA, player)
        assertTrue(stage.startsWith("__ntStage(\"$playerA\", \"xxx"))
        assertTrue(stage.length > player.length)
        assertEquals(stage + "\n" + SolverMemo.fullStdin(playerA, requests()),
            SolverMemo.stageAndFullStdin(playerA, player, requests()))
        val fresh = SolverMemo.fullPlayerStdin(playerA, player, requests())
        assertTrue(fresh.startsWith("JSON.stringify(__ntFullPlayer(\"$playerA\", \"xxx"))
        assertTrue(fresh.endsWith(", $reqs));"))
    }

    @Test
    fun valuesCannotBreakOutOfTheirLiterals() {
        val nasty = "a\"b'c\\d</script> e="
        val stdin = SolverMemo.solveStdin(nasty, requests(url = nasty, n = listOf(nasty), sig = listOf(nasty)))
        val args = stdin.removePrefix("JSON.stringify(__ntSolve(").removeSuffix("));")
        assertTrue(args.contains("a\\\"b")) // the quote is escaped
        assertFalse(args.contains("'"))
        assertFalse(args.contains("<"))
        assertFalse(args.contains(" ")) // a line terminator inside a JS string literal
        // Round trip: the key and the challenges come back exactly.
        val key = Gson().fromJson(args.substring(0, args.indexOf("\", [") + 1), String::class.java)
        assertEquals(nasty, key)
        val list: List<Map<String, Any>> = Gson().fromJson(args.substring(args.indexOf(", [") + 2),
            object : TypeToken<List<Map<String, Any>>>() {}.type)
        assertEquals(listOf(nasty), list[0]["challenges"])
        assertEquals("sig", list[1]["type"])
    }

    // endregion

    // region the guard

    private fun output(vararg responses: String) = "{\"type\":\"result\",\"responses\":[${responses.joinToString(",")}]}"
    private fun result(vararg pairs: Pair<String, String?>) =
        "{\"type\":\"result\",\"data\":{" + pairs.joinToString(",") { (k, v) -> "\"$k\":" + (v?.let { "\"$it\"" } ?: "null") } + "}}"
    private fun error(text: String) = "{\"type\":\"error\",\"error\":\"$text\"}"
    private fun check(full: String, first: String, second: String) = "{\"full\":$full,\"memo\":[$first,$second]}"

    private val good = output(result("n1" to "N1", "n2" to "N2"), result("s1" to "S1"))

    @Test
    fun checkPassesOnlyWhenBothMemoAnswersEqualTodaysPath() {
        assertNull(SolverMemo.checkVerdict(check(good, good, good), requests()))
    }

    @Test
    fun checkFailsClosed() {
        val wrongN = output(result("n1" to "N1", "n2" to "WRONG"), result("s1" to "S1"))
        assertEquals("mismatch", SolverMemo.checkVerdict(check(good, wrongN, good), requests()))
        assertEquals("stateful solver", "mismatch", SolverMemo.checkVerdict(check(good, good, wrongN), requests()))
        val memoError = output(result("n1" to "N1", "n2" to "N2"), error("boom"))
        assertEquals("mismatch", SolverMemo.checkVerdict(check(good, good, memoError), requests()))

        val fullError = output(result("n1" to "N1", "n2" to "N2"), error("no sig"))
        assertEquals("full-error", SolverMemo.checkVerdict(check(fullError, fullError, fullError), requests()))
        val fullNull = output(result("n1" to "N1", "n2" to null), result("s1" to "S1"))
        assertEquals("full-error", SolverMemo.checkVerdict(check(fullNull, fullNull, fullNull), requests()))
        val fullMissing = output(result("n1" to "N1"), result("s1" to "S1"))
        assertEquals("full-error", SolverMemo.checkVerdict(check(fullMissing, fullMissing, fullMissing), requests()))
        assertEquals("full-error", SolverMemo.checkVerdict("{\"full\":{\"type\":\"error\",\"error\":\"x\"},\"memo\":[]}", requests()))

        assertEquals("parse", SolverMemo.checkVerdict("{\"full\":$good,\"memo\":[$good]}", requests()))
        assertEquals("parse", SolverMemo.checkVerdict("{\"full\":$good}", requests()))
        assertEquals("parse", SolverMemo.checkVerdict("not json {", requests()))
        assertEquals("parse", SolverMemo.checkVerdict("null", requests()))
        assertEquals("parse", SolverMemo.checkVerdict(null, requests()))
    }

    @Test
    fun cleanMeansEveryChallengeAnswered() {
        fun clean(json: String) = SolverMemo.isClean(SolverMemo.parseOutput(json), requests())
        assertTrue(clean(good))
        assertFalse(clean(output(result("n1" to "N1", "n2" to "N2"))))
        assertFalse(clean(output(result("n1" to "N1", "n2" to "N2"), error("x"))))
        assertFalse(clean(output(result("n1" to "N1", "n2" to null), result("s1" to "S1"))))
        assertFalse(clean(output(result("n1" to "N1"), result("s1" to "S1"))))
        assertFalse(clean("{\"type\":\"error\",\"error\":\"x\"}"))
        assertFalse(clean("{\"type\":\"result\"}"))
        assertFalse(SolverMemo.isClean(null, requests()))
        assertNull(SolverMemo.parseOutput("not json {"))
    }

    @Test
    fun sameAnswerComparesValuesNotErrorTexts() {
        fun same(a: String, b: String) = SolverMemo.sameAnswer(SolverMemo.parseOutput(a), SolverMemo.parseOutput(b))
        assertTrue(same(good, good))
        assertFalse(same(good, output(result("n1" to "N1", "n2" to "X"), result("s1" to "S1"))))
        assertTrue("stacks differ between the two call paths",
            same(output(result("n1" to "N1"), error("at __ntAnswer")), output(result("n1" to "N1"), error("at main"))))
        assertFalse(same(output(result("n1" to "N1"), error("x")), output(result("n1" to "N1"), result("s1" to "S1"))))
        assertFalse(same(good, output(result("n1" to "N1", "n2" to "N2"))))
        assertFalse(SolverMemo.sameAnswer(null, SolverMemo.parseOutput(good)))
    }

    @Test
    fun theMemoAnswersOnlyWhenClean() {
        val reqs = requests()
        val clean = SolverMemo.parseOutput(good)!!
        val full = SolverMemo.parseOutput(output(result("n1" to "F1", "n2" to "F2"), result("s1" to "FS")))!!
        val unclean = SolverMemo.parseOutput(output(result("n1" to "N1", "n2" to "N2"), error("x")))!!
        var fullRuns = 0
        val seen = mutableListOf<Pair<SolverOutput, SolverOutput>>()

        assertSame(clean, SolverMemo.answer(clean, reqs, { fullRuns++; full }, { m, f -> seen.add(m to f) }))
        assertEquals(0, fullRuns)

        assertSame(full, SolverMemo.answer(null, reqs, { fullRuns++; full }, { m, f -> seen.add(m to f) }))
        assertEquals(1, fullRuns)
        assertTrue("no memo answer, nothing to compare", seen.isEmpty())

        assertSame(full, SolverMemo.answer(unclean, reqs, { fullRuns++; full }, { m, f -> seen.add(m to f) }))
        assertEquals(2, fullRuns)
        assertEquals(listOf(unclean to full), seen)
    }

    // endregion

    // region newtube.memo.js against the vendored solver

    private fun asset(name: String): String {
        val file = listOf("src/main/assets/nsigsolver/$name", "youtubeapi/src/main/assets/nsigsolver/$name")
            .map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("asset $name", file)
        return file!!.readText().replace("\r\n", "\n")
    }

    /** [from] up to and including the first [to] after it, each line trimmed. */
    private fun block(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing: $from", start >= 0)
        val end = text.indexOf(to, start)
        assertTrue("missing end of: $from", end >= 0)
        return text.substring(start, end + to.length).lines().joinToString("\n") { it.trim() }
    }

    @Test
    fun shimDefinesWhatTheStdinCalls() {
        val shim = asset("newtube.memo.js")
        for (call in listOf(
            SolverMemo.solveStdin(playerA, requests()),
            SolverMemo.loadAndSolveStdin(playerA, "code", requests()),
            SolverMemo.loadAndSolveStagedStdin(playerA, requests()),
            SolverMemo.stageStdin(playerA, "code"),
            SolverMemo.fullStdin(playerA, requests()),
            SolverMemo.fullPlayerStdin(playerA, "code", requests()),
            SolverMemo.checkLoadStdin(playerA, requests()),
            SolverMemo.checkFinishStdin(playerA, requests()),
            SolverMemo.DROP_STDIN
        )) {
            val name = Regex("(__nt\\w+)\\(").find(call)!!.groupValues[1]
            assertTrue("newtube.memo.js defines $name", shim.contains("function $name("))
        }
        assertEquals(SolverMemo.SHIM_FILE, "nsigsolver/newtube.memo.js")
        // Today's path on the staged code, the guard's included, is today's call: jsc() on the
        // preprocessed player, with the keys JsRuntimeChalBaseJCP.constructStdin sends.
        assertTrue(shim.contains("jsc({\n    type: 'preprocessed',\n    preprocessed_player: __ntStagedCode(key),\n    requests: requests,\n  })"))
        assertTrue(block(shim, "function __ntCheckFinish(key, requests) {", "\n}").contains("const full = __ntFull(key, requests);"))
        // A new player: jsc() on the player as served, asking for the preprocessed output it caches.
        assertTrue(shim.contains("jsc({\n    type: 'player',\n    player: player,\n    requests: requests,\n    output_preprocessed: true,\n  })"))
        // The guard's halves load the staged code, and dropping the memo forgets the first half.
        assertTrue(block(shim, "function __ntCheckLoad(key, requests) {", "\n}").contains("__ntLoad(key, __ntStagedCode(key))"))
        assertTrue(block(shim, "function __ntDrop() {", "\n}").contains("__ntFirst = null;"))
    }

    /**
     * The shim copies getFromPrepared() and main()'s request mapping out of the vendored
     * yt.solver.core.js. When an upstream solver update changes either, this fails: bring the copy
     * in newtube.memo.js in line (the on-device guard would also catch a behavioural difference, but
     * by turning the memo off).
     */
    @Test
    fun shimMirrorsTheVendoredSolver() {
        val core = asset("yt.solver.core.js")
        val shim = asset("newtube.memo.js")

        assertEquals(
            block(core, "function getFromPrepared(code) {", "return resultObj;"),
            block(shim, "function __ntLoad(key, code) {", "return resultObj;")
                .replace("function __ntLoad(key, code) {", "function getFromPrepared(code) {")
                .replace("\n// One evaluated player per runtime: loading another one drops the previous one.", "")
                .replace("\n__nt = Object.create(null);\n__nt[key] = resultObj;", ""))

        assertEquals(
            block(core, "const responses = input.requests.map((input) => {", "\n    });")
                .replace("input.requests.map", "requests.map")
                .replace("!isOneOf(input.type, 'n', 'sig')", "!['n', 'sig'].includes(input.type)"),
            block(shim, "const responses = requests.map((input) => {", "\n  });"))

        assertTrue(core.contains("const solvers = getFromPrepared(preprocessedPlayer);"))
        assertTrue(core.contains("const output = { type: 'result', responses: responses };"))
        assertTrue(shim.contains("return { type: 'result', responses: responses };"))
    }

    // endregion
}
