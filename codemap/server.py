"""codemap HTTP server: static UI, graph API, live file watcher and SSE push.

Stdlib only (Python >= 3.10). See SPEC.md "Server HTTP API".
"""
from __future__ import annotations

import datetime as _dt
import gzip
import hashlib
import html
import json
import mimetypes
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable

WEB_DIR = Path(__file__).resolve().parent / "web"
PLUGIN_PORTS = range(63342, 63353)  # JetBrains built-in server port range
HEARTBEAT_SECONDS = 15.0
MAX_SOURCE_LINES = 2000
IDLE_SECONDS = 30 * 60  # stop watching a project nobody looked at for this long
# Watcher back-off: a full tree scan may use at most ~1/WATCH_SCAN_FACTOR of a core (big repos: 3k files ~ 180 ms).
WATCH_SCAN_FACTOR = 20.0
WATCH_MAX_INTERVAL = 10.0
OPEN_WAIT_SECONDS = 1.5  # /open waits this long for the first build before showing the "Analysing…" page
_LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1", "[::1]"}

_CONTENT_TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".ico": "image/x-icon",
    ".map": "application/json; charset=utf-8",
    ".md": "text/markdown; charset=utf-8",
    ".txt": "text/plain; charset=utf-8",
    ".woff2": "font/woff2",
}


def _default_build_graph(root: str, excludes: list[str] | None = None) -> dict:
    from codemap.analyzer import build_graph

    return build_graph(root, excludes)


def _default_iter_files(root: str, excludes: list[str] | None = None) -> list[Path]:
    from codemap.analyzer import iter_python_files

    return iter_python_files(root, excludes)


def _log(msg: str) -> None:
    """Log to stderr. Never raises: when the owning IDE dies its stderr reader is gone (EPIPE / closed handle)
    and a failing log call must not break shutdown or a request thread."""
    ts = time.strftime("%H:%M:%S")
    try:
        print(f"[codemap {ts}] {msg}", file=sys.stderr, flush=True)
    except (OSError, ValueError, AttributeError):
        pass


# --------------------------------------------------------------------------- state


