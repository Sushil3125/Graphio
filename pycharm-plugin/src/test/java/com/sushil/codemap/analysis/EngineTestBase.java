package com.sushil.codemap.analysis;

import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.sushil.codemap.Json;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Shared helpers. Each test gets its own {@link CodeGraphService} instance (not the project service: the light
 * project is reused between tests) disposed with the test. Waiting pumps the EDT so nothing can deadlock on it.
 */
public abstract class EngineTestBase extends BasePlatformTestCase {
    protected CodeGraphService newService(FileAnalyzer analyzer) {
        CodeGraphService s = analyzer == null ? new CodeGraphService(getProject()) : new CodeGraphService(getProject(), analyzer);
        Disposer.register(getTestRootDisposable(), s);
        return s;
    }

    protected static <T> T await(CompletableFuture<T> f) throws Exception {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!f.isDone()) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for the engine");
            Thread.sleep(5);
        }
        try {
            return f.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) throw ex;
            throw e;
        }
    }

    protected static GraphSnapshot build(CodeGraphService s) throws Exception {
        return await(s.rebuildAsync());
    }

    protected static GraphSnapshot flush(CodeGraphService s) throws Exception {
        return await(s.flushPendingAsync());
    }

    /** Parsed graph: nodes by id, edge triples "source -> target [kind]". */
    protected static final class G {
        final Map<String, Object> json;
        final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        final Set<String> edges = new HashSet<>();

        @SuppressWarnings("unchecked")
        G(GraphSnapshot s) {
            json = (Map<String, Object>) Json.parse(s.json());
            for (Object o : (List<Object>) json.getOrDefault("nodes", List.of())) {
                Map<String, Object> n = (Map<String, Object>) o;
                nodes.put((String) n.get("id"), n);
            }
            for (Object o : (List<Object>) json.getOrDefault("edges", List.of())) {
                Map<String, Object> e = (Map<String, Object>) o;
                edges.add(e.get("source") + " -> " + e.get("target") + " [" + e.get("kind") + "]");
            }
        }

        boolean has(String src, String tgt, String kind) { return edges.contains(src + " -> " + tgt + " [" + kind + "]"); }

        boolean has(String src, String tgt) { return has(src, tgt, "calls"); }

        @SuppressWarnings("unchecked")
        Map<String, Object> entrypoint(String id) {
            Map<String, Object> n = nodes.get(id);
            return n == null ? null : (Map<String, Object>) n.get("entrypoint");
        }

        @SuppressWarnings("unchecked")
        List<String> ext(String id) {
            Map<String, Object> ec = (Map<String, Object>) json.getOrDefault("external_calls", Map.of());
            return (List<String>) ec.getOrDefault(id, List.of());
        }

        Set<String> out(String src, String kind) {
            Set<String> r = new HashSet<>();
            String p = src + " -> ";
            for (String e : edges) if (e.startsWith(p) && (kind == null || e.endsWith(" [" + kind + "]")))
                r.add(e.substring(p.length(), e.lastIndexOf(" [")));
            return r;
        }

        @Override public String toString() { return "nodes=" + nodes.keySet() + "\nedges=" + edges; }
    }
}
