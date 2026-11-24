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
- Without a token, mutating requests need the `X-FxPilot-Client` header.
- A token should be used when other users share the machine.
- `fxpilot.readonly=true` blocks mutating operations.
- `fxpilot.deny` is an explicit lock for controls that must never be driven.
- Request bodies are limited to 1 MB.

## Powerful operations

These operations are intentionally powerful and should only be available to trusted MCP clients:

- `fx_property` uses JavaFX bean reflection.
- `fx_web` executes JavaScript inside a WebView.
- `fx_image` writes image files.
- `fx_window` can close, move and resize windows.
- `fx_robot_*` controls the real mouse.

Do not expose the endpoint directly to the internet. Use a token, read-only mode, deny patterns, a reverse proxy with TLS and a trusted network.

## Sensitive data

`PasswordField` text is redacted in node snapshots, but recorded replay plans can contain typed text, including passwords. Treat plans as sensitive data and keep them out of version control or redact them.