class CodemapApp:
    """Holds the current graph, rebuilds it on file changes and fans out SSE events."""

    def __init__(
        self,
        root: str | os.PathLike,
        excludes: list[str] | None = None,
        *,
        watch: bool = True,
        poll_interval: float = 1.0,
        debounce: float = 0.3,
        heartbeat: float = HEARTBEAT_SECONDS,
        build_graph: Callable[..., dict] | None = None,
        iter_files: Callable[..., list] | None = None,
        plugin_ports: range | list[int] = PLUGIN_PORTS,
        ide_port: int | None = None,
    ) -> None:
        self.root = Path(root).resolve()
        self.excludes = list(excludes or [])
        self.watch = watch
        self.poll_interval = poll_interval
        self.debounce = debounce
        self.heartbeat = heartbeat
        self._build_graph = build_graph or _default_build_graph
        self._iter_files = iter_files or _default_iter_files
        self.plugin_ports = list(plugin_ports)
        self.ide_port = ide_port or None  # owning IDE's built-in server port: tried before the probe range

        self.version = 0
        self.last_build_ms = 0
        self._graph: dict = {}
        self._graph_json = b"{}"
        self._graph_gz = gzip.compress(self._graph_json)
        self._lock = threading.Lock()
        self._build_lock = threading.Lock()
        self._clients: set[queue.Queue] = set()
        self._clients_lock = threading.Lock()
        self.stop_event = threading.Event()
        self._watcher: threading.Thread | None = None
        self._snapshot: dict[str, tuple[int, int]] = {}
        self._plugin_port: int | None = None
        self.id = project_id(self.root)
        self._start_lock = threading.Lock()
        self._started = False
        self._ready = threading.Event()  # set once the first graph has been built (see start())
        self._starter: threading.Thread | None = None

    @property
    def ready(self) -> bool:
        """True once the first graph is available (always true for apps whose start() the caller manages)."""
        return self._ready.is_set() or self.version > 0 or (self._started and self._starter is None)

    def ensure_started(self, wait: float | None = None, block: bool = True) -> bool:
        """Start the first build + watcher in the background (idempotent, thread-safe).

        block=True waits for the first graph (at most ``wait`` seconds if given); returns ``self.ready``.
        """
        with self._start_lock:
            if not self._started:
                starter = threading.Thread(target=self._initial_start, name="codemap-initial-build", daemon=True)
                self._starter = starter  # before _started: `ready` must never see started-without-starter here
                self._started = True
                starter.start()
        if block and self._starter is not None:
            self._ready.wait(wait)
        return self.ready

    def _initial_start(self) -> None:
        try:
            self.start()
        except Exception as exc:  # never leave waiters hanging
            _log(f"initial build failed: {exc!r}")
            traceback.print_exc()
        finally:
            self._ready.set()

    def client_count(self) -> int:
        with self._clients_lock:
            return len(self._clients)

    # ---- graph ----
    def rebuild(self, changed: list[str] | None = None) -> None:
        with self._build_lock:
            t0 = time.perf_counter()
            try:
                graph = self._build_graph(str(self.root), self.excludes)
            except Exception as exc:  # never kill the server on an analyzer bug
                _log(f"analyzer failed: {exc!r}")
                traceback.print_exc()
                with self._lock:
                    if self._graph:
                        return  # keep serving the previous graph
                graph = {
                    "root": str(self.root),
                    "project": self.root.name,
                    "nodes": [],
                    "edges": [],
                    "external_calls": {},
                    "errors": [{"file": "", "message": f"analyzer crashed: {exc!r}"}],
                    "stats": {},
                }
            ms = int((time.perf_counter() - t0) * 1000)
            graph = dict(graph)
            graph.setdefault("root", str(self.root))
            graph.setdefault("project", self.root.name)
            graph.setdefault(
                "generated_at",
                _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            )
            with self._lock:
                version = self.version + 1
                graph["version"] = version
                data = json.dumps(graph, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
                gz = gzip.compress(data, compresslevel=5)
                self._graph, self._graph_json, self._graph_gz = graph, data, gz
                self.version = version
                self.last_build_ms = ms
        _log(
            f"graph v{version}: {len(graph.get('nodes', []))} nodes, "
            f"{len(graph.get('edges', []))} edges in {ms} ms"
            + (f" ({len(changed)} changed file(s))" if changed else "")
        )
        self.broadcast("graph", {"version": version, "changed": sorted(changed or [])})

    def graph_payload(self, want_gzip: bool) -> tuple[bytes, bool]:
        with self._lock:
            return (self._graph_gz, True) if want_gzip else (self._graph_json, False)

    def status(self) -> dict:
        with self._lock:
            errors = len(self._graph.get("errors", []) or [])
            return {
                "version": self.version,
                "id": self.id,
                "root": str(self.root),
                "watching": bool(self.watch and self._watcher and self._watcher.is_alive()),
                "last_build_ms": self.last_build_ms,
                "errors": errors,
                "clients": len(self._clients),
                "ready": self.ready,
            }

    # ---- SSE fan-out ----
    def subscribe(self) -> queue.Queue:
        q: queue.Queue = queue.Queue(maxsize=100)
        with self._clients_lock:
            self._clients.add(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._clients_lock:
            self._clients.discard(q)

    def broadcast(self, event: str, data: dict) -> None:
        msg = f"event: {event}\ndata: {json.dumps(data, separators=(',', ':'))}\n\n".encode("utf-8")
        with self._clients_lock:
            clients = list(self._clients)
        for q in clients:
            try:
                q.put_nowait(msg)
            except queue.Full:  # slow/dead client: drop it, it will reconnect
                self.unsubscribe(q)

    # ---- watcher ----
    def _scan(self) -> dict[str, tuple[int, int]]:
        snap: dict[str, tuple[int, int]] = {}
        try:
            files = self._iter_files(str(self.root), self.excludes)
        except Exception as exc:
            msg = f"file scan failed: {exc!r}"
            if msg != getattr(self, "_last_scan_error", None):
                _log(msg)
                self._last_scan_error = msg
            return self._snapshot
        self._last_scan_error = None
        for p in files:
            p = Path(p)
            if not p.is_absolute():
                p = self.root / p
            try:
                st = p.stat()
            except OSError:
                continue
            try:
                rel = p.resolve().relative_to(self.root).as_posix()
            except ValueError:
                rel = p.as_posix()
            snap[rel] = (st.st_mtime_ns, st.st_size)
        return snap

    @staticmethod
    def _diff(old: dict, new: dict) -> list[str]:
        changed = [k for k, v in new.items() if old.get(k) != v]
        changed += [k for k in old if k not in new]
        return sorted(set(changed))

    def _next_interval(self, scan_seconds: float) -> float:
        """Poll interval after a scan that took scan_seconds: back off on big trees (cap WATCH_MAX_INTERVAL)."""
        return max(self.poll_interval, min(scan_seconds * WATCH_SCAN_FACTOR, WATCH_MAX_INTERVAL))

    def _watch_loop(self) -> None:
        interval = self.poll_interval
        while not self.stop_event.wait(interval):
            try:
                t0 = time.perf_counter()
                new = self._scan()
                interval = self._next_interval(time.perf_counter() - t0)
                changed = self._diff(self._snapshot, new)
                if not changed:
                    continue
                # debounce: wait until the tree is quiet for `debounce` seconds
                pending = set(changed)
                while True:
                    if self.stop_event.wait(self.debounce):
                        return
                    newer = self._scan()
                    more = self._diff(new, newer)
                    new = newer
                    if not more:
                        break
                    pending.update(more)
                self._snapshot = new
                self.rebuild(sorted(pending))
            except Exception as exc:
                _log(f"watcher error: {exc!r}")
                traceback.print_exc()

    def start(self) -> None:
        try:
            self._snapshot = self._scan()
            self.rebuild()
            if self.watch and not self.stop_event.is_set():
                self._watcher = threading.Thread(target=self._watch_loop, name="codemap-watcher", daemon=True)
                self._watcher.start()
        finally:
            self._ready.set()  # also on failure: waiters must never hang

    def stop(self) -> None:
        self.stop_event.set()
        # wake SSE handlers so they exit promptly
        self.broadcast("bye", {})

    # ---- paths / source ----
    def safe_path(self, rel: str) -> Path | None:
        if not rel or "\x00" in rel:
            return None
        p = Path(rel)
        candidate = (p if p.is_absolute() else self.root / p).resolve()
        try:
            candidate.relative_to(self.root)
        except ValueError:
            return None
        return candidate

    # ---- open in PyCharm ----
    def _plugin_get(self, port: int, path: str, timeout: float) -> dict | None:
        url = f"http://127.0.0.1:{port}{path}"
        req = urllib.request.Request(url, headers={"Origin": "http://127.0.0.1", "User-Agent": "codemap"})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        try:
            with opener.open(req, timeout=timeout) as resp:
                body = resp.read(65536)
            return json.loads(body.decode("utf-8", "replace"))
        except (OSError, ValueError, urllib.error.URLError):
            return None

    def _candidate_ports(self) -> list[int]:
        ports = [self.ide_port] if self.ide_port else []
        return ports + [p for p in self.plugin_ports if p != self.ide_port]

    def _find_plugin_port(self) -> int | None:
        if self._plugin_port is not None:
            return self._plugin_port
        for port in self._candidate_ports():
            info = self._plugin_get(port, "/api/codemap/ping", timeout=0.4)
            if isinstance(info, dict) and info.get("ok") and info.get("plugin") == "codemap":
                self._plugin_port = port
                return port
        return None

    def open_in_ide(self, rel: str, line: int) -> dict:
        path = self.safe_path(rel)
        if path is None or not path.is_file():
            return {"ok": False, "error": f"file not found: {rel}"}
        line = max(1, int(line or 1))
        qs = urllib.parse.urlencode({"file": str(path), "line": line})

        for attempt in range(2):
            port = self._find_plugin_port()
            if port is None:
                break
            res = self._plugin_get(port, f"/api/codemap/open?{qs}", timeout=6.0)  # plugin waits <=4 s for EDT
            if isinstance(res, dict) and res.get("ok"):
                return {"ok": True, "via": "plugin", "port": port}
            self._plugin_port = None  # cached port went stale (IDE restarted?) -> re-probe once

        skipped = []
        for exe in ("pycharm", "charm", "pycharm.sh", "pycharm64"):
            cmd = shutil.which(exe)
            if cmd and _is_windows_launcher(cmd):
                # Under WSL a Toolbox/Windows launcher would start a separate *Windows* PyCharm
                # that cannot open WSL paths (the Remote Dev backend is what we want) -> skip it.
                skipped.append(cmd)
                continue
            if cmd:
                try:
                    subprocess.Popen(
                        [cmd, "--line", str(line), str(path)],
                        stdin=subprocess.DEVNULL,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        **_detached_kwargs(),
                    )
                    return {"ok": True, "via": exe}
                except OSError as exc:
                    return {"ok": False, "error": f"{exe} failed: {exc}"}
        ports = (f"{self.ide_port}, " if self.ide_port else "") + (
            f"{self.plugin_ports[0]}-{self.plugin_ports[-1]}" if self.plugin_ports else "")
        return {
            "ok": False,
            "error": "PyCharm not reachable: codemap plugin not answering on ports "
            f"{ports} (install pycharm-plugin/install.sh and restart "
            "the IDE backend) and no usable pycharm/charm launcher on PATH"
            + (f" (skipped Windows launcher(s): {', '.join(skipped)})" if skipped else ""),
        }


def _detached_kwargs() -> dict:
    """Popen kwargs that detach a GUI launcher from this process (POSIX: new session; Windows: new group)."""
    if os.name == "nt":
        flags = getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0) | getattr(subprocess, "DETACHED_PROCESS", 0)
        return {"creationflags": flags}
    return {"start_new_session": True}


def _is_windows_launcher(cmd: str) -> bool:
    """True for launchers living on a WSL-mounted Windows drive (e.g. /mnt/c/.../Toolbox/scripts/pycharm).

    Only meaningful under Linux/WSL; on Windows/macOS a launcher is never "foreign".
    """
    if not sys.platform.startswith("linux"):
        return False
    try:
        real = os.path.realpath(cmd)
    except OSError:
        real = cmd
    return any(re.match(r"^/mnt/[a-zA-Z]/", c) for c in (cmd, real))


# --------------------------------------------------------------------------- projects


def project_id(root: Path) -> str:
    """Stable, URL-safe id for a project root: '<dirname>-<hash8>'.

    The hash uses os.path.normcase so C:\\Proj and c:\\proj map to one project on Windows (no-op on POSIX).
    """
    slug = re.sub(r"[^A-Za-z0-9_.-]+", "-", root.name).strip("-.") or "project"
    key = os.path.normcase(str(root))
    return f"{slug}-{hashlib.sha1(key.encode('utf-8')).hexdigest()[:8]}"


class ProjectRegistry:
    """One CodemapApp (graph + watcher) per project root, created on first use, stopped when idle."""

    def __init__(self, factory: Callable[[Path], CodemapApp], idle_seconds: float = IDLE_SECONDS) -> None:
        self._factory = factory
        self.idle_seconds = idle_seconds
        self._apps: dict[str, CodemapApp] = {}
        self._last_used: dict[str, float] = {}
        self._lock = threading.Lock()
        self.default_id: str | None = None
        self._stop = threading.Event()
        self._reaper: threading.Thread | None = None

    @staticmethod
    def validate_root(root: str | os.PathLike) -> Path:
        p = Path(root).expanduser()
        if not p.is_absolute():
            raise ValueError("project root must be an absolute path")
        p = p.resolve()
        if not p.is_dir():
            raise ValueError(f"not a directory: {p}")
        if p == Path(p.anchor) or p == Path.home().resolve():
            raise ValueError(f"refusing to analyse {p}: pick a project directory")
        return p

    def add(self, app: CodemapApp, default: bool = False) -> CodemapApp:
        with self._lock:
            self._apps[app.id] = app
            self._last_used[app.id] = time.monotonic()
            if default:
                self.default_id = app.id
        return app

    def get_or_create(self, root: str | os.PathLike, wait: float | None = None, block: bool = True) -> CodemapApp:
        """App for root, starting its first build. block=True waits for it (at most ``wait`` s if given)."""
        path = self.validate_root(root)
        pid = project_id(path)
        with self._lock:
            app = self._apps.get(pid)
            if app is None:
                app = self._factory(path)
                self._apps[pid] = app
                _log(f"project added: {path} -> /p/{pid}/")
            self._last_used[pid] = time.monotonic()
        app.ensure_started(wait, block)
        return app

    def get(self, pid: str | None, block: bool = True) -> CodemapApp | None:
        if pid is None:
            return None
        with self._lock:
            app = self._apps.get(pid)
            if app is not None:
                self._last_used[pid] = time.monotonic()
        if app is not None:
            app.ensure_started(block=block)
        return app

    @property
    def default(self) -> CodemapApp | None:
        return self.get(self.default_id)

    def list(self) -> list[dict]:
        with self._lock:
            apps = list(self._apps.values())
        return [{"id": a.id, "project": a.root.name, "root": str(a.root), "url": f"/p/{a.id}/",
                 "default": a.id == self.default_id, "version": a.version, "clients": a.client_count()}
                for a in sorted(apps, key=lambda a: a.root.name.lower())]

    def reap_idle(self, now: float | None = None) -> list[str]:
        """Stop and forget projects with no open page that were not used for idle_seconds."""
        now = time.monotonic() if now is None else now
        dropped = []
        with self._lock:
            for pid, app in list(self._apps.items()):
                if pid == self.default_id or app.client_count():
                    self._last_used[pid] = max(self._last_used.get(pid, now), now if app.client_count() else 0)
                    continue
                if now - self._last_used.get(pid, now) >= self.idle_seconds:
                    dropped.append(pid)
                    del self._apps[pid]
                    self._last_used.pop(pid, None)
                    app.stop()
        for pid in dropped:
            _log(f"project idle, stopped watching: /p/{pid}/")
        return dropped

    def start(self) -> None:
        def loop():
            while not self._stop.wait(60):
                try:
                    self.reap_idle()
                except Exception as exc:
                    _log(f"reaper error: {exc!r}")
        self._reaper = threading.Thread(target=loop, name="codemap-reaper", daemon=True)
        self._reaper.start()

    def stop(self) -> None:
        self._stop.set()
        with self._lock:
            apps = list(self._apps.values())
        for app in apps:
            app.stop()


# --------------------------------------------------------------------------- HTTP


class CodemapHandler(BaseHTTPRequestHandler):
    server_version = "codemap/1.0"
    protocol_version = "HTTP/1.1"
    registry: ProjectRegistry  # set on subclass by make_server
    app: CodemapApp | None = None  # resolved per request

    def log_message(self, fmt: str, *args) -> None:  # quiet by default
        if os.environ.get("CODEMAP_ACCESS_LOG"):
            super().log_message(fmt, *args)

    # ---- helpers ----
    def _send(self, status: int, body: bytes, ctype: str, extra: dict | None = None) -> None:
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _json(self, obj, status: int = 200) -> None:
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        if len(body) > 1024 and self._accepts_gzip():
            self._send(status, gzip.compress(body, 5), "application/json; charset=utf-8",
                       {"Content-Encoding": "gzip", "Vary": "Accept-Encoding"})
        else:
            self._send(status, body, "application/json; charset=utf-8")

    def _error(self, status: int, msg: str) -> None:
        self._json({"ok": False, "error": msg}, status)

    def _accepts_gzip(self) -> bool:
        return "gzip" in (self.headers.get("Accept-Encoding") or "").lower()

    def _redirect(self, location: str) -> None:
        self._send(302, b"", "text/plain; charset=utf-8", {"Location": location})

    def _host_ok(self) -> bool:
        """Block DNS-rebinding: on a loopback bind only accept loopback Host headers."""
        bound = self.server.server_address[0]
        if bound not in ("127.0.0.1", "::1", "localhost"):
            return True
        host = (self.headers.get("Host") or "").strip().lower()
        if not host:
            return True
        name = host.rsplit(":", 1)[0] if not host.endswith("]") else host
        return name in _LOOPBACK_HOSTS

    def _resolve(self, path: str, query: dict) -> str | None:
        """Pick the project app for this request; returns the path inside the project, or None if handled."""
        m = re.match(r"^/p/([A-Za-z0-9_.-]+)(/.*)?$", path)
        if m:
            # /api/status must answer while the first build runs (the "Analysing…" page polls it)
            app = self.registry.get(m.group(1), block=m.group(2) != "/api/status")
            if app is None:
                self._send(404, _unknown_project_page(m.group(1)), "text/html; charset=utf-8")
                return None
            if m.group(2) is None:
                self._redirect(f"/p/{app.id}/")
                return None
            self.app = app
            return m.group(2)
        app = self.registry.default
        if app is None:
            if path in ("/", "/index.html"):
                self._send(200, _projects_page(self.registry.list()), "text/html; charset=utf-8")
            else:
                self._error(404, "no default project; open a project via /open?root=<abs path>")
            return None
        self.app = app
        return path

    # ---- routing ----
    def do_HEAD(self) -> None:
        self.do_GET()

    def do_GET(self) -> None:
        try:
            if not self._host_ok():
                return self._error(403, "forbidden host")
            parsed = urllib.parse.urlsplit(self.path)
            path = parsed.path
            query = urllib.parse.parse_qs(parsed.query)
            if path == "/open":
                root = (query.get("root") or [""])[0]
                # Browsers (the IDE tab) get an "Analysing…" page if the first build of a big project takes longer
                # than OPEN_WAIT_SECONDS; API clients keep the plain blocking 302 of the SPEC.
                browser = "text/html" in (self.headers.get("Accept") or "")
                try:
                    app = self.registry.get_or_create(root, wait=OPEN_WAIT_SECONDS if browser else None)
                except ValueError as exc:
                    return self._error(400, str(exc))
                if not app.ready:  # big project: show progress instead of a silent multi-second request
                    return self._send(200, _analysing_page(app), "text/html; charset=utf-8",
                                      {"Cache-Control": "no-store"})
                return self._redirect(f"/p/{app.id}/")
            if path == "/api/projects":
                return self._json({"projects": self.registry.list()})
            if path == "/projects":
                return self._send(200, _projects_page(self.registry.list()), "text/html; charset=utf-8")
            path = self._resolve(path, query)
            if path is None:
                return
            if path == "/api/graph":
                return self._graph()
            if path == "/api/events":
                return self._events()
            if path == "/api/source":
                return self._source(query)
            if path == "/api/status":
                return self._json(self.app.status())
            if path.startswith("/api/"):
                return self._error(404, "unknown endpoint")
            return self._static(path)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_POST(self) -> None:
        try:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(min(length, 1 << 20)) if length > 0 else b""
            if not self._host_ok():
                return self._error(403, "forbidden host")
            path = self._resolve(urllib.parse.urlsplit(self.path).path, {})
            if path is None:
                return
            if path == "/api/open":
                try:
                    body = json.loads(raw or b"{}")
                    rel = str(body["file"])
                    line = int(body.get("line") or 1)
                except (ValueError, KeyError, TypeError):
                    return self._error(400, 'expected JSON body {"file": "<rel>", "line": N}')
                return self._json(self.app.open_in_ide(rel, line))
            if path == "/api/rebuild":
                threading.Thread(target=self.app.rebuild, args=([],), daemon=True).start()
                return self._json({"ok": True})
            return self._error(404, "unknown endpoint")
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Allow", "GET, HEAD, POST, OPTIONS")
        self.send_header("Content-Length", "0")
        self.end_headers()

    # ---- endpoints ----
    def _graph(self) -> None:
        body, gz = self.app.graph_payload(self._accepts_gzip())
        extra = {"Vary": "Accept-Encoding"}
        if gz:
            extra["Content-Encoding"] = "gzip"
        self._send(200, body, "application/json; charset=utf-8", extra)

    def _events(self) -> None:
        app = self.app
        q = app.subscribe()
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "keep-alive")
            self.send_header("X-Accel-Buffering", "no")
            self.end_headers()
            self.close_connection = True
            hello = json.dumps({"version": app.version})
            self.wfile.write(f"retry: 2000\nevent: hello\ndata: {hello}\n\n".encode())
            self.wfile.flush()
            while not app.stop_event.is_set():
                try:
                    msg = q.get(timeout=app.heartbeat)
                except queue.Empty:
                    msg = b": heartbeat\n\n"
                if app.stop_event.is_set():
                    break
                self.wfile.write(msg)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass
        finally:
            app.unsubscribe(q)

    def _source(self, query: dict) -> None:
        rel = (query.get("file") or [""])[0]
        path = self.app.safe_path(rel)
        if path is None:
            return self._error(403, "path outside project root")
        if not path.is_file():
            return self._error(404, "file not found")
        try:
            start = int((query.get("start") or ["1"])[0])
            end_raw = (query.get("end") or [""])[0]
            end = int(end_raw) if end_raw else None
        except ValueError:
            return self._error(400, "start/end must be integers")
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            return self._error(500, str(exc))
        all_lines = text.splitlines()
        start = max(1, start)
        if end is None or end < start:
            end = len(all_lines)
        end = min(end, len(all_lines), start + MAX_SOURCE_LINES - 1)
        rel_out = path.relative_to(self.app.root).as_posix()
        self._json({"file": rel_out, "start": start, "end": end, "total": len(all_lines),
                    "lines": all_lines[start - 1:end]})

    def _static(self, path: str) -> None:
        rel = urllib.parse.unquote(path).lstrip("/") or "index.html"
        web = WEB_DIR.resolve()
        target = (web / rel).resolve()
        try:
            target.relative_to(web)
        except ValueError:
            return self._error(403, "forbidden")
        if target.is_dir():
            target = target / "index.html"
        if not target.is_file():
            return self._send(404, b"not found", "text/plain; charset=utf-8")
        ctype = _CONTENT_TYPES.get(target.suffix.lower()) or mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        body = target.read_bytes()
        extra = {}
        if self._accepts_gzip() and len(body) > 2048 and (ctype.startswith("text/") or "json" in ctype or "svg" in ctype):
            body = gzip.compress(body, 6)
            extra = {"Content-Encoding": "gzip", "Vary": "Accept-Encoding"}
        self._send(200, body, ctype, extra)


