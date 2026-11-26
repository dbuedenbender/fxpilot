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
package io.github.dbuedenbender.fxpilot.mcp;

import io.github.dbuedenbender.fxpilot.internal.Json;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

/**
 * The MCP tools as one registry: each entry holds the definition shown by {@code tools/list}, the profile
 * it belongs to, what it does to the app (which drives the MCP annotations and {@code FXPILOT_DANGEROUS}),
 * whether it is recorded, and its handler.
 */
final class ToolCatalog {

    /**
     * Which tools {@code tools/list} offers ({@code FXPILOT_TOOLS=core|standard|all}, default all). Every
     * endpoint operation stays reachable through {@code fx_exec}, which is part of every profile.
     */
    enum Profile { CORE, STANDARD, ALL }

    /** What a tool does to the app. */
    enum Kind {
        /** Only reads the UI. */
        READ,
        /** Writes only FxPilot's own state or files (plans, baselines). */
        LOCAL,
        /** Drives the UI; the app may have real side effects. */
        WRITE,
        /** JavaScript, reflective setters or file writes; hidden with {@code FXPILOT_DANGEROUS=false}. */
        DANGEROUS
    }

    interface Handler {
        Map<String, Object> call(Map<String, Object> args, String base) throws Exception;
    }

    static final class Entry {
        final String name;
        final Profile profile;
        final Kind kind;
        final boolean recorded;
        final Handler handler;
        final Map<String, Object> definition;

        Entry(String name, Profile profile, Kind kind, boolean recorded, Handler handler, Map<String, Object> definition) {
            this.name = name;
            this.profile = profile;
            this.kind = kind;
            this.recorded = recorded;
            this.handler = handler;
            this.definition = definition;
        }
    }

    private static final boolean RECORDED = true;
    private static final boolean NOT_RECORDED = false;

    /** Endpoint operations that {@code FXPILOT_DANGEROUS=false} rejects, like {@code fxpilot.dangerous} on the endpoint. */
    static final Set<String> DANGEROUS_OPS = Set.of("propertySet", "webExecute", "image");

    /** WebView operations that execute JavaScript but are classified as read/write on the endpoint. */
    private static final Set<String> WEB_SCRIPT_OPS = Set.of("webQuery", "webHtml", "webClick");

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    static {
        for (Entry e : define()) {
            if (ENTRIES.put(e.name, e) != null) {
                throw new IllegalStateException("duplicate tool " + e.name);
            }
        }
    }

    private static volatile Profile profile = parseProfile(System.getenv("FXPILOT_TOOLS"));
    private static volatile boolean dangerousAllowed = !"false".equalsIgnoreCase(
            String.valueOf(System.getenv("FXPILOT_DANGEROUS")).trim());

    private ToolCatalog() {
    }

    static Profile parseProfile(String raw) {
        if (raw == null || raw.isBlank()) {
            return Profile.ALL;
        }
        try {
            return Profile.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            System.err.println("[fxpilot] unknown FXPILOT_TOOLS='" + raw + "', using all (core, standard, all)");
            return Profile.ALL;
        }
    }

    /** For tests; the server reads FXPILOT_TOOLS and FXPILOT_DANGEROUS at startup. */
    static void configure(Profile newProfile, boolean allowDangerous) {
        profile = newProfile;
        dangerousAllowed = allowDangerous;
    }

    static Profile profile() {
        return profile;
    }

    static boolean enabled(Entry e) {
        return e.profile.ordinal() <= profile.ordinal() && (e.kind != Kind.DANGEROUS || dangerousAllowed);
    }

    /** Definitions for {@code tools/list}: the tools of the active profile. */
    static List<Object> list() {
        List<Object> out = new ArrayList<>();
        for (Entry e : ENTRIES.values()) {
            if (enabled(e)) {
                out.add(e.definition);
            }
        }
        return out;
    }

    static Collection<Entry> entries() {
        return Collections.unmodifiableCollection(ENTRIES.values());
    }

    static boolean isRecorded(String tool) {
        Entry e = ENTRIES.get(tool);
        return e != null && e.recorded;
    }

