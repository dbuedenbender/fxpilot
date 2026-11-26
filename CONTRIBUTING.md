# Contributing to FxPilot

Thanks for helping improve FxPilot.

## Development setup

Requirements:

- JDK 21 to run the tests (the library itself targets JDK 11)
- JDK 17 is enough to build/package the library
- Maven 3.8 or newer, or the wrapper in this repository (`mvnw.cmd` on Windows, `./mvnw` elsewhere), which pins Maven 3.9.16
- JavaFX 11 or newer for running examples

The UI tests run headless with Monocle on JavaFX 21; nothing has to be installed for that. Monocle 21 is compiled for Java 21, so `mvnw.cmd verify` on JDK 17 fails with `UnsupportedClassVersionError`. Point `JAVA_HOME` at a JDK 21, or use `mvnw.cmd -DskipTests clean package` on JDK 17. CI does both: `package` on JDK 17 and `verify` on JDK 21.

The compiler runs with `-Werror` and an explicit list of `-Xlint` categories (`pom.xml`); warnings fail the build. `mvnw.cmd verify` fails when the line coverage of a package drops below 60 % (75 % for the MCP package).

Build and test:

```bat
mvnw.cmd clean verify
```

Build the example app:

```bat
mvnw.cmd -f examples\demo-app\pom.xml package
```

## Project structure

```text
src/main/java/io/github/dbuedenbender/fxpilot/       endpoint and agent
    FxPilotServer, FxThread, DenyList, Policy           HTTP, FX thread, deny list, dangerous switch
    Selectors, NodeInfo, Actions, Screenshots           lookup, snapshots, core actions
    Operations + *Ops                                   registry and handlers of the /exec operations
src/main/java/io/github/dbuedenbender/fxpilot/internal/  JSON and token-file location, shared by both (not exported)
src/main/java/io/github/dbuedenbender/fxpilot/mcp/   MCP server
    ToolCatalog, ToolHandlers, Recorder, EndpointClient tool registry, handlers, record/replay, HTTP
src/test/java/                                       unit, HTTP, headless UI and MCP tests (TestUi is the fixture)
examples/                                            demo app, MCP configs and replay plans
docs/                                                release and publishing documentation
```

## Pull requests

- Keep changes focused and explain the user-visible behavior.
- Keep the API small: only `FxPilotServer`, `FxPilotAgent` and `FxPilotMcpServer` are public API. Code that both packages need goes into `internal` (public classes, not exported by `module-info.java`).
- Add or update tests for behavior changes. A new endpoint operation goes into `Operations` with its kind; a new MCP tool into `ToolCatalog` with profile, kind and recording flag.
- Run `mvnw.cmd clean verify` before opening a PR.
- Update `README.md`, `CHANGELOG.md` or examples when the change affects users.
- Do not commit `target/`, `.idea/`, private keys, tokens or replay plans containing sensitive data.

## Security-sensitive changes

FxPilot can control JavaFX applications. Changes to authentication, selector matching, file writes, reflection, WebView execution or window actions should include a short security note in the pull request.

Report vulnerabilities privately as described in [`SECURITY.md`](SECURITY.md), not in a public issue.

## Releases

The release process is documented in [`docs/maven-central.md`](docs/maven-central.md).
