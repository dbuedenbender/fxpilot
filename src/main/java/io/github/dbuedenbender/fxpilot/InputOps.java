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
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.stage.Window;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Extended keyboard, clipboard, mouse, scroll and drag operations.
 */
final class InputOps {

    private InputOps() {
    }

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

    static Map<String, Object> pressKey(Map<String, String> p) {
        String key = Params.req(p, "key");
        Modifiers modifiers = parseModifiers(p.get("modifiers"));
        return FxThread.interact(() -> {
            Node target;
            if (p.get("selector") != null && !p.get("selector").isBlank()) {
                target = Selectors.find(p.get("selector"), p.get("within"), p.get("nth"));
            } else {
                Window win = Selectors.window(p.get("window"));
                target = win.getScene().getFocusOwner() == null ? win.getScene().getRoot() : win.getScene().getFocusOwner();
            }
            fireKey(target, key, modifiers);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "done");
            result.put("node", NodeInfo.info(target));
            return result;
        });
    }

    static Map<String, Object> hotkey(Map<String, String> p) {
        String combo = Params.req(p, "keys");
        String[] parts = combo.split("\\+");
        if (parts.length == 0 || parts[parts.length - 1].isBlank()) {
            throw new FxPilotException(400, Codes.INVALID_KEY, "keys '" + combo + "' names no key, use e.g. CTRL+S");
        }
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
        DenyList.checkKey(target, code, mods.shift, mods.control, mods.alt, mods.meta);
        Event.fireEvent(target, keyEvent(KeyEvent.KEY_PRESSED, code, "", mods));
        if ((code.isLetterKey() || code.isDigitKey()) && !mods.control && !mods.alt && !mods.meta) {
            Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_TYPED, keyName, "", KeyCode.UNDEFINED,
                    mods.shift, false, false, false));
        }
        Event.fireEvent(target, keyEvent(KeyEvent.KEY_RELEASED, code, "", mods));
    }

    private static KeyEvent keyEvent(EventType<KeyEvent> type, KeyCode code, String text, Modifiers mods) {
        return new KeyEvent(type, KeyEvent.CHAR_UNDEFINED, text, code,
                mods.shift, mods.control, mods.alt, mods.meta);
    }

    /** The system clipboard can hold data from other applications, so reading it counts as dangerous. */
    static Map<String, Object> clipboardGet() {
        Policy.requireDangerous("reading the system clipboard");
        return FxThread.query(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("text", Clipboard.getSystemClipboard().getString());
            return m;
        });
    }

    static Map<String, Object> clipboardSet(Map<String, String> p) {
        String text = p.get("text");
        return FxThread.query(() -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text == null ? "" : text);
            Clipboard.getSystemClipboard().setContent(content);
            return Params.map("status", "done", "text", text);
        });
    }

    static Map<String, Object> click(Map<String, String> p) {
        MouseButton button = mouseButton(Params.opt(p, "button", "PRIMARY"));
        int clicks = Params.bounded(p, "clicks", 1, 1, 10);
        Modifiers mods = parseModifiers(p.get("modifiers"));
        boolean fire = !"mouse".equalsIgnoreCase(Params.opt(p, "mode", "fire"));
        return Params.action(p, n -> {
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

    static MouseEvent mouseEvent(EventType<MouseEvent> type, double x, double y, double sx, double sy,
            MouseButton button, int clicks, Modifiers mods, boolean down) {
        boolean primary = down && button == MouseButton.PRIMARY;
        boolean middle = down && button == MouseButton.MIDDLE;
        boolean secondary = down && button == MouseButton.SECONDARY;
        return new MouseEvent(type, x, y, sx, sy, button, clicks,
                mods.shift, mods.control, mods.alt, mods.meta,
                primary, middle, secondary, true, button == MouseButton.SECONDARY, true, null);
    }

    static Map<String, Object> wheel(Map<String, String> p) {
        double dx = Params.doubleVal(p, "deltaX", 0);
        double dy = Params.doubleVal(p, "deltaY", 0);
        Modifiers mods = parseModifiers(p.get("modifiers"));
        return Params.action(p, n -> fireScroll(n, dx, dy, mods));
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

    static void scrollTo(Node node, Map<String, String> p) {
        int index = Params.intVal(p, "index", Params.intVal(p, "row", -1));
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
            ((ScrollBar) node).setValue(Params.doubleVal(p, "value", 0));
            return;
        }
        if (node instanceof ScrollPane) {
            ScrollPane pane = (ScrollPane) node;
            String targetSelector = p.get("target");
            if (targetSelector != null && !targetSelector.isBlank()) {
                Node target = Selectors.find(targetSelector, p.get("targetWithin"), p.get("targetNth"));
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
                    pane.setVvalue(Params.doubleVal(p, "vvalue", 0));
                }
                if (p.get("hvalue") != null) {
                    pane.setHvalue(Params.doubleVal(p, "hvalue", 0));
                }
            }
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " cannot scrollTo");
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : value > max ? max : value;
    }

    static Map<String, Object> drag(Map<String, String> p) {
        Modifiers mods = parseModifiers(p.get("modifiers"));
        return FxThread.interact(() -> {
            Node source = Params.node(p);
            Node target = Selectors.find(Params.req(p, "target"), p.get("targetWithin"), p.get("targetNth"));
            DenyList.checkDenied(p.get("selector"), source);
            DenyList.checkDenied(p.get("target"), target);
            fireDrag(source, target, mods, Params.bounded(p, "steps", 10, 1, 200));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "done");
            m.put("source", NodeInfo.info(source));
            m.put("target", NodeInfo.info(target));
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

    static void popup(Node node, Map<String, String> p) {
        boolean show = !"close".equalsIgnoreCase(Params.opt(p, "action", "open"));
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
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " has no popup");
    }

    static void step(Node node, Map<String, String> p) {
        double amount = Params.doubleVal(p, "amount", 1);
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
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " cannot step");
    }
}
