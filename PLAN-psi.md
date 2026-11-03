# Plan: move analysis into PyCharm's own code model (v0.5)

Goal: the plugin builds the call graph itself from PyCharm's Python model.
No Python interpreter, no bundled server process, no extra port. The web UI,
the left-to-right layout and the graph JSON format (SPEC.md) stay exactly the
same; only the part that produces the graph is replaced.

The existing Python analyzer (`codemap/analyzer.py`) stays in the repo as the
standalone CLI and as the **benchmark**: the new analyzer must match it before
it replaces it.

Available in PyCharm 2025.3 (`PythonCore` plugin, module `com.intellij.modules.python`):
`PyFile`, `PyCallExpression`, `PyResolveContext`, `TypeEvalContext`,
`PyClassNameIndex`, `PyClassInheritorsSearch`, `PyOverridingMethodsSearch`,
plus platform `PsiTreeChangeListener` / `PsiModificationTracker`.

## Phase 0: benchmark (parallel with 1)
* Freeze the Python analyzer's output for 4 codebases as golden files:
  gis_api, FastAPI, Celery, SQLAlchemy (site-packages copies, read-only).
* `tools/graphdiff.py`: compares two graph JSONs and reports
  missing/extra nodes (by id), edges (by source, target, kind) and entrypoints,
  plus summary counts. Used for every acceptance check below.

## Phase 1: plugin skeleton and serving (parallel with 0)
* Plugin depends on `com.intellij.modules.python`; Gradle adds `bundledPlugin("PythonCore")`.
* Serve the web UI from the plugin jar via the IDE's built-in server:
  `http://127.0.0.1:<ide port>/codemap/<project-id>/`. Relative URLs in the UI keep working.
* Endpoints, as in SPEC: `api/graph`, `api/status`, `api/source` (read from the editor
  document, so unsaved edits show), `api/open` (navigate directly, no extra hop),
  `api/events` (SSE; the UI falls back to polling `api/status` if streaming fails).
* Inside the tab, updates are also pushed straight to the page through the JS bridge.
* Remove the bundled Python server, Python discovery and the server settings
  (mode, URL, interpreter). Settings keep only analysis options (excludes, test code on/off).

## Phase 2: analyzer port (Java, after the skeleton compiles)
* **Files:** project content only (`ProjectFileIndex.isInContent`, skip excluded
  folders, venvs, libraries).
* **Nodes:** module, class, function, method, nested. Ids are exactly
  `<module>:<qualname>`, same fields as SPEC.
* **Calls:** `PyCallExpression.multiResolveCallee` with a code-analysis
  `TypeEvalContext`. Targets inside the project become edges; library targets go to
  `external_calls` with their real qualified names (more precise than today).
* **Other edge kinds:**
  * `depends`: FastAPI `Depends` / `Security`.
  * `instantiates`: class calls.
  * `inherits`: `PyClass.getSuperClasses`.
  * `implements`: overriding methods search.
  * `references`: callbacks.
  * `routes` / `includes_router`.
* **Entrypoints:** same rules as analyzer.py (HTTP/WebSocket routes with resolved
  prefixes, Celery tasks, `__main__`, app factories, CLI commands, framework hooks).
  Router prefixes are resolved through PSI instead of text matching.

## Phase 3: incremental engine
* Per-file cache: file → its nodes and outgoing edges, keyed by modification stamp.
* On change (PSI listener, debounced 500 ms): re-analyse the changed files plus the
  files that import them; merge into the cached graph; bump version; push the event.
* All work in `ReadAction.nonBlocking(...).inSmartMode(project)`: cancelled
  whenever you type, waits for indexing ("Waiting for indexing…" in the tab),
  never runs on the UI thread.
* First build shows progress (files done / total) in the tab and the status bar.

## Phase 4: parity and tests
* Port the Python analyzer's fixture tests to the IDE test framework
  (light Python fixtures, Gradle `test`).
* Headless export command, runnable with the local PyCharm:
  `codemap-export <project dir> <out.json>`. Run it on the 4 codebases and diff
  against the golden files. Repeat fix → diff until clean.
* **Acceptance:**
  * Every edge between project functions that the Python analyzer finds is also
    found, or the difference is listed and explained (e.g. the Python version was wrong).
  * Same set of entrypoints with the same labels.
  * More calls resolved (fewer external/unresolved), reported as numbers.
  * Speed: gis_api first build under 5 s; 3,000 files under 60 s with progress;
    update after a save under 1 s.
  * No UI freezes: IDE freeze reports clean; no slow-operation warnings.

## Phase 5: release
* Version 0.5.0. Compatibility checker clean, docs and PUBLISHING.md updated,
  install, then you restart PyCharm and try it on gis_api and one other project.

## Risks and how they are handled
| Risk | Handling |
|---|---|
| IDE slowdown on big repos | cancellable background reads, one file at a time, nothing held between files |
| Python plugin interface changes | public interfaces only; compatibility checker each release |
| Interpreter not configured | project-internal links still resolve; only library names degrade |
| Behaviour drift vs. today | benchmark diff on 4 codebases is the release gate |
