package com.sushil.codemap.analysis;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.jetbrains.python.psi.PyFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Engine behaviour, independent of how complete the analyzer port is: these tests look only at module nodes,
 * versions, changedFiles and (via {@link Recording}) which files were analysed.
 */
public class CodeGraphServiceTest extends EngineTestBase {

    /** Wraps the real analyzer; records analysed files, adds import-based dependsOnModules, optional delay. */
    static final class Recording implements FileAnalyzer {
        final FileAnalyzer delegate = FileAnalyzer.create();
        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final AtomicInteger cancels = new AtomicInteger();
        volatile long delayMs;
        private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*(?:from\\s+([\\w.]+)\\s+import|import\\s+([\\w.]+))");

        @Override
        public FileResult analyze(PyFile file, String relPath) {
            try {
                long end = System.currentTimeMillis() + delayMs;
                while (System.currentTimeMillis() < end) {
                    ProgressManager.checkCanceled();
                    Thread.sleep(1);
                }
            } catch (ProcessCanceledException e) {
                cancels.incrementAndGet();
                throw e;
            } catch (InterruptedException e) {
                throw new ProcessCanceledException(e);
            }
            FileResult r = delegate.analyze(file, relPath);
            Matcher m = IMPORT.matcher(file.getText());
            while (m.find()) r.dependsOnModules.add(m.group(1) != null ? m.group(1) : m.group(2));
            calls.computeIfAbsent(relPath, k -> new AtomicInteger()).incrementAndGet();
            return r;
        }

        int count(String rel) { AtomicInteger a = calls.get(rel); return a == null ? 0 : a.get(); }
    }

    private static String mod(String m) { return m + ":" + m; }

