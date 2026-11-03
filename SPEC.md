# codemap — live, left-to-right code flow explorer for Python projects

Goal: browse a Python codebase as a **clean, ERD-like, left-to-right flow diagram** (NOT a force-directed
hairball / cluster blob). Start from an entrypoint (HTTP route, Celery task, `__main__`, app factory),
follow what it calls step by step, see file paths, jump into PyCharm with Ctrl+click. The graph updates
automatically when code changes.

First target project: `/home/sushil/PycharmProjects/gis_api` (FastAPI + SQLAlchemy + Celery, ~290 .py files).
The tool must be generic (any Python repo), never import the analyzed code (pure `ast`), and have
**zero third-party Python dependencies** (stdlib only, Python >= 3.10).

## Layout

```
codemap/                       (repo root: /home/sushil/PycharmProjects/codemap)
  SPEC.md
  README.md
  codemap/__init__.py
  codemap/__main__.py          -> `python -m codemap /path/to/repo [--port 8765] [--host 127.0.0.1] [--exclude ...]`
  codemap/analyzer.py          (AST -> graph dict)          [ANALYZER]
  codemap/server.py            (HTTP + watcher + SSE)       [SERVER]
  codemap/web/index.html, app.js, style.css                 [FRONTEND]
  codemap/web/vendor/elk.bundled.js   (already present, elkjs 0.9.3, global `ELK`)
  pycharm-plugin/              (Java IntelliJ plugin)       [PLUGIN]
  tests/                       (unittest, stdlib only)
```

## Graph JSON (analyzer output, served at GET /api/graph)

```jsonc
{
  "root": "/abs/path/to/repo",
  "project": "gis_api",                 // basename of root
  "version": 7,                         // set by server; increments on every rebuild
  "generated_at": "2026-10-31T23:50:00Z",
  "nodes": [
    {
      "id": "gis_api.modules.faq.api.router:list_faq",   // "<module>:<qualname>"; module node = "<module>:<module>"
      "kind": "function" | "method" | "class" | "module",
      "name": "list_faq",
      "qualname": "list_faq",               // "Class.method" for methods, "outer.<locals>.inner" for nested
      "module": "gis_api.modules.faq.api.router",
      "file": "gis_api/modules/faq/api/router.py",   // POSIX path relative to root
      "line": 24, "end_line": 37,
      "signature": "(municipality_code: str = 'LALITPUR', category: str | None = Query(default=None), db: Session = Depends(get_db)) -> list[dict]",
      "doc": "Enabled articles for a municipality. No auth.",   // first docstring line or ""
      "decorators": ["router.get('')"],     // ast.unparse of each decorator
      "is_async": false,
      "class_name": null,                   // owning class for methods
      "package": "gis_api.modules.faq",     // module minus last segment, for grouping/colour
      "loc": 14,
      "is_test": false,                     // file under tests/ | test/ | testing/, test_*.py, *_test.py, conftest.py
      "entrypoint": null | {
        "type": "http" | "websocket" | "celery_task" | "main" | "app_factory" | "cli" | "hook",
        "label": "GET /faq",                 // human label shown in UI
        "method": "GET", "path": "/faq"      // http only; path includes router prefix when resolvable
      }
    }
  ],
  "edges": [
    {
      "source": "<node id>", "target": "<node id>",
      "kind": "calls" | "depends" | "inherits" | "instantiates" | "includes_router" | "routes" | "references" | "implements",
      "file": "gis_api/modules/faq/api/router.py", "line": 31   // call site
    }
  ],
  "external_calls": { "<node id>": ["sqlalchemy.orm.Session.execute", "db.rollback", ...] },  // unresolved callee names (deduped, max 50) for detail panel
  "errors": [ { "file": "x.py", "message": "SyntaxError ..." } ],
  "stats": { "files": 290, "nodes": 2100, "edges": 5400, "entrypoints": 180, "ms": 900 }
}
```