class CodemapServer(ThreadingHTTPServer):
    daemon_threads = True
    # SO_REUSEADDR on Windows lets a second process bind an in-use port; only use it on POSIX.
    allow_reuse_address = os.name != "nt"

    def __init__(self, addr, handler, registry: ProjectRegistry):
        self.registry = registry
        super().__init__(addr, handler)

    def handle_error(self, request, client_address) -> None:
        exc = sys.exc_info()[1]
        if isinstance(exc, (BrokenPipeError, ConnectionResetError, ConnectionAbortedError)):
            return
        super().handle_error(request, client_address)

    def shutdown(self) -> None:
        self.registry.stop()
        super().shutdown()


def make_server(app_or_registry: CodemapApp | ProjectRegistry, host: str = "127.0.0.1",
                port: int = 8765) -> CodemapServer:
    """Create (bind) the HTTP server. Raises OSError if the port is taken.

    Passing a single CodemapApp serves it at "/" (default project) and at /p/<id>/.
    """
    if isinstance(app_or_registry, ProjectRegistry):
        registry = app_or_registry
    else:
        app = app_or_registry
        app._started = True  # caller manages start()
        registry = ProjectRegistry(lambda root: CodemapApp(root, app.excludes, watch=app.watch,
                                                           plugin_ports=app.plugin_ports, ide_port=app.ide_port))
        registry.add(app, default=True)
    handler = type("BoundCodemapHandler", (CodemapHandler,), {"registry": registry})
    return CodemapServer((host, port), handler, registry)


