"""Tests for codemap.server (stdlib unittest). Uses a stub analyzer so it does not
depend on codemap.analyzer; one extra test runs against the real analyzer if present."""
from __future__ import annotations

import gzip
import http.client
import json
import urllib.parse
import os
import socket
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from codemap.server import CodemapApp, ProjectRegistry, make_server, project_id  # noqa: E402


def stub_iter_files(root, excludes=None):
    return sorted(Path(root).rglob("*.py"))


def stub_build_graph(root, excludes=None):
    nodes = []
    for p in stub_iter_files(root, excludes):
        rel = p.relative_to(root).as_posix()
        mod = rel[:-3].replace("/", ".")
        nodes.append({"id": f"{mod}:{mod}", "kind": "module", "name": mod, "file": rel, "line": 1})
    return {"root": str(root), "project": Path(root).name, "nodes": nodes, "edges": [],
            "external_calls": {}, "errors": [], "stats": {"files": len(nodes)}}


class ServerTestBase(unittest.TestCase):
    build_graph = staticmethod(stub_build_graph)
    iter_files = staticmethod(stub_iter_files)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "proj"
        (self.root / "pkg").mkdir(parents=True)
        (self.root / "pkg" / "__init__.py").write_text("")
        (self.root / "pkg" / "a.py").write_text("def f():\n    return g()\n\n\ndef g():\n    return 1\n")
        (self.root / "main.py").write_text("from pkg.a import f\n\nif __name__ == '__main__':\n    f()\n")
        (Path(self.tmp.name) / "secret.txt").write_text("top secret\n")
        self.app = CodemapApp(self.root, watch=True, poll_interval=0.1, debounce=0.1, heartbeat=0.5,
                              build_graph=self.build_graph, iter_files=self.iter_files,
                              plugin_ports=[_free_port()])
        self.httpd = make_server(self.app, "127.0.0.1", 0)
        self.port = self.httpd.server_address[1]
        self.app.start()
        self.thread = threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(5)
        self.tmp.cleanup()

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request(method, path, body=body, headers=headers or {})
        resp = conn.getresponse()
        data = resp.read()
        conn.close()
        return resp, data


def _free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class SSEClient:
    def __init__(self, port):
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=10)
        self.sock.sendall(b"GET /api/events HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n")
        self.buf = b""
        self.events = []
        self.comments = 0
        self.lock = threading.Lock()
        self.t = threading.Thread(target=self._run, daemon=True)
        self.t.start()

    def _run(self):
        try:
            while True:
                chunk = self.sock.recv(4096)
                if not chunk:
                    return
                with self.lock:
                    self.buf += chunk
                    while b"\n\n" in self.buf:
                        block, self.buf = self.buf.split(b"\n\n", 1)
                        text = block.decode()
                        if text.startswith("HTTP/1."):
                            text = text.split("\r\n\r\n", 1)[1] if "\r\n\r\n" in text else ""
                        ev, data = None, None
                        for line in text.split("\n"):
                            if line.startswith(":"):
                                self.comments += 1
                            elif line.startswith("event:"):
                                ev = line[6:].strip()
                            elif line.startswith("data:"):
                                data = json.loads(line[5:].strip())
                        if ev:
                            self.events.append((ev, data))
        except OSError:
            return

    def wait_for(self, pred, timeout=8.0):
        end = time.time() + timeout
        while time.time() < end:
            with self.lock:
                for ev in self.events:
                    if pred(ev):
                        return ev
            time.sleep(0.05)
        return None

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.sock.close()


