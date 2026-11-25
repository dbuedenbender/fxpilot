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

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.*;

/**
 * Window lookup and selector resolution (fx:id, CSS, {@code text=}, {@code role=}, {@code ref=} ...).
 */
final class Selectors {

    private Selectors() {
    }

    static List<Window> windows() {
        List<Window> result = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w.isShowing() && w.getScene() != null) {
                result.add(w);
            }
        }
        return result;
    }

    static Window window(String index) {
        List<Window> all = windows();
        if (all.isEmpty()) {
            throw new FxPilotException(404, Codes.WINDOW_NOT_FOUND, "No window is showing");
        }
        if (index == null || index.isBlank()) {
            for (Window w : all) {
                if (w.isFocused()) {
                    return w;
                }
            }
            return all.get(0);
        }
        String key = index.trim();
        if (key.regionMatches(true, 0, "title:", 0, 6)) {
            String needle = key.substring(6).trim().toLowerCase(java.util.Locale.ROOT);
            if (needle.isEmpty()) {
                throw new FxPilotException(400, Codes.BAD_REQUEST, "window=title: needs a text");
            }
            List<String> titles = new ArrayList<>();
            for (Window w : all) {
                String title = w instanceof Stage ? ((Stage) w).getTitle() : null;
                if (title != null) {
                    titles.add(title);
                    if (title.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                        return w;
                    }
                }
            }
            throw new FxPilotException(404, Codes.WINDOW_NOT_FOUND,
                    "No window title contains '" + key.substring(6).trim() + "'. Titles: " + titles);
        }
        int i;
        try {
            i = Integer.parseInt(key);
        } catch (NumberFormatException e) {
            throw new FxPilotException(400, Codes.BAD_REQUEST,
                    "window must be an index or title:<text>, see /windows");
        }
        if (i < 0 || i >= all.size()) {
            throw new FxPilotException(404, Codes.WINDOW_NOT_FOUND, "Window index " + i + " not found, " + all.size() + " window(s) showing");
        }
        return all.get(i);
    }

    static String normalize(String selector) {
        if (selector == null || selector.isBlank()) {
            throw new FxPilotException(400, Codes.INVALID_SELECTOR, "selector is required");
        }
        String s = selector.trim();
        if (s.startsWith("text=") || s.startsWith("text*=") || s.startsWith("id=")
                || s.startsWith("accessible=") || s.startsWith("accessible*=")
                || s.startsWith("label=") || s.startsWith("label*=")
                || s.startsWith("ref=") || s.startsWith("path=")) {
            return s;
        }
        if (s.matches("[A-Za-z_][A-Za-z0-9_\\-]*") && !Character.isUpperCase(s.charAt(0))) {
            return "#" + s;
        }
        return s;
    }

    /**
     * Resolves a selector across all showing windows. Supports CSS selectors (#id, .styleClass, Type)
     * as understood by {@link Node#lookup(String)}, a bare fx:id, {@code id=<exact fx:id>[@<n>]}
     * (literal id, so ids containing dots work), {@code text=<label>} (exact),
     * {@code text*=<part>} (substring) and {@code role=<name>} (button, textbox, checkbox, ...).
     * {@code text=} and {@code text*=<part>} prefer visible nodes when the same text exists more than once.
     */
    static Node find(String selector) {
        return find(selector, null, null);
    }

    static Node find(String selector, String within, String nth) {
        List<Node> all = findAll(selector, within);
        String index = nth == null || nth.isBlank() ? inlineIdIndex(selector) : nth;
        return pickMatch(selector, all, index);
    }

    /** Index from the older {@code id=<fx:id>@<n>} selector form, or {@code null}. */
    static String inlineIdIndex(String selector) {
        String sel = normalize(selector);
        return sel.startsWith("id=") ? parseIdSelector(sel.substring(3)).index : null;
    }

    /** All nodes matching the selector across showing windows; failures are reported by the caller. */
    static Map<String, Object> matches(String selector, String within, int limit) {
        List<Node> all = findAll(selector, within);
        int max = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < all.size() && i < max; i++) {
            nodes.add(NodeInfo.info(all.get(i)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", all.size());
        m.put("truncated", all.size() > nodes.size());
        m.put("matches", nodes);
        return m;
    }

    /**
     * All matches in scene order. For CSS selectors the node that {@link Node#lookup(String)} returns
     * comes first, so {@link #find} picks the same node as before. For {@code text=} and {@code text*=<part>}
     * visible nodes come first; hidden duplicates are only used when nothing is visible. {@code within}
     * limits the search to the subtree of its own first match.
     */
    static List<Node> findAll(String selector, String within) {
        List<Node> roots = new ArrayList<>();
        if (within != null && !within.isBlank()) {
            roots.add(find(within));
        } else {
            for (Window w : windows()) {
                roots.add(w.getScene().getRoot());
            }
        }
        String sel = normalize(selector);
        boolean byRole = sel.startsWith("role=");
        boolean byId = sel.startsWith("id=");
        String exactId = byId ? parseIdSelector(sel.substring(3)).id : null;
        boolean byAccessible = sel.startsWith("accessible=") || sel.startsWith("accessible*=");
        boolean accessibleContains = sel.startsWith("accessible*=");
        boolean byLabel = sel.startsWith("label=") || sel.startsWith("label*=");
        boolean labelContains = sel.startsWith("label*=");
        boolean byRef = sel.startsWith("ref=") || sel.startsWith("path=");
        boolean contains = sel.startsWith("text*=");
        boolean byText = contains || sel.startsWith("text=");
        int valueStart = sel.indexOf('=') + 1;
        List<Node> result = new ArrayList<>();
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<Node, Boolean>());
        for (Node root : roots) {
            List<Node> found = new ArrayList<>();
            if (byRole) {
                collectByRole(root, sel.substring(5).trim(), found);
            } else if (byId) {
                collectById(root, exactId, found);
            } else if (byAccessible) {
                collectByAccessible(root, sel.substring(valueStart), accessibleContains, found);
            } else if (byLabel) {
                collectByLabel(root, sel.substring(valueStart), labelContains, found);
            } else if (byRef) {
                Node hit = findByPath(root, sel.substring(valueStart));
                if (hit != null) {
                    found.add(hit);
                }
            } else if (byText) {
                collectByText(root, contains ? sel.substring(6) : sel.substring(5), contains, found);
            } else {
                if (sel.equals("#") || sel.equals(".") || sel.endsWith("#") || sel.endsWith(".")) {
                    throw new FxPilotException(400, Codes.INVALID_SELECTOR, "Selector '" + selector + "' is incomplete");
                }
                try {
                    Node first = root.lookup(sel);
                    if (first != null) {
                        found.add(first);
                    }
                    found.addAll(root.lookupAll(sel));
                } catch (RuntimeException e) {
                    // JavaFX's CSS parser throws on malformed selectors instead of reporting them
                    throw new FxPilotException(400, Codes.INVALID_SELECTOR, "Selector '" + selector + "' is not valid CSS: " + e);
                }
            }
            for (Node n : found) {
                if (seen.add(n)) {
                    result.add(n);
                }
            }
        }
        return byText || byAccessible || byLabel ? preferVisible(result) : result;
    }

    static List<Node> findAll(String selector) {
        return findAll(selector, null);
    }

    /**
     * Splits the payload of an {@code id=} selector into the literal fx:id and an optional
     * {@code @<n>} match index. A trailing {@code @} is only an index when digits follow it.
     */
    static IdSpec parseIdSelector(String spec) {
        String id = spec;
        String index = null;
        int at = spec.lastIndexOf('@');
        if (at > 0 && spec.substring(at + 1).matches("\\d+")) {
            id = spec.substring(0, at);
            index = spec.substring(at + 1);
        }
        return new IdSpec(id, index);
    }

    static final class IdSpec {
        final String id;
        final String index;

        IdSpec(String id, String index) {
            this.id = id;
            this.index = index;
        }
    }

    /** Picks the nth match (0-based, "last" allowed) and reports a helpful error when there is none. */
    static Node pickMatch(String selector, List<Node> all, String nth) {
        if (all.isEmpty()) {
            throw new FxPilotException(404, Codes.NODE_NOT_FOUND, "No node matches selector '" + selector + "'");
        }
        int index = 0;
        if (nth != null && !nth.isBlank()) {
            String n = nth.trim();
            if ("last".equalsIgnoreCase(n)) {
                index = all.size() - 1;
            } else {
                try {
                    index = Integer.parseInt(n);
                } catch (NumberFormatException e) {
                    throw new FxPilotException(400, Codes.INVALID_SELECTOR, "nth must be an integer or 'last'");
                }
                if (index < 0) {
                    throw new FxPilotException(400, Codes.INVALID_SELECTOR, "nth must be >= 0");
                }
            }
        }
        if (index >= all.size()) {
            throw new FxPilotException(404, Codes.NODE_NOT_FOUND, "nth=" + nth + " but selector '" + selector
                    + "' has only " + all.size() + " match(es): " + describe(all, 5));
        }
        return all.get(index);
    }

    private static void collectByRole(Node node, String role, List<Node> out) {
        if (matchesRole(node, role)) {
            out.add(node);
        }
        if (node instanceof Parent) {
            for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
                collectByRole(child, role, out);
            }
        }
    }

    private static boolean matchesRole(Node node, String role) {
        switch (role.toLowerCase(java.util.Locale.ROOT)) {
            case "button": return node instanceof ButtonBase
                    && !(node instanceof CheckBox)
                    && !(node instanceof javafx.scene.control.RadioButton)
                    && !(node instanceof javafx.scene.control.MenuButton);
            case "textbox": case "textfield": return node instanceof TextInputControl;
            case "checkbox": return node instanceof CheckBox;
            case "combobox": case "choicebox": return node instanceof ComboBoxBase || node instanceof ChoiceBox;
            case "list": return node instanceof ListView;
            case "table": return node instanceof TableView || node instanceof TreeTableView;
            case "tree": return node instanceof TreeView;
            case "tab": case "tabpane": return node instanceof TabPane;
            case "menu": case "menubar": return node instanceof MenuBar;
            case "slider": return node instanceof Slider;
            case "spinner": return node instanceof Spinner;
            case "datepicker": return node instanceof DatePicker;
            case "label": return node instanceof Labeled;
            case "image": return node instanceof ImageView;
            default:
                throw new FxPilotException(400, Codes.INVALID_SELECTOR, "Unknown role '" + role
                        + "'. Use button, textbox, checkbox, combobox, list, table, tree, tab, menu, slider, "
                        + "spinner, datepicker, label or image.");
        }
    }

    private static void collectByText(Node node, String text, boolean contains, List<Node> out) {
        String actual = null;
        if (node instanceof Labeled) {
            actual = ((Labeled) node).getText();
        } else if (node instanceof TextInputControl && !(node instanceof PasswordField)) {
            // a password must not be matchable, or text= becomes a guessing oracle
            actual = ((TextInputControl) node).getText();
        }
        if (actual != null && (contains ? actual.contains(text) : actual.equals(text))) {
            out.add(node);
        }
        if (node instanceof Parent) {
            for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
                collectByText(child, text, contains, out);
            }
        }
    }

    private static void collectById(Node node, String id, List<Node> out) {
        if (id.equals(node.getId())) {
            out.add(node);
        }
        if (node instanceof Parent) {
            for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
                collectById(child, id, out);
            }
        }
    }

    private static void collectByAccessible(Node node, String needle, boolean contains, List<Node> out) {
        String text = node.getAccessibleText();
        if (text == null || text.isEmpty()) {
            text = node.getAccessibleHelp();
        }
        String role = node.getAccessibleRole() == null ? null : node.getAccessibleRole().name();
        if ((text != null && (contains ? text.contains(needle) : text.equals(needle)))
                || (role != null && (contains ? role.contains(needle) : role.equalsIgnoreCase(needle)))) {
            out.add(node);
        }
        if (node instanceof Parent) {
            for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
                collectByAccessible(child, needle, contains, out);
            }
        }
    }

    private static void collectByLabel(Node node, String needle, boolean contains, List<Node> out) {
        if (node instanceof Label) {
            String text = ((Label) node).getText();
            Node target = ((Label) node).getLabelFor();
            if (target != null && text != null && (contains ? text.contains(needle) : text.equals(needle))) {
                out.add(target);
            }
        }
        if (node instanceof Parent) {
            for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
                collectByLabel(child, needle, contains, out);
            }
        }
    }

    /**
     * Path-based reference, e.g. {@code VBox[0]/Button[2]}. It is stable as long as the scene
     * structure does not change and is used by the {@code ref=} selector.
     */
    static String nodePath(Node node) {
        if (node == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (Node n = node; n != null; n = n.getParent()) {
            Parent parent = n.getParent();
            List<Node> siblings = parent == null ? List.of(n) : children(parent, false);
            int index = siblings.indexOf(n);
            if (index < 0 && parent != null) {
                siblings = parent.getChildrenUnmodifiable();
                index = siblings.indexOf(n);
            }
            if (index < 0) {
                index = 0;
            }
            parts.add(0, typeName(n) + "[" + index + "]");
        }
        return String.join("/", parts);
    }

    private static Node findByPath(Node root, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] parts = path.split("/");
        Node current = root;
        int first = parts.length > 0 && pathType(parts[0]).equals(typeName(root)) ? 1 : 0;
        for (int i = first; i < parts.length; i++) {
            String type = pathType(parts[i]);
            int index = pathIndex(parts[i]);
            List<Node> logical = children(current, false);
            List<Node> raw = current instanceof Parent ? ((Parent) current).getChildrenUnmodifiable() : List.of();
            // nodePath uses the logical index when the child is logical content of a control and the raw
            // scene-graph index otherwise (e.g. the skin node between a TabPane and its content), so try both
            Node next = at(logical, index, type);
            if (next == null) {
                next = at(raw, index, type);
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    private static Node at(List<Node> kids, int index, String type) {
        if (index < 0 || index >= kids.size()) {
            return null;
        }
        Node n = kids.get(index);
        return type.isEmpty() || type.equals(typeName(n)) ? n : null;
    }

    private static String pathType(String part) {
        int bracket = part.lastIndexOf('[');
        return bracket < 0 ? part : part.substring(0, bracket);
    }

    private static int pathIndex(String part) {
        int bracket = part.lastIndexOf('[');
        int end = part.endsWith("]") ? part.length() - 1 : part.length();
        if (bracket < 0 || bracket + 1 >= end) {
            return 0;
        }
        try {
            return Integer.parseInt(part.substring(bracket + 1, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static String typeName(Node node) {
        Class<?> type = node.getClass();
        while (type.getSimpleName().isEmpty() && type.getSuperclass() != null) {
            type = type.getSuperclass();
        }
        return type.getSimpleName();
    }

    /** Stable reordering: nodes whose whole parent chain is visible come first. */
    private static List<Node> preferVisible(List<Node> nodes) {
        if (nodes.size() < 2) {
            return nodes;
        }
        List<Node> visible = new ArrayList<>(nodes.size());
        List<Node> hidden = new ArrayList<>();
        for (Node node : nodes) {
            (isBranchVisible(node) ? visible : hidden).add(node);
        }
        visible.addAll(hidden);
        return visible;
    }

    static boolean isBranchVisible(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return true;
    }

    /** Compact description of matches for ambiguity messages: "Button#ok \"OK\", Button#cancel \"Cancel\"". */
    static String describe(List<Node> nodes, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nodes.size() && i < max; i++) {
            Node n = nodes.get(i);
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(n.getClass().getSimpleName());
            if (n.getId() != null) {
                sb.append('#').append(n.getId());
            }
            String text = n instanceof PasswordField ? null
                    : n instanceof Labeled ? ((Labeled) n).getText()
                    : n instanceof TextInputControl ? ((TextInputControl) n).getText() : null;
            if (text != null && !text.isEmpty()) {
                String shortText = text.length() > 30 ? text.substring(0, 30) + "..." : text;
                sb.append(" \"").append(shortText).append('"');
            }
        }
        if (nodes.size() > max) {
            sb.append(", ... (").append(nodes.size() - max).append(" more)");
        }
        return sb.toString();
    }

    /** First node of the given type in any showing window; fails with 404 if there is none. */
    static <T extends Node> T findFirst(Class<T> type) {
        for (Window w : windows()) {
            T hit = findFirst(w.getScene().getRoot(), type);
            if (hit != null) {
                return hit;
            }
        }
        throw new FxPilotException(404, Codes.NODE_NOT_FOUND, "No " + type.getSimpleName() + " found");
    }

    static <T extends Node> T findFirst(Node node, Class<T> type) {
        if (type.isInstance(node)) {
            return type.cast(node);
        }
        if (node instanceof Parent) {
            for (Node child : ((Parent) node).getChildrenUnmodifiable()) {
                T hit = findFirst(child, type);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    static List<Node> children(Node node, boolean full) {
        List<Node> result = new ArrayList<>();
        if (!(node instanceof Parent)) {
            return result;
        }
        if (full || !(node instanceof Control)) {
            result.addAll(((Parent) node).getChildrenUnmodifiable());
            return result;
        }
        if (node instanceof ScrollPane && ((ScrollPane) node).getContent() != null) {
            result.add(((ScrollPane) node).getContent());
        } else if (node instanceof TabPane) {
            for (Tab t : ((TabPane) node).getTabs()) {
                if (t.getContent() != null) {
                    result.add(t.getContent());
                }
            }
        } else if (node instanceof SplitPane) {
            result.addAll(((SplitPane) node).getItems());
        } else if (node instanceof TitledPane && ((TitledPane) node).getContent() != null) {
            result.add(((TitledPane) node).getContent());
        } else if (node instanceof Accordion) {
            result.addAll(((Accordion) node).getPanes());
        } else if (node instanceof ToolBar) {
            result.addAll(((ToolBar) node).getItems());
        }
        return result;
    }
}
