#!/usr/bin/env python3
"""Compare two codemap graph JSONs (golden vs candidate) and gate the v0.5 parity check.

    python3 tools/graphdiff.py GOLDEN.json CANDIDATE.json [--json report.json]
                               [--explained bench/DIFFERENCES.md] [--top N]

Exit code: 0 = all gates pass, 1 = a gate failed, 2 = usage / input error.

Gates (SPEC.md "v0.5 ... Benchmark / acceptance"):
  (a) every golden edge whose kind is in GATED_EDGE_KINDS is present in the candidate
      (same source, target, kind) unless explained in DIFFERENCES.md;
  (b) identical entrypoint set (node id) with identical type/label/method/path, unless explained;
  (c) candidate count of resolved internal edges (distinct gated-kind edges) >= golden.
Everything else (nodes, references/implements edges, extra edges, call-site lines, signature/doc
differences, external-call counts) is reported only.

DIFFERENCES.md format (lines inside ``` fences and anything not starting with "- " are ignored):
  - <kind> <source> -> <target>: <reason>      edge allowed to be missing from the candidate
  - entrypoint <node id>: <reason>             entrypoint allowed to be missing/extra/different
<kind>, <source>, <target> and <node id> are fnmatch patterns ("*", "?", "[...]"), e.g.
  - calls sqlalchemy.* -> sqlalchemy.util.langhelpers:*: Python resolved via a wrong re-export
A "## <project>" heading scopes the following lines to graphs whose "project" equals <project>
("## all" or no heading = every project). A line without a reason is rejected (warning).
"""
from __future__ import annotations

import argparse
import fnmatch
import json
import re
import sys
from collections import Counter

GATED_EDGE_KINDS = ("calls", "depends", "instantiates", "inherits", "includes_router", "routes")
EP_FIELDS = ("type", "label", "method", "path")
NODE_FIELDS = ("kind", "file", "line", "end_line", "name", "qualname", "module", "class_name",
               "is_async", "is_test", "package")
TEXT_FIELDS = ("signature", "doc")
OTHER_NODE_FIELDS = ("decorators", "loc")
COUNT_STATS = ("files", "nodes", "edges", "entrypoints", "calls_resolved", "calls_external",
               "calls_builtin", "calls_unknown_receiver")

_EDGE_RE = re.compile(r"^(\S+)\s+(\S+)\s+->\s+(\S+?)(?::\s+(.*?)|:?)\s*$")
_EP_RE = re.compile(r"^entrypoint\s+(\S+?)(?::\s+(.*?)|:?)\s*$")


# --------------------------------------------------------------------------- explained differences

class Explained:
    """Allow-list parsed from DIFFERENCES.md."""

    def __init__(self):
        self.edges: list[dict] = []   # {kind, source, target, reason, line, scope, hits}
        self.entrypoints: list[dict] = []  # {id, reason, line, scope, hits}
        self.warnings: list[str] = []

    @classmethod
    def parse(cls, text: str, origin: str = "DIFFERENCES.md") -> "Explained":
        ex = cls()
        scope = "all"
        in_fence = False
        for no, raw in enumerate(text.splitlines(), 1):
            line = raw.strip()
            if line.startswith("```"):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            if line.startswith("## "):
                scope = line[3:].strip() or "all"
                continue
            if not line.startswith("- "):
                continue
            body = line[2:].strip()
            m = _EP_RE.match(body)
            if m:
                if not m.group(2):
                    ex.warnings.append(f"{origin}:{no}: entrypoint entry without a reason ignored: {body}")
                    continue
                ex.entrypoints.append({"id": m.group(1), "reason": m.group(2), "line": no,
                                       "scope": scope, "hits": 0})
                continue
            m = _EDGE_RE.match(body)
            if m:
                if not m.group(4):
                    ex.warnings.append(f"{origin}:{no}: edge entry without a reason ignored: {body}")
                    continue
                ex.edges.append({"kind": m.group(1), "source": m.group(2), "target": m.group(3),
                                 "reason": m.group(4), "line": no, "scope": scope, "hits": 0})
                continue
            ex.warnings.append(f"{origin}:{no}: unrecognised entry ignored: {body}")
        return ex

    @staticmethod
    def _in_scope(entry, project) -> bool:
        return entry["scope"] in ("all", "*") or fnmatch.fnmatchcase(project or "", entry["scope"])

    def edge(self, project, source, target, kind):
        for e in self.edges:
            if (self._in_scope(e, project) and fnmatch.fnmatchcase(kind, e["kind"])
                    and fnmatch.fnmatchcase(source, e["source"]) and fnmatch.fnmatchcase(target, e["target"])):
                e["hits"] += 1
                return e
        return None

    def entrypoint(self, project, node_id):
        for e in self.entrypoints:
            if self._in_scope(e, project) and fnmatch.fnmatchcase(node_id, e["id"]):
                e["hits"] += 1
                return e
        return None

    def unused(self, project) -> list[str]:
        out = []
        for e in self.edges:
            if e["hits"] == 0 and self._in_scope(e, project):
                out.append(f"line {e['line']}: {e['kind']} {e['source']} -> {e['target']}")
        for e in self.entrypoints:
            if e["hits"] == 0 and self._in_scope(e, project):
                out.append(f"line {e['line']}: entrypoint {e['id']}")
        return out


