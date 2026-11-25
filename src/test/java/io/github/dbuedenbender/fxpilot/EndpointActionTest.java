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

/** Core actions (the endpoints behind the non-extended MCP tools). */
class EndpointActionTest extends UiTestBase {

    @Test
    void setTextThenClickFiresTheButton() throws Exception {
        ok("/setText", "selector", "nameField", "text", "Sample Inc.");
        String click = ok("/click", "selector", "okButton");
        assertTrue(click.contains("\"status\":\"done\""), click);
        assertEquals("Saved Sample Inc.", status());
    }

    @Test
    void typeAppendsCharacters() throws Exception {
        ok("/setText", "selector", "nameField", "text", "Ab");
        ok("/type", "selector", "nameField", "text", "cd");
        assertTrue(read("/node", "selector", "nameField").contains("\"text\":\"Abcd\""));
    }

    @Test
    void ambiguousSelectorWarnsAndUsesTheFirstMatch() throws Exception {
        String body = ok("/click", "selector", "text=Dup");
        assertTrue(body.contains("\"matches\":2"), body);
        assertTrue(body.contains("\"warning\""), body);
        assertEquals("dupA", status());
        ok("/click", "selector", "text=Dup", "nth", "1");
        assertEquals("dupB", status());
    }

    @Test
    void requireVisibleAndEnabled() throws Exception {
        assertError(post("/click", "selector", "hiddenButton", "requireVisible", "true"), 409, "NODE_HIDDEN");
        assertError(post("/click", "selector", "disabledButton", "requireEnabled", "true"), 409, "NODE_DISABLED");
    }

    @Test
    void selectInComboListTableTreeAndTabs() throws Exception {
        ok("/select", "selector", "country", "value", "CH");
        assertTrue(read("/node", "selector", "country").contains("\"value\":\"CH\""));
        ok("/select", "selector", "list", "index", "2");
        assertTrue(read("/node", "selector", "list").contains("\"selectedIndex\":2"));
        ok("/select", "selector", "customers", "value", "Acme | DE");
        assertTrue(read("/node", "selector", "customers").contains("\"selectedRow\":\"Acme | DE\""));
        ok("/select", "selector", "orders", "value", "Item A");
        assertTrue(read("/node", "selector", "orders").contains("\"selectedItem\":\"Item A\""));
        ok("/select", "selector", "tabs", "value", "Advanced");
        assertTrue(read("/node", "selector", "tabs").contains("{\"text\":\"Advanced\",\"selected\":true}"));
    }

    @Test
    void selectErrors() throws Exception {
        assertError(post("/select", "selector", "country", "value", "FR"), 404, "ITEM_NOT_FOUND");
        assertError(post("/select", "selector", "customers", "value", "Nobody"), 404, "ROW_NOT_FOUND");
        assertError(post("/select", "selector", "orders", "value", "Nothing"), 404, "TREE_ITEM_NOT_FOUND");
        assertError(post("/select", "selector", "tabs", "value", "Nope"), 404, "TAB_NOT_FOUND");
        assertError(post("/select", "selector", "okButton", "value", "x"), 400, "UNSUPPORTED_NODE");
    }

    @Test
    void setValueOnSliderAndDatePicker() throws Exception {
        ok("/value", "selector", "volume", "value", "42");
        assertTrue(read("/node", "selector", "volume").contains("\"value\":42"));
        ok("/value", "selector", "date", "value", "2026-12-01");
        assertTrue(read("/node", "selector", "date").contains("\"value\":\"2026-12-01\""));
        assertError(post("/value", "selector", "date", "value", "01.12.2026"), 400, "INVALID_VALUE");
        assertError(post("/value", "selector", "volume", "value", "loud"), 400, "INVALID_VALUE");
    }

    @Test
    void checkFiresTheActionOnlyOnChange() throws Exception {
        ok("/check", "selector", "agree", "checked", "true");
        assertEquals("agree=true", status());
        assertTrue(read("/node", "selector", "agree").contains("\"selected\":true"));
        assertError(post("/check", "selector", "okButton", "checked", "true"), 400, "UNSUPPORTED_NODE");
    }

    @Test
    void expandAndCollapseTreeItems() throws Exception {
        String body = ok("/expand", "selector", "orders", "value", "Order 1001");
        assertTrue(body.contains("\"expanded\":true"), body);
        assertTrue(body.contains("\"visibleRows\":3"), body);
        ok("/expand", "selector", "orders", "value", "Order 1001", "expanded", "false");
        assertError(post("/expand", "selector", "orders", "value", "Order 1002"), 409, "NO_CHILDREN");
    }

    @Test
    void menuFiresNestedItems() throws Exception {
        ok("/exec", "op", "menu", "path", "File > Save");
        assertEquals("Menu saved", status());
        ok("/exec", "op", "menu", "path", "File > Export > PDF");
        assertEquals("Exported PDF", status());
        assertError(post("/exec", "op", "menu", "path", "Nope > Save"), 404, "MENU_NOT_FOUND");
        ok("/menu", "menu", "File", "item", "Save");
    }

    @Test
    void pressKeyAndScroll() throws Exception {
        String key = ok("/key", "selector", "nameField", "key", "TAB");
        assertTrue(key.contains("\"status\":\"done\""), key);
        assertError(post("/key", "selector", "nameField", "key", "NOPE"), 400, "INVALID_KEY");
        ok("/scroll", "selector", "customers", "amount", "1");
        assertError(post("/scroll", "selector", "okButton"), 400, "NOT_SCROLLABLE");
    }

    @Test
    void modalDialogReturnsPendingAndCanBeOperated() throws Exception {
        String body = ok("/click", "selector", "openDialog");
        assertTrue(body.contains("\"status\":\"pending\""), body);
        String windows = read("/windows");
        assertTrue(windows.contains("\"title\":\"Confirm\""), windows);
        ok("/click", "selector", "closeDialog");
        long end = System.currentTimeMillis() + 5000;
        while (!"Dialog closed".equals(status()) && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertEquals("Dialog closed", status());
    }

    @Test
    void menuItemThatOpensAModalDialogReturnsPending() throws Exception {
        long start = System.currentTimeMillis();
        String body = ok("/exec", "op", "menu", "path", "File > Confirm...");
        assertTrue(body.contains("\"status\":\"pending\""), body);
        assertTrue(System.currentTimeMillis() - start < 8000, "must not wait for the FX thread timeout");
        ok("/click", "selector", "closeDialog");
        long end = System.currentTimeMillis() + 5000;
        while (!"Dialog closed".equals(status()) && System.currentTimeMillis() < end) {
            Thread.sleep(50);
        }
        assertEquals("Dialog closed", status());
    }

    @Test
    void strictModeRejectsAmbiguousActions() throws Exception {
        System.setProperty("fxpilot.strictSelectors", "true");
        HttpResponse<String> r = post("/click", "selector", "text=Dup");
        assertError(r, 409, "NODE_AMBIGUOUS");
        assertTrue(r.body().contains("dupA") && r.body().contains("dupB"), r.body());
        assertFalse(status().startsWith("dup"));
    }
}
