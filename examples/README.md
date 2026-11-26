# FxPilot examples

This directory contains small, copyable examples for new users.

| Example | Purpose |
|---|---|
| [`demo-app/`](demo-app/) | "Order Desk": customers, orders, dashboard and settings with stable `fx:id`s, a modal dialog, a slow background job and destructive actions for the safety settings |
| [`modular-demo/`](modular-demo/) | JPMS app with `requires io.github.dbuedenbender.fxpilot`, a custom control in an exported and one in a non-exported package |
| [`replay-headless.sh`](replay-headless.sh) | Starts both demos headless (Monocle) and replays all plans; CI runs it |
| [`mcp/`](mcp/) | MCP configurations for Claude Code and Claude Desktop |
| [`plans/`](plans/) | Replayable plans for the demo app |

## Run the demo

Build and run the demo app:

```bat
mvnw.cmd -f examples\demo-app\pom.xml javafx:run
```

The demo listens on port `8765` when started through its Maven configuration.
Then register the MCP server and call `fx_health`.

## Replay the smoke test

With the demo app running:

```bat
java -jar fxpilot.jar --replay examples\plans\demo-smoke.json
java -jar fxpilot.jar --replay examples\plans\demo-orders.json
```

The first plan fills and saves the customer form, the second one works with orders, a modal window and the background sync.
