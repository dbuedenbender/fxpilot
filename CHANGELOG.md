# Changelog

## [1.1.0] - 2026-11-25

### Security

- The endpoint only answers requests with `Host: 127.0.0.1|localhost|[::1]:<port>` and without an `Origin` header. In 1.0.0 a web page could reach the endpoint through DNS rebinding and, without a token, read the UI and drive it. New error codes `HOST_REJECTED` and `ORIGIN_REJECTED`; methods other than GET and POST get 405.
- `fxpilot.deny` now also covers the indirect ways to trigger a denied control: keys sent to a denied focus owner, ENTER/ESCAPE on a denied default/cancel button, accelerators and menu/context-menu items (`#id`, `.styleClass`, `text=`), drag and drop, `fx_window accept|dismiss` and robot clicks at the coordinates of a denied node. In 1.0.0 these bypassed the lock.
- `token=auto` generates a random token and exchanges it with the MCP server through a per-user token file (`<port>.token`, owner-only on POSIX), so a token no longer has to be configured on both sides.
- `fxpilot.dangerous=false` (endpoint) and `FXPILOT_DANGEROUS=false` (MCP server) disable WebView JavaScript, reflective property setters, image file writes and closing windows (`DANGEROUS_DISABLED`). Replayed plans respect it. With `FXPILOT_DANGEROUS=false` the MCP server writes plans, baselines and reports only below its working directory.
- PasswordField text could be read through `fx_property` (`characters`, or piece by piece through `selectedText`), matched with `text=` and appeared in NODE_NOT_FOUND/NODE_AMBIGUOUS messages. `fx_property` now reads only an allow-list of text-independent PasswordField properties (403 `NODE_DENIED` otherwise); this affected 1.0.0 as well.
- The deny list also covers accelerators of MenuButton items and of the context menus of tabs and table columns, and tabs themselves (select and close).
- Non-finite numbers (NaN, Infinity) are rejected; `x=NaN` used to pass the robot bounds and deny checks. Repeat counts are bounded (`clicks` 1-10, drag `steps` 1-200, `fx_type` up to 10 000 characters), so a request can no longer keep the FX thread busy for hours.
- Closing a tab and reading the system clipboard count as dangerous. `fx_image` replaces an existing file only with `overwrite=true`.
- Robot clicks and drags outside the target window's scene are rejected; inside, the deny list is checked in every window at that screen position.
- The visibility, enabled, ambiguity and deny checks of an action run on the FX thread together with the action, instead of on the HTTP thread before it.
- `FXPILOT_TOKEN` is only sent to the endpoint in `FXPILOT_URL`; other ports use their token file. In 1.0.0 the token went to every port a client named.

### Added

