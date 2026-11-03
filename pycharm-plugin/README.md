# Code Map PyCharm plugin

Shows a live, left-to-right call-flow graph of the open Python project in an editor tab and jumps from the graph to
the code. Since 0.5 the plugin **builds the graph itself from PyCharm's own Python code model** (PSI, resolve, type
inference). **No Python interpreter, no extra process and no extra port are needed**: the map is served by the IDE's
built-in server. Targets PyCharm 2025.3+ (build 253+); depends on `com.intellij.modules.python`.

The Python package in `../codemap` is no longer bundled. It stays as a standalone CLI/server (`python -m codemap`)
and as the **benchmark** the Java analyzer is checked against (`bench/`, `tools/graphdiff.py`).

## What it adds
* **Code Map editor tab** (Open Code Map: Ctrl+Alt+Shift+K, Tools/View menu). It loads
  `http://127.0.0.1:<built-in server port>/codemap/<project id>/` directly. While PyCharm indexes or the first build
  runs, the page itself shows "Waiting for PyCharm to finish indexing…" / "Analysing N / M files…" and opens the map
  when it is ready. JCEF is used when available, otherwise the same URL opens in the system browser.
* **Show in Code Map** (Ctrl+Alt+Shift+M, editor/project-view context menus, Tools menu) focuses the graph on the
  def/class at the caret (`#focus=<module>:<qualname>`).
* **Live updates.** The engine (`analysis/CodeGraphService`) re-analyses changed files (unsaved edits included) and
  pushes `event: graph` over server-sent events; the page re-fetches the graph. If the stream fails, the page polls
  `api/status` every 2 s instead.
* **Ctrl+click** a node: inside the tab through the JS bridge `window.codemapIde` (only injected into Code Map pages
  of this IDE's built-in server), in an external browser through `POST api/open`.
* **Settings | Tools | Code Map**: exclude globs (one per line, relative to the project root) and "Include test
  code". Changing them rebuilds the open maps.

## Web endpoints (IDE built-in server, port 63342–63352, loopback clients and loopback Host only)
| Path | |
|---|---|
| `GET /codemap/` | list of open projects |
| `GET /codemap/<id>/...` | bundled web UI (jar resources `/codemap-web/**`) |
| `GET /codemap/<id>/api/graph` | graph JSON (SPEC format), gzip if accepted |
| `GET /codemap/<id>/api/status` | `{version, id, project, root, state, done, total, message, ready, errors, clients}`; `state` = `idle` / `waiting_for_index` / `building` / `ready` / `error` |
| `GET /codemap/<id>/api/source?file=<rel>&start=<n>&end=<n>` | lines from the editor document (unsaved edits show), confined to the project root |
| `POST /codemap/<id>/api/open` `{"file","line"}` | navigate in the IDE |
| `POST /codemap/<id>/api/rebuild` | full rebuild |
| `GET /codemap/<id>/api/events` | SSE: `event: hello`, `event: graph` / `data: {"version","changed"}`, `: heartbeat` every 15 s |
| `GET /api/codemap/ping`, `GET /api/codemap/open?file=<abs>&line=<n>` | kept for the standalone Python server's Ctrl+click |

`<id>` = `<dirname>-<first 8 hex of sha1(project base path)>`, the same as `project_id` in `codemap/server.py`.
Netty I/O threads never block: graph encoding/gzip, document reads and navigation run on pooled threads.

## Build
IntelliJ Platform Gradle Plugin 2.19.0 on Gradle 9.8.2 (wrapper committed). It needs a JDK 21: on this machine
`gradle.properties` points the toolchain at PyCharm's JBR; elsewhere set `JAVA_HOME` to any JDK 21 (or let the
foojay resolver download one).

```bash
export JAVA_HOME=/home/sushil/.cache/JetBrains/RemoteDev/dist/c4a9ef17c7e8b_pycharm-253.29346.46/jbr
./gradlew buildPlugin    # -> build/distributions/codemap-pycharm-<version>.zip  (install from disk / upload)
./gradlew test           # headless IDE tests (engine, analyzer behaviour, web layer)
./gradlew verifyPlugin   # JetBrains Plugin Verifier against the same IDE
./gradlew runIde         # sandbox IDE with the plugin
./gradlew exportGraph -PexportProject=<COPY of a project> -PexportOut=<out.json>   # headless graph export
./install.sh             # newest ZIP -> ~/.local/share/JetBrains/PyCharm2025.3/codemap-pycharm/
```

* **IDE to compile/verify against.** `-PideLocalPath=<IDE dir>`; default: the local Remote Dev dist from
  `gradle.properties` if it exists, else PyCharm `platformVersion` (2025.3) is downloaded.
* **Separate output dirs** for parallel work: `./gradlew -Pcodemap.buildDir=build-foo …`; then
  `BUILD_DIR=build-foo ./install.sh`.
* **Web UI.** `processResources` copies `../codemap/web/**` into the jar under `codemap-web/`.
* **plugin.xml is the source of truth** for id (`com.sushil.codemap`), name, version, vendor (`Sushil`), description
  and change notes. Gradle only patches `<idea-version since-build="253">`, with no until-build.
* `install.sh` replaces `codemap-pycharm/` as a whole and removes any other plugin dir that contains a Code Map jar
  (id `com.sushil.codemap` or the old `dev.codemap.pycharm`). Restart the PyCharm backend afterwards.
* `exportGraph` opens the directory as a project, which writes `.idea/` into it: only point it at copies.

Publishing to JetBrains Marketplace is covered in [PUBLISHING.md](PUBLISHING.md).

## Layout
```
build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew*, gradle/wrapper/   Gradle build
install.sh                                                                             install the built ZIP
src/main/resources/META-INF/plugin.xml (+ pluginIcon*.svg), icons/
src/main/java/com/sushil/codemap/
  analysis/                      engine: CodeGraphService (project service), FileAnalyzer/PsiFileAnalyzer,
                                 GraphAssembler/DefaultGraphAssembler, FileResult, GraphSnapshot, GraphStatus
  export/ExportStarter.java      headless "codemap-export" ApplicationStarter
  CodeMapHttpHandler.java        built-in server: /codemap/** (UI + API) and /api/codemap/{ping,open}
  CodeMapEvents.java             SSE streams over Netty (heartbeat, cleanup on close)
  CodeMapUrls.java               project id, tab URLs, "is this our page" check
  IdeNavigator.java              EDT navigation to file:line
  CodeMapTabs.java, CodeMapEditorProvider.java, CodeMapFileEditor.java, CodeMapVirtualFile.java, CodeMapFileType.java
  CodeMapPanel.java              JCEF browser, error page, JS bridge
  OpenCodeMapAction.java, ShowInCodeMapAction.java, PythonLocator.java
  CodeMapSettings.java, CodeMapConfigurable.java, CodeMapIcons.java, Json.java
src/test/java/com/sushil/codemap/  WebLayerTest (built-in server, SSE), analysis/* (engine, analyzer)
```

## Limitations
* Qualname detection for "Show in Code Map" works on the editor text, so unusual formatting can fool it.
* `api/open` waits up to 4 s for the EDT. If a modal dialog is open, it answers `{"ok":true,"pending":true}` and
  navigates once the dialog closes.
* Code Map claims the `/codemap/` prefix on the built-in server (handler `order="first"`), so the built-in web
  server cannot serve the files of a project that is literally named `codemap` under `/codemap/…`.
* In a Remote Development backend there is no JCEF; the URL opens in a browser, which must be able to reach the
  backend's built-in server port (forward it if needed).
