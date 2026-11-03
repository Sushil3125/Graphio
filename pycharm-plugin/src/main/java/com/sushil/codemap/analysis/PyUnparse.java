package com.sushil.codemap.analysis;

import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.python.psi.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Text helpers mirroring analyzer.py's use of {@code ast.unparse}, {@code _dotted}, {@code _last_name},
 * {@code _str_const} and {@code _format_signature} on PSI. Display strings (decorators, signatures) follow
 * {@code ast.unparse} closely for common expressions and fall back to whitespace-normalised source text.
 */
final class PyUnparse {
    private PyUnparse() {}

    // ------------------------------------------------------------------ analyzer.py _last_name / _str_const / _dotted

    /** {@code _last_name}: Name -> id, Attribute -> attr, otherwise null. */
    static String lastName(PyExpression e) {
        e = unparen(e);
        if (e instanceof PyReferenceExpression r) return r.getReferencedName();
        return null;
    }

    static PyExpression unparen(PyExpression e) {
        while (e instanceof PyParenthesizedExpression p && p.getContainedExpression() != null
                && !(p.getContainedExpression() instanceof PyTupleExpression)) {
            e = p.getContainedExpression();
        }
        return e;
    }

    /** {@code _str_const}: a plain (non-f, non-bytes) string literal's value, else null. */
    static String strConst(PyExpression e) {
        e = unparen(e);
        if (!(e instanceof PyStringLiteralExpression s)) return null;
        for (PyStringElement el : s.getStringElements()) {
            if (el.isFormatted() || el.isBytes() || el.isTemplate()) return null;
        }
        return s.getStringValue();
    }

    /** {@code _dotted}: readable name of an unresolved callee. */
    static String dotted(PyExpression e) {
        e = unparen(e);
        if (e instanceof PyReferenceExpression r) {
            PyExpression q = r.getQualifier();
            String name = r.getReferencedName();
            return q == null ? String.valueOf(name) : dotted(q) + "." + name;
        }
        if (e instanceof PyCallExpression c) return dotted(c.getCallee()) + "()";
        if (e instanceof PySubscriptionExpression s) return dotted(s.getOperand()) + "[]";
        if (e instanceof PyStringLiteralExpression s) {
            boolean bytes = false;
            for (PyStringElement el : s.getStringElements()) {
                if (el.isFormatted()) return "<expr>";
                bytes |= el.isBytes();
            }
            return bytes ? "bytes" : "str";
        }
        if (e instanceof PyNumericLiteralExpression n) {
            String t = n.getText().toLowerCase();
            if (t.endsWith("j")) return "complex";
            return n.isIntegerLiteral() ? "int" : "float";
        }
        if (e instanceof PyBoolLiteralExpression) return "bool";
        if (e instanceof PyNoneLiteralExpression) return "NoneType";
        if (e instanceof PyEllipsisLiteralExpression) return "ellipsis";
        return "<expr>";
    }

    // ------------------------------------------------------------------ ast.unparse approximation

    static String unparse(PsiElement e) {
        if (e == null) return "?";
        if (e instanceof PyExpression x) e = unparen(x);
        try {
            return up(e);
        } catch (RuntimeException ex) {
            if (ex instanceof com.intellij.openapi.progress.ProcessCanceledException) throw ex;
            return normalize(e.getText());
        }
    }

    /** Text of a decorator without '@', as {@code ast.unparse(decorator)}. */
    static String decorator(PyDecorator d) {
        PyExpression callee = d.getCallee();
        if (callee == null) {
            String t = d.getText();
            return normalize(t.startsWith("@") ? t.substring(1) : t);
        }
        if (!d.hasArgumentList()) return unparse(callee);
        return unparse(callee) + "(" + args(d.getArgumentList()) + ")";
    }

