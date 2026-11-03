"""Static (pure ``ast``) call-graph extraction for Python projects.

Never imports the analysed code. Best-effort resolution that favours precision
over recall: an edge is only emitted when the callee resolves to a node of the
project; everything else is recorded under ``external_calls``.

Public API::

    build_graph(root, excludes=None) -> dict     # see SPEC.md "Graph JSON"
    iter_python_files(root, excludes=None) -> list[pathlib.Path]
"""

from __future__ import annotations

import ast
import builtins
import datetime as _dt
import fnmatch
import os
import pathlib
import re
import sys
import time
from typing import Any

__all__ = ["build_graph", "iter_python_files", "DEFAULT_EXCLUDES"]

DEFAULT_EXCLUDES = (
    ".git", ".hg", ".svn", ".venv", "venv", "env", ".env", "node_modules", "__pycache__",
    ".idea", ".vscode", "build", "dist", ".mypy_cache", ".pytest_cache", ".ruff_cache",
    ".tox", ".nox", ".eggs", "site-packages",
)

HTTP_METHODS = frozenset({"get", "post", "put", "patch", "delete", "options", "head", "trace"})
ROUTE_ATTRS = HTTP_METHODS | {"api_route", "route", "websocket", "websocket_route"}
DEPENDS_NAMES = frozenset({"Depends", "Security"})
CELERY_CALL_ATTRS = frozenset({"delay", "apply_async", "s", "si", "signature", "map", "starmap"})
APP_TYPES = frozenset({"FastAPI", "Flask", "Starlette", "Quart", "Sanic", "Celery"})
APP_FACTORY_RE = re.compile(r"create_\w*app\w*$")
WRAPPER_ANNOTATIONS = frozenset({"Optional", "Annotated", "ClassVar", "Final", "Required", "NotRequired",
                                 "ReadOnly"})
BUILTIN_NAMES = frozenset(dir(builtins))
BUILTIN_TYPES = frozenset(n for n in BUILTIN_NAMES if isinstance(getattr(builtins, n), type))
MAX_EXTERNAL = 50
# Decorators that register a function with a framework to be invoked at runtime (not routes/tasks).
HOOK_ATTRS = frozenset({"exception_handler", "errorhandler", "middleware", "on_event", "listens_for",
                        "before_request", "after_request", "teardown_request", "before_app_request",
                        "after_app_request", "app_errorhandler", "on_after_configure", "on_after_finalize"})
# Builtins whose class arguments are type checks, not callbacks.
_NO_REF_CALLS = frozenset({"isinstance", "issubclass", "type", "getattr", "setattr", "hasattr", "super",
                           "cast", "TypeVar", "NewType"})
_TEST_DIRS = frozenset({"tests", "test", "testing"})


def _is_test_file(rel: str) -> bool:
    parts = rel.split("/")
    base = parts[-1]
    return (any(p in _TEST_DIRS for p in parts[:-1]) or base.startswith("test_") or base.endswith("_test.py")
            or base == "conftest.py")


# --------------------------------------------------------------------------- files

def _is_excluded(rel: str, name: str, patterns: list[str]) -> bool:
    for pat in patterns:
        p = pat.strip().rstrip("/")
        if not p:
            continue
        if fnmatch.fnmatch(rel, p) or fnmatch.fnmatch(name, p) or rel.startswith(p + "/"):
            return True
    return False


def iter_python_files(root, excludes=None) -> list[pathlib.Path]:
    """All ``*.py`` files under *root* (sorted), honouring default + user excludes."""
    root_p = pathlib.Path(root).resolve()
    patterns = list(excludes or [])
    out: list[pathlib.Path] = []
    for dirpath, dirnames, filenames in os.walk(root_p):
        rel_dir = os.path.relpath(dirpath, root_p)
        rel_dir = "" if rel_dir == "." else rel_dir.replace(os.sep, "/")
        keep = []
        for d in sorted(dirnames):
            rp = f"{rel_dir}/{d}" if rel_dir else d
            if d in DEFAULT_EXCLUDES or d.endswith(".egg-info") or _is_excluded(rp, d, patterns):
                continue
            keep.append(d)
        dirnames[:] = keep
        for f in filenames:
            if not f.endswith(".py"):
                continue
            rp = f"{rel_dir}/{f}" if rel_dir else f
            if _is_excluded(rp, f, patterns):
                continue
            out.append(root_p / rp)
    out.sort()
    return out


# --------------------------------------------------------------------------- model

class Scope:
    """A name-binding scope (module, class or function body)."""

    __slots__ = ("kind", "mod", "parent", "bindings", "owner", "cls")

    def __init__(self, kind: str, mod: str, parent: "Scope | None", owner: str):
        self.kind = kind          # "module" | "class" | "function"
        self.mod = mod
        self.parent = parent      # lexical parent (class scopes are skipped for functions)
        self.bindings: dict[str, list[tuple]] = {}
        self.owner = owner        # node id owning the scope
        self.cls: str | None = None  # for method scopes: owning class id

    def add(self, name: str, entry: tuple) -> None:
        self.bindings.setdefault(name, []).append(entry)

    @property
    def key_owner(self) -> str:
        return "" if self.kind == "module" else self.owner


class ModInfo:
    __slots__ = ("name", "file", "tree", "lines", "scope", "stars", "is_pkg", "is_main", "node_id")

    def __init__(self, name, file, tree, lines, is_pkg):
        self.name = name
        self.file = file
        self.tree = tree
        self.lines = lines
        self.is_pkg = is_pkg
        self.stars: list[str] = []
        self.is_main = False
        self.node_id = f"{name}:{name}"
        self.scope = Scope("module", name, None, self.node_id)


class DefInfo:
    """Function, method or class."""

    __slots__ = ("id", "kind", "node", "mod", "qualname", "scope", "def_scope", "cls",
                 "members", "attrs", "bases")

    def __init__(self, nid, kind, node, mod, qualname, def_scope):
        self.id = nid
        self.kind = kind            # "function" | "method" | "class"
        self.node = node
        self.mod = mod
        self.qualname = qualname
        self.def_scope = def_scope  # scope in which the def statement executes
        self.scope: Scope | None = None  # body scope
        self.cls: str | None = None      # owning class id for methods
        # class only
        self.members: dict[str, str] = {}
        self.attrs: dict[str, list[tuple[tuple, Scope]]] = {}
        self.bases: list[ast.expr] = []


# --------------------------------------------------------------------------- helpers

def _dotted(expr: ast.AST) -> str:
    if isinstance(expr, ast.Name):
        return expr.id
    if isinstance(expr, ast.Attribute):
        return f"{_dotted(expr.value)}.{expr.attr}"
    if isinstance(expr, ast.Call):
        return f"{_dotted(expr.func)}()"
    if isinstance(expr, ast.Subscript):
        return f"{_dotted(expr.value)}[]"
    if isinstance(expr, ast.Constant):
        return type(expr.value).__name__
    return "<expr>"


