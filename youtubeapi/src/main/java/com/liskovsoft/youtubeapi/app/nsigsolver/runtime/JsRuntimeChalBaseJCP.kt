package com.liskovsoft.youtubeapi.app.nsigsolver.runtime

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.youtubeapi.app.nsigsolver.common.CachedData
import com.liskovsoft.youtubeapi.app.nsigsolver.common.loadScript
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.ChallengeOutput
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProvider
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderError
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderRejectedRequest
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeProviderResponse
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeRequest
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeResponse
import com.liskovsoft.youtubeapi.app.nsigsolver.provider.JsChallengeType

internal abstract class JsRuntimeChalBaseJCP: JsChallengeProvider() {
    private val tag = JsRuntimeChalBaseJCP::class.simpleName
    protected val cacheSection = "challenge-solver"

    private val jcpGuideUrl = "https://github.com/yt-dlp/yt-dlp/wiki/YouTube-JS-Challenges"
    private val repository = "yt-dlp/ejs"
    override val supportedTypes = listOf(JsChallengeType.N, JsChallengeType.SIG)
    protected val scriptVersion = "0.0.1"
    protected val libPrefix = "nsigsolver/"

    private val scriptFilenames = mapOf(
        ScriptType.LIB to "${libPrefix}yt.solver.lib.js",
        ScriptType.CORE to "${libPrefix}yt.solver.core.js"
    )

    private val minScriptFilenames = mapOf(
        ScriptType.LIB to "yt.solver.lib.min.js",
        ScriptType.CORE to "yt.solver.core.min.js"
    )

    /** [playerUrl]: the player [stdin] evaluates (the v8-memo bookkeeping needs to know). */
    protected abstract fun runJsRuntime(stdin: String, playerUrl: String): String

    /**
     * One player's answer. Today's path here; NEWTUBE(v8-memo, v8-priority): V8ChallengeProvider
     * answers from the solvers its runtime keeps when the guard allows it, runs today's path on the
     * player's code staged in the runtime, and orders both against its warm-up (see V8Lane).
     */
    protected open fun solvePlayer(playerUrl: String, requests: List<JsChallengeRequest>): SolverOutput =
        solveFull(playerUrl, requests)

    override fun realBulkSolve(requests: List<JsChallengeRequest>): Sequence<JsChallengeProviderResponse> = sequence {
        val grouped: Map<String, List<JsChallengeRequest>> = requests.groupBy { it.input.playerUrl }

        for ((playerUrl, groupedRequests) in grouped) {
            val output = solvePlayer(playerUrl, groupedRequests)

            for ((request, responseData) in groupedRequests.zip(output.responses)) {
                if (responseData.type == "error") {
                    yield(JsChallengeProviderResponse(
                        request, null, JsChallengeProviderError(responseData.error ?: "Unknown solver output error")))
                } else {
                    yield(JsChallengeProviderResponse(
                        request, JsChallengeResponse(request.type, ChallengeOutput(responseData.data))
                    ))
                }
            }
        }
    }

    /** Today's path: read the player, send all of it, and V8 evaluates it before solving. */
    protected fun solveFull(playerUrl: String, groupedRequests: List<JsChallengeRequest>): SolverOutput {
        val data = ie.cache.load(cacheSection, "player:$playerUrl")
        var player = data?.code

        val cached = if (player != null) {
            true
        } else {
            player = getPlayer(playerUrl)
            false
        }

        val stdin = constructStdin(player, cached, groupedRequests)
        // A miss here means the whole player JS is re-preprocessed (parsed by meriyah, printed
        // by astring) inside V8 before a single challenge is solved -- by far the most expensive
        // thing this path can do, and invisible in the transform total without this line.
        android.util.Log.d("NetPath", "v8-player cached=" + (if (cached) "y" else "n")
                + " challenges=" + groupedRequests.sumOf { it.input.challenges.size })
        val stdout = runJsRuntime(stdin, playerUrl)

        val output = parseSolverOutput(stdout)
        storePreprocessed(playerUrl, output)
        return output
    }

    /** jsc()'s output; throws on an unparsable one or an error for the whole call, as today's path does. */
    protected fun parseSolverOutput(stdout: String?): SolverOutput {
        val gson = Gson()
        val parsed: SolverOutput? = try {
            gson.fromJson(stdout, solverOutputType)
        } catch (e: JsonSyntaxException) {
            throw JsChallengeProviderError("Cannot parse solver output", e)
        }
        val output = parsed ?: throw JsChallengeProviderError("Cannot parse solver output")

        if (output.type == "error")
            throw JsChallengeProviderError(output.error ?: "Unknown solver output error")

        return output
    }

