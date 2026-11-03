"""Unit tests for tools/graphdiff.py (stdlib unittest)."""

from __future__ import annotations

import copy
import io
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

REPO = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO / "tools"))

import graphdiff  # noqa: E402

GOLDEN_DIR = REPO / "bench" / "golden"


def _node(nid, kind="function", line=1, ep=None, sig="()", doc=""):
    module, qual = nid.split(":")
    return {"id": nid, "kind": kind, "name": qual.split(".")[-1], "qualname": qual, "module": module,
            "file": module.replace(".", "/") + ".py", "line": line, "end_line": line + 2, "signature": sig,
            "doc": doc, "decorators": [], "is_async": False, "class_name": None, "package": "p", "loc": 3,
            "is_test": False, "entrypoint": ep}


def _edge(s, t, kind="calls", line=2):
    return {"source": s, "target": t, "kind": kind, "file": s.split(":")[0].replace(".", "/") + ".py", "line": line}


def _graph():
    return {
        "root": "/x/p", "project": "p",
        "nodes": [
            _node("p.a:p.a", kind="module"),
            _node("p.a:route", ep={"type": "http", "label": "GET /x", "method": "GET", "path": "/x"}),
            _node("p.a:helper", sig="(a, b)"),
            _node("p.b:Svc", kind="class"),
            _node("p.b:Base", kind="class"),
        ],
        "edges": [
            _edge("p.a:route", "p.a:helper"),
            _edge("p.a:route", "p.b:Svc", "instantiates"),
            _edge("p.b:Svc", "p.b:Base", "inherits"),
            _edge("p.a:route", "p.a:helper", "references", 3),
        ],
        "external_calls": {"p.a:helper": ["os.path.join"]},
        "errors": [],
        "stats": {"files": 2, "nodes": 5, "edges": 4, "entrypoints": 1, "calls_resolved": 2, "calls_external": 1},
    }


class CompareTests(unittest.TestCase):
    def test_identical_passes(self):
        rep = graphdiff.compare(_graph(), _graph())
        self.assertTrue(rep["passed"])
        self.assertEqual(rep["gates"]["c_resolved_internal_edges"], {"passed": True, "golden": 3, "candidate": 3})

    def test_missing_gated_edge_fails(self):
        cand = _graph()
        cand["edges"] = [e for e in cand["edges"] if e["kind"] != "instantiates"]
        rep = graphdiff.compare(_graph(), cand)
        self.assertFalse(rep["passed"])
        self.assertFalse(rep["gates"]["a_gated_edges_present"]["passed"])
        self.assertFalse(rep["gates"]["c_resolved_internal_edges"]["passed"])
        self.assertEqual([(e["source"], e["target"]) for e in rep["edges"]["missing_gated"]],
                         [("p.a:route", "p.b:Svc")])

    def test_missing_explained_edge_passes_when_compensated(self):
        cand = _graph()
        cand["edges"] = [e for e in cand["edges"] if e["kind"] != "instantiates"]
        cand["edges"].append(_edge("p.a:helper", "p.b:Svc"))  # extra keeps gate c
        ex = graphdiff.Explained.parse("- instantiates p.a:* -> p.b:Svc: golden was wrong\n")
        rep = graphdiff.compare(_graph(), cand, ex)
        self.assertTrue(rep["passed"], graphdiff.render(rep))
        self.assertEqual(rep["edges"]["missing_explained"][0]["reason"], "golden was wrong")
        self.assertEqual(len(rep["edges"]["extra_gated"]), 1)

    def test_missing_reference_edge_is_report_only(self):
        cand = _graph()
        cand["edges"] = [e for e in cand["edges"] if e["kind"] != "references"]
        rep = graphdiff.compare(_graph(), cand)
        self.assertTrue(rep["passed"])
        self.assertEqual(len(rep["edges"]["missing_other"]), 1)

    def test_extra_edges_do_not_fail(self):
        cand = _graph()
        cand["edges"].append(_edge("p.a:helper", "p.b:Base", "calls"))
        cand["edges"].append(_edge("p.b:Svc", "p.b:Base", "implements"))
        rep = graphdiff.compare(_graph(), cand)
        self.assertTrue(rep["passed"])
        self.assertEqual(len(rep["edges"]["extra_gated"]), 1)
        self.assertEqual(len(rep["edges"]["extra_other"]), 1)

    def test_entrypoint_label_change_fails_and_can_be_explained(self):
        cand = _graph()
        cand["nodes"][1]["entrypoint"] = {"type": "http", "label": "GET /y", "method": "GET", "path": "/y"}
        rep = graphdiff.compare(_graph(), cand)
        self.assertFalse(rep["passed"])
        self.assertEqual(rep["gates"]["b_entrypoints_identical"]["changed"], 1)
        ex = graphdiff.Explained.parse("- entrypoint p.a:route: prefix now resolved\n")
        self.assertTrue(graphdiff.compare(_graph(), cand, ex)["passed"])

    def test_entrypoint_missing_and_extra(self):
        cand = _graph()
        cand["nodes"][1]["entrypoint"] = None
        cand["nodes"][2]["entrypoint"] = {"type": "cli", "label": "helper"}
        rep = graphdiff.compare(_graph(), cand)
        gb = rep["gates"]["b_entrypoints_identical"]
        self.assertEqual((gb["missing"], gb["extra"], gb["passed"]), (1, 1, False))

    def test_signature_whitespace_non_blocking(self):
        cand = _graph()
        cand["nodes"][2]["signature"] = "(a,b)"
        cand["nodes"][3]["line"] = 99
        rep = graphdiff.compare(_graph(), cand)
        self.assertTrue(rep["passed"])
        self.assertEqual(len(rep["nodes"]["text_differs"].get("signature", [])), 1)  # "(a,b)" != "(a, b)"
        cand["nodes"][2]["signature"] = "(a,\n    b)"
        rep = graphdiff.compare(_graph(), cand)
        self.assertEqual(len(rep["nodes"]["text_whitespace_only"]["signature"]), 1)
        self.assertEqual(rep["nodes"]["field_mismatch"]["line"][0]["id"], "p.b:Svc")

    def test_missing_nodes_reported(self):
        cand = _graph()
        cand["nodes"] = cand["nodes"][:-1]
        rep = graphdiff.compare(_graph(), cand)
        self.assertEqual(rep["nodes"]["missing"], ["p.b:Base"])


