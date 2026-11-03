package com.sushil.codemap.analysis;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Cross-file passes of analyzer.py on PSI-free {@link FileResult}s, then deterministic SPEC JSON:
 * <ul>
 * <li>{@code load}: duplicate module names ("module X is shadowed by its package; skipped"), import aliases;</li>
 * <li>symbolic calls (imports PyCharm did not resolve) -> project nodes via module names + aliases;</li>
 * <li>{@code finalize_routes} / {@code _full_prefix} / {@code _join_paths}: routes edges, prefixes through
 *     {@code include_router} chains, entrypoint labels;</li>
 * <li>{@code link_implementations}: implements edges (abstract/stub overrides, structural Protocols);</li>
 * <li>dangling edges dropped (target not analysed -> external call), {@code prune_references};</li>
 * <li>{@code build_graph}: sorting (nodes by id, edges by source/target/kind/line, errors, external keys) + stats.</li>
 * </ul>
 * Never mutates its input (results are cached by the engine).
 */
final class DefaultGraphAssembler implements GraphAssembler {
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    @Override
    public String assembleJson(Collection<FileResult> files, String root, String project, long version, long buildMs) {
        return new Assembly(files, root, project).toJson(version, buildMs);
    }

    /** One assembly run (also usable from tests via {@link #graph}). */
    static final class Assembly {
        final String root;
        final String project;
        final List<FileResult> kept = new ArrayList<>();
        final List<Map<String, Object>> errors = new ArrayList<>();
        final Map<String, Map<String, Object>> nodes = new HashMap<>();
        final Map<String, String> moduleFile = new HashMap<>();
        final Map<String, Map<String, Object>> edges = new HashMap<>();
        final Map<String, LinkedHashSet<String>> external = new HashMap<>();
        final Map<String, PyFacts.ClassInfo> classes = new HashMap<>();
        final Map<String, String> methodOwner = new HashMap<>();
        Map<String, String> aliases = Map.of();
        int nFiles;
        long resolved, externalCalls, builtin, unknown;

        Assembly(Collection<FileResult> files, String root, String project) {
            this.root = root == null ? "" : root;
            this.project = project == null ? "" : project;
            run(files);
        }

        private void run(Collection<FileResult> files) {
            List<FileResult> list = new ArrayList<>();
            for (FileResult f : files) if (f != null && f.relPath != null && f.module != null) list.add(f);
            list.sort(Comparator.comparing((FileResult f) -> f.relPath, ModuleNames.PATH_ORDER));
            nFiles = list.size();
            dedupeModules(list);
            Set<String> allRel = new LinkedHashSet<>();
            for (FileResult f : list) allRel.add(f.relPath);
            aliases = ModuleNames.importAliases(allRel, moduleFile.keySet(), project);

            for (FileResult f : kept) {
                for (Map<String, Object> n : f.nodes) {
                    Object id = n.get("id");
                    if (id instanceof String s) nodes.putIfAbsent(s, n);
                }
                PyFacts py = PyFacts.of(f);
                resolved += py.callsResolved;
                externalCalls += py.callsExternal;
                builtin += py.callsBuiltin;
                unknown += py.callsUnknown;
                for (PyFacts.ClassInfo c : py.classes) {
                    classes.putIfAbsent(c.id(), c);
                    for (PyFacts.Method m : c.methods().values()) methodOwner.putIfAbsent(m.id(), c.id());
                }
            }
            Map<String, Set<String>> removedExt = new HashMap<>();
            for (FileResult f : kept) {
                for (Map<String, Object> e : f.edges) addEdge(e);
                symbolic(f, removedExt);
            }
            for (FileResult f : kept) {
                for (Map.Entry<String, List<String>> e : f.externalCalls.entrySet()) {
                    Set<String> rm = removedExt.getOrDefault(e.getKey(), Set.of());
                    for (String n : e.getValue()) if (!rm.contains(n)) addExternal(e.getKey(), n);
                }
            }
            routes();
            implementations();
            dropDangling();
            pruneReferences();
        }

        // ------------------------------------------------------------------ modules

        private void dedupeModules(List<FileResult> list) {
            Map<String, FileResult> byModule = new LinkedHashMap<>();
            for (FileResult f : list) {
                for (String msg : f.errors) errors.add(err(f.relPath, msg));
                FileResult prev = byModule.get(f.module);
                if (prev == null) {
                    byModule.put(f.module, f);
                    continue;
                }
                boolean prevPkg = ModuleNames.isPackageFile(prev.relPath), isPkg = ModuleNames.isPackageFile(f.relPath);
                boolean keepPrev = prevPkg || !isPkg;
                String shadowed = keepPrev ? f.relPath : prev.relPath;
                errors.add(err(shadowed, "module " + f.module + " is shadowed by its package; skipped"));
                if (!keepPrev) byModule.put(f.module, f);
            }
            kept.addAll(byModule.values());
            for (FileResult f : kept) moduleFile.put(f.module, f.relPath);
        }

