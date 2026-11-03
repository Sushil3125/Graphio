package com.sushil.codemap.analysis;

import java.util.Map;
import java.util.Set;

/**
 * Analyzer behaviours ported from {@code codemap/tests/test_analyzer.py} (the Python analyzer is the spec), run
 * end to end through the engine (PsiFileAnalyzer + DefaultGraphAssembler). These depend on the analyzer port;
 * while it is still a stub they fail with "analyzer port incomplete".
 * Note: the light test project has no Python SDK, so library names (fastapi, typing) do not resolve; the fixtures
 * therefore only check project-internal edges and name-based framework rules.
 */
public class AnalyzerBehaviourTest extends EngineTestBase {

    private G graph(Map<String, String> files) throws Exception {
        for (Map.Entry<String, String> e : files.entrySet()) myFixture.addFileToProject(e.getKey(), e.getValue());
        G g = new G(build(newService(null)));
        assertTrue("analyzer port incomplete: no function nodes\n" + g, CodeGraphServiceTest.analyzerProducesFunctions(g));
        return g;
    }

    public void testCallsViaImports() throws Exception {
        G p = graph(Map.of(
                "pkg/__init__.py", "from .api import public_fn\n",
                "pkg/api/__init__.py", "from pkg.api.impl import public_fn as public_fn\n",
                "pkg/api/impl.py", "def public_fn():\n    return helper()\n\ndef helper():\n    return 1\n",
                "pkg/util.py", "def tool():\n    pass\n\nclass Thing:\n    @staticmethod\n    def make():\n        pass\n",
                "app.py", """
                        import pkg
                        import pkg.util as u
                        from pkg import public_fn as pf
                        from pkg import util

                        def run():
                            pf()
                            pkg.public_fn()
                            u.tool()
                            util.Thing.make()
                            print("x")
                        """.stripIndent(),
                "pkg/sub/__init__.py", "",
                "pkg/sub/rel.py", "from ..util import tool\nfrom . import sibling\n\ndef go():\n    tool()\n    sibling.f()\n",
                "pkg/sub/sibling.py", "def f():\n    pass\n"));
        String run = "app:run";
        assertTrue(p.toString(), p.has(run, "pkg.api.impl:public_fn"));
        assertTrue(p.has(run, "pkg.util:tool"));
        assertTrue(p.has(run, "pkg.util:Thing.make"));
        assertEquals(Set.of("pkg.api.impl:public_fn", "pkg.util:tool", "pkg.util:Thing.make"), p.out(run, "calls"));
        assertTrue(p.has("pkg.api.impl:public_fn", "pkg.api.impl:helper"));
        assertEquals(Set.of("pkg.util:tool", "pkg.sub.sibling:f"), p.out("pkg.sub.rel:go", "calls"));
    }

    private static final Map<String, String> CLASSES = Map.of(
            "svc/__init__.py", "",
            "svc/base.py", """
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
                    """.stripIndent(),
            "svc/user.py", """
                    from svc.base import Base, Repo

                    class UserService(Base):
                        def validate(self):
                            super().validate()
                            self.extra()

                        def extra(self):
                            pass

                    def make() -> UserService:
                        return UserService()

                    def handler(repo: "Repo"):
                        repo.store()
                        s = UserService(repo)
                        s.extra()
                        s.save()
                        made = make()
                        made.validate()
                        UserService.build()

                    def shadow(svc):
                        svc.save()
                    """.stripIndent());

    public void testSelfMethodViaBaseClassAndInheritance() throws Exception {
        G p = graph(CLASSES);
        assertTrue(p.toString(), p.has("svc.user:UserService", "svc.base:Base", "inherits"));
        assertTrue(p.has("svc.base:Base.save", "svc.base:Base.validate"));
        assertTrue(p.has("svc.base:Base.save", "svc.base:Cache.put"));          // self.attr = Cache()
        assertTrue(p.has("svc.user:UserService.validate", "svc.base:Base.validate"));   // super()
        assertTrue(p.has("svc.user:UserService.validate", "svc.user:UserService.extra"));
        String h = "svc.user:handler";
        assertTrue(p.has(h, "svc.base:Repo.store"));                           // string annotation
        assertTrue(p.has(h, "svc.user:UserService.extra"));                    // local var typing
        assertTrue(p.has(h, "svc.base:Base.save"));                            // inherited method
        assertTrue(p.has(h, "svc.user:make"));
        assertTrue(p.has(h, "svc.user:UserService.validate"));                 // return annotation typing
        assertTrue(p.has(h, "svc.base:Base.build"));                           // inherited classmethod
        assertEquals(Set.of(), p.out("svc.user:shadow", "calls"));             // untyped -> no guess
    }

