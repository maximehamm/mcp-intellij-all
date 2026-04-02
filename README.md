# MCP Server Companion

An IntelliJ IDEA plugin that extends the built-in [JetBrains MCP Server](https://plugins.jetbrains.com/plugin/26071-mcp-server) with additional tools for AI clients (Claude, Cursor, etc.).

## Requirements

- IntelliJ IDEA 2024.3+
- [MCP Server plugin](https://plugins.jetbrains.com/plugin/26071-mcp-server) installed
- An AI client configured with `@jetbrains/mcp-proxy` (Claude Desktop, Cursor, etc.)

## Tools

| Tool | Description |
|------|-------------|
| `get_open_editors` | Open files, focused editor, caret position, and current text selection |
| `get_build_output` | Build tool window: structured error tree with file/line numbers + console text |
| `get_run_output` | Console output from the Run tool window |
| `get_debug_output` | Console output from the Debug tool window |
| `get_debug_variables` | Local variables and values from the current debugger stack frame |
| `replace_text_undoable` | Replace text in a file via IntelliJ's document API (supports Cmd+Z undo) |

## Example prompts

**Editor**
- *"What file am I currently editing, and what line is my cursor on?"*
- *"Look at the file I'm editing and suggest improvements for the selected code."*

**Build**
- *"Check my build output and tell me what errors I have."*
- *"Run a build, check the result, and fix any errors."*
- *"I have a build error — can you identify the cause and fix it? Make it undoable."*

**Run & Debug**
- *"Run the program and show me the output."*
- *"What are the current variable values at this breakpoint?"*
- *"Set a breakpoint at line 13, launch debug, and stop when i=3."*

## Setup

### 1. Install the MCP Server plugin

Install [MCP Server](https://plugins.jetbrains.com/plugin/26071-mcp-server) from the JetBrains Marketplace.

### 2. Build and install this plugin

```bash
git clone https://github.com/maximehamm/mcp-intellij-all.git
cd mcp-intellij-all
./gradlew buildPlugin
```

Then install the plugin from `build/distributions/` via **Settings → Plugins → Install Plugin from Disk**.

### 3. Configure your AI client

Add the JetBrains MCP proxy to your AI client config (e.g. `claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "jetbrains": {
      "command": "npx",
      "args": ["-y", "@jetbrains/mcp-proxy"]
    }
  }
}
```

## Development

```bash
./gradlew runIde   # Launch a sandbox IntelliJ with the plugin
./gradlew buildPlugin  # Build the distributable .zip
```

## License

MIT