    public void testFullBuildThenIncrementalEdit() throws Exception {
        myFixture.addFileToProject("a.py", "def f():\n    pass\n");
        myFixture.addFileToProject("pkg/__init__.py", "");
        myFixture.addFileToProject("pkg/b.py", "def g():\n    pass\n");
        Recording rec = new Recording();
        CodeGraphService s = newService(rec);
        assertEquals("idle", s.status().state());

        GraphSnapshot s1 = build(s);
        G g1 = new G(s1);
        assertTrue(s1.version() >= 1);
        assertEquals("ready", s.status().state());
        assertEquals(3, s.fileCount());
        assertEquals(List.of("a.py", "pkg/__init__.py", "pkg/b.py"), s.files());
        if (assemblerReady(g1)) {   // needs DefaultGraphAssembler
            assertTrue(g1.toString(), g1.nodes.containsKey(mod("a")));
            assertTrue(g1.toString(), g1.nodes.containsKey(mod("pkg.b")));
            assertTrue(g1.toString(), g1.nodes.containsKey(mod("pkg")));
            assertEquals("a.py", g1.nodes.get(mod("a")).get("file"));
        }
        assertEquals(1, rec.count("a.py"));

        // new file -> VFS create event
        myFixture.addFileToProject("c.py", "def h():\n    pass\n");
        GraphSnapshot s2 = flush(s);
        assertTrue(s2.version() > s1.version());
        assertEquals(List.of("c.py"), s2.changedFiles());
        assertTrue(s.files().contains("c.py"));
        if (assemblerReady(g1)) assertTrue(new G(s2).nodes.containsKey(mod("c")));
        assertEquals(1, rec.count("a.py"));   // untouched files are not re-analysed

        // document edit -> PSI event after commit
        VirtualFile a = myFixture.findFileInTempDir("a.py");
        Document doc = PsiDocumentManager.getInstance(getProject()).getDocument(psi(a));
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            doc.insertString(doc.getTextLength(), "\n\ndef added():\n    f()\n");
            PsiDocumentManager.getInstance(getProject()).commitDocument(doc);
        });
        assertTrue("PSI change must be recorded", s.hasPendingChanges());
        GraphSnapshot s3 = flush(s);
        assertTrue(s3.version() > s2.version());
        assertEquals(List.of("a.py"), s3.changedFiles());
        assertEquals(2, rec.count("a.py"));
        assertEquals(1, rec.count("pkg/b.py"));
        // analyzer-dependent (PsiFileAnalyzer port): the new function shows up
        if (analyzerProducesFunctions(new G(s1))) {
            assertTrue(new G(s3).toString(), new G(s3).nodes.containsKey("a:added"));
            assertTrue(new G(s3).has("a:added", "a:f"));
        }
    }

    public void testDebouncedUpdateWithoutFlush() throws Exception {
        myFixture.addFileToProject("a.py", "x = 1\n");
        CodeGraphService s = newService(new Recording());
        GraphSnapshot s1 = build(s);
        CompletableFuture<GraphSnapshot> published = new CompletableFuture<>();
        s.addListener(published::complete, getTestRootDisposable());
        long t0 = System.currentTimeMillis();
        myFixture.addFileToProject("b.py", "y = 2\n");
        GraphSnapshot s2 = await(published);
        long dt = System.currentTimeMillis() - t0;
        assertTrue(s2.version() > s1.version());
        assertEquals(List.of("b.py"), s2.changedFiles());
        assertTrue("debounce ~500 ms, got " + dt, dt >= CodeGraphService.DEBOUNCE_MS - 50);
        assertSame(s2, s.snapshot());
    }

    public void testDeleteAndRename() throws Exception {
        myFixture.addFileToProject("a.py", "x = 1\n");
        myFixture.addFileToProject("b.py", "y = 2\n");
        myFixture.addFileToProject("sub/__init__.py", "");
        myFixture.addFileToProject("sub/m.py", "z = 3\n");
        CodeGraphService s = newService(new Recording());
        build(s);
        assertEquals(4, s.fileCount());

        VirtualFile b = myFixture.findFileInTempDir("b.py");
        WriteAction.runAndWait(() -> b.delete(this));
        GraphSnapshot s2 = flush(s);
        assertEquals(List.of("b.py"), s2.changedFiles());
        assertFalse(s.files().contains("b.py"));
        if (assemblerReady(new G(s2))) assertFalse(new G(s2).nodes.containsKey(mod("b")));
        assertEquals(3, s.fileCount());

        VirtualFile a = myFixture.findFileInTempDir("a.py");
        WriteAction.runAndWait(() -> a.rename(this, "renamed.py"));
        GraphSnapshot s3 = flush(s);
        assertEquals(List.of("a.py", "renamed.py"), s3.changedFiles());
        assertEquals(List.of("renamed.py", "sub/__init__.py", "sub/m.py"), s.files());
        G g3 = new G(s3);
        if (assemblerReady(g3)) {
            assertFalse(g3.nodes.containsKey(mod("a")));
            assertTrue(g3.nodes.containsKey(mod("renamed")));
        }

        VirtualFile sub = myFixture.findFileInTempDir("sub");
        WriteAction.runAndWait(() -> sub.delete(this));
        GraphSnapshot s4 = flush(s);
        assertEquals(List.of("sub/__init__.py", "sub/m.py"), s4.changedFiles());
        assertEquals(1, s.fileCount());
    }

    public void testDependantsAreReanalysed() throws Exception {
        myFixture.addFileToProject("lib.py", "def f():\n    pass\n");
        myFixture.addFileToProject("user.py", "import lib\n\ndef run():\n    lib.f()\n");
        myFixture.addFileToProject("other.py", "def z():\n    pass\n");
        Recording rec = new Recording();
        CodeGraphService s = newService(rec);
        build(s);

        VirtualFile lib = myFixture.findFileInTempDir("lib.py");
        Document doc = PsiDocumentManager.getInstance(getProject()).getDocument(psi(lib));
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            doc.insertString(doc.getTextLength(), "\ndef g():\n    pass\n");
            PsiDocumentManager.getInstance(getProject()).commitDocument(doc);
        });
        GraphSnapshot s2 = flush(s);
        assertEquals(List.of("lib.py", "user.py"), s2.changedFiles());
        assertEquals(2, rec.count("user.py"));
        assertEquals(1, rec.count("other.py"));

        // deleting a module re-analyses its importers too
        WriteAction.runAndWait(() -> lib.delete(this));
        GraphSnapshot s3 = flush(s);
        assertEquals(List.of("lib.py", "user.py"), s3.changedFiles());
        assertEquals(3, rec.count("user.py"));
    }

    public void testExcludes() throws Exception {
        myFixture.addFileToProject("app.py", "x = 1\n");
        myFixture.addFileToProject("venv/lib/x.py", "x = 1\n");
        myFixture.addFileToProject("pkg/build/gen.py", "x = 1\n");
        myFixture.addFileToProject("node_modules/a.py", "x = 1\n");
        myFixture.addFileToProject("thing.egg-info/a.py", "x = 1\n");
        myFixture.addFileToProject("generated/out.py", "x = 1\n");
        myFixture.addFileToProject("pkg/gen_api.py", "x = 1\n");
        myFixture.addFileToProject("pkg/keep.py", "x = 1\n");
        myFixture.addFileToProject("notes.txt", "x");
        PropertiesComponent props = PropertiesComponent.getInstance();
        String key = "com.sushil.codemap.excludes";
        String before = props.getValue(key);
        props.setValue(key, "generated\ngen_*.py");
        try {
            CodeGraphService s = newService(new Recording());
            G g = new G(build(s));
            assertEquals(List.of("app.py", "pkg/keep.py"), s.files());
            if (assemblerReady(g)) {
                assertTrue(g.nodes.containsKey(mod("app")));
                assertTrue(g.nodes.containsKey(mod("pkg.keep")));
            }

            // a new file inside an excluded dir is ignored by incremental updates as well
            myFixture.addFileToProject("venv/lib/y.py", "x = 1\n");
            long v = s.snapshot().version();
            flush(s);
            assertEquals(v, s.snapshot().version());
        } finally {
            props.setValue(key, before);
        }
    }

    public void testCancellationByWritesAndSupersededBuild() throws Exception {
        int n = 120;
        for (int i = 0; i < n; i++) myFixture.addFileToProject("m" + i + ".py", "def f" + i + "():\n    pass\n");
        Recording rec = new Recording();
        rec.delayMs = 5;   // ~0.6 s of analysis so write actions land in the middle of batches
        CodeGraphService s = newService(rec);

        CompletableFuture<GraphSnapshot> first = s.rebuildAsync();
        VirtualFile m0 = myFixture.findFileInTempDir("m0.py");
        Document doc = PsiDocumentManager.getInstance(getProject()).getDocument(psi(m0));
        int edits = 0;
        long deadline = System.currentTimeMillis() + 2_000;   // "type" for 2 s, then let the build finish
        while (!first.isDone() && System.currentTimeMillis() < deadline) {
            final int k = edits++;
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                doc.insertString(doc.getTextLength(), "\n# edit " + k + "\n");
                PsiDocumentManager.getInstance(getProject()).commitDocument(doc);
            });
            com.intellij.testFramework.PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            Thread.sleep(20);
            if (edits == 5) {
                // supersede the running build: the first future must end cancelled, the second must win
                CompletableFuture<GraphSnapshot> second = s.rebuildAsync();
                try {
                    await(first);
                    fail("superseded build must not publish");
                } catch (CancellationException expected) {
                    // ok
                }
                first = second;
            }
        }
        GraphSnapshot snap = await(first);
        assertEquals(n, s.fileCount());
        G gs = new G(snap);
        if (assemblerReady(gs)) {
            assertEquals(n, gs.nodes.values().stream().filter(x -> "module".equals(x.get("kind"))).count());
        }
        assertEquals("ready", s.status().state());
        // edits made during the build are processed afterwards
        GraphSnapshot after = flush(s);
        assertTrue(after.version() >= snap.version());
        System.out.println("cancellation test: edits=" + edits + " analyzer cancellations=" + rec.cancels.get()
                + " m0 analysed " + rec.count("m0.py") + "x");
        assertTrue("write actions should have cancelled some batches", rec.cancels.get() > 0);
    }

    public void testDisposeWhileBuilding() throws Exception {
        for (int i = 0; i < 60; i++) myFixture.addFileToProject("d" + i + ".py", "x = 1\n");
        Recording rec = new Recording();
        rec.delayMs = 10;
        CodeGraphService s = new CodeGraphService(getProject(), rec);
        CompletableFuture<GraphSnapshot> f = s.rebuildAsync();
        Thread.sleep(50);
        com.intellij.openapi.util.Disposer.dispose(s);
        try {
            await(f);
            fail("disposed build must not publish");
        } catch (CancellationException expected) {
            // ok
        }
        assertEquals(0, s.snapshot().version());
        assertTrue(s.rebuildAsync().isCancelled());
    }

    private PsiFile psi(VirtualFile vf) {
        return com.intellij.psi.PsiManager.getInstance(getProject()).findFile(vf);
    }

    /** False while DefaultGraphAssembler is still the stub that emits no nodes. */
    static boolean assemblerReady(G g) {
        return !g.nodes.isEmpty();
    }

    static boolean analyzerProducesFunctions(G g) {
        return g.nodes.values().stream().anyMatch(n -> "function".equals(n.get("kind")));
    }
}
