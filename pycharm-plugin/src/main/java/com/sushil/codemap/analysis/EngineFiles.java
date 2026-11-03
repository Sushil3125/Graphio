package com.sushil.codemap.analysis;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.ProjectScope;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Which project files the engine analyses, and their POSIX paths relative to the project root.
 * Mirrors {@code codemap/analyzer.py iter_python_files}: *.py only, default excluded dir names, *.egg-info,
 * user globs (fnmatch on the relative path or the bare name, or a relative-path prefix), applied to every
 * ancestor directory and to the file itself. On top of that: IDE content only (no excluded folders, no libraries).
 * All methods that touch the index need a read action (smart mode for {@link #collect}).
 */
final class EngineFiles {
    static final Set<String> DEFAULT_EXCLUDES = Set.of(
            ".git", ".hg", ".svn", ".venv", "venv", "env", ".env", "node_modules", "__pycache__",
            ".idea", ".vscode", "build", "dist", ".mypy_cache", ".pytest_cache", ".ruff_cache",
            ".tox", ".nox", ".eggs", "site-packages");

    private final Project project;
    private final @Nullable String basePath;
    private final List<String> rawGlobs;
    private final List<Pattern> globs = new ArrayList<>();
    private final List<String> prefixes = new ArrayList<>();

    EngineFiles(Project project, List<String> excludeGlobs) {
        this.project = project;
        String bp = project.getBasePath();
        this.basePath = bp == null ? null : stripSlash(bp.replace('\\', '/'));
        this.rawGlobs = List.copyOf(excludeGlobs);
        for (String g : excludeGlobs) {
            String p = stripSlash(g.trim());
            if (p.isEmpty()) continue;
            globs.add(fnmatchToRegex(p));
            prefixes.add(p + "/");
        }
    }

    List<String> globs() { return rawGlobs; }

    /** All analysable files, keyed (and sorted) by relPath. Needs a read action in smart mode. */
    TreeMap<String, VirtualFile> collect() {
        TreeMap<String, VirtualFile> out = new TreeMap<>();
        Collection<VirtualFile> all = FilenameIndex.getAllFilesByExt(project, "py", ProjectScope.getContentScope(project));
        for (VirtualFile vf : all) {
            ProgressManager.checkCanceled();
            String rel = relPathIfIncluded(vf);
            if (rel != null) out.put(rel, vf);
        }
        return out;
    }

    /** relPath if the file is one the engine analyses, else null. Needs a read action. */
    @Nullable String relPathIfIncluded(VirtualFile vf) {
        if (vf == null || !vf.isValid() || vf.isDirectory() || !"py".equals(vf.getExtension())) return null;
        ProjectFileIndex idx = ProjectFileIndex.getInstance(project);
        if (!idx.isInContent(vf) || idx.isExcluded(vf) || idx.isInLibrary(vf)) return null;
        String rel = relPath(vf);
        if (rel == null || isExcludedRel(rel)) return null;
        return rel;
    }

    /** POSIX path relative to the project base path, or to the file's content root when outside it. */
    @Nullable String relPath(VirtualFile vf) {
        String path = vf.getPath();
        if (basePath != null && path.startsWith(basePath + "/")) return path.substring(basePath.length() + 1);
        VirtualFile root = ProjectFileIndex.getInstance(project).getContentRootForFile(vf);
        if (root == null) return null;
        String r = VfsUtilCore.getRelativePath(vf, root, '/');
        return r == null || r.isEmpty() ? null : r;
    }

    /** relPath for an absolute path under the base path (deleted files), else null. */
    @Nullable String relPathOfAbsolute(String absPath) {
        String p = absPath.replace('\\', '/');
        if (basePath != null && p.startsWith(basePath + "/")) return p.substring(basePath.length() + 1);
        return null;
    }

    boolean isExcludedRel(String rel) {
        String[] parts = rel.split("/");
        StringBuilder acc = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i];
            if (i > 0) acc.append('/');
            acc.append(name);
            boolean dir = i < parts.length - 1;
            if (dir && (DEFAULT_EXCLUDES.contains(name) || name.endsWith(".egg-info"))) return true;
            if (matchesUser(acc.toString(), name)) return true;
        }
        return false;
    }

    private boolean matchesUser(String rel, String name) {
        for (int i = 0; i < globs.size(); i++) {
            Pattern p = globs.get(i);
            if (p.matcher(rel).matches() || p.matcher(name).matches() || rel.startsWith(prefixes.get(i))) return true;
        }
        return false;
    }

    private static String stripSlash(String s) {
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Python fnmatch semantics ('*' also matches '/'), case-sensitive (POSIX). */
    static Pattern fnmatchToRegex(String pat) {
        StringBuilder sb = new StringBuilder();
        int i = 0, n = pat.length();
        while (i < n) {
            char c = pat.charAt(i++);
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else if (c == '[') {
                int j = i;
                if (j < n && pat.charAt(j) == '!') j++;
                if (j < n && pat.charAt(j) == ']') j++;
                while (j < n && pat.charAt(j) != ']') j++;
                if (j >= n) sb.append("\\[");
                else {
                    String stuff = pat.substring(i, j).replace("\\", "\\\\");
                    i = j + 1;
                    if (stuff.startsWith("!")) stuff = "^" + stuff.substring(1);
                    else if (stuff.startsWith("^")) stuff = "\\" + stuff;
                    sb.append('[').append(stuff).append(']');
                }
            } else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL);
    }
}
