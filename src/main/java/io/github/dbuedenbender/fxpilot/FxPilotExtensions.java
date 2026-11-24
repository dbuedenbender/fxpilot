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

import io.github.dbuedenbender.fxpilot.FxPilotEngine.FxPilotException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.chart.Chart;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Accordion;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.Pagination;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Extended operations for arbitrary JavaFX controls. These are kept separate from the core engine
 * so the small, stable endpoint API stays readable while the automation surface can grow.
 */
final class FxPilotExtensions {

    private FxPilotExtensions() {
    }

    static Map<String, Object> exec(String op, Map<String, String> p) {
        if (op == null || op.isBlank()) {
            throw new FxPilotException(400, Codes.INVALID_OPERATION, "op is required");
        }
        switch (op) {
            case "pressKey": return pressKey(p);
            case "hotkey": return hotkey(p);
            case "clipboardGet": return clipboardGet();
            case "clipboardSet": return clipboardSet(p);
            case "click": return click(p);
            case "wheel": return wheel(p);
            case "scrollTo": return action(p, n -> scrollTo(n, p));
            case "drag": return drag(p);
            case "focus": return action(p, Node::requestFocus);
            case "popup": return action(p, n -> popup(n, p));
            case "step": return action(p, n -> step(n, p));
            case "textAction": return action(p, n -> textAction(n, p.get("action"), p.get("value"),
                    intVal(p, "start", 0), intVal(p, "end", -1)));
            case "selectRows": return action(p, n -> selectRows(n, p.get("indices")));
            case "selectAll": return action(p, FxPilotExtensions::selectAll);
            case "clearSelection": return action(p, FxPilotExtensions::clearSelection);
            case "tableCell": return tableCell(p);
            case "tableEdit": return action(p, n -> tableEdit(n, p));
            case "tableCellSet": return action(p, n -> tableCellSet(n, p));
            case "tableSort": return action(p, n -> tableSort(n, p));
            case "tableColumn": return action(p, n -> tableColumn(n, p));
            case "tableSelect": return action(p, n -> tableSelect(n, p));
            case "tableScroll": return action(p, n -> tableScroll(n, p));
            case "tree": return action(p, n -> treeAction(n, p));
            case "menu": return menuAction(p);
            case "contextMenu": return contextMenu(p);
            case "contextMenuItem": return contextMenuItem(p);
            case "tab": return action(p, n -> tabAction(n, p));
            case "accordion": return action(p, n -> accordionAction(n, p));
            case "pagination": return action(p, n -> paginationAction(n, p));
            case "window": return windowAction(p);
            case "propertyGet": return propertyGet(p);
            case "propertySet": return propertySet(p);
            case "webExecute": return webExecute(p);
            case "webQuery": return webQuery(p);
            case "webClick": return action(p, n -> webClick(n, req(p, "css")));
            case "webHtml": return webHtml(p);
            case "chart": return chartData(p);
            case "canvasPixel": return canvasPixel(p);
            case "canvasClick": return action(p, n -> canvasClick(n, p));
            case "media": return action(p, n -> mediaAction(n, p));
            case "image": return imageAction(p);
            case "tooltip": return tooltip(p);
            case "idle": return FxPilotEngine.idle();
            default:
                throw new FxPilotException(400, Codes.INVALID_OPERATION, "unknown operation '" + op + "'");
        }
    }

    // ---------------------------------------------------------------- request helpers

    private static String req(Map<String, String> p, String name) {
        String v = p.get(name);
        if (v == null || v.isBlank()) {
            throw new FxPilotException(400, Codes.BAD_REQUEST, "parameter '" + name + "' is required");
        }
        return v;
    }

    private static String opt(Map<String, String> p, String name, String def) {
        String v = p.get(name);
        return v == null || v.isBlank() ? def : v;
    }

    private static boolean bool(Map<String, String> p, String name) {
        return Boolean.parseBoolean(p.get(name));
    }

