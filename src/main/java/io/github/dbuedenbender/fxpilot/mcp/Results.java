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
package io.github.dbuedenbender.fxpilot.mcp;

import java.util.*;

/**
 * MCP tool results and small value helpers.
 */
final class Results {

    private Results() {
    }

    static String textOf(Map<String, Object> result) {
        Object content = result.get("content");
        if (content instanceof List && !((List<?>) content).isEmpty()) {
            Object first = ((List<?>) content).get(0);
            if (first instanceof Map) {
                Object text = ((Map<?, ?>) first).get("text");
                if (text != null) {
                    return String.valueOf(text);
                }
            }
        }
        return String.valueOf(result);
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static Map<String, Object> text(String s) {
        return Map.of("content", List.of(Map.of("type", "text", "text", s)));
    }

    static Map<String, Object> toolError(String s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", s)));
        m.put("isError", true);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        if (!(o instanceof Map)) {
            throw new RpcError(-32600, "expected JSON object");
        }
        return (Map<String, Object>) o;
    }
}
