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

import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * Selector patterns from {@code fxpilot.deny}. A node (or one of its ancestors) matching any
 * pattern cannot be driven, which turns the advisory notes into a real lock. Besides direct actions
 * this also covers the indirect ways to trigger a control: keys that reach a denied focus owner,
 * ENTER/ESCAPE on a denied default/cancel button, accelerators of denied menu items, menu and
 * context-menu entries, and robot clicks at the coordinates of a denied node.
 */
final class DenyList {

    private static volatile List<String> denyPatterns = List.of();

    private DenyList() {
    }

    static void setDenyPatterns(List<String> patterns) {
        denyPatterns = patterns == null ? List.of() : List.copyOf(patterns);
    }

    static boolean isEmpty() {
        return denyPatterns.isEmpty();
    }

    static void checkDenied(String selector, Node node) {
        List<String> patterns = denyPatterns;
        if (patterns.isEmpty() || node == null) {
            return;
        }
        for (Node n = node; n != null; n = n.getParent()) {
            for (String pattern : patterns) {
                if (matches(pattern, n.getId(), n.getStyleClass(), text(n), n.getClass().getSimpleName())) {
                    throw denied("Node '" + selector + "'", pattern);
                }
            }
        }
    }

    /** Menu items are no nodes; {@code #id}, {@code .styleClass} and {@code text=} apply to them and their parent menus. */
    static void checkMenuItem(MenuItem item) {
        List<String> patterns = denyPatterns;
        if (patterns.isEmpty() || item == null) {
            return;
        }
        for (MenuItem i = item; i != null; i = i.getParentMenu()) {
            for (String pattern : patterns) {
                if (matches(pattern, i.getId(), i.getStyleClass(), i.getText(), i.getClass().getSimpleName())) {
                    throw denied("Menu item '" + item.getText() + "'", pattern);
                }
            }
        }
        ContextMenu popup = item.getParentPopup();
        if (popup != null) {
            Node owner = popup.getOwnerNode();
            checkDenied("owner of menu item '" + item.getText() + "'", owner);
        }
    }

