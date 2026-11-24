# Contributing to FxPilot

Thanks for helping improve FxPilot.

## Development setup

Requirements:

- JDK 11 or newer
- Maven 3.8 or newer
- JavaFX 11 or newer for running examples

Build and test:

```bat
mvn clean verify
```

Build the example app:

```bat
mvn -f examples\demo-app\pom.xml package
```

## Project structure

```text
src/main/java/io/github/dbuedenbender/fxpilot/       core endpoint and agent
src/main/java/io/github/dbuedenbender/fxpilot/mcp/   MCP server
src/test/java/                                       unit and HTTP tests
examples/                                            demo app, MCP configs and replay plans
docs/                                                release and publishing documentation
```

## Pull requests

- Keep changes focused and explain the user-visible behavior.
- Add or update tests for behavior changes.
- Run `mvn clean verify` before opening a PR.
- Update `README.md`, `CHANGELOG.md` or examples when the change affects users.
- Do not commit `target/`, `.idea/`, private keys, tokens or replay plans containing sensitive data.

## Security-sensitive changes

FxPilot can control JavaFX applications. Changes to authentication, selector matching, file writes, reflection, WebView execution or window actions should include a short security note in the pull request.

Report vulnerabilities privately as described in [`SECURITY.md`](SECURITY.md), not in a public issue.

## Releases

The release process is documented in [`docs/maven-central.md`](docs/maven-central.md).
