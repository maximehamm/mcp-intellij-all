# MCP Server Companion

An IntelliJ IDEA plugin that extends the built-in [JetBrains MCP Server](https://plugins.jetbrains.com/plugin/26071-mcp-server) with additional tools for AI clients (Claude, Cursor, etc.).

## Requirements

- IntelliJ IDEA 2024.3+ or any JetBrains IDE (Android Studio, PyCharm, GoLand, WebStorm…)
- [MCP Server plugin](https://plugins.jetbrains.com/plugin/26071-mcp-server) installed (bundled in IntelliJ IDEA Ultimate 2025.1+, free on the Marketplace for all other IDEs)
- An AI client configured with `@jetbrains/mcp-proxy` (Claude Desktop, Cursor, etc.)

## Tools

### Editor & Navigation
| Tool | Description |
|------|-------------|
| `get_open_editors` | Open files, focused editor, caret position, and current text selection |
| `navigate_to` | Opens a file and places the cursor at a given line and column |
| `select_text` | Opens a file and selects a text range |
| `highlight_text` | Highlights multiple zones in a file using the IDE's search-result color |
| `clear_highlights` | Removes all highlights added by `highlight_text` |

### Build & Tests
| Tool | Description |
|------|-------------|
| `get_build_output` 🔬 | Build tool window: structured error tree with file/line numbers + console text |
| `get_console_output` 🔬 | Console output from both Run and Debug tool windows, with active window and tab indicated |
| `get_services_output` ⚠️ | Services tool window sessions: SQL output log, result grids, active session/tab indicated |
| `get_test_results` 🔬 | Last test run results: passed/failed/ignored status, duration, and failure messages |
| `get_terminal_output` 🔬 | Content of all tabs in the embedded Terminal tool window |
| `send_to_terminal` 🔬 🔒 | Send a command to a terminal tab and execute it |

### Debug
| Tool | Description |
|------|-------------|
| `debug_run_configuration` 🔬 | Launches a run configuration in debug mode |
| `get_debug_variables` 🔬 | Local variables and values from the current debugger stack frame |
| `get_breakpoints` | Lists all line breakpoints with file, line, enabled state, and condition |
| `add_conditional_breakpoint` | Adds a breakpoint with an optional condition expression |
| `set_breakpoint_condition` | Sets or removes a condition on an existing breakpoint |
| `mute_breakpoints` | Mutes or unmutes all breakpoints in the active debug session |

### Diagnostic & Processes
| Tool | Description |
|------|-------------|
| `get_intellij_diagnostic` 🔬 | One-call diagnostic: indexing status, notifications, running processes, and idea.log WARN/ERROR tail |
| `get_running_processes` | Lists active and paused background processes in IntelliJ |
| `manage_process` | Pauses, resumes, or cancels a background process by title |
| `get_ide_settings` | Read IntelliJ settings: Gradle, SDK, compiler, encoding — search by keyword, direct key lookup, or prefix subtree with optional depth |

### Code Analysis
| Tool | Description |
|------|-------------|
| `get_file_problems` | IDE-detected errors/warnings for a file or all open editors |
| `get_quick_fixes` | Quick fix suggestions at a specific file:line:column — use after get_file_problems |
| `refresh_project` 🔬 | Sync Gradle or Maven build system — detects the build tool automatically from the project root |
| `get_project_structure` | Returns SDK, modules, source roots, excluded folders, and module dependencies |

### General
| Tool | Description |
|------|-------------|
| `get_mcp_companion_overview` | Describes all available MCP Companion tools and how to use them |
| `execute_ide_action` 🔬 | Execute any IntelliJ action by ID (e.g. ShowSettings, ReformatCode), or search for action IDs by keyword |
| `replace_text_undoable` | Replace text in a file via IntelliJ's document API (supports Cmd+Z undo) |
| `delete_file` 🔒 | Deletes a file from the project (undoable) |

> 🔬 Core logic covered by headless tests. ⚠️ No automated test coverage. 🔒 Disabled by default — enable in Settings → Tools → MCP Server Companion.

## Settings

Each tool can be individually enabled or disabled in **Settings → Tools → MCP Server Companion**.


## Example prompts

**Editor & Navigation**
- *"What file am I currently editing, and what line is my cursor on?"*
- *"Navigate to the `processOrder` method and highlight all its usages."*
- *"Look at the selected code and suggest improvements — make changes undoable."*

**Build & Run**
- *"Build the project, check the result, and fix any errors."*
- *"Run the program and show me the output."*
- *"I have a build error — identify the cause and fix it."*

**Debug**
- *"Set a breakpoint at line 42 with condition `i > 3`, launch debug, and tell me the variable values when it stops."*
- *"What are the current variable values at this breakpoint?"*

**Tests**
- *"Run the tests and tell me which ones failed and why."*
- *"Fix the failing tests, run them again, and confirm they pass."*

**Database (Services)**
- *"Show me the result of the last SQL query in the Services window."*

**Diagnostics & Settings**
- *"Is IntelliJ building with Gradle or its own compiler?"*
- *"What errors or warnings does IntelliJ currently show?"*
- *"What JDK is configured for this project?"*

…and much more via `get_mcp_companion_overview`.

## Testing

Headless, ~3 seconds, no sandbox needed.

| Class | Type | What it covers |
|-------|------|----------------|
| `ReflectionApiTest` | light | Verifies every reflection call still resolves against current IntelliJ JARs: `CoreProgressManager`, `TaskInfo`, `McpToolset`, `McpServerSettings`, `TerminalViewImpl.createSendTextBuilder/doSendText`, `TerminalSendTextOptions` constructor |
| `CodeAnalysisReflectionTest` | light | Verifies `HighlightInfo.offsetStore`, `getIntentionActionDescriptors`, `DocumentMarkupModel.forDocument`, `DaemonCodeAnalyzer.isRunning` |
| `ToolsetIntegrationTest` | heavy | `BasePlatformTestCase` tests in a headless IntelliJ — real PSI, real editor, real breakpoint manager. Covers: `get_open_editors`, caret/selection, highlight/clear, `relativize`, markup model, `replace_text_undoable`, `delete_file`, add/mute/condition breakpoints, `collectRunningProcesses` |
| `SandboxToolsHeadlessTest` | heavy | Headless coverage for tools marked 🔬: `get_ide_settings`, `get_intellij_diagnostic` components, `execute_ide_action`, build tree parsing, `SMTestProxy` status mapping, `RunManager`, `get_console_output` structure (empty run/debug in headless), `get_terminal_output` fallback, `send_to_terminal` fallback + disabled-by-default behavior |

Tools marked ⚠️ in the tables above have no automated test coverage and must be tested manually via `runIde` + curl SSE (see `CLAUDE.md`).

## Setup

### 1. Install the MCP Server plugin

Install [MCP Server](https://plugins.jetbrains.com/plugin/26071-mcp-server) from the JetBrains Marketplace.

### 2. Build and install this plugin

```
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
./gradlew runIde        # Launch a sandbox IntelliJ with the plugin
./gradlew buildPlugin   # Build the distributable .zip
./gradlew test          # Run automated tests (headless, ~3s)
```

## Telemetry backend

The anonymous usage tracker is a Vercel serverless app in `tracker/`.
**Vercel auto-deploys on every push to `main`** — no manual deploy needed.

Production URL: `https://mcp-intellij-all.vercel.app` (Vercel project: `mcp-intellij-all`).
This URL is hardcoded in `McpCompanionTelemetry.kt` — do not change it to a deployment-specific URL.

If a new field is added to the telemetry payload:
1. Update `tracker/api/track.ts` (extract + insert the new field)
2. Update `tracker/schema.sql` (add column in `CREATE TABLE` + `ALTER TABLE` migration)
3. Run the `ALTER TABLE` migration manually in **Vercel → Storage → your DB → Query**
4. Push → Vercel redeploys automatically

## License

MIT
