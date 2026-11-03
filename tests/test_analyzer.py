"""Unit tests for codemap.analyzer (stdlib unittest; small throw-away fixture projects)."""

from __future__ import annotations

import os
import pathlib
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))

from codemap.analyzer import build_graph, iter_python_files  # noqa: E402

NODE_KEYS = {"id", "kind", "name", "qualname", "module", "file", "line", "end_line", "signature", "doc",
             "decorators", "is_async", "class_name", "package", "loc", "entrypoint", "is_test"}


class _Project:
    """Write a dict of {relpath: source} to a temp dir and analyse it."""

    def __init__(self, files: dict[str, str], name: str = "proj", excludes=None):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self._tmp.name) / name
        for rel, src in files.items():
            p = self.root / rel
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(textwrap.dedent(src).lstrip("\n"), encoding="utf-8")
        self.graph = build_graph(str(self.root), excludes)
        self.nodes = {n["id"]: n for n in self.graph["nodes"]}
        self.edge_set = {(e["source"], e["target"], e["kind"]) for e in self.graph["edges"]}

    def close(self):
        self._tmp.cleanup()

    def has(self, src, tgt, kind="calls") -> bool:
        return (src, tgt, kind) in self.edge_set

    def out(self, src, kind=None) -> set[str]:
        return {t for s, t, k in self.edge_set if s == src and (kind is None or k == kind)}

    def ext(self, nid) -> list[str]:
        return self.graph["external_calls"].get(nid, [])


class AnalyzerTestCase(unittest.TestCase):
    def project(self, files, **kw) -> _Project:
        p = _Project(files, **kw)
        self.addCleanup(p.close)
        return p


