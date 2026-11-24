# FxPilot examples

This directory contains small, copyable examples for new users.

| Example | Purpose |
|---|---|
| [`demo-app/`](demo-app/) | Minimal JavaFX app with stable `fx:id`s and FxPilot enabled |
| [`mcp/`](mcp/) | MCP configurations for Claude Code and Claude Desktop |
| [`plans/`](plans/) | Recorded/replayable smoke test for the demo app |

## Run the demo

Build and run the demo app:

```bat
mvn -f examples\demo-app\pom.xml javafx:run
```

The demo listens on port `8765` when started through its Maven configuration.
Then register the MCP server and call `fx_health`.

## Replay the smoke test

With the demo app running:

```bat
java -jar fxpilot.jar --replay examples\plans\demo-smoke.json
```

The plan fills the name field, activates the checkbox, saves and verifies the result.
