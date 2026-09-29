/*
 * NEWTUBE(v8-memo): keep each player's evaluated n/sig solvers in the retained V8 runtime.
 *
 * yt.solver.core.js main() evaluates the whole preprocessed player again on every call
 * (getFromPrepared: Function('_result', code)(resultObj)). On the Pixel 9 that was ~118 ms of V8
 * per solve, after ~99 ms of reading the ~3.7 MB player from the cache and JSON-encoding it, on
 * every TV_TIZEN / WEB_EMBED / MWEB answer (netbench ttff-analysis.md 3.1). These helpers evaluate
 * a player once and answer later solves from the kept functions.
 *
 * The vendored yt.solver.core.js is untouched: __ntLoad is its getFromPrepared() and __ntAnswer
 * its main() request mapping, copied as they are (SolverMemoTest fails when an upstream update
 * changes either). V8ChallengeProvider / SolverMemo decide when these run, and __ntCheckLoad /
 * __ntCheckFinish cross-check them against jsc() itself before the kept solvers may answer a real
 * request. Checked offline in node against three real players (7460dd14, eb27e5fd, fb50cd46): the
 * same answers as jsc() on 120 random challenge sets, errors included.
 *
 * NEWTUBE(v8-priority): the preprocessed player is also staged here once (__ntStage), and today's
 * full path, jsc() itself, runs on the staged code (__ntFull) instead of a copy sent with every
 * solve. The staged code is what the cache holds for that player, or what jsc() just preprocessed.
 */
var __nt = Object.create(null);
var __ntStaged = null;
var __ntFirst = null;

function __ntLoad(key, code) {
  const resultObj = { n: null, sig: null };
  Function('_result', code)(resultObj);
  // One evaluated player per runtime: loading another one drops the previous one.
  __nt = Object.create(null);
  __nt[key] = resultObj;
  return resultObj;
}

function __ntAnswer(solvers, requests) {
  const responses = requests.map((input) => {
    if (!['n', 'sig'].includes(input.type)) {
      return { type: 'error', error: `Unknown request type: ${input.type}` };
    }
    const solver = solvers[input.type];
    if (!solver) {
      return {
        type: 'error',
        error: `Failed to extract ${input.type} function`,
      };
    }
    try {
      return {
        type: 'result',
        data: Object.fromEntries(
          input.challenges.map((challenge) => [challenge, solver(challenge)]),
        ),
      };
    } catch (error) {
      return {
        type: 'error',
        error:
          error instanceof Error
            ? `${error.message}\n${error.stack}`
            : `${error}`,
      };
    }
  });
  return { type: 'result', responses: responses };
}

function __ntSolve(key, requests) {
  const solvers = __nt[key];
  if (!solvers) {
    throw new Error('newtube memo: player not loaded');
  }
  return __ntAnswer(solvers, requests);
}

function __ntLoadAndSolve(key, code, requests) {
  return __ntAnswer(__ntLoad(key, code), requests);
}

// One player's preprocessed code per runtime: staging another one replaces it.
function __ntStage(key, code) {
  __ntStaged = { key: key, code: code };
  return '';
}

function __ntStagedCode(key) {
  if (!__ntStaged || __ntStaged.key !== key) {
    throw new Error('newtube memo: player not staged');
  }
  return __ntStaged.code;
}

// Today's full path on the staged code: jsc() exactly as JsRuntimeChalBaseJCP sends it.
function __ntFull(key, requests) {
  return jsc({
    type: 'preprocessed',
    preprocessed_player: __ntStagedCode(key),
    requests: requests,
  });
}

// Today's full path for a player not preprocessed yet (a new player's validation): jsc() exactly
// as JsRuntimeChalBaseJCP sends it; the preprocessed code it returns is staged as well.
function __ntFullPlayer(key, player, requests) {
  const output = jsc({
    type: 'player',
    player: player,
    requests: requests,
    output_preprocessed: true,
  });
  if (output && output.type === 'result' && typeof output.preprocessed_player === 'string') {
    __ntStage(key, output.preprocessed_player);
  }
  return output;
}

// A player checked earlier in this process, evaluated again from the staged code.
function __ntLoadAndSolveStaged(key, requests) {
  return __ntAnswer(__ntLoad(key, __ntStagedCode(key)), requests);
}

// The guard, in two steps so that a solve can run between them (one evaluation each). The kept
// solvers answer twice, around today's full path (jsc) on the same challenges, so a copy that
// drifted from main(), a solver that fails when called again, or one whose answer moves with state
// or with globals a fresh evaluation rewrites can show up as a difference. A sample on fixed
// challenges, not a proof for every challenge (see SolverMemo).
function __ntCheckLoad(key, requests) {
  __ntFirst = null;
  const first = __ntAnswer(__ntLoad(key, __ntStagedCode(key)), requests);
  __ntFirst = { key: key, answer: first };
  return '';
}

function __ntCheckFinish(key, requests) {
  if (!__ntFirst || __ntFirst.key !== key) {
    throw new Error('newtube memo: check not loaded');
  }
  const first = __ntFirst.answer;
  __ntFirst = null;
  const full = __ntFull(key, requests);
  const second = __ntSolve(key, requests);
  return { full: full, memo: [first, second] };
}

function __ntDrop() {
  __nt = Object.create(null);
  __ntFirst = null;
  return '';
}