    private static List<Entry> define() {
        List<Entry> t = new ArrayList<>();
        t.add(tool("fx_health", Profile.CORE, Kind.READ, NOT_RECORDED,
                (a, base) -> Results.text(EndpointClient.get("/health", a, base)),
                "Check whether the JavaFX app is running with the FxPilot endpoint enabled. Returns window "
                + "titles and app-specific notes/warnings. Call this first.", props()));
        t.add(tool("fx_list_windows", Profile.CORE, Kind.READ, NOT_RECORDED,
                (a, base) -> Results.text(EndpointClient.get("/windows", a, base)),
                "List all showing windows (main stage, dialogs, popups) with index, title and bounds.", props()));

        Map<String, Object> tree = selectorProps("Only return the subtree of this node (optional)");
        tree.put("window", prop("string", "Window index or title:<text> (default: focused window)"));
        tree.put("full", prop("boolean", "Include skin internals of controls"));
        tree.put("maxDepth", prop("integer", "Maximum depth (default 50)"));
        t.add(tool("fx_get_tree", Profile.STANDARD, Kind.READ, NOT_RECORDED,
                (a, base) -> Results.text(EndpointClient.get("/tree", ToolHandlers.withSelector(a, "window", "full", "maxDepth"), base)),
                "Get the scene graph of a window as JSON: type, id (fx:id), text, value, items, selected, bounds. "
                + "TableView/TreeView nodes also include columns and rows. Controls are not expanded into their skin internals "
                + "unless full=true.", tree));
        t.add(tool("fx_get_view", Profile.CORE, Kind.READ, NOT_RECORDED,
                (a, base) -> Results.text(EndpointClient.get("/view", ToolHandlers.pick(a, "window", "maxDepth", "limit"), base)),
                "Compact flat snapshot of visible, interesting nodes (controls, labels, ids, accessible text) "
                + "with ref, state and bounds. Use this instead of the full tree for large UIs.",
                props("window", "string", "Window index or title:<text> (default: focused window)",
                        "maxDepth", "integer", "Maximum depth (default 50)",
                        "limit", "integer", "Maximum nodes (default 200, max 500)")));

        t.add(tool("fx_get_node", Profile.CORE, Kind.READ, NOT_RECORDED,
                (a, base) -> Results.text(EndpointClient.get("/node", ToolHandlers.withSelector(a), base)),
                "Get the current state of one node, including rows/columns for TableView and TreeView. "
                + "If the selector may match several nodes, call fx_find first.",
                selectorProps("fx:id, CSS selector, text=<label> or role=<name>"), "selector"));

        Map<String, Object> find = selectorProps("fx:id, CSS selector, text=<label>, text*=<part> or role=<name>");
        find.put("limit", prop("integer", "Max nodes to return (default 20, max 200)"));
        t.add(tool("fx_find", Profile.CORE, Kind.READ, NOT_RECORDED,
                (a, base) -> Results.text(EndpointClient.get("/matches", ToolHandlers.withSelector(a, "limit"), base)),
                "List all nodes matching a selector with count, type, id and text. Use it to disambiguate "
                + "selectors and to assert how many items exist.", find, "selector"));

        Map<String, Object> click = selectorProps("fx:id, CSS selector, text=<label> or role=<name>");
        click.put("clicks", prop("integer", "1 (default) or 2 for a double click, e.g. to start inline editing"));
        click.put("button", prop("string", "PRIMARY (default), SECONDARY or MIDDLE"));
        click.put("mode", prop("string", "fire (default, fires ButtonBase) or mouse (full mouse event pipeline)"));
        click.put("modifiers", prop("string", "CTRL, SHIFT, ALT, META (comma separated)"));
        click.put("requireVisible", prop("boolean", "Fail when the node is hidden"));
        click.put("requireEnabled", prop("boolean", "Fail when the node is disabled"));
        t.add(tool("fx_click", Profile.CORE, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("click", a, base),
                "Click a node. Buttons are fired by default; mode=mouse sends synthetic press/release/click. "
                + "button=SECONDARY opens context menus, modifiers=CTRL enables multi-select. clicks=2 starts inline editing. "
                + "Check the notes from fx_health for buttons with real-world side effects before clicking.", click, "selector"));

        t.add(tool("fx_hover", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> Results.text(EndpointClient.post("/hover", ToolHandlers.withSelector(a), base)),
                "Move the synthetic mouse over a node (MOUSE_ENTERED/MOUSE_MOVED) to trigger hover handlers.",
                selectorProps("fx:id, CSS selector, text=<label> or role=<name>"), "selector"));

        Map<String, Object> setText = selectorProps("Target text input");
        setText.put("text", prop("string", "New text"));
        t.add(tool("fx_set_text", Profile.CORE, Kind.WRITE, RECORDED,
                (a, base) -> Results.text(EndpointClient.post("/setText", ToolHandlers.withSelector(a, "text"), base)),
                "Replace the text of a TextField/TextArea and fire a KEY_RELEASED event so onKeyReleased handlers run.",
                setText, "selector", "text"));

        Map<String, Object> type = selectorProps("Target text input");
        type.put("text", prop("string", "Text to type"));
        t.add(tool("fx_type", Profile.CORE, Kind.WRITE, RECORDED,
                (a, base) -> Results.text(EndpointClient.post("/type", ToolHandlers.withSelector(a, "text"), base)),
                "Type text character by character (KEY_PRESSED/KEY_TYPED/KEY_RELEASED) at the end of a text input.",
                type, "selector", "text"));

        Map<String, Object> key = selectorProps("Target node (optional, default: focused node)");
        key.put("key", prop("string", "KeyCode name, e.g. ENTER, TAB, ESCAPE, F2"));
        key.put("modifiers", prop("string", "CTRL, SHIFT, ALT, META (comma separated)"));
        key.put("window", prop("string", "Window index or title:<text> for the focused node"));
        t.add(tool("fx_press_key", Profile.CORE, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("pressKey", a, base),
                "Send a key press/release, optionally with modifiers. Without a selector the focused node "
                + "(or the scene root) gets the event, so ESCAPE/ENTER also reach dialog and accelerator handlers.", key, "key"));

        Map<String, Object> select = selectorProps("Target node");
        select.put("value", prop("string", "Item text or table row text ('cell | cell')"));
        select.put("index", prop("integer", "Item or row index"));
        t.add(tool("fx_select", Profile.CORE, Kind.WRITE, RECORDED,
                (a, base) -> Results.text(EndpointClient.post("/select", ToolHandlers.withSelector(a, "value", "index"), base)),
                "Select an item in a ComboBox, ChoiceBox, ListView, TableView, TreeView or TreeTableView, "
                + "or a tab in a TabPane.", select, "selector"));

        Map<String, Object> expanded = selectorProps("TreeView or TreeTableView node");
        expanded.put("value", prop("string", "Item text or row text ('cell | cell')"));
        expanded.put("index", prop("integer", "Visible row index of the item"));
        expanded.put("expanded", prop("boolean", "true to expand (default), false to collapse"));
        t.add(tool("fx_set_expanded", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> Results.text(EndpointClient.post("/expand", ToolHandlers.withSelector(a, "value", "index", "expanded"), base)),
                "Expand or collapse an item of a TreeView/TreeTableView by item or row text, or by "
                + "visible row index. Parents are expanded automatically first.", expanded, "selector"));

        Map<String, Object> value = selectorProps("Target node");
        value.put("value", prop("string", "New value"));
        t.add(tool("fx_set_value", Profile.CORE, Kind.WRITE, RECORDED,
                (a, base) -> Results.text(EndpointClient.post("/value", ToolHandlers.withSelector(a, "value"), base)),
                "Set the value of a Slider, Spinner, DatePicker (ISO date) or ColorPicker (e.g. #ff0000).",
                value, "selector", "value"));

        Map<String, Object> scroll = selectorProps("Target node");
        scroll.put("amount", prop("number", "Wheel steps, default 1 (negative = up)"));
        t.add(tool("fx_scroll", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> Results.text(EndpointClient.post("/scroll", ToolHandlers.withSelector(a, "amount"), base)),
                "Scroll a scrollable node (ScrollPane, ListView, TableView, TreeView) by wheel steps.",
                scroll, "selector"));

        Map<String, Object> checked = selectorProps("Target node");
        checked.put("checked", prop("boolean", "Desired state"));
        t.add(tool("fx_set_checked", Profile.CORE, Kind.WRITE, RECORDED,
                (a, base) -> Results.text(EndpointClient.post("/check", ToolHandlers.withSelector(a, "checked"), base)),
                "Set a CheckBox/ToggleButton/RadioButton to checked or unchecked (fires its action if it changes).",
                checked, "selector", "checked"));

        Map<String, Object> menu = selectorProps("MenuBar node (optional, default: first MenuBar)");
        menu.put("menu", prop("string", "Text of the top-level menu"));
        menu.put("item", prop("string", "Text of the menu item"));
        menu.put("path", prop("string", "Full path, e.g. File > Export > PDF"));
        t.add(tool("fx_click_menu", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.clickMenu(a, base),
                "Fire a menu entry without opening the popup: pick the top-level menu and item by text, "
                + "or use path for nested submenus. Leave item out to just open the menu.", menu));

        t.add(tool("fx_robot_click", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> Results.text(EndpointClient.post("/robotClick", ToolHandlers.pick(a, "window", "x", "y"), base)),
                "Move the REAL mouse and click at scene coordinates of a window. Use only when there is no "
                + "node to address (e.g. inside a WebView or canvas).",
                props("window", "string", "Window index or title:<text>", "x", "number", "Scene x", "y", "number", "Scene y"),
                "x", "y"));
        t.add(tool("fx_robot_drag", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> Results.text(EndpointClient.post("/robotDrag", ToolHandlers.pick(a, "window", "x1", "y1", "x2", "y2"), base)),
                "Drag with the REAL mouse from one scene coordinate to another, e.g. drag and drop or a canvas.",
                props("window", "string", "Window index or title:<text>", "x1", "number", "Start x", "y1", "number", "Start y",
                        "x2", "number", "End x", "y2", "number", "End y"), "x1", "y1", "x2", "y2"));

        Map<String, Object> shot = selectorProps("Only this node (optional)");
        shot.put("window", prop("string", "Window index or title:<text> (default: focused window)"));
        shot.put("x", prop("number", "Optional crop X"));
        shot.put("y", prop("number", "Optional crop Y"));
        shot.put("width", prop("number", "Optional crop width"));
        shot.put("height", prop("number", "Optional crop height"));
        t.add(tool("fx_screenshot", Profile.CORE, Kind.READ, NOT_RECORDED,
                (a, base) -> ToolHandlers.screenshot(ToolHandlers.withSelector(a, "window", "x", "y", "width", "height"), base),
                "Take a PNG screenshot of a window or a single node, optionally cropped to a region.", shot));

        Map<String, Object> diff = selectorProps("Only this node (optional)");
        diff.put("window", prop("string", "Window index or title:<text>"));
        diff.put("baseline", prop("string", "Path to the baseline PNG (created if missing or update=true)"));
        diff.put("tolerance", prop("number", "Allowed fraction of differing pixels, default 0.01"));
        diff.put("update", prop("boolean", "Write the current screenshot as the new baseline"));
        diff.put("saveDiff", prop("string", "Optional path for a red/black diff PNG"));
        t.add(tool("fx_screenshot_diff", Profile.STANDARD, Kind.LOCAL, RECORDED,
                (a, base) -> ToolHandlers.screenshotDiff(a, base),
                "Compare a screenshot with a baseline PNG (pixel diff with tolerance). Use it for "
                + "visual regression; passing checks are recorded and replayed.", diff, "baseline"));

        Map<String, Object> wait = selectorProps("Target node");
        wait.put("timeoutMs", prop("integer", "Default 5000, at most 120000"));
        wait.put("text", prop("string", "Wait until text equals this"));
        wait.put("value", prop("string", "Wait until value equals this"));
        wait.put("count", prop("integer", "Wait until this many nodes match"));
        wait.put("visible", prop("boolean", "Require visibility"));
        wait.put("enabled", prop("boolean", "Require visible and not disabled"));
        wait.put("exists", prop("boolean", "Default true; false waits until the node disappears"));
        wait.put("focused", prop("boolean", "Require focus"));
        wait.put("selected", prop("boolean", "Require selected state"));
        wait.put("checked", prop("boolean", "Require checked state"));
        wait.put("selectedIndex", prop("integer", "Require this selected index"));
        wait.put("selectedIndices", prop("string", "Require these selected indices"));
        wait.put("rowCount", prop("integer", "Require this row count"));
        wait.put("progress", prop("number", "Require this progress value"));
        wait.put("tooltip", prop("string", "Require this tooltip text"));
        wait.put("accessibleText", prop("string", "Require this accessible text"));
        t.add(tool("fx_wait_for", Profile.CORE, Kind.READ, RECORDED,
                (a, base) -> ToolHandlers.waitFor(a, base),
                "Wait until a node matches the selector and the optional state checks.",
                wait, "selector"));
        t.add(tool("fx_wait_for_window", Profile.STANDARD, Kind.READ, NOT_RECORDED,
                (a, base) -> ToolHandlers.waitForWindow(a, base),
                "Wait until a window with a matching title exists.",
                props("title", "string", "Window title (contains, case-insensitive)", "timeoutMs", "integer", "Default 5000, at most 120000"),
                "title"));

        Map<String, Object> assertion = selectorProps("Target node");
        assertion.put("text", prop("string", "Expected text"));
        assertion.put("value", prop("string", "Expected value"));
        assertion.put("exists", prop("boolean", "Default true; false expects no match"));
        assertion.put("count", prop("integer", "Expected number of matching nodes"));
        assertion.put("visible", prop("boolean", "Expected visibility"));
        assertion.put("enabled", prop("boolean", "Expected enabled state"));
        assertion.put("checked", prop("boolean", "Expected selected state"));
        assertion.put("selected", prop("boolean", "Expected selected state (toggles/lists)"));
        assertion.put("selectedIndex", prop("integer", "Expected selected index"));
        assertion.put("selectedIndices", prop("string", "Expected selected indices, e.g. 0,2,3"));
        assertion.put("rowCount", prop("integer", "Expected row count"));
        assertion.put("focused", prop("boolean", "Expected focus"));
        assertion.put("progress", prop("number", "Expected progress"));
        assertion.put("tooltip", prop("string", "Expected tooltip text"));
        assertion.put("accessibleText", prop("string", "Expected accessible text"));
        assertion.put("property", prop("string", "JavaFX property name to read"));
        assertion.put("propertyValue", prop("string", "Expected property value"));
        t.add(tool("fx_assert", Profile.CORE, Kind.READ, NOT_RECORDED,
                (a, base) -> Recorder.assertNode(a),
                "Check a node's state: exists, text, value, count, visible, enabled, checked, selection, "
                + "rowCount, focus, progress, tooltip and accessible text. Passing assertions are recorded and replayed.",
                assertion, "selector"));

        t.add(tool("fx_batch", Profile.CORE, Kind.WRITE, NOT_RECORDED,
                (a, base) -> ToolHandlers.batch(a),
                "Run several tool calls in one request, sequentially. 'calls' is a list of "
                + "{tool: string, args: object}; each call is executed like a normal tool call and recorded during recording.",
                props("calls", "array", "List of {tool, args} objects"), "calls"));

        t.add(tool("fx_record_start", Profile.STANDARD, Kind.LOCAL, NOT_RECORDED,
                (a, base) -> Recorder.startRecording(a),
                "Start recording a replayable plan. Captures mutating tools and passing fx_assert "
                + "calls; the port argument is not recorded.",
                props("name", "string", "Plan name (default: recording)")));
        t.add(tool("fx_record_stop", Profile.STANDARD, Kind.LOCAL, NOT_RECORDED,
                (a, base) -> Recorder.stopRecording(a),
                "Stop recording and return the JSON plan, or write it to a file when 'file' is given.",
                props("file", "string", "Target JSON file (optional)", "name", "string", "Plan name override")));
        t.add(tool("fx_replay", Profile.STANDARD, Kind.WRITE, NOT_RECORDED,
                (a, base) -> Recorder.replay(a),
                "Replay a recorded plan against the running app. Stops at the first failing step by default "
                + "and reports passed/failed steps. Supports ${variables}, per-step retries, failure screenshots and JUnit XML. "
                + "Also available as CLI: --replay plan.json.",
                props("file", "string", "Plan JSON file", "stopOnError", "boolean", "Default true",
                        "delayMs", "integer", "Delay between steps in ms (default 300)",
                        "variables", "object", "Values for ${name} placeholders",
                        "retries", "integer", "Retries per step (default 0)",
                        "screenshotOnFailure", "boolean", "Save fxpilot/failure-step-N.png",
                        "junitReport", "string", "Optional JUnit XML report path")));

        t.add(tool("fx_exec", Profile.CORE, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.execGeneric(a, base),
                "Run an extended FxPilot operation. Use this for operations without a dedicated tool. "
                + "The args object contains the operation parameters.",
                props("op", "string", "Operation name, e.g. tableCell, propertyGet, webQuery", "args", "object",
                        "Operation arguments"), "op"));
        t.add(tool("fx_hotkey", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("hotkey", a, base),
                "Send a key combination such as CTRL+S, CTRL+SHIFT+T or ALT+F4 to the focused node.",
                props("keys", "string", "Key combination", "window", "string", "Window index or title:<text>"), "keys"));
        t.add(tool("fx_clipboard", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.clipboard(a, base),
                "Read or write the system clipboard.",
                props("action", "string", "get or set", "text", "string", "Text for set"), "action"));
        t.add(tool("fx_wheel", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("wheel", a, base),
                "Fire a real ScrollEvent (mouse wheel) on a node, e.g. for custom scroll handlers.",
                props("selector", "string", "Target node", "deltaX", "number", "Horizontal delta, default 0",
                        "deltaY", "number", "Vertical delta, default 0", "modifiers", "string", "CTRL, SHIFT, ALT, META"),
                "selector"));
        t.add(tool("fx_scroll_to", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("scrollTo", a, base),
                "Scroll a list/table/tree to a row, a ScrollPane to a target node, or set a ScrollBar value.",
                props("selector", "string", "Scrollable control", "index", "integer", "Row/index",
                        "row", "integer", "Alias for index", "target", "string", "Target node inside a ScrollPane",
                        "vvalue", "number", "ScrollPane vertical value 0..1", "hvalue", "number",
                        "ScrollPane horizontal value 0..1", "value", "number", "ScrollBar value"), "selector"));
        t.add(tool("fx_drag", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("drag", a, base),
                "Drag with synthetic mouse events from one node to another (drag and drop).",
                props("selector", "string", "Source node", "target", "string", "Target node", "targetWithin", "string",
                        "Limit target search", "targetNth", "string", "Target match index", "steps", "integer",
                        "Intermediate move steps, default 10"), "selector", "target"));
        t.add(tool("fx_focus", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("focus", a, base),
                "Move keyboard focus to a node.",
                props("selector", "string", "Target node"), "selector"));
        t.add(tool("fx_popup", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("popup", a, base),
                "Open or close the popup of a ComboBox, ChoiceBox, DatePicker, ColorPicker or MenuButton.",
                props("selector", "string", "Target control", "action", "string", "open or close"),
                "selector"));
        t.add(tool("fx_step", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("step", a, base),
                "Increment or decrement a Slider, Spinner, DatePicker or ScrollBar.",
                props("selector", "string", "Target control", "amount", "number", "Step amount, default 1"),
                "selector"));
        t.add(tool("fx_text", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("textAction", a, base),
                "Text editing: clear, append, selectAll, selectRange, caret, replace, copy, paste or cut.",
                props("selector", "string", "Text input", "action", "string", "Text action", "value", "string", "New text",
                        "start", "integer", "Start offset", "end", "integer", "End offset"), "selector", "action"));
        t.add(tool("fx_select_rows", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("selectRows", a, base),
                "Select several rows/items in a ListView, TableView, TreeView or TreeTableView.",
                props("selector", "string", "Target control", "indices", "string", "Comma/space separated indices"),
                "selector", "indices"));
        t.add(tool("fx_selection", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.selection(a, base),
                "Selection operations: rows (indices), all or clear.",
                props("selector", "string", "Target control", "action", "string", "rows, all or clear",
                        "indices", "string", "Comma/space separated indices"), "selector", "action"));
        t.add(tool("fx_table", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.table(a, base),
                "TableView/TreeTableView operations: cell read, edit, set, sort, column, select or scroll.",
                props("selector", "string", "Table control", "action", "string",
                        "cell, edit, cellSet, sort, column, select, scroll", "row", "integer", "Row index",
                        "column", "string", "Column text or index", "value", "string", "Value",
                        "order", "string", "ASCENDING or DESCENDING"),
                "selector", "action"));
        t.add(tool("fx_tree", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("tree", a, base),
                "TreeView/TreeTableView operations: expandAll, collapseAll, expand or collapse.",
                props("selector", "string", "Tree control", "action", "string", "expandAll, collapseAll, expand, collapse",
                        "value", "string", "Item text", "index", "string", "Visible row index"), "selector", "action"));
        t.add(tool("fx_tab", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("tab", a, base),
                "TabPane operations: select, close or move a tab.",
                props("selector", "string", "TabPane", "action", "string", "select, close or move",
                        "tab", "string", "Tab text", "index", "integer", "Tab index", "to", "integer",
                        "Target index for move"), "selector", "action"));
        t.add(tool("fx_accordion", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("accordion", a, base),
                "Expand a TitledPane in an Accordion.",
                props("selector", "string", "Accordion", "pane", "string", "Pane text", "index", "integer",
                        "Pane index"), "selector"));
        t.add(tool("fx_pagination", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("pagination", a, base),
                "Pagination operations: set, next or prev.",
                props("selector", "string", "Pagination", "action", "string", "set, next or prev",
                        "page", "integer", "Page index"), "selector", "action"));
        t.add(tool("fx_menu", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("menu", a, base),
                "Fire a menu entry by path, including nested submenus.",
                props("selector", "string", "MenuBar (default: first)", "path", "string", "e.g. File > Export > PDF"),
                "path"));
        t.add(tool("fx_context_menu", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.contextMenu(a, base),
                "Request a context menu on a node and optionally fire an item from it.",
                props("selector", "string", "Target node", "item", "string", "Menu item text (optional)"), "selector"));
        t.add(tool("fx_window", Profile.STANDARD, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.exec("window", a, base),
                "Window actions: focus, close, iconify, restore, maximize, unmaximize, move, resize, accept, dismiss.",
                props("window", "string", "Window index or title:<text>", "action", "string", "Window action",
                        "x", "number", "X", "y", "number", "Y", "width", "number", "Width", "height", "number", "Height"),
                "action"));
        t.add(tool("fx_property", Profile.ALL, Kind.DANGEROUS, RECORDED,
                (a, base) -> ToolHandlers.property(a, base),
                "Read or write a JavaFX bean property or a value from Node.getProperties().",
                props("selector", "string", "Target node", "action", "string", "get or set", "name", "string",
                        "Property name", "value", "string", "New value"), "selector", "action", "name"));
        t.add(tool("fx_web", Profile.ALL, Kind.DANGEROUS, RECORDED,
                (a, base) -> ToolHandlers.web(a, base),
                "WebView bridge: execute JavaScript, query an element, click it or read the HTML.",
                props("selector", "string", "WebView", "action", "string", "execute, query, click or html",
                        "script", "string", "JavaScript", "css", "string", "CSS selector", "property", "string",
                        "DOM property, default outerHTML"), "selector", "action"));
        t.add(tool("fx_chart", Profile.ALL, Kind.READ, NOT_RECORDED,
                (a, base) -> ToolHandlers.exec("chart", a, base),
                "Read chart title, series and data points.",
                props("selector", "string", "Chart node"), "selector"));
        t.add(tool("fx_canvas", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.canvas(a, base),
                "Canvas pixel read or synthetic click at local coordinates.",
                props("selector", "string", "Canvas", "action", "string", "pixel or click", "x", "number", "X",
                        "y", "number", "Y"), "selector", "action"));
        t.add(tool("fx_media", Profile.ALL, Kind.WRITE, RECORDED,
                (a, base) -> ToolHandlers.media(a, base),
                "MediaView actions: play, pause, stop, seek, volume, mute, rate or status.",
                props("selector", "string", "MediaView", "action", "string", "Media action", "value", "string",
                        "Action value"), "selector", "action"));
        t.add(tool("fx_image", Profile.ALL, Kind.DANGEROUS, NOT_RECORDED,
                (a, base) -> ToolHandlers.exec("image", a, base),
                "Save the image of an ImageView as a PNG file. An existing file is only replaced with overwrite=true.",
                props("selector", "string", "ImageView", "file", "string", "Target PNG file",
                        "overwrite", "boolean", "Replace an existing file"), "selector", "file"));
        t.add(tool("fx_tooltip", Profile.ALL, Kind.READ, NOT_RECORDED,
                (a, base) -> ToolHandlers.exec("tooltip", a, base),
                "Read the tooltip text of a control.",
                props("selector", "string", "Target node"), "selector"));
        t.add(tool("fx_idle", Profile.STANDARD, Kind.READ, NOT_RECORDED,
                (a, base) -> ToolHandlers.exec("idle", a, base),
                "Wait for the FX thread to process queued work (best effort).", props()));
        t.add(tool("fx_retry", Profile.STANDARD, Kind.WRITE, NOT_RECORDED,
                (a, base) -> ToolHandlers.retryTool(a),
                "Call a tool repeatedly until it succeeds or the attempt limit is reached.",
                props("tool", "string", "Tool name", "args", "object", "Tool arguments",
                        "attempts", "integer", "Maximum attempts, default 3",
                        "delayMs", "integer", "Delay between attempts, default 300"), "tool"));
        return t;
    }