# --------------------------------------------------------------------------- comparison

def _ws(s) -> str:
    return " ".join(str(s or "").split())


def _ep(node) -> dict | None:
    ep = node.get("entrypoint")
    if not ep:
        return None
    return {k: ep.get(k) for k in EP_FIELDS if ep.get(k) is not None}


def _edge_key(e) -> tuple:
    return (e["source"], e["target"], e["kind"])


def compare(golden: dict, cand: dict, explained: Explained | None = None) -> dict:
    """Return a JSON-able report dict; report["passed"] tells whether all gates pass."""
    explained = explained or Explained()
    project = golden.get("project") or cand.get("project")
    gn = {n["id"]: n for n in golden.get("nodes", [])}
    cn = {n["id"]: n for n in cand.get("nodes", [])}

    # ---- nodes (report only)
    missing_nodes = sorted(set(gn) - set(cn))
    extra_nodes = sorted(set(cn) - set(gn))
    field_mismatch: dict[str, list] = {}
    text_ws_only: dict[str, list] = {}
    text_diff: dict[str, list] = {}
    for nid in sorted(set(gn) & set(cn)):
        a, b = gn[nid], cn[nid]
        for f in NODE_FIELDS + OTHER_NODE_FIELDS:
            if f in a and f in b and a[f] != b[f]:
                field_mismatch.setdefault(f, []).append({"id": nid, "golden": a[f], "candidate": b[f]})
        for f in TEXT_FIELDS:
            if f in a and f in b and a[f] != b[f]:
                bucket = text_ws_only if _ws(a[f]) == _ws(b[f]) else text_diff
                bucket.setdefault(f, []).append({"id": nid, "golden": a[f], "candidate": b[f]})

    # ---- edges
    ge = {_edge_key(e): e for e in golden.get("edges", [])}
    ce = {_edge_key(e): e for e in cand.get("edges", [])}
    missing_gated, explained_missing, missing_other = [], [], []
    for k in sorted(set(ge) - set(ce)):
        src, tgt, kind = k
        item = {"source": src, "target": tgt, "kind": kind, "file": ge[k].get("file"), "line": ge[k].get("line")}
        if kind in GATED_EDGE_KINDS:
            hit = explained.edge(project, src, tgt, kind)
            if hit:
                item["reason"] = hit["reason"]
                explained_missing.append(item)
            else:
                missing_gated.append(item)
        else:
            missing_other.append(item)
    extra_gated, extra_other = [], []
    for k in sorted(set(ce) - set(ge)):
        src, tgt, kind = k
        item = {"source": src, "target": tgt, "kind": kind, "file": ce[k].get("file"), "line": ce[k].get("line")}
        (extra_gated if kind in GATED_EDGE_KINDS else extra_other).append(item)
    site_mismatch = []
    for k in sorted(set(ge) & set(ce)):
        a, b = ge[k], ce[k]
        if (a.get("file"), a.get("line")) != (b.get("file"), b.get("line")):
            site_mismatch.append({"source": k[0], "target": k[1], "kind": k[2],
                                  "golden": [a.get("file"), a.get("line")],
                                  "candidate": [b.get("file"), b.get("line")]})
    g_kinds = Counter(k[2] for k in ge)
    c_kinds = Counter(k[2] for k in ce)
    g_internal = sum(v for k, v in g_kinds.items() if k in GATED_EDGE_KINDS)
    c_internal = sum(v for k, v in c_kinds.items() if k in GATED_EDGE_KINDS)

    # ---- entrypoints
    gep = {nid: _ep(n) for nid, n in gn.items() if _ep(n)}
    cep = {nid: _ep(n) for nid, n in cn.items() if _ep(n)}
    ep_missing, ep_extra, ep_changed, ep_explained = [], [], [], []
    for nid in sorted(set(gep) | set(cep)):
        a, b = gep.get(nid), cep.get(nid)
        if a == b:
            continue
        item = {"id": nid, "golden": a, "candidate": b}
        hit = explained.entrypoint(project, nid)
        if hit:
            item["reason"] = hit["reason"]
            ep_explained.append(item)
        elif b is None:
            ep_missing.append(item)
        elif a is None:
            ep_extra.append(item)
        else:
            ep_changed.append(item)
    g_ep_types = Counter(e["type"] for e in gep.values())
    c_ep_types = Counter(e["type"] for e in cep.values())

    # ---- external calls / stats (report only)
    def ext_summary(g):
        ext = g.get("external_calls", {}) or {}
        return {"nodes_with_external": len(ext), "external_names": sum(len(v) for v in ext.values())}

    gs, cs = golden.get("stats", {}) or {}, cand.get("stats", {}) or {}
    stats = {k: {"golden": gs.get(k), "candidate": cs.get(k)} for k in COUNT_STATS if k in gs or k in cs}
    ext = {"golden": ext_summary(golden), "candidate": ext_summary(cand)}

    gates = {
        "a_gated_edges_present": {"passed": not missing_gated, "missing": len(missing_gated),
                                  "explained": len(explained_missing)},
        "b_entrypoints_identical": {"passed": not (ep_missing or ep_extra or ep_changed),
                                    "missing": len(ep_missing), "extra": len(ep_extra),
                                    "changed": len(ep_changed), "explained": len(ep_explained)},
        "c_resolved_internal_edges": {"passed": c_internal >= g_internal,
                                      "golden": g_internal, "candidate": c_internal},
    }
    return {
        "project": project,
        "passed": all(g["passed"] for g in gates.values()),
        "gates": gates,
        "nodes": {"golden": len(gn), "candidate": len(cn), "missing": missing_nodes, "extra": extra_nodes,
                  "field_mismatch": field_mismatch, "text_whitespace_only": text_ws_only,
                  "text_differs": text_diff},
        "edges": {"golden_by_kind": dict(sorted(g_kinds.items())), "candidate_by_kind": dict(sorted(c_kinds.items())),
                  "missing_gated": missing_gated, "missing_explained": explained_missing,
                  "missing_other": missing_other, "extra_gated": extra_gated, "extra_other": extra_other,
                  "call_site_mismatch": site_mismatch},
        "entrypoints": {"golden": len(gep), "candidate": len(cep),
                        "golden_by_type": dict(sorted(g_ep_types.items())),
                        "candidate_by_type": dict(sorted(c_ep_types.items())),
                        "missing": ep_missing, "extra": ep_extra, "changed": ep_changed,
                        "explained": ep_explained},
        "stats": stats,
        "external_calls": ext,
        "explained_unused": explained.unused(project),
        "warnings": list(explained.warnings),
    }