class TestServer(ServerTestBase):
    def test_graph_plain_and_gzip(self):
        resp, data = self.request("GET", "/api/graph")
        self.assertEqual(resp.status, 200)
        self.assertIn("application/json", resp.getheader("Content-Type"))
        g = json.loads(data)
        self.assertEqual(g["version"], 1)
        self.assertEqual(g["project"], "proj")
        self.assertIn("pkg.a:pkg.a", {n["id"] for n in g["nodes"]})
        resp, data = self.request("GET", "/api/graph", headers={"Accept-Encoding": "gzip"})
        self.assertEqual(resp.getheader("Content-Encoding"), "gzip")
        self.assertEqual(json.loads(gzip.decompress(data))["version"], 1)

    def test_status(self):
        resp, data = self.request("GET", "/api/status")
        st = json.loads(data)
        self.assertEqual(st["version"], 1)
        self.assertEqual(st["root"], str(self.root.resolve()))
        self.assertTrue(st["watching"])
        self.assertEqual(st["errors"], 0)

    def test_sse_event_after_edit_multiple_clients(self):
        c1, c2 = SSEClient(self.port), SSEClient(self.port)
        try:
            self.assertIsNotNone(c1.wait_for(lambda e: e[0] == "hello"))
            self.assertIsNotNone(c2.wait_for(lambda e: e[0] == "hello"))
            time.sleep(0.2)
            f = self.root / "pkg" / "a.py"
            f.write_text(f.read_text() + "\n\ndef h():\n    pass\n")
            (self.root / "pkg" / "new.py").write_text("x = 1\n")
            for c in (c1, c2):
                ev = c.wait_for(lambda e: e[0] == "graph")
                self.assertIsNotNone(ev, "no graph event received")
                self.assertGreaterEqual(ev[1]["version"], 2)
                self.assertIn("pkg/a.py", ev[1]["changed"])
            resp, data = self.request("GET", "/api/graph")
            g = json.loads(data)
            self.assertGreaterEqual(g["version"], 2)
            self.assertIn("pkg.new:pkg.new", {n["id"] for n in g["nodes"]})
            # heartbeat comment arrives (heartbeat=0.5 s in tests)
            end = time.time() + 3
            while c1.comments == 0 and time.time() < end:
                time.sleep(0.05)
            self.assertGreater(c1.comments, 0)
        finally:
            c1.close()
            c2.close()

    def test_delete_triggers_rebuild(self):
        c = SSEClient(self.port)
        try:
            c.wait_for(lambda e: e[0] == "hello")
            (self.root / "main.py").unlink()
            ev = c.wait_for(lambda e: e[0] == "graph")
            self.assertIsNotNone(ev)
            self.assertIn("main.py", ev[1]["changed"])
        finally:
            c.close()

    def test_source_ok(self):
        resp, data = self.request("GET", "/api/source?file=pkg/a.py&start=1&end=2")
        self.assertEqual(resp.status, 200)
        d = json.loads(data)
        self.assertEqual(d["file"], "pkg/a.py")
        self.assertEqual(d["start"], 1)
        self.assertEqual(d["lines"], ["def f():", "    return g()"])

    def test_source_traversal_rejected(self):
        for bad in ("../secret.txt", "pkg/../../secret.txt", "%2e%2e/secret.txt",
                    str(Path(self.tmp.name) / "secret.txt"), "/etc/passwd"):
            resp, data = self.request("GET", "/api/source?file=" + bad)
            self.assertEqual(resp.status, 403, bad)
            self.assertNotIn(b"top secret", data)

    def test_source_symlink_escape_rejected(self):
        link = self.root / "evil.py"
        try:
            os.symlink(Path(self.tmp.name) / "secret.txt", link)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks unsupported")
        resp, data = self.request("GET", "/api/source?file=evil.py")
        self.assertEqual(resp.status, 403)

    def test_source_missing(self):
        resp, _ = self.request("GET", "/api/source?file=nope.py")
        self.assertEqual(resp.status, 404)

    def test_static_traversal_rejected(self):
        resp, data = self.request("GET", "/../server.py")
        self.assertIn(resp.status, (403, 404))
        self.assertNotIn(b"ThreadingHTTPServer", data)
        resp, data = self.request("GET", "/%2e%2e/server.py")
        self.assertIn(resp.status, (403, 404))
        self.assertNotIn(b"ThreadingHTTPServer", data)

    def test_static_vendor(self):
        resp, data = self.request("GET", "/vendor/elk.bundled.js")
        self.assertEqual(resp.status, 200)
        self.assertIn("javascript", resp.getheader("Content-Type"))
        self.assertEqual(resp.getheader("Cache-Control"), "no-store")

    def test_open_without_ide(self):
        old = os.environ.get("PATH", "")
        os.environ["PATH"] = self.tmp.name  # make sure pycharm/charm are not found
        try:
            resp, data = self.request("POST", "/api/open", body=json.dumps({"file": "pkg/a.py", "line": 3}),
                                      headers={"Content-Type": "application/json"})
        finally:
            os.environ["PATH"] = old
        d = json.loads(data)
        self.assertFalse(d["ok"])
        self.assertIn("error", d)
        resp, data = self.request("POST", "/api/open", body=json.dumps({"file": "../secret.txt", "line": 1}))
        self.assertFalse(json.loads(data)["ok"])
        resp, data = self.request("POST", "/api/open", body=b"not json")
        self.assertEqual(resp.status, 400)

    def test_windows_launcher_detection(self):
        from codemap.server import _is_windows_launcher
        if not sys.platform.startswith("linux"):  # WSL heuristic only applies on Linux
            self.assertFalse(_is_windows_launcher("/mnt/c/Users/x/scripts/pycharm"))
            return
        self.assertTrue(_is_windows_launcher("/mnt/c/Users/x/AppData/Local/JetBrains/Toolbox/scripts/pycharm"))
        self.assertFalse(_is_windows_launcher("/usr/local/bin/charm"))

    def test_unknown_api(self):
        resp, _ = self.request("GET", "/api/nope")
        self.assertEqual(resp.status, 404)


