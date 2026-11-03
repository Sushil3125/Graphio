package com.sushil.codemap.analysis;

import com.intellij.openapi.diagnostic.ControlFlowException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiWhiteSpace;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.psi.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of {@code codemap/analyzer.py} (the spec of behaviour) to PyCharm's Python PSI, one file at a time.
 * Name binding / call targets come from PyCharm ({@link PyResolver}: {@code multiFollowAssignmentsChain} with
 * {@code PyResolveContext.defaultContext(TypeEvalContext.codeAnalysis(project, file))}); where PyCharm gives up, the
 * analyzer.py rules are applied on top (factory return values, {@code x = Cls(); x.m()}, celery {@code .delay}, ...).
 * Cross-file passes run on PSI-free facts ({@link PyFacts}) in {@link DefaultGraphAssembler}.
 *
 * <p>Rule map (analyzer.py -> Java):
 * <ul>
 * <li>{@code iter_python_files}/DEFAULT_EXCLUDES: engine file set + {@link ModuleNames#isAnalyzablePath} for call
 *     targets; edges into files the engine did not analyse become external calls ({@link PyFacts.Fallback}).</li>
 * <li>{@code load}: module names {@link ModuleNames#moduleName} ({@code __init__} = package, root init = project
 *     name); "shadowed by its package" errors and the src-layout import alias ({@link ModuleNames#importAliases})
 *     live in the assembler; the alias resolves imports PyCharm could not resolve ({@link PyFacts.Symbolic}).
 *     Syntax errors: {@link Run#syntaxError} (PSI is error tolerant, so the file is still analysed).</li>
 * <li>{@code collect}/{@code _new_node}/{@code _collect_func}/{@code _collect_class}: {@link Run#collect},
 *     {@link Run#newNode} (module node, kinds function/method/class, {@code .<locals>.} qualnames via
 *     {@link PyResolver#qualnameOf}, first definition wins, signature/doc/decorators/is_async/class_name/package/
 *     loc/is_test); {@code if __name__ == "__main__"} -> {@link Run#isMainGuard} (module scope, also nested in
 *     module-level blocks).</li>
 * <li>{@code _detect_entrypoint}: {@link Run#detectEntrypoint} (http/websocket routes, celery tasks, click/typer
 *     cli, framework hooks, app factories). Route prefixes and labels are completed in the assembler from
 *     {@link PyFacts.Route} / {@link PyFacts#ownPrefix} / {@link PyFacts.Include} ({@code finalize_routes},
 *     {@code _full_prefix}, {@code _join_paths}); the routes edge (router module -> handler) too.</li>
 * <li>{@code ev}/{@code eval_entry}/{@code getattr_t}/{@code call_result}/{@code func_return}/{@code ann_type}/
 *     {@code class_attr}/{@code mro}: {@link PyResolver}. {@code eval_binding} priorities (def > import > param >
 *     annotation > assign, source order among equals) are applied to PyCharm's raw bindings of a name, of an
 *     imported name and of a project module attribute ({@code lookup_module}, incl. the submodule fallback);
 *     with-targets evaluate their context expression.</li>
 * <li>{@code resolve_calls}/{@code _unit}: {@link Run#unit} (calls; instantiates + {@code __init__} calls; celery
 *     {@code .delay/.apply_async/...} task calls; {@code __call__} of instances; builtins ignored; external_calls
 *     naming incl. {@code <factory>()()}, dedup and the 50 cap). {@code _def_unit} + {@code _decorator}:
 *     {@link Run#defUnit} (decorator args and defaults count for the def, decorator function -> calls, parent ->
 *     decorated nested def references, inherits, class bases/keywords/body).</li>
 * <li>FastAPI {@code _is_depends}/{@code _handle_depends}/{@code _depends_on_annotation}: {@link Run#handleDepends},
 *     {@link Run#dependsOnAnnotation} (defaults, {@code Annotated[...]}, {@code dependencies=[...]} in decorator
 *     args, {@code Depends()} + annotation, instance {@code __call__}, factory returning an inner function).</li>
 * <li>{@code _handle_include}/{@code _router_key}/{@code _module_var_key}: {@link Run#handleInclude},
 *     {@link Run#routerKey} (includes_router module -> module and def -> module edges).</li>
 * <li>{@code _refs}/{@code _iter_refs}/{@code _callback_class}: {@link Run#refs}.</li>
 * <li>{@code link_implementations}/{@code _is_stub}: facts from {@link Run#classFacts} + {@link Run#isStub};
 *     implements edges, {@code prune_references}, dangling-edge removal and sorting in the assembler.</li>
 * <li>{@code build_graph} output / stats: {@link DefaultGraphAssembler}, {@link JsonWriter}.</li>
 * </ul>
 * Differences by design: PyCharm resolution is tried first, so more calls resolve (union members, typed
 * attributes, library return types); external names are PyCharm's real qualified names (e.g.
 * {@code sqlalchemy.orm.session.Session.execute}) when the library is resolvable.
 */
final class PsiFileAnalyzer implements FileAnalyzer {
    @Override
    public FileResult analyze(PyFile file, String relPath) {
        FileResult r = new FileResult();
        r.relPath = relPath;
        String root = rootOf(file, relPath);
        String projectName = root.substring(root.lastIndexOf('/') + 1);
        r.module = ModuleNames.moduleName(relPath, projectName);
        PyFacts facts = new PyFacts();
        facts.isPackage = ModuleNames.isPackageFile(relPath);
        r.facts.put(PyFacts.KEY, facts);
        Run run = new Run(file, r, facts, root, projectName);
        try {
            run.run();
        } catch (Throwable t) {
            if (t instanceof ControlFlowException) throw t;
            r.errors.add(t.getClass().getSimpleName() + " while analysing: " + t.getMessage());
        }
        run.finish();
        return r;
    }

    /** Absolute project root: the file path minus {@code relPath} (same root the engine computed relPath against). */
    static String rootOf(PyFile file, String relPath) {
        VirtualFile vf = file.getOriginalFile().getVirtualFile();
        if (vf != null) {
            String p = vf.getPath();
            if (p.endsWith("/" + relPath)) return p.substring(0, p.length() - relPath.length() - 1);
        }
        String base = file.getProject().getBasePath();
        return base == null ? "" : base;
    }

    /** One file's analysis state. */
    static final class Run {
        final PyFile file;
        final FileResult r;
        final PyFacts facts;
        final PyResolver res;
        final String rel;
        final String module;
        final String moduleId;
        final boolean isTest;
        final int[] lineStarts;
        final int nlines;
        final Map<String, Map<String, Object>> edges = new LinkedHashMap<>();
        final Map<String, Set<String>> extSeen = new LinkedHashMap<>();
        /** Collected defs in analyzer.py collect order (pre-order). */
        final List<PsiElement> defs = new ArrayList<>();
        final Map<PsiElement, String> defIds = new LinkedHashMap<>();
        final Set<String> nodeIds = new LinkedHashSet<>();
        int phase = 0;
        int seq = 0;

        Run(PyFile file, FileResult r, PyFacts facts, String root, String projectName) {
            this.file = file;
            this.r = r;
            this.facts = facts;
            this.rel = r.relPath;
            this.module = r.module;
            this.moduleId = module + ":" + module;
            this.isTest = ModuleNames.isTestFile(rel);
            this.res = new PyResolver(file, root, projectName, module, facts.isPackage);
            CharSequence text = file.getViewProvider().getContents();
            List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') starts.add(i + 1);
            lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
            int nl = starts.size() - 1;
            nlines = Math.max(1, nl + (text.length() == 0 || text.charAt(text.length() - 1) == '\n' ? 0 : 1));
        }

        int line(int offset) {
            int lo = 0, hi = lineStarts.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (lineStarts[mid] <= offset) lo = mid; else hi = mid - 1;
            }
            return lo + 1;
        }

        int line(PsiElement e) {
            return line(e.getTextRange().getStartOffset());
        }

        void run() {
            syntaxError();
            collect();
            // analyzer.py resolve_calls: module-level code first, then every def in collect order
            phase = 0;
            unit(moduleId, List.of(file));
            phase = 1;
            for (PsiElement d : defs) {
                ProgressManager.checkCanceled();
                defUnit(d);
            }
            ownPrefixes();
        }

        void finish() {
            for (Map<String, Object> e : edges.values()) r.edges.add(e);
            r.dependsOnModules.addAll(res.dependsOn);
        }

        // ------------------------------------------------------------------ errors

        void syntaxError() {
            PsiErrorElement err = PsiTreeUtil.findChildOfType(file, PsiErrorElement.class);
            if (err != null) {
                String base = rel.substring(rel.lastIndexOf('/') + 1);
                r.errors.add("SyntaxError: " + err.getErrorDescription() + " (" + base + ", line " + line(err) + ")");
            }
        }

        // ------------------------------------------------------------------ pass 1: collect

        void collect() {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", moduleId);
            n.put("kind", "module");
            n.put("name", ModuleNames.lastSegment(module));
            n.put("qualname", module);
            n.put("module", module);
            n.put("file", rel);
            n.put("line", 1);
            n.put("end_line", nlines);
            n.put("signature", "");
            n.put("doc", firstDocLine(file.getDocStringValue()));
            n.put("decorators", List.of());
            n.put("is_async", false);
            n.put("class_name", null);
            n.put("package", ModuleNames.packageOf(module));
            n.put("loc", nlines);
            n.put("entrypoint", null);
            n.put("is_test", isTest);
            r.nodes.add(n);
            nodeIds.add(moduleId);
            boolean[] main = {false};
            walkDefs(file, main);
            if (main[0]) n.put("entrypoint", ep("main", "python -m " + module));
        }

        private void walkDefs(PsiElement e, boolean[] main) {
            for (PsiElement c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c instanceof PyFunction || c instanceof PyClass) {
                    ProgressManager.checkCanceled();
                    if (newNode(c)) walkDefs(c, main);
                } else if (c instanceof PyIfStatement ifs && PyResolver.scopeOwner(ifs) == file) {
                    if (isMainGuard(ifs.getIfPart().getCondition())) main[0] = true;
                    for (PyIfPart p : ifs.getElifParts()) if (isMainGuard(p.getCondition())) main[0] = true;
                    walkDefs(c, main);
                } else if (!(c instanceof PyExpression)) {
                    walkDefs(c, main);
                }
            }
        }

        /** analyzer.py {@code _is_main_guard}. */
        static boolean isMainGuard(PyExpression test) {
            test = PyUnparse.unparen(test);
            if (!(test instanceof PyBinaryExpression b) || !b.isOperator("==")) return false;
            PyExpression l = PyUnparse.unparen(b.getLeftExpression()), rt = PyUnparse.unparen(b.getRightExpression());
            if (l instanceof PyBinaryExpression || rt instanceof PyBinaryExpression) return false;
            return isNameRef(l, "__name__") && "__main__".equals(PyUnparse.strConst(rt))
                    || isNameRef(rt, "__name__") && "__main__".equals(PyUnparse.strConst(l));
        }

        static boolean isNameRef(PyExpression e, String name) {
            return e instanceof PyReferenceExpression ref && !ref.isQualified() && name.equals(ref.getReferencedName());
        }

        /** analyzer.py {@code _new_node}; false for a redefinition (first one wins, its body is not analysed). */
        boolean newNode(PsiElement def) {
            String qual = PyResolver.qualnameOf(def);
            if (qual == null) return false;
            String id = module + ":" + qual;
            if (!nodeIds.add(id)) return false;
            PsiElement owner = PyResolver.scopeOwner(def);
            String kind = def instanceof PyClass ? "class" : owner instanceof PyClass ? "method" : "function";
            Map<String, Object> n = new LinkedHashMap<>();
            PyDecoratorList dl = ((PyDecoratable) def).getDecoratorList();
            List<String> decorators = new ArrayList<>();
            if (dl != null) for (PyDecorator d : dl.getDecorators()) decorators.add(PyUnparse.decorator(d));
            int line = defLine(def);
            int end = Math.max(line, endLine(def));
            n.put("id", id);
            n.put("kind", kind);
            n.put("name", PyResolver.nameOf(def));
            n.put("qualname", qual);
            n.put("module", module);
            n.put("file", rel);
            n.put("line", line);
            n.put("end_line", end);
            n.put("signature", def instanceof PyFunction f ? PyUnparse.signature(f) : PyUnparse.classSignature((PyClass) def));
            n.put("doc", firstDocLine(((PyDocStringOwner) def).getDocStringValue()));
            n.put("decorators", decorators);
            n.put("is_async", def instanceof PyFunction f && f.isAsync());
            n.put("class_name", "method".equals(kind) ? PyResolver.qualnameOf(owner) : null);
            n.put("package", ModuleNames.packageOf(module));
            n.put("loc", end - line + 1);
            n.put("entrypoint", null);
            n.put("is_test", isTest);
            r.nodes.add(n);
            defs.add(def);
            defIds.put(def, id);
            if (def instanceof PyFunction f) detectEntrypoint(f, n, kind, owner);
            return true;
        }

        int defLine(PsiElement def) {
            PsiElement nameId = def instanceof PyFunction f ? f.getNameIdentifier() : ((PyClass) def).getNameIdentifier();
            return line(nameId != null ? nameId : def);
        }

        /** Last line of real code in the def (trailing comments / whitespace excluded, like ast end_lineno). */
        int endLine(PsiElement def) {
            PsiElement last = PsiTreeUtil.getDeepestLast(def);
            while (last != null && last != def && (last instanceof PsiWhiteSpace || last instanceof PsiComment
                    || last.getTextLength() == 0)) {
                last = PsiTreeUtil.prevLeaf(last);
            }
            com.intellij.openapi.util.TextRange tr = last == null ? null : last.getTextRange();
            if (tr == null || !def.getTextRange().contains(tr)) tr = def.getTextRange();
            return line(Math.max(tr.getStartOffset(), tr.getEndOffset() - 1));
        }

        static String firstDocLine(String doc) {
            if (doc == null) return "";
            for (String l : doc.strip().split("\\R")) {
                if (!l.isBlank()) return l.strip();
            }
            return "";
        }

        // ------------------------------------------------------------------ entrypoints

        /** analyzer.py {@code _detect_entrypoint} (first matching decorator wins, then app factories). */
        void detectEntrypoint(PyFunction fn, Map<String, Object> node, String kind, PsiElement owner) {
            PyDecoratorList dl = fn.getDecoratorList();
            String qual = (String) node.get("qualname");
            if (dl != null) {
                for (PyDecorator d : dl.getDecorators()) {
                    PyExpression func = d.getCallee();
                    String last = PyUnparse.lastName(func);
                    if (last == null) continue;
                    boolean isCall = d.hasArgumentList();
                    boolean isAttr = PyUnparse.unparen(func) instanceof PyReferenceExpression fr && fr.isQualified();
                    PyArgumentList args = d.getArgumentList();
                    if (isCall && isAttr && PyRules.ROUTE_ATTRS.contains(last)) {
                        List<PyExpression> pos = positional(args);
                        PyExpression pathExpr = !pos.isEmpty() ? pos.get(0) : first(kw(args, "path"), kw(args, "rule"));
                        String path = pathExpr == null ? null : PyUnparse.strConst(pathExpr);
                        if (path == null || !(path.isEmpty() || path.startsWith("/"))) continue;
                        String etype;
                        List<String> methods = new ArrayList<>();
                        if (last.startsWith("websocket")) {
                            etype = "websocket";
                            methods.add("WEBSOCKET");
                        } else if (PyRules.HTTP_METHODS.contains(last)) {
                            etype = "http";
                            methods.add(last.toUpperCase(java.util.Locale.ROOT));
                        } else {
                            etype = "http";
                            PyExpression m = PyUnparse.unparen(kw(args, "methods"));
                            if (m instanceof PyListLiteralExpression || m instanceof PyTupleExpression
                                    || m instanceof PySetLiteralExpression) {
                                for (PyExpression x : ((PySequenceExpression) m).getElements()) {
                                    String s = PyUnparse.strConst(x);
                                    if (s != null && !s.isEmpty()) methods.add(s.toUpperCase(java.util.Locale.ROOT));
                                }
                            }
                            if (methods.isEmpty()) methods.add("GET");
                        }
                        String joined = String.join(",", methods);
                        PyExpression recv = ((PyReferenceExpression) PyUnparse.unparen(func)).getQualifier();
                        facts.routes.add(new PyFacts.Route((String) node.get("id"), routerKey(recv), etype, joined, path,
                                (Integer) node.get("line")));
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("type", etype);
                        e.put("label", "");
                        e.put("method", joined);
                        e.put("path", path);
                        node.put("entrypoint", e);
                        return;
                    }
                    if ("task".equals(last) && isAttr || "shared_task".equals(last)) {
                        String label = isCall ? strOrNull(kw(args, "name")) : null;
                        node.put("entrypoint", ep("celery_task", label != null ? label : qual));
                        return;
                    }
                    if (("command".equals(last) || "group".equals(last)) && (isAttr || isCall)) {
                        String label = null;
                        if (isCall) {
                            List<PyExpression> pos = positional(args);
                            label = pos.isEmpty() ? null : PyUnparse.strConst(pos.get(0));
                            if (label == null) label = strOrNull(kw(args, "name"));
                        }
                        node.put("entrypoint", ep("cli", label != null ? label : fn.getName().replace('_', '-')));
                        return;
                    }
                    if (PyRules.HOOK_ATTRS.contains(last) && (isAttr || "listens_for".equals(last))) {
                        String arg = "";
                        List<PyExpression> pos = isCall ? positional(args) : List.of();
                        if (!pos.isEmpty()) {
                            arg = constOrText(pos.get(0));
                            if ("listens_for".equals(last) && pos.size() > 1) arg = arg + " " + constOrText(pos.get(1));
                        }
                        String label = (last + " " + arg).strip();
                        node.put("entrypoint", ep("hook", label.length() > 80 ? label.substring(0, 80) : label));
                        return;
                    }
                }
            }
            if ("function".equals(kind) && owner instanceof PsiFile) {
                String ret = null;
                PyAnnotation ann = fn.getAnnotation();
                PyExpression rv = ann == null ? null : ann.getValue();
                if (rv != null) {
                    ret = rv instanceof PyReferenceExpression ? PyUnparse.lastName(rv) : null;
                    String s = rv instanceof PyStringLiteralExpression ? PyUnparse.strConst(rv) : null;
                    if (s != null) ret = s.substring(s.lastIndexOf('.') + 1);
                }
                String name = fn.getName();
                if (PyRules.APP_FACTORY_RE.matcher(name).matches() || "create_app".equals(name)
                        || (ret != null && PyRules.APP_TYPES.contains(ret))) {
                    node.put("entrypoint", ep("app_factory", name + "()" + (ret != null ? " → " + ret : "")));
                }
            }
        }

        private static Map<String, Object> ep(String type, String label) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("label", label);
            return m;
        }

        private static String strOrNull(PyExpression e) {
            return e == null ? null : PyUnparse.strConst(e);
        }

        private static String constOrText(PyExpression e) {
            String s = PyUnparse.strConst(e);
            return s != null ? s : PyUnparse.unparse(e);
        }

        static PyExpression first(PyExpression a, PyExpression b) {
            return a != null ? a : b;
        }

        /** Positional (non-keyword) arguments, analyzer.py {@code call.args}. */
        static List<PyExpression> positional(PyArgumentList args) {
            List<PyExpression> out = new ArrayList<>();
            if (args == null) return out;
            for (PyExpression a : args.getArguments()) {
                if (a instanceof PyKeywordArgument || (a instanceof PyStarArgument s && s.isKeyword())) continue;
                out.add(a);
            }
            return out;
        }

        /** analyzer.py {@code _kw}: value of keyword argument {@code name}. */
        static PyExpression kw(PyArgumentList args, String name) {
            if (args == null) return null;
            for (PyExpression a : args.getArguments()) {
                if (a instanceof PyKeywordArgument k && name.equals(k.getKeyword())) return k.getValueExpression();
            }
            return null;
        }

        // ------------------------------------------------------------------ router keys / prefixes

        /** analyzer.py {@code _router_key}: identity of the assigned variable a bare name refers to. */
        PyFacts.RouterKey routerKey(PyExpression expr) {
            expr = PyUnparse.unparen(expr);
            if (!(expr instanceof PyReferenceExpression ref) || ref.isQualified()) return null;
            for (PsiElement el : res.resolveRaw(ref)) {
                if (el instanceof PyImportElement ie) {
                    PsiElement t = null;
                    try {
                        t = ie.resolve();
                    } catch (RuntimeException ex) {
                        if (ex instanceof ControlFlowException) throw ex;
                    }
                    if (t == null || t instanceof PyImportElement) return null;
                    el = t;
                }
                if (el instanceof PyTargetExpression t && !t.isQualified()) {
                    PsiElement p = t.getParent();
                    if (!(p instanceof PyAssignmentStatement || p instanceof PyWithItem)) return null;
                    return keyFor(t, t.getName());
                }
                return null;
            }
            return null;
        }

        private PyFacts.RouterKey keyFor(PsiElement at, String name) {
            String m = res.moduleOf(at);
            if (m == null || name == null) return null;
            PsiElement owner = PyResolver.scopeOwner(at);
            String ownerKey = owner instanceof PsiFile ? "" : res.idOf(owner);
            if (ownerKey == null) return null;
            return new PyFacts.RouterKey(m, ownerKey, name);
        }

        /** analyzer.py collect: {@code x = Call(..., prefix="/p")} (plain assignment, any scope; last one wins). */
        void ownPrefixes() {
            for (PyAssignmentStatement st : PsiTreeUtil.findChildrenOfType(file, PyAssignmentStatement.class)) {
                ProgressManager.checkCanceled();
                if (st.getAnnotation() != null) continue;
                if (!(PyUnparse.unparen(st.getAssignedValue()) instanceof PyCallExpression call)) continue;
                PyExpression pre = first(kw(call.getArgumentList(), "prefix"), kw(call.getArgumentList(), "url_prefix"));
                String s = pre == null ? null : PyUnparse.strConst(pre);
                if (s == null) continue;
                for (PyExpression t : st.getRawTargets()) {
                    if (t instanceof PyTargetExpression te && !te.isQualified()) {
                        PyFacts.RouterKey k = keyFor(te, te.getName());
                        if (k != null) facts.ownPrefix.put(k, s);
                    }
                }
            }
        }

        // ------------------------------------------------------------------ edges / externals

        void edge(String src, String tgt, String kind, int line) {
            if (src == null || tgt == null) return;
            String key = src + "\u0000" + tgt + "\u0000" + kind;
            Map<String, Object> e = edges.get(key);
            if (e == null || line < (Integer) e.get("line")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("source", src);
                m.put("target", tgt);
                m.put("kind", kind);
                m.put("file", rel);
                m.put("line", line);
                edges.put(key, m);
            }
        }

        /** Edge to a project def; remembers an external name in case the target's file is not part of the graph. */
        void edgeTo(String src, PsiElement target, String kind, int line) {
            String tid = res.idOf(target);
            if (tid == null || src == null) return;
            boolean fresh = !edges.containsKey(src + "\u0000" + tid + "\u0000" + kind);
            edge(src, tid, kind, line);
            if (fresh && !rel.equals(res.relPathOf(target))) {
                facts.fallback.add(new PyFacts.Fallback(src, tid, kind, res.qualDotted(target)));
            }
        }

        void external(String owner, String name) {
            Set<String> seen = extSeen.computeIfAbsent(owner, k -> new java.util.HashSet<>());
            if (!seen.add(name)) return;
            List<String> lst = r.externalCalls.computeIfAbsent(owner, k -> new ArrayList<>());
            if (lst.size() < PyRules.MAX_EXTERNAL) lst.add(name);
        }

        // ------------------------------------------------------------------ pass 2: units

        /** analyzer.py {@code _def_unit} (+ {@code _decorator}). */
        void defUnit(PsiElement def) {
            String id = defIds.get(def);
            PyDecoratorList dl = ((PyDecoratable) def).getDecoratorList();
            PyDecorator[] decs = dl == null ? new PyDecorator[0] : dl.getDecorators();
            for (PyDecorator dec : decs) {
                if (dec.hasArgumentList()) unit(id, argRoots(dec.getArgumentList()));
                PyResolver.Val t = res.eval(dec.getCallee());
                if (t != null && t.isDefFunction()) edgeTo(id, t.el, "calls", line(dec));
            }
            PsiElement owner = PyResolver.scopeOwner(def);
            if (owner instanceof PyFunction && decs.length > 0 && defIds.containsKey(owner)) {
                edge(defIds.get(owner), id, "references", line(decs[0]));
            }
            if (def instanceof PyClass cls) {
                List<String> baseIds = new ArrayList<>();
                for (PyClass b : res.projectBases(cls)) {
                    edgeTo(id, b, "inherits", defLine(cls));
                    String bid = res.idOf(b);
                    if (bid != null && !baseIds.contains(bid)) baseIds.add(bid);
                }
                PyArgumentList sup = cls.getSuperClassExpressionList();
                if (sup != null) unit(id, argRoots(sup));
                unit(id, List.of(cls.getStatementList()));
                classFacts(cls, id, baseIds);
                return;
            }
            PyFunction fn = (PyFunction) def;
            List<PsiElement> defaults = new ArrayList<>();
            for (PyParameter p : fn.getParameterList().getParameters()) {
                PyExpression d = p.getDefaultValue();
                if (d != null) defaults.add(d);
            }
            unit(id, defaults);
            for (PyParameter p : fn.getParameterList().getParameters()) {
                PyNamedParameter np = p.getAsNamed();
                if (np == null || np.isPositionalContainer() || np.isKeywordContainer()) continue;
                PyExpression ann = np.getAnnotation() == null ? null : np.getAnnotation().getValue();
                PyExpression dflt = PyUnparse.unparen(np.getDefaultValue());
                if (dflt instanceof PyCallExpression dc && isDepends(dc) && dependsArg(dc) == null) {
                    dependsOnAnnotation(id, ann, line(dc));
                }
                if (ann != null) {
                    for (PyCallExpression c : calls(List.of(ann))) {
                        if (!isDepends(c)) continue;
                        if (dependsArg(c) != null) handleDepends(id, c);
                        else dependsOnAnnotation(id, ann, line(c));
                    }
                }
            }
            unit(id, List.of(fn.getStatementList()));
        }

        /** Positional arguments and keyword values of a call / decorator / class bases. */
        static List<PsiElement> argRoots(PyArgumentList args) {
            List<PsiElement> out = new ArrayList<>();
            if (args == null) return out;
            for (PyExpression a : args.getArguments()) {
                if (a instanceof PyKeywordArgument k) {
                    if (k.getValueExpression() != null) out.add(k.getValueExpression());
                } else {
                    out.add(a);
                }
            }
            return out;
        }

        static boolean isDepends(PyCallExpression c) {
            String n = PyUnparse.lastName(c.getCallee());
            return n != null && PyRules.DEPENDS_NAMES.contains(n);
        }

        static PyExpression dependsArg(PyCallExpression c) {
            List<PyExpression> pos = positional(c.getArgumentList());
            return !pos.isEmpty() ? pos.get(0) : kw(c.getArgumentList(), "dependency");
        }

        /** analyzer.py {@code _iter_calls}: pre-order calls under {@code roots}, not entering nested def/class. */
        static List<PyCallExpression> calls(List<? extends PsiElement> roots) {
            List<PyCallExpression> out = new ArrayList<>();
            for (PsiElement root : roots) collectCalls(root, out);
            return out;
        }

        private static void collectCalls(PsiElement e, List<PyCallExpression> out) {
            if (e == null) return;
            if (e instanceof PyCallExpression c && !(e instanceof PyDecorator)) out.add(c);
            for (PsiElement ch = e.getFirstChild(); ch != null; ch = ch.getNextSibling()) {
                if (ch instanceof PyFunction || ch instanceof PyClass) continue;
                collectCalls(ch, out);
            }
        }

        /** analyzer.py {@code _unit}: edges + external calls for the calls under {@code roots}, then callbacks. */
        void unit(String owner, List<? extends PsiElement> roots) {
            for (PyCallExpression call : calls(roots)) {
                ProgressManager.checkCanceled();
                PyExpression callee = call.getCallee();
                if (callee == null) continue;
                int ln = line(call);
                String last = PyUnparse.lastName(callee);
                if (last != null && PyRules.DEPENDS_NAMES.contains(last)) {
                    PyResolver.Val t = res.eval(callee);
                    if (t == null || t.k != PyResolver.Val.K.DEF) {
                        handleDepends(owner, call);
                        continue;
                    }
                }
                if (PyUnparse.unparen(callee) instanceof PyReferenceExpression cr && cr.isQualified()
                        && ("include_router".equals(last) || "register_blueprint".equals(last))) {
                    handleInclude(owner, call, cr);
                    continue;
                }
                PyResolver.Val t = res.eval(callee);
                if (t != null) {
                    switch (t.k) {
                        case DEF -> {
                            callDef(owner, t.el, ln);
                            for (PyResolver.Val alt : res.attrAlternatives(callee)) if (alt.el != t.el) callDef(owner, alt.el, ln);
                            facts.callsResolved++;
                            continue;
                        }
                        case TASK -> {
                            edgeTo(owner, t.el, "calls", ln);
                            facts.callsResolved++;
                            continue;
                        }
                        case INST -> {
                            PyFunction m = res.projectMethod((PyClass) t.el, "__call__");
                            if (m != null) {
                                edgeTo(owner, m, "calls", ln);
                                facts.callsResolved++;
                                continue;
                            }
                        }
                        case BUILTIN -> {
                            facts.callsBuiltin++;
                            continue;
                        }
                        default -> { }
                    }
                }
                facts.callsExternal++;
                String name;
                if (t != null && (t.k == PyResolver.Val.K.EXT || t.k == PyResolver.Val.K.EXTINST)) {
                    name = t.k == PyResolver.Val.K.EXTINST ? t.name + "()" : t.name;
                    if (t.sym && t.k == PyResolver.Val.K.EXT) facts.symbolic.add(new PyFacts.Symbolic(owner, t.name, ln));
                } else if (t != null && t.k == PyResolver.Val.K.MOD) {
                    name = t.name;
                } else {
                    name = PyUnparse.dotted(callee);
                    facts.callsUnknown++;
                }
                external(owner, name.length() > 160 ? name.substring(0, 160) : name);
            }
            refs(owner, roots);
        }

        private void callDef(String owner, PsiElement el, int ln) {
            if (el instanceof PyClass c) {
                edgeTo(owner, c, "instantiates", ln);
                PyFunction init = res.projectMethod(c, "__init__");
                if (init != null) edgeTo(owner, init, "calls", ln);
            } else {
                edgeTo(owner, el, "calls", ln);
            }
        }

        /** analyzer.py {@code _handle_depends}. */
        void handleDepends(String owner, PyCallExpression call) {
            PyExpression arg = dependsArg(call);
            if (arg == null) return;
            PyResolver.Val t = res.eval(arg);
            PsiElement target = null;
            if (t != null && t.k == PyResolver.Val.K.DEF) {
                target = t.el;
            } else if (t != null && t.k == PyResolver.Val.K.INST) {
                PyResolver.Val m = res.classAttr((PyClass) t.el, "__call__", false);
                target = m != null && m.k == PyResolver.Val.K.DEF ? m.el : t.el;
            } else if (PyUnparse.unparen(arg) instanceof PyReferenceExpression ref && !ref.isQualified()) {
                // var assigned from a factory call whose result could not be typed -> the factory
                for (PsiElement el : res.resolveRaw(ref)) {
                    if (el instanceof PyTargetExpression te
                            && PyUnparse.unparen(te.findAssignedValue()) instanceof PyCallExpression vc) {
                        PyResolver.Val f = res.eval(vc.getCallee());
                        if (f != null && f.k == PyResolver.Val.K.DEF) {
                            target = f.el;
                            break;
                        }
                    }
                }
            }
            if (target != null) {
                edgeTo(owner, target, "depends", line(call));
                facts.callsResolved++;
            } else {
                facts.callsExternal++;
                String n = t != null && t.k == PyResolver.Val.K.EXT ? t.name : PyUnparse.dotted(arg);
                external(owner, "Depends(" + n + ")");
            }
        }

        /** analyzer.py {@code _depends_on_annotation}: {@code Depends()} without argument -> the annotated class. */
        void dependsOnAnnotation(String owner, PyExpression ann, int line) {
            if (ann == null) return;
            PyResolver.Val t = res.annType(ann);
            if (t != null && t.k == PyResolver.Val.K.INST) {
                edgeTo(owner, t.el, "depends", line);
                facts.callsResolved++;
            }
        }

        /** analyzer.py {@code _handle_include}. */
        void handleInclude(String owner, PyCallExpression call, PyReferenceExpression callee) {
            PyArgumentList args = call.getArgumentList();
            PyFacts.RouterKey parent = routerKey(callee.getQualifier());
            List<PyExpression> pos = positional(args);
            PyExpression childExpr = !pos.isEmpty() ? pos.get(0) : first(kw(args, "router"), kw(args, "blueprint"));
            PyFacts.RouterKey child = childExpr == null ? null : routerKey(childExpr);
            PyExpression pre = first(kw(args, "prefix"), kw(args, "url_prefix"));
            String extra = "";
            if (pre != null) {
                String s = PyUnparse.strConst(pre);
                extra = s == null ? "" : s;
            }
            int ln = line(call);
            if (parent != null && child != null) {
                facts.includes.add(new PyFacts.Include(parent, child, extra, phase, seq++));
                if (!parent.module().equals(child.module())) {
                    edge(parent.module() + ":" + parent.module(), child.module() + ":" + child.module(),
                            "includes_router", ln);
                }
            }
            // the function doing the include (e.g. create_app) also leads to the included router module
            if (child != null && !owner.equals(moduleId) && !child.module().equals(module)) {
                edge(owner, child.module() + ":" + child.module(), "includes_router", ln);
            }
        }

        /** analyzer.py {@code _refs} / {@code _iter_refs}: internal defs used as values (callbacks). */
        void refs(String owner, List<? extends PsiElement> roots) {
            List<Object[]> found = new ArrayList<>();
            for (PsiElement root : roots) collectRefs(root, found);
            for (Object[] f : found) {
                ProgressManager.checkCanceled();
                PyExpression expr = (PyExpression) f[0];
                String ctx = (String) f[1];
                int ln = (Integer) f[2];
                PyResolver.Val t = res.eval(expr);
                if (t == null || t.k != PyResolver.Val.K.DEF) continue;
                refDef(owner, t.el, ctx, ln);
                for (PyResolver.Val alt : res.attrAlternatives(expr)) if (alt.el != t.el) refDef(owner, alt.el, ctx, ln);
            }
        }

        private void refDef(String owner, PsiElement el, String ctx, int ln) {
            String tid = res.idOf(el);
            if (tid == null || tid.equals(owner)) return;
            if (el instanceof PyClass c && (!"arg".equals(ctx) || !callbackClass(c))) return;
            edgeTo(owner, el, "references", ln);
        }

        private void collectRefs(PsiElement n, List<Object[]> out) {
            if (n == null) return;
            if (n instanceof PyCallExpression c && !(n instanceof PyDecorator)) {
                String fname = PyUnparse.lastName(c.getCallee());
                if (fname == null || !(PyRules.NO_REF_CALLS.contains(fname) || PyRules.DEPENDS_NAMES.contains(fname))) {
                    PyArgumentList al = c.getArgumentList();
                    if (al != null) {
                        for (PyExpression a : al.getArguments()) {
                            PyExpression v = a instanceof PyKeywordArgument k ? k.getValueExpression()
                                    : a instanceof PyStarArgument s ? PsiTreeUtil.getChildOfType(s, PyExpression.class) : a;
                            v = PyUnparse.unparen(v);
                            if (v instanceof PyReferenceExpression) out.add(new Object[]{v, "arg", line(c)});
                        }
                    }
                }
            } else if (n instanceof PyDictLiteralExpression d) {
                for (PsiElement ch : d.getChildren()) {
                    if (ch instanceof PyKeyValueExpression kv) {
                        PyExpression v = PyUnparse.unparen(kv.getValue());
                        if (v instanceof PyReferenceExpression) out.add(new Object[]{v, "elt", line(v)});
                    }
                }
            } else if (n instanceof PyListLiteralExpression || n instanceof PySetLiteralExpression
                    || n instanceof PyTupleExpression) {
                for (PyExpression x : ((PySequenceExpression) n).getElements()) {
                    PyExpression v = PyUnparse.unparen(x);
                    if (v instanceof PyReferenceExpression) out.add(new Object[]{v, "elt", line(v)});
                }
            } else if (n instanceof PyReturnStatement rs) {
                PyExpression v = PyUnparse.unparen(rs.getExpression());
                if (v instanceof PyReferenceExpression) out.add(new Object[]{v, "ret", line(rs)});
            } else if (n instanceof PyYieldExpression y && !y.isDelegating()) {
                PyExpression v = PyUnparse.unparen(y.getExpression());
                if (v instanceof PyReferenceExpression) out.add(new Object[]{v, "ret", line(y)});
            }
            for (PsiElement ch = n.getFirstChild(); ch != null; ch = ch.getNextSibling()) {
                if (ch instanceof PyFunction || ch instanceof PyClass) continue;
                collectRefs(ch, out);
            }
        }

        /** analyzer.py {@code _callback_class}: own non-dunder methods or {@code __call__}. */
        static boolean callbackClass(PyClass c) {
            for (Map.Entry<String, PsiElement> m : PyResolver.members(c).entrySet()) {
                if (m.getValue() instanceof PyFunction && (!m.getKey().startsWith("__") || m.getKey().equals("__call__"))) {
                    return true;
                }
            }
            return false;
        }

        // ------------------------------------------------------------------ implementations (facts)

        void classFacts(PyClass cls, String id, List<String> baseIds) {
            Map<String, String> members = new LinkedHashMap<>();
            Map<String, PyFacts.Method> methods = new LinkedHashMap<>();
            for (Map.Entry<String, PsiElement> m : PyResolver.members(cls).entrySet()) {
                String mid = defIds.get(m.getValue());
                if (mid == null) mid = res.idOf(m.getValue());
                if (mid == null) continue;
                members.put(m.getKey(), mid);
                if (m.getValue() instanceof PyFunction f) {
                    methods.put(m.getKey(), new PyFacts.Method(mid, defLine(f), isStub(f)));
                }
            }
            facts.classes.add(new PyFacts.ClassInfo(id, List.copyOf(baseIds), PyResolver.isProtocol(cls),
                    java.util.Collections.unmodifiableMap(members), java.util.Collections.unmodifiableMap(methods),
                    PyResolver.attributeNames(cls)));
        }

        /** analyzer.py {@code _is_stub}. */
        static boolean isStub(PyFunction fn) {
            PyDecoratorList dl = fn.getDecoratorList();
            if (dl != null) {
                for (PyDecorator d : dl.getDecorators()) {
                    String l = PyUnparse.lastName(d.getCallee());
                    if ("abstractmethod".equals(l) || "abstractproperty".equals(l)) return true;
                }
            }
            List<PyStatement> body = new ArrayList<>(List.of(fn.getStatementList().getStatements()));
            if (!body.isEmpty() && body.get(0) instanceof PyExpressionStatement es
                    && PyUnparse.strConst(es.getExpression()) != null) {
                body.remove(0);
            }
            if (body.isEmpty()) return true;
            if (body.size() == 1) {
                PyStatement b = body.get(0);
                if (b instanceof PyPassStatement) return true;
                if (b instanceof PyExpressionStatement es && es.getExpression() instanceof PyEllipsisLiteralExpression) {
                    return true;
                }
                if (b instanceof PyRaiseStatement rs && rs.getExpressions().length > 0) {
                    PyExpression ex = PyUnparse.unparen(rs.getExpressions()[0]);
                    PyExpression f = ex instanceof PyCallExpression c ? c.getCallee() : ex;
                    return "NotImplementedError".equals(PyUnparse.lastName(f));
                }
            }
            return false;
        }
    }
}