def _last_name(expr: ast.AST) -> str | None:
    if isinstance(expr, ast.Name):
        return expr.id
    if isinstance(expr, ast.Attribute):
        return expr.attr
    return None


def _str_const(expr) -> str | None:
    if isinstance(expr, ast.Constant) and isinstance(expr.value, str):
        return expr.value
    return None


def _kw(call: ast.Call, name: str):
    for k in call.keywords:
        if k.arg == name:
            return k.value
    return None


def _safe_unparse(node) -> str:
    try:
        return ast.unparse(node)
    except Exception:  # pragma: no cover
        return "?"


def _format_signature(fn: ast.FunctionDef | ast.AsyncFunctionDef) -> str:
    a = fn.args
    parts: list[str] = []

    def one(arg: ast.arg, default=None, star=""):
        s = star + arg.arg
        if arg.annotation is not None:
            s += f": {_safe_unparse(arg.annotation)}"
        if default is not None:
            s += f" = {_safe_unparse(default)}" if arg.annotation is not None else f"={_safe_unparse(default)}"
        return s

    positional = list(a.posonlyargs) + list(a.args)
    defaults = [None] * (len(positional) - len(a.defaults)) + list(a.defaults)
    for i, (arg, d) in enumerate(zip(positional, defaults)):
        parts.append(one(arg, d))
        if a.posonlyargs and i == len(a.posonlyargs) - 1:
            parts.append("/")
    if a.vararg:
        parts.append(one(a.vararg, star="*"))
    elif a.kwonlyargs:
        parts.append("*")
    for arg, d in zip(a.kwonlyargs, a.kw_defaults):
        parts.append(one(arg, d))
    if a.kwarg:
        parts.append(one(a.kwarg, star="**"))
    sig = f"({', '.join(parts)})"
    if fn.returns is not None:
        sig += f" -> {_safe_unparse(fn.returns)}"
    return sig


def _first_doc_line(node) -> str:
    try:
        doc = ast.get_docstring(node)
    except Exception:  # pragma: no cover
        return ""
    if not doc:
        return ""
    for line in doc.strip().splitlines():
        if line.strip():
            return line.strip()
    return ""


def _is_main_guard(test: ast.expr) -> bool:
    if not isinstance(test, ast.Compare) or len(test.ops) != 1 or not isinstance(test.ops[0], ast.Eq):
        return False
    left, right = test.left, test.comparators[0]
    for a, b in ((left, right), (right, left)):
        if isinstance(a, ast.Name) and a.id == "__name__" and _str_const(b) == "__main__":
            return True
    return False


def _iter_calls(roots):
    """Pre-order walk yielding Call nodes; does not enter nested def/class statements."""
    stack = list(reversed(list(roots)))
    while stack:
        n = stack.pop()
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        if isinstance(n, ast.Call):
            yield n
        stack.extend(reversed(list(ast.iter_child_nodes(n))))


def _join_paths(*parts: str) -> str:
    s = "".join(p for p in parts if p)
    s = re.sub(r"/{2,}", "/", s)
    if not s.startswith("/"):
        s = "/" + s
    return s


# --------------------------------------------------------------------------- analyzer