    /**
     * A key event can fire more than its target: ENTER/ESCAPE reach the scene's default/cancel button
     * and key combinations trigger menu accelerators. All of them must be allowed.
     */
    static void checkKey(Node target, KeyCode code, boolean shift, boolean control, boolean alt, boolean meta) {
        if (denyPatterns.isEmpty() || target == null) {
            return;
        }
        checkDenied(target.getClass().getSimpleName(), target);
        Scene scene = target.getScene();
        if (scene == null) {
            return;
        }
        if (code == KeyCode.ENTER || code == KeyCode.ESCAPE) {
            for (Node n : all(scene.getRoot())) {
                if (n instanceof Button && n.isVisible() && !n.isDisabled()
                        && (code == KeyCode.ENTER ? ((Button) n).isDefaultButton() : ((Button) n).isCancelButton())) {
                    checkDenied((code == KeyCode.ENTER ? "default" : "cancel") + " button", n);
                }
            }
        }
        KeyEvent event = new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, shift, control, alt, meta);
        for (Node n : all(scene.getRoot())) {
            // every place a MenuItem with an accelerator can live; the owner node counts as well
            if (n instanceof MenuBar) {
                for (Menu menu : ((MenuBar) n).getMenus()) {
                    checkAccelerators(menu.getItems(), event, n);
                }
            }
            if (n instanceof MenuButton) {
                checkAccelerators(((MenuButton) n).getItems(), event, n);
            }
            if (n instanceof Control && ((Control) n).getContextMenu() != null) {
                checkAccelerators(((Control) n).getContextMenu().getItems(), event, n);
            }
            if (n instanceof TabPane) {
                for (Tab tab : ((TabPane) n).getTabs()) {
                    if (tab.getContextMenu() != null) {
                        checkAccelerators(tab.getContextMenu().getItems(), event, n);
                    }
                }
            }
            if (n instanceof TableView) {
                checkColumns(((TableView<?>) n).getColumns(), event, n);
            }
            if (n instanceof TreeTableView) {
                checkColumns(((TreeTableView<?>) n).getColumns(), event, n);
            }
        }
        for (Window w : Window.getWindows()) {
            if (w instanceof ContextMenu && w.isShowing()) {
                checkAccelerators(((ContextMenu) w).getItems(), event, ((ContextMenu) w).getOwnerNode());
            }
        }
        // Scene accelerators are plain Runnables without a target node, so they cannot be matched to a pattern.
    }

    private static void checkColumns(List<? extends TableColumnBase<?, ?>> columns, KeyEvent event, Node owner) {
        for (TableColumnBase<?, ?> column : columns) {
            if (column.getContextMenu() != null) {
                checkAccelerators(column.getContextMenu().getItems(), event, owner);
            }
            checkColumns(column.getColumns(), event, owner);
        }
    }

    private static void checkAccelerators(List<MenuItem> items, KeyEvent event, Node owner) {
        for (MenuItem item : items) {
            if (item.getAccelerator() != null && item.getAccelerator().match(event)) {
                checkMenuItem(item);
                checkDenied("owner of menu item '" + item.getText() + "'", owner);
            }
            if (item instanceof Menu) {
                checkAccelerators(((Menu) item).getItems(), event, owner);
            }
        }
    }

    /** Tabs are no nodes; {@code #id}, {@code .styleClass} and {@code text=} apply to them, and to their TabPane. */
    static void checkTab(Tab tab, TabPane pane) {
        List<String> patterns = denyPatterns;
        if (patterns.isEmpty()) {
            return;
        }
        for (String pattern : patterns) {
            if (matches(pattern, tab.getId(), tab.getStyleClass(), tab.getText(), tab.getClass().getSimpleName())) {
                throw denied("Tab '" + tab.getText() + "'", pattern);
            }
        }
        checkDenied("TabPane of tab '" + tab.getText() + "'", pane);
    }

    /**
     * Guards a robot click at scene coordinates of {@code window}. The robot clicks the real screen, so a
     * point outside the scene could hit another window or application and is always rejected. Inside, every
     * showing window at that screen position counts (popups and dialogs lie on top of their owner), and
     * the deepest node under the point must not be denied in any of them.
     */
    static void checkPoint(Window window, double sceneX, double sceneY) {
        Scene scene = window.getScene();
        if (!Double.isFinite(sceneX) || !Double.isFinite(sceneY)
                || sceneX < 0 || sceneY < 0 || sceneX >= scene.getWidth() || sceneY >= scene.getHeight()) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "Point " + sceneX + "," + sceneY
                    + " is outside the window's scene (" + scene.getWidth() + "x" + scene.getHeight() + ")");
        }
        if (denyPatterns.isEmpty()) {
            return;
        }
        double screenX = window.getX() + scene.getX() + sceneX;
        double screenY = window.getY() + scene.getY() + sceneY;
        for (Window w : Window.getWindows()) {
            Scene s = w.getScene();
            if (!w.isShowing() || s == null) {
                continue;
            }
            double x = screenX - w.getX() - s.getX();
            double y = screenY - w.getY() - s.getY();
            if (x >= 0 && y >= 0 && x < s.getWidth() && y < s.getHeight()) {
                checkDenied("node at " + sceneX + "," + sceneY, nodeAt(s.getRoot(), x, y));
            }
        }
    }

    static Node nodeAt(Node node, double sceneX, double sceneY) {
        if (!node.isVisible() || node.isMouseTransparent()) {
            return null;
        }
        Point2D local = node.sceneToLocal(sceneX, sceneY);
        if (local == null || !node.contains(local) && !node.getBoundsInLocal().contains(local)) {
            return null;
        }
        if (node instanceof Parent) {
            List<Node> children = ((Parent) node).getChildrenUnmodifiable();
            for (int i = children.size() - 1; i >= 0; i--) {
                Node hit = nodeAt(children.get(i), sceneX, sceneY);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return node;
    }

    private static List<Node> all(Node root) {
        List<Node> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(Node node, List<Node> out) {
        out.add(node);
        if (node instanceof Parent) {
            for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
                collect(child, out);
            }
        }
    }

    private static boolean matches(String pattern, String id, List<String> styleClass, String text, String type) {
        String p = pattern.trim();
        if (p.isEmpty()) {
            return false;
        }
        if (p.startsWith("#")) {
            return p.substring(1).equals(id);
        }
        if (p.startsWith(".")) {
            return styleClass.contains(p.substring(1));
        }
        if (p.startsWith("text=")) {
            return p.substring(5).equals(text);
        }
        return type.equals(p);
    }

    private static String text(Node node) {
        return node instanceof Labeled ? ((Labeled) node).getText() : null;
    }

    private static FxPilotException denied(String what, String pattern) {
        return new FxPilotException(403, Codes.NODE_DENIED, what + " is denied by fxpilot.deny='" + pattern + "'");
    }
}
