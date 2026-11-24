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
package io.github.dbuedenbender.fxpilot;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;

/**
 * Minimal JSON writer to avoid an extra dependency for the FxPilot endpoint.
 */
final class Json {

    private Json() {
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            string(sb, (String) value);
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
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
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
}