class _Analyzer:
    def __init__(self, root: pathlib.Path, excludes):
        self.root = root
        self.project = root.name
        self.excludes = excludes
        self.mods: dict[str, ModInfo] = {}
        self.alias: dict[str, str] = {}
        self.defs: dict[str, DefInfo] = {}
        self.errors: list[dict] = []
        self.nodes: dict[str, dict] = {}
        self.edges: dict[tuple, dict] = {}
        self.external: dict[str, list[str]] = {}
        self._external_seen: dict[str, set] = {}
        self.memo: dict[tuple, Any] = {}
        self.inprog: set = set()
        self.pending_routes: list[tuple] = []
        self.includes: list[tuple] = []
        self.own_prefix: dict[tuple, str] = {}
        self.n_resolved = 0
        self.n_unresolved = 0
        self.n_builtin = 0
        self.n_unknown = 0  # unresolved calls whose receiver is not a known library object
        self.top_packages: set[str] = set()
        self.n_files = 0

    # ------------------------------------------------------------------ pass 0: files
    def load(self) -> None:
        files = iter_python_files(self.root, self.excludes)
        self.n_files = len(files)
        rels = [p.relative_to(self.root).as_posix() for p in files]
        pkg_dirs = {r.rsplit("/", 1)[0] if "/" in r else "" for r in rels if r.endswith("__init__.py")}
        alias_candidates: dict[str, list[str]] = {}
        for path, rel in zip(files, rels):
            parts = rel[:-3].split("/")
            is_pkg = parts[-1] == "__init__"
            if is_pkg:
                parts = parts[:-1]
            name = ".".join(parts) or self.project
            try:
                src = path.read_bytes()
                tree = ast.parse(src, filename=rel)
            except (SyntaxError, ValueError, UnicodeDecodeError, OSError, RecursionError) as e:
                msg = f"{type(e).__name__}: {e}"
                self.errors.append({"file": rel, "message": msg})
                continue
            nlines = src.count(b"\n") + (0 if src.endswith(b"\n") or not src else 1)
            if name in self.mods:  # a.py next to a/__init__.py: Python imports the package
                prev = self.mods[name]
                shadowed = rel if prev.is_pkg or not is_pkg else prev.file
                self.errors.append({"file": shadowed, "message": f"module {name} is shadowed by its package; skipped"})
                if prev.is_pkg or not is_pkg:
                    continue
            self.mods[name] = ModInfo(name, rel, tree, max(nlines, 1), is_pkg)
            # import-name alias: strip leading non-package directories (src/ layout, scripts/ ...)
            dirs = rel.split("/")[:-1]
            k = len(dirs)
            while k > 0 and "/".join(dirs[:k]) in pkg_dirs:
                k -= 1
            imp_parts = (dirs[k:] + ([] if is_pkg else [rel.rsplit("/", 1)[-1][:-3]]))
            imp = ".".join(imp_parts)
            if imp and imp != name:
                lst = alias_candidates.setdefault(imp, [])
                if name not in lst:
                    lst.append(name)
        self.top_packages = {m.split(".", 1)[0] for m in self.mods} | {a.split(".", 1)[0] for a in alias_candidates}
        for imp, names in alias_candidates.items():
            if len(names) == 1 and imp not in self.mods:
                self.alias[imp] = names[0]

    def resolve_mod(self, name: str) -> str | None:
        if name in self.mods:
            return name
        return self.alias.get(name)

    # ------------------------------------------------------------------ pass 1: collect
    def collect(self) -> None:
        for mi in self.mods.values():
            tree = mi.tree
            self.nodes[mi.node_id] = {
                "id": mi.node_id, "kind": "module", "name": mi.name.rsplit(".", 1)[-1],
                "qualname": mi.name, "module": mi.name, "file": mi.file,
                "line": 1, "end_line": mi.lines, "signature": "",
                "doc": _first_doc_line(tree), "decorators": [], "is_async": False,
                "class_name": None, "package": mi.name.rsplit(".", 1)[0] if "." in mi.name else "",
                "loc": mi.lines, "entrypoint": None, "is_test": _is_test_file(mi.file),
            }
            self._collect_block(mi, tree.body, mi.scope, "", None, top=True)
            if mi.is_main:
                self.nodes[mi.node_id]["entrypoint"] = {"type": "main", "label": f"python -m {mi.name}"}

    def _abs_import(self, mi: ModInfo, module: str | None, level: int) -> str:
        if not level:
            return module or ""
        base = mi.name.split(".") if mi.is_pkg else mi.name.split(".")[:-1]
        if level > 1:
            base = base[: len(base) - (level - 1)] if level - 1 <= len(base) else []
        if module:
            base = base + module.split(".")
        return ".".join(base)

    def _collect_block(self, mi, stmts, scope: Scope, prefix: str, cls: DefInfo | None,
                       top=False, self_name: str | None = None) -> None:
        for st in stmts:
            self._collect_stmt(mi, st, scope, prefix, cls, top, self_name)

    def _bind_target(self, scope: Scope, target, entry, cls, self_name):
        if isinstance(target, ast.Name):
            scope.add(target.id, entry)
            if cls is not None and scope.kind == "class":
                cls.attrs.setdefault(target.id, []).append((entry, scope))
        elif isinstance(target, (ast.Tuple, ast.List)):
            for elt in target.elts:
                self._bind_target(scope, elt, ("unknown",), cls, self_name)
        elif isinstance(target, ast.Starred):
            self._bind_target(scope, target.value, ("unknown",), cls, self_name)
        elif (isinstance(target, ast.Attribute) and self_name and isinstance(target.value, ast.Name)
              and target.value.id == self_name and scope.cls):
            owner = self.defs.get(scope.cls)
            if owner is not None:
                owner.attrs.setdefault(target.attr, []).append((entry, scope))

    def _collect_stmt(self, mi, st, scope: Scope, prefix, cls, top, self_name):
        if isinstance(st, (ast.FunctionDef, ast.AsyncFunctionDef)):
            self._collect_func(mi, st, scope, prefix, cls)
        elif isinstance(st, ast.ClassDef):
            self._collect_class(mi, st, scope, prefix, cls)
        elif isinstance(st, ast.Import):
            for a in st.names:
                if a.asname:
                    scope.add(a.asname, ("import", a.name))
                else:
                    scope.add(a.name.split(".")[0], ("import", a.name.split(".")[0]))
        elif isinstance(st, ast.ImportFrom):
            src = self._abs_import(mi, st.module, st.level or 0)
            for a in st.names:
                if a.name == "*":
                    if scope.kind == "module":
                        m = self.resolve_mod(src)
                        if m:
                            mi.stars.append(m)
                    continue
                scope.add(a.asname or a.name, ("from", src, a.name))
        elif isinstance(st, ast.Assign):
            for t in st.targets:
                self._bind_target(scope, t, ("assign", st.value), cls, self_name)
                if isinstance(t, ast.Name) and isinstance(st.value, ast.Call):
                    pre = _kw(st.value, "prefix") or _kw(st.value, "url_prefix")
                    pre_s = _str_const(pre) if pre is not None else None
                    if pre_s is not None:
                        self.own_prefix[(scope.mod, scope.key_owner, t.id)] = pre_s
        elif isinstance(st, ast.AnnAssign):
            self._bind_target(scope, st.target, ("ann", st.annotation), cls, self_name)
            if st.value is not None:
                self._bind_target(scope, st.target, ("assign", st.value), cls, self_name)
        elif isinstance(st, ast.AugAssign):
            if isinstance(st.target, ast.Name) and st.target.id not in scope.bindings:
                scope.add(st.target.id, ("unknown",))
        elif isinstance(st, (ast.For, ast.AsyncFor)):
            self._bind_target(scope, st.target, ("unknown",), cls, self_name)
            self._collect_block(mi, st.body, scope, prefix, cls, top, self_name)
            self._collect_block(mi, st.orelse, scope, prefix, cls, top, self_name)
        elif isinstance(st, (ast.With, ast.AsyncWith)):
            for item in st.items:
                if item.optional_vars is not None:
                    self._bind_target(scope, item.optional_vars, ("assign", item.context_expr), cls, self_name)
            self._collect_block(mi, st.body, scope, prefix, cls, top, self_name)
        elif isinstance(st, ast.If):
            if top and _is_main_guard(st.test):
                mi.is_main = True
            self._collect_block(mi, st.body, scope, prefix, cls, top, self_name)
            self._collect_block(mi, st.orelse, scope, prefix, cls, top, self_name)
        elif isinstance(st, ast.While):
            self._collect_block(mi, st.body, scope, prefix, cls, top, self_name)
            self._collect_block(mi, st.orelse, scope, prefix, cls, top, self_name)
        elif isinstance(st, ast.Try) or type(st).__name__ == "TryStar":
            self._collect_block(mi, st.body, scope, prefix, cls, top, self_name)
            for h in st.handlers:
                if h.name:
                    scope.add(h.name, ("unknown",))
                self._collect_block(mi, h.body, scope, prefix, cls, top, self_name)
            self._collect_block(mi, st.orelse, scope, prefix, cls, top, self_name)
            self._collect_block(mi, st.finalbody, scope, prefix, cls, top, self_name)
        elif isinstance(st, ast.Match):
            for case in st.cases:
                self._collect_block(mi, case.body, scope, prefix, cls, top, self_name)

    def _new_node(self, mi: ModInfo, node, kind: str, qualname: str, cls: DefInfo | None, signature: str):
        nid = f"{mi.name}:{qualname}"
        if nid in self.nodes:  # redefinition (e.g. if/else variants): keep first
            return None
        decorators = [_safe_unparse(d) for d in node.decorator_list]
        end = getattr(node, "end_lineno", None) or node.lineno
        self.nodes[nid] = {
            "id": nid, "kind": kind, "name": node.name, "qualname": qualname,
            "module": mi.name, "file": mi.file, "line": node.lineno, "end_line": end,
            "signature": signature, "doc": _first_doc_line(node), "decorators": decorators,
            "is_async": isinstance(node, ast.AsyncFunctionDef),
            "class_name": cls.qualname if cls is not None and kind == "method" else None,
            "package": mi.name.rsplit(".", 1)[0] if "." in mi.name else "",
            "loc": end - node.lineno + 1, "entrypoint": None, "is_test": _is_test_file(mi.file),
        }
        return nid

    def _collect_func(self, mi, fn, scope: Scope, prefix, cls):
        qual = prefix + fn.name
        kind = "method" if scope.kind == "class" else "function"
        nid = self._new_node(mi, fn, kind, qual, cls, _format_signature(fn))
        if nid is None:
            return
        di = DefInfo(nid, kind, fn, mi.name, qual, scope)
        self.defs[nid] = di
        scope.add(fn.name, ("def", nid))
        if kind == "method" and cls is not None:
            cls.members.setdefault(fn.name, nid)
            di.cls = cls.id
        parent = scope.parent if scope.kind == "class" else scope
        fs = Scope("function", mi.name, parent, nid)
        di.scope = fs
        # parameters
        a = fn.args
        params = list(a.posonlyargs) + list(a.args)
        self_name = None
        is_static = any(_last_name(d) == "staticmethod" for d in fn.decorator_list)
        is_classm = any(_last_name(d) == "classmethod" for d in fn.decorator_list)
        if kind == "method" and cls is not None:
            fs.cls = cls.id
            if params and not is_static:
                first = params[0]
                if first.annotation is None:
                    fs.add(first.arg, ("special", "cls" if is_classm else "self", cls.id))
                    params = params[1:]
                if not is_classm:
                    self_name = first.arg
        elif scope.kind == "function" and scope.cls:
            fs.cls = scope.cls  # closures inside methods
        for arg in params + list(a.kwonlyargs):
            fs.add(arg.arg, ("param", arg.annotation))
        if a.vararg:
            fs.add(a.vararg.arg, ("unknown",))
        if a.kwarg:
            fs.add(a.kwarg.arg, ("unknown",))
        self._collect_block(mi, fn.body, fs, qual + ".<locals>.", cls if kind == "method" else None,
                            self_name=self_name)
        self._detect_entrypoint(mi, di, fn, scope)

    def _collect_class(self, mi, node: ast.ClassDef, scope: Scope, prefix, outer_cls):
        qual = prefix + node.name
        bases = ", ".join(_safe_unparse(b) for b in node.bases)
        nid = self._new_node(mi, node, "class", qual, None, f"({bases})" if bases else "")
        if nid is None:
            return
        di = DefInfo(nid, "class", node, mi.name, qual, scope)
        di.bases = list(node.bases)
        self.defs[nid] = di
        scope.add(node.name, ("def", nid))
        if scope.kind == "class" and outer_cls is not None:
            outer_cls.members.setdefault(node.name, nid)
        parent = scope.parent if scope.kind == "class" else scope
        cs = Scope("class", mi.name, parent, nid)
        di.scope = cs
        self._collect_block(mi, node.body, cs, qual + ".", di)

    # ------------------------------------------------------------------ entrypoints
    def _detect_entrypoint(self, mi, di: DefInfo, fn, scope: Scope) -> None:
        node = self.nodes[di.id]
        for d in fn.decorator_list:
            func = d.func if isinstance(d, ast.Call) else d
            last = _last_name(func)
            if last is None:
                continue
            if isinstance(d, ast.Call) and isinstance(func, ast.Attribute) and last in ROUTE_ATTRS:
                path_expr = d.args[0] if d.args else (_kw(d, "path") or _kw(d, "rule"))
                path = _str_const(path_expr) if path_expr is not None else None
                if path is None or not (path == "" or path.startswith("/")):
                    continue
                if last.startswith("websocket"):
                    etype, methods = "websocket", ["WEBSOCKET"]
                elif last in HTTP_METHODS:
                    etype, methods = "http", [last.upper()]
                else:
                    etype = "http"
                    m = _kw(d, "methods")
                    methods = []
                    if isinstance(m, (ast.List, ast.Tuple, ast.Set)):
                        methods = [s.upper() for s in (_str_const(e) for e in m.elts) if s]
                    methods = methods or ["GET"]
                self.pending_routes.append((di.id, func.value, scope, etype, methods, path))
                node["entrypoint"] = {"type": etype, "label": "", "method": ",".join(methods), "path": path}
                return
            if last == "task" and isinstance(func, ast.Attribute) or last == "shared_task":
                label = None
                if isinstance(d, ast.Call):
                    label = _str_const(_kw(d, "name")) if _kw(d, "name") is not None else None
                node["entrypoint"] = {"type": "celery_task", "label": label or di.qualname}
                return
            if last in ("command", "group") and (isinstance(func, ast.Attribute) or isinstance(d, ast.Call)):
                label = None
                if isinstance(d, ast.Call):
                    label = _str_const(d.args[0]) if d.args else None
                    if label is None and _kw(d, "name") is not None:
                        label = _str_const(_kw(d, "name"))
                node["entrypoint"] = {"type": "cli", "label": label or fn.name.replace("_", "-")}
                return
            if last in HOOK_ATTRS and (isinstance(func, ast.Attribute) or last == "listens_for"):
                arg = ""
                if isinstance(d, ast.Call) and d.args:
                    arg = _str_const(d.args[0]) or _safe_unparse(d.args[0])
                    if last == "listens_for" and len(d.args) > 1:
                        arg = f"{arg} {_str_const(d.args[1]) or _safe_unparse(d.args[1])}"
                node["entrypoint"] = {"type": "hook", "label": f"{last} {arg}".strip()[:80]}
                return
        if di.kind == "function" and scope.kind == "module":
            ret = _last_name(fn.returns) if fn.returns is not None else None
            if isinstance(fn.returns, ast.Constant) and isinstance(fn.returns.value, str):
                ret = fn.returns.value.rsplit(".", 1)[-1]
            if APP_FACTORY_RE.match(fn.name) or fn.name == "create_app" or ret in APP_TYPES:
                label = f"{fn.name}()" + (f" → {ret}" if ret else "")
                node["entrypoint"] = {"type": "app_factory", "label": label}

    # ------------------------------------------------------------------ resolution
    def lookup_name(self, scope: Scope | None, name: str):
        s = scope
        while s is not None:
            if name in s.bindings:
                return self.eval_binding(s, name)
            s = s.parent
        if name in BUILTIN_NAMES:
            return ("builtin", name)
        return None

    def eval_binding(self, scope: Scope, name: str):
        key = ("b", id(scope), name)
        if key in self.memo:
            return self.memo[key]
        if key in self.inprog:
            return None
        self.inprog.add(key)
        try:
            entries = scope.bindings[name]
            prio = {"def": 0, "import": 1, "from": 1, "special": 1, "param": 2, "ann": 3, "assign": 4, "unknown": 5}
            res = None
            for e in sorted(entries, key=lambda e: prio.get(e[0], 9)):
                res = self.eval_entry(scope, e)
                if res is not None:
                    break
        finally:
            self.inprog.discard(key)
        self.memo[key] = res
        return res

    def eval_entry(self, scope: Scope, e: tuple):
        k = e[0]
        if k == "def":
            return ("node", e[1])
        if k == "import":
            m = self.resolve_mod(e[1])
            return ("mod", m) if m else ("ext", e[1])
        if k == "from":
            return self.resolve_from(e[1], e[2])
        if k == "special":
            return ("node", e[2]) if e[1] == "cls" else ("inst", e[2])
        if k == "param":
            if e[1] is None:
                return None
            return self.ann_type(e[1], scope.parent or scope)
        if k == "ann":
            return self.ann_type(e[1], scope if scope.kind != "function" else scope)
        if k == "assign":
            return self.ev(e[1], scope)
        return None

    def resolve_from(self, src: str, name: str):
        m = self.resolve_mod(src) if src else None
        if m:
            t = self.lookup_module(m, name)
            if t is not None:
                return t
        sub = self.resolve_mod(f"{src}.{name}" if src else name)
        if sub:
            return ("mod", sub)
        return ("ext", f"{src}.{name}" if src else name)

    def lookup_module(self, m: str, name: str, depth: int = 0):
        key = ("m", m, name)
        if key in self.memo:
            return self.memo[key]
        if key in self.inprog or depth > 20:
            return None
        self.inprog.add(key)
        res = None
        try:
            mi = self.mods[m]
            if name in mi.scope.bindings:
                res = self.eval_binding(mi.scope, name)
            else:
                sub = self.resolve_mod(f"{m}.{name}")
                if sub:
                    res = ("mod", sub)
                elif not name.startswith("_"):
                    for star in mi.stars:
                        res = self.lookup_module(star, name, depth + 1)
                        if res is not None:
                            break
        finally:
            self.inprog.discard(key)
        self.memo[key] = res
        return res

    def ev(self, expr, scope: Scope):
        if isinstance(expr, ast.Name):
            return self.lookup_name(scope, expr.id)
        if isinstance(expr, ast.Attribute):
            return self.getattr_t(self.ev(expr.value, scope), expr.attr)
        if isinstance(expr, ast.Call):
            f = expr.func
            if isinstance(f, ast.Name) and f.id == "super" and scope.cls and "super" not in scope.bindings:
                return ("super", scope.cls)
            return self.call_result(self.ev(f, scope))
        if isinstance(expr, ast.Await):
            return self.ev(expr.value, scope)
        if isinstance(expr, ast.NamedExpr):
            return self.ev(expr.value, scope)
        return None

    def _qual_dotted(self, nid: str) -> str:
        d = self.defs.get(nid)
        if d is None:
            return nid.replace(":", ".")
        return f"{d.mod}.{d.qualname}"

    def getattr_t(self, t, attr: str):
        if t is None:
            return None
        k = t[0]
        if k == "mod":
            r = self.lookup_module(t[1], attr)
            return r if r is not None else ("ext", f"{t[1]}.{attr}")
        if k in ("ext", "extinst"):
            return ("ext", f"{t[1]}.{attr}")
        if k == "builtin":
            return ("ext", f"{t[1]}.{attr}") if t[1] in BUILTIN_TYPES else None
        if k == "node":
            d = self.defs.get(t[1])
            if d is None:
                return None
            if d.kind == "class":
                r = self.class_attr(d.id, attr, skip_self=False)
                return r if r is not None else ("ext", f"{self._qual_dotted(d.id)}.{attr}")
            if attr in CELERY_CALL_ATTRS:
                return ("task", d.id)
            return ("ext", f"{self._qual_dotted(d.id)}.{attr}")
        if k == "inst":
            r = self.class_attr(t[1], attr, skip_self=False)
            return r if r is not None else ("ext", f"{self._qual_dotted(t[1])}.{attr}")
        if k == "super":
            return self.class_attr(t[1], attr, skip_self=True)
        return None

    def mro(self, cid: str) -> list[str]:
        key = ("mro", cid)
        if key in self.memo:
            return self.memo[key]
        if key in self.inprog:
            return [cid]
        self.inprog.add(key)
        out = [cid]
        try:
            d = self.defs[cid]
            for b in d.bases:
                expr = b.value if isinstance(b, ast.Subscript) else b
                t = self.ev(expr, d.def_scope)
                if t and t[0] == "node" and t[1] in self.defs and self.defs[t[1]].kind == "class":
                    for x in self.mro(t[1]):
                        if x not in out:
                            out.append(x)
        finally:
            self.inprog.discard(key)
        self.memo[key] = out
        return out

    def class_attr(self, cid: str, attr: str, skip_self: bool):
        chain = self.mro(cid)
        if skip_self:
            chain = chain[1:]
        for c in chain:
            d = self.defs[c]
            if attr in d.members:
                return ("node", d.members[attr])
            if attr in d.attrs:
                key = ("attr", c, attr)
                if key in self.memo:
                    return self.memo[key]
                if key in self.inprog:
                    return None
                self.inprog.add(key)
                res = None
                try:
                    entries = sorted(d.attrs[attr], key=lambda es: 0 if es[0][0] == "ann" else 1)
                    for entry, scope in entries:
                        res = self.eval_entry(scope, entry)
                        if res is not None:
                            break
                finally:
                    self.inprog.discard(key)
                self.memo[key] = res
                return res
        return None

    def call_result(self, t):
        if t is None:
            return None
        k = t[0]
        if k == "node":
            d = self.defs.get(t[1])
            if d is None:
                return None
            if d.kind == "class":
                return ("inst", d.id)
            return self.func_return(d)
        if k in ("ext", "extinst"):
            last = t[1].rsplit(".", 1)[-1]
            return ("extinst", t[1] if k == "ext" and last[:1].isupper() else t[1] + "()")
        if k == "builtin":
            return ("extinst", t[1]) if t[1] in BUILTIN_TYPES else None
        if k == "inst":
            m = self.class_attr(t[1], "__call__", False)
            if m and m[0] == "node":
                return self.call_result(m)
        return None

    def func_return(self, d: DefInfo):
        key = ("ret", d.id)
        if key in self.memo:
            return self.memo[key]
        if key in self.inprog:
            return None
        self.inprog.add(key)
        res = None
        try:
            fn = d.node
            if fn.returns is not None:
                res = self.ann_type(fn.returns, d.scope.parent or d.def_scope)
                if res is not None and res[0] not in ("inst", "extinst", "node"):
                    res = None
            if res is None:
                for st in _iter_returns(fn.body):
                    if st.value is None:
                        continue
                    r = self.ev(st.value, d.scope)
                    if r is not None and r[0] in ("inst", "extinst", "node"):
                        res = r
                        break
        finally:
            self.inprog.discard(key)
        self.memo[key] = res
        return res

    def ann_type(self, expr, scope: Scope):
        if expr is None:
            return None
        if isinstance(expr, ast.Constant):
            if isinstance(expr.value, str):
                try:
                    inner = ast.parse(expr.value.strip(), mode="eval").body
                except SyntaxError:
                    return None
                return self.ann_type(inner, scope)
            return None
        if isinstance(expr, ast.BinOp) and isinstance(expr.op, ast.BitOr):
            return self.ann_type(expr.left, scope) or self.ann_type(expr.right, scope)
        if isinstance(expr, ast.Subscript):
            base = _last_name(expr.value)
            sl = expr.slice
            elts = sl.elts if isinstance(sl, ast.Tuple) else [sl]
            if base in WRAPPER_ANNOTATIONS:
                return self.ann_type(elts[0], scope) if elts else None
            if base == "Union":
                for e in elts:
                    r = self.ann_type(e, scope)
                    if r is not None:
                        return r
                return None
            if base in ("type", "Type") and elts:
                r = self.ann_type(elts[0], scope)
                return ("node", r[1]) if r and r[0] == "inst" else None
            return None
        if isinstance(expr, (ast.Name, ast.Attribute)):
            t = self.ev(expr, scope)
            if t is None:
                return None
            if t[0] == "node" and self.defs.get(t[1]) is not None and self.defs[t[1]].kind == "class":
                return ("inst", t[1])
            if t[0] == "ext":
                return ("extinst", t[1])
            if t[0] == "builtin" and t[1] in BUILTIN_TYPES:
                return ("extinst", t[1])
            return None
        return None

    # ------------------------------------------------------------------ pass 2: calls
    def add_edge(self, src: str, tgt: str, kind: str, file: str, line: int) -> None:
        key = (src, tgt, kind)
        e = self.edges.get(key)
        if e is None or line < e["line"]:
            self.edges[key] = {"source": src, "target": tgt, "kind": kind, "file": file, "line": line}

    def add_external(self, src: str, name: str) -> None:
        seen = self._external_seen.setdefault(src, set())
        if name in seen:
            return
        seen.add(name)
        lst = self.external.setdefault(src, [])
        if len(lst) < MAX_EXTERNAL:
            lst.append(name)

    def resolve_calls(self) -> None:
        for mi in self.mods.values():
            try:
                body = [s for s in mi.tree.body]
                self._unit(mi.node_id, mi.file, mi.scope, body)
            except RecursionError:
                self.errors.append({"file": mi.file, "message": "RecursionError while resolving module body"})
        for d in list(self.defs.values()):
            mi = self.mods[d.mod]
            try:
                self._def_unit(mi, d)
            except RecursionError:
                self.errors.append({"file": mi.file, "message": f"RecursionError while resolving {d.qualname}"})

    def _def_unit(self, mi: ModInfo, d: DefInfo) -> None:
        node = d.node
        # decorators execute in the defining scope but count for the decorated def
        for dec in node.decorator_list:
            self._decorator(d.id, mi.file, d.def_scope, dec)
        # A nested def registered by a decorator inside its parent (e.g. @app.exception_handler(X)) is
        # wired up by the parent, so make it reachable from there.
        if d.def_scope.kind == "function" and node.decorator_list and d.def_scope.owner in self.defs:
            self.add_edge(d.def_scope.owner, d.id, "references", mi.file, node.decorator_list[0].lineno)
        if d.kind == "class":
            for b in d.bases:
                expr = b.value if isinstance(b, ast.Subscript) else b
                t = self.ev(expr, d.def_scope)
                if t and t[0] == "node" and self.defs.get(t[1]) and self.defs[t[1]].kind == "class":
                    self.add_edge(d.id, t[1], "inherits", mi.file, node.lineno)
            self._unit(d.id, mi.file, d.def_scope, list(node.bases) + [k.value for k in node.keywords])
            self._unit(d.id, mi.file, d.scope, node.body)
            return
        a = node.args
        defaults = [x for x in list(a.defaults) + list(a.kw_defaults) if x is not None]
        self._unit(d.id, mi.file, d.def_scope, defaults)
        # Depends() without argument -> the annotated class; Annotated[T, Depends(...)]
        positional = list(a.posonlyargs) + list(a.args)
        pdefs = [None] * (len(positional) - len(a.defaults)) + list(a.defaults)
        for arg, dflt in list(zip(positional, pdefs)) + list(zip(a.kwonlyargs, a.kw_defaults)):
            ann = arg.annotation
            if isinstance(dflt, ast.Call) and self._is_depends(dflt) and not self._depends_arg(dflt):
                self._depends_on_annotation(d.id, mi.file, d.def_scope, ann, dflt.lineno)
            if ann is not None:
                for c in _iter_calls([ann]):
                    if self._is_depends(c):
                        if self._depends_arg(c) is not None:
                            self._handle_depends(d.id, mi.file, d.def_scope, c)
                        else:
                            self._depends_on_annotation(d.id, mi.file, d.def_scope, ann, c.lineno)
        self._unit(d.id, mi.file, d.scope, node.body)

    def _decorator(self, owner, file, scope, dec) -> None:
        if isinstance(dec, ast.Call):
            self._unit(owner, file, scope, list(dec.args) + [k.value for k in dec.keywords])
            target = dec.func
        else:
            target = dec
        t = self.ev(target, scope)
        if t and t[0] == "node" and self.defs.get(t[1]) and self.defs[t[1]].kind != "class":
            self.add_edge(owner, t[1], "calls", file, dec.lineno)

    @staticmethod
    def _is_depends(call: ast.Call) -> bool:
        return _last_name(call.func) in DEPENDS_NAMES

    @staticmethod
    def _depends_arg(call: ast.Call):
        if call.args:
            return call.args[0]
        return _kw(call, "dependency")

    def _depends_on_annotation(self, owner, file, scope, ann, line) -> None:
        t = self.ann_type(ann, scope)
        if t and t[0] == "inst":
            self.add_edge(owner, t[1], "depends", file, line)
            self.n_resolved += 1

    def _handle_depends(self, owner, file, scope, call: ast.Call) -> None:
        arg = self._depends_arg(call)
        if arg is None:
            return
        t = self.ev(arg, scope)
        target = None
        if t and t[0] == "node" and t[1] in self.defs:
            target = t[1]
        elif t and t[0] == "inst":
            m = self.class_attr(t[1], "__call__", False)
            target = m[1] if m and m[0] == "node" else t[1]
        elif isinstance(arg, ast.Name):
            # var assigned from a factory call whose result we could not type -> the factory
            s = scope
            while s is not None and arg.id not in s.bindings:
                s = s.parent
            if s is not None:
                for e in s.bindings[arg.id]:
                    if e[0] == "assign" and isinstance(e[1], ast.Call):
                        f = self.ev(e[1].func, s)
                        if f and f[0] == "node":
                            target = f[1]
                            break
        if target is not None:
            self.add_edge(owner, target, "depends", file, call.lineno)
            self.n_resolved += 1
        else:
            self.n_unresolved += 1
            self.add_external(owner, f"Depends({t[1] if t and t[0] == 'ext' else _dotted(arg)})")

    def _router_key(self, expr, scope: Scope):
        if not isinstance(expr, ast.Name):
            return None
        s = scope
        while s is not None:
            if expr.id in s.bindings:
                for e in s.bindings[expr.id]:
                    if e[0] == "assign":
                        return (s.mod, s.key_owner, expr.id)
                    if e[0] == "from":
                        return self._module_var_key(e[1], e[2], 0)
                return None
            s = s.parent
        return None

    def _module_var_key(self, src: str, name: str, depth: int):
        m = self.resolve_mod(src)
        if m is None or depth > 10:
            return None
        sc = self.mods[m].scope
        for e in sc.bindings.get(name, []):
            if e[0] == "assign":
                return (m, "", name)
            if e[0] == "from":
                return self._module_var_key(e[1], e[2], depth + 1)
        return (m, "", name)

    def _handle_include(self, owner, file, scope, call: ast.Call) -> None:
        func = call.func
        parent = self._router_key(func.value, scope)
        child_expr = call.args[0] if call.args else (_kw(call, "router") or _kw(call, "blueprint"))
        child = self._router_key(child_expr, scope) if child_expr is not None else None
        pre = _kw(call, "prefix") or _kw(call, "url_prefix")
        extra = (_str_const(pre) or "") if pre is not None else ""
        if parent and child:
            self.includes.append((parent, child, extra))
            if parent[0] != child[0]:
                self.add_edge(self.mods[parent[0]].node_id, self.mods[child[0]].node_id,
                              "includes_router", file, call.lineno)
        # the function doing the include (e.g. create_app) also leads to the included router module
        if child and owner in self.defs and child[0] in self.mods and child[0] != self.defs[owner].mod:
            self.add_edge(owner, self.mods[child[0]].node_id, "includes_router", file, call.lineno)
        if child_expr is not None:
            self._unit(owner, file, scope, [k.value for k in call.keywords])

    def _unit(self, owner: str, file: str, scope: Scope, roots) -> None:
        for call in _iter_calls(roots):
            func = call.func
            if self._is_depends(call):
                t = self.ev(func, scope)
                if not (t and t[0] == "node"):
                    self._handle_depends(owner, file, scope, call)
                    continue
            if isinstance(func, ast.Attribute) and func.attr in ("include_router", "register_blueprint"):
                self._handle_include(owner, file, scope, call)
                continue
            t = self.ev(func, scope)
            line = call.lineno
            if t is not None:
                k = t[0]
                if k == "node" and t[1] in self.defs:
                    d = self.defs[t[1]]
                    if d.kind == "class":
                        self.add_edge(owner, d.id, "instantiates", file, line)
                        init = self.class_attr(d.id, "__init__", False)
                        if init and init[0] == "node" and self.defs.get(init[1]) and self.defs[init[1]].kind == "method":
                            self.add_edge(owner, init[1], "calls", file, line)
                    else:
                        self.add_edge(owner, d.id, "calls", file, line)
                    self.n_resolved += 1
                    continue
                if k == "task":
                    self.add_edge(owner, t[1], "calls", file, line)
                    self.n_resolved += 1
                    continue
                if k == "inst":
                    m = self.class_attr(t[1], "__call__", False)
                    if m and m[0] == "node":
                        self.add_edge(owner, m[1], "calls", file, line)
                        self.n_resolved += 1
                        continue
                if k == "builtin":
                    self.n_builtin += 1
                    continue
            self.n_unresolved += 1
            if t is not None and t[0] in ("ext", "extinst"):
                # calling an *instance* (e.g. SessionLocal = sessionmaker(); SessionLocal()) -> "<factory>()()"
                name = t[1] + "()" if t[0] == "extinst" else t[1]
                root_mod = name.split(".", 1)[0].split("(", 1)[0]
                if root_mod in self.top_packages:
                    self.n_unknown += 1
            elif t is not None and t[0] == "mod":
                name = t[1]
            else:
                name = _dotted(func)
                self.n_unknown += 1
            self.add_external(owner, name[:160])
        self._refs(owner, file, scope, roots)

    def _refs(self, owner: str, file: str, scope: Scope, roots) -> None:
        """Edges for internal functions/classes used as values (callbacks), not called directly:
        ``add_task(job)``, ``FastAPI(lifespan=_lifespan)``, ``{KEY: handler}``, ``return _dep``."""
        for expr, ctx, line in _iter_refs(roots):
            t = self.ev(expr, scope)
            if not t or t[0] != "node":
                continue
            d = self.defs.get(t[1])
            if d is None or d.id == owner:
                continue
            if d.kind == "class" and (ctx != "arg" or not self._callback_class(d)):
                continue
            self.add_edge(owner, d.id, "references", file, line)

    def _callback_class(self, d: DefInfo) -> bool:
        """Classes passed as values are mostly type/model arguments (select(Model), response_model=X).
        Only behaviour-carrying classes (own non-dunder methods or __call__) count as callbacks."""
        for name, mid in d.members.items():
            if self.defs.get(mid) is not None and self.defs[mid].kind == "method" and (
                    not name.startswith("__") or name == "__call__"):
                return True
        return False
    # ------------------------------------------------------------------ finalize
    def _full_prefix(self, key, seen) -> str:
        own = self.own_prefix.get(key, "")
        if key in seen:
            return own
        seen = seen | {key}
        for parent, child, extra in self.includes:
            if child == key:
                return self._full_prefix(parent, seen) + extra + own
        return own

    def finalize_routes(self) -> None:
        for fid, recv, scope, etype, methods, path in self.pending_routes:
            key = self._router_key(recv, scope)
            # router module -> handler, so app factory -> router modules -> handlers can be traced
            src_mod = key[0] if key and key[0] in self.mods else self.defs[fid].mod
            self.add_edge(self.mods[src_mod].node_id, fid, "routes", self.mods[src_mod].file,
                          self.defs[fid].node.lineno)
            prefix = self._full_prefix(key, frozenset()) if key else ""
            full = _join_paths(prefix, path)
            if len(full) > 1 and full.endswith("/") and not path.endswith("/"):
                full = full.rstrip("/")
            ep = self.nodes[fid]["entrypoint"]
            ep["path"] = full
            ep["label"] = f"{'WS' if etype == 'websocket' else ep['method']} {full}"

    # ------------------------------------------------------------------ implementations
    @staticmethod
    def _is_stub(fn) -> bool:
        for x in fn.decorator_list:
            if _last_name(x.func if isinstance(x, ast.Call) else x) in ("abstractmethod", "abstractproperty"):
                return True
        body = list(fn.body)
        if body and isinstance(body[0], ast.Expr) and _str_const(body[0].value) is not None:
            body = body[1:]
        if not body:
            return True
        if len(body) == 1:
            b = body[0]
            if isinstance(b, ast.Pass):
                return True
            if isinstance(b, ast.Expr) and isinstance(b.value, ast.Constant) and b.value.value is Ellipsis:
                return True
            if isinstance(b, ast.Raise) and b.exc is not None:
                return _last_name(b.exc.func if isinstance(b.exc, ast.Call) else b.exc) == "NotImplementedError"
        return False

    def _own_methods(self, d: DefInfo) -> dict[str, str]:
        return {n: m for n, m in d.members.items() if self.defs.get(m) is not None and self.defs[m].kind == "method"}

    def link_implementations(self) -> None:
        """``implements`` edges from abstract / Protocol methods to concrete methods, so that a call
        through an interface (``publisher: TilePublisher`` -> ``publisher.publish()``) can be followed
        to the candidate implementations."""
        classes = sorted((d for d in self.defs.values() if d.kind == "class"), key=lambda d: d.id)
        protocols = {d.id for d in classes
                     if any(_last_name(b.value if isinstance(b, ast.Subscript) else b) == "Protocol" for b in d.bases)}
        stub_cache: dict[str, bool] = {}

        def stub(mid: str) -> bool:
            if mid not in stub_cache:
                stub_cache[mid] = self._is_stub(self.defs[mid].node)
            return stub_cache[mid]

        for d in classes:
            mro = self.mro(d.id)
            # nominal: overrides of abstract/stub methods of a base class
            for name, mid in self._own_methods(d).items():
                if name.startswith("__") and name != "__call__":
                    continue
                for anc in mro[1:]:
                    amid = self._own_methods(self.defs[anc]).get(name)
                    if amid is None:
                        continue
                    if stub(amid) and not stub(mid):
                        self.add_edge(amid, mid, "implements", self.mods[d.mod].file, self.defs[mid].node.lineno)
                    break
            # structural: classes that provide every method of a Protocol
            if d.id in protocols:
                continue
            for pid in protocols:
                if pid in mro:
                    continue
                want = {n: m for n, m in self._own_methods(self.defs[pid]).items()
                        if not n.startswith("__") or n == "__call__"}
                if not want:
                    continue
                found = {}
                for name in want:
                    r = self.class_attr(d.id, name, False)
                    if not (r and r[0] == "node" and self.defs.get(r[1]) is not None
                            and self.defs[r[1]].kind == "method" and self.defs[r[1]].cls not in protocols
                            and not stub(r[1])):
                        break
                    found[name] = r[1]
                else:
                    for name, mid in found.items():
                        self.add_edge(want[name], mid, "implements", self.mods[self.defs[mid].mod].file,
                                      self.defs[mid].node.lineno)

    def prune_references(self) -> None:
        """Drop a ``references`` edge when the same pair already has a stronger edge."""
        strong = {(s, t) for (s, t, k) in self.edges if k in ("calls", "instantiates", "depends")}
        for key in [k for k in self.edges if k[2] == "references" and (k[0], k[1]) in strong]:
            del self.edges[key]