    private static String up(PsiElement e) {
        if (e instanceof PyReferenceExpression r) {
            PyExpression q = r.getQualifier();
            return q == null ? r.getReferencedName() : up(q) + "." + r.getReferencedName();
        }
        if (e instanceof PyDecorator d) return decorator(d);
        if (e instanceof PyCallExpression c) {
            return up(c.getCallee()) + "(" + args(c.getArgumentList()) + ")";
        }
        if (e instanceof PySubscriptionExpression s) {
            PyExpression idx = s.getIndexExpression();
            String i = idx instanceof PyTupleExpression t
                    ? (t.getElements().length == 0 ? "()" : t.getElements().length == 1 ? up(t.getElements()[0]) + "," : joinElems(t.getElements()))
                    : idx == null ? "" : up(idx);
            return up(s.getOperand()) + "[" + i + "]";
        }
        if (e instanceof PyStringLiteralExpression s) {
            int bytes = 0;
            for (PyStringElement el : s.getStringElements()) {
                if (el.isFormatted() || el.isTemplate()) return normalize(s.getText());
                if (el.isBytes()) bytes++;
            }
            if (bytes == 0) return repr(s.getStringValue());
            if (bytes != s.getStringElements().size()) return normalize(s.getText());
            String v = s.getStringValue();
            for (int i = 0; i < v.length(); i++) if (v.charAt(i) > 0xff) return normalize(s.getText());
            return "b" + repr(v, true);
        }
        if (e instanceof PyKeyValueExpression kv) {
            return up(kv.getKey()) + ": " + (kv.getValue() == null ? "" : up(kv.getValue()));
        }
        if (e instanceof PyKeywordArgument k) {
            return k.getKeyword() + "=" + up(k.getValueExpression());
        }
        if (e instanceof PyStarArgument st) {
            PyExpression inner = PsiTreeUtil.getChildOfType(st, PyExpression.class);
            return (st.isKeyword() ? "**" : "*") + (inner == null ? "" : up(inner));
        }
        if (e instanceof PyStarExpression st) return "*" + up(st.getExpression());
        if (e instanceof PyDoubleStarExpression st) return "**" + up(st.getExpression());
        if (e instanceof PyParenthesizedExpression p) {
            PyExpression inner = p.getContainedExpression();
            if (inner == null) return "()";
            if (inner instanceof PyTupleExpression) return up(inner);
            if (isAtom(inner)) return up(inner);
            return "(" + up(inner) + ")";
        }
        if (e instanceof PyTupleExpression t) {
            PyExpression[] el = t.getElements();
            if (el.length == 1) return "(" + up(el[0]) + ",)";
            return "(" + joinElems(el) + ")";
        }
        if (e instanceof PyListLiteralExpression l) return "[" + joinElems(l.getElements()) + "]";
        if (e instanceof PySetLiteralExpression s) return "{" + joinElems(s.getElements()) + "}";
        if (e instanceof PyDictLiteralExpression d) {
            List<String> parts = new ArrayList<>();
            for (PsiElement c : d.getChildren()) {
                if (c instanceof PyKeyValueExpression kv) {
                    parts.add(up(kv.getKey()) + ": " + (kv.getValue() == null ? "" : up(kv.getValue())));
                } else if (c instanceof PyExpression x) {
                    parts.add(up(x));
                }
            }
            return "{" + String.join(", ", parts) + "}";
        }
        if (e instanceof PyBinaryExpression b && b.getLeftExpression() != null && b.getRightExpression() != null) {
            PyExpression l = b.getLeftExpression(), r = b.getRightExpression();
            String text = b.getText();
            int base = b.getTextRange().getStartOffset();
            int from = l.getTextRange().getEndOffset() - base;
            int to = r.getTextRange().getStartOffset() - base;
            String op = (from >= 0 && to <= text.length() && from <= to) ? normalize(text.substring(from, to)) : "?";
            return up(l) + " " + op + " " + up(r);
        }
        if (e instanceof PyPrefixExpression p && p.getOperand() != null) {
            PsiElement op = p.getPsiOperator();
            String o = op == null ? "" : op.getText();
            boolean word = !o.isEmpty() && Character.isLetter(o.charAt(0));
            return o + (word ? " " : "") + up(p.getOperand());
        }
        if (e instanceof PyConditionalExpression c && c.getCondition() != null && c.getFalsePart() != null) {
            return up(c.getTruePart()) + " if " + up(c.getCondition()) + " else " + up(c.getFalsePart());
        }
        if (e instanceof PyLambdaExpression lam) {
            String params = lam.getParameterList().getParameters().length == 0 ? ""
                    : " " + paramList(lam.getParameterList());
            return "lambda" + params + ": " + (lam.getBody() == null ? "" : up(lam.getBody()));
        }
        if (e instanceof PyEllipsisLiteralExpression) return "...";
        if (e instanceof PyNumericLiteralExpression n) return number(n.getText());
        if (e instanceof PyComprehensionElement c) return comprehension(c);
        return normalize(e.getText());
    }