    /** Props shared by every selector-based tool. */
    private static Map<String, Object> selectorProps(String selectorDescription) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("selector", prop("string", selectorDescription
                + " Also accepts id=<exact fx:id>[@<n>], accessible=<text>, label=<text> and ref=<path>."));
        m.put("within", prop("string", "Only search inside the first match of this selector"));
        m.put("nth", prop("string", "0-based match index or 'last' (default: first match)"));
        m.put("requireVisible", prop("boolean", "Fail when the node is hidden"));
        m.put("requireEnabled", prop("boolean", "Fail when the node is disabled"));
        return m;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    static Map<String, Object> callTool(String name, Map<String, Object> a) throws Exception {
        if (name == null) {
            throw new RpcError(-32602, "tool name missing");
        }
        Entry e = ENTRIES.get(name);
        if (e == null) {
            throw new RpcError(-32602, "Unknown tool: " + name);
        }
        if (!enabled(e)) {
            return Results.toolError(name + " is not enabled: "
                    + (e.kind == Kind.DANGEROUS && !dangerousAllowed ? "FXPILOT_DANGEROUS=false"
                    : "FXPILOT_TOOLS=" + profile.name().toLowerCase(Locale.ROOT) + "; use fx_exec or FXPILOT_TOOLS=all"));
        }
        String blocked = blockedByPolicy(name, a);
        if (blocked != null) {
            return Results.toolError(blocked);
        }
        Map<String, Object> result = invoke(name, a, EndpointClient.baseUrl(a.get("port")));
        Recorder.recordStep(name, a, result);
        return result;
    }