def _iter_refs(roots):
    """Yield (expr, context, line) for Name/Attribute expressions used as *values* that may be callbacks:
    call arguments ("arg"), container elements ("elt") and return/yield values ("ret").
    Does not enter nested def/class statements."""
    ref_t = (ast.Name, ast.Attribute)
    stack = list(reversed(list(roots)))
    while stack:
        n = stack.pop()
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            continue
        if isinstance(n, ast.Call):
            fname = _last_name(n.func)
            if fname not in _NO_REF_CALLS and fname not in DEPENDS_NAMES:
                for a in n.args:
                    a = a.value if isinstance(a, ast.Starred) else a
                    if isinstance(a, ref_t):
                        yield a, "arg", n.lineno
                for k in n.keywords:
                    if isinstance(k.value, ref_t):
                        yield k.value, "arg", n.lineno
        elif isinstance(n, ast.Dict):
            for v in n.values:
                if isinstance(v, ref_t):
                    yield v, "elt", v.lineno
        elif isinstance(n, (ast.List, ast.Tuple, ast.Set)) and isinstance(getattr(n, "ctx", ast.Load()), ast.Load):
            for v in n.elts:
                if isinstance(v, ref_t):
                    yield v, "elt", v.lineno
        elif isinstance(n, (ast.Return, ast.Yield)) and n.value is not None and isinstance(n.value, ref_t):
            yield n.value, "ret", n.lineno
        stack.extend(reversed(list(ast.iter_child_nodes(n))))