# --------------------------------------------------------------------------- output

def _fmt_edge(e) -> str:
    s = f"{e['kind']} {e['source']} -> {e['target']}"
    if e.get("file"):
        s += f"  ({e['file']}:{e.get('line')})"
    if e.get("reason"):
        s += f"  [{e['reason']}]"
    return s


def _fmt_ep(e) -> str:
    s = f"{e['id']}: golden={json.dumps(e['golden'], sort_keys=True)} candidate={json.dumps(e['candidate'], sort_keys=True)}"
    if e.get("reason"):
        s += f"  [{e['reason']}]"
    return s


def _section(lines, title, items, fmt, top, by_kind=False):
    if not items:
        return
    if by_kind:
        kinds = Counter(i["kind"] for i in items)
        title += " (" + ", ".join(f"{k}={v}" for k, v in sorted(kinds.items())) + ")"
    lines.append(f"  {title}: {len(items)}")
    for i in items[:top]:
        lines.append(f"    {fmt(i)}")
    if len(items) > top:
        lines.append(f"    ... {len(items) - top} more")


def render(rep: dict, top: int = 10) -> str:
    L: list[str] = []
    ok = lambda b: "PASS" if b else "FAIL"  # noqa: E731
    g = rep["gates"]
    L.append(f"graphdiff [{rep['project']}]: {'PASS' if rep['passed'] else 'FAIL'}")
    ga, gb, gc = g["a_gated_edges_present"], g["b_entrypoints_identical"], g["c_resolved_internal_edges"]
    L.append(f"  gate a (golden {'/'.join(GATED_EDGE_KINDS)} edges present): {ok(ga['passed'])} "
             f"- missing {ga['missing']}, explained {ga['explained']}")
    L.append(f"  gate b (entrypoints identical): {ok(gb['passed'])} - missing {gb['missing']}, "
             f"extra {gb['extra']}, changed {gb['changed']}, explained {gb['explained']}")
    L.append(f"  gate c (resolved internal edges >= golden): {ok(gc['passed'])} - "
             f"golden {gc['golden']}, candidate {gc['candidate']} ({gc['candidate'] - gc['golden']:+d})")
    n, e, ep = rep["nodes"], rep["edges"], rep["entrypoints"]
    L.append(f"nodes: golden {n['golden']}, candidate {n['candidate']}, missing {len(n['missing'])}, "
             f"extra {len(n['extra'])}")
    kinds = sorted(set(e["golden_by_kind"]) | set(e["candidate_by_kind"]))
    L.append("edges by kind (golden/candidate): " + ", ".join(
        f"{k}={e['golden_by_kind'].get(k, 0)}/{e['candidate_by_kind'].get(k, 0)}" for k in kinds))
    L.append(f"entrypoints: golden {ep['golden']}, candidate {ep['candidate']}; by type (golden/candidate): " + ", ".join(
        f"{k}={ep['golden_by_type'].get(k, 0)}/{ep['candidate_by_type'].get(k, 0)}"
        for k in sorted(set(ep["golden_by_type"]) | set(ep["candidate_by_type"]))))
    st = rep["stats"]
    if st:
        L.append("stats (golden/candidate): " + ", ".join(
            f"{k}={v['golden']}/{v['candidate']}" for k, v in st.items()))
    x = rep["external_calls"]
    L.append(f"external_calls: nodes {x['golden']['nodes_with_external']}/{x['candidate']['nodes_with_external']}, "
             f"names {x['golden']['external_names']}/{x['candidate']['external_names']}")
    L.append("details:")
    _section(L, "MISSING gated edges (gate a)", e["missing_gated"], _fmt_edge, top, by_kind=True)
    _section(L, "missing gated edges, explained", e["missing_explained"], _fmt_edge, top, by_kind=True)
    _section(L, "missing other edges (report only)", e["missing_other"], _fmt_edge, top, by_kind=True)
    _section(L, "extra gated edges (check they are right)", e["extra_gated"], _fmt_edge, top, by_kind=True)
    _section(L, "extra other edges", e["extra_other"], _fmt_edge, top, by_kind=True)
    _section(L, "call-site file/line mismatches", e["call_site_mismatch"],
             lambda i: f"{i['kind']} {i['source']} -> {i['target']}: {i['golden']} vs {i['candidate']}", top)
    _section(L, "MISSING entrypoints (gate b)", ep["missing"], _fmt_ep, top)
    _section(L, "EXTRA entrypoints (gate b)", ep["extra"], _fmt_ep, top)
    _section(L, "CHANGED entrypoints (gate b)", ep["changed"], _fmt_ep, top)
    _section(L, "entrypoint differences, explained", ep["explained"], _fmt_ep, top)
    _section(L, "missing nodes", n["missing"], str, top)
    _section(L, "extra nodes", n["extra"], str, top)
    for title, bucket in (("node field mismatch", n["field_mismatch"]),
                          ("text differs", n["text_differs"]),
                          ("whitespace-only text difference (non-blocking)", n["text_whitespace_only"])):
        for f, items in sorted(bucket.items()):
            _section(L, f"{title} [{f}]", items,
                     lambda i: f"{i['id']}: {json.dumps(i['golden'])} vs {json.dumps(i['candidate'])}", top)
    for w in rep["warnings"]:
        L.append(f"warning: {w}")
    if rep["explained_unused"]:
        _section(L, "DIFFERENCES.md entries that matched nothing (stale?)", rep["explained_unused"], str, top)
    return "\n".join(L)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Compare a candidate codemap graph against a golden one.")
    ap.add_argument("golden")
    ap.add_argument("candidate")
    ap.add_argument("--json", dest="json_out", help="write the full report as JSON here")
    ap.add_argument("--explained", help="DIFFERENCES.md allow-list of explained differences")
    ap.add_argument("--top", type=int, default=10, help="examples shown per category (default 10)")
    a = ap.parse_args(argv)
    try:
        with open(a.golden, encoding="utf-8") as f:
            golden = json.load(f)
        with open(a.candidate, encoding="utf-8") as f:
            cand = json.load(f)
        explained = Explained()
        if a.explained:
            with open(a.explained, encoding="utf-8") as f:
                explained = Explained.parse(f.read(), a.explained)
    except (OSError, ValueError) as exc:
        print(f"graphdiff: {exc}", file=sys.stderr)
        return 2
    rep = compare(golden, cand, explained)
    print(render(rep, a.top))
    if a.json_out:
        with open(a.json_out, "w", encoding="utf-8") as f:
            json.dump(rep, f, indent=1, sort_keys=True)
    return 0 if rep["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
