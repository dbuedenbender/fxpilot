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

import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Accordion;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Pagination;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trees, menus, context menus, tabs, accordions and pagination.
 */
final class NavigationOps {

    private NavigationOps() {
    }

    static void treeAction(Node node, Map<String, String> p) {
        String action = Params.opt(p, "action", "expandAll");
        if (node instanceof TreeView) {
            TreeView<?> tree = (TreeView<?>) node;
            switch (action) {
                case "expandAll": expandTree(tree.getRoot(), true); break;
                case "collapseAll": collapseAll(tree.getRoot(), tree.isShowRoot()); break;
                case "expand":
                case "collapse":
                    Actions.setExpanded(node, p.get("value"), p.get("index"), "expand".equals(action));
                    break;
                default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown tree action '" + action + "'");
            }
            return;
        }
        if (node instanceof TreeTableView) {
            TreeTableView<?> table = (TreeTableView<?>) node;
            switch (action) {
                case "expandAll": expandTree(table.getRoot(), true); break;
                case "collapseAll": collapseAll(table.getRoot(), table.isShowRoot()); break;
                case "expand":
                case "collapse":
                    Actions.setExpanded(node, p.get("value"), p.get("index"), "expand".equals(action));
                    break;
                default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown tree action '" + action + "'");
            }
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not a TreeView/TreeTableView");
    }

    /** A hidden root stays expanded; collapsing it would leave the control empty. */
    private static void collapseAll(TreeItem<?> root, boolean showRoot) {
        expandTree(root, false);
        if (root != null && !showRoot) {
            root.setExpanded(true);
        }
    }

    private static void expandTree(TreeItem<?> item, boolean expanded) {
        if (item == null) {
            return;
        }
        item.setExpanded(expanded);
        for (TreeItem<?> child : item.getChildren()) {
            expandTree(child, expanded);
        }
    }

    static Map<String, Object> menuAction(Map<String, String> p) {
        String selector = p.get("selector");
        if (selector == null || selector.isBlank()) {
            return FxThread.interact(() -> {
                Node bar = Selectors.findFirst(MenuBar.class);
                menuPath(bar, Params.req(p, "path"));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", "done");
                m.put("node", NodeInfo.info(bar));
                return m;
            });
        }
        return Params.action(p, n -> menuPath(n, Params.req(p, "path")));
    }

    private static void requireEnabled(MenuItem item) {
        if (item.isDisable()) {
            throw new FxPilotException(409, Codes.NODE_DISABLED, "Menu item '" + item.getText() + "' is disabled");
        }
    }

    private static void menuPath(Node node, String path) {
        if (!(node instanceof MenuBar)) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not a MenuBar");
        }
        String[] parts = path.split("\\s*>\\s*");
        if (parts.length == 0) {
            throw new FxPilotException(400, Codes.BAD_REQUEST, "menu path is required");
        }
        MenuBar bar = (MenuBar) node;
        Menu menu = findMenu(bar.getMenus(), parts[0]);
        if (menu == null) {
            throw new FxPilotException(404, Codes.MENU_NOT_FOUND, "Menu '" + parts[0] + "' not found. Available: " + menuTexts(bar));
        }
        requireEnabled(menu);
        DenyList.checkMenuItem(menu);
        if (parts.length == 1) {
            menu.show();
            return;
        }
        MenuItem current = menu;
        for (int i = 1; i < parts.length; i++) {
            String label = parts[i].trim();
            MenuItem next = null;
            if (current instanceof Menu) {
                next = findMenuItem(((Menu) current).getItems(), label);
            }
            if (next == null) {
                throw new FxPilotException(404, Codes.MENU_NOT_FOUND, "Menu item '" + label + "' not found under '" + current.getText() + "'");
            }
            requireEnabled(next);
            DenyList.checkMenuItem(next);
            if (i == parts.length - 1) {
                if (next instanceof Menu) {
                    ((Menu) next).show();
                } else {
                    next.fire();
                }
                return;
            }
            if (!(next instanceof Menu)) {
                throw new FxPilotException(400, Codes.UNSUPPORTED_ACTION, "'" + label + "' is not a submenu");
            }
            current = next;
        }
    }

    private static Menu findMenu(List<Menu> menus, String text) {
        for (Menu menu : menus) {
            if (text.equals(menu.getText())) {
                return menu;
            }
        }
        return null;
    }

    private static MenuItem findMenuItem(List<MenuItem> items, String text) {
        for (MenuItem item : items) {
            if (text.equals(item.getText())) {
                return item;
            }
        }
        return null;
    }

    private static List<String> menuTexts(MenuBar bar) {
        List<String> result = new ArrayList<>();
        for (Menu menu : bar.getMenus()) {
            result.add(menu.getText());
        }
        return result;
    }