class TestOpenViaFakePlugin(unittest.TestCase):
    """A fake JetBrains built-in server implementing /api/codemap/ping and /open."""

    def test_plugin_used(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

        calls = []

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def do_GET(self):
                calls.append(self.path)
                body = b'{"ok":true,"plugin":"codemap"}'
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        fake = ThreadingHTTPServer(("127.0.0.1", 0), H)
        t = threading.Thread(target=fake.serve_forever, daemon=True)
        t.start()
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "m.py").write_text("x=1\n")
            dead = _free_port()
            app = CodemapApp(d, watch=False, build_graph=stub_build_graph, iter_files=stub_iter_files,
                             plugin_ports=[dead, fake.server_address[1]])
            try:
                res = app.open_in_ide("m.py", 7)
                self.assertEqual(res["ok"], True)
                self.assertEqual(res["via"], "plugin")
                self.assertTrue(any(c.startswith("/api/codemap/ping") for c in calls))
                opens = [c for c in calls if c.startswith("/api/codemap/open")]
                self.assertEqual(len(opens), 1)
                self.assertIn("line=7", opens[0])
                self.assertIn("m.py", opens[0])
                self.assertEqual(app._plugin_port, fake.server_address[1])
            finally:
                fake.shutdown()
                fake.server_close()