class TestImportsAndModules(AnalyzerTestCase):
    def test_import_forms_and_reexports(self):
        p = self.project({
            "pkg/__init__.py": "from .api import public_fn\n",
            "pkg/api/__init__.py": "from pkg.api.impl import public_fn as public_fn\n",
            "pkg/api/impl.py": """
                def public_fn():
                    return helper()

                def helper():
                    return 1
            """,
            "pkg/util.py": """
                def tool():
                    pass

                class Thing:
                    @staticmethod
                    def make():
                        pass
            """,
            "pkg/star.py": "from pkg.util import *\n",
            "app.py": """
                import pkg
                import pkg.util as u
                from pkg import public_fn as pf
                from pkg import util
                from pkg.star import tool as star_tool
                from . import nothing_here

                def run():
                    pf()
                    pkg.public_fn()
                    u.tool()
                    util.Thing.make()
                    star_tool()
                    print("x")
                    unknown_thing.go()
            """,
            "pkg/sub/rel.py": """
                from ..util import tool
                from .. import util as U
                from . import sibling

                def go():
                    tool()
                    U.Thing.make()
                    sibling.f()
            """,
            "pkg/sub/__init__.py": "",
            "pkg/sub/sibling.py": "def f():\n    pass\n",
        })
        run = "app:run"
        self.assertTrue(p.has(run, "pkg.api.impl:public_fn"))
        self.assertTrue(p.has(run, "pkg.util:tool"))
        self.assertTrue(p.has(run, "pkg.util:Thing.make"))
        self.assertEqual(p.out(run), {"pkg.api.impl:public_fn", "pkg.util:tool", "pkg.util:Thing.make"})
        self.assertIn("unknown_thing.go", p.ext(run))
        self.assertNotIn("print", p.ext(run))  # builtins are ignored
        self.assertTrue(p.has("pkg.api.impl:public_fn", "pkg.api.impl:helper"))
        go = "pkg.sub.rel:go"
        self.assertEqual(p.out(go), {"pkg.util:tool", "pkg.util:Thing.make", "pkg.sub.sibling:f"})

    def test_node_schema_and_module_names(self):
        p = self.project({
            "pkg/__init__.py": '"""Package doc."""\n',
            "pkg/mod.py": '''
                """Module doc line.

                More.
                """

                def outer(a: int, b: str = "x", *args, c=1, **kw) -> int:
                    """Outer doc."""
                    def inner():
                        return 1
                    return inner()

                class K(object):
                    async def m(self, /, x):
                        pass
            ''',
        })
        g = p.graph
        for key in ("root", "project", "generated_at", "nodes", "edges", "external_calls", "errors", "stats"):
            self.assertIn(key, g)
        self.assertEqual(g["project"], "proj")
        for n in g["nodes"]:
            self.assertEqual(set(n), NODE_KEYS, n["id"])
        self.assertEqual(p.nodes["pkg:pkg"]["kind"], "module")
        self.assertEqual(p.nodes["pkg:pkg"]["file"], "pkg/__init__.py")
        self.assertEqual(p.nodes["pkg:pkg"]["doc"], "Package doc.")
        mod = p.nodes["pkg.mod:pkg.mod"]
        self.assertEqual((mod["doc"], mod["package"], mod["name"]), ("Module doc line.", "pkg", "mod"))
        outer = p.nodes["pkg.mod:outer"]
        self.assertEqual(outer["signature"], "(a: int, b: str = 'x', *args, c=1, **kw) -> int")
        self.assertEqual(outer["doc"], "Outer doc.")
        self.assertEqual(outer["loc"], outer["end_line"] - outer["line"] + 1)
        inner = p.nodes["pkg.mod:outer.<locals>.inner"]
        self.assertEqual(inner["kind"], "function")
        self.assertTrue(p.has("pkg.mod:outer", "pkg.mod:outer.<locals>.inner"))
        m = p.nodes["pkg.mod:K.m"]
        self.assertEqual((m["kind"], m["class_name"], m["is_async"]), ("method", "K", True))
        self.assertEqual(m["signature"], "(self, /, x)")
        self.assertEqual(p.nodes["pkg.mod:K"]["signature"], "(object)")

    def test_src_layout_alias(self):
        p = self.project({
            "src/lib/__init__.py": "",
            "src/lib/core.py": "def f():\n    pass\n",
            "main.py": "from lib.core import f\n\nf()\n",
        })
        self.assertTrue(p.has("main:main", "src.lib.core:f"))


class TestClassesAndTyping(AnalyzerTestCase):
    FILES = {
        "svc/__init__.py": "",
        "svc/base.py": """
            class Base:
                def __init__(self, repo: "Repo | None" = None):
                    self.repo = repo
                    self.cache = Cache()

                def save(self):
                    self.validate()
                    self.repo.store()
                    self.cache.put()

                def validate(self):
                    pass

                @classmethod
                def build(cls):
                    return cls()

            class Repo:
                def store(self):
                    pass

            class Cache:
                def put(self):
                    pass
        """,
        "svc/user.py": """
            from typing import Optional
            from svc.base import Base, Repo

            class UserService(Base):
                def validate(self):
                    super().validate()
                    self.extra()

                def extra(self):
                    pass

            def make() -> UserService:
                return UserService()

            def handler(svc: Optional[UserService], repo: "Repo"):
                svc.save()
                repo.store()
                s = UserService(repo)
                s.extra()
                made = make()
                made.validate()
                with Repo() as r:
                    r.store()
                UserService.build()

            def shadow(svc):
                svc.save()
        """,
    }

    def test_methods_bases_instantiation(self):
        p = self.project(self.FILES)
        self.assertTrue(p.has("svc.user:UserService", "svc.base:Base", "inherits"))
        # self.m() in a base-class method resolves to the base method; MRO for subclass lookup
        self.assertTrue(p.has("svc.base:Base.save", "svc.base:Base.validate"))
        self.assertTrue(p.has("svc.base:Base.save", "svc.base:Repo.store"))   # self.attr typed via param
        self.assertTrue(p.has("svc.base:Base.save", "svc.base:Cache.put"))    # self.attr = Cache()
        self.assertTrue(p.has("svc.base:Base.__init__", "svc.base:Cache", "instantiates"))
        self.assertTrue(p.has("svc.base:Base.build", "svc.base:Base", "instantiates"))  # cls()
        self.assertTrue(p.has("svc.user:UserService.validate", "svc.base:Base.validate"))  # super()
        self.assertTrue(p.has("svc.user:UserService.validate", "svc.user:UserService.extra"))
        h = "svc.user:handler"
        self.assertTrue(p.has(h, "svc.base:Base.save"))                  # Optional[...] param, inherited
        self.assertTrue(p.has(h, "svc.base:Repo.store"))                 # string annotation; with-as
        self.assertTrue(p.has(h, "svc.user:UserService", "instantiates"))
        self.assertTrue(p.has(h, "svc.base:Base.__init__"))              # inherited __init__
        self.assertTrue(p.has(h, "svc.user:UserService.extra"))          # local var typing
        self.assertTrue(p.has(h, "svc.user:make"))
        self.assertTrue(p.has(h, "svc.user:UserService.validate"))       # return annotation typing
        self.assertTrue(p.has(h, "svc.base:Base.build"))                 # inherited classmethod
        self.assertEqual(p.out("svc.user:shadow"), set())                # untyped -> no guess
        self.assertIn("svc.save", p.ext("svc.user:shadow"))