- Tool profiles: `FXPILOT_TOOLS=core|standard|all` (default `all`) limits the tools offered by `tools/list`; `fx_exec` stays available in every profile.
- MCP tool annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`).
- Headless UI tests (Monocle) for the endpoint and the MCP tools, an MCP stdio smoke test and a JaCoCo coverage gate (60 % lines per package).
- `examples/modular-demo`, a JPMS app that requires FxPilot as a module, and `examples/replay-headless.sh`, which CI uses to start both demos headless and replay their plans.
- Error code `PROPERTY_NOT_ACCESSIBLE` (403) when `fx_property` reads a getter of a class in a package the app's module does not export to FxPilot, with the `exports` line to add.
- The demo app is now "Order Desk" (customers, orders, dashboard, settings, modal windows, a background job and destructive actions), with `javafx:run@safe` and `javafx:run@readonly` and a second replay plan. It needs JDK 17 and JavaFX 21.
- Quick start, demo JavaFX application, MCP configuration examples and a replay smoke-test plan
- GitHub issue and pull-request templates
- Dependabot configuration for Maven and GitHub Actions
- GitHub Pages workflow for generated Javadoc
- Maven Central publishing documentation

### Changed

- MCP protocol negotiation: the server answers with the client's version only if it implements it (2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05), otherwise with the newest one, instead of echoing any version.
- The MCP server creates its HTTP client on first use, so it starts (and reports a tool error) even where the client cannot be created.
- Internal restructuring: `FxPilotEngine` and `FxPilotExtensions` are split into smaller package-private classes, extended operations live in one registry (`Operations`) with their read/write/dangerous kind, and the MCP tools in one registry (`ToolCatalog`) with definition, profile, kind, recording flag and handler. Endpoints, operation and tool names are unchanged.
- `fxpilot.jar` is a named JPMS module (`io.github.dbuedenbender.fxpilot`, with `module-info.java` instead of `Automatic-Module-Name`). It exports the two API packages; the new package `...fxpilot.internal` holds the JSON code and the token-file location that endpoint and MCP server used to keep as separate copies, and is not exported. Class-path use (`-javaagent`, `java -jar`, Maven dependency) is unchanged.
- `tableCell` on a TreeTableView returns the column text in `column`, like on a TableView (was the index).
- The extended tools are marked experimental for 1.x.
- The compiler runs with `-Xlint:all -Werror`, so a warning fails the build. CI builds the library on JDK 17 and runs the full test suite on JDK 21, matching the JDK 21 requirement of the Monocle-based UI tests.
- The JavaFX test runtime is 21.0.6, which also removes the Maven model warning emitted by the OpenJFX 21.0.2 POM.
- Added tests for MCP stdio protocol errors, tool argument validation and replay failure handling; the MCP package now has a 75 % line coverage gate.
- Updated the Central publishing plugin to `0.12.0`
- Updated Maven compiler, surefire, jar, source, javadoc and GPG plugins
- Updated JUnit to the latest 5.x release
- Enforced GPG signing best practices
- Moved the Central publishing plugin into the release profile so normal CI builds do not depend on it
- Added the Maven Central and Javadoc badges

### Fixed

- `fx_set_expanded` is recorded, so replayed plans expand tree items again before selecting inside them.
- `contextMenuItem` (`fx_context_menu` with `item`) fires items of the showing ContextMenu; it searched skin nodes that never matched.
- `tableCell` on a TreeTableView reads the cell value instead of splitting the row text, which broke on cells containing ` | `.
- `/node` on a TreeTableView with a collapsed hidden root failed with HTTP 500; `collapseAll` keeps a hidden root expanded.
- Actions that open a modal dialog return `status: pending` instead of failing after 10 s with `FX_THREAD_TIMEOUT`: menu items, keys, context-menu items, `fx_window accept|dismiss`, drag, `fx_set_expanded` and WebView JavaScript. Read-only queries keep the 10 s timeout.
- `id=<fx:id>@<n>` picked the first match for actions; it now selects the n-th match as it does for reads.
- `fx_click_menu`/`fx_menu` by path fired disabled menu items; they now fail with `NODE_DISABLED`.
- `fx_set_checked` left a tri-state CheckBox indeterminate; it now ends in the requested state.
- `ref=` paths of nodes inside TabPane, ScrollPane and ToolBar did not resolve.
- `tableCellSet` reported success without committing; it now commits through the column's edit-commit event.
- `fx_window accept|dismiss` found dialog buttons only by English text; it now uses their ButtonData, then default/cancel buttons.
- Closing a tab ignored `closable=false`, the closing policy and an `onCloseRequest` veto.
- DatePicker and ColorPicker fired `onAction` twice on `fx_set_value`.
- Malformed CSS selectors (`.`, `#`) gave HTTP 500 or matched the root node; `tableCell` on a missing row returned `null`; `window=title:` without text matched the first window.
- An action that returned `pending` and failed later lost its error; it is now logged.
- Bad input gave HTTP 500: `fx_hotkey keys=+`, non-numeric indices and malformed form encoding now return 400.
- `/key` (`fx_press_key`) ignored `within`/`nth` and modifiers on the plain endpoint; `/key` and `/click` now use the same implementation as `fx_press_key` and `fx_click`.
- A replayed plan that calls `fx_replay` failed with a StackOverflowError that stopped the MCP server; nested replays are rejected. `fx_retry delayMs` is capped at 10 s.
- `fx_drag` resolved its nodes outside the FX thread.
- Integral numbers above 2^53 keep their precision in endpoint JSON; the MCP server writes NaN/Infinity as `null`.
- Javadoc Pages deployment now uses the correct `target/reports/apidocs` output directory

## [1.0.0] - 2026-11-24

### Added

- MCP server with JavaFX automation tools
- Library and Java-agent integration
- Scene-graph inspection with `fx:id`, CSS, text, role, accessibility and `ref=` selectors
- Keyboard, mouse, drag-and-drop, clipboard and scrolling support
- Table, tree, menu, tab, accordion, pagination, window and dialog automation
- Property, WebView, chart, canvas, media and image support
- Waits, assertions, retries and screenshot diffing
- Record and replay with variables, retries, failure screenshots and JUnit XML reports
- Loopback binding, token authentication, read-only mode and deny patterns
- Password-field redaction in node snapshots

### Security

- Central publishing is skipped when release secrets are missing
- GPG signatures are required for Central releases