def _iter_returns(stmts):
    stack = list(reversed(stmts))
    while stack:
        n = stack.pop()
        if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef, ast.Lambda)):
            continue
        if isinstance(n, ast.Return):
            yield n
            continue
        if isinstance(n, ast.stmt):
            stack.extend(reversed([c for c in ast.iter_child_nodes(n) if isinstance(c, (ast.stmt, ast.excepthandler, ast.match_case))]))
        elif isinstance(n, (ast.excepthandler, ast.match_case)):
            stack.extend(reversed(n.body))


# --------------------------------------------------------------------------- public

def build_graph(root: str, excludes: list[str] | None = None) -> dict:
    """Analyse the project under *root* and return the graph dict (SPEC "Graph JSON", minus "version")."""
    t0 = time.perf_counter()
    root_p = pathlib.Path(root).resolve()
    an = _Analyzer(root_p, excludes)
    old_limit = sys.getrecursionlimit()
    if old_limit < 5000:
        sys.setrecursionlimit(5000)
    try:
        an.load()
        an.collect()
        an.resolve_calls()
        an.finalize_routes()
        an.link_implementations()
        an.prune_references()
    finally:
        if old_limit < 5000:
            sys.setrecursionlimit(old_limit)
    nodes = sorted(an.nodes.values(), key=lambda n: n["id"])
    edges = sorted(an.edges.values(), key=lambda e: (e["source"], e["target"], e["kind"], e["line"]))
    external = {k: an.external[k] for k in sorted(an.external)}
    errors = sorted(an.errors, key=lambda e: (e["file"], e["message"]))
    entrypoints = sum(1 for n in nodes if n["entrypoint"])
    ms = int((time.perf_counter() - t0) * 1000)
    return {
        "root": str(root_p),
        "project": root_p.name,
        "generated_at": _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "nodes": nodes,
        "edges": edges,
        "external_calls": external,
        "errors": errors,
        "stats": {
            "files": an.n_files, "nodes": len(nodes), "edges": len(edges), "entrypoints": entrypoints,
            "ms": ms, "calls_resolved": an.n_resolved, "calls_external": an.n_unresolved,
            "calls_builtin": an.n_builtin, "calls_unknown_receiver": an.n_unknown,
        },
    }