class TestFastAPI(AnalyzerTestCase):
    FILES = {
        "app/__init__.py": "",
        "app/envelope.py": """
            from fastapi import APIRouter

            def api_router(**kwargs) -> APIRouter:
                return APIRouter(**kwargs)
        """,
        "app/deps.py": """
            from fastapi import Depends

            def get_db():
                yield 1

            def get_principal(db=Depends(get_db)):
                return 1

            def require_roles(*roles):
                def _dep(p=Depends(get_principal)):
                    return p
                return _dep

            class Pager:
                def __init__(self, limit: int = 10):
                    self.limit = limit

            class Checker:
                def __call__(self):
                    return True

            checker = Checker()
        """,
        "app/faq/__init__.py": "",
        "app/faq/service.py": """
            def list_public(db):
                return []
        """,
        "app/faq/router.py": """
            from typing import Annotated
            from fastapi import Depends, Query
            from app.deps import get_db, require_roles, Pager, checker
            from app.envelope import api_router
            from app.faq import service as faq_service

            router = api_router(prefix="/faq", tags=["faq"])
            _write_dep = require_roles("admin")

            @router.get("")
            def list_faq(category: str | None = Query(default=None), db=Depends(get_db)) -> list:
                items = faq_service.list_public(db)
                db.rollback()
                return items

            @router.post("/manage", dependencies=[Depends(checker)])
            async def create(p=Depends(_write_dep), page: Pager = Depends()):
                pass

            @router.api_route("/both", methods=["GET", "POST"])
            def both(page: Annotated[Pager, Depends()], db: Annotated[object, Depends(get_db)]):
                pass

            @router.websocket("/ws")
            async def ws(websocket):
                pass
        """,
        "app/v1.py": """
            from fastapi import APIRouter
            from app.faq.router import router as faq_router

            router = APIRouter(prefix="/api/v1")
            router.include_router(faq_router)
        """,
        "app/main.py": """
            from fastapi import FastAPI
            from app.v1 import router as v1_router

            def create_app() -> FastAPI:
                app = FastAPI()
                app.include_router(v1_router, prefix="/x")

                @app.get("/health")
                def health():
                    return {}

                return app

            app = create_app()
        """,
    }

    def test_routes_prefixes_and_depends(self):
        p = self.project(self.FILES)
        n = p.nodes
        ep = n["app.faq.router:list_faq"]["entrypoint"]
        self.assertEqual(ep, {"type": "http", "label": "GET /x/api/v1/faq", "method": "GET", "path": "/x/api/v1/faq"})
        self.assertEqual(n["app.faq.router:create"]["entrypoint"]["label"], "POST /x/api/v1/faq/manage")
        self.assertEqual(n["app.faq.router:both"]["entrypoint"]["method"], "GET,POST")
        self.assertEqual(n["app.faq.router:ws"]["entrypoint"]["type"], "websocket")
        self.assertEqual(n["app.main:create_app.<locals>.health"]["entrypoint"]["label"], "GET /health")
        self.assertEqual(n["app.main:create_app"]["entrypoint"]["type"], "app_factory")

        lf = "app.faq.router:list_faq"
        self.assertTrue(p.has(lf, "app.deps:get_db", "depends"))
        self.assertTrue(p.has(lf, "app.faq.service:list_public"))
        self.assertIn("fastapi.Query", p.ext(lf))
        self.assertNotIn("Depends", " ".join(p.ext(lf)))
        cr = "app.faq.router:create"
        self.assertTrue(p.has(cr, "app.deps:require_roles.<locals>._dep", "depends"))  # factory-made dep
        self.assertTrue(p.has(cr, "app.deps:Pager", "depends"))                       # Depends() + annotation
        self.assertTrue(p.has(cr, "app.deps:Checker.__call__", "depends"))            # decorator dependencies=
        self.assertTrue(p.has("app.deps:require_roles.<locals>._dep", "app.deps:get_principal", "depends"))
        both = "app.faq.router:both"
        self.assertTrue(p.has(both, "app.deps:Pager", "depends"))
        self.assertTrue(p.has(both, "app.deps:get_db", "depends"))
        # module-level code
        self.assertTrue(p.has("app.faq.router:app.faq.router", "app.envelope:api_router"))
        self.assertTrue(p.has("app.faq.router:app.faq.router", "app.deps:require_roles"))
        self.assertTrue(p.has("app.v1:app.v1", "app.faq.router:app.faq.router", "includes_router"))
        self.assertTrue(p.has("app.main:app.main", "app.v1:app.v1", "includes_router"))
        self.assertTrue(p.has("app.main:app.main", "app.main:create_app"))