### Analyzer rules (best-effort static resolution, favour precision over recall)
* Walk root for `*.py`, default excludes: `.git, .venv, venv, env, node_modules, __pycache__, .idea, build, dist, .mypy_cache, .pytest_cache, site-packages`, plus user `--exclude` globs. Module name derived from path relative to root (`a/b/c.py` -> `a.b.c`, `__init__.py` -> package name).
* Nodes: every module (kind=module, represents module-level code), class, function, method (incl. nested functions, qualname with `.<locals>.`).
* Resolve calls inside each function body (and module-level code for the module node):
  - local names defined in same module; `from x import y [as z]`; `import x.y as z` then `z.f()`; relative imports;
  - `self.m()` / `cls.m()` -> method on same class, walking resolvable base classes (MRO best-effort);
  - `ClassName(...)` -> edge kind `instantiates` to class node (and to its `__init__` if defined, kind `calls`);
  - `module_alias.func()` / `module_alias.Class.method()`; re-exports through `__init__.py` (follow one+ hops, cycle-safe);
  - simple local var typing: `x = ClassName(...)` then `x.m()`; parameters with annotations `p: ClassName` then `p.m()`.
  - Unresolved -> recorded in external_calls, no edge. Calling a variable bound to an unresolved factory result
    (`SessionLocal = sessionmaker(); SessionLocal()`) is recorded as `sqlalchemy.orm.sessionmaker()()`.
* Extra edge kinds (all restricted to resolvable internal targets):
  - `references`: an internal function/method (or a behaviour-carrying class, i.e. one with own non-dunder methods or
    `__call__`) used as a *value*: call argument (`add_task(job)`, `FastAPI(lifespan=f)`, `add_middleware(Cls)`), dict/list/
    tuple/set element, or `return`/`yield` value (closures returned by factories). Not emitted for `isinstance`/`issubclass`/
    `Depends` arguments, and dropped when the same pair already has a calls/instantiates/depends edge. A nested def that is
    decorated inside its parent (`@app.exception_handler(X)` in `register(app)`) gets a parent -> child `references` edge.
  - `implements`: abstract/stub method (`@abstractmethod`, body `...`/`pass`/`raise NotImplementedError`) -> overriding
    method in a subclass; and `typing.Protocol` method -> same-named method of every non-protocol class that structurally
    provides all of the protocol's methods.
  - `routes`: module node of the router variable -> each route handler decorated on it.
  - `includes_router` is also emitted from the *function* doing `x.include_router(r)` (e.g. `create_app`) to r's module, so
    app factory -> router modules -> handlers is a traceable chain.
* FastAPI: `Depends(fn)` / `Security(fn)` in parameters defaults or `dependencies=[Depends(fn)]` in decorators -> edge kind `depends`.
  Calls in decorator args/defaults count for the decorated function.
* Entrypoints:
  - decorator `<anything>.get|post|put|patch|delete|options|head|api_route|websocket(<str path>, ...)` -> http/websocket.
    Prefix: if the receiver is a module-level variable assigned from a call with a `prefix="..."` kwarg (e.g.
    `router = api_router(prefix="/faq")` or `APIRouter(prefix=...)`), prepend it. Also best-effort follow
    `x.include_router(r, prefix="/v1")` chains (edge kind `includes_router` from module of x to module of r) and prepend.
  - decorator `*.task`, `shared_task`, `*.task(...)` -> celery_task.
  - `if __name__ == "__main__":` -> the module node is an entrypoint type `main` (and functions it calls get edges from module node).
  - functions named `create_app`/`create_*app*` returning FastAPI/Flask -> `app_factory`; click/typer commands (`@*.command`) -> `cli`.
  - framework callbacks `@*.exception_handler(X)`, `@*.middleware(...)`, `@*.on_event(...)`, Flask `errorhandler`/
    `before_request`/`after_request`..., SQLAlchemy `listens_for` -> `hook` (label e.g. `exception_handler AppError`).
* Must analyze gis_api fully in < 3 s, never crash on a bad file (record in errors), deterministic output (sorted).
* Public API: `build_graph(root: str, excludes: list[str] | None = None) -> dict` (everything except "version").
  Also expose `iter_python_files(root, excludes) -> list[pathlib.Path]` (used by the watcher).

