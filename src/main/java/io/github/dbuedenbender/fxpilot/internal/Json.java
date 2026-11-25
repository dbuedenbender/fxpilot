/*
 * Copyright 2026 FxPilot contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.dbuedenbender.fxpilot.internal;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON parser and writer shared by the endpoint and the MCP server, so FxPilot needs no
 * dependencies. Not part of the FxPilot API; may change in any release.
 *
 * <p>Parsing returns {@link LinkedHashMap}, {@link ArrayList}, {@link String}, {@link Long} (integers),
 * {@link Double} (other numbers), {@link Boolean} and {@code null}. Writing accepts maps, collections,
 * strings, numbers and booleans; anything else is written as its {@code toString()}.
 */
public final class Json {

    /** Malformed JSON input. */
    public static final class ParseException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ParseException(String message) {
            super(message);
        }
    }

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    // ---------------------------------------------------------------- writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            string(sb, (String) value);
        } else if (value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte || value instanceof BigInteger) {
            // integral types are written as is; a detour through double loses precision above 2^53
            sb.append(value);
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null"); // NaN and Infinity are not valid JSON
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Boolean) {
            sb.append(value);
        } else if (value instanceof Map) {
            sb.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) value).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append('}');
        } else if (value instanceof Collection) {
            sb.append('[');
            Iterator<?> it = ((Collection<?>) value).iterator();
            while (it.hasNext()) {
                write(sb, it.next());
                if (it.hasNext()) {
                    sb.append(',');
                }
            }
            sb.append(']');
        } else {
            string(sb, value.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ---------------------------------------------------------------- parsing

    /** Parses one JSON value; throws {@link ParseException} for malformed input. */
    public static Object parse(String s) {
        if (s == null) {
            throw new ParseException("no JSON input");
        }
        Json p = new Json(s);
        try {
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i != s.length()) {
                throw new ParseException("trailing characters in JSON");
            }
            return v;
        } catch (IndexOutOfBoundsException e) {
            throw new ParseException("unexpected end of JSON");
        } catch (NumberFormatException e) {
            throw new ParseException("invalid number in JSON: " + e.getMessage());
        }
    }

    private Object value() {
        ws();
        if (i >= s.length()) {
            throw new ParseException("unexpected end of JSON");
        }
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            if (s.charAt(i++) != ':') {
                throw new ParseException("expected ':'");
            }
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw new ParseException("expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw new ParseException("expected ',' or ']'");
            }
        }
    }

    private String string() {
        if (s.charAt(i) != '"') {
            throw new ParseException("expected string");
        }
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: throw new ParseException("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        if (start == i) {
            throw new ParseException("unexpected character '" + s.charAt(i) + "'");
        }
        String n = s.substring(start, i);
        if (n.contains(".") || n.contains("e") || n.contains("E")) {
            return Double.parseDouble(n);
        }
        return Long.parseLong(n);
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) {
            throw new ParseException("expected " + word);
        }
        i += word.length();
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }
}
