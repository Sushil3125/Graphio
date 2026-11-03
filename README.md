# codemap

A local, live **left-to-right call-flow explorer** for Python projects. It works like an ERD or flow chart, not a force-directed hairball.

* Static analysis only (pure `ast`). It never imports your code. Stdlib only, Python ≥ 3.10, no `pip install`.
* Finds entrypoints: FastAPI/Starlette routes (with router prefixes), websockets, Celery tasks, `__main__` blocks,
  app factories and click/typer commands.
* Pick an entrypoint or search for any function, method or class. Its callers flow in from the left and its callees
  flow out to the right, with `file:line` on every card. Double-click a node to drill in, use Back/Forward, find the path from A to B, and open the source preview.
* **Ctrl/Cmd+click opens the function in PyCharm** through the Code Map plugin, falling back to the `pycharm`/`charm` CLI.
* **In PyCharm itself** the plugin (0.5+) builds the same map from PyCharm's code model, with no Python needed (see below).
* **Live**: a watcher polls `*.py` mtimes every second. A change triggers a rebuild (debounced 300 ms), and the browser
  updates over Server-Sent Events while keeping your current focus.

See `SPEC.md` for the graph JSON schema and the HTTP API.

## Quick start

```bash
cd ~/PycharmProjects/codemap
python3 -m codemap                                       # multi-project server: http://127.0.0.1:8765/
python3 -m codemap ~/PycharmProjects/gis_api            # same, plus gis_api as the default project shown at /
```

### One map per project

A single server hosts a separate map for every project, each with its own graph and file watcher:

* `GET /open?root=<abs project dir>` loads that project on first use and redirects to `/p/<name>-<hash>/`,
  which is that project's own UI and API, so two projects never show each other's code. (The PyCharm plugin 0.5
  serves its own per-project maps under `/codemap/<id>/` on the IDE port, with the same `<name>-<hash>` ids.)
* `/projects` (or `/` when no default project is set) lists the loaded projects. `GET /api/projects` returns the same list as JSON.
* A project with no page open for 30 minutes is unloaded (stops watching). Opening it again reloads it.
* Only loopback `Host` headers are accepted (DNS-rebinding guard), and `/`, the home directory and missing paths are refused as roots.

Options:

| flag | default | meaning |
|---|---|---|
| `--port N` | `8765` (env `CODEMAP_PORT`) | HTTP port |
| `--host H` | `127.0.0.1` (env `CODEMAP_HOST`) | bind address. WSL2 forwards `localhost` to Windows, so the Windows browser can use `http://localhost:8765/` |
| `--exclude GLOB` | – | extra excludes (repeatable), e.g. `--exclude 'migrations/*' --exclude 'tests/*'` |
| `--no-watch` | off | build once and don't watch for changes |
| `--port 0` | – | pick any free port (the chosen port is reported on stdout, see below) |
| `--exit-on-stdin-eof` | off | shut down cleanly when stdin is closed (used by plugin 0.4's bundled mode so the server died with the IDE) |
| `--ide-port N` | – | built-in server port of the owning IDE; tried first for "open in editor", before probing 63342–63352 |

If the port is already taken, codemap exits with a clear message (usually because another instance is already running).

stdout carries exactly one machine-readable line, printed (and flushed) as soon as the socket is bound; all logs go to stderr:

```
CODEMAP_READY port=<n> url=http://127.0.0.1:<n>/
```

Plugin 0.4 started it this way (bundled mode, removed in 0.5): `python -B -m codemap --host 127.0.0.1 --port 0 --exit-on-stdin-eof --ide-port <p>`.
The server runs on CPython 3.10+ on Linux, macOS and Windows (stdlib only).

### HTTP API (summary)

Per project, under `/p/<id>/` (or at `/` for the default project): `GET /api/graph` (gzip), `GET /api/events` (SSE: `event: graph` + `data: {"version", "changed"}`, a heartbeat every 15 s),
`GET /api/source?file=&start=&end=` (paths are confined to the project root), `POST /api/open {"file","line"}`,
`GET /api/status`, `POST /api/rebuild` (forces a rebuild).

## PyCharm integration (plugin 0.5+: no Python needed)

`pycharm-plugin/` contains the Code Map plugin (id `com.sushil.codemap`, PyCharm 2025.3+). Since 0.5 it **builds the
graph itself in Java from PyCharm's own Python code model** (PSI, resolve, type inference). It needs no Python
interpreter, starts no process and opens no port: the same web UI (`codemap/web`, bundled in the jar) is served by
the IDE's built-in server at `http://127.0.0.1:<IDE port>/codemap/<project id>/`.

* **Open Code Map** (Ctrl+Alt+Shift+K, Tools/View menu) shows the current project's map in an editor tab (JCEF, or
  the system browser without JCEF). While PyCharm indexes or the first build runs, the page shows its progress.