## Server HTTP API (stdlib `http.server.ThreadingHTTPServer`)
* Multi-project: `ProjectRegistry` holds one `CodemapApp` (graph + watcher + SSE) per project root, created on
  `GET /open?root=<abs dir>` (302 -> `/p/<dirname>-<sha1(root)[:8]>/`), unloaded after 30 min without an open page.
  The first build runs in the background: API clients get the 302 once it is done; browsers (`Accept: text/html`) get
  an "Analysing <project>…" page (polls `/p/<id>/api/status` → `"ready": true`, then opens the map keeping `#focus=…`)
  if it takes longer than 1.5 s. The watcher's poll interval backs off to 20× the last scan time (max 10 s).
  All endpoints below are served per project under `/p/<id>/`; the optional CLI root is also served at `/`.
  `GET /api/projects` / `GET /projects` list loaded projects. Loopback Host headers only.
* `GET /` and static files from `codemap/web/` (correct content types, no caching).
* `GET /api/graph` -> current graph JSON (gzip if `Accept-Encoding: gzip`).
* `GET /api/events` -> Server-Sent Events. On every rebuild send `event: graph\ndata: {"version": N, "changed": ["rel/path.py", ...]}\n\n`; heartbeat comment every 15 s.
* `GET /api/source?file=<rel>&start=<n>&end=<m>` -> `{"file":..., "start":..., "lines":[...]}` (path must stay inside root).
* `POST /api/open` body `{"file": "<rel>", "line": N}` -> asks PyCharm to open it. Tries, in order:
  1. PyCharm plugin endpoint `GET http://127.0.0.1:63342/api/codemap/open?file=<ABS path>&line=<N>` (provided by our plugin, built-in server port 63342..63352: probe; cache the working port).
  2. fallback: run `pycharm --line N <abs>` / `charm` if on PATH.
  Returns `{"ok": true, "via": "plugin"}` or `{"ok": false, "error": "..."}` (UI then shows a toast with the file:line).
* `GET /api/status` -> `{"version", "root", "watching": true, "last_build_ms", "errors": n}`.
* Watcher: background thread polling mtimes/size of all `.py` files every 1.0 s (stdlib; no watchdog). On change,
  debounce 300 ms, rebuild the full graph (cheap) in background, swap atomically, increment version, push SSE.
* `python -m codemap <root>` prints the URL. Binds 127.0.0.1 by default (WSL2 forwards localhost to Windows).

## Frontend (vanilla JS, no build step; ELK layered layout via `codemap/web/vendor/elk.bundled.js`)
Design principle: **show a focused subgraph, never the whole hairball.** Layout = ELK `layered`, `elk.direction: RIGHT`,
orthogonal edge routing, ports on the left/right of each node. Looks like an ERD / flow chart.
* Left sidebar:
  - search box (fuzzy over name/qualname/module/route path), keyboard ↑↓ Enter.
  - Entrypoints list grouped by type (HTTP routes grouped by path prefix, shows `GET /faq`), with counts; filter.
  - Module tree (package → module → classes/functions) as an alternative browser.
* Canvas (SVG, pan with drag, zoom with wheel, "fit" button):
  - Focus a node: it sits in the middle column; its callers flow in from the left (depth configurable 0–3, default 1),
    its callees flow out to the right (depth configurable 1–6, default 2). "Flow from entrypoint" = callers 0.
  - Node = ERD-style card: header coloured by kind/entrypoint (http=green, task=orange, class=purple, method=blue,
    function=teal, module=grey), title = name (methods `Class.method`), sub-line = `file:line` path, optional
    badge (`GET /faq`, `async`, `task`). Long names ellipsised. Optional "+N more" stub node when a node has
    more callees than shown (click to expand).
  - Edges: orthogonal, arrowheads, dashed for `depends`, dotted for `instantiates`/`inherits`; hover shows call line.
  - Click node = select (details panel). Double-click = re-focus on it (push to history; Back/Forward buttons + breadcrumb).
    Click a leaf's ▸ toggle = expand its callees in place. **Ctrl/Cmd+click = open in PyCharm** (POST /api/open);
    also an "Open in PyCharm" button in details.
  - Highlight on hover: the hovered node's incoming/outgoing edges.
* Right details panel: kind, qualname, `file:line` (clickable → open), signature, docstring, decorators,
  entrypoint info, callers list, callees list (each clickable = focus), external/unresolved calls, inline source
  preview (GET /api/source, syntax highlight is optional).