class TestOtherEntrypoints(AnalyzerTestCase):
    def test_celery_main_cli(self):
        p = self.project({
            "worker.py": """
                from celery import Celery, shared_task
                import click

                celery_app = Celery("x")

                @celery_app.task(name="jobs.process")
                def process(x):
                    return helper(x)

                @shared_task
                def other():
                    process.delay(1)

                def helper(x):
                    return x

                @click.command()
                def cli_cmd():
                    other.apply_async()

                def main():
                    helper(2)

                if __name__ == "__main__":
                    main()
            """,
            "factory.py": """
                from flask import Flask

                def create_web_app():
                    return Flask(__name__)
            """,
        })
        n = p.nodes
        self.assertEqual(n["worker:process"]["entrypoint"], {"type": "celery_task", "label": "jobs.process"})
        self.assertEqual(n["worker:other"]["entrypoint"]["type"], "celery_task")
        self.assertEqual(n["worker:cli_cmd"]["entrypoint"]["type"], "cli")
        self.assertEqual(n["worker:worker"]["entrypoint"]["type"], "main")
        self.assertEqual(n["factory:create_web_app"]["entrypoint"]["type"], "app_factory")
        self.assertIsNone(n["worker:helper"]["entrypoint"])
        self.assertTrue(p.has("worker:worker", "worker:main"))
        self.assertTrue(p.has("worker:other", "worker:process"))      # .delay()
        self.assertTrue(p.has("worker:cli_cmd", "worker:other"))      # .apply_async()
        self.assertTrue(p.has("worker:process", "worker:helper"))


