# Security policy

## Supported versions

The latest published minor version receives security fixes.

## Reporting a vulnerability

Please do not open a public issue for a vulnerability. Use GitHub's private vulnerability reporting:

https://github.com/dbuedenbender/fxpilot/security/advisories

Include:

- affected version
- a minimal reproduction
- expected and actual behavior
- whether the issue requires a token, local access or a malicious MCP client

## Security model

FxPilot is designed for local development and test automation:

- The endpoint binds to `127.0.0.1` only.
- It rejects requests whose `Host` header does not name `127.0.0.1`, `localhost` or `[::1]` with its port, and requests with an `Origin` header. This stops web pages from reaching it through DNS rebinding (fixed in 1.1.0; 1.0.0 is affected unless a token is set).
- Without a token, mutating requests need the `X-FxPilot-Client` header.
- A token should be used when other users share the machine. `token=auto` generates one per start and hands it to the MCP server through a per-user token file.
- `fxpilot.readonly=true` blocks mutating operations.
- `fxpilot.deny` is an explicit lock for controls that must never be driven, including keys, accelerators (menu bars, MenuButtons, context menus), mnemonics, menu items, tabs, drag and drop and robot clicks that would reach them. Scene accelerators registered as plain `Runnable`s cannot be matched to a control.
- `fxpilot.dangerous=false` disables WebView JavaScript, reflective setters, image file writes, closing windows and tabs, and reading the system clipboard. These are allowed by default in 1.x and will be opt-in in 2.0. `FXPILOT_DANGEROUS=false` on the MCP server also hides these tools and restricts the files it writes (plans, baselines, reports) and the plans and baselines it reads to its working directory; symbolic links are resolved first. The `--replay` CLI reads any plan the user names.
- Request bodies are limited to 1 MB, and requests are handled by at most 8 threads.
- With `token=auto` the token file is deleted on `stop()` and when the JVM ends.
- A static `FXPILOT_TOKEN` goes to whatever process listens on the port in `FXPILOT_URL`. If another local user could bind that port first, prefer `token=auto`: the token file is owner-only and changes with every start.

## Threat model and secure defaults

Trusted: the local user, the MCP client that user configured, and the app being driven.

Untrusted: web pages, other local users, network peers, and replay plans or baseline images received from elsewhere. FxPilot is not a sandbox: a client that can call tools has the same power over the app as the user running it, including file writes and real mouse and keyboard input. Grant that power deliberately.

Defaults in 1.x:

| Setting | Default | Recommendation |
|---|---|---|
| bind address | `127.0.0.1` only | never expose it; a reverse proxy is rejected by the `Host` check unless it forwards `Host: 127.0.0.1:<port>` |
| endpoint token | none | `token=auto` when other users share the machine |
| `fxpilot.readonly` | `false` | `true` for inspection-only sessions |
| `fxpilot.dangerous` | `true` (opt-in in 2.0) | `false` unless the session needs it |
| `FXPILOT_TOOLS` | `all` | `core` or `standard` for small or less trusted clients |
| `FXPILOT_DANGEROUS` | `true` | `false` when replaying plans from elsewhere |
| request body | 1 MB | keep the endpoint local |

## Powerful operations

These operations are intentionally powerful and should only be available to trusted MCP clients. `fxpilot.dangerous=false` (and `FXPILOT_DANGEROUS=false` on the MCP server) turns off property setters, WebView JavaScript, image file writes, closing windows and tabs, and reading the clipboard:

- `fx_property` uses JavaFX bean reflection.
- `fx_web` executes JavaScript inside a WebView.
- `fx_image` writes image files.
- `fx_window` can close, move and resize windows.
- `fx_robot_*` controls the real mouse.

The endpoint is meant for the local machine only. Do not expose it to other machines; a reverse proxy in front of it is rejected by the Host check unless it forwards `Host: 127.0.0.1:<port>`. Use a token, read-only mode, deny patterns and `fxpilot.dangerous=false` where the app controls real equipment or data.

## Sensitive data

`PasswordField` text is never returned: snapshots show `***`, only text-independent properties can be read, text selectors do not match it and error messages leave it out, and recorded replay plans store `${name}` variables instead of text entered into a PasswordField with `fx_set_text`, `fx_type`, `fx_text` or `fx_exec`. Single keys sent with `fx_press_key`, clipboard content and text typed into other fields are recorded as they are, so still treat plans as sensitive data.
