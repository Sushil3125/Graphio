package com.sushil.codemap.analysis;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.ResolveResult;
import com.intellij.psi.util.QualifiedName;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyBuiltinCache;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.resolve.QualifiedNameFinder;
import com.jetbrains.python.psi.resolve.QualifiedRatedResolveResult;
import com.jetbrains.python.psi.types.PyClassType;
import com.jetbrains.python.psi.types.PyFunctionType;
import com.jetbrains.python.psi.types.PyModuleType;
import com.jetbrains.python.psi.types.PyType;
import com.jetbrains.python.psi.types.PyUnionType;
import com.jetbrains.python.psi.types.TypeEvalContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-file value evaluator: the port of analyzer.py {@code ev / eval_entry / getattr_t / call_result / func_return /
 * ann_type / class_attr / mro} with name binding delegated to PyCharm ({@code multiFollowAssignmentsChain} with a
 * code-analysis {@link TypeEvalContext}) and PSI types used as a last resort. Lives only for one
 * {@link PsiFileAnalyzer#analyze} call; never escapes into results.
 */
final class PyResolver {
    /** Abstract value of an expression (analyzer.py tuples ("node", id), ("inst", id), ...). */
    static final class Val {
        enum K { DEF, INST, MOD, EXT, EXTINST, TASK, BUILTIN, SUPER }

        final K k;
        /** DEF: project PyFunction/PyClass; INST/SUPER: project PyClass; MOD: project PyFile; TASK: project PyFunction. */
        final PsiElement el;
        /** EXT/EXTINST/BUILTIN: dotted name; MOD: module name. */
        final String name;
        /** EXT derived from an import PyCharm could not resolve (candidate for the assembler's symbolic lookup). */
        final boolean sym;

        private Val(K k, PsiElement el, String name, boolean sym) {
            this.k = k; this.el = el; this.name = name; this.sym = sym;
        }

        static Val def(PsiElement e) { return new Val(K.DEF, e, null, false); }
        static Val inst(PyClass c) { return new Val(K.INST, c, null, false); }
        static Val mod(PsiElement f, String name) { return new Val(K.MOD, f, name, false); }
        static Val ext(String n, boolean sym) { return new Val(K.EXT, null, n, sym); }
        static Val extInst(String n, boolean sym) { return new Val(K.EXTINST, null, n, sym); }
        static Val task(PsiElement fn) { return new Val(K.TASK, fn, null, false); }
        static Val builtin(String n) { return new Val(K.BUILTIN, null, n, false); }
        static Val sup(PyClass c) { return new Val(K.SUPER, c, null, false); }

        boolean isDefClass() { return k == K.DEF && el instanceof PyClass; }
        boolean isDefFunction() { return k == K.DEF && el instanceof PyFunction; }
    }

    private static final int MAX_DEPTH = 48;
    private static final Object NULL = new Object();

    final PyFile file;
    final String rootSlash;
    final String projectName;
    final String module;
    final boolean isPkg;
    final TypeEvalContext ctx;
    final PyResolveContext rctx;
    /** Project modules this file's results depend on (other than itself). */
    final Set<String> dependsOn = new java.util.TreeSet<>();

    private final Map<PsiFile, Object> relCache = new HashMap<>();
    private final Map<PsiElement, Object> memo = new HashMap<>();
    private final Map<PsiElement, Object> retMemo = new HashMap<>();
    private final Map<PsiElement, List<PyClass>> mroMemo = new HashMap<>();
    private final Set<PsiElement> inProgress = new HashSet<>();
    private Map<String, String> importMap;
    private int depth;

    PyResolver(PyFile file, String root, String projectName, String module, boolean isPkg) {
        this.file = file;
        this.rootSlash = root.endsWith("/") ? root : root + "/";
        this.projectName = projectName;
        this.module = module;
        this.isPkg = isPkg;
        this.ctx = TypeEvalContext.codeAnalysis(file.getProject(), file);
        this.rctx = PyResolveContext.defaultContext(ctx);
    }

    // ------------------------------------------------------------------ project membership / ids

    /** Project-relative path of the file containing {@code e}, or null when outside the analysed project. */
    String relPathOf(PsiElement e) {
        if (e == null) return null;
        VirtualFile vf;
        PsiFile pf = null;
        if (e instanceof PsiDirectory d) {
            vf = d.getVirtualFile();
        } else {
            pf = e.getContainingFile();
            if (pf == null) return null;
            Object cached = relCache.get(pf);
            if (cached != null) return cached == NULL ? null : (String) cached;
            PsiFile orig = pf.getOriginalFile();
            vf = orig.getVirtualFile();
        }
        String rel = null;
        if (vf != null) {
            String p = vf.getPath();
            if (p.startsWith(rootSlash)) {
                String r = p.substring(rootSlash.length());
                if (ModuleNames.isAnalyzablePath(r) || e instanceof PsiDirectory) rel = r;
            }
        }
        if (pf != null) {
            relCache.put(pf, rel == null ? NULL : rel);
            if (rel != null && pf != file) {
                String m = ModuleNames.moduleName(rel, projectName);
                if (!m.equals(module)) dependsOn.add(m);
            }
        }
        return rel;
    }

    boolean isProject(PsiElement e) {
        return !(e instanceof PsiDirectory) && relPathOf(e) != null;
    }

    String moduleOf(PsiElement e) {
        String rel = relPathOf(e);
        return rel == null ? null : ModuleNames.moduleName(rel, projectName);
    }

    /** analyzer.py scope owner: the PyFunction/PyClass whose body (statement list) contains {@code e}, else the file. */
    static PsiElement scopeOwner(PsiElement e) {
        PsiElement cur = e;
        while (cur != null && !(cur instanceof PsiFile)) {
            PsiElement p = cur.getParent();
            if ((p instanceof PyFunction || p instanceof PyClass) && cur instanceof PyStatementList) return p;
            cur = p;
        }
        return cur;
    }

    /** {@code Class.method}, {@code outer.<locals>.inner}. */
    static String qualnameOf(PsiElement def) {
        String name = nameOf(def);
        if (name == null) return null;
        StringBuilder sb = new StringBuilder(name);
        PsiElement owner = scopeOwner(def);
        while (owner instanceof PyFunction || owner instanceof PyClass) {
            String n = nameOf(owner);
            if (n == null) return null;
            sb.insert(0, owner instanceof PyClass ? n + "." : n + ".<locals>.");
            owner = scopeOwner(owner);
        }
        return sb.toString();
    }

    static String nameOf(PsiElement e) {
        if (e instanceof PyFunction f) return f.getName();
        if (e instanceof PyClass c) return c.getName();
        return null;
    }

    /** Node id of a project def ({@code <module>:<qualname>}), or null. */
    String idOf(PsiElement def) {
        if (!(def instanceof PyFunction || def instanceof PyClass)) return null;
        String m = moduleOf(def);
        if (m == null) return null;
        String q = qualnameOf(def);
        return q == null ? null : m + ":" + q;
    }

    /** analyzer.py {@code _qual_dotted}. */
    String qualDotted(PsiElement def) {
        String m = moduleOf(def);
        String q = qualnameOf(def);
        if (m == null || q == null) return extName(def);
        return m + "." + q;
    }

    // ------------------------------------------------------------------ names of library objects

    String extName(PsiElement el) {
        if (el instanceof PyFunction || el instanceof PyClass || el instanceof PyTargetExpression) {
            String n = ((PyQualifiedNameOwner) el).getName();
            if (isInBuiltins(el)) {
                PyClass c = el instanceof PyFunction f ? f.getContainingClass()
                        : el instanceof PyTargetExpression t ? t.getContainingClass() : null;
                return c != null ? c.getName() + "." + n : n;
            }
            String q = ((PyQualifiedNameOwner) el).getQualifiedName();
            return q != null ? q : n;
        }
        if (el instanceof PsiFileSystemItem fs) return moduleQName(fs);
        return el == null ? "?" : el.getText();
    }

    private static String moduleQName(PsiFileSystemItem f) {
        try {
            QualifiedName q = QualifiedNameFinder.findShortestImportableQName(f);
            if (q != null && q.getComponentCount() > 0) return q.toString();
        } catch (RuntimeException ex) {
            if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException) throw ex;
        }
        String n = f.getName();
        return n.endsWith(".py") ? n.substring(0, n.length() - 3) : n.endsWith(".pyi") ? n.substring(0, n.length() - 4) : n;
    }

    private static boolean isInBuiltins(PsiElement el) {
        try {
            return PyBuiltinCache.getInstance(el).isBuiltin(el);
        } catch (RuntimeException ex) {
            if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException) throw ex;
            return false;
        }
    }

    // ------------------------------------------------------------------ PSI resolution

    /** Targets of a reference after following plain assignment chains, best rated first. */
    List<PsiElement> resolve(PyReferenceExpression r) {
        List<QualifiedRatedResolveResult> rs;
        try {
            rs = r.multiFollowAssignmentsChain(rctx);
        } catch (RuntimeException ex) {
            if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException) throw ex;
            return List.of();
        }
        if (rs == null || rs.isEmpty()) return List.of();
        List<QualifiedRatedResolveResult> sorted = new ArrayList<>(rs);
        // best rated first; equal rates in source order (analyzer.py takes the first binding in collect order)
        sorted.sort((a, b) -> {
            int c = Integer.compare(b.getRate(), a.getRate());
            if (c != 0) return c;
            PsiElement x = a.getElement(), y = b.getElement();
            if (x == null || y == null || x.getContainingFile() != y.getContainingFile()) return 0;
            return Integer.compare(x.getTextOffset(), y.getTextOffset());
        });
        List<PsiElement> out = new ArrayList<>(sorted.size());
        for (QualifiedRatedResolveResult x : sorted) {
            PsiElement el = x.getElement();
            if (el != null && x.isValidResult() && !out.contains(el)) out.add(el);
        }
        return out;
    }

    /** Binding elements of a name (no assignment following). */
    List<PsiElement> resolveRaw(PyReferenceExpression r) {
        try {
            PsiPolyVariantReference ref = r.getReference(rctx);
            List<PsiElement> out = new ArrayList<>();
            for (ResolveResult x : ref.multiResolve(false)) {
                PsiElement el = x.getElement();
                if (el != null && x.isValidResult() && !out.contains(el)) out.add(el);
            }
            return out;
        } catch (RuntimeException ex) {
            if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException) throw ex;
            return List.of();
        }
    }

    // ------------------------------------------------------------------ ev

    Val eval(PyExpression e) {
        if (e == null) return null;
        ProgressManager.checkCanceled();
        if (depth > MAX_DEPTH) return null;
        depth++;
        try {
            return eval0(e);
        } finally {
            depth--;
        }
    }

    private Val eval0(PyExpression e) {
        if (e instanceof PyParenthesizedExpression p) {
            PyExpression in = p.getContainedExpression();
            return in instanceof PyTupleExpression ? null : eval(in);
        }
        if (e instanceof PyAssignmentExpression w) return eval(w.getAssignedValue());
        if (e instanceof PyPrefixExpression p && p.getPsiOperator() != null
                && "await".equals(p.getPsiOperator().getText())) {
            return eval(p.getOperand());
        }
        if (e instanceof PyReferenceExpression r) return evalRef(r);
        if (e instanceof PyCallExpression c) {
            PyExpression callee = c.getCallee();
            if (callee instanceof PyReferenceExpression cr && !cr.isQualified() && "super".equals(cr.getReferencedName())) {
                PyClass cls = enclosingMethodClass(c);
                if (cls != null && isProject(cls)) {
                    boolean shadowed = false;
                    for (PsiElement el : resolveRaw(cr)) if (isProject(el)) shadowed = true;
                    if (!shadowed) return Val.sup(cls);
                }
            }
            Val v = callResult(eval(callee));
            if (v == null) v = fromType(typeOf(c));
            return v;
        }
        return null;
    }

    /** analyzer.py {@code scope.cls}: the class of the enclosing method (also for closures inside methods). */
    static PyClass enclosingMethodClass(PsiElement e) {
        PsiElement o = scopeOwner(e);
        while (o instanceof PyFunction || o instanceof PyClass) {
            PsiElement up = scopeOwner(o);
            if (o instanceof PyFunction && up instanceof PyClass c) return c;
            if (o instanceof PyClass) return null;
            o = up;
        }
        return null;
    }

    private Val evalRef(PyReferenceExpression r) {
        String name = r.getReferencedName();
        PyExpression q = r.getQualifier();
        if (name == null) return null;
        if (q != null && PyRules.CELERY_CALL_ATTRS.contains(name)) {
            Val qv = eval(q);
            if (qv != null && qv.isDefFunction()) return Val.task(qv.el);
        }
        if (q instanceof PyReferenceExpression) {
            // project module attribute (`_state.get_current_app`): analyzer.py lookup_module binding priority
            Val qv = eval(q);
            if (qv != null && qv.k == Val.K.MOD) {
                Val g = getattr(qv, name);
                if (g != null && g.k != Val.K.EXT) return g;
            }
        }
        if (q == null) {
            // analyzer.py eval_binding: the name's bindings by priority (def > import > param > annotation > assign),
            // first one that evaluates wins; e.g. `X: type[Body] = File` is typed by its annotation.
            List<PsiElement> raw = new ArrayList<>(resolveRaw(r));
            if (raw.size() > 1 || raw.size() == 1 && raw.get(0) instanceof PyTargetExpression t0 && t0.getAnnotation() != null) {
                raw.sort(java.util.Comparator.comparingInt(PyResolver::bindingPriority));
                for (PsiElement el : raw) {
                    Val v = evalElement(el);
                    if (v != null) return v;
                }
            }
        }
        List<PsiElement> els = resolve(r);
        // multiFollowAssignmentsChain drops some bindings (e.g. `with Repo() as r`: __enter__ is untyped); the raw
        // binding elements are then evaluated with the analyzer.py rules (with-target -> context expression).
        if (els.isEmpty()) els = resolveRaw(r);
        Val best = null;
        for (PsiElement el : els) {
            Val v = evalElement(el);
            if (v == null) continue;
            if (v.k == Val.K.DEF) return v;
            if (best == null) best = v;
        }
        if (best != null) return best;
        if (q != null) {
            Val g = getattr(eval(q), name);
            if (g != null) return g;
        } else if (els.isEmpty()) {
            String imp = importFallback(name);
            if (imp != null) return Val.ext(imp, true);
            if (PyRules.BUILTIN_NAMES.contains(name)) return Val.builtin(name);
            return null;
        }
        Val ty = fromType(typeOf(r));
        if (ty == null && q == null) ty = otherBindings(name, els);
        return ty;
    }

    /**
     * analyzer.py binds names flow-insensitively: when none of the bindings reaching this use evaluates (e.g. a
     * tuple-unpacking loop target over a list), the other plain assignments of the name in the same scope are tried
     * in source order ({@code f = self.factory(x)} ... {@code for _, f in pairs: f()}).
     */
    private Val otherBindings(String name, List<PsiElement> reaching) {
        if (reaching.isEmpty()) return null;
        PsiElement owner = scopeOwner(reaching.get(0));
        if (!(owner instanceof PyFunction) || !isProject(owner)) return null;
        for (PsiElement el : reaching) if (!(el instanceof PyTargetExpression) || scopeOwner(el) != owner) return null;
        for (PyTargetExpression t : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(owner, PyTargetExpression.class)) {
            if (t.isQualified() || !name.equals(t.getName()) || reaching.contains(t) || scopeOwner(t) != owner) continue;
            if (!(t.getParent() instanceof PyAssignmentStatement)) continue;
            Val v = evalElement(t);
            if (v != null) return v;
        }
        return null;
    }

    /** analyzer.py {@code eval_binding} priorities. */
    private static int bindingPriority(PsiElement el) {
        if (el instanceof PyFunction || el instanceof PyClass) return 0;
        if (el instanceof PyImportElement || el instanceof PsiFileSystemItem) return 1;
        if (el instanceof PyNamedParameter p) return p.getAnnotation() == null ? 1 : 2;
        if (el instanceof PyTargetExpression t) return t.getAnnotation() != null ? 3 : 4;
        return 5;
    }

    PyType typeOf(PyTypedElement e) {
        try {
            return ctx.getType(e);
        } catch (RuntimeException ex) {
            if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException) throw ex;
            return null;
        }
    }

    /** analyzer.py eval_entry for one binding element found by PyCharm. */
    Val evalElement(PsiElement el) {
        if (el == null) return null;
        if (el instanceof PyFunction || el instanceof PyClass) {
            if (isProject(el)) return Val.def(el);
            if (isInBuiltins(el) && scopeOwner(el) instanceof PsiFile) return Val.builtin(nameOf(el));
            return Val.ext(extName(el), false);
        }
        if (el instanceof PyFile f) {
            String m = moduleOf(f);
            return m != null ? Val.mod(f, m) : Val.ext(moduleQName(f), false);
        }
        if (el instanceof PsiDirectory d) {
            PsiFile init = d.findFile("__init__.py");
            if (init instanceof PyFile pf && isProject(pf)) return Val.mod(pf, moduleOf(pf));
            return Val.ext(moduleQName(d), false);
        }
        if (el instanceof PyTargetExpression t) {
            if (!isProject(t)) {
                if (isInBuiltins(t)) return Val.builtin(t.getName());
                String q = t.getQualifiedName();
                return Val.ext(q != null ? q : t.getName(), false);
            }
            return memoized(memo, t, () -> evalTarget(t));
        }
        if (el instanceof PyNamedParameter p) return memoized(memo, p, () -> evalParam(p));
        if (el instanceof PyExpression x && !(el instanceof PyReferenceExpression)) {
            // followAssignmentsChain stops at a non-reference assigned value and returns it (x = Foo(), with Foo() as x)
            PsiElement parent = x.getParent();
            PyTargetExpression target = null;
            if (parent instanceof PyAssignmentStatement as && as.getAssignedValue() == x) {
                for (PyExpression t : as.getRawTargets()) {
                    if (t instanceof PyTargetExpression te) { target = te; break; }
                }
            } else if (parent instanceof PyWithItem wi && wi.getTarget() instanceof PyTargetExpression te) {
                target = te;
            }
            if (target != null) return evalElement(target);
            return isProject(x) ? memoized(memo, x, () -> eval(x)) : null;
        }
        if (el instanceof PyImportElement ie) {
            // analyzer.py lookup_module + eval_binding: of the module's bindings for the name, by priority
            // (a def before a plain assignment; source order among equals)
            List<PsiElement> targets = new ArrayList<>();
            try {
                for (ResolveResult x : ie.multiResolve()) {
                    PsiElement t = x.getElement();
                    if (t != null && t != ie && !(t instanceof PyImportElement) && !targets.contains(t)) targets.add(t);
                }
            } catch (RuntimeException ex) {
                if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException) throw ex;
            }
            if (targets.size() > 1) {
                targets.sort(java.util.Comparator.comparingInt(PyResolver::bindingPriority)
                        .thenComparingInt(t -> t.getContainingFile() == null ? 0 : t.getTextOffset()));
            }
            for (PsiElement t : targets) {
                Val v = evalElement(t);
                if (v != null) return v;
            }
            if (!targets.isEmpty()) return null;
            String n = importName(ie);
            return n == null ? null : Val.ext(n, true);
        }
        return null;
    }

    private interface Thunk { Val get(); }

    private Val memoized(Map<PsiElement, Object> m, PsiElement key, Thunk f) {
        Object c = m.get(key);
        if (c != null) return c == NULL ? null : (Val) c;
        if (!inProgress.add(key)) return null;
        Val v;
        try {
            v = f.get();
        } finally {
            inProgress.remove(key);
        }
        m.put(key, v == null ? NULL : v);
        return v;
    }

    private Val evalTarget(PyTargetExpression t) {
        PyAnnotation ann = t.getAnnotation();
        if (ann != null && ann.getValue() != null) {
            Val a = annType(ann.getValue());
            if (a != null) return a;
        }
        PsiElement parent = t.getParent();
        if (parent instanceof PyWithItem wi && wi.getTarget() == t) return eval(wi.getExpression());
        PyExpression v = null;
        try {
            v = t.findAssignedValue();
        } catch (RuntimeException ex) {
            if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException) throw ex;
        }
        if (v != null && v != t) {
            Val r = eval(v);
            if (r != null) return r;
        }
        return fromType(typeOf(t));
    }

    private Val evalParam(PyNamedParameter p) {
        if (p.isPositionalContainer() || p.isKeywordContainer()) return null;
        PyAnnotation ann = p.getAnnotation();
        PyFunction fn = p.getParent() instanceof PyParameterList pl ? pl.getContainingFunction() : null;
        if (ann == null && fn != null && isProject(fn) && scopeOwner(fn) instanceof PyClass cls) {
            PyParameter[] ps = fn.getParameterList().getParameters();
            if (ps.length > 0 && ps[0] == p && !hasDecorator(fn, "staticmethod")) {
                return hasDecorator(fn, "classmethod") ? Val.def(cls) : Val.inst(cls);
            }
        }
        if (ann == null || ann.getValue() == null) return null;
        Val a = annType(ann.getValue());
        if (a == null) a = fromType(typeOf(p));
        return a;
    }

    static boolean hasDecorator(PyDecoratable d, String lastName) {
        PyDecoratorList dl = d.getDecoratorList();
        if (dl == null) return false;
        for (PyDecorator dec : dl.getDecorators()) {
            if (lastName.equals(PyUnparse.lastName(dec.getCallee()))) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ imports (unresolved by PyCharm)

    /** analyzer.py binding for import entries: ("import", name) / ("from", src, name) as an external dotted name. */
    String importName(PyImportElement ie) {
        PyStatement st = ie.getContainingImportStatement();
        QualifiedName qn = ie.getImportedQName();
        if (qn == null) return null;
        if (st instanceof PyFromImportStatement fs) {
            QualifiedName src = fs.getImportSourceQName();
            String s = absImport(src == null ? null : src.toString(), fs.getRelativeLevel());
            return s.isEmpty() ? qn.toString() : s + "." + qn;
        }
        if (ie.getAsName() != null) return qn.toString();
        return qn.getFirstComponent();
    }

    /** analyzer.py {@code _abs_import}. */
    String absImport(String mod, int level) {
        if (level <= 0) return mod == null ? "" : mod;
        List<String> base = new ArrayList<>(List.of(module.split("\\.")));
        if (!isPkg && !base.isEmpty()) base.remove(base.size() - 1);
        if (level > 1) {
            int cut = level - 1;
            base = cut <= base.size() ? new ArrayList<>(base.subList(0, base.size() - cut)) : new ArrayList<>();
        }
        if (mod != null && !mod.isEmpty()) base.addAll(List.of(mod.split("\\.")));
        return String.join(".", base);
    }

    private String importFallback(String name) {
        if (importMap == null) {
            importMap = new HashMap<>();
            for (PyImportStatementBase st : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(file, PyImportStatementBase.class)) {
                for (PyImportElement ie : st.getImportElements()) {
                    String visible = ie.getVisibleName();
                    String n = importName(ie);
                    if (visible != null && n != null) importMap.putIfAbsent(visible, n);
                }
            }
        }
        return importMap.get(name);
    }

    // ------------------------------------------------------------------ getattr / call_result / returns

    Val getattr(Val t, String attr) {
        if (t == null || attr == null) return null;
        switch (t.k) {
            case MOD -> {
                if (t.el instanceof PyFile f) {
                    try {
                        // analyzer.py lookup_module: the module's bindings by eval_binding priority, source order
                        List<RatedLike> rs = rated(f.multiResolveName(attr));
                        rs.sort(java.util.Comparator.comparingInt((RatedLike x) -> bindingPriority(x.el()))
                                .thenComparingInt(x -> x.el().getContainingFile() == f ? x.el().getTextOffset() : 0));
                        for (RatedLike x : rs) {
                            Val v = evalElement(x.el());
                            if (v != null) return v;
                        }
                        // name not bound in the package: its submodule (analyzer.py resolve_mod(f"{m}.{name}"))
                        if (rs.isEmpty() && "__init__.py".equals(f.getName()) && f.getContainingDirectory() != null) {
                            PsiDirectory dir = f.getContainingDirectory();
                            PsiFile sub = dir.findFile(attr + ".py");
                            if (sub == null) {
                                PsiDirectory sd = dir.findSubdirectory(attr);
                                if (sd != null) sub = sd.findFile("__init__.py");
                            }
                            if (sub instanceof PyFile sf) {
                                String m = moduleOf(sf);
                                if (m != null) return Val.mod(sf, m);
                            }
                        }
                    } catch (RuntimeException ex) {
                        if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException) throw ex;
                    }
                }
                return Val.ext(t.name + "." + attr, false);
            }
            case EXT, EXTINST -> { return Val.ext(t.name + "." + attr, t.sym); }
            case BUILTIN -> { return PyRules.BUILTIN_TYPES.contains(t.name) ? Val.ext(t.name + "." + attr, false) : null; }
            case DEF -> {
                if (t.el instanceof PyClass c) {
                    Val r = classAttr(c, attr, false);
                    return r != null ? r : Val.ext(qualDotted(c) + "." + attr, false);
                }
                if (PyRules.CELERY_CALL_ATTRS.contains(attr)) return Val.task(t.el);
                return Val.ext(qualDotted(t.el) + "." + attr, false);
            }
            case INST -> {
                Val r = classAttr((PyClass) t.el, attr, false);
                return r != null ? r : Val.ext(qualDotted(t.el) + "." + attr, false);
            }
            case SUPER -> { return classAttr((PyClass) t.el, attr, true); }
            default -> { return null; }
        }
    }

    private record RatedLike(PsiElement el) {}

    private static List<RatedLike> rated(List<? extends ResolveResult> rs) {
        List<RatedLike> out = new ArrayList<>();
        if (rs != null) for (ResolveResult r : rs) if (r.getElement() != null) out.add(new RatedLike(r.getElement()));
        return out;
    }

    Val callResult(Val t) {
        if (t == null) return null;
        switch (t.k) {
            case DEF -> {
                if (t.el instanceof PyClass c) return Val.inst(c);
                return funcReturn((PyFunction) t.el);
            }
            case EXT, EXTINST -> {
                String last = t.name.substring(t.name.lastIndexOf('.') + 1);
                boolean upper = !last.isEmpty() && Character.isUpperCase(last.charAt(0));
                return Val.extInst(t.k == Val.K.EXT && upper ? t.name : t.name + "()", t.sym);
            }
            case BUILTIN -> { return PyRules.BUILTIN_TYPES.contains(t.name) ? Val.extInst(t.name, false) : null; }
            case INST -> {
                Val m = classAttr((PyClass) t.el, "__call__", false);
                if (m != null && m.isDefFunction()) return callResult(m);
                return null;
            }
            default -> { return null; }
        }
    }

    Val funcReturn(PyFunction fn) {
        return memoized(retMemo, fn, () -> {
            Val res = null;
            PyAnnotation ann = fn.getAnnotation();
            if (ann != null && ann.getValue() != null) {
                res = annType(ann.getValue());
                if (res != null && !(res.k == Val.K.INST || res.k == Val.K.EXTINST || res.k == Val.K.DEF)) res = null;
            }
            if (res == null) {
                List<PyReturnStatement> rets = new ArrayList<>();
                collectReturns(fn.getStatementList(), rets);
                for (PyReturnStatement st : rets) {
                    PyExpression v = st.getExpression();
                    if (v == null) continue;
                    Val r = eval(v);
                    if (r != null && (r.k == Val.K.INST || r.k == Val.K.EXTINST || r.k == Val.K.DEF)) {
                        res = r;
                        break;
                    }
                }
            }
            return res;
        });
    }

    /** analyzer.py {@code _iter_returns}: return statements of a body, not inside nested defs/classes/lambdas. */
    static void collectReturns(PsiElement e, List<PyReturnStatement> out) {
        if (e == null) return;
        for (PsiElement c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof PyReturnStatement r) out.add(r);
            else if (c instanceof PyFunction || c instanceof PyClass || c instanceof PyExpression) continue;
            else collectReturns(c, out);
        }
    }

    /** analyzer.py {@code ann_type}: the instance type an annotation denotes. */
    Val annType(PyExpression ann) {
        if (ann == null || depth > MAX_DEPTH) return null;
        depth++;
        try {
            return annType0(PyUnparse.unparen(ann));
        } finally {
            depth--;
        }
    }

    private Val annType0(PyExpression e) {
        if (e instanceof PyStringLiteralExpression s) {
            String text = PyUnparse.strConst(s);
            if (text == null || text.isBlank()) return null;
            try {
                PyExpression inner = PyUtil.createExpressionFromFragment(text.strip(), s);
                return inner == null ? null : annType(inner);
            } catch (RuntimeException ex) {
                if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException) throw ex;
                return null;
            }
        }
        if (e instanceof PyBinaryExpression b && b.isOperator("|")) {
            Val l = annType(b.getLeftExpression());
            return l != null ? l : annType(b.getRightExpression());
        }
        if (e instanceof PySubscriptionExpression s) {
            String base = PyUnparse.lastName(s.getOperand());
            PyExpression idx = PyUnparse.unparen(s.getIndexExpression());
            PyExpression[] elts = idx instanceof PyTupleExpression t ? t.getElements()
                    : idx == null ? new PyExpression[0] : new PyExpression[]{idx};
            if (base == null) return null;
            if (PyRules.WRAPPER_ANNOTATIONS.contains(base)) return elts.length > 0 ? annType(elts[0]) : null;
            if (base.equals("Union")) {
                for (PyExpression x : elts) {
                    Val r = annType(x);
                    if (r != null) return r;
                }
                return null;
            }
            if ((base.equals("type") || base.equals("Type")) && elts.length > 0) {
                Val r = annType(elts[0]);
                return r != null && r.k == Val.K.INST ? Val.def(r.el) : null;
            }
            return null;
        }
        if (e instanceof PyReferenceExpression) {
            Val t = eval(e);
            if (t == null) return null;
            if (t.isDefClass()) return Val.inst((PyClass) t.el);
            if (t.k == Val.K.EXT) return Val.extInst(t.name, t.sym);
            if (t.k == Val.K.BUILTIN && PyRules.BUILTIN_TYPES.contains(t.name)) return Val.extInst(t.name, false);
            return null;
        }
        return null;
    }

    /** PSI type -> value (last resort, where analyzer.py would have given up). */
    Val fromType(PyType t) {
        if (t == null) return null;
        if (t instanceof PyUnionType u) {
            Val first = null;
            for (PyType m : u.getMembers()) {
                Val v = fromType(m);
                if (v == null) continue;
                if (v.k == Val.K.INST || v.k == Val.K.DEF) return v;
                if (first == null) first = v;
            }
            return first;
        }
        if (t instanceof PyModuleType mt) return evalElement(mt.getModule());
        if (t instanceof PyClassType ct) {
            PyClass c = ct.getPyClass();
            if (c == null) return null;
            String n = c.getName();
            if (n == null || "NoneType".equals(n) || "None".equals(n)) return null;
            boolean def = ct.isDefinition();
            if (isProject(c)) return def ? Val.def(c) : Val.inst(c);
            if (isInBuiltins(c) && scopeOwner(c) instanceof PsiFile) return def ? Val.builtin(n) : Val.extInst(n, false);
            String q = extName(c);
            return def ? Val.ext(q, false) : Val.extInst(q, false);
        }
        if (t instanceof PyFunctionType ft && ft.getCallable() instanceof PyFunction f) return evalElement(f);
        return null;
    }

    // ------------------------------------------------------------------ classes

    /** analyzer.py {@code mro}: the class plus project base classes, depth-first left-to-right. */
    List<PyClass> mro(PyClass cls) {
        List<PyClass> cached = mroMemo.get(cls);
        if (cached != null) return cached;
        List<PyClass> out = new ArrayList<>();
        out.add(cls);
        if (!inProgress.add(cls)) return out;
        try {
            for (PyClass b : projectBases(cls)) {
                for (PyClass x : mro(b)) if (!out.contains(x)) out.add(x);
            }
        } finally {
            inProgress.remove(cls);
        }
        mroMemo.put(cls, out);
        return out;
    }

    /** Base class expressions ({@code Generic[T]} -> {@code Generic}) that resolve to project classes, in order. */
    List<PyClass> projectBases(PyClass cls) {
        List<PyClass> out = new ArrayList<>();
        for (PyExpression b : baseExpressions(cls)) {
            Val t = eval(b);
            if (t != null && t.isDefClass() && !out.contains((PyClass) t.el)) out.add((PyClass) t.el);
        }
        return out;
    }

    static List<PyExpression> baseExpressions(PyClass cls) {
        List<PyExpression> out = new ArrayList<>();
        for (PyExpression b : cls.getSuperClassExpressions()) {
            if (b instanceof PyKeywordArgument || b instanceof PyStarArgument) continue;
            b = PyUnparse.unparen(b);
            if (b instanceof PySubscriptionExpression s) b = s.getOperand();
            if (b != null) out.add(b);
        }
        return out;
    }

    /** analyzer.py {@code class_attr}: first class in the project MRO defining {@code attr}. */
    Val classAttr(PyClass cls, String attr, boolean skipSelf) {
        List<PyClass> chain = mro(cls);
        for (int i = skipSelf ? 1 : 0; i < chain.size(); i++) {
            PyClass c = chain.get(i);
            PsiElement member = ownMember(c, attr);
            if (member != null) return Val.def(member);
            PyTargetExpression a = ownAttribute(c, attr);
            if (a != null) return evalElement(a);
        }
        return null;
    }

    /** First method / nested class named {@code name} defined in the class body (analyzer.py {@code members}). */
    static PsiElement ownMember(PyClass c, String name) {
        Map<String, PsiElement> m = members(c);
        return m.get(name);
    }

    static Map<String, PsiElement> members(PyClass c) {
        Map<String, PsiElement> out = new LinkedHashMap<>();
        collectMembers(c.getStatementList(), out);
        return out;
    }

    private static void collectMembers(PsiElement e, Map<String, PsiElement> out) {
        if (e == null) return;
        for (PsiElement ch = e.getFirstChild(); ch != null; ch = ch.getNextSibling()) {
            if (ch instanceof PyFunction || ch instanceof PyClass) {
                String n = nameOf(ch);
                if (n != null) out.putIfAbsent(n, ch);
            } else if (!(ch instanceof PyExpression)) {
                collectMembers(ch, out);
            }
        }
    }

    /** Class-level or {@code self.x} attribute of exactly this class (analyzer.py {@code attrs}). */
    PyTargetExpression ownAttribute(PyClass c, String name) {
        for (PyTargetExpression t : c.getClassAttributes()) if (name.equals(t.getName())) return t;
        for (PyTargetExpression t : c.getInstanceAttributes()) if (name.equals(t.getName())) return t;
        return null;
    }

    /**
     * analyzer.py keeps every assignment of a class attribute ({@code attrs[name]} is a list): {@code task_cls = Task}
     * in the class body and {@code self.task_cls = TaskExtended} in a method are both possible values. PyCharm's resolve
     * yields one; this returns the project defs/classes of all the bindings of {@code obj.attr} when {@code obj} is a
     * project class or instance and the attribute (not a method) is assigned more than once. Source order.
     */
    List<Val> attrAlternatives(PyExpression callee) {
        PyExpression e = PyUnparse.unparen(callee);
        if (!(e instanceof PyReferenceExpression r) || r.getQualifier() == null || r.getReferencedName() == null) return List.of();
        String attr = r.getReferencedName();
        Val qv = eval(r.getQualifier());
        if (qv == null || !(qv.k == Val.K.INST || qv.k == Val.K.DEF && qv.el instanceof PyClass)) return List.of();
        for (PyClass c : mro((PyClass) qv.el)) {
            if (ownMember(c, attr) != null) return List.of();
            List<PyTargetExpression> ts = new ArrayList<>();
            for (PyTargetExpression t : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(c, PyTargetExpression.class)) {
                if (!attr.equals(t.getName())) continue;
                PyExpression tq = t.getQualifier();
                if (tq == null ? scopeOwner(t) == c
                        : tq instanceof PyReferenceExpression sr && !sr.isQualified() && enclosingMethodClass(t) == c
                          && isSelfRef(sr, c)) ts.add(t);
            }
            if (ts.isEmpty()) continue;
            if (ts.size() < 2) return List.of();
            List<Val> out = new ArrayList<>();
            Set<PsiElement> seen = new HashSet<>();
            for (PyTargetExpression t : ts) {
                Val v = evalElement(t);
                if (v != null && v.k == Val.K.DEF && seen.add(v.el)) out.add(v);
            }
            return out;
        }
        return List.of();
    }

    /** {@code sr} names the first parameter of an instance method of {@code c}. */
    private static boolean isSelfRef(PyReferenceExpression sr, PyClass c) {
        PyFunction fn = com.intellij.psi.util.PsiTreeUtil.getParentOfType(sr, PyFunction.class);
        if (fn == null || scopeOwner(fn) != c || hasDecorator(fn, "staticmethod") || hasDecorator(fn, "classmethod")) return false;
        PyParameter[] ps = fn.getParameterList().getParameters();
        return ps.length > 0 && ps[0].getName() != null && ps[0].getName().equals(sr.getReferencedName());
    }

    /** Names of class-level and instance attributes. */
    static Set<String> attributeNames(PyClass c) {
        Set<String> out = new java.util.TreeSet<>();
        for (PyTargetExpression t : c.getClassAttributes()) if (t.getName() != null) out.add(t.getName());
        for (PyTargetExpression t : c.getInstanceAttributes()) if (t.getName() != null) out.add(t.getName());
        return Collections.unmodifiableSet(out);
    }

    /** {@code __init__}/{@code __call__} through the project MRO, only when it is a project method. */
    PyFunction projectMethod(PyClass cls, String name) {
        Val m = classAttr(cls, name, false);
        if (m != null && m.isDefFunction() && scopeOwner(m.el) instanceof PyClass) return (PyFunction) m.el;
        return null;
    }

    /** Is any declared base named {@code Protocol} (analyzer.py protocol detection is textual). */
    static boolean isProtocol(PyClass c) {
        for (PyExpression b : baseExpressions(c)) if ("Protocol".equals(PyUnparse.lastName(b))) return true;
        return false;
    }
}