        private static Map<String, Object> err(String file, String msg) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", file);
            m.put("message", msg);
            return m;
        }

        String resolveModule(String name) {
            if (moduleFile.containsKey(name)) return name;
            return aliases.get(name);
        }

        // ------------------------------------------------------------------ edges / externals

        void addEdge(Map<String, Object> e) {
            String key = e.get("source") + "\u0000" + e.get("target") + "\u0000" + e.get("kind");
            Map<String, Object> prev = edges.get(key);
            if (prev == null || ((Number) e.get("line")).intValue() < ((Number) prev.get("line")).intValue()) {
                edges.put(key, e);
            }
        }

        void edge(String src, String tgt, String kind, String file, int line) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source", src);
            m.put("target", tgt);
            m.put("kind", kind);
            m.put("file", file);
            m.put("line", line);
            addEdge(m);
        }

        void addExternal(String owner, String name) {
            LinkedHashSet<String> s = external.computeIfAbsent(owner, k -> new LinkedHashSet<>());
            if (s.size() < PyRules.MAX_EXTERNAL) s.add(name);
        }

        /** Imports PyCharm could not resolve (e.g. src/ layout without a source root): module names + aliases. */
        private void symbolic(FileResult f, Map<String, Set<String>> removedExt) {
            for (PyFacts.Symbolic s : PyFacts.of(f).symbolic) {
                String id = resolveSymbol(s.symbol());
                if (id == null) continue;
                Map<String, Object> n = nodes.get(id);
                String kind = n == null ? null : (String) n.get("kind");
                if (kind == null || kind.equals("module")) continue;
                if (kind.equals("class")) {
                    edge(s.source(), id, "instantiates", f.relPath, s.line());
                    String init = classMember(id, "__init__");
                    if (init != null && methodOwner.containsKey(init)) edge(s.source(), init, "calls", f.relPath, s.line());
                } else {
                    edge(s.source(), id, "calls", f.relPath, s.line());
                }
                removedExt.computeIfAbsent(s.source(), k -> new HashSet<>()).add(s.symbol());
                resolved++;
                externalCalls--;
            }
        }

        String resolveSymbol(String sym) {
            String[] parts = sym.split("\\.");
            for (int i = parts.length - 1; i >= 1; i--) {
                String m = resolveModule(String.join(".", java.util.Arrays.asList(parts).subList(0, i)));
                if (m == null) continue;
                String id = m + ":" + String.join(".", java.util.Arrays.asList(parts).subList(i, parts.length));
                if (nodes.containsKey(id)) return id;
                return null;
            }
            return null;
        }

        // ------------------------------------------------------------------ routes

        private Map<PyFacts.RouterKey, String> ownPrefix;
        private List<PyFacts.Include> includes;

        private void routes() {
            ownPrefix = new HashMap<>();
            List<Object[]> inc = new ArrayList<>();
            for (int fi = 0; fi < kept.size(); fi++) {
                PyFacts py = PyFacts.of(kept.get(fi));
                ownPrefix.putAll(py.ownPrefix);
                for (PyFacts.Include i : py.includes) inc.add(new Object[]{i, fi});
            }
            inc.sort(Comparator.comparingInt((Object[] o) -> ((PyFacts.Include) o[0]).phase())
                    .thenComparingInt(o -> (Integer) o[1])
                    .thenComparingInt(o -> ((PyFacts.Include) o[0]).seq()));
            includes = new ArrayList<>();
            for (Object[] o : inc) includes.add((PyFacts.Include) o[0]);

            for (FileResult f : kept) {
                for (PyFacts.Route rt : PyFacts.of(f).routes) {
                    Map<String, Object> node = nodes.get(rt.handlerId());
                    if (node == null) continue;
                    PyFacts.RouterKey key = rt.router();
                    String srcMod = key != null && moduleFile.containsKey(key.module()) ? key.module() : f.module;
                    edge(srcMod + ":" + srcMod, rt.handlerId(), "routes", moduleFile.get(srcMod), rt.line());
                    String prefix = key != null ? fullPrefix(key, new HashSet<>()) : "";
                    String full = joinPaths(prefix, rt.path());
                    if (full.length() > 1 && full.endsWith("/") && !rt.path().endsWith("/")) full = rstripSlash(full);
                    Map<String, Object> ep = new LinkedHashMap<>();
                    ep.put("type", rt.etype());
                    ep.put("label", ("websocket".equals(rt.etype()) ? "WS" : rt.methods()) + " " + full);
                    ep.put("method", rt.methods());
                    ep.put("path", full);
                    Map<String, Object> copy = new LinkedHashMap<>(node);
                    copy.put("entrypoint", ep);
                    nodes.put(rt.handlerId(), copy);
                }
            }
        }

        private String fullPrefix(PyFacts.RouterKey key, Set<PyFacts.RouterKey> seen) {
            String own = ownPrefix.getOrDefault(key, "");
            if (!seen.add(key)) return own;
            for (PyFacts.Include i : includes) {
                if (i.child().equals(key)) return fullPrefix(i.parent(), seen) + i.extra() + own;
            }
            return own;
        }

        static String joinPaths(String... parts) {
            StringBuilder sb = new StringBuilder();
            for (String p : parts) if (p != null) sb.append(p);
            String s = sb.toString().replaceAll("/{2,}", "/");
            return s.startsWith("/") ? s : "/" + s;
        }

        private static String rstripSlash(String s) {
            int e = s.length();
            while (e > 0 && s.charAt(e - 1) == '/') e--;
            return s.substring(0, e);
        }

        // ------------------------------------------------------------------ implements

        private final Map<String, List<String>> mroMemo = new HashMap<>();

        List<String> mro(String cid) {
            List<String> cached = mroMemo.get(cid);
            if (cached != null) return cached;
            List<String> out = new ArrayList<>();
            out.add(cid);
            mroMemo.put(cid, out); // cycle guard: a re-entrant call sees the partial list
            PyFacts.ClassInfo c = classes.get(cid);
            if (c != null) {
                for (String b : c.bases()) {
                    if (!classes.containsKey(b)) continue;
                    for (String x : mro(b)) if (!out.contains(x)) out.add(x);
                }
            }
            return out;
        }

        /** analyzer.py {@code class_attr} on facts: member id (method / nested class) or null (attribute or none). */
        String classMember(String cid, String name) {
            for (String c : mro(cid)) {
                PyFacts.ClassInfo ci = classes.get(c);
                if (ci == null) continue;
                String m = ci.members().get(name);
                if (m != null) return m;
                if (ci.attrs().contains(name)) return null;
            }
            return null;
        }

        private PyFacts.Method method(String mid) {
            String owner = methodOwner.get(mid);
            if (owner == null) return null;
            for (PyFacts.Method m : classes.get(owner).methods().values()) if (m.id().equals(mid)) return m;
            return null;
        }

        private String fileOf(String id) {
            Map<String, Object> n = nodes.get(id);
            return n == null ? null : (String) n.get("file");
        }

        private void implementations() {
            List<String> ids = new ArrayList<>(new TreeSet<>(classes.keySet()));
            Set<String> protocols = new TreeSet<>();
            for (String id : ids) if (classes.get(id).protocol()) protocols.add(id);
            for (String cid : ids) {
                PyFacts.ClassInfo d = classes.get(cid);
                List<String> mro = mro(cid);
                String dFile = fileOf(cid);
                for (Map.Entry<String, PyFacts.Method> e : d.methods().entrySet()) {
                    String name = e.getKey();
                    if (name.startsWith("__") && !name.equals("__call__")) continue;
                    for (int i = 1; i < mro.size(); i++) {
                        PyFacts.ClassInfo anc = classes.get(mro.get(i));
                        PyFacts.Method am = anc == null ? null : anc.methods().get(name);
                        if (am == null) continue;
                        if (am.stub() && !e.getValue().stub() && dFile != null) {
                            edge(am.id(), e.getValue().id(), "implements", dFile, e.getValue().line());
                        }
                        break;
                    }
                }
                if (protocols.contains(cid)) continue;
                for (String pid : protocols) {
                    if (mro.contains(pid)) continue;
                    Map<String, PyFacts.Method> want = new LinkedHashMap<>();
                    for (Map.Entry<String, PyFacts.Method> e : classes.get(pid).methods().entrySet()) {
                        if (!e.getKey().startsWith("__") || e.getKey().equals("__call__")) want.put(e.getKey(), e.getValue());
                    }
                    if (want.isEmpty()) continue;
                    Map<String, PyFacts.Method> found = new LinkedHashMap<>();
                    boolean ok = true;
                    for (String name : want.keySet()) {
                        String mid = classMember(cid, name);
                        PyFacts.Method m = mid == null ? null : method(mid);
                        if (m == null || protocols.contains(methodOwner.get(mid)) || m.stub()) {
                            ok = false;
                            break;
                        }
                        found.put(name, m);
                    }
                    if (!ok) continue;
                    for (Map.Entry<String, PyFacts.Method> e : found.entrySet()) {
                        String file = fileOf(e.getValue().id());
                        if (file != null) edge(want.get(e.getKey()).id(), e.getValue().id(), "implements", file, e.getValue().line());
                    }
                }
            }
        }

        // ------------------------------------------------------------------ cleanup

        private void dropDangling() {
            Map<String, String> fallback = new HashMap<>();
            for (FileResult f : kept) {
                for (PyFacts.Fallback fb : PyFacts.of(f).fallback) {
                    fallback.putIfAbsent(fb.source() + "\u0000" + fb.target() + "\u0000" + fb.kind(), fb.ext());
                }
            }
            edges.entrySet().removeIf(en -> {
                Map<String, Object> e = en.getValue();
                String s = (String) e.get("source"), t = (String) e.get("target");
                if (!nodes.containsKey(s)) return true;
                if (nodes.containsKey(t)) return false;
                String ext = fallback.get(en.getKey());
                if (ext != null && !"references".equals(e.get("kind")) && !"inherits".equals(e.get("kind"))) {
                    addExternal(s, "depends".equals(e.get("kind")) ? "Depends(" + ext + ")" : ext);
                }
                return true;
            });
        }

        /** analyzer.py {@code prune_references}. */
        private void pruneReferences() {
            Set<String> strong = new HashSet<>();
            for (Map<String, Object> e : edges.values()) {
                Object k = e.get("kind");
                if ("calls".equals(k) || "instantiates".equals(k) || "depends".equals(k)) {
                    strong.add(e.get("source") + "\u0000" + e.get("target"));
                }
            }
            edges.values().removeIf(e -> "references".equals(e.get("kind"))
                    && strong.contains(e.get("source") + "\u0000" + e.get("target")));
        }

        // ------------------------------------------------------------------ output

        /** The graph as plain maps/lists (SPEC field names), sorted. */
        Map<String, Object> graph(long version, long buildMs) {
            List<Map<String, Object>> nodeList = new ArrayList<>(new TreeMap<>(nodes).values());
            List<Map<String, Object>> edgeList = new ArrayList<>(edges.values());
            edgeList.sort(Comparator.comparing((Map<String, Object> e) -> (String) e.get("source"))
                    .thenComparing(e -> (String) e.get("target"))
                    .thenComparing(e -> (String) e.get("kind"))
                    .thenComparingInt(e -> ((Number) e.get("line")).intValue()));
            Map<String, Object> ext = new TreeMap<>();
            for (Map.Entry<String, LinkedHashSet<String>> e : external.entrySet()) {
                if (nodes.containsKey(e.getKey()) && !e.getValue().isEmpty()) ext.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            List<Map<String, Object>> errs = new ArrayList<>(errors);
            errs.sort(Comparator.comparing((Map<String, Object> e) -> (String) e.get("file"))
                    .thenComparing(e -> (String) e.get("message")));
            int entrypoints = 0;
            for (Map<String, Object> n : nodeList) if (n.get("entrypoint") != null) entrypoints++;
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("files", nFiles);
            stats.put("nodes", nodeList.size());
            stats.put("edges", edgeList.size());
            stats.put("entrypoints", entrypoints);
            stats.put("ms", buildMs);
            stats.put("calls_resolved", resolved);
            stats.put("calls_external", Math.max(0, externalCalls));
            stats.put("calls_builtin", builtin);
            stats.put("calls_unknown_receiver", unknown);
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("root", root);
            g.put("project", project);
            g.put("version", version);
            g.put("generated_at", ISO.format(Instant.now()));
            g.put("nodes", nodeList);
            g.put("edges", edgeList);
            g.put("external_calls", ext);
            g.put("errors", errs);
            g.put("stats", stats);
            return g;
        }

        String toJson(long version, long buildMs) {
            Map<String, Object> g = graph(version, buildMs);
            // top level in SPEC order; nested objects with sorted keys
            StringBuilder sb = new StringBuilder(1 << 16);
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : g.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                JsonWriter.str(sb, e.getKey());
                sb.append(':');
                JsonWriter.write(sb, e.getValue());
            }
            return sb.append('}').toString();
        }
    }
}
