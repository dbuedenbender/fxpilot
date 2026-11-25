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
- `fxpilot.deny` is an explicit lock for controls that must never be driven, including keys, accelerators (menu bars, MenuButtons, context menus), menu items, tabs, drag and drop and robot clicks that would reach them. Scene accelerators registered as plain `Runnable`s cannot be matched to a control.
- `fxpilot.dangerous=false` disables WebView JavaScript, reflective setters, image file writes, closing windows and tabs, and reading the system clipboard. These are allowed by default in 1.x and will be opt-in in 2.0. `FXPILOT_DANGEROUS=false` on the MCP server also hides these tools and restricts the files it writes (plans, baselines, reports) to its working directory.
- Request bodies are limited to 1 MB.

## Powerful operations

These operations are intentionally powerful and should only be available to trusted MCP clients. `fxpilot.dangerous=false` (and `FXPILOT_DANGEROUS=false` on the MCP server) turns off property setters, WebView JavaScript, image file writes and closing windows:

- `fx_property` uses JavaFX bean reflection.
- `fx_web` executes JavaScript inside a WebView.
- `fx_image` writes image files.
- `fx_window` can close, move and resize windows.
- `fx_robot_*` controls the real mouse.

The endpoint is meant for the local machine only. Do not expose it to other machines; a reverse proxy in front of it is rejected by the Host check unless it forwards `Host: 127.0.0.1:<port>`. Use a token, read-only mode, deny patterns and `fxpilot.dangerous=false` where the app controls real equipment or data.

## Sensitive data

`PasswordField` text is never returned: snapshots show `***`, only text-independent properties can be read, text selectors do not match it and error messages leave it out, but recorded replay plans can contain typed text, including passwords. Treat plans as sensitive data and keep them out of version control or redact them.
