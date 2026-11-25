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

import javafx.beans.value.ObservableValue;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Callback;

import java.util.*;

/**
 * Read-only snapshots of nodes and windows for the {@code /node}, {@code /tree} and {@code /view} endpoints.
 */
final class NodeInfo {

    private static final int MAX_ITEMS = 100;

    private NodeInfo() {
    }

    static Map<String, Object> windowInfo(int index, Window w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("index", index);
        Class<?> type = w.getClass();
        while (type.getSimpleName().isEmpty()) {
            type = type.getSuperclass();
        }
        m.put("type", type.getSimpleName());
        if (w instanceof Stage) {
            m.put("title", ((Stage) w).getTitle());
            m.put("modality", ((Stage) w).getModality().toString());
        }
        m.put("focused", w.isFocused());
        m.put("x", w.getX());
        m.put("y", w.getY());
        m.put("width", w.getWidth());
        m.put("height", w.getHeight());
        Node root = w.getScene().getRoot();
        m.put("root", root.getClass().getSimpleName() + (root.getId() != null ? "#" + root.getId() : ""));
        return m;
    }

    static List<Map<String, Object>> listWindows() {
        List<Map<String, Object>> list = new ArrayList<>();
        List<Window> all = Selectors.windows();
        for (int i = 0; i < all.size(); i++) {
            list.add(windowInfo(i, all.get(i)));
        }
        return list;
    }

