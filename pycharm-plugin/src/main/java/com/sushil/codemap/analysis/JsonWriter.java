package com.sushil.codemap.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Minimal deterministic JSON writer (object keys sorted, like {@code json.dumps(sort_keys=True)}). */
final class JsonWriter {
    private JsonWriter() {}

    static String write(Object v) {
        StringBuilder sb = new StringBuilder(4096);
        write(sb, v);
        return sb.toString();
    }

    static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            str(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(d);
        } else if (v instanceof Number n) {
            sb.append(n.longValue());
        } else if (v instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>();
            for (Object k : m.keySet()) keys.add(String.valueOf(k));
            keys.sort(null);
            sb.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(',');
                first = false;
                str(sb, k);
                sb.append(':');
                write(sb, m.get(k));
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            write(sb, List.of(arr));
        } else {
            str(sb, v.toString());
        }
    }

    static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    boolean loneSurrogate = Character.isHighSurrogate(c)
                            ? !(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
                            : Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || c == ' ' || c == ' ' || loneSurrogate) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
