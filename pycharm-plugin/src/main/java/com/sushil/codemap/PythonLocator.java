package com.sushil.codemap;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text-only Python locator (no dependency on the Python plugin PSI).
 * Derives the codemap node id "<module>:<qualname>" for a caret position.
 */
public final class PythonLocator {
    private static final Pattern DEF = Pattern.compile("^(\\s*)(?:async\\s+)?def\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern CLASS = Pattern.compile("^(\\s*)class\\s+([A-Za-z_][A-Za-z0-9_]*)");

    private PythonLocator() {}

    /** "a/b/c.py" -> "a.b.c"; "a/b/__init__.py" -> "a.b"; null if not a .py path. */
    public static String moduleName(String relPath) {
        if (relPath == null) return null;
        String p = relPath.replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        if (p.endsWith(".pyi")) p = p.substring(0, p.length() - 4);
        else if (p.endsWith(".py")) p = p.substring(0, p.length() - 3);
        else return null;
        if (p.equals("__init__")) return null;
        if (p.endsWith("/__init__")) p = p.substring(0, p.length() - "/__init__".length());
        return p.replace('/', '.');
    }

    /** Node id for a caret in a file: "<module>:<qualname>" or module node "<module>:<module>". */
    public static String nodeId(String module, String text, int caretLine0) {
        if (module == null) return null;
        String q = text == null ? null : qualname(text, caretLine0);
        return module + ":" + (q == null ? module : q);
    }

    private static final class Scope {
        final String name;
        final boolean isDef;
        Scope(String name, boolean isDef) { this.name = name; this.isDef = isDef; }
    }

    /** Qualname of the innermost def/class enclosing the 0-based line, or null at module level. */
    public static String qualname(String text, int caretLine0) {
        String[] lines = text.split("\r\n|\r|\n", -1);
        if (lines.length == 0) return null;
        int start = Math.max(0, Math.min(caretLine0, lines.length - 1));
        boolean[] logical = logicalStarts(lines);

        // Caret on a decorator: move down to the decorated def/class.
        if (lines[start].trim().startsWith("@")) {
            int ind = indent(lines[start]);
            for (int i = start + 1; i < lines.length && i < start + 50; i++) {
                if (!logical[i] || isBlankOrComment(lines[i])) continue;
                if (indent(lines[i]) == ind && (DEF.matcher(lines[i]).find() || CLASS.matcher(lines[i]).find())) {
                    start = i;
                    break;
                }
                if (!lines[i].trim().startsWith("@")) break;
            }
        }
        // Caret inside a continuation line (e.g. multi-line signature): move up to the logical start.
        while (start > 0 && !logical[start]) start--;

        List<Scope> stack = new ArrayList<>();
        int cur;
        String first = lines[start];
        if (isBlankOrComment(first)) {
            cur = Integer.MAX_VALUE;
        } else {
            cur = indent(first);
            Scope s = match(first);
            if (s != null) stack.add(s);
        }
        for (int i = start - 1; i >= 0 && cur > 0; i--) {
            if (!logical[i] || isBlankOrComment(lines[i])) continue;
            int ind = indent(lines[i]);
            if (ind >= cur) continue;
            Scope s = match(lines[i]);
            if (s != null) stack.add(s);
            cur = ind;
        }
        if (stack.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int k = stack.size() - 1; k >= 0; k--) {
            Scope s = stack.get(k);
            if (sb.length() > 0) sb.append(stack.get(k + 1).isDef ? ".<locals>." : ".");
            sb.append(s.name);
        }
        return sb.toString();
    }

    private static Scope match(String line) {
        Matcher m = DEF.matcher(line);
        if (m.find()) return new Scope(m.group(2), true);
        m = CLASS.matcher(line);
        if (m.find()) return new Scope(m.group(2), false);
        return null;
    }

    private static boolean isBlankOrComment(String line) {
        String t = line.trim();
        return t.isEmpty() || t.startsWith("#");
    }

    private static int indent(String line) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') n++;
            else if (c == '\t') n = (n / 8 + 1) * 8;
            else break;
        }
        return n;
    }

    /** For each line: true if it begins a new logical line (not inside a bracket, string or after a backslash). */
    static boolean[] logicalStarts(String[] lines) {
        boolean[] res = new boolean[lines.length];
        int depth = 0;
        String triple = null;      // """ or ''' when inside a triple-quoted string
        boolean backslash = false;
        for (int li = 0; li < lines.length; li++) {
            res[li] = depth == 0 && triple == null && !backslash;
            backslash = false;
            String s = lines[li];
            int i = 0, n = s.length();
            while (i < n) {
                if (triple != null) {
                    int end = s.indexOf(triple, i);
                    // skip escaped quotes
                    while (end > 0 && s.charAt(end - 1) == '\\' && !isEscapedBackslash(s, end - 1)) {
                        end = s.indexOf(triple, end + 1);
                    }
                    if (end < 0) { i = n; break; }
                    i = end + 3;
                    triple = null;
                    continue;
                }
                char c = s.charAt(i);
                if (c == '#') break;
                if (c == '"' || c == '\'') {
                    if (i + 2 < n && s.charAt(i + 1) == c && s.charAt(i + 2) == c) {
                        triple = "" + c + c + c;
                        i += 3;
                        continue;
                    }
                    int j = i + 1;
                    while (j < n) {
                        char d = s.charAt(j);
                        if (d == '\\') { j += 2; continue; }
                        if (d == c) break;
                        j++;
                    }
                    i = j + 1;
                    continue;
                }
                if (c == '(' || c == '[' || c == '{') depth++;
                else if (c == ')' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
                else if (c == '\\' && i == n - 1) backslash = true;
                i++;
            }
        }
        return res;
    }

    private static boolean isEscapedBackslash(String s, int idx) {
        int count = 0;
        for (int k = idx; k >= 0 && s.charAt(k) == '\\'; k--) count++;
        return count % 2 == 0;
    }
}