READY_PREFIX = "CODEMAP_READY"


def ready_line(host: str, port: int) -> str:
    """The single machine-readable stdout line announcing the bound port (see SPEC "Bundled mode")."""
    shown = "127.0.0.1" if host in ("0.0.0.0", "", "::") else host
    if ":" in shown and not shown.startswith("["):
        shown = f"[{shown}]"
    return f"{READY_PREFIX} port={port} url=http://{shown}:{port}/"


def _watch_stdin(on_eof: Callable[[], None]) -> threading.Thread | None:
    """Daemon thread: call on_eof() once stdin reaches EOF (the parent IDE died or closed the pipe).

    Reads the raw fd with os.read (no BufferedReader lock held at interpreter shutdown; works on Windows pipes).
    """
    try:
        fd = sys.stdin.fileno() if sys.stdin is not None else -1
    except (OSError, ValueError, AttributeError):
        fd = -1
    if fd < 0:
        _log("--exit-on-stdin-eof: no usable stdin; ignoring")
        return None

    def loop() -> None:
        try:
            while os.read(fd, 4096):
                pass
        except (OSError, ValueError):
            pass
        try:
            _log("stdin closed: shutting down")  # _log never raises, but on_eof must run whatever happens
        finally:
            on_eof()

    t = threading.Thread(target=loop, name="codemap-stdin", daemon=True)
    t.start()
    return t