    private static int intVal(Map<String, String> p, String name, int def) {
        String v = p.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new FxPilotException(400, Codes.INVALID_NUMBER, "'" + name + "' must be an integer");
        }
    }

    private static double doubleVal(Map<String, String> p, String name, double def) {
        String v = p.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new FxPilotException(400, Codes.INVALID_NUMBER, "'" + name + "' must be a number");
        }
    }

    private static Map<String, Object> action(Map<String, String> p, Consumer<Node> action) {
        return FxPilotEngine.action(req(p, "selector"), p.get("within"), p.get("nth"),
                bool(p, "requireVisible"), bool(p, "requireEnabled"), action);
    }

    private static Node node(Map<String, String> p) {
        return FxPilotEngine.find(req(p, "selector"), p.get("within"), p.get("nth"));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ---------------------------------------------------------------- keyboard and clipboard

    static final class Modifiers {
        static final Modifiers NONE = new Modifiers(false, false, false, false);
        final boolean shift;
        final boolean control;
        final boolean alt;
        final boolean meta;

        Modifiers(boolean shift, boolean control, boolean alt, boolean meta) {
            this.shift = shift;
            this.control = control;
            this.alt = alt;
            this.meta = meta;
        }
    }

    static Modifiers parseModifiers(String raw) {
        if (raw == null || raw.isBlank()) {
            return Modifiers.NONE;
        }
        boolean shift = false;
        boolean control = false;
        boolean alt = false;
        boolean meta = false;
        for (String part : raw.split("[,+\\s]+")) {
            switch (part.toUpperCase(Locale.ROOT)) {
                case "SHIFT": shift = true; break;
                case "CTRL": case "CONTROL": control = true; break;
                case "ALT": alt = true; break;
                case "META": case "CMD": case "COMMAND": case "WIN": meta = true; break;
                default: break;
            }
        }
        return new Modifiers(shift, control, alt, meta);
    }

    private static Map<String, Object> pressKey(Map<String, String> p) {
        String key = req(p, "key");
        Modifiers modifiers = parseModifiers(p.get("modifiers"));
        return FxPilotEngine.query(() -> {
            Node target;
            if (p.get("selector") != null && !p.get("selector").isBlank()) {
                target = FxPilotEngine.find(p.get("selector"), p.get("within"), p.get("nth"));
            } else {
                Window win = FxPilotEngine.window(p.get("window"));
                target = win.getScene().getFocusOwner() == null ? win.getScene().getRoot() : win.getScene().getFocusOwner();
            }
            fireKey(target, key, modifiers);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "done");
            result.put("node", FxPilotEngine.info(target));
            return result;
        });
    }

    private static Map<String, Object> hotkey(Map<String, String> p) {
        String combo = req(p, "keys");
        String[] parts = combo.split("\\+");
        String key = parts[parts.length - 1].trim();
        StringBuilder modifiers = new StringBuilder();
        for (int i = 0; i < parts.length - 1; i++) {
            if (modifiers.length() > 0) {
                modifiers.append('+');
            }
            modifiers.append(parts[i].trim());
        }
        Map<String, String> copy = new LinkedHashMap<>(p);
        copy.put("key", key);
        copy.put("modifiers", modifiers.toString());
        return pressKey(copy);
    }

    private static void fireKey(Node target, String keyName, Modifiers mods) {
        KeyCode code;
        try {
            String upper = keyName.trim().toUpperCase(Locale.ROOT);
            code = upper.length() == 1 ? KeyCode.getKeyCode(upper) : KeyCode.valueOf(upper);
        } catch (IllegalArgumentException e) {
            code = KeyCode.getKeyCode(keyName.trim().toUpperCase(Locale.ROOT));
        }
        if (code == null) {
            throw new FxPilotException(400, Codes.INVALID_KEY, "Unknown key '" + keyName
                    + "', use javafx KeyCode names like ENTER, TAB, ESCAPE, DOWN");
        }
        Event.fireEvent(target, keyEvent(KeyEvent.KEY_PRESSED, code, "", mods));
        if (code.isLetterKey() || code.isDigitKey()) {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_TYPED, keyName, "", KeyCode.UNDEFINED,
                    mods.shift, mods.control, mods.alt, mods.meta));
        }
        Event.fireEvent(target, keyEvent(KeyEvent.KEY_RELEASED, code, "", mods));
    }

    private static KeyEvent keyEvent(EventType<KeyEvent> type, KeyCode code, String text, Modifiers mods) {
        return new KeyEvent(type, KeyEvent.CHAR_UNDEFINED, text, code,
                mods.shift, mods.control, mods.alt, mods.meta);
    }

    private static Map<String, Object> clipboardGet() {
        return FxPilotEngine.query(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", Clipboard.getSystemClipboard().getString());
            return m;
        });
    }

    private static Map<String, Object> clipboardSet(Map<String, String> p) {
        String text = p.get("text");
        return FxPilotEngine.query(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text == null ? "" : text);
            Clipboard.getSystemClipboard().setContent(content);
            return map("status", "done", "text", text);
        });
    }

    // ---------------------------------------------------------------- mouse and drag

    private static Map<String, Object> click(Map<String, String> p) {
        MouseButton button = mouseButton(opt(p, "button", "PRIMARY"));
        int clicks = Math.max(1, intVal(p, "clicks", 1));
        Modifiers mods = parseModifiers(p.get("modifiers"));
        boolean fire = !"mouse".equalsIgnoreCase(opt(p, "mode", "fire"));
        return action(p, n -> {
            if (fire && button == MouseButton.PRIMARY && clicks == 1 && n instanceof ButtonBase) {
                n.requestFocus();
                ((ButtonBase) n).fire();
                return;
            }
            clickNode(n, button, clicks, mods);
        });
    }

    private static void clickNode(Node node, MouseButton button, int clicks, Modifiers mods) {
        Bounds b = node.localToScene(node.getBoundsInLocal());
        Point2D screen = node.localToScreen(b.getWidth() / 2, b.getHeight() / 2);
        double x = b.getMinX() + b.getWidth() / 2;
        double y = b.getMinY() + b.getHeight() / 2;
        double sx = screen != null ? screen.getX() : x;
        double sy = screen != null ? screen.getY() : y;
        node.requestFocus();
        for (int i = 1; i <= clicks; i++) {
            Event.fireEvent(node, mouseEvent(MouseEvent.MOUSE_PRESSED, x, y, sx, sy, button, i, mods, true));
            Event.fireEvent(node, mouseEvent(MouseEvent.MOUSE_RELEASED, x, y, sx, sy, button, i, mods, false));
            Event.fireEvent(node, mouseEvent(MouseEvent.MOUSE_CLICKED, x, y, sx, sy, button, i, mods, false));
        }
    }

    private static MouseButton mouseButton(String value) {
        try {
            return MouseButton.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown mouse button '" + value + "'");
        }
    }

    private static MouseEvent mouseEvent(EventType<MouseEvent> type, double x, double y, double sx, double sy,
            MouseButton button, int clicks, Modifiers mods, boolean down) {
        boolean primary = down && button == MouseButton.PRIMARY;
        boolean middle = down && button == MouseButton.MIDDLE;
        boolean secondary = down && button == MouseButton.SECONDARY;
        return new MouseEvent(type, x, y, sx, sy, button, clicks,
                mods.shift, mods.control, mods.alt, mods.meta,
                primary, middle, secondary, true, button == MouseButton.SECONDARY, true, null);
    }

    private static Map<String, Object> wheel(Map<String, String> p) {
        double dx = doubleVal(p, "deltaX", 0);
        double dy = doubleVal(p, "deltaY", 0);
        Modifiers mods = parseModifiers(p.get("modifiers"));
        return action(p, n -> fireScroll(n, dx, dy, mods));
    }

    private static void fireScroll(Node node, double dx, double dy, Modifiers mods) {
        Bounds b = node.localToScene(node.getBoundsInLocal());
        Point2D screen = node.localToScreen(b.getWidth() / 2, b.getHeight() / 2);
        double x = b.getMinX() + b.getWidth() / 2;
        double y = b.getMinY() + b.getHeight() / 2;
        double sx = screen != null ? screen.getX() : x;
        double sy = screen != null ? screen.getY() : y;
        ScrollEvent event = new ScrollEvent(ScrollEvent.SCROLL, x, y, sx, sy,
                mods.shift, mods.control, mods.alt, mods.meta, true, false,
                dx, dy, dx, dy,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0,
                ScrollEvent.VerticalTextScrollUnits.NONE, 0, 0, null);
        Event.fireEvent(node, event);
    }

    private static void scrollTo(Node node, Map<String, String> p) {
        int index = intVal(p, "index", intVal(p, "row", -1));
        if (node instanceof ListView && index >= 0) {
            ((ListView<?>) node).scrollTo(index);
            return;
        }
        if (node instanceof TableView && index >= 0) {
            ((TableView<?>) node).scrollTo(index);
            return;
        }
        if (node instanceof TreeView && index >= 0) {
            ((TreeView<?>) node).scrollTo(index);
            return;
        }
        if (node instanceof TreeTableView && index >= 0) {
            ((TreeTableView<?>) node).scrollTo(index);
            return;
        }
        if (node instanceof ScrollBar) {
            ((ScrollBar) node).setValue(doubleVal(p, "value", 0));
            return;
        }
        if (node instanceof ScrollPane) {
            ScrollPane pane = (ScrollPane) node;
            String targetSelector = p.get("target");
            if (targetSelector != null && !targetSelector.isBlank()) {
                Node target = FxPilotEngine.find(targetSelector, p.get("targetWithin"), p.get("targetNth"));
                Bounds targetBounds = target.getBoundsInParent();
                Bounds contentBounds = pane.getContent() == null
                        ? pane.getBoundsInLocal() : pane.getContent().getBoundsInLocal();
                double viewportHeight = pane.getViewportBounds().getHeight();
                double viewportWidth = pane.getViewportBounds().getWidth();
                pane.setVvalue(clamp((targetBounds.getMinY()) / Math.max(1,
                        contentBounds.getHeight() - viewportHeight), 0, 1));
                pane.setHvalue(clamp((targetBounds.getMinX()) / Math.max(1,
                        contentBounds.getWidth() - viewportWidth), 0, 1));
            } else {
                if (p.get("vvalue") != null) {
                    pane.setVvalue(doubleVal(p, "vvalue", 0));
                }
                if (p.get("hvalue") != null) {
                    pane.setHvalue(doubleVal(p, "hvalue", 0));
                }
            }
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " cannot scrollTo");
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : value > max ? max : value;
    }

    private static Map<String, Object> drag(Map<String, String> p) {
        Node source = node(p);
        Node target = FxPilotEngine.find(req(p, "target"), p.get("targetWithin"), p.get("targetNth"));
        Modifiers mods = parseModifiers(p.get("modifiers"));
        return FxPilotEngine.query(() -> {
            fireDrag(source, target, mods, intVal(p, "steps", 10));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "done");
            m.put("source", FxPilotEngine.info(source));
            m.put("target", FxPilotEngine.info(target));
            return m;
        });
    }

    private static void fireDrag(Node source, Node target, Modifiers mods, int steps) {
        Bounds sb = source.localToScene(source.getBoundsInLocal());
        Bounds tb = target.localToScene(target.getBoundsInLocal());
        double sx = sb.getMinX() + sb.getWidth() / 2;
        double sy = sb.getMinY() + sb.getHeight() / 2;
        double tx = tb.getMinX() + tb.getWidth() / 2;
        double ty = tb.getMinY() + tb.getHeight() / 2;
        Event.fireEvent(source, mouseEvent(MouseEvent.MOUSE_PRESSED, sx, sy, sx, sy,
                MouseButton.PRIMARY, 1, mods, true));
        Event.fireEvent(source, mouseEvent(MouseEvent.DRAG_DETECTED, sx, sy, sx, sy,
                MouseButton.PRIMARY, 1, mods, true));
        int count = Math.max(2, steps);
        for (int i = 1; i <= count; i++) {
            Node current = i == count ? target : source;
            double x = sx + (tx - sx) * i / count;
            double y = sy + (ty - sy) * i / count;
            Event.fireEvent(current, mouseEvent(MouseEvent.MOUSE_DRAGGED, x, y, x, y,
                    MouseButton.PRIMARY, 1, mods, true));
        }
        Event.fireEvent(target, mouseEvent(MouseEvent.MOUSE_RELEASED, tx, ty, tx, ty,
                MouseButton.PRIMARY, 1, mods, false));
    }

    private static void popup(Node node, Map<String, String> p) {
        boolean show = !"close".equalsIgnoreCase(opt(p, "action", "open"));
        if (node instanceof ComboBoxBase) {
            if (show) {
                ((ComboBoxBase<?>) node).show();
            } else {
                ((ComboBoxBase<?>) node).hide();
            }
            return;
        }
        if (node instanceof ChoiceBox) {
            if (show) {
                ((ChoiceBox<?>) node).show();
            } else {
                ((ChoiceBox<?>) node).hide();
            }
            return;
        }
        if (node instanceof javafx.scene.control.MenuButton) {
            if (show) {
                ((javafx.scene.control.MenuButton) node).show();
            } else {
                ((javafx.scene.control.MenuButton) node).hide();
            }
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " has no popup");
    }

    private static void step(Node node, Map<String, String> p) {
        double amount = doubleVal(p, "amount", 1);
        if (node instanceof javafx.scene.control.Slider) {
            javafx.scene.control.Slider slider = (javafx.scene.control.Slider) node;
            slider.setValue(clamp(slider.getValue() + amount * slider.getBlockIncrement(), slider.getMin(), slider.getMax()));
            return;
        }
        if (node instanceof javafx.scene.control.Spinner) {
            javafx.scene.control.Spinner<?> spinner = (javafx.scene.control.Spinner<?>) node;
            if (amount < 0) {
                spinner.decrement((int) Math.max(1, Math.round(-amount)));
            } else {
                spinner.increment((int) Math.max(1, Math.round(amount)));
            }
            return;
        }
        if (node instanceof javafx.scene.control.DatePicker) {
            javafx.scene.control.DatePicker picker = (javafx.scene.control.DatePicker) node;
            picker.setValue((picker.getValue() == null ? LocalDate.now() : picker.getValue()).plusDays((long) amount));
            return;
        }
        if (node instanceof javafx.scene.control.ScrollBar) {
            javafx.scene.control.ScrollBar bar = (javafx.scene.control.ScrollBar) node;
            bar.setValue(clamp(bar.getValue() + amount, bar.getMin(), bar.getMax()));
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " cannot step");
    }

    // ---------------------------------------------------------------- text and selection

    private static void textAction(Node node, String action, String value, int start, int end) {
        TextInputControl text = textInput(node);
        String name = action == null || action.isBlank() ? "clear" : action;
        switch (name) {
            case "clear": text.clear(); break;
            case "append": text.appendText(value == null ? "" : value); break;
            case "selectAll": text.selectAll(); break;
            case "selectRange": text.selectRange(Math.max(0, start), Math.max(start, end < 0 ? text.getLength() : end)); break;
            case "caret": text.positionCaret(Math.max(0, start)); break;
            case "replace":
                int to = end < 0 ? text.getLength() : end;
                text.replaceText(Math.max(0, start), Math.max(start, to), value == null ? "" : value);
                break;
            case "copy": text.copy(); break;
            case "paste": text.paste(); break;
            case "cut": text.cut(); break;
            default:
                throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown text action '" + name + "'");
        }
        Event.fireEvent(text, new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, "", KeyCode.END,
                false, false, false, false));
    }

    private static TextInputControl textInput(Node node) {
        if (node instanceof TextInputControl) {
            return (TextInputControl) node;
        }
        if (node instanceof ComboBoxBase && ((ComboBoxBase<?>) node).isEditable() && node instanceof javafx.scene.control.ComboBox) {
            return ((javafx.scene.control.ComboBox<?>) node).getEditor();
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not a text input");
    }

    private static void selectRows(Node node, String indices) {
        if (indices == null || indices.isBlank()) {
            throw new FxPilotException(400, Codes.BAD_REQUEST, "indices is required");
        }
        MultipleSelectionModel<?> model = selectionModel(node);
        if (model == null) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " has no selection model");
        }
        model.setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        model.clearSelection();
        for (String part : indices.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                int index = Integer.parseInt(part.trim());
                if (index >= 0) {
                    model.select(index);
                }
            } catch (NumberFormatException e) {
                throw new FxPilotException(400, Codes.INVALID_NUMBER, "'" + part + "' is not a row index");
            }
        }
    }

    private static void selectAll(Node node) {
        MultipleSelectionModel<?> model = selectionModel(node);
        if (model == null) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " has no selection model");
        }
        model.selectAll();
    }

    private static void clearSelection(Node node) {
        MultipleSelectionModel<?> model = selectionModel(node);
        if (model == null) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " has no selection model");
        }
        model.clearSelection();
    }

    private static MultipleSelectionModel<?> selectionModel(Node node) {
        if (node instanceof ListView) {
            return ((ListView<?>) node).getSelectionModel();
        }
        if (node instanceof TableView) {
            return ((TableView<?>) node).getSelectionModel();
        }
        if (node instanceof TreeView) {
            return ((TreeView<?>) node).getSelectionModel();
        }
        if (node instanceof TreeTableView) {
            return ((TreeTableView<?>) node).getSelectionModel();
        }
        return null;
    }

    // ---------------------------------------------------------------- tables

    private static Map<String, Object> tableCell(Map<String, String> p) {
        return FxPilotEngine.query(() -> {
            Node node = node(p);
            int row = intVal(p, "row", 0);
            String column = p.get("column");
            if (node instanceof TableView) {
                TableView<?> table = (TableView<?>) node;
                TableColumn<?, ?> col = tableColumn(table, column);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("row", row);
                m.put("column", col.getText());
                m.put("value", col.getCellData(row));
                return m;
            }
            if (node instanceof TreeTableView) {
                TreeTableView<?> table = (TreeTableView<?>) node;
                int columnIndex = columnIndex(table.getColumns(), column);
                String[] cells = FxPilotEngine.treeTableRowText(table, row).split(" \\| ", -1);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("row", row);
                m.put("column", columnIndex);
                m.put("value", columnIndex >= 0 && columnIndex < cells.length ? cells[columnIndex] : null);
                return m;
            }
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not a TableView/TreeTableView");
        });
    }

    private static void tableEdit(Node node, Map<String, String> p) {
        int row = intVal(p, "row", 0);
        String column = p.get("column");
        if (node instanceof TableView) {
            TableView<?> table = (TableView<?>) node;
            TableColumn<?, ?> col = tableColumn(table, column);
            table.getSelectionModel().select(row);
            table.scrollTo(row);
            table.edit(row, (TableColumn) col);
            return;
        }
        if (node instanceof TreeTableView) {
            TreeTableView<?> table = (TreeTableView<?>) node;
            TreeTableColumn<?, ?> col = treeTableColumn(table, column);
            table.getSelectionModel().select(row);
            table.edit(row, (TreeTableColumn) col);
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not a TableView/TreeTableView");
    }

    private static void tableCellSet(Node node, Map<String, String> p) {
        tableEdit(node, p);
        String value = p.get("value");
        Node editor = node.lookup(".text-field");
        if (editor instanceof TextInputControl) {
            TextInputControl text = (TextInputControl) editor;
            text.setText(value == null ? "" : value);
            Event.fireEvent(text, new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, "", KeyCode.ENTER,
                    false, false, false, false));
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_ACTION,
                "No editable text field was found; call tableEdit and set the editor manually");
    }

    private static void tableSort(Node node, Map<String, String> p) {
        String column = req(p, "column");
        String order = opt(p, "order", "ASCENDING");
        if (node instanceof TableView) {
            TableView<?> table = (TableView<?>) node;
            TableColumn<?, ?> col = tableColumn(table, column);
            col.setSortType("DESCENDING".equalsIgnoreCase(order)
                    ? TableColumn.SortType.DESCENDING : TableColumn.SortType.ASCENDING);
            table.getSortOrder().setAll((TableColumn) col);
            table.sort();
            return;
        }
        if (node instanceof TreeTableView) {
            TreeTableView<?> table = (TreeTableView<?>) node;
            TreeTableColumn<?, ?> col = treeTableColumn(table, column);
            col.setSortType("DESCENDING".equalsIgnoreCase(order)
                    ? TreeTableColumn.SortType.DESCENDING : TreeTableColumn.SortType.ASCENDING);
            table.getSortOrder().setAll((TreeTableColumn) col);
            table.sort();
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not a TableView/TreeTableView");
    }

    private static void tableColumn(Node node, Map<String, String> p) {
        String column = req(p, "column");
        String action = opt(p, "action", "visible");
        if (node instanceof TableView) {
            TableView<?> table = (TableView<?>) node;
            TableColumn<?, ?> col = tableColumn(table, column);
            switch (action) {
                case "visible": col.setVisible(Boolean.parseBoolean(opt(p, "value", "true"))); break;
                case "width": col.setPrefWidth(doubleVal(p, "value", col.getWidth())); break;
                case "order": {
                    int index = intVal(p, "value", table.getColumns().indexOf(col));
                    table.getColumns().remove(col);
                    table.getColumns().add(Math.max(0, Math.min(index, table.getColumns().size())), (TableColumn) col);
                    break;
                }
                case "text": col.setText(opt(p, "value", col.getText())); break;
                default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown column action '" + action + "'");
            }
            return;
        }
        if (node instanceof TreeTableView) {
            TreeTableView<?> table = (TreeTableView<?>) node;
            TreeTableColumn<?, ?> col = treeTableColumn(table, column);
            switch (action) {
                case "visible": col.setVisible(Boolean.parseBoolean(opt(p, "value", "true"))); break;
                case "width": col.setPrefWidth(doubleVal(p, "value", col.getWidth())); break;
                case "order": {
                    int index = intVal(p, "value", table.getColumns().indexOf(col));
                    table.getColumns().remove(col);
                    table.getColumns().add(Math.max(0, Math.min(index, table.getColumns().size())), (TreeTableColumn) col);
                    break;
                }
                case "text": col.setText(opt(p, "value", col.getText())); break;
                default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown column action '" + action + "'");
            }
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not a TableView/TreeTableView");
    }

    private static void tableSelect(Node node, Map<String, String> p) {
        int row = intVal(p, "row", 0);
        MultipleSelectionModel<?> model = selectionModel(node);
        if (model == null) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " has no selection model");
        }
        model.clearAndSelect(row);
        if (node instanceof TableView) {
            ((TableView<?>) node).scrollTo(row);
        } else if (node instanceof TreeTableView) {
            ((TreeTableView<?>) node).scrollTo(row);
        }
    }

    private static void tableScroll(Node node, Map<String, String> p) {
        int row = intVal(p, "row", 0);
        if (node instanceof TableView) {
            ((TableView<?>) node).scrollTo(row);
            return;
        }
        if (node instanceof TreeTableView) {
            ((TreeTableView<?>) node).scrollTo(row);
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not a TableView/TreeTableView");
    }

    private static TableColumn<?, ?> tableColumn(TableView<?> table, String spec) {
        int index = columnIndex(table.getColumns(), spec);
        if (index < 0 || index >= table.getColumns().size()) {
            throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Column '" + spec + "' not found");
        }
        return table.getColumns().get(index);
    }

    private static TreeTableColumn<?, ?> treeTableColumn(TreeTableView<?> table, String spec) {
        int index = columnIndex(table.getColumns(), spec);
        if (index < 0 || index >= table.getColumns().size()) {
            throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Column '" + spec + "' not found");
        }
        return table.getColumns().get(index);
    }

    private static int columnIndex(List<? extends TableColumnBase<?, ?>> columns, String spec) {
        if (spec == null || spec.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(spec.trim());
        } catch (NumberFormatException ignored) {
            // fall through to text matching
        }
        for (int i = 0; i < columns.size(); i++) {
            if (spec.equals(columns.get(i).getText())) {
                return i;
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getText() != null && columns.get(i).getText().contains(spec)) {
                return i;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------- trees

    private static void treeAction(Node node, Map<String, String> p) {
        String action = opt(p, "action", "expandAll");
        if (node instanceof TreeView) {
            TreeView<?> tree = (TreeView<?>) node;
            switch (action) {
                case "expandAll": expandTree(tree.getRoot(), true); break;
                case "collapseAll": expandTree(tree.getRoot(), false); break;
                case "expand":
                case "collapse":
                    FxPilotEngine.setExpanded(node, p.get("value"), p.get("index"), "expand".equals(action));
                    break;
                default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown tree action '" + action + "'");
            }
            return;
        }
        if (node instanceof TreeTableView) {
            TreeTableView<?> table = (TreeTableView<?>) node;
            switch (action) {
                case "expandAll": expandTree(table.getRoot(), true); break;
                case "collapseAll": expandTree(table.getRoot(), false); break;
                case "expand":
                case "collapse":
                    FxPilotEngine.setExpanded(node, p.get("value"), p.get("index"), "expand".equals(action));
                    break;
                default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown tree action '" + action + "'");
            }
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not a TreeView/TreeTableView");
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

    // ---------------------------------------------------------------- menus

    private static Map<String, Object> menuAction(Map<String, String> p) {
        String selector = p.get("selector");
        if (selector == null || selector.isBlank()) {
            return FxPilotEngine.query(() -> {
                Node bar = FxPilotEngine.findFirst(MenuBar.class);
                menuPath(bar, req(p, "path"));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", "done");
                m.put("node", FxPilotEngine.info(bar));
                return m;
            });
        }
        return action(p, n -> menuPath(n, req(p, "path")));
    }

    private static void menuPath(Node node, String path) {
        if (!(node instanceof MenuBar)) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not a MenuBar");
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

    private static Map<String, Object> contextMenu(Map<String, String> p) {
        Node node = node(p);
        return FxPilotEngine.query(() -> {
            Bounds b = node.localToScene(node.getBoundsInLocal());
            Point2D screen = node.localToScreen(b.getWidth() / 2, b.getHeight() / 2);
            double x = b.getMinX() + b.getWidth() / 2;
            double y = b.getMinY() + b.getHeight() / 2;
            double sx = screen != null ? screen.getX() : x;
            double sy = screen != null ? screen.getY() : y;
            Event.fireEvent(node, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    x, y, sx, sy, false, null));
            return map("status", "done", "hint", "Context menu requested. Use contextMenuItem to fire an item.");
        });
    }

    private static Map<String, Object> contextMenuItem(Map<String, String> p) {
        String text = req(p, "item");
        return FxPilotEngine.query(() -> {
            for (Window window : FxPilotEngine.windows()) {
                if (window.getScene() == null || window.getScene().getRoot() == null) {
                    continue;
                }
                for (Node candidate : window.getScene().getRoot().lookupAll(".menu-item")) {
                    if (!(candidate instanceof Labeled) || !text.equals(((Labeled) candidate).getText())) {
                        continue;
                    }
                    Object item = invokeOptional(candidate, "getItem");
                    if (item instanceof MenuItem) {
                        ((MenuItem) item).fire();
                        return map("status", "done", "item", text);
                    }
                    Event.fireEvent(candidate, mouseEvent(MouseEvent.MOUSE_CLICKED,
                            candidate.getLayoutX(), candidate.getLayoutY(),
                            candidate.getLayoutX(), candidate.getLayoutY(),
                            MouseButton.PRIMARY, 1, Modifiers.NONE, false));
                    return map("status", "done", "item", text);
                }
            }
            throw new FxPilotException(404, Codes.MENU_NOT_FOUND, "Open menu item '" + text + "' not found");
        });
    }

    // ---------------------------------------------------------------- tabs, accordion, pagination

    private static void tabAction(Node node, Map<String, String> p) {
        if (!(node instanceof TabPane)) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not a TabPane");
        }
        TabPane tabs = (TabPane) node;
        String action = opt(p, "action", "select");
        Tab tab = findTab(tabs, p.get("tab"), intVal(p, "index", -1));
        if (tab == null && !"close".equals(action)) {
            throw new FxPilotException(404, Codes.TAB_NOT_FOUND, "Tab not found");
        }
        switch (action) {
            case "select": tabs.getSelectionModel().select(tab); break;
            case "close": tabs.getTabs().remove(tab); break;
            case "move": {
                int index = intVal(p, "to", tabs.getTabs().size() - 1);
                tabs.getTabs().remove(tab);
                tabs.getTabs().add(Math.max(0, Math.min(index, tabs.getTabs().size())), tab);
                break;
            }
            default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown tab action '" + action + "'");
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

    private static void accordionAction(Node node, Map<String, String> p) {
        if (!(node instanceof Accordion)) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not an Accordion");
        }
        Accordion accordion = (Accordion) node;
        String text = p.get("pane");
        int index = intVal(p, "index", -1);
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

    private static void paginationAction(Node node, Map<String, String> p) {
        if (!(node instanceof Pagination)) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, FxPilotEngine.typeName(node) + " is not a Pagination");
        }
        Pagination pagination = (Pagination) node;
        String action = opt(p, "action", "set");
        switch (action) {
            case "set": pagination.setCurrentPageIndex(intVal(p, "page", 0)); break;
            case "next": pagination.setCurrentPageIndex(Math.min(pagination.getPageCount() - 1,
                    pagination.getCurrentPageIndex() + 1)); break;
            case "prev": pagination.setCurrentPageIndex(Math.max(0, pagination.getCurrentPageIndex() - 1)); break;
            default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown pagination action '" + action + "'");
        }
    }

    // ---------------------------------------------------------------- windows

    private static Map<String, Object> windowAction(Map<String, String> p) {
        return FxPilotEngine.query(() -> {
            Window window = FxPilotEngine.window(p.get("window"));
            String action = opt(p, "action", "focus");
            switch (action) {
                case "focus": window.requestFocus(); break;
                case "close":
                    if (window instanceof Stage) {
                        ((Stage) window).close();
                    } else {
                        window.hide();
                    }
                    break;
                case "accept": clickDialogButton(window, "OK", "YES", "APPLY", "SAVE", "CLOSE"); break;
                case "dismiss": clickDialogButton(window, "CANCEL", "NO", "ABORT", "CLOSE"); break;
                case "iconify": requireStage(window).setIconified(true); break;
                case "restore": requireStage(window).setIconified(false); break;
                case "maximize": requireStage(window).setMaximized(true); break;
                case "unmaximize": requireStage(window).setMaximized(false); break;
                case "move":
                    window.setX(doubleVal(p, "x", window.getX()));
                    window.setY(doubleVal(p, "y", window.getY()));
                    break;
                case "resize":
                    window.setWidth(doubleVal(p, "width", window.getWidth()));
                    window.setHeight(doubleVal(p, "height", window.getHeight()));
                    break;
                default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown window action '" + action + "'");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "done");
            m.put("window", FxPilotEngine.windowInfo(0, window));
            return m;
        });
    }

    private static void clickDialogButton(Window window, String... labels) {
        for (Node node : window.getScene().getRoot().lookupAll(".button")) {
            if (!(node instanceof ButtonBase) || !(node instanceof Labeled)) {
                continue;
            }
            String text = ((Labeled) node).getText();
            if (text == null) {
                continue;
            }
            for (String label : labels) {
                if (label.equalsIgnoreCase(text.trim())) {
                    ((ButtonBase) node).fire();
                    return;
                }
            }
        }
        throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "No dialog button found for "
                + String.join("/", labels));
    }

    private static Stage requireStage(Window window) {
        if (!(window instanceof Stage)) {
            throw new FxPilotException(400, Codes.WINDOW_ACTION_FAILED, "Window is not a Stage");
        }
        return (Stage) window;
    }

    // ---------------------------------------------------------------- generic properties

    private static Map<String, Object> propertyGet(Map<String, String> p) {
        return FxPilotEngine.query(() -> {
            Node node = node(p);
            String name = req(p, "name");
            Object value = readProperty(node, name);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("value", value instanceof Node ? ((Node) value).getId() : value);
            m.put("type", value == null ? null : value.getClass().getName());
            return m;
        });
    }

    private static Map<String, Object> propertySet(Map<String, String> p) {
        return FxPilotEngine.action(req(p, "selector"), p.get("within"), p.get("nth"),
                bool(p, "requireVisible"), bool(p, "requireEnabled"), node -> {
                    writeProperty(node, req(p, "name"), p.get("value"));
                });
    }

    private static Object readProperty(Node node, String name) {
        if (node.getProperties().containsKey(name)) {
            return node.getProperties().get(name);
        }
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : new String[]{"get", "is"}) {
            Object value = invokeOptional(node, prefix + suffix);
            if (value != NoValue.INSTANCE) {
                return value;
            }
        }
        Object property = invokeOptional(node, name + "Property");
        if (property instanceof javafx.beans.value.ObservableValue) {
            return ((javafx.beans.value.ObservableValue<?>) property).getValue();
        }
        throw new FxPilotException(404, Codes.PROPERTY_NOT_FOUND, "Property '" + name + "' not found on " + FxPilotEngine.typeName(node));
    }

    private static void writeProperty(Node node, String name, String value) {
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Method method : node.getClass().getMethods()) {
            if (method.getName().equals("set" + suffix) && method.getParameterCount() == 1) {
                try {
                    method.invoke(node, convert(value, method.getParameterTypes()[0]));
                    return;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new FxPilotException(400, Codes.INVALID_VALUE, "Cannot set property '" + name + "': " + e);
                }
            }
        }
        node.getProperties().put(name, value);
    }

    private static Object convert(String value, Class<?> type) {
        if (type == String.class) {
            return value;
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(value);
        }
        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(value);
        }
        if (type == long.class || type == Long.class) {
            return Long.parseLong(value);
        }
        if (type == double.class || type == Double.class) {
            return Double.parseDouble(value);
        }
        if (type == float.class || type == Float.class) {
            return Float.parseFloat(value);
        }
        if (type == LocalDate.class) {
            return LocalDate.parse(value);
        }
        if (type == Color.class) {
            return Color.web(value);
        }
        if (type.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) type, value);
        }
        return value;
    }

    private static Object invokeOptional(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return NoValue.INSTANCE;
        }
    }

    private static final class NoValue {
        static final NoValue INSTANCE = new NoValue();
    }

    // ---------------------------------------------------------------- WebView, charts, canvas, media

    private static Map<String, Object> webExecute(Map<String, String> p) {
        return FxPilotEngine.query(() -> {
            Node node = node(p);
            Object engine = webEngine(node);
            Object result = invoke(engine, "executeScript", req(p, "script"));
            return map("status", "done", "result", result == null ? null : String.valueOf(result));
        });
    }

    private static Map<String, Object> webQuery(Map<String, String> p) {
        return FxPilotEngine.query(() -> {
            Node node = node(p);
            Object engine = webEngine(node);
            String css = req(p, "css");
            String property = opt(p, "property", "outerHTML");
            String script = "(function(){var e=document.querySelector(" + jsString(css) + ");"
                    + "if(!e)return null;var v=e[" + jsString(property) + "];"
                    + "return v==null?null:String(v);})()";
            Object result = invoke(engine, "executeScript", script);
            return map("status", "done", "selector", css, "property", property,
                    "value", result == null ? null : String.valueOf(result));
        });
    }

    private static void webClick(Node node, String css) {
        Object engine = webEngine(node);
        invoke(engine, "executeScript",
                "(function(){var e=document.querySelector(" + jsString(css) + ");if(e)e.click();})()");
    }

    private static Map<String, Object> webHtml(Map<String, String> p) {
        return FxPilotEngine.query(() -> {
            Node node = node(p);
            Object engine = webEngine(node);
            Object result = invoke(engine, "executeScript", "document.documentElement.outerHTML");
            return map("html", result == null ? null : String.valueOf(result));
        });
    }

    private static Object webEngine(Node node) {
        if (!"javafx.scene.web.WebView".equals(node.getClass().getName())) {
            throw new FxPilotException(400, Codes.WEB_NOT_AVAILABLE, FxPilotEngine.typeName(node) + " is not a WebView");
        }
        return invoke(node, "getEngine");
    }

    private static Map<String, Object> chartData(Map<String, String> p) {
        return FxPilotEngine.query(() -> {
            Node node = node(p);
            Map<String, Object> m = new LinkedHashMap<>();
            if (node instanceof XYChart) {
                XYChart<?, ?> chart = (XYChart<?, ?>) node;
                m.put("title", chart.getTitle());
                List<Object> series = new ArrayList<>();
                for (XYChart.Series<?, ?> s : chart.getData()) {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("name", s.getName());
                    List<Object> points = new ArrayList<>();
                    for (XYChart.Data<?, ?> d : s.getData()) {
                        points.add(map("x", d.getXValue(), "y", d.getYValue(), "extra", d.getExtraValue()));
                    }
                    sm.put("data", points);
                    series.add(sm);
                }
                m.put("series", series);
                return m;
            }
            if (node instanceof PieChart) {
                PieChart chart = (PieChart) node;
                m.put("title", chart.getTitle());
                List<Object> data = new ArrayList<>();
                for (PieChart.Data d : chart.getData()) {
                    data.add(map("name", d.getName(), "value", d.getPieValue()));
                }
                m.put("data", data);
                return m;
            }
            throw new FxPilotException(400, Codes.CHART_NOT_FOUND, FxPilotEngine.typeName(node) + " is not a chart");
        });
    }

    private static Map<String, Object> canvasPixel(Map<String, String> p) {
        return FxPilotEngine.query(() -> {
            Node node = node(p);
            if (!(node instanceof Canvas)) {
                throw new FxPilotException(400, Codes.CANVAS_NOT_FOUND, FxPilotEngine.typeName(node) + " is not a Canvas");
            }
            int x = intVal(p, "x", 0);
            int y = intVal(p, "y", 0);
            WritableImage img = node.snapshot(new SnapshotParameters(), null);
            int argb = img.getPixelReader().getArgb(x, y);
            return map("x", x, "y", y, "argb", argb,
                    "color", String.format("#%08x", argb));
        });
    }

    private static void canvasClick(Node node, Map<String, String> p) {
        if (!(node instanceof Canvas)) {
            throw new FxPilotException(400, Codes.CANVAS_NOT_FOUND, FxPilotEngine.typeName(node) + " is not a Canvas");
        }
        double x = doubleVal(p, "x", 0);
        double y = doubleVal(p, "y", 0);
        Point2D scene = node.localToScene(x, y);
        Point2D screen = node.localToScreen(x, y);
        Event.fireEvent(node, mouseEvent(MouseEvent.MOUSE_PRESSED, scene.getX(), scene.getY(),
                screen == null ? scene.getX() : screen.getX(), screen == null ? scene.getY() : screen.getY(),
                MouseButton.PRIMARY, 1, Modifiers.NONE, true));
        Event.fireEvent(node, mouseEvent(MouseEvent.MOUSE_RELEASED, scene.getX(), scene.getY(),
                screen == null ? scene.getX() : screen.getX(), screen == null ? scene.getY() : screen.getY(),
                MouseButton.PRIMARY, 1, Modifiers.NONE, false));
        Event.fireEvent(node, mouseEvent(MouseEvent.MOUSE_CLICKED, scene.getX(), scene.getY(),
                screen == null ? scene.getX() : screen.getX(), screen == null ? scene.getY() : screen.getY(),
                MouseButton.PRIMARY, 1, Modifiers.NONE, false));
    }

    private static void mediaAction(Node node, Map<String, String> p) {
        Object player = invokeOptional(node, "getMediaPlayer");
        if (player == NoValue.INSTANCE) {
            throw new FxPilotException(400, Codes.MEDIA_NOT_AVAILABLE, FxPilotEngine.typeName(node) + " has no MediaPlayer");
        }
        String action = opt(p, "action", "status");
        switch (action) {
            case "play": invoke(player, "play"); break;
            case "pause": invoke(player, "pause"); break;
            case "stop": invoke(player, "stop"); break;
            case "seek": invoke(player, "seek", Duration.seconds(doubleVal(p, "value", 0))); break;
            case "volume": invoke(player, "setVolume", doubleVal(p, "value", 0.5)); break;
            case "mute": invoke(player, "setMute", Boolean.parseBoolean(opt(p, "value", "true"))); break;
            case "rate": invoke(player, "setRate", doubleVal(p, "value", 1)); break;
            case "status": break;
            default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown media action '" + action + "'");
        }
        // Result is available through the /node response; this action only mutates.
    }

    // ---------------------------------------------------------------- images and tooltips

    private static Map<String, Object> imageAction(Map<String, String> p) {
        return FxPilotEngine.query(() -> {
            Node node = node(p);
            if (!(node instanceof ImageView) || ((ImageView) node).getImage() == null) {
                throw new FxPilotException(400, Codes.IMAGE_NOT_FOUND, FxPilotEngine.typeName(node) + " has no image");
            }
            Image image = ((ImageView) node).getImage();
            Path path = Path.of(req(p, "file"));
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            ImageIO.write(toBufferedImage(image), "png", path.toFile());
            return map("status", "done", "file", path.toAbsolutePath().toString(),
                    "width", image.getWidth(), "height", image.getHeight());
        });
    }

    static BufferedImage toBufferedImage(Image image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] argb = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, argb, 0, width);
        return buffered;
    }

    static byte[] toPng(Image image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(toBufferedImage(image), "png", out);
        return out.toByteArray();
    }

    private static Map<String, Object> tooltip(Map<String, String> p) {
        return FxPilotEngine.query(() -> {
            Node node = node(p);
            String tooltip = node instanceof Control && ((Control) node).getTooltip() != null
                    ? ((Control) node).getTooltip().getText() : null;
            return map("tooltip", tooltip);
        });
    }

    // ---------------------------------------------------------------- reflection helpers

    private static Object invoke(Object target, String method, Object... args) {
        try {
            Method found = null;
            for (Method candidate : target.getClass().getMethods()) {
                if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                throw new FxPilotException(400, Codes.UNSUPPORTED_ACTION,
                        "Method '" + method + "' not found on " + target.getClass().getName());
            }
            return found.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (e instanceof FxPilotException) {
                throw (FxPilotException) e;
            }
            throw new FxPilotException(500, Codes.ACTION_FAILED, "Reflection call '" + method + "' failed: " + e);
        }
    }

    private static String jsString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
