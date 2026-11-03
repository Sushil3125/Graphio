"""Tests for the bundled-mode CLI (SPEC "Bundled mode ... v0.4"): --port 0 + CODEMAP_READY, --exit-on-stdin-eof,
--ide-port. The server is spawned exactly as the IDE plugin does it, from a copy of the package in a temp dir."""
from __future__ import annotations

import http.client
import json
import os
import queue
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

PKG = Path(__file__).resolve().parents[1] / "codemap"
sys.path.insert(0, str(PKG.parent))

from codemap.server import CodemapApp, project_id, ready_line  # noqa: E402

READY_RE = re.compile(r"^CODEMAP_READY port=(\d+) url=http://127\.0\.0\.1:(\d+)/$")


def _free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _reader(stream, q: queue.Queue) -> None:
    for line in iter(stream.readline, ""):
        q.put(line)
    q.put(None)


class TestBundledServerProcess(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        self.server_dir = base / "codemap-server"
        shutil.copytree(PKG, self.server_dir / "codemap", ignore=shutil.ignore_patterns("__pycache__"))
        self.fixture = base / "fixture_proj"
        (self.fixture / "pkg").mkdir(parents=True)
        (self.fixture / "pkg" / "__init__.py").write_text("")
        (self.fixture / "pkg" / "a.py").write_text("def f():\n    return g()\n\n\ndef g():\n    return 1\n")
        (self.fixture / "main.py").write_text("from pkg.a import f\n\nf()\n")
        self.proc: subprocess.Popen | None = None

    def tearDown(self):
        if self.proc is not None and self.proc.poll() is None:
            self.proc.kill()
            self.proc.wait(5)
        for s in (getattr(self.proc, "stdin", None), getattr(self.proc, "stdout", None),
                  getattr(self.proc, "stderr", None)):
            if s:
                try:
                    s.close()
                except OSError:
                    pass
        self.tmp.cleanup()

    def _spawn(self, *extra: str) -> tuple[int, queue.Queue]:
        env = dict(os.environ, PYTHONPATH=str(self.server_dir), PYTHONUNBUFFERED="1")
        env.pop("CODEMAP_PORT", None)
        self.proc = subprocess.Popen(
            [sys.executable, "-B", "-m", "codemap", "--host", "127.0.0.1", "--port", "0", *extra],
            cwd=str(self.server_dir), env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, encoding="utf-8",
        )
        out: queue.Queue = queue.Queue()
        threading.Thread(target=_reader, args=(self.proc.stdout, out), daemon=True).start()
        self.err: queue.Queue = queue.Queue()
        threading.Thread(target=_reader, args=(self.proc.stderr, self.err), daemon=True).start()
        try:
            line = out.get(timeout=20)
        except queue.Empty:
            self.fail("no CODEMAP_READY line within 20 s")
        self.assertIsNotNone(line, f"server exited early: {self._stderr()}")
        m = READY_RE.match(line.rstrip("\r\n"))
        self.assertIsNotNone(m, f"bad ready line: {line!r}")
        self.assertEqual(m.group(1), m.group(2))
        port = int(m.group(1))
        self.assertGreater(port, 0)
        return port, out

    def _stderr(self) -> str:
        lines = []
        while True:
            try:
                ln = self.err.get_nowait()
            except queue.Empty:
                break
            if ln is None:
                break
            lines.append(ln)
        return "".join(lines)

    def _get(self, port: int, path: str):
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
        conn.request("GET", path)
        resp = conn.getresponse()
        data = resp.read()
        conn.close()
        return resp, data

    def test_ready_open_and_exit_on_stdin_eof(self):
        port, out = self._spawn("--exit-on-stdin-eof", "--no-watch", "--ide-port", str(_free_port()))
        resp, _ = self._get(port, "/open?root=" + str(self.fixture))
        self.assertEqual(resp.status, 302)
        loc = resp.getheader("Location")
        pid = project_id(self.fixture.resolve())
        self.assertEqual(loc, f"/p/{pid}/")
        resp, data = self._get(port, f"/p/{pid}/api/graph")
        self.assertEqual(resp.status, 200)
        graph = json.loads(data)
        files = {n.get("file") for n in graph["nodes"]}
        self.assertIn("pkg/a.py", files)  # rel paths are posix on every OS
        resp, _ = self._get(port, f"/p/{pid}/")
        self.assertEqual(resp.status, 200)

        t0 = time.monotonic()
        self.proc.stdin.close()
        try:
            rc = self.proc.wait(3)
        except subprocess.TimeoutExpired:
            self.fail("server did not exit within 3 s of stdin EOF")
        self.assertLess(time.monotonic() - t0, 3.0)
        self.assertEqual(rc, 0, self._stderr())
        # stdout must carry nothing but the READY line
        rest = []
        while True:
            ln = out.get(timeout=5)
            if ln is None:
                break
            rest.append(ln)
        self.assertEqual(rest, [], "unexpected stdout output after CODEMAP_READY")

    def test_exit_on_stdin_eof_when_stderr_reader_is_gone(self):
        """The IDE died: nobody reads stderr/stdout any more, then stdin hits EOF -> the server must still exit
        (logging "stdin closed" to a broken pipe must not kill the shutdown path)."""
        env = dict(os.environ, PYTHONPATH=str(self.server_dir), PYTHONUNBUFFERED="1")
        self.proc = subprocess.Popen(
            [sys.executable, "-B", "-m", "codemap", "--host", "127.0.0.1", "--port", "0", "--exit-on-stdin-eof"],
            cwd=str(self.server_dir), env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE)
        line = self.proc.stdout.readline().decode()
        self.assertTrue(line.startswith("CODEMAP_READY"), line)
        port = int(READY_RE.match(line.strip()).group(1))
        self.proc.stderr.close()
        self.proc.stdout.close()
        # make the server log something to the now reader-less stderr pipe before EOF, too
        resp, _ = self._get(port, "/open?root=" + str(self.fixture))
        self.assertEqual(resp.status, 302)
        self.proc.stdin.close()
        try:
            rc = self.proc.wait(3)
        except subprocess.TimeoutExpired:
            self.fail("server kept running after stdin EOF with its stderr reader gone (orphan)")
        self.assertEqual(rc, 0)

    def test_without_flag_stdin_eof_is_ignored(self):
        port, _ = self._spawn("--no-watch")
        self.proc.stdin.close()
        time.sleep(1.0)
        self.assertIsNone(self.proc.poll(), "server exited on stdin EOF without --exit-on-stdin-eof")
        resp, _ = self._get(port, "/api/projects")
        self.assertEqual(resp.status, 200)


class TestReadyLine(unittest.TestCase):
    def test_format(self):
        self.assertEqual(ready_line("127.0.0.1", 5123), "CODEMAP_READY port=5123 url=http://127.0.0.1:5123/")
        self.assertEqual(ready_line("0.0.0.0", 1), "CODEMAP_READY port=1 url=http://127.0.0.1:1/")
        self.assertEqual(ready_line("::1", 2), "CODEMAP_READY port=2 url=http://[::1]:2/")


class _FakeIde:
    """A fake JetBrains built-in server answering /api/codemap/ping and /api/codemap/open."""

    def __init__(self):
        self.calls: list[str] = []
        calls = self.calls

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

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class TestIdePort(unittest.TestCase):
    def test_ide_port_tried_first(self):
        ide, other = _FakeIde(), _FakeIde()
        try:
            with tempfile.TemporaryDirectory() as d:
                (Path(d) / "m.py").write_text("x = 1\n")
                app = CodemapApp(d, watch=False, plugin_ports=[other.port, _free_port()], ide_port=ide.port,
                                 build_graph=lambda r, e=None: {"nodes": [], "edges": []},
                                 iter_files=lambda r, e=None: [])
                self.assertEqual(app._candidate_ports()[0], ide.port)
                res = app.open_in_ide("m.py", 4)
                self.assertTrue(res["ok"], res)
                self.assertEqual(res["port"], ide.port)
                self.assertTrue(any(c.startswith("/api/codemap/open") and "line=4" in c for c in ide.calls))
                self.assertEqual(other.calls, [], "probe range must not be touched when the IDE port answers")
        finally:
            ide.close()
            other.close()

    def test_falls_back_to_probe_range(self):
        other = _FakeIde()
        try:
            with tempfile.TemporaryDirectory() as d:
                (Path(d) / "m.py").write_text("x = 1\n")
                dead = _free_port()
                app = CodemapApp(d, watch=False, plugin_ports=[other.port], ide_port=dead,
                                 build_graph=lambda r, e=None: {"nodes": [], "edges": []},
                                 iter_files=lambda r, e=None: [])
                self.assertEqual(app._candidate_ports(), [dead, other.port])
                res = app.open_in_ide("m.py", 1)
                self.assertTrue(res["ok"], res)
                self.assertEqual(res["port"], other.port)
        finally:
            other.close()


class TestPortability(unittest.TestCase):
    def test_project_id_case_folding_matches_platform(self):
        a = project_id(Path("/x/Proj"))
        b = project_id(Path("/x/proj"))
        if os.name == "nt":
            self.assertEqual(a.split("-")[-1], b.split("-")[-1])
        else:
            self.assertNotEqual(a, b)

    def test_project_id_stable(self):
        self.assertEqual(project_id(Path("/home/u/my proj")), project_id(Path("/home/u/my proj")))
        self.assertRegex(project_id(Path("/home/u/my proj")), r"^my-proj-[0-9a-f]{8}$")


if __name__ == "__main__":
    unittest.main()