def serve(root: str | None, host: str = "127.0.0.1", port: int = 8765,
          excludes: list[str] | None = None, watch: bool = True, *,
          ide_port: int | None = None, exit_on_stdin_eof: bool = False) -> None:
    """Run the server until Ctrl+C / SIGTERM / (optionally) stdin EOF.

    stdout carries exactly one line, ``CODEMAP_READY port=<n> url=http://127.0.0.1:<n>/``, printed right after
    the socket is bound; all other output goes to stderr.
    """
    try:  # installed before READY is printed so an early SIGTERM is still clean
        import signal

        def _term(signum, frame):  # systemd/kill -> clean shutdown
            raise KeyboardInterrupt

        signal.signal(signal.SIGTERM, _term)
    except (ValueError, OSError, AttributeError):  # not in main thread / platform without SIGTERM
        pass
    registry = ProjectRegistry(lambda r: CodemapApp(r, excludes, watch=watch, ide_port=ide_port))
    httpd = make_server(registry, host, port)  # bind first so port errors surface before the build
    main_done = threading.Event()

    def _on_stdin_eof() -> None:
        # Runs on the stdin thread. Stop watchers first, then stop serve_forever from a helper thread (shutdown()
        # blocks until the loop exits). Hard deadline: if the main thread cannot finish (e.g. a long analysis
        # holds the GIL in C code), exit anyway so the IDE never waits on us and no orphan survives.
        registry.stop()
        threading.Thread(target=httpd.shutdown, name="codemap-shutdown", daemon=True).start()
        if not main_done.wait(1.0):
            _log("shutdown deadline passed: exiting now")
            os._exit(0)

    bound_port = httpd.server_address[1]
    shown = "127.0.0.1" if host in ("0.0.0.0", "", "::") else host
    base = f"http://{shown}:{bound_port}"
    try:
        sys.stdout.write(ready_line(host, bound_port) + "\n")
        sys.stdout.flush()
        if root:
            app = registry.get_or_create(root)
            registry.default_id = app.id
            _log(f"serving {app.root} at {base}/  (also {base}/p/{app.id}/)")
        _log(f"per-project maps at {base}/open?root=<abs project path>  (list: {base}/projects)"
             f"  (watch={'on' if watch else 'off'}" + (f", ide port {ide_port}" if ide_port else "") + ")")
        registry.start()
        if exit_on_stdin_eof:
            _watch_stdin(_on_stdin_eof)  # serve_forever (main thread) then returns
        httpd.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:  # Ctrl+C or SIGTERM (see _term)
        _log("shutting down")
    finally:
        registry.stop()
        httpd.server_close()
    _log("stopped")
    if exit_on_stdin_eof:
        # Managed (IDE-owned) mode: skip interpreter finalisation, which can take seconds on a big graph (freeing
        # it) or wait on a running analysis thread. Nothing is buffered: stdout only carried the READY line.
        main_done.set()
        try:
            sys.stderr.flush()
        except (OSError, ValueError, AttributeError):
            pass
        os._exit(0)


