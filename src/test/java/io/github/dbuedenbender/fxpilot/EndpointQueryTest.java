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

import java.net.http.HttpResponse;

import static io.github.dbuedenbender.fxpilot.EndpointHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-only endpoints against a real (headless) scene graph. */
class EndpointQueryTest extends UiTestBase {

    @Test
    void healthListsWindowTitlesAndNotes() throws Exception {
        FxPilotServer.setNotes("Do not click #deleteAll");
        try {
            String body = read("/health");
            assertTrue(body.contains("\"status\":\"ok\""), body);
            assertTrue(body.contains(TestUi.TITLE), body);
            assertTrue(body.contains("Do not click #deleteAll"), body);
        } finally {
            FxPilotServer.setNotes(null);
        }
    }

    @Test
    void windowsListsTheMainStage() throws Exception {
        String body = read("/windows");
        assertTrue(body.contains("\"title\":\"" + TestUi.TITLE + "\""), body);
        assertTrue(body.contains("\"index\":0"), body);
    }

    @Test
    void windowByTitleAndUnknownWindow() throws Exception {
        assertTrue(read("/view", "window", "title:fxpilot test").contains("okButton"));
        assertError(get("/view", "window", "title:nope"), 404, "WINDOW_NOT_FOUND");
        assertError(get("/view", "window", "7"), 404, "WINDOW_NOT_FOUND");
        assertError(get("/view", "window", "abc"), 400, "BAD_REQUEST");
    }

    @Test
    void treeContainsControlsButNotSkinInternalsByDefault() throws Exception {
        String body = read("/tree");
        assertTrue(body.contains("\"id\":\"okButton\""), body);
        assertTrue(body.contains("\"id\":\"customers\""), body);
        assertFalse(body.contains("\"styleClass\":[\"virtual-flow\"]"), "skin internals only with full=true");
        assertTrue(read("/tree", "full", "true").contains("virtual-flow"));
        String limited = read("/tree", "maxDepth", "1");
        assertFalse(limited.contains("okButton"), limited);
    }

    @Test
    void viewIsCompactAndHasRefs() throws Exception {
        String body = read("/view", "limit", "500");
        assertTrue(body.contains("\"id\":\"okButton\""), body);
        assertTrue(body.contains("\"ref\":\"VBox[0]/"), body);
        assertFalse(body.contains("hiddenButton"), "hidden nodes are not part of the view");
    }

    @Test
    void nodeReportsTableTreeAndPasswordState() throws Exception {
        String table = read("/node", "selector", "customers");
        assertTrue(table.contains("Sample Inc. | CH"), table);
        assertTrue(table.contains("\"rowCount\":3"), table);
        String tree = read("/node", "selector", "orders");
        assertTrue(tree.contains("Order 1001"), tree);
        String password = read("/node", "selector", "password");
        assertTrue(password.contains("\"text\":\"***\""), password);
        assertFalse(password.contains("geheim"), password);
    }

    @Test
    void selectorForms() throws Exception {
        assertTrue(read("/node", "selector", "#okButton").contains("\"id\":\"okButton\""));
        assertTrue(read("/node", "selector", "text=OK").contains("\"id\":\"okButton\""));
        assertTrue(read("/node", "selector", "text*=Delete").contains("\"id\":\"deleteAll\""));
        assertTrue(read("/node", "selector", "id=okButton").contains("\"id\":\"okButton\""));
        assertTrue(read("/node", "selector", "label=Name").contains("\"id\":\"nameField\""));
        assertTrue(read("/node", "selector", ".danger").contains("\"id\":\"deleteAll\""));
        assertTrue(read("/node", "selector", "role=checkbox").contains("\"id\":\"agree\""));
        assertTrue(read("/node", "selector", "text=Dup", "nth", "1").contains("\"id\":\"dupB\""));
        assertTrue(read("/node", "selector", "text=Dup", "nth", "last").contains("\"id\":\"dupB\""));
        assertTrue(read("/node", "selector", "Button", "within", "#buttons").contains("\"id\":\"okButton\""));
    }

    @Test
    void refFromViewResolvesToTheSameNode() throws Exception {
        String node = read("/node", "selector", "okButton");
        int i = node.indexOf("\"ref\":\"") + 7;
        String ref = node.substring(i, node.indexOf('"', i));
        assertTrue(read("/node", "selector", "ref=" + ref).contains("\"id\":\"okButton\""));
    }

    @Test
    void matchesCountsAllCandidates() throws Exception {
        String body = read("/matches", "selector", "text=Dup");
        assertTrue(body.contains("\"count\":2"), body);
        assertTrue(body.contains("dupA") && body.contains("dupB"), body);
    }

    @Test
    void selectorErrors() throws Exception {
        assertError(get("/node", "selector", "noSuchNode"), 404, "NODE_NOT_FOUND");
        assertError(get("/node", "selector", "role=unicorn"), 400, "INVALID_SELECTOR");
        assertError(get("/node", "selector", "text=Dup", "nth", "5"), 404, "NODE_NOT_FOUND");
        assertError(get("/node", "selector", "text=Dup", "nth", "x"), 400, "INVALID_SELECTOR");
        assertError(get("/node"), 400, "INVALID_SELECTOR");
    }

    @Test
    void screenshotIsAPng() throws Exception {
        HttpResponse<byte[]> r = getBytes("/screenshot");
        assertEquals(200, r.statusCode());
        byte[] png = r.body();
        assertTrue(png.length > 100);
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        HttpResponse<byte[]> node = getBytes("/screenshot", "selector", "okButton");
        assertEquals(200, node.statusCode());
        HttpResponse<byte[]> crop = getBytes("/screenshot", "x", "0", "y", "0", "width", "10", "height", "10");
        assertEquals(200, crop.statusCode());
    }

    @Test
    void readOnlyExtendedOperationsUseGet() throws Exception {
        assertTrue(read("/exec", "op", "tableCell", "selector", "customers", "row", "1", "column", "Country")
                .contains("\"value\":\"DE\""));
        assertTrue(read("/exec", "op", "tableCell", "selector", "customers", "row", "2", "column", "Name")
                .contains("\"value\":\"Beta | Corp\""));
        assertTrue(read("/exec", "op", "tooltip", "selector", "okButton").contains("Saves the form"));
        assertTrue(read("/exec", "op", "propertyGet", "selector", "okButton", "name", "text")
                .contains("\"value\":\"OK\""));
        assertError(get("/exec", "op", "propertyGet", "selector", "password", "name", "text"), 403, "NODE_DENIED");
        assertTrue(read("/exec", "op", "idle").contains("\"status\":\"done\""));
        assertError(get("/exec", "op", "propertyGet", "selector", "okButton", "name", "noSuchProperty"),
                404, "PROPERTY_NOT_FOUND");
    }
}
