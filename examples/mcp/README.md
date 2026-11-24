# MCP configurations

## Claude Code

```bat
claude mcp add --scope user fxpilot ^
  -e FXPILOT_URL=http://127.0.0.1:8765 ^
  -- java -jar C:\path\to\fxpilot.jar
```

Check the registration:

```bat
claude mcp list
```

Inside Claude Code, `/mcp` shows the connection and available tools.

## Claude Desktop

Copy [`claude-desktop.json`](claude-desktop.json) into your MCP configuration and replace the jar path.

Windows configuration file:

```text
%APPDATA%\Claude\claude_desktop_config.json
```

After changing the file, quit Claude Desktop completely and start it again.

## Token

When the FxPilot endpoint uses a token, add it to the MCP environment:

```json
"env": {
  "FXPILOT_URL": "http://127.0.0.1:8765",
  "FXPILOT_TOKEN": "your-token"
}
```

The app must be started with the same token:

```bat
-javaagent:C:\path\to\fxpilot.jar=port=8765,token=your-token
```

## Multiple applications

Every app runs on its own port. The MCP server keeps `FXPILOT_URL` as the default and every FxPilot tool accepts an optional `port` argument.
