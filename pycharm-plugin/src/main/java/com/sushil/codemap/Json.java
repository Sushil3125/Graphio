package com.sushil.codemap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON helpers (no library dependency): string quoting, a small parser, a streaming element counter. */
public final class Json {
    private Json() {}

    public static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == ' ' || c == ' ') sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** Parses a JSON value into Map / List / String / Double / Boolean / null. Throws IllegalArgumentException. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw p.err("trailing data");
        return v;
    }

    /**
     * Number of elements of the array under top-level key {@code key} of a JSON object, without building a tree
     * (cheap on multi-MB graphs). 0 if the key is missing or not an array; -1 if the text is not a JSON object.
     */
    public static int topLevelArrayLength(String json, String key) {
        try {
            Parser p = new Parser(json);
            p.ws();
            if (!p.eat('{')) return -1;
            p.ws();
            if (p.eat('}')) return 0;
            while (true) {
                p.ws();
                String k = p.string();
                p.ws();
                p.expect(':');
                p.ws();
                if (k.equals(key)) {
                    if (!p.eat('[')) return 0;
                    p.ws();
                    if (p.eat(']')) return 0;
                    int n = 0;
                    while (true) {
                        p.ws();
                        p.skip();
                        n++;
                        p.ws();
                        if (p.eat(']')) return n;
                        p.expect(',');
                    }
                }
                p.skip();
                p.ws();
                if (p.eat('}')) return 0;
                p.expect(',');
            }
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) { this.s = s; }

        IllegalArgumentException err(String m) { return new IllegalArgumentException(m + " at " + i); }

        void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
                else break;
            }
        }

        boolean eat(char c) {
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }

        void expect(char c) { if (!eat(c)) throw err("expected '" + c + "'"); }

        Object value() {
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{': {
                    i++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    ws();
                    if (eat('}')) return m;
                    while (true) {
                        ws();
                        String k = string();
                        ws();
                        expect(':');
                        ws();
                        m.put(k, value());
                        ws();
                        if (eat('}')) return m;
                        expect(',');
                    }
                }
                case '[': {
                    i++;
                    List<Object> l = new ArrayList<>();
                    ws();
                    if (eat(']')) return l;
                    while (true) {
                        ws();
                        l.add(value());
                        ws();
                        if (eat(']')) return l;
                        expect(',');
                    }
                }
                case '"': return string();
                case 't': word("true"); return Boolean.TRUE;
                case 'f': word("false"); return Boolean.FALSE;
                case 'n': word("null"); return null;
                default: return number();
            }
        }

        /** Skips one value (structure-aware; strings scanned without building them). */
        void skip() {
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            if (c == '"') { skipString(); return; }
            if (c == '{' || c == '[') {
                int depth = 0;
                while (i < s.length()) {
                    char d = s.charAt(i);
                    if (d == '"') { skipString(); continue; }
                    if (d == '{' || d == '[') depth++;
                    else if (d == '}' || d == ']') {
                        depth--;
                        if (depth == 0) { i++; return; }
                    }
                    i++;
                }
                throw err("unterminated");
            }
            int start = i;
            while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) i++;
            if (i == start) throw err("value expected");
        }

        void skipString() {
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\\') i++;
                else if (c == '"') return;
            }
            throw err("unterminated string");
        }

        void word(String w) {
            if (!s.startsWith(w, i)) throw err("bad literal");
            i += w.length();
        }

        Double number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw err("unexpected character");
            try {
                return Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                throw err("bad number");
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw err("bad escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw err("bad escape");
                        }
                        i += 4;
                        break;
                    default: sb.append(e);
                }
            }
            throw err("unterminated string");
        }
    }
}
