# codemap v0.5 benchmark (Python analyzer = golden, PSI analyzer = candidate)

The stdlib Python analyzer (`codemap/analyzer.py`) is the behavioural spec. Its output on four
corpora is frozen in `bench/golden/<name>.json`; the PyCharm plugin's headless export is diffed
against it with `tools/graphdiff.py`. See SPEC.md "v0.5 ... Benchmark / acceptance" and PLAN-psi.md.

| corpus | source | project root analysed | files | nodes | edges | entrypoints | calls resolved / external |
|---|---|---|---|---|---|---|---|
| gis_api | `/home/sushil/PycharmProjects/gis_api` | `$CORPORA/gis_api` | 292 | 1469 | 1884 | 194 | 1816 / 4357 |
| fastapi | site-packages/fastapi | `$CORPORA/fastapi` (contains `fastapi/`) | 48 | 531 | 614 | 0 | 630 / 784 |
| celery | site-packages/celery | `$CORPORA/celery` (contains `celery/`) | 161 | 3414 | 3459 | 50 | 3098 / 4835 |
| sqlalchemy | site-packages/sqlalchemy | `$CORPORA/sqlalchemy` (contains `sqlalchemy/`) | 256 | 12624 | 19354 | 19 | 13962 / 11318 |

(site-packages = `/home/sushil/anaconda3/envs/gis_api/lib/python3.13/site-packages`; numbers from 2026-11-04.)

## 1. Recreate the corpora (copies only)

Never analyse or open the originals in the IDE (opening a project writes `.idea/`). Copies go to
the scratchpad by default (`$CORPORA`):

```bash
bench/corpora.sh                 # or: bench/corpora.sh /some/dir   or CODEMAP_CORPORA=/some/dir bench/corpora.sh
# default CORPORA=/tmp/claude-1000/-home-sushil-PycharmProjects-gis-api/679583d0-1cbe-4a24-b9b3-6a164c92c301/scratchpad/psi/corpora
```

`rsync -a --delete`, excluding `.git`, `.idea`, `__pycache__`, `*.pyc`. Each package is copied
*into* a directory named after it, so module names stay `fastapi.routing`, `celery.app.base`, ...

## 2. Regenerate the golden graphs

```bash
cd /home/sushil/PycharmProjects/codemap
python3 tools/make_golden.py                    # all four; or: tools/make_golden.py gis_api --corpora DIR
```

Runs `codemap.analyzer.build_graph(<CORPORA>/<name>)` and writes `bench/golden/<name>.json`, with
`generated_at`, `version` and `stats.ms` removed and keys sorted: re-running on an unchanged corpus
is byte-identical. `root` is the copy dir, which is exactly the root the Java exporter must be
given (graphdiff ignores `root` anyway). Regenerate only when analyzer.py behaviour intentionally
changes or the corpora are refreshed, and say so in the commit.

## 3. Diff a candidate

```bash
# candidate = headless export of the same copy, e.g. codemap-export $CORPORA/gis_api /tmp/.../gis_api.json
python3 tools/graphdiff.py bench/golden/gis_api.json CANDIDATE.json \
    --explained bench/DIFFERENCES.md [--json report.json] [--top 20]
```

Exit code 0 only if all gates pass (1 = gate failed, 2 = bad input):

* **a**: every golden edge of kind `calls`/`depends`/`instantiates`/`inherits`/`includes_router`/`routes`
  is in the candidate (same source, target, kind) or explained in `DIFFERENCES.md`;
* **b**: same entrypoint set (node id) with the same `type`/`label`/`method`/`path`, or explained;
* **c**: candidate's resolved internal edges (distinct gated-kind edges) >= golden's.

Reported only (never a failure): missing/extra nodes, node field mismatches (kind/file/line/...),
signature/doc differences (whitespace-only ones listed separately), `references`/`implements`
differences, extra edges (samples printed so reviewers can check them), call-site line mismatches,
`stats` and `external_calls` counts, and `DIFFERENCES.md` entries that matched nothing.

## 4. Explaining a difference

When a golden edge/entrypoint is legitimately absent or different (typically: the Python analyzer
was wrong), add a line to `bench/DIFFERENCES.md` under the corpus heading:

```
- calls gis_api.x:f -> gis_api.y:g: golden resolved the name through a shadowed import
- entrypoint gis_api.app:create_app: label now includes the resolved prefix
```

Format details are in the header of `DIFFERENCES.md`.

## Tests

```bash
python3 -m unittest tests.test_graphdiff -v     # 16 tests, incl. golden-vs-itself = pass, mutated = fail
```