    /** A player preprocessed by this call goes to the cache, for the next process. */
    protected fun storePreprocessed(playerUrl: String, output: SolverOutput) {
        val preprocessed = output.preprocessed_player
        if (preprocessed != null)
            ie.cache.store(cacheSection, "player:$playerUrl", CachedData(preprocessed))
    }

    /** The cache's preprocessed [playerUrl], or null. */
    protected fun cachedPlayer(playerUrl: String): String? = ie.cache.load(cacheSection, "player:$playerUrl")?.code

    protected fun constructStdin(player: String, preprocessed: Boolean, requests: List<JsChallengeRequest>): String {
        val jsonRequests = requests.map { request ->
            mapOf(
                // TODO: i despise nsig name
                //"type" to if (request.type.value == "n") "nsig" else request.type.value,
                "type" to request.type.value,
                "challenges" to request.input.challenges
            )
        }
        val data = if (preprocessed) {
            mapOf(
                "type" to "preprocessed",
                "preprocessed_player" to player,
                "requests" to jsonRequests
            )
        } else {
            mapOf(
                "type" to "player",
                "player" to player,
                "requests" to jsonRequests,
                "output_preprocessed" to true
            )
        }
        val gson = Gson()
        val jsonData = gson.toJson(data)
        return """
        JSON.stringify(jsc($jsonData));
        """
    }

    protected fun constructCommonStdin(): String {
        return """
        ${libScript.code}
        ${coreScript.code}
        "";
        """
    }

    // region: challenge solver script

    private val libScript: Script by lazy {
        getScript(ScriptType.LIB)
    }

    private val coreScript: Script by lazy {
        getScript(ScriptType.CORE)
    }

    private fun getScript(scriptType: ScriptType): Script {
        for ((_, fromSource) in iterScriptSources()) {
            val script = fromSource(scriptType)
            if (script == null)
                continue
            if (script.version != scriptVersion)
                Log.w(tag, "Challenge solver ${scriptType.value} script version ${script.version} " +
                        "is not supported (source: ${script.source.value}, supported version: $scriptVersion)")

            Log.d(tag, "Using challenge solver ${script.type.value} script v${script.version} " +
                    "(source: ${script.source.value}, variant: ${script.variant.value})")
            return script
        }
        throw JsChallengeProviderRejectedRequest("No usable challenge solver ${scriptType.value} script available")
    }

    protected open fun iterScriptSources(): Sequence<Pair<ScriptSource, (scriptType: ScriptType) -> Script?>> = sequence {
        yieldAll(listOf(
            Pair(ScriptSource.CACHE, ::cachedSource),
            Pair(ScriptSource.BUILTIN, ::builtinSource),
            Pair(ScriptSource.WEB, ::webReleaseSource)
        ))
    }

    private fun cachedSource(scriptType: ScriptType): Script? {
        val data = ie.cache.load(cacheSection, scriptType.value) ?: return null
        return Script(scriptType,
            ScriptVariant.valueOf(data.variant ?: "unknown"), ScriptSource.CACHE, data.version ?: "unknown", data.code)
    }

    private fun builtinSource(scriptType: ScriptType): Script? {
        val fileName = scriptFilenames[scriptType] ?: return null
        val code = loadScript(fileName, "Failed to read builtin challenge solver ${scriptType.value}")
        return Script(scriptType, ScriptVariant.UNMINIFIED, ScriptSource.BUILTIN, scriptVersion, code)
    }

    private fun webReleaseSource(scriptType: ScriptType): Script? {
        val fileName = minScriptFilenames[scriptType] ?: return null
        val url = "https://github.com/$repository/releases/download/$scriptVersion/$fileName"
        val code = ie.downloadWebpageWithRetries(url, "[${tag}] Failed to download challenge solver ${scriptType.value} script")
        Log.d(tag, "[${tag}] Downloading challenge solver ${scriptType.value} script from $url")
        ie.cache.store(cacheSection, scriptType.value, CachedData(code))
        return Script(scriptType, ScriptVariant.MINIFIED, ScriptSource.WEB, scriptVersion, code)
    }

    // endregion: challenge solver script
}