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

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.dbuedenbender.fxpilot.EndpointHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TreeTableView, charts, canvas, images, windows and the smaller controls. */
class EndpointContentTest extends UiTestBase {

    @Test
    void treeTableRowsCellsAndExpansion() throws Exception {
        assertTrue(read("/node", "selector", "machines").contains("\"rowCount\":1"));
        ok("/select", "selector", "machines", "value", "Line | A | stopped");
        String node = read("/node", "selector", "machines");
        assertTrue(node.contains("\"selectedRow\":\"Line | A | stopped\""), node);
        assertTrue(read("/exec", "op", "tableCell", "selector", "machines", "row", "1", "column", "Machine")
                .contains("\"value\":\"Line | A\""), "cells containing ' | ' are read as a whole");
        assertTrue(read("/exec", "op", "tableCell", "selector", "machines", "row", "1", "column", "State")
                .contains("\"value\":\"stopped\""));
        assertError(get("/exec", "op", "tableCell", "selector", "machines", "row", "9", "column", "State"), 404,
                "ROW_NOT_FOUND");
        ok("/exec", "op", "tree", "selector", "machines", "action", "collapseAll");
        assertTrue(read("/node", "selector", "machines").contains("\"rowCount\":1"));
        ok("/expand", "selector", "machines", "value", "Plant 2");
        assertTrue(read("/node", "selector", "machines").contains("\"rowCount\":2"));
        ok("/exec", "op", "tableSort", "selector", "machines", "column", "State");
        ok("/exec", "op", "tableColumn", "selector", "machines", "column", "State", "action", "width", "value", "90");
    }

    @Test
    void tableColumnsScrollAndEdit() throws Exception {
        ok("/exec", "op", "tableColumn", "selector", "customers", "column", "Country", "action", "order", "value", "0");
        assertTrue(read("/node", "selector", "customers").contains("\"columns\":[{\"text\":\"Country\""));
        ok("/exec", "op", "tableColumn", "selector", "customers", "column", "Name", "action", "text", "value", "Customer");
        assertTrue(read("/node", "selector", "customers").contains("\"text\":\"Customer\""));
        ok("/exec", "op", "tableScroll", "selector", "customers", "row", "2");
        ok("/exec", "op", "tableEdit", "selector", "customers", "row", "0", "column", "0");
        assertError(post("/exec", "op", "tableCellSet", "selector", "customers", "row", "0", "column", "0", "value", "x"),
                400, "UNSUPPORTED_ACTION");
        assertError(post("/exec", "op", "tableSort", "selector", "okButton", "column", "0"), 400, "UNSUPPORTED_NODE");
    }

    @Test
    void chartData() throws Exception {
        String body = read("/exec", "op", "chart", "selector", "chart");
        assertTrue(body.contains("\"title\":\"Weekly output\""), body);
        assertTrue(body.contains("\"name\":\"Output\""), body);
        assertTrue(body.contains("{\"x\":\"Mon\",\"y\":3"), body);
        assertError(get("/exec", "op", "chart", "selector", "okButton"), 400, "CHART_NOT_FOUND");
    }

    @Test
    void canvasPixelAndClick() throws Exception {
        assertTrue(read("/exec", "op", "canvasPixel", "selector", "canvas", "x", "5", "y", "5")
                .contains("\"color\":\"#ffff0000\""));
        assertError(get("/exec", "op", "canvasPixel", "selector", "canvas", "x", "99", "y", "5"), 400, "INVALID_VALUE");
        assertError(get("/exec", "op", "canvasPixel", "selector", "okButton"), 400, "CANVAS_NOT_FOUND");
        ok("/exec", "op", "canvasClick", "selector", "canvas", "x", "5", "y", "6");
        assertEquals("canvas 5,6", status());
    }

    @Test
    void imageIsSavedAsPng(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("sub/image.png");
        String body = ok("/exec", "op", "image", "selector", "image", "file", file.toString());
        assertTrue(body.contains("\"width\":4"), body);
        byte[] png = Files.readAllBytes(file);
        assertEquals((byte) 0x89, png[0]);
        assertError(post("/exec", "op", "image", "selector", "okButton", "file", file.toString()), 400, "IMAGE_NOT_FOUND");
    }

