#!/usr/bin/env python3
"""Freeze the Python analyzer's output as golden graphs for the v0.5 benchmark.

    python3 tools/make_golden.py [--corpora DIR] [--out bench/golden] [NAME ...]

NAME defaults to gis_api fastapi celery sqlalchemy; the project root analysed is DIR/NAME
(the same root the Java exporter is given). Output is deterministic: run-dependent fields
(generated_at, version, stats.ms) are removed and keys are sorted, so regenerating on an
unchanged corpus gives byte-identical files.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys
import time

REPO = pathlib.Path(__file__).resolve().parent.parent
DEFAULT_CORPORA = ("/tmp/claude-1000/-home-sushil-PycharmProjects-gis-api/"
                   "679583d0-1cbe-4a24-b9b3-6a164c92c301/scratchpad/psi/corpora")
NAMES = ["gis_api", "fastapi", "celery", "sqlalchemy"]
VOLATILE_TOP = ("generated_at", "version")
VOLATILE_STATS = ("ms",)


def normalize(graph: dict) -> dict:
    g = {k: v for k, v in graph.items() if k not in VOLATILE_TOP}
    if isinstance(g.get("stats"), dict):
        g["stats"] = {k: v for k, v in g["stats"].items() if k not in VOLATILE_STATS}
    return g


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("names", nargs="*", default=NAMES)
    ap.add_argument("--corpora", default=DEFAULT_CORPORA)
    ap.add_argument("--out", default=str(REPO / "bench" / "golden"))
    a = ap.parse_args(argv)
    sys.path.insert(0, str(REPO))
    from codemap.analyzer import build_graph  # noqa: E402  (repo-local import)

    out = pathlib.Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for name in a.names:
        root = pathlib.Path(a.corpora) / name
        if not root.is_dir():
            print(f"missing corpus {root} (run bench/corpora.sh)", file=sys.stderr)
            return 2
        t0 = time.perf_counter()
        g = normalize(build_graph(str(root)))
        dt = time.perf_counter() - t0
        path = out / f"{name}.json"
        path.write_text(json.dumps(g, indent=1, sort_keys=True, ensure_ascii=False) + "\n", encoding="utf-8")
        st = g["stats"]
        print(f"{name:11s} {st['files']:4d} files {st['nodes']:6d} nodes {st['edges']:6d} edges "
              f"{st['entrypoints']:4d} entrypoints resolved={st['calls_resolved']} "
              f"external={st['calls_external']} ({dt:.1f}s) -> {path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
