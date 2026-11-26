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

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals("\"\\r\"", Json.write("\r"));
        assertEquals("\"\\u0001\"", Json.write("\u0001"));
    }

    @Test
    void parsesAllEscapeSequences() {
        assertEquals("\"", Json.parse("\"\\\"\""));
        assertEquals("\\", Json.parse("\"\\\\\""));
        assertEquals("/", Json.parse("\"\\/\""));
        assertEquals("\b", Json.parse("\"\\b\""));
        assertEquals("\f", Json.parse("\"\\f\""));
        assertEquals("\n", Json.parse("\"\\n\""));
        assertEquals("\r", Json.parse("\"\\r\""));
        assertEquals("\t", Json.parse("\"\\t\""));
        assertEquals("A", Json.parse("\"\\u0041\""));
        assertThrows(Json.ParseException.class, () -> Json.parse("\"\\q\""), "bad escape");
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
    void writesLargeIntegralNumbersExactly() {
        assertEquals("9007199254740993", Json.write(9_007_199_254_740_993L));
        assertEquals(String.valueOf(Long.MAX_VALUE), Json.write(Long.MAX_VALUE));
        assertEquals("123456789012345678901234567890",
                Json.write(new java.math.BigInteger("123456789012345678901234567890")));
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
    void parsesValidNumbers() {
        assertEquals(1L, Json.parse("1"));
        assertEquals(-1L, Json.parse("-1"));
        assertEquals(1.5, Json.parse("1.5"));
        assertEquals(100.0, Json.parse("1e2"));
        assertEquals(0.01, Json.parse("1e-2"));
    }

    @Test
    void rejectsInvalidNumbers() {
        for (String invalid : List.of("+1", "01", "-01", ".5", "1.", "1e", "1e+", "1e309", "-1e309", "--1")) {
            assertThrows(Json.ParseException.class, () -> Json.parse(invalid), invalid);
        }
    }

    @Test
    void parsesEmptyContainers() {
        assertEquals(Map.of(), Json.parse("{}"));
        assertEquals(List.of(), Json.parse("[]"));
    }

    @Test
    void rejectsNullInput() {
        assertThrows(Json.ParseException.class, () -> Json.parse(null));
    }

    @Test
    void rejectsMalformedInput() {
        for (String invalid : List.of("", " ", "{", "[", "{\"a\" 1}", "{\"a\":1 \"b\":2}",
                "[1 2]", "1 2", "tru", "fals", "nul", "x", "\"unterminated")) {
            assertThrows(Json.ParseException.class, () -> Json.parse(invalid), invalid);
        }
    }

    @Test
    void rejectsIntegersOutsideTheLongRange() {
        assertThrows(Json.ParseException.class, () -> Json.parse("99999999999999999999"));
    }

    @Test
    void rejectsUnescapedControlCharactersInStrings() {
        assertThrows(Json.ParseException.class, () -> Json.parse("\"\u0001\""));
    }

    @Test
    void rejectsDeeplyNestedInput() {
        String deep = "[".repeat(300) + "0" + "]".repeat(300);
        assertThrows(Json.ParseException.class, () -> Json.parse(deep));
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
