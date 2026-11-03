package com.sushil.codemap.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything one Python file contributes to the graph. Produced by {@link FileAnalyzer} inside a read action,
 * consumed (without PSI) by {@link GraphAssembler}. Plain data, safe to cache across read actions.
 *
 * CONTRACT (engine relies on these fields; the analyzer may add more fields for its cross-file passes):
 *   relPath          POSIX path relative to the project root, e.g. "gis_api/main.py"
 *   module           dotted module name, e.g. "gis_api.main"
 *   dependsOnModules project module names whose declarations this file's edges/facts refer to
 *                    (imports, resolved call targets) - used to re-analyse dependants after a change
 *   errors           parse/analysis problems for this file (shown in the UI's error list)
 */
public final class FileResult {
    public String relPath;
    public String module;
    public final List<Map<String, Object>> nodes = new ArrayList<>();   // SPEC node objects
    public final List<Map<String, Object>> edges = new ArrayList<>();   // SPEC edge objects
    public final Map<String, List<String>> externalCalls = new LinkedHashMap<>();
    public final Set<String> dependsOnModules = new LinkedHashSet<>();
    public final List<String> errors = new ArrayList<>();
    /** Analyzer-private facts for cross-file passes (router prefixes, include_router, protocol members, ...). */
    public final Map<String, Object> facts = new LinkedHashMap<>();
}