    static Map<String, Object> tree(Node node, boolean full, int depth, int maxDepth) {
        Map<String, Object> m = info(node);
        if (depth >= maxDepth) {
            return m;
        }
        List<Node> children = Selectors.children(node, full);
        if (!children.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Node c : children) {
                list.add(tree(c, full, depth + 1, maxDepth));
            }
            m.put("children", list);
        }
        return m;
    }

    /** Compact flat list of visible, actionable nodes for AI agents; much smaller than the full tree. */
    static Map<String, Object> view(Window window, int maxDepth, int limit) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        collectView(window.getScene().getRoot(), 0, maxDepth, limit, nodes);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", nodes.size());
        m.put("truncated", nodes.size() >= limit);
        m.put("nodes", nodes);
        return m;
    }

    private static void collectView(Node node, int depth, int maxDepth, int limit, List<Map<String, Object>> out) {
        if (out.size() >= limit) {
            return;
        }
        boolean interesting = node instanceof Control || node instanceof Labeled
                || node.getId() != null || node.getAccessibleText() != null;
        if (interesting && Selectors.isBranchVisible(node)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("depth", depth);
            m.put("type", Selectors.typeName(node));
            m.put("ref", Selectors.nodePath(node));
            if (node.getId() != null) {
                m.put("id", node.getId());
            }
            if (node instanceof Labeled) {
                m.put("text", ((Labeled) node).getText());
            }
            if (node instanceof TextInputControl) {
                m.put("text", node instanceof PasswordField ? "***" : ((TextInputControl) node).getText());
                if (node instanceof PasswordField) {
                    m.put("redacted", true);
                }
            }
            if (node instanceof ComboBoxBase) {
                m.put("value", str(((ComboBoxBase<?>) node).getValue()));
            }
            if (node instanceof Toggle) {
                m.put("selected", ((Toggle) node).isSelected());
            }
            if (node instanceof ProgressIndicator) {
                m.put("progress", ((ProgressIndicator) node).getProgress());
            }
            if (node.getAccessibleText() != null && !node.getAccessibleText().isEmpty()) {
                m.put("accessibleText", node.getAccessibleText());
            }
            if (node instanceof Control && ((Control) node).getTooltip() != null) {
                m.put("tooltip", ((Control) node).getTooltip().getText());
            }
            m.put("disabled", node.isDisabled());
            m.put("focused", node.isFocused());
            out.add(m);
        }
        if (depth >= maxDepth) {
            return;
        }
        for (Node child : Selectors.children(node, false)) {
            collectView(child, depth + 1, maxDepth, limit, out);
        }
    }

    static Map<String, Object> info(Node node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", node.getClass().getSimpleName());
        m.put("ref", Selectors.nodePath(node));
        if (node.getId() != null) {
            m.put("id", node.getId());
        }
        if (!node.getStyleClass().isEmpty()) {
            m.put("styleClass", new ArrayList<>(node.getStyleClass()));
        }
        if (node.getAccessibleRole() != null) {
            m.put("accessibleRole", node.getAccessibleRole().name());
        }
        if (node.getAccessibleText() != null && !node.getAccessibleText().isEmpty()) {
            m.put("accessibleText", node.getAccessibleText());
        }
        if (node.getAccessibleHelp() != null && !node.getAccessibleHelp().isEmpty()) {
            m.put("accessibleHelp", node.getAccessibleHelp());
        }
        m.put("opacity", node.getOpacity());
        m.put("managed", node.isManaged());
        if (node.getStyle() != null && !node.getStyle().isEmpty()) {
            m.put("style", node.getStyle());
        }
        if (!node.getPseudoClassStates().isEmpty()) {
            List<String> pseudo = new ArrayList<>();
            for (javafx.css.PseudoClass pc : node.getPseudoClassStates()) {
                pseudo.add(pc.getPseudoClassName());
            }
            m.put("pseudoClass", pseudo);
        }
        if (node instanceof Control && ((Control) node).getTooltip() != null
                && ((Control) node).getTooltip().getText() != null) {
            m.put("tooltip", ((Control) node).getTooltip().getText());
        }
        if (node instanceof ProgressIndicator) {
            m.put("progress", ((ProgressIndicator) node).getProgress());
        }
        if (node instanceof Labeled) {
            m.put("text", ((Labeled) node).getText());
        }
        if (node instanceof TextInputControl) {
            TextInputControl t = (TextInputControl) node;
            if (node instanceof PasswordField) {
                m.put("text", "***");
                m.put("redacted", true);
            } else {
                m.put("text", t.getText());
            }
            if (t.getPromptText() != null && !t.getPromptText().isEmpty()) {
                m.put("promptText", t.getPromptText());
            }
            m.put("editable", t.isEditable());
        }
        if (node instanceof Toggle) {
            m.put("selected", ((Toggle) node).isSelected());
        }
        if (node instanceof CheckBox) {
            m.put("selected", ((CheckBox) node).isSelected());
        }
        if (node instanceof ComboBoxBase) {
            m.put("value", str(((ComboBoxBase<?>) node).getValue()));
            m.put("showing", ((ComboBoxBase<?>) node).isShowing());
        }
        if (node instanceof ComboBox) {
            m.put("items", items(((ComboBox<?>) node).getItems()));
        }
        if (node instanceof ChoiceBox) {
            m.put("value", str(((ChoiceBox<?>) node).getValue()));
            m.put("items", items(((ChoiceBox<?>) node).getItems()));
        }
        if (node instanceof ListView) {
            ListView<?> lv = (ListView<?>) node;
            m.put("items", items(lv.getItems()));
            m.put("selectedIndex", lv.getSelectionModel().getSelectedIndex());
            m.put("selectedIndices", new ArrayList<>(lv.getSelectionModel().getSelectedIndices()));
            m.put("selectedItems", items(lv.getSelectionModel().getSelectedItems()));
        }
        if (node instanceof TabPane) {
            List<Map<String, Object>> tabs = new ArrayList<>();
            for (Tab t : ((TabPane) node).getTabs()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("text", t.getText());
                if (t.getId() != null) {
                    tm.put("id", t.getId());
                }
                tm.put("selected", t.isSelected());
                tabs.add(tm);
            }
            m.put("tabs", tabs);
        }
        if ("javafx.scene.web.WebView".equals(node.getClass().getName())) {
            m.put("location", webViewLocation(node));
        }
        if ("javafx.scene.media.MediaView".equals(node.getClass().getName())) {
            mediaInfo(node, m);
        }
        if (node instanceof javafx.scene.canvas.Canvas) {
            m.put("canvasWidth", ((javafx.scene.canvas.Canvas) node).getWidth());
            m.put("canvasHeight", ((javafx.scene.canvas.Canvas) node).getHeight());
        }
        if (node instanceof ImageView && ((ImageView) node).getImage() != null) {
            m.put("imageWidth", ((ImageView) node).getImage().getWidth());
            m.put("imageHeight", ((ImageView) node).getImage().getHeight());
            if (((ImageView) node).getImage().getUrl() != null) {
                m.put("imageUrl", ((ImageView) node).getImage().getUrl());
            }
        }
        if (node instanceof Slider) {
            Slider s = (Slider) node;
            m.put("value", s.getValue());
            m.put("min", s.getMin());
            m.put("max", s.getMax());
        }
        if (node instanceof Spinner) {
            m.put("value", str(((Spinner<?>) node).getValue()));
        }
        if (node instanceof ScrollBar) {
            ScrollBar sb = (ScrollBar) node;
            m.put("value", sb.getValue());
            m.put("min", sb.getMin());
            m.put("max", sb.getMax());
        }
        if (node instanceof TableView) {
            tableInfo((TableView<?>) node, m);
        }
        if (node instanceof TreeView) {
            treeInfo((TreeView<?>) node, m);
        }
        if (node instanceof TreeTableView) {
            treeTableInfo((TreeTableView<?>) node, m);
        }
        if (node instanceof MenuBar) {
            List<Map<String, Object>> menus = new ArrayList<>();
            for (Menu menu : ((MenuBar) node).getMenus()) {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("text", menu.getText());
                List<String> entries = new ArrayList<>();
                for (MenuItem i : menu.getItems()) {
                    entries.add(i.getText());
                }
                mm.put("items", entries);
                menus.add(mm);
            }
            m.put("menus", menus);
        }
        m.put("visible", node.isVisible());
        m.put("visibleInScene", Selectors.isBranchVisible(node));
        m.put("disabled", node.isDisabled());
        m.put("focused", node.isFocused());
        m.put("layoutX", node.getLayoutX());
        m.put("layoutY", node.getLayoutY());
        m.put("translateX", node.getTranslateX());
        m.put("translateY", node.getTranslateY());
        m.put("scaleX", node.getScaleX());
        m.put("scaleY", node.getScaleY());
        m.put("rotate", node.getRotate());
        if (node.getScene() != null && node.getScene().getWindow() instanceof Stage) {
            m.put("windowTitle", ((Stage) node.getScene().getWindow()).getTitle());
        }
        Bounds b = node.localToScreen(node.getBoundsInLocal());
        if (b != null) {
            Map<String, Object> bounds = new LinkedHashMap<>();
            bounds.put("screenX", b.getMinX());
            bounds.put("screenY", b.getMinY());
            bounds.put("width", b.getWidth());
            bounds.put("height", b.getHeight());
            m.put("bounds", bounds);
            m.put("onScreen", !javafx.stage.Screen.getScreensForRectangle(
                    b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()).isEmpty());
        }
        return m;
    }

    /** Reflection keeps javafx-web optional for applications that do not use it. */
    private static String webViewLocation(Node webView) {
        try {
            Object engine = webView.getClass().getMethod("getEngine").invoke(webView);
            return (String) engine.getClass().getMethod("getLocation").invoke(engine);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** Reflection keeps javafx-media optional for applications that do not use it. */
    private static void mediaInfo(Node mediaView, Map<String, Object> m) {
        try {
            Object player = mediaView.getClass().getMethod("getMediaPlayer").invoke(mediaView);
            if (player == null) {
                return;
            }
            m.put("mediaStatus", str(player.getClass().getMethod("getStatus").invoke(player)));
            m.put("mediaTime", str(player.getClass().getMethod("getCurrentTime").invoke(player)));
            m.put("mediaVolume", player.getClass().getMethod("getVolume").invoke(player));
            m.put("mediaMute", player.getClass().getMethod("isMute").invoke(player));
            m.put("mediaRate", player.getClass().getMethod("getRate").invoke(player));
        } catch (ReflectiveOperationException ignored) {
            // Media support is optional and may not expose a player yet.
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static List<String> items(List<?> items) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < items.size() && i < MAX_ITEMS; i++) {
            result.add(str(items.get(i)));
        }
        if (items.size() > MAX_ITEMS) {
            result.add("... (" + (items.size() - MAX_ITEMS) + " more)");
        }
        return result;
    }

    private static void tableInfo(TableView<?> table, Map<String, Object> m) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (TableColumn<?, ?> c : table.getColumns()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("text", c.getText());
            if (c.getId() != null) {
                cm.put("id", c.getId());
            }
            cm.put("visible", c.isVisible());
            cm.put("width", c.getWidth());
            if (c.getSortType() != null) {
                cm.put("sortType", c.getSortType().name());
            }
            columns.add(cm);
        }
        m.put("columns", columns);
        List<String> sortOrder = new ArrayList<>();
        for (TableColumn<?, ?> c : table.getSortOrder()) {
            sortOrder.add(c.getText());
        }
        m.put("sortOrder", sortOrder);
        m.put("rowCount", table.getItems().size());
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < table.getItems().size() && i < MAX_ITEMS; i++) {
            rows.add(rowText(table, i));
        }
        if (table.getItems().size() > MAX_ITEMS) {
            rows.add("... (" + (table.getItems().size() - MAX_ITEMS) + " more)");
        }
        m.put("items", rows);
        int selected = table.getSelectionModel().getSelectedIndex();
        m.put("selectedIndex", selected);
        m.put("selectedIndices", new ArrayList<>(table.getSelectionModel().getSelectedIndices()));
        m.put("selectedItems", items(table.getSelectionModel().getSelectedItems()));
        if (selected >= 0 && selected < table.getItems().size()) {
            m.put("selectedRow", rowText(table, selected));
        }
    }

    /** Row as "cell | cell | cell", which is also what fx_select matches against. */
    static String rowText(TableView<?> table, int row) {
        StringBuilder sb = new StringBuilder();
        for (TableColumn<?, ?> c : table.getColumns()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(c.getCellData(row));
        }
        return sb.toString();
    }

    private static void treeInfo(TreeView<?> tree, Map<String, Object> m) {
        List<String> rows = new ArrayList<>();
        if (tree.getRoot() != null) {
            collectTreeRows(tree.getRoot(), rows, 0);
        }
        m.put("items", rows);
        int selectedIndex = tree.getSelectionModel().getSelectedIndex();
        m.put("selectedIndex", selectedIndex);
        m.put("selectedIndices", new ArrayList<>(tree.getSelectionModel().getSelectedIndices()));
        m.put("selectedItems", treeItems(tree.getSelectionModel().getSelectedItems()));
        TreeItem<?> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null) {
            m.put("selectedItem", treeItemText(selected));
        }
        List<String> expanded = new ArrayList<>();
        if (tree.getRoot() != null) {
            collectExpanded(tree.getRoot(), expanded);
        }
        m.put("expandedItems", expanded);
    }

    private static List<String> treeItems(List<? extends TreeItem<?>> items) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < items.size() && i < MAX_ITEMS; i++) {
            result.add(treeItemText(items.get(i)));
        }
        if (items.size() > MAX_ITEMS) {
            result.add("... (" + (items.size() - MAX_ITEMS) + " more)");
        }
        return result;
    }

    private static void collectExpanded(TreeItem<?> item, List<String> out) {
        if (item.isExpanded() && !item.getChildren().isEmpty()) {
            out.add(treeItemText(item));
        }
        for (TreeItem<?> child : item.getChildren()) {
            if (out.size() >= MAX_ITEMS) {
                out.add("... (more)");
                return;
            }
            collectExpanded(child, out);
        }
    }

    private static void collectTreeRows(TreeItem<?> item, List<String> out, int depth) {
        for (TreeItem<?> child : item.getChildren()) {
            if (out.size() >= MAX_ITEMS) {
                out.add("... (more)");
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth && i < 8; i++) {
                sb.append("  ");
            }
            sb.append(treeItemText(child));
            if (child.isExpanded() && !child.getChildren().isEmpty()) {
                sb.append(" [expanded]");
            }
            out.add(sb.toString());
            collectTreeRows(child, out, depth + 1);
        }
    }

    static String treeItemText(TreeItem<?> item) {
        return item.getValue() == null ? "null" : String.valueOf(item.getValue());
    }

    private static void treeTableInfo(TreeTableView<?> table, Map<String, Object> m) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (TreeTableColumn<?, ?> c : table.getColumns()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("text", c.getText());
            if (c.getId() != null) {
                cm.put("id", c.getId());
            }
            cm.put("visible", c.isVisible());
            cm.put("width", c.getWidth());
            if (c.getSortType() != null) {
                cm.put("sortType", c.getSortType().name());
            }
            columns.add(cm);
        }
        m.put("columns", columns);
        List<String> sortOrder = new ArrayList<>();
        for (TreeTableColumn<?, ?> c : table.getSortOrder()) {
            sortOrder.add(c.getText());
        }
        m.put("sortOrder", sortOrder);
        int rows = table.getExpandedItemCount();
        m.put("rowCount", rows);
        List<String> items = new ArrayList<>();
        for (int i = 0; i < rows && i < MAX_ITEMS; i++) {
            TreeItem<?> item = table.getTreeItem(i);
            if (item == null) {
                // a collapsed hidden root still counts as one expanded row
                continue;
            }
            StringBuilder sb = new StringBuilder();
            for (int d = 0; d < table.getTreeItemLevel(item) && d < 8; d++) {
                sb.append("  ");
            }
            sb.append(treeTableRowText(table, i));
            if (item.isExpanded() && !item.getChildren().isEmpty()) {
                sb.append(" [expanded]");
            }
            items.add(sb.toString());
        }
        if (rows > MAX_ITEMS) {
            items.add("... (" + (rows - MAX_ITEMS) + " more)");
        }
        m.put("items", items);
        int selected = table.getSelectionModel().getSelectedIndex();
        m.put("selectedIndex", selected);
        m.put("selectedIndices", new ArrayList<>(table.getSelectionModel().getSelectedIndices()));
        List<String> selectedRows = new ArrayList<>();
        for (int index : table.getSelectionModel().getSelectedIndices()) {
            if (index >= 0 && index < rows && selectedRows.size() < MAX_ITEMS) {
                selectedRows.add(treeTableRowText(table, index));
            }
        }
        m.put("selectedItems", selectedRows);
        if (selected >= 0 && selected < rows) {
            m.put("selectedRow", treeTableRowText(table, selected));
        }
        List<String> expanded = new ArrayList<>();
        if (table.getRoot() != null) {
            collectExpanded(table.getRoot(), expanded);
        }
        m.put("expandedItems", expanded);
    }

    /** Visible row as "cell | cell | cell", which is also what fx_select matches against. */
    static String treeTableRowText(TreeTableView<?> table, int row) {
        return treeTableItemText(table, table.getTreeItem(row));
    }

    static String treeTableItemText(TreeTableView<?> table, TreeItem<?> item) {
        StringBuilder sb = new StringBuilder();
        for (TreeTableColumn<?, ?> c : table.getColumns()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(treeTableCellValue(c, table, item));
        }
        return sb.toString();
    }

    /**
     * TreeTableColumn has no getCellData(int) (unlike TableColumn), so the cell value is produced by
     * calling the column's own cell value factory, which is exactly what the renderer does.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static Object treeTableCellValue(TreeTableColumn<?, ?> column, TreeTableView<?> table, TreeItem<?> item) {
        Callback factory = column.getCellValueFactory();
        if (factory == null || item == null) {
            return null;
        }
        Object observable = factory.call(new TreeTableColumn.CellDataFeatures(table, column, item));
        return observable instanceof ObservableValue ? ((ObservableValue) observable).getValue() : observable;
    }
}
