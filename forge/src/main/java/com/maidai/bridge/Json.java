package com.maidai.bridge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tiny hand-rolled JSON helpers (no external deps, mirrors the MaidAI gateway style). */
public final class Json {

    private Json() {
    }

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
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** Round to 2 decimals for compact telemetry. */
    public static double r(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 100.0) / 100.0;
    }

    /** Compact number for command arguments: 100 instead of 100.0 */
    public static String n(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1.0e9) return Long.toString((long) v);
        return Double.toString(r(v));
    }

    public static String strArg(String body, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .matcher(body == null ? "" : body);
        return m.find() ? unescape(m.group(1)) : "";
    }

    public static Double numArg(String body, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
                .matcher(body == null ? "" : body);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }

    public static Integer intField(String text, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+)")
                .matcher(text == null ? "" : text);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    public static Boolean boolField(String text, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)")
                .matcher(text == null ? "" : text);
        return m.find() ? Boolean.valueOf(m.group(1)) : null;
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char d = s.charAt(++i);
            switch (d) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                default -> sb.append(d);
            }
        }
        return sb.toString();
    }
}