class TestRobustness(AnalyzerTestCase):
    def test_syntax_error_excludes_and_determinism(self):
        files = {
            "good.py": "def a():\n    b()\n\ndef b():\n    pass\n",
            "bad.py": "def broken(:\n",
            "skipme/x.py": "def hidden():\n    pass\n",
            ".venv/lib.py": "def v():\n    pass\n",
            "a.py": "def shadowed():\n    pass\n",
            "a/__init__.py": "def real():\n    pass\n",
            "cyc1.py": "from cyc2 import thing\n\ndef f():\n    thing()\n",
            "cyc2.py": "from cyc1 import thing\n",
            "self_ref.py": "x = x.y\n\ndef g():\n    x.z()\n",
        }
        p = self.project(files, excludes=["skipme"])
        self.assertTrue(p.has("good:a", "good:b"))
        self.assertIn("bad.py", [e["file"] for e in p.graph["errors"]])
        self.assertIn("a.py", [e["file"] for e in p.graph["errors"]])
        self.assertIn("a:real", p.nodes)
        self.assertNotIn("skipme.x:hidden", p.nodes)
        self.assertFalse(any(k.startswith(".venv") for k in p.nodes))
        self.assertEqual(p.out("cyc1:f"), set())
        files_found = [f.relative_to(p.root).as_posix() for f in iter_python_files(p.root, ["skipme"])]
        self.assertNotIn("skipme/x.py", files_found)
        self.assertNotIn(".venv/lib.py", files_found)
        self.assertEqual(files_found, [f.as_posix() for f in sorted(pathlib.PurePosixPath(x) for x in files_found)])
        g2 = build_graph(str(p.root), ["skipme"])
        for key in ("nodes", "edges", "external_calls", "errors"):
            self.assertEqual(p.graph[key], g2[key])
        st = p.graph["stats"]
        self.assertEqual(st["nodes"], len(p.graph["nodes"]))
        self.assertEqual(st["edges"], len(p.graph["edges"]))

    def test_external_cap(self):
        body = "\n".join(f"    lib.f{i}()" for i in range(80))
        p = self.project({"m.py": f"import lib\n\ndef f():\n{body}\n"})
        ext = p.ext("m:f")
        self.assertEqual(len(ext), 50)
        self.assertEqual(ext[0], "lib.f0")