class TestMultiProject(unittest.TestCase):
    """One server, a separate graph per project root (no default project)."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.a = base / "alpha"
        self.b = base / "beta"
        for root, fn in ((self.a, "only_in_alpha"), (self.b, "only_in_beta")):
            root.mkdir()
            (root / "mod.py").write_text(f"def {fn}():\n    return 1\n")
        self.registry = ProjectRegistry(lambda r: CodemapApp(
            r, watch=True, poll_interval=0.1, debounce=0.1, heartbeat=0.5,
            plugin_ports=[_free_port()]),  # real analyzer
            idle_seconds=60)
        self.httpd = make_server(self.registry, "127.0.0.1", 0)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(5)
        self.tmp.cleanup()

    def request(self, method, path, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        conn.request(method, path, headers=headers or {})
        resp = conn.getresponse()
        data = resp.read()
        conn.close()
        return resp, data

    def open_project(self, root):
        resp, _ = self.request("GET", "/open?root=" + urllib.parse.quote(str(root)))
        self.assertEqual(resp.status, 302)
        loc = resp.getheader("Location")
        self.assertEqual(loc, f"/p/{project_id(root.resolve())}/")
        return loc

    def node_names(self, prefix):
        resp, data = self.request("GET", prefix + "api/graph")
        self.assertEqual(resp.status, 200)
        return {n["name"] for n in json.loads(data)["nodes"]}

    def test_projects_are_isolated(self):
        pa, pb = self.open_project(self.a), self.open_project(self.b)
        self.assertNotEqual(pa, pb)
        self.assertIn("only_in_alpha", self.node_names(pa))
        self.assertNotIn("only_in_beta", self.node_names(pa))
        self.assertIn("only_in_beta", self.node_names(pb))
        self.assertNotIn("only_in_alpha", self.node_names(pb))
        resp, data = self.request("GET", pa + "api/source?file=mod.py")
        self.assertIn("only_in_alpha", data.decode())
        resp, data = self.request("GET", "/api/projects")
        self.assertEqual({p["project"] for p in json.loads(data)["projects"]}, {"alpha", "beta"})
        resp, data = self.request("GET", pa)
        self.assertEqual(resp.status, 200)
        self.assertIn(b"app.js", data)  # the web UI, served under the project prefix

    def test_reopen_reuses_project(self):
        self.assertEqual(self.open_project(self.a), self.open_project(self.a))
        self.assertEqual(len(self.registry.list()), 1)

    def test_no_default_project_shows_project_list(self):
        self.open_project(self.a)
        resp, data = self.request("GET", "/")
        self.assertEqual(resp.status, 200)
        self.assertIn(b"alpha", data)
        resp, _ = self.request("GET", "/api/graph")
        self.assertEqual(resp.status, 404)

    def test_bad_roots_rejected(self):
        for bad in ("relative/path", str(Path(self.tmp.name) / "missing"), "/", str(Path.home())):
            resp, _ = self.request("GET", "/open?root=" + urllib.parse.quote(bad))
            self.assertEqual(resp.status, 400, bad)

    def test_unknown_project_404(self):
        resp, data = self.request("GET", "/p/nope-12345678/")
        self.assertEqual(resp.status, 404)
        self.assertIn(b"Open Code Map", data)

    def test_foreign_host_header_rejected(self):
        resp, _ = self.request("GET", "/api/projects", headers={"Host": "evil.example:8765"})
        self.assertEqual(resp.status, 403)
        resp, _ = self.request("GET", "/api/projects", headers={"Host": "localhost:8765"})
        self.assertEqual(resp.status, 200)

    def test_idle_project_is_stopped(self):
        pa = self.open_project(self.a)
        app = self.registry.get(pa.split("/")[2])
        import time as _t
        dropped = self.registry.reap_idle(now=_t.monotonic() + 3600)
        self.assertEqual(dropped, [app.id])
        self.assertTrue(app.stop_event.is_set())
        resp, _ = self.request("GET", pa)
        self.assertEqual(resp.status, 404)
        self.open_project(self.a)  # re-open works
        self.assertIn("only_in_alpha", self.node_names(pa))


def _real_analyzer_available():
    try:
        import codemap.analyzer as a  # noqa: F401
        return hasattr(a, "build_graph") and hasattr(a, "iter_python_files")
    except Exception:
        return False


@unittest.skipUnless(_real_analyzer_available(), "codemap.analyzer not available yet")
class TestWithRealAnalyzer(ServerTestBase):
    build_graph = None
    iter_files = None

    def test_graph_real(self):
        resp, data = self.request("GET", "/api/graph")
        g = json.loads(data)
        self.assertEqual(g["version"], 1)
        ids = {n["id"] for n in g["nodes"]}
        self.assertIn("pkg.a:f", ids)


if __name__ == "__main__":
    unittest.main()


class TestRobustness(unittest.TestCase):
    """Bundled-mode hardening: logging to a dead stderr, stdin-EOF thread, watcher back-off, async first build."""

    def test_log_never_raises(self):
        import io
        import codemap.server as srv

        class Broken(io.StringIO):
            def write(self, s):
                raise BrokenPipeError(32, "Broken pipe")

        old = sys.stderr
        try:
            sys.stderr = Broken()
            srv._log("hello")  # must not raise
            closed = io.StringIO()
            closed.close()
            sys.stderr = closed
            srv._log("hello")  # ValueError: I/O operation on closed file -> swallowed
        finally:
            sys.stderr = old

    def test_stdin_eof_calls_on_eof_even_if_logging_fails(self):
        import codemap.server as srv

        r, w = os.pipe()

        class FakeStdin:
            def fileno(self):
                return r

        called = threading.Event()
        old_stdin, old_log = sys.stdin, srv._log
        try:
            sys.stdin = FakeStdin()

            def bad_log(msg):
                raise BrokenPipeError(32, "Broken pipe")

            srv._log = bad_log
            t = srv._watch_stdin(called.set)
            os.close(w)
            self.assertTrue(called.wait(3), "on_eof was not called")
            t.join(3)
        finally:
            sys.stdin, srv._log = old_stdin, old_log
            os.close(r)

    def test_watch_interval_backs_off_with_scan_time(self):
        import codemap.server as srv
        app = CodemapApp(tempfile.gettempdir(), watch=False, poll_interval=1.0, build_graph=lambda r, e: {},
                         iter_files=lambda r, e: [])
        self.assertEqual(app._next_interval(0.001), 1.0)
        self.assertAlmostEqual(app._next_interval(0.2), 0.2 * srv.WATCH_SCAN_FACTOR)
        self.assertEqual(app._next_interval(5.0), srv.WATCH_MAX_INTERVAL)

    def test_open_shows_analysing_page_for_slow_first_build(self):
        import codemap.server as srv
        release = threading.Event()

        def slow_build(root, excludes=None):
            release.wait(10)
            return {"nodes": [{"id": "m:f", "name": "f"}], "edges": []}

        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name) / "bigproj"
        root.mkdir()
        registry = ProjectRegistry(lambda r: CodemapApp(r, watch=False, build_graph=slow_build,
                                                        iter_files=lambda r, e: []))
        httpd = make_server(registry, "127.0.0.1", 0)
        port = httpd.server_address[1]
        th = threading.Thread(target=httpd.serve_forever, kwargs={"poll_interval": 0.1}, daemon=True)
        th.start()
        self.addCleanup(lambda: (release.set(), httpd.shutdown(), httpd.server_close(), th.join(5)))
        old_wait = srv.OPEN_WAIT_SECONDS
        srv.OPEN_WAIT_SECONDS = 0.2
        self.addCleanup(setattr, srv, "OPEN_WAIT_SECONDS", old_wait)

        def get(path, headers=None):
            conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
            conn.request("GET", path, headers=headers or {})
            resp = conn.getresponse()
            data = resp.read()
            conn.close()
            return resp, data

        pid = project_id(root.resolve())
        t0 = time.monotonic()
        html_accept = {"Accept": "text/html,application/xhtml+xml,*/*;q=0.8"}
        resp, data = get("/open?root=" + urllib.parse.quote(str(root)), html_accept)
        self.assertLess(time.monotonic() - t0, 3.0)
        self.assertEqual(resp.status, 200)
        self.assertIn(b"Analysing bigproj", data)
        self.assertIn(b"location.hash", data)  # keeps #focus=...
        resp, data = get(f"/p/{pid}/api/status")  # must not block on the build
        self.assertEqual(resp.status, 200)
        self.assertFalse(json.loads(data)["ready"])
        # an API client (no text/html in Accept) still gets the SPEC's blocking 302
        threading.Timer(0.5, release.set).start()
        resp, _ = get("/open?root=" + urllib.parse.quote(str(root)))
        self.assertEqual(resp.status, 302)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            resp, data = get(f"/p/{pid}/api/status")
            if json.loads(data)["ready"]:
                break
            time.sleep(0.05)
        self.assertTrue(json.loads(data)["ready"])
        resp, _ = get("/open?root=" + urllib.parse.quote(str(root)), html_accept)
        self.assertEqual(resp.status, 302)
        resp, data = get(f"/p/{pid}/api/graph")
        self.assertEqual([n["name"] for n in json.loads(data)["nodes"]], ["f"])