    @Test
    void webAndMediaNeedTheirNodes() throws Exception {
        assertError(post("/exec", "op", "webExecute", "selector", "okButton", "script", "1"), 400, "WEB_NOT_AVAILABLE");
        assertError(get("/exec", "op", "webQuery", "selector", "okButton", "css", "body"), 400, "WEB_NOT_AVAILABLE");
        assertError(get("/exec", "op", "webHtml", "selector", "okButton"), 400, "WEB_NOT_AVAILABLE");
        assertError(post("/exec", "op", "media", "selector", "okButton", "action", "play"), 400, "MEDIA_NOT_AVAILABLE");
        assertTrue(read("/exec", "op", "mediaStatus", "selector", "okButton").contains("\"id\":\"okButton\""));
    }

    @Test
    void windowActions() throws Exception {
        ok("/exec", "op", "window", "action", "move", "x", "40", "y", "50");
        assertTrue(read("/windows").contains("\"x\":40"));
        ok("/exec", "op", "window", "action", "maximize");
        ok("/exec", "op", "window", "action", "unmaximize");
        ok("/exec", "op", "window", "action", "focus");
        assertError(post("/exec", "op", "window", "action", "dismiss"), 404, "ITEM_NOT_FOUND");
    }

    @Test
    void dialogCanBeAcceptedThroughTheWindowOperation() throws Exception {
        assertTrue(ok("/click", "selector", "openDialog").contains("\"status\":\"pending\""));
        ok("/exec", "op", "window", "window", "title:Confirm", "action", "accept");
        long end = System.currentTimeMillis() + 5000;
        while (!"Dialog closed".equals(status()) && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertEquals("Dialog closed", status());
    }

    @Test
    void accordionPaginationAndSpinner() throws Exception {
        ok("/exec", "op", "accordion", "selector", "accordion", "pane", "Second");
        assertTrue(read("/exec", "op", "propertyGet", "selector", "text=Second", "name", "expanded")
                .contains("\"value\":true"));
        ok("/exec", "op", "pagination", "selector", "pages", "action", "next");
        ok("/exec", "op", "pagination", "selector", "pages", "action", "next");
        ok("/exec", "op", "pagination", "selector", "pages", "action", "prev");
        assertTrue(read("/exec", "op", "propertyGet", "selector", "pages", "name", "currentPageIndex")
                .contains("\"value\":1"));
        ok("/exec", "op", "pagination", "selector", "pages", "page", "4");
        ok("/exec", "op", "step", "selector", "spinner", "amount", "2");
        assertTrue(read("/node", "selector", "spinner").contains("\"value\":\"5\""));
        ok("/value", "selector", "spinner", "value", "7");
        assertTrue(read("/node", "selector", "spinner").contains("\"value\":\"7\""));
        assertError(post("/exec", "op", "accordion", "selector", "accordion", "pane", "Third"), 404, "ITEM_NOT_FOUND");
    }

    @Test
    void scrollingAndWheel() throws Exception {
        ok("/exec", "op", "scrollTo", "selector", "list", "index", "2");
        ok("/exec", "op", "scrollTo", "selector", "customers", "row", "1");
        ok("/exec", "op", "wheel", "selector", "list", "deltaY", "-40");
        assertError(post("/exec", "op", "scrollTo", "selector", "okButton", "index", "1"), 400, "UNSUPPORTED_NODE");
    }

    @Test
    void propertiesFromNodeProperties() throws Exception {
        TestUi.onFx(() -> Selectors.find("okButton").getProperties().put("role", "primary"));
        assertTrue(read("/exec", "op", "propertyGet", "selector", "okButton", "name", "role").contains("\"value\":\"primary\""));
        ok("/exec", "op", "propertySet", "selector", "okButton", "name", "custom", "value", "x");
        assertTrue(read("/exec", "op", "propertyGet", "selector", "okButton", "name", "custom").contains("\"value\":\"x\""));
        ok("/exec", "op", "propertySet", "selector", "okButton", "name", "disable", "value", "true");
        assertTrue(read("/node", "selector", "okButton").contains("\"disabled\":true"));
    }
}