* Path finder: "Find path" from A to B (BFS over calls, depends, instantiates, references, implements, includes_router and
  routes edges, shortest few paths), rendered as the focused subgraph. A failed lookup (no path / unknown id) keeps the
  previous view, shows a persistent banner and adds no history entry.
* "+N" stubs expand 25 more callers/callees per click (and recenter on the parent); once the 150-node cap is reached the stub
  becomes a link to the full list in the details panel.
* Test code (`is_test`) entrypoints are grouped in a collapsed "Test code" section and ranked lower in search.
* Toggles: show/hide edge kinds; hide modules/externals; "collapse by module" view (nodes = modules, edges aggregated) for a high-level map.
* Live updates: EventSource `/api/events`; on new version re-fetch graph, keep current focus (by id), re-layout, briefly
  flash nodes in changed files, keep the details panel scroll position. If the focused node is missing (syntax error while
  typing, rename) keep focus + URL + the last layout greyed out with a banner and re-render automatically once it is back.
  Small "● live v12" indicator in header.
* Navigation uses `history.pushState` (one browser entry per in-app history entry), so browser/mouse Back & Forward work;
  in-app ‹ › and breadcrumbs go through `history.go`. Window resizes only recenter if the user has not panned/zoomed.
* URL hash keeps state (`#focus=<id>&in=1&out=2`) so views are shareable/bookmarkable and the plugin can open a focus.
* Dark theme matching PyCharm Darcula-ish colours. Must stay smooth: cap rendered nodes (~150) with "+N more" stubs.

## PyCharm plugin (Java, built with JBR's javac against local PyCharm jars — no Gradle)
PyCharm runs as **JetBrains Remote Development backend in WSL** (dist: `/home/sushil/.cache/JetBrains/RemoteDev/dist/c4a9ef17c7e8b_pycharm-253.29346.46`, build 253.29346.46, JBR at `<dist>/jbr`), the UI is the JetBrains Client on Windows. Plugin installs into the backend: `~/.local/share/JetBrains/PyCharm2025.3/codemap-pycharm/lib/codemap-pycharm.jar`.
* `com.intellij.httpRequestHandler` extension (`org.jetbrains.ide.HttpRequestHandler`) serving
  `GET /api/codemap/open?file=<abs>&line=<1-based>` on the built-in server (port 63342): finds the open project containing
  the file (or first project), opens it at line in the editor (OpenFileDescriptor(project, vf, line-1, 0).navigate(true))
  on the EDT, brings the IDE to front; responds JSON `{"ok":true}`. Also `GET /api/codemap/ping` -> `{"ok":true,"plugin":"codemap"}`.
  Only accept requests from localhost.
* Editor tab "Code Map" (FileEditorProvider over a LightVirtualFile; action "Open Code Map"): if `JBCefApp.isSupported()`, embed the web UI (URL from setting, default `http://127.0.0.1:8765/`) in a
  JBCefBrowser with a JS→Java bridge (JBCefJSQuery) so Ctrl+click opens files directly; otherwise show a panel with an
  "Open Code Map in browser" button (BrowserUtil.browse, which Remote Dev forwards to the client machine) and the URL.
* Editor/Project-view action "Show in Code Map" (Ctrl+Alt+Shift+M): finds the Python function/class at caret **without depending on
  the Python plugin API** (derive module from file path relative to content root + enclosing `def`/`class` names by scanning
  indentation in the document text) and opens `http://127.0.0.1:8765/#focus=<module>:<qualname>` (in the Code Map editor tab if JCEF, else browser).
* plugin.xml: id `com.sushil.codemap` (was `dev.codemap.pycharm` before v0.4), vendor `Sushil`, `<depends>com.intellij.modules.platform</depends>`, since-build 253, no until-build.
* Build script `pycharm-plugin/build.sh` compiles with `<dist>/jbr/bin/javac --release 21 -cp "<dist>/lib/*:<dist>/plugins/..."`,
  packages jar, and `install.sh` copies it to the plugins dir. User restarts the IDE backend afterwards.

## Bundled mode (plugin ships and runs the server itself) — v0.4

