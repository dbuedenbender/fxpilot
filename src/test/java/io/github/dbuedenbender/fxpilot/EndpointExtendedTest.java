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

import static io.github.dbuedenbender.fxpilot.EndpointHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Extended operations behind {@code /exec}. */
class EndpointExtendedTest extends UiTestBase {

    @Test
    void clickWithMouseModeAndHotkey() throws Exception {
        ok("/exec", "op", "click", "selector", "okButton", "mode", "mouse");
        ok("/exec", "op", "hotkey", "keys", "CTRL+S");
        ok("/exec", "op", "pressKey", "selector", "nameField", "key", "A", "modifiers", "SHIFT");
        assertError(post("/exec", "op", "click", "selector", "okButton", "button", "LEFTISH"), 400, "INVALID_VALUE");
    }

    @Test
    void textActions() throws Exception {
        ok("/setText", "selector", "nameField", "text", "Hello");
        ok("/exec", "op", "textAction", "selector", "nameField", "action", "append", "value", " World");
        assertTrue(read("/node", "selector", "nameField").contains("\"text\":\"Hello World\""));
        ok("/exec", "op", "textAction", "selector", "nameField", "action", "replace", "start", "0", "end", "5",
                "value", "Bye");
        assertTrue(read("/node", "selector", "nameField").contains("\"text\":\"Bye World\""));
        ok("/exec", "op", "textAction", "selector", "nameField", "action", "clear");
        assertTrue(read("/node", "selector", "nameField").contains("\"text\":\"\""));
        assertError(post("/exec", "op", "textAction", "selector", "password", "action", "copy"), 403, "NODE_DENIED");
    }

    @Test
    void selectionOperations() throws Exception {
        ok("/exec", "op", "selectRows", "selector", "list", "indices", "0,2");
        assertTrue(read("/node", "selector", "list").contains("\"selectedIndices\":[0,2]"));
        ok("/exec", "op", "clearSelection", "selector", "list");
        assertTrue(read("/node", "selector", "list").contains("\"selectedIndices\":[]"));
        ok("/exec", "op", "selectAll", "selector", "list");
        assertTrue(read("/node", "selector", "list").contains("\"selectedIndices\":[0,1,2]"));
    }

    @Test
    void tableOperations() throws Exception {
        ok("/exec", "op", "tableSort", "selector", "customers", "column", "Name", "order", "DESCENDING");
        assertTrue(read("/exec", "op", "tableCell", "selector", "customers", "row", "0", "column", "0")
                .contains("\"value\":\"Sample Inc.\""));
        ok("/exec", "op", "tableColumn", "selector", "customers", "column", "Country", "action", "visible",
                "value", "false");
        assertTrue(read("/node", "selector", "customers").contains("{\"text\":\"Country\",\"visible\":false"));
        ok("/exec", "op", "tableSelect", "selector", "customers", "row", "1");
        assertTrue(read("/node", "selector", "customers").contains("\"selectedIndex\":1"));
        assertError(post("/exec", "op", "tableSort", "selector", "customers", "column", "Nope"), 404,
                "ITEM_NOT_FOUND");
    }

    @Test
    void treeTabsAndSteps() throws Exception {
        ok("/exec", "op", "tree", "selector", "orders", "action", "expandAll");
        assertTrue(read("/node", "selector", "orders").contains("Item A"));
        ok("/exec", "op", "tab", "selector", "tabs", "tab", "Advanced");
        assertTrue(read("/node", "selector", "tabs").contains("{\"text\":\"Advanced\",\"selected\":true}"));
        ok("/exec", "op", "step", "selector", "date", "amount", "1");
        ok("/value", "selector", "volume", "value", "10");
        ok("/exec", "op", "step", "selector", "volume", "amount", "1");
        assertTrue(read("/node", "selector", "volume").contains("\"value\":20"));
    }

    @Test
    void propertySetAndWindowActions() throws Exception {
        ok("/exec", "op", "propertySet", "selector", "nameField", "name", "promptText", "value", "Your name");
        assertTrue(read("/node", "selector", "nameField").contains("\"promptText\":\"Your name\""));
        ok("/exec", "op", "window", "action", "resize", "width", "640", "height", "480");
        assertTrue(read("/windows").contains("\"width\":640"));
        assertError(post("/exec", "op", "window", "action", "spin"), 400, "INVALID_VALUE");
    }

    @Test
    void focusAndPopupAndContextMenu() throws Exception {
        ok("/exec", "op", "focus", "selector", "nameField");
        assertTrue(read("/node", "selector", "nameField").contains("\"focused\":true"));
        ok("/exec", "op", "popup", "selector", "country", "action", "open");
        ok("/exec", "op", "popup", "selector", "country", "action", "close");
        assertError(post("/exec", "op", "popup", "selector", "okButton"), 400, "UNSUPPORTED_NODE");
        ok("/exec", "op", "contextMenu", "selector", "nameField");
    }

    @Test
    void dragFiresMouseEvents() throws Exception {
        String body = ok("/exec", "op", "drag", "selector", "okButton", "target", "nameField");
        assertTrue(body.contains("\"status\":\"done\""), body);
    }

    @Test
    void unknownOperation() throws Exception {
        assertError(post("/exec", "op", "fly"), 400, "INVALID_OPERATION");
        assertEquals(405, get("/exec", "op", "propertySet", "selector", "okButton", "name", "text").statusCode());
    }
}