# --------------------------------------------------------------------------- pages

_PAGE_CSS = """body{background:#1e1f22;color:#dfe1e5;font:14px system-ui,sans-serif;margin:40px auto;max-width:760px}
a{color:#548af7;text-decoration:none}a:hover{text-decoration:underline}h1{font-size:20px}
code,input{font-family:'JetBrains Mono',monospace}li{margin:8px 0}.muted{color:#868a91}
input{background:#2b2d30;color:#dfe1e5;border:1px solid #43454a;border-radius:4px;padding:6px;width:70%}
button{background:#3574f0;color:#fff;border:0;border-radius:4px;padding:7px 12px;cursor:pointer}"""


def _projects_page(projects: list[dict]) -> bytes:
    items = "".join(
        f'<li><a href="{html.escape(p["url"])}">{html.escape(p["project"])}</a> '
        f'<span class="muted"><code>{html.escape(p["root"])}</code></span></li>'
        for p in projects
    ) or '<li class="muted">No project open yet.</li>'
    body = f"""<!doctype html><html><head><meta charset="utf-8"><title>codemap projects</title>
<style>{_PAGE_CSS}</style></head><body><h1>codemap: projects</h1>
<p class="muted">Each project has its own map. In PyCharm use <b>Open Code Map</b> (Ctrl+Alt+Shift+K) to open the
current project's map, or open a project path here.</p><ul>{items}</ul>
<form action="/open" method="get"><input name="root" placeholder="/home/you/PycharmProjects/my_project">
<button>Open</button></form></body></html>"""
    return body.encode("utf-8")


