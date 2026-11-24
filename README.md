# FxPilot

[![build](https://github.com/dbuedenbender/fxpilot/actions/workflows/build.yml/badge.svg)](https://github.com/dbuedenbender/fxpilot/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.dbuedenbender/fxpilot.svg)](https://central.sonatype.com/artifact/io.github.dbuedenbender/fxpilot)

FxPilot lets Claude Code drive a running JavaFX application: read the scene graph, click, enter text, select items and take screenshots. It has two parts:

```
Claude Code ──stdio/MCP──> fxpilot.jar (MCP server) ──HTTP 127.0.0.1:<port>──> fxpilot.jar (inside the app)
```

- `fxpilot.jar` contains both sides: `java -jar fxpilot.jar` starts the MCP server, and `-javaagent:fxpilot.jar` (or a Maven dependency) starts the HTTP endpoint inside the app.

Requirements: JDK 11 or newer, JavaFX 11 or newer. The app only needs `javafx-controls`; `javafx-web` and `javafx-swing` are optional. If JavaFX is missing or older than 11, the endpoint does not start and logs the reason to stderr. With `-javaagent`, the toolkit is usually not running yet when the agent starts, so only the presence of JavaFX is checked there.

> **Modular apps:** the endpoint uses `com.sun.net.httpserver`, which lives in the `jdk.httpserver` module. Classpath launches resolve it automatically, but an app started with `--module-path` and a `module-info.java` needs `requires jdk.httpserver;` (or `--add-modules jdk.httpserver`).

## One-time setup

1. Build the module and install it into the local Maven repository:

   ```bat
   cd C:\path\to\fxpilot
   mvn clean install
   ```

   The jar is then in `target\fxpilot.jar` and in the local Maven repository under `io\github\dbuedenbender\fxpilot\`.

2. Register the MCP server globally for Claude Code (applies to all projects):

   ```bat
   claude mcp add --scope user fxpilot -e FXPILOT_URL=http://127.0.0.1:8765 -- java -jar C:\path\to\fxpilot\target\fxpilot.jar
   ```

   If `java` is not on the PATH, give the full path to a `java.exe`. A JRE 11 or newer is enough.

## Adding it to an app

### Option A: Maven dependency (your own projects)

`pom.xml`:

```xml
<dependency>
    <groupId>io.github.dbuedenbender</groupId>
    <artifactId>fxpilot</artifactId>
    <version>1.0.0</version>
</dependency>
```

In the `Application` class:

```java
import io.github.dbuedenbender.fxpilot.FxPilotServer;

@Override
public void start(Stage stage) throws Exception {
    // ...
    stage.show();
    FxPilotServer.setNotes("Button #deleteAll deletes data in the database, do not click.");
    FxPilotServer.startIfEnabled();
}

@Override
public void stop() throws Exception {
    FxPilotServer.stop();
    super.stop();
}
```

The endpoint is only active with the VM option `-Dfxpilot.port=8765`. With `javafx-maven-plugin`, a separate execution works well:

```xml
<execution>
    <!-- Usage: mvn clean javafx:run@fxpilot -->
    <id>fxpilot</id>
    <configuration>
        <options>
            <option>-Dfxpilot.port=8765</option>
        </options>
    </configuration>
</execution>
```

### Option B: Java agent (no code changes)

For third-party or older apps, one VM option is enough:

```
-javaagent:C:\path\to\fxpilot\target\fxpilot.jar=port=8765
```

With a token: `=port=8765,token=secret`. Pass hints for Claude with `-Dfxpilot.notes="..."`.

## Configuration

| Setting                          | System property                               | Environment variable | Agent argument |
|----------------------------------|-----------------------------------------------|----------------------|----------------|
| Port (enables the endpoint)      | `fxpilot.port`                                | `FXPILOT_PORT`       | `port=`        |
| Token (header `X-FxPilot-Token`) | `fxpilot.token`                               | `FXPILOT_TOKEN`      | `token=`       |
| Hints for Claude                 | `fxpilot.notes` or `FxPilotServer.setNotes()` |                      |                |
| Strict selectors (ambiguous = error) | `fxpilot.strictSelectors`                 | `FXPILOT_STRICT`     |                |
| Read-only (all POSTs rejected)   | `fxpilot.readonly`                            | `FXPILOT_READONLY`   |                |
| Denied selectors (comma list)    | `fxpilot.deny`                                | `FXPILOT_DENY`       |                |

## Several apps at once

Each app gets its own port. By default, the global MCP server talks to the port from `FXPILOT_URL`, but every tool has an optional `port` parameter. So you can tell Claude, for example: "Use the app on port 8766."

## Tools

| Tool                      | Purpose                                                                            |
|---------------------------|------------------------------------------------------------------------------------|
| `fx_health`               | Checks whether the app is reachable; returns window titles and app hints (`notes`) |
| `fx_list_windows`         | All open windows (main window, dialogs, popups)                                    |
| `fx_get_tree`             | Scene graph as JSON (fx:id, text, values, items, rows, state, position)            |
| `fx_get_node`             | State of a single node                                                             |
| `fx_find`                 | All nodes matching a selector, with count (for disambiguation and assertions)       |
| `fx_click`                | Fires a button or simulates a mouse click                                          |
| `fx_hover`                | Fires mouse-enter/move events to trigger hover handlers and `:hover` CSS           |
| `fx_set_text` / `fx_type` | Sets text, or types it character by character (fires `onKeyReleased`)              |
| `fx_press_key`            | Sends a key, e.g. `ENTER`, `TAB`, `ESCAPE`; without selector to the focused node   |
| `fx_select`               | Selects an entry in a ComboBox, ChoiceBox, ListView, TableView, TreeView, TreeTableView or TabPane |
| `fx_set_expanded`         | Expands or collapses an item in a TreeView/TreeTableView                            |
| `fx_set_value`            | Sets a Slider, Spinner, DatePicker (`2026-11-18`) or ColorPicker (`#ff0000`)        |
| `fx_scroll`               | Scrolls a ScrollPane, ListView, TableView or TreeView by wheel steps               |
| `fx_set_checked`          | Sets a CheckBox or toggle                                                          |
| `fx_click_menu`           | Fires a menu entry by menu and item text, without opening the popup                 |
| `fx_screenshot`           | PNG of a window or node                                                            |
| `fx_wait_for`             | Waits until a node matches text/value/count/visible/enabled checks                  |
| `fx_assert`               | Checks node state (text, value, visible, enabled, checked, existence, count)        |
| `fx_batch`                | Runs several tool calls in one request                                             |
| `fx_screenshot_diff`      | Pixel diff against a baseline PNG (visual regression, with tolerance)              |
| `fx_record_start`         | Starts recording a replayable plan                                                 |
| `fx_record_stop`          | Returns or writes the JSON plan                                                    |
| `fx_replay`               | Replays a plan against the app, reports passed/failed steps                        |
| `fx_robot_click`          | Real mouse click at coordinates, only needed for WebView or Canvas                 |
| `fx_robot_drag`           | Real mouse drag between two coordinates (drag and drop, canvas)                    |

Selectors: fx:id (`textField`), CSS (`#button`, `.check-box`, `ComboBox`), `id=customer.name` for a literal fx:id (also IDs containing dots; `id=customer.name@2` picks the third match), `accessible=Save` for accessible text/help, `label=Name` for a `Label` linked with `labelFor`, `ref=VBox[0]/Button[2]` for a path returned by `fx_find`/`fx_get_tree`, `text=Save` for an exact label match (visible nodes are preferred over hidden duplicates), `text*=Sav` for a substring, or `role=button` for a JavaFX type (`button`, `textbox`, `checkbox`, `combobox`, `list`, `table`, `tree`, `tab`, `menu`, `slider`, `spinner`, `datepicker`, `label`, `image`). `within=<selector>` limits the search to a subtree, `nth=<index>` (or `nth=last`) picks a specific match. Windows can be addressed by index or `title:<text>`. Unique `fx:id`s in the FXML files make FxPilot much more reliable; `fx_get_view` returns a compact list of visible actionable nodes with stable `ref` paths for foreign UIs.

### Extended tools

| Tool | Purpose |
|---|---|
| `fx_get_view` | Compact snapshot of visible, actionable nodes with stable refs |
| `fx_hotkey` | Key combinations such as `CTRL+S` or `CTRL+SHIFT+T` |
| `fx_clipboard` | Read or write the system clipboard |
| `fx_wheel` | Real `ScrollEvent` (also for custom scroll handlers) |
| `fx_scroll_to` | Scroll to a row/node, set ScrollPane vvalue/hvalue or ScrollBar value |
| `fx_drag` | Synthetic node-to-node drag and drop |
| `fx_focus` | Move keyboard focus |
| `fx_popup` / `fx_step` | Open/close popups; increment/decrement Slider, Spinner, DatePicker, ScrollBar |
| `fx_text` | Clear, append, select range, caret, replace, copy/paste/cut |
| `fx_select_rows` / `fx_selection` | Multi-select rows, select all or clear selection |
| `fx_table` | Cells, edit/set, sort, columns, selection and scrolling in TableView/TreeTableView |
| `fx_tree` | Expand/collapse one item or all items in TreeView/TreeTableView |
| `fx_tab` / `fx_accordion` / `fx_pagination` | Tabs, accordion panes and pagination |
| `fx_menu` / `fx_context_menu` | Nested menu paths and context menus |
| `fx_window` | Focus, close, iconify, maximize, move and resize windows |
| `fx_property` | Read/write JavaFX bean properties or `Node.getProperties()` values |
| `fx_web` | WebView JavaScript execution, DOM query, click and HTML |
| `fx_chart` | Chart title, series and data points |
| `fx_canvas` | Canvas pixel read and coordinate click |
| `fx_media` | MediaView play/pause/stop/seek/volume/mute/rate |
| `fx_image` | Save an ImageView image as PNG |
| `fx_tooltip` / `fx_idle` | Read tooltips / wait for queued FX work |
| `fx_wait_for_window` | Wait until a window with a matching title exists |
| `fx_retry` | Retry any tool call until it succeeds |
| `fx_exec` | Generic access to the extended operation set |

`fx_click` understands `button` (`PRIMARY`/`SECONDARY`/`MIDDLE`), `mode` (`fire` or `mouse`), `modifiers` and `requireVisible`/`requireEnabled`. `fx_press_key` accepts `modifiers`, `fx_assert` checks selection, row counts, focus, progress, tooltips and arbitrary properties, and `fx_screenshot` accepts a crop region (`x`, `y`, `width`, `height`).

Examples:

```
fx_select(selector="customerTable", value="Sample Inc. | CH")
fx_select(selector="customerTable", index=3)
fx_set_value(selector="deliveryDate", value="2026-12-01")
fx_click_menu(menu="File", item="Save as...")
fx_set_expanded(selector="orderTree", value="Order 1001 | open")
fx_select(selector="orderTree", value="Order 1001 | open")
fx_click(selector="role=button", within="#dialog", nth=1)
fx_wait_for(selector="statusLabel", text="Saved", timeoutMs=5000)
fx_screenshot_diff(selector="#statusPane", baseline="fxpilot/status.png", tolerance=0.005)
```

`fx_batch` runs several calls in one request, which keeps a multi-step interaction compact:

```
fx_batch(calls=[
  {"tool": "fx_set_text", "args": {"selector": "nameField", "text": "Sample Inc."}},
  {"tool": "fx_assert",   "args": {"selector": "nameField", "text": "Sample Inc."}},
  {"tool": "fx_click",    "args": {"selector": "okButton"}}
])
```

Tree tables: `fx_get_node` returns the visible rows (indented, `cell | cell`) and `rowCount`, so a tree that is collapsed only shows what is expanded. `fx_set_expanded` expands the parents automatically, and `fx_select` by row text also opens collapsed branches. If a tree has thousands of rows, `maxDepth` and `full` on `fx_get_tree` keep the output manageable.

### Ambiguous selectors

A selector such as `.button` can match several nodes. Actions then use the first match and add `"matches": 3` and a `warning` to the response, and `fx_find` lists all candidates:

```
fx_find(selector=".button")
fx_assert(selector=".button", count=3)
```

With `-Dfxpilot.strictSelectors=true` (or `FXPILOT_STRICT=true`), ambiguous actions fail instead of guessing:

```json
{"error": "Selector '.button' matches 2 nodes: Button#ok \"OK\", Button#cancel \"Cancel\". Use a more specific selector, nth=<index>, or call fx_find to list them.",
 "code": "NODE_AMBIGUOUS"}
```

## Record & Replay

A driven run can become a regression test. Record the actions while Claude works:

```
fx_record_start(name="checkout")
fx_set_text(selector="nameField", text="Sample Inc.")
fx_assert(selector="nameField", text="Sample Inc.")
fx_click(selector="okButton")
fx_assert(selector="resultLabel", text="Saved")
fx_record_stop(file="fxpilot/checkout.json")
```

Replay the plan against a running app, via the agent or from a normal shell (exit code 0 = pass, 1 = fail):

```bat
java -jar fxpilot.jar --replay fxpilot\checkout.json
java -jar fxpilot.jar --replay fxpilot\checkout.json --port 8766 --delay 500 --continue-on-error
```

Or as an MCP tool: `fx_replay(file="fxpilot/checkout.json")`.

Replay supports variables and CI-friendly reporting:

```bat
java -jar fxpilot.jar --replay fxpilot\checkout.json --var customer="Sample Inc." --retry 3 --screenshot-on-failure --report fxpilot\replay.xml
```

`--var key=value` replaces `${key}` in step arguments, `--retry` retries a failing step, `--screenshot-on-failure` writes `fxpilot/failure-step-N.png`, and `--report` writes a JUnit XML file.

- Recorded are mutating tools (`fx_click`, `fx_set_text`, `fx_select`, `fx_set_value`, `fx_press_key`, ...), `fx_wait_for`, `fx_screenshot_diff` and passing `fx_assert` calls. Reads and plain screenshots are not recorded.
- The plan stores selectors and arguments, not node references, so it survives UI rebuilds as long as the selectors stay valid. If an `fx:id` changes, re-record.
- The `port` argument is not recorded, so one plan runs against any app instance; `--port` overrides the target for replay.
- `fx_robot_click` and `fx_robot_drag` record absolute scene coordinates and are sensitive to window position and display scaling.
- Default delay between steps is 300 ms. Use `--delay 0` / `delayMs: 0` only for fully synchronous UIs.
- Plan format: `{"fxpilot":"1.0","name":"...","createdAt":"...","steps":[{"tool":"fx_click","args":{...}}]}`.
- **Recordings contain the text you type, including passwords.** Do not record credentials and do not commit plans with sensitive data.

## Error codes

Every error response carries a stable `code` next to the human-readable message:

```json
{"error": "No node matches selector 'okButton'", "code": "NODE_NOT_FOUND"}
```

| Code | HTTP | Meaning |
|---|---|---|
| `UNAUTHORIZED` / `FORBIDDEN` | 401 / 403 | Missing token or missing `X-FxPilot-Client` |
| `UNKNOWN_ENDPOINT` | 404 | Unknown path |
| `METHOD_NOT_ALLOWED` | 405 | Action called with GET |
| `BODY_TOO_LARGE` | 413 | Body above 1 MB |
| `INVALID_SELECTOR` | 400 | Selector empty or malformed |
| `NODE_NOT_FOUND` | 404 | No node matches the selector |
| `NODE_AMBIGUOUS` | 409 | Selector matches several nodes (strict mode) |
| `WINDOW_NOT_FOUND` | 404 | Window index does not exist |
| `ITEM_NOT_FOUND` / `ROW_NOT_FOUND` / `TAB_NOT_FOUND` | 404 | Combo/list/table/tab entry missing |
| `TREE_ITEM_NOT_FOUND` / `MENU_NOT_FOUND` | 404 | Tree item or menu missing |
| `UNSUPPORTED_NODE` | 400 | Action not supported by this control |
| `NOT_SCROLLABLE` | 400 | Node has no scroll bar |
| `INVALID_KEY` / `INVALID_VALUE` / `INVALID_NUMBER` | 400 | Bad key, value or number |
| `NODE_DISABLED` / `NO_CHILDREN` | 409 | Menu item disabled / tree item has no children |
| `NODE_HIDDEN` | 409 | Node is not visible in the scene graph |
| `UNSUPPORTED_ACTION` | 400 | Operation is not possible for this control |
| `PROPERTY_NOT_FOUND` | 404 | JavaFX property does not exist |
| `WINDOW_ACTION_FAILED` | 400 | Window action is not possible (not a Stage) |
| `WEB_NOT_AVAILABLE` | 400 | Node is not a WebView / WebView is unavailable |
| `CHART_NOT_FOUND` / `CANVAS_NOT_FOUND` | 400 | Node is not a chart/canvas |
| `MEDIA_NOT_AVAILABLE` / `IMAGE_NOT_FOUND` | 400 | No MediaPlayer / no image |
| `INVALID_OPERATION` | 400 | Unknown extended operation name |
| `READ_ONLY` / `NODE_DENIED` | 403 | Read-only mode / selector denied by `fxpilot.deny` |
| `TOOLKIT_NOT_RUNNING` | 503 | JavaFX toolkit is not running yet |
| `FX_THREAD_TIMEOUT` | 504 | FX thread busy for more than 10 s |
| `ACTION_FAILED` / `INTERNAL_ERROR` | 500 | Handler threw / unexpected error |

## Security

- Without a port, no endpoint starts. Production launches are unaffected as long as the VM option is absent.
- The endpoint binds to `127.0.0.1` only.
- A token is optional, but recommended if other users share the machine. It is sent in `X-FxPilot-Token` and compared in constant time.
- Without a token, every POST needs the header `X-FxPilot-Client: <anything>`. The MCP server sends it automatically; browsers cannot set it on a simple form post, so a web page cannot click buttons in your app. If you call the endpoint with curl yourself, add `-H "X-FxPilot-Client: curl"`.
- Request bodies are limited to 1 MB.
- Mark buttons with real side effects (printing, database, machine control) with `setNotes()` or `fxpilot.notes`. Claude reads these hints through `fx_health`; they are not a technical lock.
- `-Dfxpilot.readonly=true` rejects every action (POST) with 403 `READ_ONLY`; queries and screenshots keep working.
- `-Dfxpilot.deny=#deleteAll,.danger` is a real lock: actions on matching nodes or one of their ancestors fail with 403 `NODE_DENIED`. Use it instead of notes for controls that must never be driven.
- `fx_property` and `fx_web` are powerful escape hatches: property reflection can call setters, and the WebView bridge executes JavaScript. Restrict them with `fxpilot.readonly`, a token and deny patterns in environments where that matters.
- `fx_image` writes files and `fx_window` can close or resize windows; use a token and avoid untrusted MCP clients.
- `PasswordField` text is redacted as `***` in node snapshots. Recorded plans still contain the text that was typed; keep them out of version control or redact them.
- If a handler opens a modal dialog, the action returns `status: pending`. The dialog then shows up in `fx_list_windows` and can be operated normally, e.g. with `fx_click` on `text=OK`.

## What FxPilot does not do

- `WebView` DOM access is limited to the optional JavaScript bridge (`fx_web`); there is no browser-automation model for downloads, dialogs or cross-origin pages.
- `Canvas` support is pixel/coordinate based (`fx_canvas`); there is no structured drawing model.
- Native OS dialogs (file chooser, print dialog) cannot be automated through JavaFX. Charts are read-only; there is no data-point selection API.
- Table/tree rows are matched as strings (`cell | cell`), not as domain objects. Direct cell writes are best effort and depend on the cell editor being present.
- `TreeTableView` only exposes visible (expanded) rows; use `fx_set_expanded`/`fx_tree` to open branches.
- Robot tools need the window in the foreground and are not reliable on multi-monitor setups with different scaling.
- Media controls require a `MediaPlayer` exposed by the `MediaView`.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `HTTP 503: JavaFX toolkit is not running (yet)` | The app is starting or has no FX thread yet. Wait and retry, or call `fx_health` until it answers. |
| `Cannot reach the app at ...` | Wrong port, `-Dfxpilot.port` missing, or the app ended. |
| Nothing listens and stderr says `JavaFX not found` | The app runs without JavaFX on the class/module path, or JavaFX is older than 11. |
| `NoClassDefFoundError: com/sun/net/httpserver/HttpServer` | Modular app without `requires jdk.httpserver;`. |
| Agent loads but prints "without port" | Pass `=port=8765` to `-javaagent`. |
| `Address already in use` | Pick another port per app. |
| `ESCAPE` does not close a dialog | Call `fx_press_key` without a selector, so the event reaches the focused node and the scene. |

## How it compares to TestFX

TestFX drives a JavaFX app from the inside as a test library, with JUnit integration and headless support. FxPilot is not a test framework: it is a runtime endpoint for an AI agent, starts with one VM option or one method call, needs no test code, and works on any app including third-party ones. Use TestFX for regression suites, FxPilot for exploratory driving and verification while developing.

## Development

```bat
mvn test          :: unit tests, including real HTTP requests to the endpoint
mvn clean verify  :: full build, no signing
mvn -Prelease deploy  :: release build, GPG-signs and uploads to the Central Portal
java -jar fxpilot.jar --replay plan.json  :: replay a recorded plan in CI
```

The MCP server is part of the jar, so `mvn verify` compiles and tests it.

### Releases

`mvn -Prelease deploy` signs the artifacts and uploads them to the Central Portal. `autoPublish` is `false`, so review the deployment in the portal and release it there.

The workflow `.github/workflows/release.yml` does the same for `v*` tags and refuses to publish when the tag (`v1.0.0`) does not match `project.version` (`1.0.0`). Required repository secrets:

| Secret | Purpose |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal token user |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal token |
| `MAVEN_GPG_PRIVATE_KEY` | ASCII-armored GPG private key |
| `MAVEN_GPG_PASSPHRASE` | Key passphrase |

If the Central secrets are missing, the tag workflow succeeds and skips the Central upload. See [docs/maven-central.md](docs/maven-central.md) for namespace verification, GPG setup, token creation and the publish step.

```bat
git tag -a v1.0.0 -m "FxPilot 1.0.0"
git push origin v1.0.0
```

## License

FxPilot is licensed under the [Apache License 2.0](LICENSE) and may be used, modified and redistributed for commercial and non-commercial purposes. Redistributions must include `LICENSE` and `NOTICE`. Both files are also contained in the jar under `META-INF/`.