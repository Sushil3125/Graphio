package com.sushil.codemap.analysis;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The rest of {@code codemap/tests/test_analyzer.py} on the PSI port (complements {@link AnalyzerBehaviourTest}).
 * The light fixture has no Python SDK: library imports stay unresolved, which also exercises the analyzer.py-style
 * fallbacks (import names, builtins list, name-based typing wrappers).
 */
public class AnalyzerPortTest extends EngineTestBase {
    private static final Set<String> NODE_KEYS = Set.of("id", "kind", "name", "qualname", "module", "file", "line",
            "end_line", "signature", "doc", "decorators", "is_async", "class_name", "package", "loc", "entrypoint",
            "is_test");

    private G graph(Map<String, String> files) throws Exception {
        for (Map.Entry<String, String> e : files.entrySet()) myFixture.addFileToProject(e.getKey(), e.getValue());
        return new G(build(newService(null)));
    }

    private static Map<String, String> files(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1].stripIndent());
        return m;
    }

    public void testNodeSchemaAndModuleNames() throws Exception {
        G p = graph(files(
                "pkg/__init__.py", "\"\"\"Package doc.\"\"\"\n",
                "pkg/mod.py", """
                        \"\"\"Module doc line.

                        More.
                        \"\"\"

                        def outer(a: int, b: str = "x", *args, c=1, **kw) -> int:
                            \"\"\"Outer doc.\"\"\"
                            def inner():
                                return 1
                            return inner()

                        class K(object):
                            async def m(self, /, x):
                                pass
                        """));
        for (Map<String, Object> n : p.nodes.values()) assertEquals(n.get("id").toString(), NODE_KEYS, n.keySet());
        Map<String, Object> pkg = p.nodes.get("pkg:pkg");
        assertEquals("module", pkg.get("kind"));
        assertEquals("pkg/__init__.py", pkg.get("file"));
        assertEquals("Package doc.", pkg.get("doc"));
        Map<String, Object> mod = p.nodes.get("pkg.mod:pkg.mod");
        assertEquals(List.of("Module doc line.", "pkg", "mod"), List.of(mod.get("doc"), mod.get("package"), mod.get("name")));
        Map<String, Object> outer = p.nodes.get("pkg.mod:outer");
        assertEquals("(a: int, b: str = 'x', *args, c=1, **kw) -> int", outer.get("signature"));
        assertEquals("Outer doc.", outer.get("doc"));
        assertEquals(6L, num(outer.get("line")));
        assertEquals(10L, num(outer.get("end_line")));
        assertEquals(num(outer.get("end_line")) - num(outer.get("line")) + 1, num(outer.get("loc")));
        assertEquals("function", p.nodes.get("pkg.mod:outer.<locals>.inner").get("kind"));
        assertTrue(p.toString(), p.has("pkg.mod:outer", "pkg.mod:outer.<locals>.inner"));
        Map<String, Object> m = p.nodes.get("pkg.mod:K.m");
        assertEquals(List.of("method", "K", true), List.of(m.get("kind"), m.get("class_name"), m.get("is_async")));
        assertEquals("(self, /, x)", m.get("signature"));
        assertEquals("(object)", p.nodes.get("pkg.mod:K").get("signature"));
    }

    /** Display strings follow ast.unparse (expected values produced by CPython 3 ast.unparse). */
    public void testUnparseFidelity() throws Exception {
        G p = graph(files("u.py", """
                def deco(*a, **k):
                    return lambda f: f

                @deco(params=[(r, s) for r in (True, False) for s in ("none", "x") if r])
                @deco(**{k: ("2.0", "p " "q") for k, v in {}.items()})
                def f(a=-0.010, b=1e-05, c=0x10, d=1_000, e=1e16, g=2.5e3j, h=.5) -> (int | str):
                    pass

                def t(x: "Tuple[()]" = None, y: dict[(), int] = None, z=sum(i for i in ())):
                    pass
                """));
        Map<String, Object> f = p.nodes.get("u:f");
        assertEquals("(a=-0.01, b=1e-05, c=16, d=1000, e=1e+16, g=2500j, h=0.5) -> int | str", f.get("signature"));
        assertEquals(List.of("deco(params=[(r, s) for r in (True, False) for s in ('none', 'x') if r])",
                "deco(**{k: ('2.0', 'p q') for k, v in {}.items()})"), f.get("decorators"));
        assertEquals("(x: 'Tuple[()]' = None, y: dict[(), int] = None, z=sum((i for i in ())))",
                p.nodes.get("u:t").get("signature"));
        assertEquals("1.2345678901234568e+17", PyUnparse.floatRepr(123456789012345678.0));
        assertEquals("0.0001", PyUnparse.floatRepr(1e-4));
    }

    private static long num(Object o) { return ((Number) o).longValue(); }

    public void testTypingForms() throws Exception {
        G p = graph(files(
                "svc/__init__.py", "",
                "svc/base.py", """
                        class Repo:
                            def store(self):
                                pass

                        class Base:
                            def save(self):
                                pass
                        """,
                "svc/user.py", """
                        from typing import Optional
                        from svc.base import Base, Repo

                        class UserService(Base):
                            pass

                        def handler(svc: Optional[UserService]):
                            svc.save()
                            with Repo() as r:
                                r.store()
                        """));
        assertTrue(p.toString(), p.has("svc.user:handler", "svc.base:Base.save"));
        assertTrue(p.has("svc.user:handler", "svc.base:Repo.store"));
        assertTrue(p.has("svc.user:handler", "svc.base:Repo", "instantiates"));
    }

    public void testFastApiFull() throws Exception {
        G p = graph(files(
                "app/__init__.py", "",
                "app/envelope.py", """
                        from fastapi import APIRouter

                        def api_router(**kwargs) -> APIRouter:
                            return APIRouter(**kwargs)
                        """,
                "app/deps.py", """
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
                "app/faq/__init__.py", "",
                "app/faq/service.py", "def list_public(db):\n    return []\n",
                "app/faq/router.py", """
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
                "app/v1.py", """
                        from fastapi import APIRouter
                        from app.faq.router import router as faq_router

                        router = APIRouter(prefix="/api/v1")
                        router.include_router(faq_router)
                        """,
                "app/main.py", """
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
                        """));
        assertEquals(Map.of("type", "http", "label", "GET /x/api/v1/faq", "method", "GET", "path", "/x/api/v1/faq"),
                p.entrypoint("app.faq.router:list_faq"));
        assertEquals("POST /x/api/v1/faq/manage", p.entrypoint("app.faq.router:create").get("label"));
        assertEquals("GET,POST", p.entrypoint("app.faq.router:both").get("method"));
        assertEquals("websocket", p.entrypoint("app.faq.router:ws").get("type"));
        assertEquals("WS /x/api/v1/faq/ws", p.entrypoint("app.faq.router:ws").get("label"));
        assertEquals("GET /health", p.entrypoint("app.main:create_app.<locals>.health").get("label"));
        assertEquals("app_factory", p.entrypoint("app.main:create_app").get("type"));
        assertEquals("create_app() → FastAPI", p.entrypoint("app.main:create_app").get("label"));

        String lf = "app.faq.router:list_faq";
        assertTrue(p.toString(), p.has(lf, "app.deps:get_db", "depends"));
        assertTrue(p.has(lf, "app.faq.service:list_public"));
        assertTrue(p.ext(lf).toString(), p.ext(lf).contains("fastapi.Query"));
        assertTrue(p.ext(lf).toString(), p.ext(lf).contains("db.rollback"));
        assertFalse(String.join(" ", p.ext(lf)).contains("Depends"));
        String cr = "app.faq.router:create";
        assertTrue(p.out(cr, null).toString(), p.has(cr, "app.deps:require_roles.<locals>._dep", "depends"));
        assertTrue(p.has(cr, "app.deps:Pager", "depends"));
        assertTrue(p.has(cr, "app.deps:Checker.__call__", "depends"));
        assertTrue(p.has("app.deps:require_roles.<locals>._dep", "app.deps:get_principal", "depends"));
        String both = "app.faq.router:both";
        assertTrue(p.has(both, "app.deps:Pager", "depends"));
        assertTrue(p.has(both, "app.deps:get_db", "depends"));
        assertTrue(p.has("app.faq.router:app.faq.router", "app.envelope:api_router"));
        assertTrue(p.has("app.faq.router:app.faq.router", "app.deps:require_roles"));
        assertTrue(p.has("app.v1:app.v1", "app.faq.router:app.faq.router", "includes_router"));
        assertTrue(p.has("app.main:app.main", "app.v1:app.v1", "includes_router"));
        assertTrue(p.has("app.main:create_app", "app.v1:app.v1", "includes_router"));
        assertTrue(p.has("app.main:app.main", "app.main:create_app"));
        assertTrue(p.has("app.faq.router:app.faq.router", lf, "routes"));
        assertTrue(p.has("app.main:app.main", "app.main:create_app.<locals>.health", "routes"));
        assertTrue(p.has("app.main:create_app", "app.main:create_app.<locals>.health", "references"));
    }

    public void testCeleryMainCli() throws Exception {
        G p = graph(files(
                "worker.py", """
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
                "factory.py", """
                        from flask import Flask

                        def create_web_app():
                            return Flask(__name__)
                        """));
        assertEquals(Map.of("type", "celery_task", "label", "jobs.process"), p.entrypoint("worker:process"));
        assertEquals("celery_task", p.entrypoint("worker:other").get("type"));
        assertEquals(Map.of("type", "cli", "label", "cli-cmd"), p.entrypoint("worker:cli_cmd"));
        assertEquals(Map.of("type", "main", "label", "python -m worker"), p.entrypoint("worker:worker"));
        assertEquals("app_factory", p.entrypoint("factory:create_web_app").get("type"));
        assertNull(p.entrypoint("worker:helper"));
        assertTrue(p.toString(), p.has("worker:worker", "worker:main"));
        assertTrue(p.has("worker:other", "worker:process"));
        assertTrue(p.has("worker:cli_cmd", "worker:other"));
        assertTrue(p.has("worker:process", "worker:helper"));
    }

    @SuppressWarnings("unchecked")
    public void testRobustness() throws Exception {
        G p = graph(files(
                "good.py", "def a():\n    b()\n\ndef b():\n    pass\n",
                "bad.py", "def broken(:\n",
                "a.py", "def shadowed():\n    pass\n",
                "a/__init__.py", "def real():\n    pass\n",
                "cyc1.py", "from cyc2 import thing\n\ndef f():\n    thing()\n",
                "cyc2.py", "from cyc1 import thing\n",
                "self_ref.py", "x = x.y\n\ndef g():\n    x.z()\n",
                "redef.py", "def f():\n    pass\n\ndef f():\n    g()\n\ndef g():\n    pass\n"));
        assertTrue(p.toString(), p.has("good:a", "good:b"));
        List<Map<String, Object>> errors = (List<Map<String, Object>>) p.json.get("errors");
        Set<Object> errFiles = new java.util.HashSet<>();
        for (Map<String, Object> e : errors) errFiles.add(e.get("file"));
        assertTrue(errors.toString(), errFiles.contains("bad.py"));
        assertTrue(errors.toString(), errFiles.contains("a.py"));
        assertTrue(p.nodes.containsKey("a:real"));
        assertFalse(p.nodes.containsKey("a:shadowed"));
        assertEquals(Set.of(), p.out("cyc1:f", null));
        assertEquals(Set.of(), p.out("redef:f", null));   // first definition wins
        Map<String, Object> st = (Map<String, Object>) p.json.get("stats");
        assertEquals(num(st.get("nodes")), p.nodes.size());
        assertEquals(num(st.get("edges")), p.edges.size());
    }

    public void testExternalCap() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 80; i++) body.append("    lib.f").append(i).append("()\n");
        G p = graph(files("m.py", "import lib\n\ndef f():\n" + body));
        List<String> ext = p.ext("m:f");
        assertEquals(50, ext.size());
        assertEquals("lib.f0", ext.get(0));
    }

    public void testCallbacksNestedHooksAndRoutes() throws Exception {
        G p = graph(files(
                "app/__init__.py", "",
                "app/jobs.py", """
                        def run_job(x):
                            pass

                        def handler_a():
                            pass

                        HANDLERS = {"a": handler_a}
                        """,
                "app/mw.py", """
                        class Audit:
                            def dispatch(self, request):
                                pass

                        class Schema:
                            name: str
                        """,
                "app/router.py", """
                        from fastapi import APIRouter, BackgroundTasks
                        from app.jobs import run_job
                        from app.mw import Schema
                        router = APIRouter(prefix="/r")

                        @router.post("/up", response_model=Schema)
                        def upload(bg: BackgroundTasks):
                            bg.add_task(run_job, 1)
                            isinstance(bg, Schema)
                        """,
                "app/main.py", """
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
                "app/db.py", """
                        from sqlalchemy.orm import sessionmaker
                        SessionLocal = sessionmaker()

                        def get_db():
                            db = SessionLocal()
                            db.close()
                        """,
                "tests/test_x.py", "def test_a():\n    pass\n"));
        assertTrue(p.toString(), p.has("app.router:upload", "app.jobs:run_job", "references"));
        assertTrue(p.has("app.jobs:app.jobs", "app.jobs:handler_a", "references"));
        assertTrue(p.has("app.main:create_app", "app.main:_lifespan", "references"));
        assertTrue(p.has("app.main:create_app", "app.mw:Audit", "references"));
        assertTrue(p.has("app.main:make_dep", "app.main:make_dep.<locals>._dep", "references"));
        assertFalse(p.has("app.router:upload", "app.mw:Schema", "references"));
        String h = "app.main:create_app.<locals>.on_value_error";
        assertTrue(p.has("app.main:create_app", h, "references"));
        assertEquals(Map.of("type", "hook", "label", "exception_handler ValueError"), p.entrypoint(h));
        assertTrue(p.has("app.router:app.router", "app.router:upload", "routes"));
        assertTrue(p.has("app.main:create_app", "app.router:app.router", "includes_router"));
        assertEquals("POST /api/r/up", p.entrypoint("app.router:upload").get("label"));
        assertEquals(List.of("sqlalchemy.orm.sessionmaker()()", "sqlalchemy.orm.sessionmaker()().close"),
                p.ext("app.db:get_db"));
        assertEquals(Boolean.TRUE, p.nodes.get("tests.test_x:test_a").get("is_test"));
        assertEquals(Boolean.FALSE, p.nodes.get("app.db:get_db").get("is_test"));
    }

    public void testImplementsProtocolAndAbstract() throws Exception {
        G p = graph(files(
                "ports.py", """
                        from typing import Protocol
                        from abc import ABC, abstractmethod

                        class Publisher(Protocol):
                            def publish(self, x: str) -> None: ...

                        class Base(ABC):
                            @abstractmethod
                            def run(self): ...
                        """,
                "impl.py", """
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
                "use.py", """
                        from ports import Publisher

                        def go(p: Publisher):
                            p.publish("a")
                        """));
        assertTrue(p.toString(), p.has("use:go", "ports:Publisher.publish"));
        assertTrue(p.has("ports:Publisher.publish", "impl:GeoPublisher.publish", "implements"));
        assertTrue(p.has("ports:Base.run", "impl:Job.run", "implements"));
        assertEquals(Set.of("impl:GeoPublisher.publish"), p.out("ports:Publisher.publish", "implements"));
    }

    /** Attributes with several assigned classes, and a factory result passed through a tuple loop target. */
    public void testMultiValueAttrAndLoopFactory() throws Exception {
        G p = graph(files("m.py", """
                class Task:
                    pass

                class TaskExtended(Task):
                    pass

                class Backend:
                    task_cls = Task

                    def __init__(self, ext):
                        if ext:
                            self.task_cls = TaskExtended

                    def store(self):
                        return self.task_cls(1)

                class Scanner:
                    def resolver(self, cls):
                        def local_attrs():
                            return []
                        return local_attrs

                    def scan(self):
                        bases = []
                        for base in (1, 2):
                            local_attrs = self.resolver(base)
                            bases.append((base, local_attrs))
                        for base, local_attrs in bases:
                            for x in local_attrs():
                                pass
                """));
        assertTrue(p.toString(), p.has("m:Backend.store", "m:Task", "instantiates"));
        assertTrue(p.toString(), p.has("m:Backend.store", "m:TaskExtended", "instantiates"));
        assertTrue(p.toString(), p.has("m:Scanner.scan", "m:Scanner.resolver.<locals>.local_attrs"));
    }

    public void testSrcLayoutAlias() throws Exception {
        G p = graph(files(
                "src/lib/__init__.py", "",
                "src/lib/core.py", "def f():\n    pass\n",
                "main.py", "from lib.core import f\n\nf()\n"));
        assertTrue(p.toString(), p.has("main:main", "src.lib.core:f"));
    }

    private static String volatileFields(String json) {
        return json.replaceAll("\"generated_at\":\"[^\"]*\"", "").replaceAll("\"(version|ms)\":\\d+", "");
    }

    public void testDeterministicJson() throws Exception {
        myFixture.addFileToProject("x.py", "def a():\n    b()\n\ndef b():\n    pass\n");
        CodeGraphService s = newService(null);
        String raw = build(s).json();
        assertNotNull(com.sushil.codemap.Json.parse(raw));
        assertEquals(volatileFields(raw), volatileFields(build(s).json()));
    }
}