Goal: someone installs only the plugin from the Marketplace, presses **Open Code Map**, and gets the map of their
project. No clone, no service, no manual Python setup, no LLM, offline.

### Packaging
* The plugin jar contains the Python package and web UI as resources under `/codemap-server/`:
  `codemap-server/codemap/{__init__,__main__,analyzer,server}.py`, `codemap-server/codemap/web/**` (incl. vendor/elk + its LICENSE),
  and `codemap-server/VERSION` (plugin version + short content hash so a changed server re-extracts). No `__pycache__`, no tests.
* Built with **Gradle + IntelliJ Platform Gradle Plugin 2.x** (`pycharm-plugin/build.gradle.kts`, Gradle wrapper committed),
  which copies `../codemap` into resources at build time, runs `verifyPlugin` (JetBrains Plugin Verifier) and produces a
  Marketplace-ready ZIP via `buildPlugin`. `signPlugin`/`publishPlugin` configured from env vars (CERTIFICATE_CHAIN, PRIVATE_KEY,
  PRIVATE_KEY_PASSWORD, PUBLISH_TOKEN) but never run by us. IDE dependency: a local IDE via `-PideLocalPath=<dir>` (default: the
  Remote Dev dist on this machine if it exists) else download PyCharm 2025.3. `pycharm-plugin/build.sh` stays as the quick no-Gradle dev
  build and must bundle the same resources.

### Server CLI additions (codemap/__main__.py, codemap/server.py)
* `--port 0` = pick a free port. After binding, print to **stdout** exactly one machine-readable line, flushed:
  `CODEMAP_READY port=<n> url=http://127.0.0.1:<n>/` (other logs go to stderr).
* `--exit-on-stdin-eof`: a daemon thread reads stdin; on EOF the server shuts down cleanly (orphan protection when the IDE dies).
  Must work when nobody reads stderr any more (IDE killed): logging never raises; exits within ~1 s even mid-analysis.
* `--ide-port <n>`: the IDE built-in server port of the owning IDE; `/api/open` tries it first (before probing 63342–63352).
* Must run on **Python 3.10+** on Linux/macOS/Windows, stdlib only (no 3.11+ APIs/syntax; paths via pathlib; no fork-only APIs).

### Plugin: managed server (Java, package com.sushil.codemap)
* `CodeMapServerManager` — application-level service (Disposable). `CompletableFuture<String> baseUrl(Project)`:
  - Mode `managed` (default): if a live managed process exists return its URL; else extract resources (if VERSION differs) to
    `PathManager.getSystemPath()/codemap-server/<VERSION>/`, find Python, start
    `<python> -B -m codemap --host 127.0.0.1 --port 0 --exit-on-stdin-eof --ide-port <BuiltInServerManager port>`
    with working dir = extracted dir (so `-m codemap` resolves) and `PYTHONPATH` = extracted dir, `PYTHONUNBUFFERED=1`;
    parse `CODEMAP_READY` from stdout (timeout 20 s); forward stderr to idea.log (Logger). One process per IDE, shared by all projects.
    Restart automatically on next request if it died. Destroy on dispose (IDE exit).
  - Mode `external`: use the configured URL as-is (power users / the old systemd service).
  - Never runs on the EDT.
* Python discovery (first that reports version >= 3.10 via `-c "import sys;print('%d.%d'%sys.version_info[:2])"`, 5 s timeout):
  settings override path → project SDK home (`ProjectRootManager.getProjectSdk().getHomePath()`) → module SDKs →
  PATH: `python3`, `python`, `python3.14`…`python3.10` (if found), on Windows `py -3`; on macOS also Homebrew
  (`/opt/homebrew/bin`, `/usr/local/bin`) and python.org framework installs, skipping `/usr/bin/python3` when the
  Command Line Tools are missing (`xcode-select -p` fails: it is the install-prompt stub). Version = last non-empty
  stdout line, anchored. Without a project (Settings › Restart) the last tab's project, then all open projects, give the
  SDKs. Uses only platform APIs (no Python-plugin dependency). Extraction is serialised (static lock + `.lock` file).
  Failure → clear message listing what was tried.
