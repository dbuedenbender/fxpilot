# Changelog

## [1.1.1] - 2026-11-26

Security release. Update from 1.0.0 and 1.1.0: 1.1.0 writes text typed into password fields into recorded plans and its launchers trust the checksum file of the download server; 1.0.0 also lacks the DNS rebinding guard. The API and the tool names are unchanged.

### Security

- Recorded plans no longer contain text entered into a `PasswordField` with `fx_set_text`, `fx_type`, `fx_text` or `fx_exec`. The step stores `${<fx:id>}` (or `${password}`, `${password2}` ...), the plan lists these names under `secrets`, and a replay without values for all of them fails before the first step. Single keys (`fx_press_key`) and clipboard content are still recorded as they are.
- The launchers (`fxpilot-mcp.cmd`, `fxpilot-mcp.sh`) and the installers check the downloaded jar against a SHA-256 stored in the scripts for the default version, and against `FXPILOT_SHA256` / `-Sha256` or Maven Central's `.sha256` for other versions. Before, `fxpilot-mcp.cmd` and the installers did not check the download at all, and `fxpilot-mcp.sh` only against the `.sha1` from the same server, skipping the check when that file or a hash tool was missing.
- The MCP server resolves symbolic links before it checks that a file lies below its working directory, and with `FXPILOT_DANGEROUS=false` it now also reads plans (`fx_replay`) and screenshot baselines (`fx_screenshot_diff`) only from there. The `--replay` CLI is not restricted.
- The deny list also checks mnemonics (ALT+underlined letter) of denied controls. There was no known bypass: FxPilot's own key events carry no text and did not fire mnemonics.
- With `token=auto` the token file is deleted when the JVM ends, also in agent mode, where `stop()` is never called.
- The endpoint handles requests with at most 8 threads instead of an unbounded pool.

### Changed

- `fx_wait_for` and `fx_wait_for_window` wait at most 120 s (`timeoutMs` is capped), because the MCP server handles one request at a time.
- The MCP server handles `notifications/cancelled`: the named request stops (a running call is interrupted, also inside `fx_batch` and `fx_replay`) and gets no reply. Requests still run one at a time and in order, so a `ping` sent during a long call is answered after it.
- With `FXPILOT_DANGEROUS=false`, files are written and read through the path that was checked (symbolic links resolved), not the path the client passed.
- CI starts the MCP server from the built jar on JDK 11.
- JSON-RPC error codes: invalid params (arguments or params that are no object, a tool name that is no string) are `-32602` instead of `-32600`/`-32603`; a request without `method`, a non-string `method` and a JSON array are answered with `-32600` instead of being dropped or answered with `-32603`.
- `--replay` rejects unknown options with exit code 2 instead of ignoring them.
- CI retries a Maven run only when the log shows a failed download; a failing test fails the job at once.
- README: the settings are process-wide and can be set in code with `System.setProperty` before `startIfEnabled()`.
- The compiler checks an explicit list of lint categories instead of `-Xlint:all`, so a newer JDK with new categories does not break the build.
- `scripts/pin-release.sh` and `scripts/pin-release.ps1` update the version and SHA-256 pins in `fxpilot-mcp.cmd`, `fxpilot-mcp.sh` and `fxpilot-jar.ps1` from Maven Central's `fxpilot-<version>.jar.sha256` in one step. The order stays "publish, then pin", so no launcher checks a new version against the previous checksum before the artifact exists; the release workflow points to the script after a deployment.
- The repository ships the Maven Wrapper (`mvnw`, `mvnw.cmd`, Maven 3.9.16), and CI builds with `./mvnw`, so a local Maven installation is optional. A new `.gitattributes` keeps `mvnw` and `*.sh` at LF and the Windows scripts at CRLF, so the shell launchers also run from Git Bash on Windows.
- The JaCoCo gate follows the measured level: 78 % of the lines per package (85 % for `internal`) and 78 % for the whole jar, instead of 60 % and 75 % for the MCP package. `Codes`, which only holds error-code constants, is excluded.
- `FXPILOT_TOOLS` defaults to `standard` instead of `all`: `tools/list` offers the reading, driving, waiting, asserting, screenshot and record/replay tools, which is enough for most sessions and costs less context. `FXPILOT_TOOLS=all` adds the robot, clipboard, canvas, WebView, chart, media, image and property tools; `fx_exec` keeps every operation reachable in all profiles, and a hidden tool called by name still fails with a hint.
- The release workflow publishes only on the tag that matches `project.version`: a `workflow_dispatch` from a branch used to skip that check and deploy whatever `main` had. All GitHub Actions are pinned by commit SHA instead of a moving tag.
- The example apps carry the same Apache header as the library, and a test checks that every Java source under `src` and `examples` has it.

### Fixed

- `fx_press_key` typed the key name (`DIGIT1` inserted "DIGIT1"); it now types the character (`1`, `7` for `NUMPAD7`, a space for `SPACE`) and honours SHIFT for letters. SHIFT with a digit row key types nothing, because the result depends on the keyboard layout.
- `modifiers` with leading or trailing spaces (`" CTRL"`) failed with "Unknown modifier ''".
- `fx_replay` while recording added the replayed `fx_assert` steps, but not the replayed actions, to the recording.
- `fx_media` on a MediaView without a MediaPlayer failed with HTTP 500 instead of `MEDIA_NOT_AVAILABLE`.
- An `Error` in a request handler (e.g. `NoClassDefFoundError`) closed the connection without a response; it is now a 500 `INTERNAL_ERROR`.
- `FxPilotServer.start(port, token)` checks the JavaFX version like `startIfEnabled()`.
- The launchers and installers download into a separate file and move it into the cache only when complete and verified. An interrupted download used to stay in the cache.
- `fxpilot-mcp.cmd` downloads with PowerShell instead of `curl.exe`, which fails where the certificate revocation server is unreachable (`CRYPT_E_REVOCATION_OFFLINE`).
- Demo app: the "Customers by country" chart showed a "?" slice for a newly saved customer, because it followed only added and removed customers, not their country.
- Tests: positive tests for `fx_web` (real WebView), `fx_media` (real MediaPlayer) and a robot click, plus tests for the fixes above.

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
- An out-of-range `fxpilot.port` (system property, environment or agent argument) was an uncaught `IllegalArgumentException` that could abort application startup; it is now rejected with a log message.
- `--replay` printed a stack trace for malformed plan JSON or plan structure; it now exits 2 with a message. The JSON parser rejects invalid numbers (`+1`, `01`, `.5`, `1.`, `1e309`) and deeply nested input instead of accepting or overflowing it.
- `FXPILOT_DANGEROUS=false` now also blocks `fx_exec` WebView queries, HTML reads and clicks, which execute JavaScript; previously only `fx_web`/`webExecute` were covered.
- `FXPILOT_TOKEN` was not sent when `FXPILOT_URL` used a loopback alias (`localhost`, `[::1]`) and a tool passed the same port; loopback aliases now count as the same endpoint.
- `fx_hotkey` silently ignored unknown modifiers (`CTLR+S` pressed `S`); unknown modifiers are now rejected with `INVALID_KEY`.
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