    public void testInstantiation() throws Exception {
        G p = graph(CLASSES);
        assertTrue(p.toString(), p.has("svc.user:handler", "svc.user:UserService", "instantiates"));
        assertTrue(p.has("svc.base:Base.__init__", "svc.base:Cache", "instantiates"));
        assertTrue(p.has("svc.base:Base.build", "svc.base:Base", "instantiates"));   // cls()
        assertTrue(p.has("svc.user:make", "svc.user:UserService", "instantiates"));
        assertTrue(p.has("svc.user:handler", "svc.base:Base.__init__"));            // inherited __init__
    }

    private static final Map<String, String> FASTAPI = Map.of(
            "app/__init__.py", "",
            "app/deps.py", """
                    from fastapi import Depends

                    def get_db():
                        yield 1

                    def get_principal(db=Depends(get_db)):
                        return 1
                    """.stripIndent(),
            "app/faq/__init__.py", "",
            "app/faq/service.py", "def list_public(db):\n    return []\n",
            "app/faq/router.py", """
                    from fastapi import APIRouter, Depends
                    from app.deps import get_db, get_principal
                    from app.faq import service as faq_service

                    router = APIRouter(prefix="/faq", tags=["faq"])

                    @router.get("")
                    def list_faq(db=Depends(get_db)) -> list:
                        return faq_service.list_public(db)

                    @router.post("/manage")
                    async def create(p=Depends(get_principal)):
                        pass
                    """.stripIndent(),
            "app/v1.py", """
                    from fastapi import APIRouter
                    from app.faq.router import router as faq_router

                    router = APIRouter(prefix="/api/v1")
                    router.include_router(faq_router)
                    """.stripIndent(),
            "app/main.py", """
                    from fastapi import FastAPI
                    from app.v1 import router as v1_router

                    def create_app() -> FastAPI:
                        app = FastAPI()
                        app.include_router(v1_router, prefix="/x")
                        return app

                    app = create_app()
                    """.stripIndent());

    public void testDependsEdges() throws Exception {
        G p = graph(FASTAPI);
        assertTrue(p.toString(), p.has("app.faq.router:list_faq", "app.deps:get_db", "depends"));
        assertTrue(p.has("app.faq.router:create", "app.deps:get_principal", "depends"));
        assertTrue(p.has("app.deps:get_principal", "app.deps:get_db", "depends"));
        assertTrue(p.has("app.faq.router:list_faq", "app.faq.service:list_public"));
        assertFalse(String.join(" ", p.ext("app.faq.router:list_faq")).contains("Depends"));
    }

    public void testRoutePrefixesThroughIncludeRouter() throws Exception {
        G p = graph(FASTAPI);
        Map<String, Object> ep = p.entrypoint("app.faq.router:list_faq");
        assertNotNull(p.toString(), ep);
        assertEquals("http", ep.get("type"));
        assertEquals("GET /x/api/v1/faq", ep.get("label"));
        assertEquals("GET", ep.get("method"));
        assertEquals("/x/api/v1/faq", ep.get("path"));
        assertEquals("POST /x/api/v1/faq/manage", p.entrypoint("app.faq.router:create").get("label"));
        assertEquals("app_factory", p.entrypoint("app.main:create_app").get("type"));
        assertTrue(p.has("app.v1:app.v1", "app.faq.router:app.faq.router", "includes_router"));
        assertTrue(p.has("app.main:app.main", "app.v1:app.v1", "includes_router"));
    }

    public void testDependsOnModulesRecorded() throws Exception {
        // engine contract: the analyzer reports the project modules a file refers to (for dependant re-analysis)
        myFixture.addFileToProject("lib.py", "def f():\n    pass\n");
        var pf = (com.jetbrains.python.psi.PyFile) myFixture.addFileToProject("user.py", "from lib import f\n\ndef run():\n    f()\n");
        FileResult r = com.intellij.openapi.application.ReadAction.compute(() -> FileAnalyzer.create().analyze(pf, "user.py"));
        assertEquals("user", r.module);
        assertTrue("dependsOnModules=" + r.dependsOnModules, r.dependsOnModules.contains("lib"));
    }
}