    /** Sub-actions of enabled tools that FXPILOT_DANGEROUS=false still rejects. */
    static String blockedByPolicy(String name, Map<String, Object> a) {
        if (dangerousAllowed) {
            return null;
        }
        Map<String, Object> nested = a.get("args") instanceof Map ? Results.asMap(a.get("args")) : Map.of();
        String op = Results.str(a.get("op"));
        String action = "fx_exec".equals(name) ? Results.str(nested.get("action")) : Results.str(a.get("action"));
        boolean closes = "close".equalsIgnoreCase(action)
                && ("fx_window".equals(name) || "fx_tab".equals(name)
                        || "fx_exec".equals(name) && ("window".equals(op) || "tab".equals(op)));
        boolean readsClipboard = "fx_clipboard".equals(name) && !"set".equalsIgnoreCase(action)
                || "fx_exec".equals(name) && "clipboardGet".equals(op);
        boolean blocked = "fx_exec".equals(name) && (DANGEROUS_OPS.contains(op) || WEB_SCRIPT_OPS.contains(op))
                || closes || readsClipboard;
        return blocked ? name + (op == null ? "" : " op=" + op) + (action == null ? "" : " action=" + action)
                + " is disabled (FXPILOT_DANGEROUS=false)" : null;
    }

    /**
     * Path for a file the MCP server writes (plans, baselines, diffs, reports). With FXPILOT_DANGEROUS=false
     * it must lie below the working directory, so a client cannot write anywhere on the disk.
     */
    static Path writablePath(String raw) {
        return confined(raw, "Writing");
    }