* Tab UX: the Code Map tab opens immediately with a "Starting Code Map…" page (JCEF `loadHTML`), then navigates to
  `<base>open?root=<project dir>[#focus=…]` when ready. On failure shows an error page with the reason, what was tried, and a
  Retry button + link to Settings. URLs from actions are built against the base URL once known.
* Settings (Tools | Code Map): Server mode [Bundled (automatic) | External URL] + URL field, Python interpreter override (optional,
  blank = auto), read-only status line (running on port N with python X / not running), "Restart server" button.
* Plugin id `com.sushil.codemap`, vendor `Sushil` (final; Java package, action/configurable ids and PropertiesComponent keys
  all use the `com.sushil.codemap` prefix; old `dev.codemap.pycharm.*` settings are not migrated). The IDE built-in server
  endpoints stay `/api/codemap/ping` and `/api/codemap/open` (the Python server calls them).

## v0.5: analysis in Java on PyCharm's Python model (supersedes "Bundled mode")
Plan: PLAN-psi.md. Graph JSON format: unchanged (sections above). Python package `codemap/` stays as the standalone
CLI **and the benchmark**; it is no longer bundled. Plugin depends on `com.intellij.modules.python`
(Gradle `bundledPlugin("PythonCore")`); the web UI `codemap/web/**` is bundled at `/codemap-web/` in the jar.

### Contracts (Java, package `com.sushil.codemap.analysis`; stubs exist and compile)
* `FileResult`: per-file data (relPath, module, nodes, edges, externalCalls, dependsOnModules, errors, facts).
* `FileAnalyzer.analyze(PyFile, relPath)`: runs inside a non-blocking read action in smart mode, calls
  `ProgressManager.checkCanceled()`, keeps no PSI in the result. Impl `PsiFileAnalyzer`.
* `GraphAssembler.assembleJson(files, root, project, version, buildMs)`: pure, cross-file passes, deterministic,
  gis_api < 200 ms. Impl `DefaultGraphAssembler`.
* `CodeGraphService` (project service): `getInstance`, `ensureStarted`, `snapshot()` -> `GraphSnapshot(version, json, changedFiles)`,
  `status()` -> `GraphStatus(state idle|waiting_for_index|building|ready|error, done, total, message)`, `requestRebuild`,
  `addListener(Listener, Disposable)` (called on a background thread after each publish).
* `CodeMapSettings.excludeGlobs()`: extra exclude globs for analysis (default empty).

### Web layer (built-in server, no extra process/port)
* `HttpRequestHandler` under `/codemap/`: `/codemap/` = project list page; `/codemap/<id>/...` = bundled UI files;
  `<id>` = `<dirname>-<sha1(project basePath)[:8]>` (same scheme as server.py `project_id` on Linux).
* Per project: `api/graph` (gzip if accepted), `api/status` (`{version, root, state, done, total, message, ready, errors}`),
  `api/source?file&start&end` (from the editor Document so unsaved edits show; confined to the project root),
  `POST api/open {file,line}` (navigate directly), `api/events` (SSE `event: graph` / `data: {"version","changed"}` + heartbeat;
  UI falls back to polling `api/status` every 2 s if the stream fails). Loopback clients + loopback Host only.
* Tab URL: `http://127.0.0.1:<BuiltInServerManager port>/codemap/<id>/[#focus=...]`.
* UI: when the graph is empty and state != ready, show "Waiting for PyCharm indexing…" / "Analysing N / M files…" and refresh on events.

### Benchmark / acceptance
* Corpora: gis_api, and site-packages copies of fastapi, celery, sqlalchemy
  (`/home/sushil/anaconda3/envs/gis_api/lib/python3.13/site-packages/<pkg>`). Golden graphs from the Python analyzer
  in `bench/golden/<name>.json`; `tools/graphdiff.py A.json B.json` compares.
* Headless export (`codemap-export <project dir> <out.json>`, an `ApplicationStarter`, run through a Gradle `runIde`-type task
  with its own sandbox) always runs on **copies** of the corpora under the scratchpad (never open gis_api itself: opening writes `.idea/`).
* Gates: every internal edge (source+target+kind) in the golden graph is present or listed+explained in `bench/DIFFERENCES.md`;
  same entrypoint set and labels; resolved-call count >= golden; perf targets from PLAN-psi.md; no EDT work.
