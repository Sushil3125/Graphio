package com.sushil.codemap.analysis;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Path / module-name rules shared by {@link PsiFileAnalyzer} and {@link DefaultGraphAssembler}
 * (analyzer.py: {@code DEFAULT_EXCLUDES}, {@code _is_test_file}, {@code _Analyzer.load}).
 */
final class ModuleNames {
    private ModuleNames() {}

    /** analyzer.py DEFAULT_EXCLUDES (directory names never analysed). */
    static final Set<String> DEFAULT_EXCLUDES = Set.of(
            ".git", ".hg", ".svn", ".venv", "venv", "env", ".env", "node_modules", "__pycache__",
            ".idea", ".vscode", "build", "dist", ".mypy_cache", ".pytest_cache", ".ruff_cache",
            ".tox", ".nox", ".eggs", "site-packages");

    private static final Set<String> TEST_DIRS = Set.of("tests", "test", "testing");

    /** {@code a/b/c.py -> a.b.c}, {@code a/b/__init__.py -> a.b}, root {@code __init__.py -> <project>}. */
    static String moduleName(String relPath, String projectName) {
        String p = stripExt(relPath);
        String[] parts = p.split("/");
        int n = parts.length;
        if (n > 0 && parts[n - 1].equals("__init__")) n--;
        String name = String.join(".", Arrays.asList(parts).subList(0, n));
        return name.isEmpty() ? projectName : name;
    }

    static boolean isPackageFile(String relPath) {
        String base = relPath.substring(relPath.lastIndexOf('/') + 1);
        return base.equals("__init__.py") || base.equals("__init__.pyi");
    }

    static String stripExt(String relPath) {
        if (relPath.endsWith(".pyi")) return relPath.substring(0, relPath.length() - 4);
        if (relPath.endsWith(".py")) return relPath.substring(0, relPath.length() - 3);
        return relPath;
    }

    /** "module minus last segment" (node field {@code package}). */
    static String packageOf(String module) {
        int i = module.lastIndexOf('.');
        return i < 0 ? "" : module.substring(0, i);
    }

    static String lastSegment(String module) {
        int i = module.lastIndexOf('.');
        return i < 0 ? module : module.substring(i + 1);
    }

    /** analyzer.py {@code _is_test_file}. */
    static boolean isTestFile(String rel) {
        String[] parts = rel.split("/");
        String base = parts[parts.length - 1];
        for (int i = 0; i < parts.length - 1; i++) if (TEST_DIRS.contains(parts[i])) return true;
        return base.startsWith("test_") || base.endsWith("_test.py") || base.equals("conftest.py");
    }

    /** A project-relative path that the Python analyzer would have walked (default excludes only; user globs are
     * applied by the engine's file set, dangling edges are turned into external calls by the assembler). */
    static boolean isAnalyzablePath(String rel) {
        if (!(rel.endsWith(".py") || rel.endsWith(".pyi"))) return false;
        String[] parts = rel.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (DEFAULT_EXCLUDES.contains(parts[i]) || parts[i].endsWith(".egg-info")) return false;
        }
        return true;
    }

    /** Ordering of {@code sorted(pathlib.Path)}: component-wise string comparison. */
    static final Comparator<String> PATH_ORDER = (a, b) -> {
        String[] x = a.split("/"), y = b.split("/");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = x[i].compareTo(y[i]);
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    };

    /**
     * analyzer.py {@code load()} import-name aliases: strip leading non-package directories (src/ layout,
     * scripts/ ...), e.g. {@code src/lib/core.py} is importable as {@code lib.core}. Only unambiguous aliases that do
     * not collide with a real module are kept. Returns alias -> real module name.
     */
    static Map<String, String> importAliases(Collection<String> relPaths, Set<String> modules, String projectName) {
        Set<String> pkgDirs = new HashSet<>();
        for (String r : relPaths) {
            if (r.endsWith("__init__.py")) pkgDirs.add(r.contains("/") ? r.substring(0, r.lastIndexOf('/')) : "");
        }
        Map<String, List<String>> candidates = new HashMap<>();
        for (String rel : relPaths) {
            if (!rel.endsWith(".py")) continue;
            String name = moduleName(rel, projectName);
            boolean isPkg = isPackageFile(rel);
            String[] all = rel.split("/");
            List<String> dirs = Arrays.asList(all).subList(0, all.length - 1);
            int k = dirs.size();
            while (k > 0 && pkgDirs.contains(String.join("/", dirs.subList(0, k)))) k--;
            java.util.ArrayList<String> imp = new java.util.ArrayList<>(dirs.subList(k, dirs.size()));
            if (!isPkg) imp.add(stripExt(all[all.length - 1]));
            String alias = String.join(".", imp);
            if (!alias.isEmpty() && !alias.equals(name)) {
                List<String> lst = candidates.computeIfAbsent(alias, x -> new java.util.ArrayList<>());
                if (!lst.contains(name)) lst.add(name);
            }
        }
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : candidates.entrySet()) {
            if (e.getValue().size() == 1 && !modules.contains(e.getKey())) out.put(e.getKey(), e.getValue().get(0));
        }
        return out;
    }
}