    /** Path for a file a tool reads (plans, screenshot baselines); same rule as {@link #writablePath}. */
    static Path readablePath(String raw) {
        return confined(raw, "Reading");
    }

    /**
     * Symbolic links are resolved for the part of the path that exists, so a link below the working
     * directory cannot point the write or read elsewhere. The resolved path is returned, so the file
     * operation uses the path that was checked, not one whose links could still change in between.
     */
    private static Path confined(String raw, String verb) {
        Path path = Path.of(raw);
        if (dangerousAllowed) {
            return path;
        }
        try {
            Path cwd = Path.of("").toAbsolutePath().toRealPath();
            Path real = realPath(path.toAbsolutePath().normalize());
            if (!real.startsWith(cwd)) {
                throw new ToolError(verb + " '" + raw + "' is not allowed: with FXPILOT_DANGEROUS=false files must lie below "
                        + cwd);
            }
            return real;
        } catch (IOException e) {
            throw new ToolError(verb + " '" + raw + "' is not allowed: " + e.getMessage());
        }
    }

    private static Path realPath(Path absolute) throws IOException {
        Path existing = absolute;
        Path rest = null;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            Path name = existing.getFileName();
            rest = rest == null ? name : name.resolve(rest);
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        Path real = existing.toRealPath();
        return rest == null ? real : real.resolve(rest).normalize();
    }

