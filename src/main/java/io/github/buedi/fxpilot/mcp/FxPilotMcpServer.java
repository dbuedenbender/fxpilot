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

package io.github.buedi.fxpilot.mcp;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * MCP server (stdio, JSON-RPC 2.0) that exposes the FxPilot endpoint of a running JavaFX
 * application as tools. No dependencies; run with {@code java FxPilotMcpServer.java} (JDK 11+).
 *
 * Environment: FXPILOT_URL (default http://127.0.0.1:8765), FXPILOT_TOKEN (optional).
 * Every tool also accepts an optional "port" argument to address another app on 127.0.0.1.
 */
public class FxPilotMcpServer {

    private static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final String VERSION = serverVersion();
    /** Sent on every request; the endpoint rejects POSTs without it when no token is configured. */
    private static final String CLIENT_HEADER = "fxpilot-mcp";

    /** Tools whose effects are worth replaying. Reads (tree, node, screenshot) are not recorded. */
    private static final Set<String> RECORDABLE = Set.of(
            "fx_click", "fx_hover", "fx_set_text", "fx_type", "fx_press_key", "fx_select",
            "fx_set_value", "fx_set_checked", "fx_scroll", "fx_click_menu",
            "fx_robot_click", "fx_robot_drag", "fx_wait_for", "fx_screenshot_diff",
            "fx_hotkey", "fx_clipboard", "fx_wheel", "fx_drag", "fx_focus", "fx_text",
            "fx_select_rows", "fx_selection", "fx_table", "fx_tree", "fx_tab", "fx_accordion",
            "fx_popup", "fx_step",
            "fx_pagination", "fx_menu", "fx_context_menu", "fx_scroll_to",
            "fx_window", "fx_property", "fx_web", "fx_media", "fx_canvas", "fx_exec");

    /** Operations that only read state and therefore use GET (also in read-only mode). */
    private static final Set<String> EXEC_READ_OPS = Set.of(
            "clipboardGet", "propertyGet", "chart", "canvasPixel", "webQuery", "webHtml",
            "tooltip", "idle", "tableCell");

    private static final String BASE_URL = env("FXPILOT_URL", "http://127.0.0.1:8765").replaceAll("/+$", "");
    private static final String TOKEN = env("FXPILOT_TOKEN", "");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private static PrintStream out;

    /**
     * Recording state. The stdio loop handles one request at a time, so no locking is needed.
     * {@code recording == null} means "not recording".
     */
    private static List<Object> recording;
    private static String recordingName;
    private static String recordingStartedAt;

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && ("--replay".equals(args[0]) || "-r".equals(args[0]))) {
            System.exit(replayMain(args));
        }
        out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8");
        System.setOut(System.err);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            Object id = null;
            try {
                Map<String, Object> msg = asMap(Json.parse(line));
                id = msg.get("id");
                String method = (String) msg.get("method");
                if (method == null || !msg.containsKey("id")) {
                    continue; // notification or response
                }
                Map<String, Object> params = msg.get("params") == null ? Map.of() : asMap(msg.get("params"));
                reply(id, dispatch(method, params));
            } catch (RpcError e) {
                replyError(id, e.code, e.getMessage());
            } catch (Exception e) {
                replyError(id, -32603, e.toString());
            }
        }
    }

    private static Object dispatch(String method, Map<String, Object> params) throws Exception {
        switch (method) {
            case "initialize": {
                Map<String, Object> r = new LinkedHashMap<>();
                Object v = params.get("protocolVersion");
                r.put("protocolVersion", v != null ? v : DEFAULT_PROTOCOL);
                r.put("capabilities", Map.of("tools", Map.of()));
                r.put("serverInfo", Map.of("name", "fxpilot", "version", VERSION));
                r.put("instructions", "Drives a running JavaFX application through its local FxPilot endpoint ("
                        + BASE_URL + ", other apps via the optional 'port' argument). Call fx_health first: it returns "
                        + "app-specific notes and warnings (e.g. buttons that print or write to production systems) - "
                        + "respect them. Then use fx_get_tree or fx_get_view to learn node ids/refs, act with fx_click / fx_set_text / "
                        + "fx_select / fx_set_value / fx_set_expanded / fx_set_checked / fx_scroll / fx_click_menu and verify with "
                        + "fx_screenshot or fx_assert. Selectors: fx:id (e.g. textField), CSS (#id, .class, Button), "
                        + "id=<exact fx:id>[@<n>] for literal ids (e.g. containing dots), accessible=<text>, label=<text>, "
                        + "ref=<path from fx_find>, text=<label> (exact, prefers visible nodes) or text*=<part> (substring). "
                        + "Use fx_hotkey for shortcuts (CTRL+S), fx_click(button=SECONDARY, mode=mouse) for real mouse paths, "
                        + "fx_wheel for scroll events, fx_drag, fx_table, fx_tree, fx_menu, fx_context_menu, fx_window, "
                        + "fx_text, fx_property, fx_web, fx_chart, fx_canvas and fx_media for component-specific operations. "
                        + "Errors contain a stable code such as "
                        + "NODE_NOT_FOUND; use it to decide the next step. When an action matches several nodes the "
                        + "result contains matches>1 and a warning - call fx_find to list them, then use nth=<index>, "
                        + "within=<scope selector> or a more specific selector. Use fx_batch to run several calls in one "
                        + "request. For regression checks, record a run with "
                        + "fx_record_start/fx_record_stop and replay it with fx_replay or "
                        + "'java FxPilotMcpServer.java --replay plan.json' in CI.");
                return r;
            }
            case "ping":
                return Map.of();
            case "tools/list":
                return Map.of("tools", tools());
            case "tools/call":
                return callTool((String) params.get("name"),
                        params.get("arguments") == null ? Map.of() : asMap(params.get("arguments")));
            default:
                throw new RpcError(-32601, "Method not found: " + method);
        }
    }

    // ---------------------------------------------------------------- tools

    private static List<Object> tools() {
        List<Object> t = new ArrayList<>();
        t.add(tool("fx_health", "Check whether the JavaFX app is running with the FxPilot endpoint enabled. Returns window "
                + "titles and app-specific notes/warnings. Call this first.", props()));
        t.add(tool("fx_list_windows", "List all showing windows (main stage, dialogs, popups) with index, title and bounds.", props()));

        Map<String, Object> tree = selectorProps("Only return the subtree of this node (optional)");
        tree.put("window", prop("string", "Window index or title:<text> (default: focused window)"));
        tree.put("full", prop("boolean", "Include skin internals of controls"));
        tree.put("maxDepth", prop("integer", "Maximum depth (default 50)"));
        t.add(tool("fx_get_tree", "Get the scene graph of a window as JSON: type, id (fx:id), text, value, items, selected, bounds. "
                + "TableView/TreeView nodes also include columns and rows. Controls are not expanded into their skin internals "
                + "unless full=true.", tree));
        t.add(tool("fx_get_view", "Compact flat snapshot of visible, interesting nodes (controls, labels, ids, accessible text) "
                + "with ref, state and bounds. Use this instead of the full tree for large UIs.",
                props("window", "string", "Window index or title:<text> (default: focused window)",
                        "maxDepth", "integer", "Maximum depth (default 50)",
                        "limit", "integer", "Maximum nodes (default 200, max 500)")));

        t.add(tool("fx_get_node", "Get the current state of one node, including rows/columns for TableView and TreeView. "
                + "If the selector may match several nodes, call fx_find first.",
                selectorProps("fx:id, CSS selector, text=<label> or role=<name>"), "selector"));

        Map<String, Object> find = selectorProps("fx:id, CSS selector, text=<label>, text*=<part> or role=<name>");
        find.put("limit", prop("integer", "Max nodes to return (default 20, max 200)"));
        t.add(tool("fx_find", "List all nodes matching a selector with count, type, id and text. Use it to disambiguate "
                + "selectors and to assert how many items exist.", find, "selector"));

        Map<String, Object> click = selectorProps("fx:id, CSS selector, text=<label> or role=<name>");
        click.put("clicks", prop("integer", "1 (default) or 2 for a double click, e.g. to start inline editing"));
        click.put("button", prop("string", "PRIMARY (default), SECONDARY or MIDDLE"));
        click.put("mode", prop("string", "fire (default, fires ButtonBase) or mouse (full mouse event pipeline)"));
        click.put("modifiers", prop("string", "CTRL, SHIFT, ALT, META (comma separated)"));
        click.put("requireVisible", prop("boolean", "Fail when the node is hidden"));
        click.put("requireEnabled", prop("boolean", "Fail when the node is disabled"));
        t.add(tool("fx_click", "Click a node. Buttons are fired by default; mode=mouse sends synthetic press/release/click. "
                + "button=SECONDARY opens context menus, modifiers=CTRL enables multi-select. clicks=2 starts inline editing. "
                + "Check the notes from fx_health for buttons with real-world side effects before clicking.", click, "selector"));

        t.add(tool("fx_hover", "Move the synthetic mouse over a node (MOUSE_ENTERED/MOUSE_MOVED) to trigger hover handlers.",
                selectorProps("fx:id, CSS selector, text=<label> or role=<name>"), "selector"));

        Map<String, Object> setText = selectorProps("Target text input");
        setText.put("text", prop("string", "New text"));
        t.add(tool("fx_set_text", "Replace the text of a TextField/TextArea and fire a KEY_RELEASED event so onKeyReleased handlers run.",
                setText, "selector", "text"));

        Map<String, Object> type = selectorProps("Target text input");
        type.put("text", prop("string", "Text to type"));
        t.add(tool("fx_type", "Type text character by character (KEY_PRESSED/KEY_TYPED/KEY_RELEASED) at the end of a text input.",
                type, "selector", "text"));

        Map<String, Object> key = selectorProps("Target node (optional, default: focused node)");
        key.put("key", prop("string", "KeyCode name, e.g. ENTER, TAB, ESCAPE, F2"));
        key.put("modifiers", prop("string", "CTRL, SHIFT, ALT, META (comma separated)"));
        key.put("window", prop("string", "Window index or title:<text> for the focused node"));
        t.add(tool("fx_press_key", "Send a key press/release, optionally with modifiers. Without a selector the focused node "
                + "(or the scene root) gets the event, so ESCAPE/ENTER also reach dialog and accelerator handlers.", key, "key"));

        Map<String, Object> select = selectorProps("Target node");
        select.put("value", prop("string", "Item text or table row text ('cell | cell')"));
        select.put("index", prop("integer", "Item or row index"));
        t.add(tool("fx_select", "Select an item in a ComboBox, ChoiceBox, ListView, TableView, TreeView or TreeTableView, "
                + "or a tab in a TabPane.", select, "selector"));

        Map<String, Object> expanded = selectorProps("TreeView or TreeTableView node");
        expanded.put("value", prop("string", "Item text or row text ('cell | cell')"));
        expanded.put("index", prop("integer", "Visible row index of the item"));
        expanded.put("expanded", prop("boolean", "true to expand (default), false to collapse"));
        t.add(tool("fx_set_expanded", "Expand or collapse an item of a TreeView/TreeTableView by item or row text, or by "
                + "visible row index. Parents are expanded automatically first.", expanded, "selector"));

        Map<String, Object> value = selectorProps("Target node");
        value.put("value", prop("string", "New value"));
        t.add(tool("fx_set_value", "Set the value of a Slider, Spinner, DatePicker (ISO date) or ColorPicker (e.g. #ff0000).",
                value, "selector", "value"));

        Map<String, Object> scroll = selectorProps("Target node");
        scroll.put("amount", prop("number", "Wheel steps, default 1 (negative = up)"));
        t.add(tool("fx_scroll", "Scroll a scrollable node (ScrollPane, ListView, TableView, TreeView) by wheel steps.",
                scroll, "selector"));

        Map<String, Object> checked = selectorProps("Target node");
        checked.put("checked", prop("boolean", "Desired state"));
        t.add(tool("fx_set_checked", "Set a CheckBox/ToggleButton/RadioButton to checked or unchecked (fires its action if it changes).",
                checked, "selector", "checked"));

        Map<String, Object> menu = selectorProps("MenuBar node (optional, default: first MenuBar)");
        menu.put("menu", prop("string", "Text of the top-level menu"));
        menu.put("item", prop("string", "Text of the menu item"));
        menu.put("path", prop("string", "Full path, e.g. File > Export > PDF"));
        t.add(tool("fx_click_menu", "Fire a menu entry without opening the popup: pick the top-level menu and item by text, "
                + "or use path for nested submenus. Leave item out to just open the menu.", menu));

        t.add(tool("fx_robot_click", "Move the REAL mouse and click at scene coordinates of a window. Use only when there is no "
                + "node to address (e.g. inside a WebView or canvas).",
                props("window", "string", "Window index or title:<text>", "x", "number", "Scene x", "y", "number", "Scene y"),
                "x", "y"));
        t.add(tool("fx_robot_drag", "Drag with the REAL mouse from one scene coordinate to another, e.g. drag and drop or a canvas.",
                props("window", "string", "Window index or title:<text>", "x1", "number", "Start x", "y1", "number", "Start y",
                        "x2", "number", "End x", "y2", "number", "End y"), "x1", "y1", "x2", "y2"));

        Map<String, Object> shot = selectorProps("Only this node (optional)");
        shot.put("window", prop("string", "Window index or title:<text> (default: focused window)"));
        shot.put("x", prop("number", "Optional crop X"));
        shot.put("y", prop("number", "Optional crop Y"));
        shot.put("width", prop("number", "Optional crop width"));
        shot.put("height", prop("number", "Optional crop height"));
        t.add(tool("fx_screenshot", "Take a PNG screenshot of a window or a single node, optionally cropped to a region.", shot));

        Map<String, Object> diff = selectorProps("Only this node (optional)");
        diff.put("window", prop("string", "Window index or title:<text>"));
        diff.put("baseline", prop("string", "Path to the baseline PNG (created if missing or update=true)"));
        diff.put("tolerance", prop("number", "Allowed fraction of differing pixels, default 0.01"));
        diff.put("update", prop("boolean", "Write the current screenshot as the new baseline"));
        diff.put("saveDiff", prop("string", "Optional path for a red/black diff PNG"));
        t.add(tool("fx_screenshot_diff", "Compare a screenshot with a baseline PNG (pixel diff with tolerance). Use it for "
                + "visual regression; passing checks are recorded and replayed.", diff, "baseline"));

        Map<String, Object> wait = selectorProps("Target node");
        wait.put("timeoutMs", prop("integer", "Default 5000"));
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
        t.add(tool("fx_wait_for", "Wait until a node matches the selector and the optional state checks.",
                wait, "selector"));
        t.add(tool("fx_wait_for_window", "Wait until a window with a matching title exists.",
                props("title", "string", "Window title (contains, case-insensitive)", "timeoutMs", "integer", "Default 5000"),
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
        t.add(tool("fx_assert", "Check a node's state: exists, text, value, count, visible, enabled, checked, selection, "
                + "rowCount, focus, progress, tooltip and accessible text. Passing assertions are recorded and replayed.",
                assertion, "selector"));

        t.add(tool("fx_batch", "Run several tool calls in one request, sequentially. 'calls' is a list of "
                + "{tool: string, args: object}; each call is executed like a normal tool call and recorded during recording.",
                props("calls", "array", "List of {tool, args} objects"), "calls"));

        t.add(tool("fx_record_start", "Start recording a replayable plan. Captures mutating tools and passing fx_assert "
                + "calls; the port argument is not recorded.",
                props("name", "string", "Plan name (default: recording)")));
        t.add(tool("fx_record_stop", "Stop recording and return the JSON plan, or write it to a file when 'file' is given.",
                props("file", "string", "Target JSON file (optional)", "name", "string", "Plan name override")));
        t.add(tool("fx_replay", "Replay a recorded plan against the running app. Stops at the first failing step by default "
                + "and reports passed/failed steps. Supports ${variables}, per-step retries, failure screenshots and JUnit XML. "
                + "Also available as CLI: --replay plan.json.",
                props("file", "string", "Plan JSON file", "stopOnError", "boolean", "Default true",
                        "delayMs", "integer", "Delay between steps in ms (default 300)",
                        "variables", "object", "Values for ${name} placeholders",
                        "retries", "integer", "Retries per step (default 0)",
                        "screenshotOnFailure", "boolean", "Save fxpilot/failure-step-N.png",
                        "junitReport", "string", "Optional JUnit XML report path")));

        t.add(tool("fx_exec", "Run an extended FxPilot operation. Use this for operations without a dedicated tool. "
                + "The args object contains the operation parameters.",
                props("op", "string", "Operation name, e.g. tableCell, propertyGet, webQuery", "args", "object",
                        "Operation arguments"), "op"));
        t.add(tool("fx_hotkey", "Send a key combination such as CTRL+S, CTRL+SHIFT+T or ALT+F4 to the focused node.",
                props("keys", "string", "Key combination", "window", "string", "Window index or title:<text>"), "keys"));
        t.add(tool("fx_clipboard", "Read or write the system clipboard.",
                props("action", "string", "get or set", "text", "string", "Text for set"), "action"));
        t.add(tool("fx_wheel", "Fire a real ScrollEvent (mouse wheel) on a node, e.g. for custom scroll handlers.",
                props("selector", "string", "Target node", "deltaX", "number", "Horizontal delta, default 0",
                        "deltaY", "number", "Vertical delta, default 0", "modifiers", "string", "CTRL, SHIFT, ALT, META"),
                "selector"));
        t.add(tool("fx_scroll_to", "Scroll a list/table/tree to a row, a ScrollPane to a target node, or set a ScrollBar value.",
                props("selector", "string", "Scrollable control", "index", "integer", "Row/index",
                        "row", "integer", "Alias for index", "target", "string", "Target node inside a ScrollPane",
                        "vvalue", "number", "ScrollPane vertical value 0..1", "hvalue", "number",
                        "ScrollPane horizontal value 0..1", "value", "number", "ScrollBar value"), "selector"));
        t.add(tool("fx_drag", "Drag with synthetic mouse events from one node to another (drag and drop).",
                props("selector", "string", "Source node", "target", "string", "Target node", "targetWithin", "string",
                        "Limit target search", "targetNth", "string", "Target match index", "steps", "integer",
                        "Intermediate move steps, default 10"), "selector", "target"));
        t.add(tool("fx_focus", "Move keyboard focus to a node.",
                props("selector", "string", "Target node"), "selector"));
        t.add(tool("fx_popup", "Open or close the popup of a ComboBox, ChoiceBox, DatePicker, ColorPicker or MenuButton.",
                props("selector", "string", "Target control", "action", "string", "open or close"),
                "selector"));
        t.add(tool("fx_step", "Increment or decrement a Slider, Spinner, DatePicker or ScrollBar.",
                props("selector", "string", "Target control", "amount", "number", "Step amount, default 1"),
                "selector"));
        t.add(tool("fx_text", "Text editing: clear, append, selectAll, selectRange, caret, replace, copy, paste or cut.",
                props("selector", "string", "Text input", "action", "string", "Text action", "value", "string", "New text",
                        "start", "integer", "Start offset", "end", "integer", "End offset"), "selector", "action"));
        t.add(tool("fx_select_rows", "Select several rows/items in a ListView, TableView, TreeView or TreeTableView.",
                props("selector", "string", "Target control", "indices", "string", "Comma/space separated indices"),
                "selector", "indices"));
        t.add(tool("fx_selection", "Selection operations: rows (indices), all or clear.",
                props("selector", "string", "Target control", "action", "string", "rows, all or clear",
                        "indices", "string", "Comma/space separated indices"), "selector", "action"));
        t.add(tool("fx_table", "TableView/TreeTableView operations: cell read, edit, set, sort, column, select or scroll.",
                props("selector", "string", "Table control", "action", "string",
                        "cell, edit, cellSet, sort, column, select, scroll", "row", "integer", "Row index",
                        "column", "string", "Column text or index", "value", "string", "Value",
                        "order", "string", "ASCENDING or DESCENDING"),
                "selector", "action"));
        t.add(tool("fx_tree", "TreeView/TreeTableView operations: expandAll, collapseAll, expand or collapse.",
                props("selector", "string", "Tree control", "action", "string", "expandAll, collapseAll, expand, collapse",
                        "value", "string", "Item text", "index", "string", "Visible row index"), "selector", "action"));
        t.add(tool("fx_tab", "TabPane operations: select, close or move a tab.",
                props("selector", "string", "TabPane", "action", "string", "select, close or move",
                        "tab", "string", "Tab text", "index", "integer", "Tab index", "to", "integer",
                        "Target index for move"), "selector", "action"));
        t.add(tool("fx_accordion", "Expand a TitledPane in an Accordion.",
                props("selector", "string", "Accordion", "pane", "string", "Pane text", "index", "integer",
                        "Pane index"), "selector"));
        t.add(tool("fx_pagination", "Pagination operations: set, next or prev.",
                props("selector", "string", "Pagination", "action", "string", "set, next or prev",
                        "page", "integer", "Page index"), "selector", "action"));
        t.add(tool("fx_menu", "Fire a menu entry by path, including nested submenus.",
                props("selector", "string", "MenuBar (default: first)", "path", "string", "e.g. File > Export > PDF"),
                "path"));
        t.add(tool("fx_context_menu", "Request a context menu on a node and optionally fire an item from it.",
                props("selector", "string", "Target node", "item", "string", "Menu item text (optional)"), "selector"));
        t.add(tool("fx_window", "Window actions: focus, close, iconify, restore, maximize, unmaximize, move, resize, accept, dismiss.",
                props("window", "string", "Window index or title:<text>", "action", "string", "Window action",
                        "x", "number", "X", "y", "number", "Y", "width", "number", "Width", "height", "number", "Height"),
                "action"));
        t.add(tool("fx_property", "Read or write a JavaFX bean property or a value from Node.getProperties().",
                props("selector", "string", "Target node", "action", "string", "get or set", "name", "string",
                        "Property name", "value", "string", "New value"), "selector", "action", "name"));
        t.add(tool("fx_web", "WebView bridge: execute JavaScript, query an element, click it or read the HTML.",
                props("selector", "string", "WebView", "action", "string", "execute, query, click or html",
                        "script", "string", "JavaScript", "css", "string", "CSS selector", "property", "string",
                        "DOM property, default outerHTML"), "selector", "action"));
        t.add(tool("fx_chart", "Read chart title, series and data points.",
                props("selector", "string", "Chart node"), "selector"));
        t.add(tool("fx_canvas", "Canvas pixel read or synthetic click at local coordinates.",
                props("selector", "string", "Canvas", "action", "string", "pixel or click", "x", "number", "X",
                        "y", "number", "Y"), "selector", "action"));
        t.add(tool("fx_media", "MediaView actions: play, pause, stop, seek, volume, mute, rate or status.",
                props("selector", "string", "MediaView", "action", "string", "Media action", "value", "string",
                        "Action value"), "selector", "action"));
        t.add(tool("fx_image", "Save the image of an ImageView as a PNG file.",
                props("selector", "string", "ImageView", "file", "string", "Target PNG file"), "selector", "file"));
        t.add(tool("fx_tooltip", "Read the tooltip text of a control.",
                props("selector", "string", "Target node"), "selector"));
        t.add(tool("fx_idle", "Wait for the FX thread to process queued work (best effort).", props()));
        t.add(tool("fx_retry", "Call a tool repeatedly until it succeeds or the attempt limit is reached.",
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

    private static Map<String, Object> callTool(String name, Map<String, Object> a) throws Exception {
        if (name == null) {
            throw new RpcError(-32602, "tool name missing");
        }
        if ("fx_batch".equals(name)) {
            return batch(a);
        }
        if (name.startsWith("fx_record") || "fx_replay".equals(name) || "fx_assert".equals(name)) {
            return recordTool(name, a);
        }
        Map<String, Object> result = invoke(name, a, baseUrl(a.get("port")));
        recordStep(name, a, result);
        return result;
    }

    private static Map<String, Object> invoke(String name, Map<String, Object> a, String base) throws Exception {
        try {
            switch (name) {
                case "fx_health": return text(get("/health", a, base));
                case "fx_list_windows": return text(get("/windows", a, base));
                case "fx_get_tree": return text(get("/tree", withSelector(a, "window", "full", "maxDepth"), base));
                case "fx_get_view": return text(get("/view", pick(a, "window", "maxDepth", "limit"), base));
                case "fx_get_node": return text(get("/node", withSelector(a), base));
                case "fx_find": return text(get("/matches", withSelector(a, "limit"), base));
                case "fx_click": return exec("click", a, base);
                case "fx_hover": return text(post("/hover", withSelector(a), base));
                case "fx_set_text": return text(post("/setText", withSelector(a, "text"), base));
                case "fx_type": return text(post("/type", withSelector(a, "text"), base));
                case "fx_press_key": return exec("pressKey", a, base);
                case "fx_select": return text(post("/select", withSelector(a, "value", "index"), base));
                case "fx_set_value": return text(post("/value", withSelector(a, "value"), base));
                case "fx_set_expanded": return text(post("/expand", withSelector(a, "value", "index", "expanded"), base));
                case "fx_set_checked": return text(post("/check", withSelector(a, "checked"), base));
                case "fx_scroll": return text(post("/scroll", withSelector(a, "amount"), base));
                case "fx_click_menu": return clickMenu(a, base);
                case "fx_robot_click": return text(post("/robotClick", pick(a, "window", "x", "y"), base));
                case "fx_robot_drag": return text(post("/robotDrag", pick(a, "window", "x1", "y1", "x2", "y2"), base));
                case "fx_screenshot": return screenshot(withSelector(a, "window", "x", "y", "width", "height"), base);
                case "fx_screenshot_diff": return screenshotDiff(a, base);
                case "fx_wait_for": return waitFor(a, base);
                case "fx_wait_for_window": return waitForWindow(a, base);
                case "fx_batch": return batch(a);
                case "fx_exec": return execGeneric(a, base);
                case "fx_hotkey": return exec("hotkey", a, base);
                case "fx_clipboard": return clipboard(a, base);
                case "fx_wheel": return exec("wheel", a, base);
                case "fx_scroll_to": return exec("scrollTo", a, base);
                case "fx_drag": return exec("drag", a, base);
                case "fx_focus": return exec("focus", a, base);
                case "fx_popup": return exec("popup", a, base);
                case "fx_step": return exec("step", a, base);
                case "fx_text": return exec("textAction", a, base);
                case "fx_select_rows": return exec("selectRows", a, base);
                case "fx_selection": return selection(a, base);
                case "fx_table": return table(a, base);
                case "fx_tree": return exec("tree", a, base);
                case "fx_tab": return exec("tab", a, base);
                case "fx_accordion": return exec("accordion", a, base);
                case "fx_pagination": return exec("pagination", a, base);
                case "fx_menu": return exec("menu", a, base);
                case "fx_context_menu": return contextMenu(a, base);
                case "fx_window": return exec("window", a, base);
                case "fx_property": return property(a, base);
                case "fx_web": return web(a, base);
                case "fx_chart": return exec("chart", a, base);
                case "fx_canvas": return canvas(a, base);
                case "fx_media": return exec("media", a, base);
                case "fx_image": return exec("image", a, base);
                case "fx_tooltip": return exec("tooltip", a, base);
                case "fx_idle": return exec("idle", a, base);
                case "fx_retry": return retryTool(a);
                default: throw new RpcError(-32602, "Unknown tool: " + name);
            }
        } catch (java.net.ConnectException e) {
            return toolError("Cannot reach the app at " + base + ". Start it with the FxPilot endpoint enabled "
                    + "(-Dfxpilot.port=<port> or -javaagent:fxpilot.jar=port=<port>).");
        } catch (HttpFailure e) {
            return toolError(e.getMessage());
        } catch (java.io.IOException e) {
            return toolError("I/O error talking to " + base + ": " + e);
        }
    }

    private static Map<String, Object> retryTool(Map<String, Object> a) throws Exception {
        String tool = str(a.get("tool"));
        if (tool == null || tool.isBlank() || "fx_retry".equals(tool)) {
            throw new RpcError(-32602, "tool must name a non-retry tool");
        }
        Map<String, Object> args = a.get("args") instanceof Map
                ? new LinkedHashMap<>(asMap(a.get("args"))) : new LinkedHashMap<>();
        int attempts = a.get("attempts") instanceof Number ? ((Number) a.get("attempts")).intValue() : 3;
        attempts = Math.max(1, Math.min(20, attempts));
        long delay = a.get("delayMs") instanceof Number ? ((Number) a.get("delayMs")).longValue() : 300;
        Map<String, Object> result = null;
        for (int i = 0; i < attempts; i++) {
            result = callTool(tool, args);
            if (!Boolean.TRUE.equals(result.get("isError"))) {
                return result;
            }
            if (i < attempts - 1) {
                Thread.sleep(Math.max(0, delay));
            }
        }
        return result;
    }

    private static Map<String, Object> exec(String op, Map<String, Object> a, String base) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>(a);
        args.put("op", op);
        return execRaw(args, base);
    }

    private static Map<String, Object> execGeneric(Map<String, Object> a, String base) throws Exception {
        String op = str(a.get("op"));
        if (op == null || op.isBlank()) {
            throw new RpcError(-32602, "op is required");
        }
        Map<String, Object> args = a.get("args") instanceof Map
                ? new LinkedHashMap<>(asMap(a.get("args"))) : new LinkedHashMap<>();
        args.put("op", op);
        if (a.get("port") != null) {
            args.put("port", a.get("port"));
        }
        return execRaw(args, base);
    }

    private static Map<String, Object> execRaw(Map<String, Object> args, String base) throws Exception {
        String op = str(args.get("op"));
        return text(EXEC_READ_OPS.contains(op) ? get("/exec", args, base) : post("/exec", args, base));
    }

    private static Map<String, Object> clipboard(Map<String, Object> a, String base) throws Exception {
        String action = str(a.get("action"));
        return exec("set".equalsIgnoreCase(action) ? "clipboardSet" : "clipboardGet", a, base);
    }

    private static Map<String, Object> selection(Map<String, Object> a, String base) throws Exception {
        String action = str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        switch (action) {
            case "rows": return exec("selectRows", a, base);
            case "all": return exec("selectAll", a, base);
            case "clear": return exec("clearSelection", a, base);
            default: throw new RpcError(-32602, "Unknown selection action: " + action);
        }
    }

    private static Map<String, Object> table(Map<String, Object> a, String base) throws Exception {
        String action = str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        switch (action) {
            case "cell": return exec("tableCell", a, base);
            case "edit": return exec("tableEdit", a, base);
            case "cellSet": return exec("tableCellSet", a, base);
            case "sort": return exec("tableSort", a, base);
            case "column": return exec("tableColumn", a, base);
            case "select": return exec("tableSelect", a, base);
            case "scroll": return exec("tableScroll", a, base);
            default: throw new RpcError(-32602, "Unknown table action: " + action);
        }
    }

    private static Map<String, Object> property(Map<String, Object> a, String base) throws Exception {
        String action = str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        return exec("set".equalsIgnoreCase(action) ? "propertySet" : "propertyGet", a, base);
    }

    private static Map<String, Object> web(Map<String, Object> a, String base) throws Exception {
        String action = str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        switch (action) {
            case "execute": return exec("webExecute", a, base);
            case "query": return exec("webQuery", a, base);
            case "click": return exec("webClick", a, base);
            case "html": return exec("webHtml", a, base);
            default: throw new RpcError(-32602, "Unknown web action: " + action);
        }
    }

    private static Map<String, Object> canvas(Map<String, Object> a, String base) throws Exception {
        String action = str(a.get("action"));
        if (action == null) {
            throw new RpcError(-32602, "action is required");
        }
        return exec("click".equalsIgnoreCase(action) ? "canvasClick" : "canvasPixel", a, base);
    }

    private static Map<String, Object> clickMenu(Map<String, Object> a, String base) throws Exception {
        Map<String, Object> args = new LinkedHashMap<>(a);
        if (a.get("path") == null) {
            String menu = str(a.get("menu"));
            if (menu == null || menu.isBlank()) {
                throw new RpcError(-32602, "menu or path is required");
            }
            String path = menu;
            String item = str(a.get("item"));
            if (item != null && !item.isBlank()) {
                path += " > " + item;
            }
            args.put("path", path);
            args.remove("menu");
            args.remove("item");
        }
        return exec("menu", args, base);
    }

    private static Map<String, Object> contextMenu(Map<String, Object> a, String base) throws Exception {
        Map<String, Object> first = exec("contextMenu", a, base);
        if (Boolean.TRUE.equals(first.get("isError")) || a.get("item") == null) {
            return first;
        }
        Thread.sleep(150);
        Map<String, Object> args = new LinkedHashMap<>(a);
        args.remove("selector");
        args.remove("within");
        args.remove("nth");
        return exec("contextMenuItem", args, base);
    }

    private static Map<String, Object> waitForWindow(Map<String, Object> a, String base) throws Exception {
        String title = str(a.get("title"));
        if (title == null || title.isBlank()) {
            throw new RpcError(-32602, "title is required");
        }
        long timeout = a.get("timeoutMs") instanceof Number ? ((Number) a.get("timeoutMs")).longValue() : 5000;
        long end = System.currentTimeMillis() + Math.max(0, timeout);
        String last = "";
        while (true) {
            last = get("/windows", a, base);
            Object parsed = Json.parse(last);
            if (parsed instanceof List) {
                for (Object item : (List<?>) parsed) {
                    Map<String, Object> window = asMap(item);
                    String windowTitle = str(window.get("title"));
                    if (windowTitle != null && windowTitle.toLowerCase(java.util.Locale.ROOT)
                            .contains(title.toLowerCase(java.util.Locale.ROOT))) {
                        return text(Json.write(window));
                    }
                }
            }
            if (System.currentTimeMillis() >= end) {
                return toolError("Timeout after " + timeout + " ms waiting for window '" + title + "'. Last: " + last);
            }
            Thread.sleep(200);
        }
    }

    /** selector/within/nth plus the named extra arguments, skipping absent ones. */
    private static Map<String, Object> withSelector(Map<String, Object> a, String... extra) {
        Map<String, Object> m = pick(a, "selector", "within", "nth");
        for (String k : extra) {
            if (a.get(k) != null) {
                m.put(k, a.get(k));
            }
        }
        return m;
    }

    private static Map<String, Object> batch(Map<String, Object> a) throws Exception {
        Object raw = a.get("calls");
        if (!(raw instanceof List)) {
            throw new RpcError(-32602, "calls must be an array of {tool, args} objects");
        }
        List<?> calls = (List<?>) raw;
        StringBuilder log = new StringBuilder();
        int failed = 0;
        for (int i = 0; i < calls.size(); i++) {
            Map<String, Object> call = asMap(calls.get(i));
            String tool = str(call.get("tool"));
            Map<String, Object> args = call.get("args") instanceof Map
                    ? new LinkedHashMap<>(asMap(call.get("args")))
                    : new LinkedHashMap<>();
            if (a.get("port") != null && args.get("port") == null) {
                args.put("port", a.get("port"));
            }
            Map<String, Object> result;
            try {
                result = "fx_batch".equals(tool)
                        ? toolError("nested fx_batch is not supported")
                        : callTool(tool, args);
            } catch (Exception e) {
                result = toolError(tool + " failed: " + e);
            }
            boolean ok = !Boolean.TRUE.equals(result.get("isError"));
            if (!ok) {
                failed++;
            }
            log.append(i + 1).append(' ').append(tool).append(": ").append(ok ? "ok" : "FAIL").append('\n');
            if (!ok) {
                log.append("  ").append(textOf(result)).append('\n');
            }
        }
        String summary = "Batch: " + calls.size() + " call(s), failed=" + failed + "\n" + log;
        return failed == 0 ? text(summary) : toolError(summary);
    }

    /** Default endpoint, or 127.0.0.1 on the port passed by the caller (for several apps at once). */
    private static String baseUrl(Object port) {
        if (port == null) {
            return BASE_URL;
        }
        long p;
        try {
            p = port instanceof Number ? ((Number) port).longValue() : Long.parseLong(String.valueOf(port).trim());
        } catch (NumberFormatException e) {
            throw new RpcError(-32602, "port must be an integer");
        }
        if (p < 1 || p > 65535) {
            throw new RpcError(-32602, "port must be between 1 and 65535");
        }
        return "http://127.0.0.1:" + p;
    }

    private static Map<String, Object> screenshot(Map<String, Object> a, String base) throws Exception {
        HttpResponse<byte[]> r = HTTP.send(request("/screenshot", a, false, base), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw failure(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        }
        Map<String, Object> img = new LinkedHashMap<>();
        img.put("type", "image");
        img.put("data", Base64.getEncoder().encodeToString(r.body()));
        img.put("mimeType", "image/png");
        return Map.of("content", List.of(img));
    }

    private static Map<String, Object> screenshotDiff(Map<String, Object> a, String base) throws Exception {
        Object baselineArg = a.get("baseline");
        if (baselineArg == null || String.valueOf(baselineArg).isBlank()) {
            throw new RpcError(-32602, "baseline is required (path to a PNG file)");
        }
        Path baseline = Path.of(String.valueOf(baselineArg));
        boolean update = Boolean.TRUE.equals(a.get("update"));
        double tolerance = a.get("tolerance") instanceof Number ? ((Number) a.get("tolerance")).doubleValue() : 0.01;
        HttpResponse<byte[]> r = HTTP.send(request("/screenshot", withSelector(a, "window"), false, base),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) {
            throw failure(r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        }
        BufferedImage current = ImageIO.read(new ByteArrayInputStream(r.body()));
        if (current == null) {
            return toolError("Screenshot is not a readable PNG");
        }
        if (update || !Files.exists(baseline)) {
            if (baseline.getParent() != null) {
                Files.createDirectories(baseline.getParent());
            }
            ImageIO.write(current, "png", baseline.toFile());
            return text((update ? "UPDATED" : "BASELINE CREATED") + " " + baseline.toAbsolutePath());
        }
        BufferedImage reference = ImageIO.read(baseline.toFile());
        if (reference == null) {
            return toolError("Cannot read baseline PNG " + baseline);
        }
        if (reference.getWidth() != current.getWidth() || reference.getHeight() != current.getHeight()) {
            return toolError("FAIL size " + current.getWidth() + "x" + current.getHeight()
                    + " differs from baseline " + reference.getWidth() + "x" + reference.getHeight());
        }
        int width = current.getWidth();
        int height = current.getHeight();
        int diffPixels = 0;
        BufferedImage diffImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                boolean changed = channelDiff(current.getRGB(x, y), reference.getRGB(x, y)) > 8;
                if (changed) {
                    diffPixels++;
                }
                diffImage.setRGB(x, y, changed ? 0xFFFF0000 : 0x22000000);
            }
        }
        double ratio = (double) diffPixels / ((double) width * height);
        String diffPath = str(a.get("saveDiff"));
        if (diffPath != null && !diffPath.isBlank()) {
            Path p = Path.of(diffPath);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            ImageIO.write(diffImage, "png", p.toFile());
        }
        String summary = (ratio <= tolerance ? "PASS" : "FAIL") + " diff="
                + String.format(java.util.Locale.ROOT, "%.4f", ratio) + " (" + diffPixels + " px), tolerance="
                + tolerance + ", baseline=" + baseline;
        return ratio <= tolerance ? text(summary) : toolError(summary);
    }

    private static int channelDiff(int a, int b) {
        int alpha = Math.abs(((a >> 24) & 0xFF) - ((b >> 24) & 0xFF));
        int red = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int green = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int blue = Math.abs((a & 0xFF) - (b & 0xFF));
        return Math.max(Math.max(alpha, red), Math.max(green, blue));
    }

    private static Map<String, Object> waitFor(Map<String, Object> a, String base) throws Exception {
        long timeout = a.get("timeoutMs") instanceof Number ? ((Number) a.get("timeoutMs")).longValue() : 5000;
        long started = System.currentTimeMillis();
        long end = started + Math.max(0, timeout);
        List<String> problems = List.of("no attempt yet");
        while (true) {
            try {
                problems = evaluate(a).problems;
            } catch (java.io.IOException e) {
                problems = List.of("app not reachable at " + base + " (" + e.getMessage() + ")");
            }
            if (problems.isEmpty()) {
                return text("PASS " + a.get("selector") + " after " + (System.currentTimeMillis() - started) + " ms");
            }
            if (System.currentTimeMillis() >= end) {
                break;
            }
            Thread.sleep(200);
        }
        return toolError("Timeout after " + timeout + " ms: " + String.join("; ", problems));
    }

    // ---------------------------------------------------------------- record, assert, replay

    private static Map<String, Object> recordTool(String name, Map<String, Object> a) throws Exception {
        switch (name) {
            case "fx_record_start":
                return startRecording(a);
            case "fx_record_stop":
                return stopRecording(a);
            case "fx_replay":
                return replay(a);
            case "fx_assert":
                return assertNode(a);
            default:
                throw new RpcError(-32602, "Unknown tool: " + name);
        }
    }

    private static Map<String, Object> startRecording(Map<String, Object> a) {
        if (recording != null) {
            return toolError("Already recording '" + recordingName + "'. Call fx_record_stop first.");
        }
        Object name = a.get("name");
        recording = new ArrayList<>();
        recordingName = name == null || String.valueOf(name).isBlank() ? "recording" : String.valueOf(name);
        recordingStartedAt = Instant.now().toString();
        return text("Recording '" + recordingName + "' started. Mutating tools and passing fx_assert calls are "
                + "captured; fx_record_stop returns or writes the JSON plan.");
    }

    private static Map<String, Object> stopRecording(Map<String, Object> a) throws Exception {
        if (recording == null) {
            return toolError("Not recording. Call fx_record_start first.");
        }
        List<Object> steps = recording;
        recording = null;
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("fxpilot", VERSION);
        plan.put("name", recordingName);
        plan.put("createdAt", recordingStartedAt);
        plan.put("steps", steps);
        Object override = a.get("name");
        if (override != null && !String.valueOf(override).isBlank()) {
            plan.put("name", String.valueOf(override));
        }
        String json = Json.write(plan);
        Object file = a.get("file");
        if (file == null || String.valueOf(file).isBlank()) {
            return text(json);
        }
        Path path = Path.of(String.valueOf(file));
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, json, StandardCharsets.UTF_8);
        return text("Recording '" + plan.get("name") + "' stopped: " + steps.size()
                + " step(s) written to " + path.toAbsolutePath());
    }

    private static void recordStep(String tool, Map<String, Object> args, Map<String, Object> result) {
        if (recording == null || Boolean.TRUE.equals(result.get("isError")) || !RECORDABLE.contains(tool)) {
            return;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", tool);
        step.put("args", copyArgs(args));
        recording.add(step);
    }

    private static void recordAssert(Map<String, Object> args) {
        if (recording == null) {
            return;
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", "fx_assert");
        step.put("args", copyArgs(args));
        recording.add(step);
    }

    /** Recorded plans must be portable, so the target port is not part of the plan. */
    private static Map<String, Object> copyArgs(Map<String, Object> args) {
        Map<String, Object> copy = new LinkedHashMap<>(args);
        copy.remove("port");
        return copy;
    }

    private static Map<String, Object> assertNode(Map<String, Object> a) throws Exception {
        Object selector = a.get("selector");
        if (selector == null || String.valueOf(selector).isBlank()) {
            throw new RpcError(-32602, "selector is required");
        }
        Evaluation e;
        try {
            e = evaluate(a);
        } catch (java.io.IOException ex) {
            return toolError("Cannot reach the app at " + baseUrl(a.get("port")) + ": " + ex);
        }
        if (!e.problems.isEmpty()) {
            return toolError("FAIL " + selector + ": " + String.join("; ", e.problems));
        }
        StringBuilder pass = new StringBuilder("PASS ").append(selector);
        if (e.count != null) {
            pass.append(" count=").append(e.count);
        }
        String expectedText = str(a.get("text"));
        String expectedValue = str(a.get("value"));
        if (expectedText != null) {
            pass.append(" text=\"").append(expectedText).append('"');
        }
        if (expectedValue != null) {
            pass.append(" value=\"").append(expectedValue).append('"');
        }
        Map<String, Object> result = text(pass.toString());
        recordAssert(a);
        return result;
    }

    private static final class Evaluation {
        final List<String> problems;
        final Integer count;

        Evaluation(List<String> problems, Integer count) {
            this.problems = problems;
            this.count = count;
        }
    }

    /** Runs the selector/count/state checks shared by fx_assert and fx_wait_for. */
    private static Evaluation evaluate(Map<String, Object> a) throws Exception {
        Object selector = a.get("selector");
        if (selector == null || String.valueOf(selector).isBlank()) {
            throw new RpcError(-32602, "selector is required");
        }
        String base = baseUrl(a.get("port"));
        String expectedText = str(a.get("text"));
        String expectedValue = str(a.get("value"));
        boolean hasState = expectedText != null || expectedValue != null
                || a.get("visible") != null || a.get("enabled") != null || a.get("checked") != null
                || a.get("selected") != null || a.get("selectedIndex") != null || a.get("selectedIndices") != null
                || a.get("rowCount") != null || a.get("focused") != null || a.get("progress") != null
                || a.get("tooltip") != null || a.get("accessibleText") != null || a.get("property") != null;
        boolean expectExists = !Boolean.FALSE.equals(a.get("exists"));
        List<String> problems = new ArrayList<>();
        Integer actualCount = null;

        Object countArg = a.get("count");
        if (countArg != null) {
            int want;
            try {
                want = Integer.parseInt(String.valueOf(countArg).trim());
            } catch (NumberFormatException e) {
                throw new RpcError(-32602, "count must be an integer");
            }
            HttpResponse<String> c = HTTP.send(request("/matches", withSelector(a), false, base),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (c.statusCode() != 200) {
                problems.add("HTTP " + c.statusCode() + ": " + c.body());
            } else {
                Object count = asMap(Json.parse(c.body())).get("count");
                actualCount = count instanceof Number ? ((Number) count).intValue() : null;
                if (actualCount == null || actualCount != want) {
                    problems.add("count expected " + want + " but was " + actualCount);
                }
            }
        }

        boolean needNode = hasState || a.get("exists") != null || countArg == null;
        if (countArg != null && Integer.valueOf(0).equals(actualCount) && hasState) {
            problems.add("count is 0, so there is no node to check");
            needNode = false;
        }
        if (needNode) {
            HttpResponse<String> r = HTTP.send(request("/node", withSelector(a), false, base),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() == 404) {
                if (expectExists) {
                    problems.add("node does not exist");
                }
            } else if (r.statusCode() != 200) {
                problems.add("HTTP " + r.statusCode() + ": " + r.body());
            } else if (a.get("exists") != null && !expectExists) {
                problems.add("node exists but should not");
            } else if (hasState) {
                checkState(a, asMap(Json.parse(r.body())), expectedText, expectedValue, problems);
            }
        }
        if (a.get("property") != null) {
            Map<String, Object> args = new LinkedHashMap<>(a);
            args.put("op", "propertyGet");
            args.put("name", String.valueOf(a.get("property")));
            HttpResponse<String> pr = HTTP.send(request("/exec", args, false, base),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (pr.statusCode() != 200) {
                problems.add("property HTTP " + pr.statusCode() + ": " + pr.body());
            } else {
                Object actual = asMap(Json.parse(pr.body())).get("value");
                String expected = str(a.get("propertyValue"));
                if (expected != null && !sameValue(actual, expected)) {
                    problems.add("property " + a.get("property") + " expected \"" + expected
                            + "\" but was \"" + actual + "\"");
                }
            }
        }
        return new Evaluation(problems, actualCount);
    }

    private static void checkState(Map<String, Object> a, Map<String, Object> node,
            String expectedText, String expectedValue, List<String> problems) {
        if (expectedText != null) {
            String actual = node.get("text") == null ? "" : String.valueOf(node.get("text"));
            if (!actual.equals(expectedText)) {
                problems.add("text expected \"" + expectedText + "\" but was \"" + actual + "\"");
            }
        }
        if (expectedValue != null && !sameValue(node.get("value"), expectedValue)) {
            problems.add("value expected \"" + expectedValue + "\" but was \"" + node.get("value") + "\"");
        }
        if (a.get("visible") != null) {
            boolean want = Boolean.parseBoolean(String.valueOf(a.get("visible")));
            boolean actual = Boolean.TRUE.equals(node.get("visible"));
            if (want != actual) {
                problems.add("visible expected " + want + " but was " + actual);
            }
        }
        if (a.get("enabled") != null) {
            boolean want = Boolean.parseBoolean(String.valueOf(a.get("enabled")));
            boolean actual = !Boolean.TRUE.equals(node.get("disabled"));
            if (want != actual) {
                problems.add("enabled expected " + want + " but was " + actual);
            }
        }
        if (a.get("checked") != null) {
            boolean want = Boolean.parseBoolean(String.valueOf(a.get("checked")));
            boolean actual = Boolean.TRUE.equals(node.get("selected"));
            if (want != actual) {
                problems.add("checked expected " + want + " but was " + actual);
            }
        }
        if (a.get("selected") != null) {
            boolean want = Boolean.parseBoolean(String.valueOf(a.get("selected")));
            boolean actual = Boolean.TRUE.equals(node.get("selected"));
            if (want != actual) {
                problems.add("selected expected " + want + " but was " + actual);
            }
        }
        if (a.get("selectedIndex") != null) {
            int want = Integer.parseInt(String.valueOf(a.get("selectedIndex")).trim());
            int actual = node.get("selectedIndex") instanceof Number
                    ? ((Number) node.get("selectedIndex")).intValue() : -1;
            if (want != actual) {
                problems.add("selectedIndex expected " + want + " but was " + actual);
            }
        }
        if (a.get("selectedIndices") != null) {
            String want = normalizeIndices(String.valueOf(a.get("selectedIndices")));
            String actual = normalizeIndices(String.valueOf(node.get("selectedIndices")));
            if (!want.equals(actual)) {
                problems.add("selectedIndices expected " + want + " but was " + actual);
            }
        }
        if (a.get("rowCount") != null) {
            int want = Integer.parseInt(String.valueOf(a.get("rowCount")).trim());
            int actual = node.get("rowCount") instanceof Number ? ((Number) node.get("rowCount")).intValue() : -1;
            if (want != actual) {
                problems.add("rowCount expected " + want + " but was " + actual);
            }
        }
        if (a.get("focused") != null) {
            boolean want = Boolean.parseBoolean(String.valueOf(a.get("focused")));
            boolean actual = Boolean.TRUE.equals(node.get("focused"));
            if (want != actual) {
                problems.add("focused expected " + want + " but was " + actual);
            }
        }
        if (a.get("progress") != null) {
            double want = Double.parseDouble(String.valueOf(a.get("progress")));
            double actual = node.get("progress") instanceof Number
                    ? ((Number) node.get("progress")).doubleValue() : Double.NaN;
            if (Math.abs(want - actual) > 1e-6) {
                problems.add("progress expected " + want + " but was " + actual);
            }
        }
        if (a.get("tooltip") != null) {
            String want = String.valueOf(a.get("tooltip"));
            String actual = node.get("tooltip") == null ? "" : String.valueOf(node.get("tooltip"));
            if (!want.equals(actual)) {
                problems.add("tooltip expected \"" + want + "\" but was \"" + actual + "\"");
            }
        }
        if (a.get("accessibleText") != null) {
            String want = String.valueOf(a.get("accessibleText"));
            String actual = node.get("accessibleText") == null ? "" : String.valueOf(node.get("accessibleText"));
            if (!want.equals(actual)) {
                problems.add("accessibleText expected \"" + want + "\" but was \"" + actual + "\"");
            }
        }
    }

    private static String normalizeIndices(String raw) {
        if (raw == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String part : raw.replace("[", "").replace("]", "").split("[,;\\s]+")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return String.join(",", parts);
    }

    private static String readPlan(Path path) throws Exception {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        // Windows editors (and PowerShell) may write a UTF-8 BOM, which is not part of the JSON.
        return !text.isEmpty() && text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    private static boolean sameValue(Object actual, String expected) {
        if (actual instanceof Number) {
            try {
                return Math.abs(((Number) actual).doubleValue() - Double.parseDouble(expected)) < 1e-9;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return actual != null && String.valueOf(actual).equals(expected);
    }

    private static Map<String, Object> replay(Map<String, Object> a) throws Exception {
        Object file = a.get("file");
        if (file == null || String.valueOf(file).isBlank()) {
            throw new RpcError(-32602, "file is required (path to a recorded plan)");
        }
        Path path = Path.of(String.valueOf(file));
        if (!Files.isReadable(path)) {
            return toolError("Cannot read plan file " + path.toAbsolutePath());
        }
        Map<String, Object> plan = asMap(Json.parse(readPlan(path)));
        Object rawSteps = plan.get("steps");
        if (!(rawSteps instanceof List)) {
            return toolError("Plan " + path.getFileName() + " has no steps array");
        }
        List<?> steps = (List<?>) rawSteps;
        boolean stopOnError = !Boolean.FALSE.equals(a.get("stopOnError"));
        boolean screenshotOnFailure = Boolean.TRUE.equals(a.get("screenshotOnFailure"));
        int retries = a.get("retries") instanceof Number ? ((Number) a.get("retries")).intValue() : 0;
        retries = Math.max(0, Math.min(10, retries));
        Map<String, Object> variables = a.get("variables") instanceof Map
                ? new LinkedHashMap<>(asMap(a.get("variables"))) : new LinkedHashMap<>();
        long delayMs = a.get("delayMs") instanceof Number ? ((Number) a.get("delayMs")).longValue() : 300;
        delayMs = Math.max(0, Math.min(10_000, delayMs));
        long started = System.currentTimeMillis();
        StringBuilder failures = new StringBuilder();
        StringBuilder report = new StringBuilder();
        int passed = 0;
        int failed = 0;
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> step = asMap(steps.get(i));
            String tool = str(step.get("tool"));
            Map<String, Object> args = step.get("args") instanceof Map
                    ? new LinkedHashMap<>(asMap(step.get("args")))
                    : new LinkedHashMap<>();
            args = resolveArgs(args, variables);
            if (a.get("port") != null) {
                args.put("port", a.get("port"));
            }
            Map<String, Object> result = null;
            for (int attempt = 0; attempt <= retries; attempt++) {
                try {
                    result = "fx_assert".equals(tool)
                            ? assertNode(args)
                            : invoke(tool == null ? "" : tool, args, baseUrl(args.get("port")));
                } catch (Exception e) {
                    result = toolError(tool + " failed: " + e);
                }
                if (!Boolean.TRUE.equals(result.get("isError"))) {
                    break;
                }
                if (attempt < retries) {
                    Thread.sleep(200);
                }
            }
            boolean failedStep = result == null || Boolean.TRUE.equals(result.get("isError"));
            if (failedStep) {
                failed++;
                String detail = result == null ? "no result" : textOf(result);
                failures.append("  step ").append(i + 1).append(' ').append(tool).append(": ").append(detail).append('\n');
                report.append("      <testcase name=\"step ").append(i + 1).append(' ').append(xml(tool))
                        .append("\" classname=\"fxpilot\"><failure message=\"").append(xml(detail))
                        .append("\"/></testcase>\n");
                if (screenshotOnFailure) {
                    saveFailureScreenshot(args, "fxpilot/failure-step-" + (i + 1) + ".png", baseUrl(args.get("port")));
                }
                if (stopOnError) {
                    break;
                }
            } else {
                passed++;
                report.append("      <testcase name=\"step ").append(i + 1).append(' ').append(xml(tool))
                        .append("\" classname=\"fxpilot\"/>\n");
            }
            if (i < steps.size() - 1 && delayMs > 0) {
                Thread.sleep(delayMs);
            }
        }
        long duration = System.currentTimeMillis() - started;
        String junitReport = str(a.get("junitReport"));
        if (junitReport != null && !junitReport.isBlank()) {
            Path reportPath = Path.of(junitReport);
            if (reportPath.getParent() != null) {
                Files.createDirectories(reportPath.getParent());
            }
            String reportXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<testsuite name=\"fxpilot\" tests=\"" + steps.size() + "\" failures=\"" + failed + "\">\n"
                    + report + "</testsuite>\n";
            Files.writeString(reportPath, reportXml, StandardCharsets.UTF_8);
        }
        String summary = "Replay " + path.getFileName() + ": " + (passed + failed) + "/" + steps.size()
                + " step(s), passed=" + passed + ", failed=" + failed + ", durationMs=" + duration
                + ", status=" + (failed == 0 ? "PASS" : "FAIL");
        return failed == 0 ? text(summary) : toolError(summary + "\n" + failures);
    }

    private static Map<String, Object> resolveArgs(Map<String, Object> args, Map<String, Object> variables) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), variables));
        }
        return resolved;
    }

    private static Object resolveValue(Object value, Map<String, Object> variables) {
        if (value instanceof String) {
            String text = (String) value;
            for (Map.Entry<String, Object> var : variables.entrySet()) {
                text = text.replace("${" + var.getKey() + "}", String.valueOf(var.getValue()));
            }
            return text;
        }
        if (value instanceof Map) {
            return resolveArgs(asMap(value), variables);
        }
        if (value instanceof List) {
            List<Object> list = new ArrayList<>();
            for (Object item : (List<?>) value) {
                list.add(resolveValue(item, variables));
            }
            return list;
        }
        return value;
    }

    private static void saveFailureScreenshot(Map<String, Object> args, String file, String base) {
        try {
            HttpResponse<byte[]> response = HTTP.send(
                    request("/screenshot", withSelector(args, "window"), false, base),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 200) {
                Path path = Path.of(file);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.write(path, response.body());
            }
        } catch (Exception ignored) {
            // A failed screenshot must not hide the original replay failure.
        }
    }

    private static String xml(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** CLI mode: java FxPilotMcpServer.java --replay plan.json [--port N] [--delay MS] [--continue-on-error] */
    private static int replayMain(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java -jar fxpilot.jar --replay <plan.json> "
                    + "[--port <n>] [--delay <ms>] [--continue-on-error] [--retry <n>] "
                    + "[--screenshot-on-failure] [--report <xml>] [--var key=value]");
            return 2;
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("file", args[1]);
        Map<String, Object> variables = new LinkedHashMap<>();
        try {
            for (int i = 2; i < args.length; i++) {
                switch (args[i]) {
                    case "--port":
                        a.put("port", Integer.parseInt(args[++i]));
                        break;
                    case "--delay":
                        a.put("delayMs", Long.parseLong(args[++i]));
                        break;
                    case "--continue-on-error":
                        a.put("stopOnError", false);
                        break;
                    case "--retry":
                        a.put("retries", Integer.parseInt(args[++i]));
                        break;
                    case "--screenshot-on-failure":
                        a.put("screenshotOnFailure", true);
                        break;
                    case "--report":
                        a.put("junitReport", args[++i]);
                        break;
                    case "--var": {
                        String assignment = args[++i];
                        int eq = assignment.indexOf('=');
                        if (eq <= 0) {
                            System.err.println("Invalid --var, use key=value");
                            return 2;
                        }
                        variables.put(assignment.substring(0, eq), assignment.substring(eq + 1));
                        break;
                    }
                    default:
                        break;
                }
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException e) {
            System.err.println("Invalid argument: " + e.getMessage());
            return 2;
        }
        if (!variables.isEmpty()) {
            a.put("variables", variables);
        }
        Map<String, Object> result = replay(a);
        System.out.println(textOf(result));
        return Boolean.TRUE.equals(result.get("isError")) ? 1 : 0;
    }

    private static String textOf(Map<String, Object> result) {
        Object content = result.get("content");
        if (content instanceof List && !((List<?>) content).isEmpty()) {
            Object first = ((List<?>) content).get(0);
            if (first instanceof Map) {
                Object text = ((Map<?, ?>) first).get("text");
                if (text != null) {
                    return String.valueOf(text);
                }
            }
        }
        return String.valueOf(result);
    }

    // ---------------------------------------------------------------- HTTP

    private static String get(String path, Map<String, Object> a, String base) throws Exception {
        return send(request(path, a, false, base));
    }

    private static String post(String path, Map<String, Object> a, String base) throws Exception {
        return send(request(path, a, true, base));
    }

    private static HttpRequest request(String path, Map<String, Object> a, boolean post, String base) {
        String form = form(a);
        HttpRequest.Builder b;
        if (post) {
            b = HttpRequest.newBuilder(URI.create(base + path))
                    .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                    .header("X-FxPilot-Client", CLIENT_HEADER)
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        } else {
            b = HttpRequest.newBuilder(URI.create(base + path + (form.isEmpty() ? "" : "?" + form)))
                    .header("X-FxPilot-Client", CLIENT_HEADER)
                    .GET();
        }
        if (!TOKEN.isEmpty()) {
            b.header("X-FxPilot-Token", TOKEN);
        }
        return b.timeout(Duration.ofSeconds(30)).build();
    }

    private static String send(HttpRequest req) throws Exception {
        HttpResponse<String> r = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() >= 400) {
            throw failure(r.statusCode(), r.body());
        }
        return r.body();
    }

    /** Turns an error body into an HttpFailure that carries the stable FxPilot error code. */
    private static HttpFailure failure(int status, String body) {
        String code = null;
        String message = null;
        try {
            Map<String, Object> e = asMap(Json.parse(body));
            code = str(e.get("code"));
            message = str(e.get("error"));
        } catch (RuntimeException ignored) {
            // not a JSON error body, fall back to the raw text
        }
        return new HttpFailure(status, code, message == null ? body : message);
    }

    private static String form(Map<String, Object> a) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : a.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            Object v = e.getValue();
            String s = v instanceof Double && ((Double) v) == Math.rint((Double) v) ? String.valueOf(((Double) v).longValue()) : String.valueOf(v);
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(s, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, Object> pick(Map<String, Object> a, String... keys) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : keys) {
            if (a.get(k) != null) {
                m.put(k, a.get(k));
            }
        }
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Map<String, Object> text(String s) {
        return Map.of("content", List.of(Map.of("type", "text", "text", s)));
    }

    private static Map<String, Object> toolError(String s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", List.of(Map.of("type", "text", "text", s)));
        m.put("isError", true);
        return m;
    }

    private static Map<String, Object> tool(String name, String description, Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        properties.put("port", Map.of("type", "integer", "description", "Port of the target app on 127.0.0.1 (default from FXPILOT_URL)"));
        schema.put("properties", properties);
        if (required.length > 0) {
            schema.put("required", List.of(required));
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("name", name);
        t.put("description", description);
        t.put("inputSchema", schema);
        return t;
    }

    /** props(name, type, description, name, type, description, ...) */
    private static Map<String, Object> props(String... spec) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 2 < spec.length; i += 3) {
            m.put(spec[i], Map.of("type", spec[i + 1], "description", spec[i + 2]));
        }
        return m;
    }

    private static void reply(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        out.println(Json.write(m));
    }

    private static void replyError(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message == null ? "error" : message));
        out.println(Json.write(m));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (!(o instanceof Map)) {
            throw new RpcError(-32600, "expected JSON object");
        }
        return (Map<String, Object>) o;
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? def : v;
    }

    /** Implementation-Version from the jar manifest, "dev" when running without one. */
    private static String serverVersion() {
        Package pkg = FxPilotMcpServer.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return v == null || v.isBlank() ? "dev" : v;
    }

    static final class RpcError extends RuntimeException {
        final int code;

        RpcError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    static final class HttpFailure extends Exception {
        final int status;
        final String code;

        HttpFailure(int status, String code, String message) {
            super((code == null || code.isEmpty() ? "" : code + ": ") + message);
            this.status = status;
            this.code = code;
        }
    }

    // ---------------------------------------------------------------- JSON

    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String s) {
            Json p = new Json(s);
            p.ws();
            Object v = p.value();
            p.ws();
            if (p.i != s.length()) {
                throw new RpcError(-32700, "trailing characters in JSON");
            }
            return v;
        }

        private Object value() {
            ws();
            if (i >= s.length()) {
                throw new RpcError(-32700, "unexpected end of JSON");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return number();
            }
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                if (s.charAt(i++) != ':') {
                    throw new RpcError(-32700, "expected ':'");
                }
                m.put(k, value());
                ws();
                char c = s.charAt(i++);
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new RpcError(-32700, "expected ',' or '}'");
                }
            }
        }

        private List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(value());
                ws();
                char c = s.charAt(i++);
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw new RpcError(-32700, "expected ',' or ']'");
                }
            }
        }

        private String string() {
            if (s.charAt(i) != '"') {
                throw new RpcError(-32700, "expected string");
            }
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: throw new RpcError(-32700, "bad escape");
                }
            }
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (start == i) {
                throw new RpcError(-32700, "unexpected character '" + s.charAt(i) + "'");
            }
            String n = s.substring(start, i);
            if (n.contains(".") || n.contains("e") || n.contains("E")) {
                return Double.parseDouble(n);
            }
            return Long.parseLong(n);
        }

        private void expect(String word) {
            if (!s.startsWith(word, i)) {
                throw new RpcError(-32700, "expected " + word);
            }
            i += word.length();
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v);
            return sb.toString();
        }

        private static void write(StringBuilder sb, Object v) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof String) {
                String str = (String) v;
                sb.append('"');
                for (int k = 0; k < str.length(); k++) {
                    char c = str.charAt(k);
                    switch (c) {
                        case '"': sb.append("\\\""); break;
                        case '\\': sb.append("\\\\"); break;
                        case '\n': sb.append("\\n"); break;
                        case '\r': sb.append("\\r"); break;
                        case '\t': sb.append("\\t"); break;
                        default:
                            if (c < 0x20) {
                                sb.append(String.format("\\u%04x", (int) c));
                            } else {
                                sb.append(c);
                            }
                    }
                }
                sb.append('"');
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof Map) {
                sb.append('{');
                Iterator<? extends Map.Entry<?, ?>> it = ((Map<?, ?>) v).entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<?, ?> e = it.next();
                    write(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                    if (it.hasNext()) {
                        sb.append(',');
                    }
                }
                sb.append('}');
            } else if (v instanceof List) {
                sb.append('[');
                Iterator<?> it = ((List<?>) v).iterator();
                while (it.hasNext()) {
                    write(sb, it.next());
                    if (it.hasNext()) {
                        sb.append(',');
                    }
                }
                sb.append(']');
            } else {
                write(sb, v.toString());
            }
        }
    }
}
