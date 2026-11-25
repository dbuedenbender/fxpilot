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

import javafx.geometry.Bounds;
import javafx.scene.Node;
import org.junit.jupiter.api.Test;

import static io.github.dbuedenbender.fxpilot.EndpointHttp.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code fxpilot.deny} must hold for every way of triggering a control, not only for direct clicks.
 * The fixture's #deleteAll is the default button, and "Wipe" exists as a menu item (Ctrl+W) and as a
 * context-menu item of #nameField.
 */
class EndpointDenyTest extends UiTestBase {

    @Override
    void configure() {
        System.setProperty("fxpilot.deny", "#deleteAll,text=Wipe,#nuke,#lockedTab");
    }

    @Test
    void directActionsAreDenied() throws Exception {
        assertError(post("/click", "selector", "deleteAll"), 403, "NODE_DENIED");
        assertError(post("/exec", "op", "click", "selector", "deleteAll", "mode", "mouse"), 403, "NODE_DENIED");
        assertError(post("/exec", "op", "focus", "selector", "deleteAll"), 403, "NODE_DENIED");
        assertEquals("", status());
    }

    @Test
    void keyOnDeniedFocusOwnerIsDenied() throws Exception {
        TestUi.onFx(() -> Selectors.find("deleteAll").requestFocus());
        assertError(post("/key", "key", "SPACE"), 403, "NODE_DENIED");
        assertError(post("/exec", "op", "pressKey", "key", "SPACE"), 403, "NODE_DENIED");
        assertEquals("", status());
    }

    @Test
    void enterThatWouldFireADeniedDefaultButtonIsDenied() throws Exception {
        assertError(post("/key", "selector", "nameField", "key", "ENTER"), 403, "NODE_DENIED");
        assertError(post("/exec", "op", "pressKey", "selector", "nameField", "key", "ENTER"), 403, "NODE_DENIED");
        ok("/key", "selector", "nameField", "key", "TAB");
        assertEquals("", status());
    }

    @Test
    void acceleratorOfADeniedMenuItemIsDenied() throws Exception {
        assertError(post("/exec", "op", "hotkey", "keys", "CTRL+W"), 403, "NODE_DENIED");
        ok("/exec", "op", "hotkey", "keys", "CTRL+S");
        assertEquals("", status());
    }

    @Test
    void acceleratorOfADeniedMenuButtonItemIsDenied() throws Exception {
        assertError(post("/exec", "op", "hotkey", "keys", "CTRL+D"), 403, "NODE_DENIED");
        assertEquals("", status());
    }

    @Test
    void deniedTabsCannotBeSelectedOrClosed() throws Exception {
        assertError(post("/exec", "op", "tab", "selector", "tabs", "tab", "Locked"), 403, "NODE_DENIED");
        assertError(post("/select", "selector", "tabs", "value", "Locked"), 403, "NODE_DENIED");
        ok("/select", "selector", "tabs", "value", "Advanced");
    }

    @Test
    void deniedMenuItemsCannotBeFired() throws Exception {
        assertError(post("/exec", "op", "menu", "path", "Danger > Wipe"), 403, "NODE_DENIED");
        assertError(post("/menu", "menu", "Danger", "item", "Wipe"), 403, "NODE_DENIED");
        ok("/exec", "op", "menu", "path", "File > Save");
        assertEquals("Menu saved", status());
    }

    @Test
    void deniedContextMenuItemsCannotBeFired() throws Exception {
        ok("/exec", "op", "contextMenu", "selector", "nameField");
        assertError(post("/exec", "op", "contextMenuItem", "item", "Wipe"), 403, "NODE_DENIED");
        ok("/exec", "op", "contextMenuItem", "item", "Shout");
        assertEquals("Shouted", status());
    }

    @Test
    void dragOntoOrFromADeniedNodeIsDenied() throws Exception {
        assertError(post("/exec", "op", "drag", "selector", "okButton", "target", "deleteAll"), 403, "NODE_DENIED");
        assertError(post("/exec", "op", "drag", "selector", "deleteAll", "target", "okButton"), 403, "NODE_DENIED");
    }

    @Test
    void robotClickOnADeniedNodeIsDenied() throws Exception {
        double[] center = FxThread.query(() -> {
            Node n = Selectors.find("deleteAll");
            Bounds b = n.localToScene(n.getBoundsInLocal());
            return new double[]{b.getCenterX(), b.getCenterY()};
        });
        assertError(post("/robotClick", "x", String.valueOf(center[0]), "y", String.valueOf(center[1])), 403,
                "NODE_DENIED");
        assertError(post("/robotDrag", "x1", "1", "y1", "1", "x2", String.valueOf(center[0]),
                "y2", String.valueOf(center[1])), 403, "NODE_DENIED");
    }

    @Test
    void nodeAtFindsTheDeepestVisibleNode() throws Exception {
        String id = FxThread.query(() -> {
            Node n = Selectors.find("okButton");
            Bounds b = n.localToScene(n.getBoundsInLocal());
            Node hit = DenyList.nodeAt(n.getScene().getRoot(), b.getCenterX(), b.getCenterY());
            for (Node p = hit; p != null; p = p.getParent()) {
                if (p.getId() != null) {
                    return p.getId();
                }
            }
            return null;
        });
        assertEquals("okButton", id);
    }
}
