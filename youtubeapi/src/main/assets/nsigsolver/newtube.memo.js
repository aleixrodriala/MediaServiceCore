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
 * changes either). V8ChallengeProvider / SolverMemo decide when these run, and __ntCheck
 * cross-checks them against jsc() itself before the kept solvers may answer a real request.
 * Checked offline in node against three real players (7460dd14, eb27e5fd, fb50cd46): the same
 * answers as jsc() on 120 random challenge sets, errors included.
 */
var __nt = Object.create(null);

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

// The guard. The kept solvers answer twice, around today's full path (jsc) on the same
// challenges, so a copy that drifted from main(), a solver that fails when called again, or one
// whose answer moves with state or with globals a fresh evaluation rewrites can show up as a
// difference. A sample on fixed challenges, not a proof for every challenge (see SolverMemo).
function __ntCheck(key, code, requests) {
  const first = __ntAnswer(__ntLoad(key, code), requests);
  const full = jsc({
    type: 'preprocessed',
    preprocessed_player: code,
    requests: requests,
  });
  const second = __ntSolve(key, requests);
  return { full: full, memo: [first, second] };
}

function __ntDrop() {
  __nt = Object.create(null);
  return '';
}
