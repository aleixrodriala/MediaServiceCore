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
        assertFalse(memo.worthWaiting(playerA, true))
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
        assertFalse(memo.worthWaiting(playerA, false))
        memo.enabled = false
        assertEquals(SolverMemo.Route.OFF, memo.route(playerA, true))
        assertEquals("off", memo.fullPathLabel(playerA, true))
        assertFalse(memo.worthWaiting(playerA, true))
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

    @Test
    fun aSolveWaitsOnlyWhenWaitingCanPay() {
        val memo = SolverMemo()
        assertFalse("unchecked, no check under way: today's path, no lock", memo.worthWaiting(playerA, true))
        memo.onCheckStarted(playerA)
        assertTrue("the warm-up is checking it", memo.worthWaiting(playerA, true))
        assertFalse("another player", memo.worthWaiting(playerB, true))
        assertFalse("keep-alive off", memo.worthWaiting(playerA, false))
        memo.onCheckFinished(playerA)
        assertFalse(memo.worthWaiting(playerA, true))
        // Two extractors for one player: the first warm-up to finish does not end the wait.
        memo.onCheckStarted(playerA)
        memo.onCheckStarted(playerA)
        memo.onCheckFinished(playerA)
        assertTrue(memo.worthWaiting(playerA, true))
        memo.onCheckFinished(playerA)
        assertFalse(memo.worthWaiting(playerA, true))
        memo.onCheckFinished(playerA) // unbalanced: stays at none
        assertFalse(memo.worthWaiting(playerA, true))
        hitRoute(memo)
        assertTrue(memo.worthWaiting(playerA, true))
        memo.onRuntimeDisposed()
        assertTrue("LOAD", memo.worthWaiting(playerA, true))
    }

    // endregion

    // region what V8 is sent

    @Test
    fun hitSendsTheChallengesOnly() {
        val player = "x".repeat(1_000_000)
        val hit = SolverMemo.solveStdin(playerA, requests())
        val miss = SolverMemo.loadAndSolveStdin(playerA, player, requests())
        val check = SolverMemo.checkStdin(playerA, player, requests())

        assertEquals(
            "JSON.stringify(__ntSolve(\"$playerA\", "
                    + "[{\"type\":\"n\",\"challenges\":[\"n1\",\"n2\"]},{\"type\":\"sig\",\"challenges\":[\"s1\"]}]));",
            hit)
        assertTrue(hit.length < 256)
        assertTrue(miss.startsWith("JSON.stringify(__ntLoadAndSolve(\"$playerA\", \"xxx"))
        assertTrue(miss.length > player.length)
        assertTrue(check.startsWith("JSON.stringify(__ntCheck(\"$playerA\", \"xxx"))
        assertTrue(check.endsWith("[{\"type\":\"n\",\"challenges\":[\"n1\",\"n2\"]},{\"type\":\"sig\",\"challenges\":[\"s1\"]}]));"))
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
            SolverMemo.checkStdin(playerA, "code", requests()),
            SolverMemo.DROP_STDIN
        )) {
            val name = Regex("(__nt\\w+)\\(").find(call)!!.groupValues[1]
            assertTrue("newtube.memo.js defines $name", shim.contains("function $name("))
        }
        assertEquals(SolverMemo.SHIM_FILE, "nsigsolver/newtube.memo.js")
        // The guard's full path is today's call: jsc() on the preprocessed player.
        assertTrue(shim.contains("jsc({\n    type: 'preprocessed',\n    preprocessed_player: code,\n    requests: requests,\n  })"))
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