    /** {@code ast.unparse} of a comprehension / generator expression (generators always get their own parens). */
    private static String comprehension(PyComprehensionElement c) {
        String open, close;
        if (c instanceof PyListCompExpression) { open = "["; close = "]"; }
        else if (c instanceof PySetCompExpression || c instanceof PyDictCompExpression) { open = "{"; close = "}"; }
        else if (c instanceof PyGeneratorExpression) { open = "("; close = ")"; }
        else return normalize(c.getText());
        PsiElement result = c.getResultExpression();
        if (c instanceof PyDictCompExpression && !(result instanceof PyKeyValueExpression)) {
            result = PsiTreeUtil.getChildOfType(c, PyKeyValueExpression.class);
        }
        if (result == null) return normalize(c.getText());
        StringBuilder sb = new StringBuilder(open).append(up(result));
        for (PyComprehensionComponent comp : c.getComponents()) {
            if (comp instanceof PyComprehensionForComponent f) {
                PyExpression target = f.getIteratorVariable();
                String t = target instanceof PyTupleExpression tt ? joinElems(tt.getElements()) : up(target);
                sb.append(f.isAsync() ? " async for " : " for ").append(t).append(" in ").append(up(f.getIteratedList()));
            } else if (comp instanceof PyComprehensionIfComponent i) {
                sb.append(" if ").append(up(i.getTest()));
            } else {
                return normalize(c.getText());
            }
        }
        return sb.append(close).toString();
    }