class ExplainedParseTests(unittest.TestCase):
    def test_parse_formats_scopes_and_fences(self):
        text = "\n".join([
            "# Differences", "Some prose - not an entry.", "```", "- calls a:x -> b:y: example only", "```",
            "- calls p.a:f -> p.b:g: reason one",
            "- * p.c:* -> p.d:Cls.method: wildcard kind",
            "- entrypoint p.a:route: label changed",
            "- calls p.a:f -> p.b:h",
            "## other",
            "- calls p.a:route -> p.a:helper: only for project other",
        ])
        ex = graphdiff.Explained.parse(text)
        self.assertEqual([(e["kind"], e["source"], e["target"], e["reason"]) for e in ex.edges], [
            ("calls", "p.a:f", "p.b:g", "reason one"),
            ("*", "p.c:*", "p.d:Cls.method", "wildcard kind"),
            ("calls", "p.a:route", "p.a:helper", "only for project other"),
        ])
        self.assertEqual(ex.entrypoints[0]["id"], "p.a:route")
        self.assertEqual(len(ex.warnings), 1)  # entry without a reason
        self.assertIsNone(ex.edge("p", "p.a:route", "p.a:helper", "calls"))  # scoped to "other"
        self.assertIsNotNone(ex.edge("other", "p.a:route", "p.a:helper", "calls"))
        self.assertIsNotNone(ex.edge("p", "p.c:m", "p.d:Cls.method", "depends"))

    def test_unused_entries_listed(self):
        ex = graphdiff.Explained.parse("- calls p.z:a -> p.z:b: never happens\n")
        rep = graphdiff.compare(_graph(), _graph(), ex)
        self.assertEqual(len(rep["explained_unused"]), 1)


class CliTests(unittest.TestCase):
    def _run(self, golden, cand, *extra):
        with tempfile.TemporaryDirectory() as d:
            gp, cp, rp = (pathlib.Path(d) / n for n in ("g.json", "c.json", "r.json"))
            gp.write_text(json.dumps(golden))
            cp.write_text(json.dumps(cand))
            buf = io.StringIO()
            with redirect_stdout(buf):
                code = graphdiff.main([str(gp), str(cp), "--json", str(rp), *extra])
            return code, buf.getvalue(), json.loads(rp.read_text())

    def test_cli_exit_codes(self):
        code, out, rep = self._run(_graph(), _graph())
        self.assertEqual(code, 0)
        self.assertIn("PASS", out)
        self.assertTrue(rep["passed"])
        cand = _graph()
        cand["edges"].pop(0)
        code, out, rep = self._run(_graph(), cand)
        self.assertEqual(code, 1)
        self.assertIn("MISSING gated edges", out)

    def test_bad_input_exit_2(self):
        self.assertEqual(graphdiff.main(["/nonexistent/a.json", "/nonexistent/b.json"]), 2)


@unittest.skipUnless((GOLDEN_DIR / "gis_api.json").exists(), "golden graphs not generated")
class GoldenTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.golden = json.loads((GOLDEN_DIR / "gis_api.json").read_text(encoding="utf-8"))

    def test_golden_vs_itself_passes(self):
        rep = graphdiff.compare(self.golden, copy.deepcopy(self.golden))
        self.assertTrue(rep["passed"])
        self.assertFalse(rep["edges"]["missing_gated"] or rep["edges"]["extra_gated"] or rep["nodes"]["missing"])

    def test_golden_vs_itself_cli_with_bench_differences(self):
        gp = str(GOLDEN_DIR / "gis_api.json")
        r = subprocess.run([sys.executable, str(REPO / "tools" / "graphdiff.py"), gp, gp,
                            "--explained", str(REPO / "bench" / "DIFFERENCES.md")],
                           capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)

    def test_mutated_golden_fails(self):
        cand = copy.deepcopy(self.golden)
        drop = next(i for i, e in enumerate(cand["edges"]) if e["kind"] == "depends")
        del cand["edges"][drop]
        ep_node = next(n for n in cand["nodes"] if n["entrypoint"] and n["entrypoint"]["type"] == "http")
        ep_node["entrypoint"] = dict(ep_node["entrypoint"], label=ep_node["entrypoint"]["label"] + "/changed")
        rep = graphdiff.compare(self.golden, cand)
        self.assertFalse(rep["passed"])
        self.assertEqual(rep["gates"]["a_gated_edges_present"]["missing"], 1)
        self.assertEqual(rep["gates"]["b_entrypoints_identical"]["changed"], 1)
        self.assertFalse(rep["gates"]["c_resolved_internal_edges"]["passed"])


if __name__ == "__main__":
    unittest.main()
