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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.dbuedenbender.fxpilot.EndpointHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Edge cases from the second review of 1.1.0: each test failed before its fix. */
class EndpointEdgeCaseTest extends UiTestBase {

    @Test
    void passwordFieldPropertiesOutsideTheAllowListAreRefused() throws Exception {
        ok("/exec", "op", "textAction", "selector", "password", "action", "selectRange", "start", "0", "end", "3");
        for (String name : new String[]{"selectedText", "length", "characters", "text", "selection", "caretPosition"}) {
            HttpResponse<String> r = get("/exec", "op", "propertyGet", "selector", "password", "name", name);
            assertError(r, 403, "NODE_DENIED");
            assertFalse(r.body().contains("geh"), name + ": " + r.body());
        }
        assertTrue(read("/exec", "op", "propertyGet", "selector", "password", "name", "editable").contains("true"));
    }

    @Test
    void customPropertiesOfAPasswordFieldAreNoGuessingOracle() throws Exception {
        ok("/exec", "op", "propertySet", "selector", "password", "name", "probe", "value", "geheim");
        assertError(get("/exec", "op", "propertyGet", "selector", "password", "name", "probe"), 403, "NODE_DENIED");
    }

    @Test
    void nonFiniteNumbersAreRejected() throws Exception {
        assertError(post("/robotClick", "x", "NaN", "y", "NaN"), 400, "INVALID_NUMBER");
        assertError(post("/robotDrag", "x1", "1", "y1", "1", "x2", "Infinity", "y2", "1"), 400, "INVALID_NUMBER");
        assertError(post("/value", "selector", "volume", "value", "NaN"), 400, "INVALID_VALUE");
        assertError(post("/scroll", "selector", "customers", "amount", "-Infinity"), 400, "INVALID_NUMBER");
        assertTrue(read("/node", "selector", "volume").contains("\"value\":10"));
    }

    @Test
    void repeatCountsAreBounded() throws Exception {
        assertError(post("/exec", "op", "click", "selector", "okButton", "mode", "mouse", "clicks", "2000000000"),
                400, "INVALID_VALUE");
        assertError(post("/click", "selector", "okButton", "clicks", "0"), 400, "INVALID_VALUE");
        assertError(post("/exec", "op", "drag", "selector", "okButton", "target", "nameField", "steps", "100000"),
                400, "INVALID_VALUE");
        assertError(post("/type", "selector", "nameField", "text", "x".repeat(10_001)), 400, "INVALID_VALUE");
        assertTrue(read("/health").contains("\"status\":\"ok\""), "FX thread must stay responsive");
    }

    @Test
    void refPathsResolveInsideSkinnedContainers() throws Exception {
        for (String id : new String[]{"toolButton", "scrollButton", "generalContent"}) {
            String node = read("/node", "selector", id);
            int i = node.indexOf("\"ref\":\"") + 7;
            String ref = node.substring(i, node.indexOf('"', i));
            assertTrue(read("/node", "selector", "ref=" + ref).contains("\"id\":\"" + id + "\""), id + " via " + ref);
        }
        String view = read("/view", "limit", "500");
        int i = view.lastIndexOf("\"ref\":\"", view.indexOf("\"id\":\"toolButton\""));
        assertTrue(i > 0, view);
        ok("/click", "selector", "ref=" + view.substring(i + 7, view.indexOf('"', i + 7)));
        assertEquals("tool", status());
    }

    @Test
    void tableCellSetCommitsTheValue() throws Exception {
        ok("/exec", "op", "tableCellSet", "selector", "notesTable", "row", "0", "column", "Note", "value", "new");
        assertEquals("note new", status());
        assertTrue(read("/exec", "op", "tableCell", "selector", "notesTable", "row", "0", "column", "Note")
                .contains("\"value\":\"new\""));
        assertError(post("/exec", "op", "tableCellSet", "selector", "customers", "row", "0", "value", "x"), 400,
                "UNSUPPORTED_ACTION");
    }

    @Test
    void dialogButtonsAreFoundByTheirRoleInAnyLanguage() throws Exception {
        assertTrue(ok("/click", "selector", "openGerman").contains("\"status\":\"pending\""));
        ok("/exec", "op", "window", "window", "title:Frage", "action", "dismiss");
        waitForStatus("german cancel");
        assertTrue(ok("/click", "selector", "openGerman").contains("\"status\":\"pending\""));
        ok("/exec", "op", "window", "window", "title:Frage", "action", "accept");
        waitForStatus("german ok");
    }

    private static void waitForStatus(String expected) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!expected.equals(status()) && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertEquals(expected, status());
    }

    @Test
    void tabCloseRespectsTheApplicationsRules() throws Exception {
        assertError(post("/exec", "op", "tab", "selector", "tabs", "tab", "Fixed", "action", "close"), 409,
                "UNSUPPORTED_ACTION");
        assertError(post("/exec", "op", "tab", "selector", "tabs", "tab", "Locked", "action", "close"), 409,
                "UNSUPPORTED_ACTION");
        assertEquals("close vetoed", status());
        ok("/exec", "op", "tab", "selector", "tabs", "tab", "Closable", "action", "close");
        String tabs = read("/node", "selector", "tabs");
        assertFalse(tabs.contains("\"Closable\""), tabs);
        assertTrue(tabs.contains("\"Locked\"") && tabs.contains("\"Fixed\""), tabs);
    }

    @Test
    void datePickerFiresItsActionOnce() throws Exception {
        ok("/value", "selector", "date", "value", "2026-12-24");
        assertEquals("date actions 1", status());
    }

    @Test
    void malformedSelectorsAndWindowsAreClientErrors() throws Exception {
        assertError(get("/node", "selector", "."), 400, "INVALID_SELECTOR");
        assertError(get("/node", "selector", "#"), 400, "INVALID_SELECTOR");
        assertError(get("/view", "window", "title:"), 400, "BAD_REQUEST");
        assertError(get("/exec", "op", "tableCell", "selector", "customers", "row", "99"), 404, "ROW_NOT_FOUND");
    }

    @Test
    void imageDoesNotOverwriteExistingFiles(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("existing.png");
        Files.writeString(file, "keep me");
        assertError(post("/exec", "op", "image", "selector", "image", "file", file.toString()), 409, "CONFLICT");
        assertEquals("keep me", Files.readString(file));
        ok("/exec", "op", "image", "selector", "image", "file", file.toString(), "overwrite", "true");
        assertEquals((byte) 0x89, Files.readAllBytes(file)[0], "now a PNG");
    }
}
