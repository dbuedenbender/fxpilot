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

## IntelliJ IDEA

The repository contains a shared run configuration:

```text
.run/FxPilot Demo (Maven).run.xml
```

Open the project in IntelliJ and select **FxPilot Demo (Maven)** in the run-configuration dropdown, then click Run.

If the configuration is not visible, import `examples/demo-app/pom.xml` as a Maven project once. IntelliJ then resolves the JavaFX dependencies and the run configuration starts `javafx:run` with the correct module path.

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

## Replay plan

With the demo running, execute the included smoke test:

```bat
java -jar fxpilot.jar --replay examples\plans\demo-smoke.json
```