def _summary(graph: dict) -> str:
    from collections import Counter

    st = graph["stats"]
    by_type = Counter(n["entrypoint"]["type"] for n in graph["nodes"] if n["entrypoint"])
    by_kind = Counter(n["kind"] for n in graph["nodes"])
    by_edge = Counter(e["kind"] for e in graph["edges"])
    total = st["calls_resolved"] + st["calls_external"]
    rate = (100.0 * st["calls_resolved"] / total) if total else 0.0
    lines = [
        f"project {graph['project']}: {st['files']} files, {st['nodes']} nodes, {st['edges']} edges, "
        f"{st['entrypoints']} entrypoints in {st['ms']} ms",
        "nodes by kind: " + ", ".join(f"{k}={v}" for k, v in sorted(by_kind.items())),
        "edges by kind: " + ", ".join(f"{k}={v}" for k, v in sorted(by_edge.items())),
        "entrypoints by type: " + ", ".join(f"{k}={v}" for k, v in sorted(by_type.items())),
        f"call resolution: {st['calls_resolved']} resolved / {st['calls_external']} external "
        f"({rate:.1f}% resolved; {st['calls_builtin']} builtin calls ignored)",
        f"  of the external calls, {st['calls_external'] - st['calls_unknown_receiver']} target a known library/stdlib "
        f"object and {st['calls_unknown_receiver']} have an untyped/unknown receiver",
        f"errors: {len(graph['errors'])}",
    ]
    return "\n".join(lines)


if __name__ == "__main__":  # python -m codemap.analyzer /path/to/repo [--json]
    import json

    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    g = build_graph(args[0] if args else ".")
    if "--json" in sys.argv:
        json.dump(g, sys.stdout, indent=1)
    else:
        print(_summary(g))