class TestReferencesAndLinks(AnalyzerTestCase):
    def test_callbacks_nested_hooks_and_routes(self):
        p = self.project({
            "app/__init__.py": "",
            "app/jobs.py": """
                def run_job(x):
                    pass

                def handler_a():
                    pass

                HANDLERS = {"a": handler_a}
            """,
            "app/mw.py": """
                class Audit:
                    def dispatch(self, request):
                        pass

                class Schema:
                    name: str
            """,
            "app/router.py": """
                from fastapi import APIRouter, BackgroundTasks
                from app.jobs import run_job
                from app.mw import Schema
                router = APIRouter(prefix="/r")

                @router.post("/up", response_model=Schema)
                def upload(bg: BackgroundTasks):
                    bg.add_task(run_job, 1)
                    isinstance(bg, Schema)
            """,
            "app/main.py": """
                from fastapi import FastAPI
                from app.mw import Audit
                from app.router import router

                async def _lifespan(app):
                    yield

                def make_dep(role):
                    def _dep():
                        return role
                    return _dep

                def create_app():
                    app = FastAPI(lifespan=_lifespan)
                    app.add_middleware(Audit)
                    app.include_router(router, prefix="/api")

                    @app.exception_handler(ValueError)
                    async def on_value_error(request, exc):
                        pass
                    return app
            """,
            "app/db.py": """
                from sqlalchemy.orm import sessionmaker
                SessionLocal = sessionmaker()

                def get_db():
                    db = SessionLocal()
                    db.close()
            """,
            "tests/test_x.py": "def test_a():\n    pass\n",
        })
        self.assertTrue(p.has("app.router:upload", "app.jobs:run_job", "references"))
        self.assertTrue(p.has("app.jobs:app.jobs", "app.jobs:handler_a", "references"))
        self.assertTrue(p.has("app.main:create_app", "app.main:_lifespan", "references"))
        self.assertTrue(p.has("app.main:create_app", "app.mw:Audit", "references"))
        self.assertTrue(p.has("app.main:make_dep", "app.main:make_dep.<locals>._dep", "references"))
        # model/type arguments are not callbacks
        self.assertFalse(p.has("app.router:upload", "app.mw:Schema", "references"))
        # nested def registered by a decorator inside its parent + hook entrypoint
        h = "app.main:create_app.<locals>.on_value_error"
        self.assertTrue(p.has("app.main:create_app", h, "references"))
        self.assertEqual(p.nodes[h]["entrypoint"], {"type": "hook", "label": "exception_handler ValueError"})
        # router module -> handler, factory -> included router module
        self.assertTrue(p.has("app.router:app.router", "app.router:upload", "routes"))
        self.assertTrue(p.has("app.main:create_app", "app.router:app.router", "includes_router"))
        self.assertEqual(p.nodes["app.router:upload"]["entrypoint"]["label"], "POST /api/r/up")
        # calling a variable bound to a factory result
        self.assertEqual(p.ext("app.db:get_db"), ["sqlalchemy.orm.sessionmaker()()", "sqlalchemy.orm.sessionmaker()().close"])
        self.assertTrue(p.nodes["tests.test_x:test_a"]["is_test"])
        self.assertFalse(p.nodes["app.db:get_db"]["is_test"])

    def test_implements_protocol_and_abstract(self):
        p = self.project({
            "ports.py": """
                from typing import Protocol
                from abc import ABC, abstractmethod

                class Publisher(Protocol):
                    def publish(self, x: str) -> None: ...

                class Base(ABC):
                    @abstractmethod
                    def run(self): ...
            """,
            "impl.py": """
                from ports import Base

                class GeoPublisher:
                    def publish(self, x):
                        print(x)

                class Unrelated:
                    def other(self):
                        pass

                class Job(Base):
                    def run(self):
                        return 1
            """,
            "use.py": """
                from ports import Publisher

                def go(p: Publisher):
                    p.publish("a")
            """,
        })
        self.assertTrue(p.has("use:go", "ports:Publisher.publish"))
        self.assertTrue(p.has("ports:Publisher.publish", "impl:GeoPublisher.publish", "implements"))
        self.assertTrue(p.has("ports:Base.run", "impl:Job.run", "implements"))
        self.assertEqual(p.out("ports:Publisher.publish", "implements"), {"impl:GeoPublisher.publish"})


@unittest.skipUnless(os.path.isdir("/home/sushil/PycharmProjects/gis_api"), "gis_api not present")
class TestGisApi(unittest.TestCase):
    def test_gis_api_smoke(self):
        g = build_graph("/home/sushil/PycharmProjects/gis_api")
        self.assertLess(g["stats"]["ms"], 3000)
        nodes = {n["id"]: n for n in g["nodes"]}
        edges = {(e["source"], e["target"], e["kind"]) for e in g["edges"]}
        lf = "gis_api.modules.faq.api.router:list_faq"
        self.assertEqual(nodes[lf]["entrypoint"]["label"], "GET /api/v1/faq")
        self.assertIn((lf, "gis_api.modules.faq.application.service:list_public", "calls"), edges)
        self.assertIn((lf, "gis_api.db.session:get_db", "depends"), edges)
        # background job started by a route is reachable from it
        self.assertIn(("gis_api.modules.gis_data.api.router:upload_gis_dataset",
                       "gis_api.modules.gis_data.application.service:run_ingest_job", "references"), edges)
        self.assertIn(("gis_api.modules.faq.api.router:gis_api.modules.faq.api.router", lf, "routes"), edges)


if __name__ == "__main__":
    unittest.main()
