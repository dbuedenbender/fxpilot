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
package io.github.buedi.fxpilot;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonTest {

    @Test
    void writesNull() {
        assertEquals("null", Json.write(null));
    }

    @Test
    void writesAndEscapesStrings() {
        assertEquals("\"hello\"", Json.write("hello"));
        assertEquals("\"a\\\"b\"", Json.write("a\"b"));
        assertEquals("\"a\\\\b\"", Json.write("a\\b"));
        assertEquals("\"line1\\nline2\"", Json.write("line1\nline2"));
        assertEquals("\"tab\\there\"", Json.write("tab\there"));
        assertEquals("\"\\u0001\"", Json.write("\u0001"));
    }

    @Test
    void writesIntegralNumbersWithoutDecimalPoint() {
        assertEquals("42", Json.write(42));
        assertEquals("42", Json.write(42.0));
        assertEquals("-7", Json.write(-7L));
    }

    @Test
    void writesFractionalNumbers() {
        assertEquals("3.5", Json.write(3.5));
    }

    @Test
    void writesNonFiniteNumbersAsNull() {
        assertEquals("null", Json.write(Double.NaN));
        assertEquals("null", Json.write(Double.POSITIVE_INFINITY));
        assertEquals("null", Json.write(Double.NEGATIVE_INFINITY));
    }

    @Test
    void writesBooleans() {
        assertEquals("true", Json.write(true));
        assertEquals("false", Json.write(false));
    }

    @Test
    void writesMapInInsertionOrder() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", "x");
        assertEquals("{\"a\":1,\"b\":\"x\"}", Json.write(map));
    }

    @Test
    void writesCollections() {
        assertEquals("[1,2,3]", Json.write(List.of(1, 2, 3)));
        assertEquals("[]", Json.write(List.of()));
    }

    @Test
    void writesNestedStructures() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("items", Arrays.asList("a", null));
        map.put("ok", true);
        assertEquals("{\"items\":[\"a\",null],\"ok\":true}", Json.write(map));
    }

    @Test
    void fallsBackToToStringForUnknownTypes() {
        Object value = new Object() {
            @Override
            public String toString() {
                return "1";
            }
        };
        assertEquals("\"1\"", Json.write(value));
    }
}