def _analysing_page(app: CodemapApp) -> bytes:
    """Shown by /open while a big project's first build runs; polls /api/status, then opens the map (keeping the
    #focus=… fragment the IDE may have added)."""
    pid = json.dumps(app.id)
    body = f"""<!doctype html><html><head><meta charset="utf-8"><title>codemap: analysing {html.escape(app.root.name)}</title>
<style>{_PAGE_CSS}
.spin{{width:28px;height:28px;border:3px solid rgba(127,127,127,.3);border-top-color:#548af7;border-radius:50%;
animation:s 1s linear infinite}}@keyframes s{{to{{transform:rotate(360deg)}}}}</style></head><body>
<div class="spin"></div><h1>Analysing {html.escape(app.root.name)}…</h1>
<p class="muted">Building the call graph of <code>{html.escape(str(app.root))}</code>
(<span id="t">0</span> s). Large projects can take a while the first time; the map opens by itself.</p>
<script>(function(){{var id={pid},t0=Date.now();
function go(){{location.replace('/p/'+encodeURIComponent(id)+'/'+location.hash);}}
function poll(){{document.getElementById('t').textContent=Math.round((Date.now()-t0)/1000);
fetch('/p/'+encodeURIComponent(id)+'/api/status',{{cache:'no-store'}}).then(function(r){{return r.ok?r.json():null;}})
.then(function(s){{if(s&&s.ready)go();else setTimeout(poll,700);}},function(){{setTimeout(poll,1500);}});}}
poll();}})();</script></body></html>"""
    return body.encode("utf-8")


def _unknown_project_page(pid: str) -> bytes:
    body = f"""<!doctype html><html><head><meta charset="utf-8"><title>codemap: unknown project</title>
<style>{_PAGE_CSS}</style></head><body><h1>Project not loaded</h1>
<p>No project <code>{html.escape(pid)}</code> is loaded (the server restarted or the project was idle).
Re-open it from PyCharm with <b>Open Code Map</b>, or pick one on the <a href="/projects">projects page</a>.</p>
</body></html>"""
    return body.encode("utf-8")
