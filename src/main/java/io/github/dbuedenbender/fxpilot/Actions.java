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
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.*;

/**
 * Core input simulation behind the action endpoints. Runs on the FX thread.
 */
final class Actions {

    private Actions() {
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double x, double y,
            double sx, double sy, boolean down, int clicks) {
        return new MouseEvent(type, x, y, sx, sy, MouseButton.PRIMARY, clicks,
                false, false, false, false, down, false, false, true, false, true, null);
    }

    static void setText(Node node, String text) {
        TextInputControl t = textInput(node);
        t.requestFocus();
        t.setText(text);
        t.positionCaret(text.length());
        Event.fireEvent(t, key(KeyEvent.KEY_RELEASED, KeyCode.END, ""));
    }

    /** Like {@link Params#finite} but with the INVALID_VALUE code that set_value has always reported. */
    private static double finiteValue(String v) {
        double d;
        try {
            d = Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "'" + v + "' is not a valid number");
        }
        if (!Double.isFinite(d)) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "'" + v + "' is not a finite number");
        }
        return d;
    }

    /** Upper bound for fx_type; every character is three key events on the FX thread. */
    static final int MAX_TYPED = 10_000;

    static void type(Node node, String text) {
        if (text.length() > MAX_TYPED) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "text for type is longer than " + MAX_TYPED
                    + " characters; use setText");
        }
        TextInputControl t = textInput(node);
        t.requestFocus();
        t.positionCaret(t.getText().length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            KeyCode code = KeyCode.getKeyCode(ch.toUpperCase(java.util.Locale.ROOT));
            if (code == null) {
                code = KeyCode.UNDEFINED;
            }
            Event.fireEvent(t, key(KeyEvent.KEY_PRESSED, code, ""));
            Event.fireEvent(t, new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED,
                    false, false, false, false));
            Event.fireEvent(t, key(KeyEvent.KEY_RELEASED, code, ""));
        }
    }

    private static KeyEvent key(javafx.event.EventType<KeyEvent> type, KeyCode code, String text) {
        return new KeyEvent(type, KeyEvent.CHAR_UNDEFINED, text, code, false, false, false, false);
    }

    static TextInputControl textInput(Node node) {
        if (node instanceof TextInputControl) {
            return (TextInputControl) node;
        }
        if (node instanceof ComboBoxBase && ((ComboBoxBase<?>) node).isEditable() && node instanceof ComboBox) {
            return ((ComboBox<?>) node).getEditor();
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, node.getClass().getSimpleName() + " is not a text input");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static void select(Node node, String value, String index) {
        if (node instanceof ComboBox) {
            ComboBox combo = (ComboBox) node;
            Object item = pick(combo.getItems(), value, index);
            if (item == null && combo.isEditable() && value != null) {
                combo.setValue(value);
            } else if (item == null) {
                throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Item not found. Available: " + NodeInfo.items(combo.getItems()));
            } else {
                combo.getSelectionModel().select(item);
            }
        } else if (node instanceof ChoiceBox) {
            ChoiceBox choice = (ChoiceBox) node;
            Object item = pick(choice.getItems(), value, index);
            if (item == null) {
                throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Item not found. Available: " + NodeInfo.items(choice.getItems()));
            }
            choice.getSelectionModel().select(item);
        } else if (node instanceof ListView) {
            ListView list = (ListView) node;
            Object item = pick(list.getItems(), value, index);
            if (item == null) {
                throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Item not found. Available: " + NodeInfo.items(list.getItems()));
            }
            list.getSelectionModel().select(item);
            list.scrollTo(item);
        } else if (node instanceof TableView) {
            selectTableRow((TableView<?>) node, value, index);
        } else if (node instanceof TreeView) {
            selectTreeRow((TreeView<?>) node, value, index);
        } else if (node instanceof TreeTableView) {
            selectTreeTableRow((TreeTableView<?>) node, value, index);
        } else if (node instanceof TabPane) {
            TabPane tabs = (TabPane) node;
            for (int i = 0; i < tabs.getTabs().size(); i++) {
                Tab t = tabs.getTabs().get(i);
                if ((value != null && value.equals(t.getText())) || String.valueOf(i).equals(index)) {
                    DenyList.checkTab(t, tabs);
                    tabs.getSelectionModel().select(t);
                    return;
                }
            }
            throw new FxPilotException(404, Codes.TAB_NOT_FOUND, "Tab not found");
        } else {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, node.getClass().getSimpleName() + " does not support select");
        }
    }

    private static void selectTableRow(TableView<?> table, String value, String index) {
        int row = -1;
        if (index != null && !index.isBlank()) {
            row = Integer.parseInt(index.trim());
        } else if (value != null) {
            for (int i = 0; i < table.getItems().size(); i++) {
                if (NodeInfo.rowText(table, i).equals(value)) {
                    row = i;
                    break;
                }
            }
            for (int i = 0; row < 0 && i < table.getItems().size(); i++) {
                if (NodeInfo.rowText(table, i).contains(value)) {
                    row = i;
                }
            }
        }
        if (row < 0 || row >= table.getItems().size()) {
            throw new FxPilotException(404, Codes.ROW_NOT_FOUND, "Row not found (" + table.getItems().size()
                    + " rows). Pass index or the pipe-separated row text, see fx_get_node.");
        }
        table.getSelectionModel().select(row);
        table.scrollTo(row);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void selectTreeRow(TreeView<?> tree, String value, String index) {
        List<TreeItem<?>> all = new ArrayList<>();
        if (tree.getRoot() != null) {
            collectTreeItems(tree.getRoot(), all);
        }
        TreeItem<?> hit = null;
        if (index != null && !index.isBlank()) {
            int i = Integer.parseInt(index.trim());
            hit = i >= 0 && i < all.size() ? all.get(i) : null;
        } else {
            for (TreeItem<?> item : all) {
                if (NodeInfo.treeItemText(item).equals(value)) {
                    hit = item;
                    break;
                }
            }
        }
        if (hit == null) {
            throw new FxPilotException(404, Codes.TREE_ITEM_NOT_FOUND, "Tree item not found (" + all.size()
                    + " items). Pass index or the item text, see fx_get_node.");
        }
        expandParents(hit);
        ((TreeView) tree).getSelectionModel().select((TreeItem) hit);
    }

    private static void collectTreeItems(TreeItem<?> parent, List<TreeItem<?>> out) {
        for (TreeItem<?> child : parent.getChildren()) {
            out.add(child);
            collectTreeItems(child, out);
        }
    }

    /** Selects a visible row of a TreeTableView, expanding collapsed branches when matching by text. */
    private static void selectTreeTableRow(TreeTableView<?> table, String value, String index) {
        int row = -1;
        int rows = table.getExpandedItemCount();
        if (index != null && !index.isBlank()) {
            row = Integer.parseInt(index.trim());
        } else if (value != null) {
            for (int i = 0; i < rows && row < 0; i++) {
                if (NodeInfo.treeTableRowText(table, i).equals(value)) {
                    row = i;
                }
            }
            for (int i = 0; i < rows && row < 0; i++) {
                if (NodeInfo.treeTableRowText(table, i).contains(value)) {
                    row = i;
                }
            }
            if (row < 0) {
                TreeItem<?> item = findTreeTableItem(table, value);
                if (item != null) {
                    expandParents(item);
                    row = treeTableRow(table, item);
                }
            }
        }
        if (row < 0 || row >= table.getExpandedItemCount()) {
            throw new FxPilotException(404, Codes.ROW_NOT_FOUND, "Row not found (" + table.getExpandedItemCount()
                    + " visible rows). Pass index or row text, or expand the branch first, see fx_get_node.");
        }
        table.getSelectionModel().select(row);
        table.scrollTo(row);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int treeTableRow(TreeTableView<?> table, TreeItem<?> item) {
        return ((TreeTableView) table).getRow((TreeItem) item);
    }

    private static TreeItem<?> findTreeTableItem(TreeTableView<?> table, String value) {
        return table.getRoot() == null ? null : findTreeTableItem(table, table.getRoot(), value);
    }

    private static TreeItem<?> findTreeTableItem(TreeTableView<?> table, TreeItem<?> parent, String value) {
        for (TreeItem<?> child : parent.getChildren()) {
            if (NodeInfo.treeItemText(child).equals(value) || NodeInfo.treeTableItemText(table, child).equals(value)
                    || itemHasCellValue(table, child, value)) {
                return child;
            }
            TreeItem<?> hit = findTreeTableItem(table, child, value);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static boolean itemHasCellValue(TreeTableView<?> table, TreeItem<?> item, String value) {
        for (TreeTableColumn<?, ?> c : table.getColumns()) {
            Object cell = NodeInfo.treeTableCellValue(c, table, item);
            if (cell != null && String.valueOf(cell).equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static void expandParents(TreeItem<?> item) {
        for (TreeItem<?> p = item.getParent(); p != null; p = p.getParent()) {
            p.setExpanded(true);
        }
    }

    /** Expands or collapses an item of a TreeView/TreeTableView, addressed by value or visible row index. */
    static Map<String, Object> setExpanded(Node node, String value, String index, boolean expanded) {
        DenyList.checkDenied(node.getClass().getSimpleName(), node);
        TreeItem<?> item;
        if (node instanceof TreeView) {
            item = resolveTreeItem((TreeView<?>) node, value, index);
        } else if (node instanceof TreeTableView) {
            item = resolveTreeTableItem((TreeTableView<?>) node, value, index);
        } else {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, node.getClass().getSimpleName() + " is not a TreeView/TreeTableView");
        }
        if (item == null) {
            throw new FxPilotException(404, Codes.TREE_ITEM_NOT_FOUND, "Tree item not found. Pass value or a visible row index, see fx_get_node.");
        }
        if (item.getChildren().isEmpty()) {
            throw new FxPilotException(409, Codes.NO_CHILDREN, "Tree item '" + NodeInfo.treeItemText(item) + "' has no children");
        }
        expandParents(item);
        item.setExpanded(expanded);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "done");
        result.put("item", NodeInfo.treeItemText(item));
        result.put("expanded", item.isExpanded());
        result.put("visibleRows", node instanceof TreeView
                ? ((TreeView<?>) node).getExpandedItemCount()
                : ((TreeTableView<?>) node).getExpandedItemCount());
        return result;
    }

    private static TreeItem<?> resolveTreeItem(TreeView<?> tree, String value, String index) {
        if (index != null && !index.isBlank()) {
            int i = Integer.parseInt(index.trim());
            return i >= 0 && i < tree.getExpandedItemCount() ? tree.getTreeItem(i) : null;
        }
        if (value == null || tree.getRoot() == null) {
            return null;
        }
        return findTreeItem(tree.getRoot(), value);
    }

    private static TreeItem<?> findTreeItem(TreeItem<?> parent, String value) {
        for (TreeItem<?> child : parent.getChildren()) {
            if (NodeInfo.treeItemText(child).equals(value)) {
                return child;
            }
            TreeItem<?> hit = findTreeItem(child, value);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static TreeItem<?> resolveTreeTableItem(TreeTableView<?> table, String value, String index) {
        if (index != null && !index.isBlank()) {
            int i = Integer.parseInt(index.trim());
            return i >= 0 && i < table.getExpandedItemCount() ? table.getTreeItem(i) : null;
        }
        return value == null ? null : findTreeTableItem(table, value);
    }

    static Object pick(List<?> items, String value, String index) {
        if (index != null && !index.isBlank()) {
            int i = Integer.parseInt(index.trim());
            return i >= 0 && i < items.size() ? items.get(i) : null;
        }
        for (Object o : items) {
            if (o != null && o.toString().equals(value)) {
                return o;
            }
        }
        return null;
    }

    static void setChecked(Node node, boolean checked) {
        if (node instanceof CheckBox) {
            CheckBox cb = (CheckBox) node;
            if (cb.isSelected() != checked || cb.isIndeterminate()) {
                // set the state from which fire() moves to the wanted one, so the action handler runs once;
                // a tri-state box cycles unchecked -> indeterminate -> checked -> unchecked
                if (cb.isAllowIndeterminate()) {
                    cb.setSelected(!checked);
                    cb.setIndeterminate(checked);
                } else {
                    cb.setIndeterminate(false);
                    cb.setSelected(!checked);
                }
                cb.fire();
            }
        } else if (node instanceof Toggle && node instanceof ButtonBase) {
            if (((Toggle) node).isSelected() != checked) {
                ((ButtonBase) node).fire();
            }
        } else {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, node.getClass().getSimpleName() + " is not a CheckBox/Toggle");
        }
    }

    /** Moves the real OS mouse and clicks. Coordinates are relative to the window's scene. */
    static void robotClick(Window w, double sceneX, double sceneY) {
        Scene scene = w.getScene();
        double sx = w.getX() + scene.getX() + sceneX;
        double sy = w.getY() + scene.getY() + sceneY;
        Robot robot = new Robot();
        robot.mouseMove(sx, sy);
        robot.mouseClick(MouseButton.PRIMARY);
    }

    /** Real drag with the OS mouse, e.g. for drag and drop. Coordinates are relative to the scene. */
    static void robotDrag(Window w, double fromX, double fromY, double toX, double toY) {
        Scene scene = w.getScene();
        double offsetX = w.getX() + scene.getX();
        double offsetY = w.getY() + scene.getY();
        Robot robot = new Robot();
        robot.mouseMove(offsetX + fromX, offsetY + fromY);
        robot.mousePress(MouseButton.PRIMARY);
        int steps = 20;
        for (int i = 1; i <= steps; i++) {
            robot.mouseMove(offsetX + fromX + (toX - fromX) * i / steps,
                    offsetY + fromY + (toY - fromY) * i / steps);
        }
        robot.mouseRelease(MouseButton.PRIMARY);
    }

    /** Fires MOUSE_ENTERED/MOUSE_MOVED so hover handlers and CSS :hover styling run. */
    static void hover(Node node) {
        Bounds b = node.localToScene(node.getBoundsInLocal());
        Point2D screen = node.localToScreen(b.getWidth() / 2, b.getHeight() / 2);
        double x = b.getMinX() + b.getWidth() / 2;
        double y = b.getMinY() + b.getHeight() / 2;
        double sx = screen != null ? screen.getX() : x;
        double sy = screen != null ? screen.getY() : y;
        Event.fireEvent(node, mouse(MouseEvent.MOUSE_ENTERED, x, y, sx, sy, false, 1));
        Event.fireEvent(node, mouse(MouseEvent.MOUSE_MOVED, x, y, sx, sy, false, 1));
    }

    /**
     * Scrolls a control by {@code amount} steps (1 = a typical wheel notch, negative = up/left).
     * Uses the vertical scroll bar for virtualized controls and the vvalue of a ScrollPane.
     */
    static void scroll(Node node, double amount) {
        double steps = amount == 0 ? 1 : amount;
        if (node instanceof ScrollPane) {
            ScrollPane sp = (ScrollPane) node;
            sp.setVvalue(clamp(sp.getVvalue() + steps * 0.1, 0, 1));
            return;
        }
        for (Node n : node.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar && ((ScrollBar) n).getOrientation() == Orientation.VERTICAL) {
                ScrollBar bar = (ScrollBar) n;
                bar.setValue(clamp(bar.getValue() + steps * 0.1, bar.getMin(), bar.getMax()));
                return;
            }
        }
        throw new FxPilotException(400, Codes.NOT_SCROLLABLE, node.getClass().getSimpleName()
                + " has no vertical scroll bar (not skinned yet, or not scrollable)");
    }

    /** Sets the value of a Slider, Spinner, DatePicker or ColorPicker. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void setValue(Node node, String value) {
        if (value == null) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "value is required");
        }
        String v = value.trim();
        try {
            if (node instanceof Slider) {
                Slider slider = (Slider) node;
                slider.setValue(clamp(finiteValue(v), slider.getMin(), slider.getMax()));
            } else if (node instanceof Spinner) {
                Spinner spinner = (Spinner) node;
                if (spinner.getValueFactory() == null) {
                    throw new FxPilotException(400, Codes.INVALID_VALUE, "Spinner has no value factory");
                }
                SpinnerValueFactory factory = spinner.getValueFactory();
                Object current = factory.getValue();
                Object next;
                if (current instanceof Integer) {
                    next = Integer.valueOf(v);
                } else if (current instanceof Long) {
                    next = Long.valueOf(v);
                } else if (current instanceof Double) {
                    next = finiteValue(v);
                } else if (current instanceof LocalDate) {
                    next = LocalDate.parse(v);
                } else {
                    next = v;
                }
                factory.setValue(next);
            } else if (node instanceof DatePicker) {
                DatePicker picker = (DatePicker) node;
                picker.setValue(LocalDate.parse(v)); // fires onAction itself when the value changes
            } else if (node instanceof javafx.scene.control.ColorPicker) {
                javafx.scene.control.ColorPicker picker = (javafx.scene.control.ColorPicker) node;
                picker.setValue(Color.web(v)); // fires onAction itself when the value changes
            } else {
                throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, node.getClass().getSimpleName()
                        + " does not support set_value (Slider, Spinner, DatePicker, ColorPicker)");
            }
        } catch (NumberFormatException e) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "'" + value + "' is not a valid number");
        } catch (java.time.DateTimeException e) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "'" + value + "' is not a valid date (use ISO format yyyy-MM-dd)");
        } catch (IllegalArgumentException e) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "'" + value + "' is not a valid value: " + e.getMessage());
        }
    }

    /**
     * Opens a menu of a MenuBar and fires the matching item. Menu items are no nodes until their
     * popup is showing, so this works on the model instead of on the scene graph.
     */
    static Map<String, Object> clickMenu(String selector, String within, String nth, String menuText, String itemText) {
        if (menuText == null || menuText.isBlank()) {
            throw new FxPilotException(400, Codes.BAD_REQUEST, "menu is required (text of the top-level menu)");
        }
        Node target = selector == null || selector.isBlank() ? Selectors.findFirst(MenuBar.class) : Selectors.find(selector, within, nth);
        DenyList.checkDenied(selector == null ? "MenuBar" : selector, target);
        if (!(target instanceof MenuBar)) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, target.getClass().getSimpleName() + " is not a MenuBar");
        }
        MenuBar bar = (MenuBar) target;
        Menu menu = null;
        for (Menu m : bar.getMenus()) {
            if (menuText.equals(m.getText())) {
                menu = m;
                break;
            }
        }
        if (menu == null) {
            throw new FxPilotException(404, Codes.MENU_NOT_FOUND, "Menu '" + menuText + "' not found. Available: " + menuTexts(bar));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("menu", menu.getText());
        if (itemText == null || itemText.isBlank()) {
            menu.show();
            result.put("status", "done");
            result.put("hint", "Menu popup opened, no item fired. Pass item=<text> to fire an item.");
            return result;
        }
        MenuItem item = null;
        for (MenuItem i : menu.getItems()) {
            if (itemText.equals(i.getText())) {
                item = i;
                break;
            }
        }
        if (item == null) {
            throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Item '" + itemText + "' not found in menu '" + menuText
                    + "'. Available: " + menuItemTexts(menu));
        }
        if (item.isDisable()) {
            throw new FxPilotException(409, Codes.NODE_DISABLED, "Menu item '" + itemText + "' is disabled");
        }
        DenyList.checkMenuItem(item);
        if (item instanceof Menu) {
            ((Menu) item).show();
        } else {
            item.fire();
        }
        result.put("status", "done");
        result.put("item", item.getText());
        return result;
    }

    private static List<String> menuTexts(MenuBar bar) {
        List<String> texts = new ArrayList<>();
        for (Menu m : bar.getMenus()) {
            texts.add(m.getText());
        }
        return texts;
    }

    private static List<String> menuItemTexts(Menu menu) {
        List<String> texts = new ArrayList<>();
        for (MenuItem i : menu.getItems()) {
            texts.add(i.getText());
        }
        return texts;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : value > max ? max : value;
    }
}
