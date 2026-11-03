package com.sushil.codemap.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyzer-private, PSI-free facts of one file for the cross-file passes in {@link DefaultGraphAssembler}.
 * Stored in {@code FileResult.facts} under {@link #KEY}. Immutable after {@link PsiFileAnalyzer} returns
 * (the assembler only reads it, results are cached by the engine).
 */
final class PyFacts {
    static final String KEY = "py";

    /** Router variable identity, analyzer.py {@code (module, key_owner, name)}; owner is "" for module scope. */
    record RouterKey(String module, String owner, String name) {}

    /** A route decorator found on {@code handlerId}; prefix + label are completed by the assembler. */
    record Route(String handlerId, RouterKey router, String etype, String methods, String path, int line) {}

    /** {@code parent.include_router(child, prefix=extra)}; (phase, seq) reproduce the Python analyzer's order
     * (phase 0 = module-level code, 1 = code inside defs; seq = order inside this file). */
    record Include(RouterKey parent, RouterKey child, String extra, int phase, int seq) {}

    record Method(String id, int line, boolean stub) {}

    /** A class: resolved project base ids (for MRO), own methods / nested classes / attributes. */
    record ClassInfo(String id, List<String> bases, boolean protocol, Map<String, String> members,
                     Map<String, Method> methods, Set<String> attrs) {}

    /** A call PyCharm could not resolve whose name looks importable ({@code from lib.core import f} in a src/ layout
     * that is not a source root); resolved against project modules (+ import aliases) by the assembler. */
    record Symbolic(String source, String symbol, int line) {}

    /** If edge (source, target, kind) dangles (target file not analysed) record {@code ext} as an external call. */
    record Fallback(String source, String target, String kind, String ext) {}

    boolean isPackage;
    final List<Route> routes = new ArrayList<>();
    final Map<RouterKey, String> ownPrefix = new LinkedHashMap<>();
    final List<Include> includes = new ArrayList<>();
    final List<ClassInfo> classes = new ArrayList<>();
    final List<Symbolic> symbolic = new ArrayList<>();
    final List<Fallback> fallback = new ArrayList<>();
    int callsResolved, callsExternal, callsBuiltin, callsUnknown;

    static PyFacts of(FileResult r) {
        Object o = r.facts.get(KEY);
        return o instanceof PyFacts f ? f : EMPTY;
    }

    private static final PyFacts EMPTY = new PyFacts();
}