    /** {@code ast.unparse} of a numeric literal: ints in decimal, floats / imaginary as Python's {@code repr}. */
    static String number(String text) {
        String t = text.replace("_", "").toLowerCase(java.util.Locale.ROOT);
        try {
            if (t.endsWith("j")) {
                String f = floatRepr(Double.parseDouble(t.substring(0, t.length() - 1)));
                return (f.endsWith(".0") ? f.substring(0, f.length() - 2) : f) + "j";
            }
            if (t.startsWith("0x")) return new java.math.BigInteger(t.substring(2), 16).toString();
            if (t.startsWith("0o")) return new java.math.BigInteger(t.substring(2), 8).toString();
            if (t.startsWith("0b")) return new java.math.BigInteger(t.substring(2), 2).toString();
            if (t.endsWith("l")) t = t.substring(0, t.length() - 1);
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0) return new java.math.BigInteger(t).toString();
            return floatRepr(Double.parseDouble(t));
        } catch (NumberFormatException ex) {
            return normalize(text);
        }
    }

    /** Python {@code repr(float)} (shortest round-trip digits; scientific outside 1e-4 <= |d| < 1e16). */
    static String floatRepr(double d) {
        if (Double.isNaN(d)) return "nan";
        if (Double.isInfinite(d)) return "1e309"; // what ast.unparse prints for an overflowing literal
        if (d == 0) return (1 / d < 0) ? "-0.0" : "0.0";
        java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int exp = digits.length() - 1 - bd.scale();
        String sign = d < 0 ? "-" : "";
        if (exp >= -4 && exp < 16) {
            String plain = bd.toPlainString();
            return sign + (plain.indexOf('.') < 0 ? plain + ".0" : plain);
        }
        String mant = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
        int ae = Math.abs(exp);
        return sign + mant + "e" + (exp < 0 ? "-" : "+") + (ae < 10 ? "0" : "") + ae;
    }

    private static boolean isAtom(PyExpression e) {
        return e instanceof PyReferenceExpression || e instanceof PyCallExpression || e instanceof PyLiteralExpression
                || e instanceof PySubscriptionExpression || e instanceof PyListLiteralExpression
                || e instanceof PyDictLiteralExpression || e instanceof PySetLiteralExpression
                || e instanceof PyParenthesizedExpression;
    }

    private static String args(PyArgumentList list) {
        if (list == null) return "";
        List<String> pos = new ArrayList<>(), kw = new ArrayList<>();
        for (PyExpression a : list.getArguments()) {
            if (a instanceof PyKeywordArgument || (a instanceof PyStarArgument s && s.isKeyword())) kw.add(up(a));
            else pos.add(up(a));
        }
        pos.addAll(kw);
        return String.join(", ", pos);
    }

    private static String joinElems(PyExpression[] el) {
        List<String> parts = new ArrayList<>();
        for (PyExpression x : el) parts.add(up(x));
        return String.join(", ", parts);
    }

    /** Python {@code repr(str)}. */
    static String repr(String s) {
        return repr(s, false);
    }

    /** {@code repr(str)} or, with {@code bytes}, the quoted part of {@code repr(bytes)} (chars are byte values). */
    static String repr(String s, boolean bytes) {
        char q = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder().append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') sb.append("\\\\");
            else if (c == q) sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c == '\t') sb.append("\\t");
            else if (c < 0x20 || c == 0x7f || (bytes ? c >= 0x80 : c >= 0x80 && c <= 0xa0)) sb.append(String.format("\\x%02x", (int) c));
            else sb.append(c);
        }
        return sb.append(q).toString();
    }

    /** Collapse whitespace / comments / line continuations of a source fragment. */
    static String normalize(String t) {
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        char quote = 0;
        boolean space = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (quote != 0) {
                sb.append(c);
                if (c == '\\' && i + 1 < t.length()) sb.append(t.charAt(++i));
                else if (c == quote) quote = 0;
                continue;
            }
            if (c == '#') {
                while (i + 1 < t.length() && t.charAt(i + 1) != '\n') i++;
                continue;
            }
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '\n') { space = true; i++; continue; }
            if (Character.isWhitespace(c)) { space = true; continue; }
            if (space && sb.length() > 0) {
                char p = sb.charAt(sb.length() - 1);
                if ("([{".indexOf(p) < 0 && ")]},".indexOf(c) < 0) sb.append(' ');
            }
            space = false;
            if (c == '\'' || c == '"') quote = c;
            if (")]}".indexOf(c) >= 0 && sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') {
                sb.setLength(sb.length() - 1);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ analyzer.py _format_signature

    static String signature(PyFunction fn) {
        String sig = "(" + paramList(fn.getParameterList()) + ")";
        PyAnnotation ret = fn.getAnnotation();
        if (ret != null && ret.getValue() != null) sig += " -> " + unparse(ret.getValue());
        return sig;
    }

    private static String paramList(PyParameterList list) {
        List<String> parts = new ArrayList<>();
        for (PyParameter p : list.getParameters()) {
            if (p instanceof PySlashParameter) { parts.add("/"); continue; }
            if (p instanceof PySingleStarParameter) { parts.add("*"); continue; }
            PyNamedParameter n = p.getAsNamed();
            if (n == null) { parts.add(normalize(p.getText())); continue; }
            String s = (n.isPositionalContainer() ? "*" : n.isKeywordContainer() ? "**" : "") + n.getName();
            PyAnnotation ann = n.getAnnotation();
            boolean hasAnn = ann != null && ann.getValue() != null;
            if (hasAnn) s += ": " + unparse(ann.getValue());
            PyExpression d = n.getDefaultValue();
            if (d != null) s += (hasAnn ? " = " : "=") + unparse(d);
            parts.add(s);
        }
        return String.join(", ", parts);
    }

    /** Class "signature": {@code (Base1, Base2)} without keywords, "" without bases. */
    static String classSignature(PyClass cls) {
        List<String> parts = new ArrayList<>();
        for (PyExpression b : cls.getSuperClassExpressions()) {
            if (b instanceof PyKeywordArgument || (b instanceof PyStarArgument s && s.isKeyword())) continue;
            parts.add(unparse(b));
        }
        return parts.isEmpty() ? "" : "(" + String.join(", ", parts) + ")";
    }
}
