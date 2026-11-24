# Launcher and installer scripts

Java cannot execute a jar directly from an HTTP URL. `-jar` and `-javaagent` require a local file. These scripts download the published jar from Maven Central into a local cache and then start the MCP server.

## One-command setup for Claude

Windows, Claude Desktop:

```bat
scripts\install-claude-desktop.cmd
```

Windows, Claude Code:

```bat
scripts\install-claude-code.cmd
```

The installers download `fxpilot-1.0.0.jar` into `%LOCALAPPDATA%\FxPilot` and register the MCP server with the default endpoint `http://127.0.0.1:8765`.

Optional arguments:

```bat
scripts\install-claude-desktop.cmd -Version 1.0.0 -Url http://127.0.0.1:8766 -Token your-token
```

The Claude Desktop installer backs up an existing `claude_desktop_config.json` to `.bak` and merges the `fxpilot` entry without removing other MCP servers.

## Launcher only

If you prefer to manage the MCP configuration yourself:

### Windows

```bat
scripts\fxpilot-mcp.cmd
```

The jar is cached under:

```text
%LOCALAPPDATA%\FxPilot\fxpilot-1.0.0.jar
```

### Linux and macOS

```sh
chmod +x scripts/fxpilot-mcp.sh
./scripts/fxpilot-mcp.sh
```

The jar is cached under `${XDG_CACHE_HOME:-$HOME/.cache}/fxpilot/`.

### MCP configuration

Point the MCP client at the launcher instead of the jar:

```json
{
  "mcpServers": {
    "fxpilot": {
      "command": "cmd",
      "args": ["/c", "C:\\path\\to\\fxpilot-mcp.cmd"],
      "env": {
        "FXPILOT_URL": "http://127.0.0.1:8765"
      }
    }
  }
}
```

On Linux/macOS use the absolute path to `fxpilot-mcp.sh` as `command`.

### Version pinning

Both scripts use `1.0.0` by default. Set `FXPILOT_VERSION` to use another published version:

```bat
set FXPILOT_VERSION=1.0.1
scripts\fxpilot-mcp.cmd
```

For production or shared environments, verify the downloaded artifact against the published checksums. The scripts intentionally keep the download simple; a checksum-hardened variant can be added when needed.

### Agent mode

`-javaagent` cannot load a remote jar. For library/agent mode, use the Maven dependency or keep the jar locally:

```bat
java -javaagent:C:\path\to\fxpilot.jar=port=8765 -jar YourApp.jar
```

The MCP launcher handles only the MCP server side. The JavaFX application still runs locally and exposes its HTTP endpoint on `127.0.0.1`.
