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
package io.github.buedi.fxpilot;

import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Callback;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Scene-graph inspection and input simulation. All scene access happens on the FX thread.
 */
final class FxPilotEngine {

    private static final long QUERY_TIMEOUT_MS = 10_000;
    private static final long ACTION_WAIT_MS = 2_000;
    private static final int MAX_ITEMS = 100;

    private static volatile List<String> denyPatterns = List.of();

    private FxPilotEngine() {
    }

    static final class FxPilotException extends RuntimeException {
        final int status;
        final String code;

        FxPilotException(int status, String message) {
            this(status, null, message);
        }

        FxPilotException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code == null ? Codes.forStatus(status) : code;
        }
    }

    // ---------------------------------------------------------------- threading

    static <T> T query(Callable<T> task) {
        if (Platform.isFxApplicationThread()) {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        runLater(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new FxPilotException(504, Codes.FX_THREAD_TIMEOUT, "FX thread did not respond within " + QUERY_TIMEOUT_MS + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FxPilotException(500, Codes.INTERRUPTED, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * Runs an action that may block the FX thread (e.g. an event handler opening a modal dialog).
     * Returns after the action finished or after {@link #ACTION_WAIT_MS}, whichever comes first.
     */
    static Map<String, Object> action(String selector, Consumer<Node> action) {
        return action(selector, null, null, action);
    }

    static Map<String, Object> action(String selector, String within, String nth, Consumer<Node> action) {
        return action(selector, within, nth, false, false, action);
    }

    static Map<String, Object> action(String selector, String within, String nth,
            boolean requireVisible, boolean requireEnabled, Consumer<Node> action) {
        List<Node> all = query(() -> findAll(selector, within));
        Node node = pickMatch(selector, all, nth);
        if (requireVisible && !isBranchVisible(node)) {
            throw new FxPilotException(409, Codes.NODE_HIDDEN, "Node '" + selector + "' is hidden");
        }
        if (requireEnabled && node.isDisabled()) {
            throw new FxPilotException(409, Codes.NODE_DISABLED, "Node '" + selector + "' is disabled");
        }
        boolean disambiguated = nth != null && !nth.isBlank();
        if (all.size() > 1 && !disambiguated && Boolean.parseBoolean(System.getProperty("fxpilot.strictSelectors",
                String.valueOf(System.getenv("FXPILOT_STRICT"))))) {
            throw new FxPilotException(409, Codes.NODE_AMBIGUOUS, "Selector '" + selector + "' matches "
                    + all.size() + " nodes: " + describe(all, 5)
                    + ". Use a more specific selector, nth=<index>, or call fx_find to list them.");
        }
        checkDenied(selector, node);
        CompletableFuture<Void> done = new CompletableFuture<>();
        runLater(() -> {
            try {
                action.accept(node);
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        if (!awaitAction(done)) {
            return pending();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "done");
        result.put("node", query(() -> info(node)));
        if (all.size() > 1 && !disambiguated) {
            result.put("matches", all.size());
            result.put("warning", "Selector matched " + all.size() + " nodes, used the first one. "
                    + "Add #id, a CSS class, text= or nth=<index> to be specific, or call fx_find.");
        }
        return result;
    }

    /**
     * Selector patterns from {@code fxpilot.deny}; a node (or one of its ancestors) matching any
     * pattern cannot be driven, which turns the advisory notes into a real lock.
     */
    static void setDenyPatterns(List<String> patterns) {
        denyPatterns = patterns == null ? List.of() : List.copyOf(patterns);
    }

    private static void checkDenied(String selector, Node node) {
        List<String> patterns = denyPatterns;
        if (patterns.isEmpty()) {
            return;
        }
        for (Node n = node; n != null; n = n.getParent()) {
            for (String pattern : patterns) {
                if (matchesDeny(n, pattern)) {
                    throw new FxPilotException(403, Codes.NODE_DENIED, "Node '" + selector + "' is denied by "
                            + "fxpilot.deny='" + pattern + "'");
                }
            }
        }
    }

    private static boolean matchesDeny(Node node, String pattern) {
        String p = pattern.trim();
        if (p.isEmpty()) {
            return false;
        }
        if (p.startsWith("#")) {
            return p.substring(1).equals(node.getId());
        }
        if (p.startsWith(".")) {
            return node.getStyleClass().contains(p.substring(1));
        }
        if (p.startsWith("text=")) {
            return node instanceof Labeled && p.substring(5).equals(((Labeled) node).getText());
        }
        return node.getClass().getSimpleName().equals(p);
    }

    /** Menu entries are model objects, so they bypass {@link #action} and are fired directly. */
    static Map<String, Object> actionMenu(String selector, String within, String nth, String menu, String item) {
        java.util.concurrent.atomic.AtomicReference<Map<String, Object>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        runLater(() -> {
            try {
                result.set(clickMenu(selector, within, nth, menu, item));
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        return awaitAction(done) ? result.get() : pending();
    }

    private static boolean awaitAction(CompletableFuture<?> done) {
        try {
            done.get(ACTION_WAIT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FxPilotException(500, Codes.INTERRUPTED, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FxPilotException) {
                throw (FxPilotException) cause;
            }
            throw new FxPilotException(500, Codes.ACTION_FAILED, "Action failed: " + cause);
        }
    }

    private static Map<String, Object> pending() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "pending");
        result.put("hint", "The FX thread is still busy, probably a modal dialog or a long-running handler. "
                + "Call list_windows or screenshot to see what is open.");
        return result;
    }

    /** Waits until the FX thread has processed the work that was already queued when the call arrived. */
    static Map<String, Object> idle() {
        if (!Platform.isFxApplicationThread()) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            runLater(() -> done.complete(null));
            try {
                done.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new FxPilotException(504, Codes.FX_THREAD_TIMEOUT,
                        "FX thread did not become idle within " + QUERY_TIMEOUT_MS + " ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FxPilotException(500, Codes.INTERRUPTED, "interrupted");
            } catch (ExecutionException e) {
                throw new FxPilotException(500, Codes.INTERNAL_ERROR, "idle wait failed: " + e.getCause());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "done");
        return result;
    }

    private static void runLater(Runnable r) {
        try {
            Platform.runLater(r);
        } catch (IllegalStateException e) {
            throw new FxPilotException(503, Codes.TOOLKIT_NOT_RUNNING, "JavaFX toolkit is not running (yet): " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- lookup

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
    private static String inlineIdIndex(String selector) {
        String sel = normalize(selector);
        return sel.startsWith("id=") ? parseIdSelector(sel.substring(3)).index : null;
    }

    /** All nodes matching the selector across showing windows; failures are reported by the caller. */
    static Map<String, Object> matches(String selector, String within, int limit) {
        List<Node> all = findAll(selector, within);
        int max = Math.max(1, Math.min(200, limit));
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < all.size() && i < max; i++) {
            nodes.add(info(all.get(i)));
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
                Node first = root.lookup(sel);
                if (first != null) {
                    found.add(first);
                }
                found.addAll(root.lookupAll(sel));
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
        } else if (node instanceof TextInputControl) {
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
            List<Node> kids = children(current, false);
            if (kids.isEmpty() && current instanceof Parent) {
                kids = ((Parent) current).getChildrenUnmodifiable();
            }
            if (index < 0 || index >= kids.size()) {
                return null;
            }
            Node next = kids.get(index);
            if (!type.isEmpty() && !type.equals(typeName(next))) {
                next = null;
                for (Node child : kids) {
                    if (type.equals(typeName(child))) {
                        next = child;
                        break;
                    }
                }
                if (next == null) {
                    return null;
                }
            }
            current = next;
        }
        return current;
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

    private static boolean isBranchVisible(Node node) {
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
            String text = n instanceof Labeled ? ((Labeled) n).getText()
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

    private static <T extends Node> T findFirst(Node node, Class<T> type) {
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

    // ---------------------------------------------------------------- inspection

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
        List<Window> all = windows();
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
        List<Node> children = children(node, full);
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
        if (interesting && isBranchVisible(node)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("depth", depth);
            m.put("type", typeName(node));
            m.put("ref", nodePath(node));
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
        for (Node child : children(node, false)) {
            collectView(child, depth + 1, maxDepth, limit, out);
        }
    }

    private static List<Node> children(Node node, boolean full) {
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

    static Map<String, Object> info(Node node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", node.getClass().getSimpleName());
        m.put("ref", nodePath(node));
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
        m.put("visibleInScene", isBranchVisible(node));
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

    private static List<String> items(List<?> items) {
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

    private static String treeTableItemText(TreeTableView<?> table, TreeItem<?> item) {
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
    private static Object treeTableCellValue(TreeTableColumn<?, ?> column, TreeTableView<?> table, TreeItem<?> item) {
        Callback factory = column.getCellValueFactory();
        if (factory == null || item == null) {
            return null;
        }
        Object observable = factory.call(new TreeTableColumn.CellDataFeatures(table, column, item));
        return observable instanceof ObservableValue ? ((ObservableValue) observable).getValue() : observable;
    }

    // ---------------------------------------------------------------- actions (FX thread)

    static void click(Node node) {
        click(node, 1);
    }

    /** clicks=2 sends a double click, which starts inline editing in TableView/TreeView cells. */
    static void click(Node node, int clicks) {
        if (clicks <= 1 && node instanceof ButtonBase) {
            node.requestFocus();
            ((ButtonBase) node).fire();
            return;
        }
        Bounds b = node.localToScene(node.getBoundsInLocal());
        Point2D screen = node.localToScreen(b.getWidth() / 2, b.getHeight() / 2);
        double x = b.getMinX() + b.getWidth() / 2;
        double y = b.getMinY() + b.getHeight() / 2;
        double sx = screen != null ? screen.getX() : x;
        double sy = screen != null ? screen.getY() : y;
        node.requestFocus();
        for (int i = 1; i <= Math.max(1, clicks); i++) {
            Event.fireEvent(node, mouse(MouseEvent.MOUSE_PRESSED, x, y, sx, sy, true, i));
            Event.fireEvent(node, mouse(MouseEvent.MOUSE_RELEASED, x, y, sx, sy, false, i));
            Event.fireEvent(node, mouse(MouseEvent.MOUSE_CLICKED, x, y, sx, sy, false, i));
        }
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

    static void type(Node node, String text) {
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

    static void pressKey(Node node, String keyName) {
        KeyCode code;
        try {
            code = KeyCode.valueOf(keyName.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new FxPilotException(400, Codes.INVALID_KEY, "Unknown key '" + keyName + "', use javafx KeyCode names like ENTER, TAB, ESCAPE, DOWN");
        }
        Event.fireEvent(node, key(KeyEvent.KEY_PRESSED, code, ""));
        Event.fireEvent(node, key(KeyEvent.KEY_RELEASED, code, ""));
    }

    private static KeyEvent key(javafx.event.EventType<KeyEvent> type, KeyCode code, String text) {
        return new KeyEvent(type, KeyEvent.CHAR_UNDEFINED, text, code, false, false, false, false);
    }

    private static TextInputControl textInput(Node node) {
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
                throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Item not found. Available: " + items(combo.getItems()));
            } else {
                combo.getSelectionModel().select(item);
            }
        } else if (node instanceof ChoiceBox) {
            ChoiceBox choice = (ChoiceBox) node;
            Object item = pick(choice.getItems(), value, index);
            if (item == null) {
                throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Item not found. Available: " + items(choice.getItems()));
            }
            choice.getSelectionModel().select(item);
        } else if (node instanceof ListView) {
            ListView list = (ListView) node;
            Object item = pick(list.getItems(), value, index);
            if (item == null) {
                throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Item not found. Available: " + items(list.getItems()));
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
                if (rowText(table, i).equals(value)) {
                    row = i;
                    break;
                }
            }
            for (int i = 0; row < 0 && i < table.getItems().size(); i++) {
                if (rowText(table, i).contains(value)) {
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
                if (treeItemText(item).equals(value)) {
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
                if (treeTableRowText(table, i).equals(value)) {
                    row = i;
                }
            }
            for (int i = 0; i < rows && row < 0; i++) {
                if (treeTableRowText(table, i).contains(value)) {
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
            if (treeItemText(child).equals(value) || treeTableItemText(table, child).equals(value)
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
            Object cell = treeTableCellValue(c, table, item);
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
        checkDenied(node.getClass().getSimpleName(), node);
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
            throw new FxPilotException(409, Codes.NO_CHILDREN, "Tree item '" + treeItemText(item) + "' has no children");
        }
        expandParents(item);
        item.setExpanded(expanded);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "done");
        result.put("item", treeItemText(item));
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
            if (treeItemText(child).equals(value)) {
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
                cb.setIndeterminate(false);
                cb.setSelected(!checked);
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
                slider.setValue(clamp(Double.parseDouble(v), slider.getMin(), slider.getMax()));
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
                    next = Double.valueOf(v);
                } else if (current instanceof LocalDate) {
                    next = LocalDate.parse(v);
                } else {
                    next = v;
                }
                factory.setValue(next);
            } else if (node instanceof DatePicker) {
                DatePicker picker = (DatePicker) node;
                picker.setValue(LocalDate.parse(v));
                Event.fireEvent(picker, new javafx.event.ActionEvent());
            } else if (node instanceof javafx.scene.control.ColorPicker) {
                javafx.scene.control.ColorPicker picker = (javafx.scene.control.ColorPicker) node;
                picker.setValue(Color.web(v));
                Event.fireEvent(picker, new javafx.event.ActionEvent());
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
        Node target = selector == null || selector.isBlank() ? findFirst(MenuBar.class) : find(selector, within, nth);
        checkDenied(selector == null ? "MenuBar" : selector, target);
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

    // ---------------------------------------------------------------- screenshots

    static byte[] screenshot(Window w, Node node) throws IOException {
        return screenshot(w, node, 0, 0, -1, -1);
    }

    static byte[] screenshot(Window w, Node node, double x, double y, double width, double height) throws IOException {
        WritableImage img = query(() -> node != null
                ? node.snapshot(new SnapshotParameters(), null)
                : w.getScene().snapshot(null));
        int imageWidth = (int) img.getWidth();
        int imageHeight = (int) img.getHeight();
        int clipX = Math.max(0, Math.min(imageWidth, (int) Math.round(x)));
        int clipY = Math.max(0, Math.min(imageHeight, (int) Math.round(y)));
        int clipWidth = width > 0 ? Math.min(imageWidth - clipX, (int) Math.round(width)) : imageWidth - clipX;
        int clipHeight = height > 0 ? Math.min(imageHeight - clipY, (int) Math.round(height)) : imageHeight - clipY;
        if (clipWidth <= 0 || clipHeight <= 0) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "screenshot region is empty");
        }
        int[] argb = new int[clipWidth * clipHeight];
        img.getPixelReader().getPixels(clipX, clipY, clipWidth, clipHeight,
                PixelFormat.getIntArgbInstance(), argb, 0, clipWidth);
        BufferedImage buffered = new BufferedImage(clipWidth, clipHeight, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, clipWidth, clipHeight, argb, 0, clipWidth);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", out);
        return out.toByteArray();
    }
}