    /** FXPILOT_DANGEROUS check without the profile check, for replayed steps; null when allowed. */
    static String dangerousBlocked(String name, Map<String, Object> a) {
        Entry e = ENTRIES.get(name);
        if (e != null && e.kind == Kind.DANGEROUS && !dangerousAllowed) {
            return name + " is disabled (FXPILOT_DANGEROUS=false)";
        }
        return blockedByPolicy(name, a);
    }

    /** Runs a tool without profile, policy and recording checks; callers check first. */
    static Map<String, Object> invoke(String name, Map<String, Object> a, String base) throws Exception {
        Entry e = ENTRIES.get(name);
        if (e == null) {
            throw new RpcError(-32602, "Unknown tool: " + name);
        }
        try {
            return e.handler.call(a, base);
        } catch (ToolError ex) {
            return Results.toolError(ex.getMessage());
        } catch (Json.ParseException ex) {
            return Results.toolError("Invalid JSON: " + ex.getMessage());
        } catch (ConnectException ex) {
            return Results.toolError("Cannot reach the app at " + base + ". Start it with the FxPilot endpoint enabled "
                    + "(-Dfxpilot.port=<port> or -javaagent:fxpilot.jar=port=<port>).");
        } catch (HttpFailure ex) {
            return Results.toolError(ex.getMessage());
        } catch (IOException ex) {
            return Results.toolError("I/O error talking to " + base + ": " + ex);
        }
    }

    private static Entry tool(String name, Profile profile, Kind kind, boolean recorded, Handler handler,
            String description, Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        properties.put("port", Map.of("type", "integer", "description", "Port of the target app on 127.0.0.1 (default from FXPILOT_URL)"));
        schema.put("properties", properties);
        if (required.length > 0) {
            schema.put("required", List.of(required));
        }
        Map<String, Object> annotations = new LinkedHashMap<>();
        annotations.put("readOnlyHint", kind == Kind.READ);
        annotations.put("destructiveHint", kind == Kind.WRITE || kind == Kind.DANGEROUS);
        annotations.put("idempotentHint", kind == Kind.READ);
        annotations.put("openWorldHint", false);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        t.put("annotations", annotations);
        return new Entry(name, profile, kind, recorded, handler, t);
    }

    /** props(name, type, description, name, type, description, ...) */
    private static Map<String, Object> props(String... spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 2 < spec.length; i += 3) {
            m.put(spec[i], Map.of("type", spec[i + 1], "description", spec[i + 2]));
        }
        return m;
    }
}
