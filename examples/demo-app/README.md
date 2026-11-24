# FxPilot Demo App

A minimal JavaFX application with stable `fx:id`s for trying FxPilot.

## Build

```bat
mvn -f examples\demo-app\pom.xml package
```

## Run

```bat
mvn -f examples\demo-app\pom.xml javafx:run
```

The Maven configuration starts the app with:

```text
-Dfxpilot.port=8765
```

## Controls

| Selector | Control |
|---|---|
| `#nameField` | Customer name |
| `#activeCheck` | Active checkbox |
| `#saveButton` | Save button |
| `#statusLabel` | Result label |
| `#customerTable` | Customer table |
| `#root` | Root container |

Try the MCP tools `fx_health`, `fx_get_view`, `fx_set_text`, `fx_click` and `fx_assert`.

## Alternative: Java agent

Build the main FxPilot jar, then start the demo with the agent instead:

```bat
java -javaagent:C:\path\to\fxpilot.jar=port=8765 ^
  -cp "examples\demo-app\target\classes;..." ^
  io.github.dbuedenbender.fxpilot.demo.DemoApp
```

The Maven `javafx:run` variant is simpler because it sets up the JavaFX module path.
