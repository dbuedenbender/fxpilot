# FxPilot Modular Demo

A small JPMS application that uses FxPilot as a module. It shows what a modular JavaFX app needs for FxPilot and checks in CI that the `fxpilot.jar` module works on the module path.

Requirements: JDK 17 or newer (JavaFX 21).

## module-info.java

```java
module io.github.dbuedenbender.fxpilot.modulardemo {
    requires javafx.controls;
    requires io.github.dbuedenbender.fxpilot;

    opens io.github.dbuedenbender.fxpilot.modulardemo to javafx.graphics;
    exports io.github.dbuedenbender.fxpilot.modulardemo.widgets to io.github.dbuedenbender.fxpilot;
}
```

- `requires io.github.dbuedenbender.fxpilot` makes `FxPilotServer` available. FxPilot brings `jdk.httpserver` itself, so the app does not require it.
- FxPilot works through the public JavaFX API, so it needs nothing for ordinary controls, including subclasses in non-exported packages.
- Only `fx_property` calls getters of the app's own classes by reflection. That needs the package exported to FxPilot, as `widgets` is here. For the custom getter of `VaultLabel` in the non-exported package `secret`, FxPilot answers 403 `PROPERTY_NOT_ACCESSIBLE` and names the missing `exports` line. Its inherited JavaFX properties such as `text` still work.

## Build and run

```bat
mvn -DskipTests install
mvn -f examples\modular-demo\pom.xml javafx:run
```

`javafx:run` starts the app on the module path with `-Dfxpilot.port=8765`. The app calls `FxPilotServer.startIfEnabled()` itself.

| Selector | Control |
|---|---|
| `#machineField` | Machine name |
| `#lineCombo` | ComboBox: Line 1, Line 2, Line 3 |
| `#addButton` | Default button: adds the machine to the table or shows an error |
| `#machineTable` | Machines with their line |
| `#serialButton` | `SerialButton` (exported package), property `serial` = `SN-4711` |
| `#vaultLabel` | `VaultLabel` (not exported), custom property `code` is not accessible |
| `#statusLabel` | Result of every action |

## Replay

```bat
java -jar fxpilot.jar --replay examples\plans\modular-smoke.json
```

## Headless, as in CI

[`../replay-headless.sh`](../replay-headless.sh) starts this app on the module path and `demo-app` on the class path with Monocle and replays all example plans. For the module path, Monocle is patched into `javafx.graphics`:

```bash
java --module-path "examples/modular-demo/target/fxpilot-modular-demo-1.1.1.jar:target/headless/mods" \
     --patch-module javafx.graphics=target/headless/openjfx-monocle-21.0.2.jar \
     -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw -Dfxpilot.port=8782 \
     -m io.github.dbuedenbender.fxpilot.modulardemo/io.github.dbuedenbender.fxpilot.modulardemo.ModularDemoApp
```

The same app also works with FxPilot as a Java agent (`-javaagent:fxpilot.jar=port=8794,token=auto`) while `fxpilot.jar` is on the module path.
