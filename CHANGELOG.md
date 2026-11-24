# Changelog

## [Unreleased]

### Added

- Quick start, demo JavaFX application, MCP configuration examples and a replay smoke-test plan
- GitHub issue and pull-request templates
- Dependabot configuration for Maven and GitHub Actions
- GitHub Pages workflow for generated Javadoc
- Maven Central publishing documentation

### Changed

- Updated the Central publishing plugin to `0.12.0`
- Updated Maven compiler, surefire, jar, source, javadoc and GPG plugins
- Updated JUnit to the latest 5.x release
- Enforced GPG signing best practices
- Moved the Central publishing plugin into the release profile so normal CI builds do not depend on it
- Added the Maven Central and Javadoc badges

### Fixed

- Javadoc Pages deployment now uses the correct `target/reports/apidocs` output directory

## [1.0.0] - 2026-11-24

### Added

- MCP server with JavaFX automation tools
- Library and Java-agent integration
- Scene-graph inspection with `fx:id`, CSS, text, role, accessibility and `ref=` selectors
- Keyboard, mouse, drag-and-drop, clipboard and scrolling support
- Table, tree, menu, tab, accordion, pagination, window and dialog automation
- Property, WebView, chart, canvas, media and image support
- Waits, assertions, retries and screenshot diffing
- Record and replay with variables, retries, failure screenshots and JUnit XML reports
- Loopback binding, token authentication, read-only mode and deny patterns
- Password-field redaction in node snapshots

### Security

- Central publishing is skipped when release secrets are missing
- GPG signatures are required for Central releases
