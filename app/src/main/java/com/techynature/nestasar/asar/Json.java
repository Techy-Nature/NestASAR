package com.techynature.nestasar.asar;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON reader/writer, just enough for ASAR headers.
 * Objects become LinkedHashMap (order kept), arrays ArrayList, numbers Long or Double.
 */
public final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("Trailing characters");
        return v;
    }

    private AsarException err(String msg) {
        return new AsarException("Bad header JSON: " + msg + " at char " + i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++;
            else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw err("Unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("Unexpected '" + c + "'");
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("Expected " + w);
        i += w.length();
    }

    private Map<String, Object> object() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("Expected key");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("Expected ':'");
            i++;
            ws();
            m.put(k, value());
            ws();
            if (i >= s.length()) throw err("Unexpected end in object");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("Expected ',' or '}'");
        }
    }

    private List<Object> array() {
        ArrayList<Object> a = new ArrayList<>();
        i++;
        ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            if (i >= s.length()) throw err("Unexpected end in array");
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw err("Expected ',' or ']'");
        }
    }

    private String string() {
        i++; // opening quote
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("Unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("Bad escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("Bad unicode escape");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: throw err("Bad escape \\" + e);
            }
        }
    }

    private Number number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        boolean dbl = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') i++;
            else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') { dbl = true; i++; }
            else break;
        }
        String n = s.substring(start, i);
        try {
            if (!dbl) return Long.parseLong(n);
            return Double.parseDouble(n);
        } catch (NumberFormatException ex) {
            throw err("Bad number " + n);
        }
    }

    // ---------------------------------------------------------------- writing

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v) {
        if (v == null) {
            b.append("null");
        } else if (v instanceof String) {
            quote(b, (String) v);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            b.append(v);
        } else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 9e15) b.append((long) d);
            else b.append(d);
        } else if (v instanceof Map) {
            b.append('{');
            Iterator<Map.Entry<String, Object>> it = ((Map<String, Object>) v).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Object> e = it.next();
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
                if (it.hasNext()) b.append(',');
            }
            b.append('}');
        } else if (v instanceof List) {
            b.append('[');
            List<Object> l = (List<Object>) v;
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) b.append(',');
                write(b, l.get(k));
            }
            b.append(']');
        } else {
            throw new IllegalArgumentException("Can't write " + v.getClass());
        }
    }

    private static void quote(StringBuilder b, String str) {
        b.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