* **Show in Code Map** (Ctrl+Alt+Shift+M) focuses the graph on the function/class at the caret.
* Updates are live (unsaved edits included); Ctrl+click opens the code; Settings | Tools | Code Map has exclude
  globs and "Include test code".

Build with `cd pycharm-plugin && ./gradlew buildPlugin`, install with `pycharm-plugin/install.sh` (installs the ZIP
from `build/distributions`), then restart the PyCharm (Remote Dev) backend. Details: `pycharm-plugin/README.md`.

### This Python package: standalone CLI, server and benchmark

The Python package is no longer bundled into the plugin. It remains:

* a **standalone server/CLI** (everything above): useful without PyCharm, in a browser, or as the always-on service
  below. Its Ctrl+click still opens files in PyCharm through the plugin's
  `GET http://127.0.0.1:63342/api/codemap/open?file=<abs>&line=N` (codemap probes ports 63342–63352 for
  `/api/codemap/ping` and caches the port that answers). Without the plugin, "open" falls back to
  `pycharm --line N file` or `charm` if either is on `PATH`; otherwise the UI shows a toast with `file:line`.
* the **benchmark** for the plugin's Java analyzer: its output on reference codebases is frozen in `bench/golden/` and
  compared with `tools/graphdiff.py`.

## Run it always-on (user service)

Settings live in `~/.config/codemap/env`, which both methods below share:

```bash
CODEMAP_HOME=/home/sushil/PycharmProjects/codemap
CODEMAP_ROOT=            # optional default project for "/"; leave empty for the project list
CODEMAP_PORT=8765
CODEMAP_HOST=127.0.0.1
CODEMAP_PYTHON=/home/sushil/anaconda3/envs/gis_api/bin/python3
CODEMAP_ARGS=--exclude migrations/*
```

`scripts/install-service.sh [PROJECT_ROOT] [PORT]` writes that file (unless it already exists) and then picks a method:

### A) systemd (Linux, or WSL with systemd enabled)

Check with `ps -p 1 -o comm=`. If it prints `systemd`, you can use a user unit.
(To enable systemd on WSL, put `[boot]\nsystemd=true` in `/etc/wsl.conf` and run `wsl --shutdown` from Windows.)

```bash
scripts/install-service.sh                      # copies scripts/codemap.service to ~/.config/systemd/user/, enable --now
# or manually:
mkdir -p ~/.config/systemd/user && cp scripts/codemap.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now codemap
loginctl enable-linger "$USER"                  # keep it running without an open login session

systemctl --user status codemap                 # status
journalctl --user -u codemap -f                 # logs
systemctl --user restart codemap                # after editing ~/.config/codemap/env
```

Note: WSL itself shuts down when no terminal or process is using it for a while. Once WSL starts again, systemd starts codemap with it.

### B) No systemd: nohup + pidfile

```bash
scripts/start-codemap.sh start | stop | restart | status | logs
```

The pidfile and log are in `~/.local/state/codemap/`. To start it automatically, add this line to `~/.bashrc`:

```bash
~/PycharmProjects/codemap/scripts/start-codemap.sh start >/dev/null 2>&1
```

`start` does nothing if codemap is already running.

## Tests

```bash
python3 -m unittest discover -s tests -v
```

## Limitations

* Static resolution is best-effort. Dynamic dispatch, `getattr`, monkeypatching and most calls on untyped
  variables can't be resolved. Those calls appear as "external/unresolved" calls in the details panel.
* Each change triggers a full rebuild. This is cheap for a few hundred files (about 1 s), but large monorepos should use `--exclude`.
* Polling means a change can take up to about 1.3 s to show up.
