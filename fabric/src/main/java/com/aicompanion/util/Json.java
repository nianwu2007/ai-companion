package com.aicompanion.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal zero-dependency JSON reader/writer.
 *
 * Supports object / array / string / number / true / false / null.
 * Used for: config file, request bodies, and picking fields out of LLM replies.
 */
public final class Json {

    private Json() {}

    // ---------------------------------------------------------------- writing

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
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
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    public static String quote(String s) {
        return "\"" + esc(s) + "\"";
    }

    /** Number literal without a trailing ".0" for integral values. */
    public static String n(double d) {
        if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    /** key/value pair for hand-built objects, e.g. pair("text", quote(msg)). */
    public static String pair(String key, String rawJsonValue) {
        return quote(key) + ":" + rawJsonValue;
    }

    public static String obj(String... keyValueJsonPairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i + 1 < keyValueJsonPairs.length; i += 2) {
            if (sb.length() > 1) sb.append(',');
            sb.append(pair(keyValueJsonPairs[i], keyValueJsonPairs[i + 1]));
        }
        return sb.append('}').toString();
    }

    // ---------------------------------------------------------------- parsing

    public static Object parseValue(String s) {
        if (s == null) return null;
        P p = new P(s);
        p.ws();
        Object v = p.value();
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String s) {
        Object v = parseValue(s);
        if (v instanceof Map<?, ?> m) return (Map<String, Object>) m;
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String s) {
        Object v = parseValue(s);
        if (v instanceof List<?> l) return (List<Object>) l;
        return new ArrayList<>();
    }

    /** Field lookup on a raw JSON string; returns "" when absent. */
    public static String str(String json, String key) {
        return strOr(json, key, "");
    }

    public static String strOr(String json, String key, String def) {
        Object v = parseObject(json).get(key);
        if (v == null) return def;
        return v instanceof String s ? s : String.valueOf(v);
    }

    public static double num(String json, String key, double def) {
        Object v = parseObject(json).get(key);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s.trim()); } catch (RuntimeException ignored) { }
        }
        return def;
    }

    public static boolean bool(String json, String key, boolean def) {
        Object v = parseObject(json).get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) return Boolean.parseBoolean(s.trim());
        return def;
    }

    /**
     * Deep field lookup: strPath(body, "choices", "0", "message", "content")
     * walks objects by name and arrays by numeric index.
     */
    public static String strPath(String json, String... path) {
        Object cur = parseValue(json);
        for (String k : path) {
            if (cur instanceof Map<?, ?> m) {
                cur = m.get(k);
            } else if (cur instanceof List<?> l) {
                int idx;
                try { idx = Integer.parseInt(k); } catch (RuntimeException e) { return ""; }
                if (idx < 0 || idx >= l.size()) return "";
                cur = l.get(idx);
            } else {
                return "";
            }
            if (cur == null) return "";
        }
        return String.valueOf(cur);
    }

    // ---------------------------------------------------------------- parser

    private static final class P {
        private final String src;
        private int i;

        P(String src) { this.src = src; }

        void ws() {
            while (i < src.length()) {
                char c = src.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
                else break;
            }
        }

        Object value() {
            ws();
            if (i >= src.length()) return null;
            char c = src.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': if (lit("true")) return Boolean.TRUE; return null;
                case 'f': if (lit("false")) return Boolean.FALSE; return null;
                case 'n': if (lit("null")) return null; return null;
                default: return number();
            }
        }

        boolean lit(String word) {
            if (src.startsWith(word, i)) { i += word.length(); return true; }
            return false;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // '{'
            ws();
            if (i < src.length() && src.charAt(i) == '}') { i++; return m; }
            while (i < src.length()) {
                ws();
                if (i >= src.length() || src.charAt(i) != '"') break;
                String key = string();
                ws();
                if (i < src.length() && src.charAt(i) == ':') i++;
                m.put(key, value());
                ws();
                if (i < src.length() && src.charAt(i) == ',') { i++; continue; }
                if (i < src.length() && src.charAt(i) == '}') { i++; break; }
                break;
            }
            return m;
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; // '['
            ws();
            if (i < src.length() && src.charAt(i) == ']') { i++; return l; }
            while (i < src.length()) {
                l.add(value());
                ws();
                if (i < src.length() && src.charAt(i) == ',') { i++; continue; }
                if (i < src.length() && src.charAt(i) == ']') { i++; break; }
                break;
            }
            return l;
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (i < src.length()) {
                char c = src.charAt(i++);
                if (c == '"') break;
                if (c == '\\' && i < src.length()) {
                    char e = src.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (i + 4 <= src.length()) {
                                try {
                                    sb.append((char) Integer.parseInt(src.substring(i, i + 4), 16));
                                    i += 4;
                                } catch (NumberFormatException ignored) { }
                            }
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Object number() {
            int start = i;
            while (i < src.length()) {
                char c = src.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') i++;
                else break;
            }
            String raw = src.substring(start, i);
            if (raw.isEmpty()) { i++; return null; }
            try {
                if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                    return Long.parseLong(raw);
                }
                return Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