    static Map<String, Object> contextMenu(Map<String, String> p) {
        return FxThread.interact(() -> {
            Node node = Params.node(p);
            DenyList.checkDenied(p.get("selector"), node);
            Bounds b = node.localToScene(node.getBoundsInLocal());
            Point2D screen = node.localToScreen(b.getWidth() / 2, b.getHeight() / 2);
            double x = b.getMinX() + b.getWidth() / 2;
            double y = b.getMinY() + b.getHeight() / 2;
            double sx = screen != null ? screen.getX() : x;
            double sy = screen != null ? screen.getY() : y;
            Event.fireEvent(node, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    x, y, sx, sy, false, null));
            return Params.map("status", "done", "hint", "Context menu requested. Use contextMenuItem to fire an item.");
        });
    }

    /**
     * Fires an item of a showing context menu. Works on the ContextMenu model: the popup's item nodes are
     * skin internals (not Labeled), so matching them by text never found anything.
     */
    static Map<String, Object> contextMenuItem(Map<String, String> p) {
        String text = Params.req(p, "item");
        return FxThread.interact(() -> {
            List<String> available = new ArrayList<>();
            for (Window window : new ArrayList<>(Window.getWindows())) {
                if (!(window instanceof ContextMenu) || !window.isShowing()) {
                    continue;
                }
                ContextMenu menu = (ContextMenu) window;
                MenuItem item = findMenuItemDeep(menu.getItems(), text, available);
                if (item == null) {
                    continue;
                }
                if (item.isDisable()) {
                    throw new FxPilotException(409, Codes.NODE_DISABLED, "Menu item '" + text + "' is disabled");
                }
                DenyList.checkMenuItem(item);
                if (item instanceof Menu) {
                    ((Menu) item).show();
                } else {
                    menu.hide();
                    item.fire();
                }
                return Params.map("status", "done", "item", text);
            }
            throw new FxPilotException(404, Codes.MENU_NOT_FOUND, "No open context menu has an item '" + text
                    + "'" + (available.isEmpty() ? " (no context menu is showing)" : ". Available: " + available));
        });
    }

    private static MenuItem findMenuItemDeep(List<MenuItem> items, String text, List<String> available) {
        for (MenuItem item : items) {
            if (item.getText() != null) {
                available.add(item.getText());
            }
            if (text.equals(item.getText())) {
                return item;
            }
            if (item instanceof Menu) {
                MenuItem hit = findMenuItemDeep(((Menu) item).getItems(), text, available);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    static void tabAction(Node node, Map<String, String> p) {
        if (!(node instanceof TabPane)) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not a TabPane");
        }
        TabPane tabs = (TabPane) node;
        String action = Params.opt(p, "action", "select");
        Tab tab = findTab(tabs, p.get("tab"), Params.intVal(p, "index", -1));
        if (tab == null) {
            throw new FxPilotException(404, Codes.TAB_NOT_FOUND, "Tab not found");
        }
        DenyList.checkTab(tab, tabs);
        switch (action) {
            case "select": tabs.getSelectionModel().select(tab); break;
            case "close": closeTab(tabs, tab); break;
            case "move": {
                int index = Params.intVal(p, "to", tabs.getTabs().size() - 1);
                tabs.getTabs().remove(tab);
                tabs.getTabs().add(Math.max(0, Math.min(index, tabs.getTabs().size())), tab);
                break;
            }
            default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown tab action '" + action + "'");
        }
    }

    /** Closes a tab the way the tab's close button does, so the app's rules and handlers apply. */
    private static void closeTab(TabPane tabs, Tab tab) {
        Policy.requireDangerous("tab action 'close'");
        if (!tab.isClosable() || tabs.getTabClosingPolicy() == TabPane.TabClosingPolicy.UNAVAILABLE) {
            throw new FxPilotException(409, Codes.UNSUPPORTED_ACTION, "Tab '" + tab.getText() + "' is not closable");
        }
        Event request = new Event(tab, tab, Tab.TAB_CLOSE_REQUEST_EVENT);
        Event.fireEvent(tab, request);
        if (request.isConsumed()) {
            throw new FxPilotException(409, Codes.UNSUPPORTED_ACTION, "Closing tab '" + tab.getText()
                    + "' was vetoed by the application (onCloseRequest)");
        }
        tabs.getTabs().remove(tab);
        if (tab.getOnClosed() != null) {
            Event.fireEvent(tab, new Event(Tab.CLOSED_EVENT));
        }
    }

    private static Tab findTab(TabPane tabs, String text, int index) {
        if (index >= 0 && index < tabs.getTabs().size()) {
            return tabs.getTabs().get(index);
        }
        for (Tab tab : tabs.getTabs()) {
            if (text != null && text.equals(tab.getText())) {
                return tab;
            }
        }
        return null;
    }

    static void accordionAction(Node node, Map<String, String> p) {
        if (!(node instanceof Accordion)) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not an Accordion");
        }
        Accordion accordion = (Accordion) node;
        String text = p.get("pane");
        int index = Params.intVal(p, "index", -1);
        TitledPane pane = index >= 0 && index < accordion.getPanes().size()
                ? accordion.getPanes().get(index) : null;
        if (pane == null) {
            for (TitledPane candidate : accordion.getPanes()) {
                if (text != null && text.equals(candidate.getText())) {
                    pane = candidate;
                    break;
                }
            }
        }
        if (pane == null) {
            throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Accordion pane not found");
        }
        accordion.setExpandedPane(pane);
    }

    static void paginationAction(Node node, Map<String, String> p) {
        if (!(node instanceof Pagination)) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not a Pagination");
        }
        Pagination pagination = (Pagination) node;
        if (pagination.getPageCount() <= 0) {
            throw new FxPilotException(409, Codes.INVALID_VALUE, "Pagination has no pages");
        }
        String action = Params.opt(p, "action", "set");
        switch (action) {
            case "set":
                pagination.setCurrentPageIndex(Math.max(0,
                        Math.min(pagination.getPageCount() - 1, Params.intVal(p, "page", 0))));
                break;
            case "next": pagination.setCurrentPageIndex(Math.min(pagination.getPageCount() - 1,
                    pagination.getCurrentPageIndex() + 1)); break;
            case "prev": pagination.setCurrentPageIndex(Math.max(0, pagination.getCurrentPageIndex() - 1)); break;
            default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown pagination action '" + action + "'");
        }
    }
}
