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
| `show_diff` | Opens IntelliJ's built-in diff viewer to visually compare current file content with proposed content (read-only) |

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
| `list_run_configurations` | Lists all run configurations with name, type, folder, and running status |
| `start_run_configuration` | Launches a named run configuration in run or debug mode |
| `modify_run_configuration` | Modifies VM options, program arguments, env vars, or working dir of a run configuration |
| `get_run_configuration_xml` | Returns the full XML definition of a run configuration |
| `create_run_configuration_from_xml` | Creates a new run configuration from an XML definition (any type) |
| `debug_run_configuration` 🔬 | Launches a run configuration in debug mode |
| `get_debug_variables` 🔬 | Local variables and values from the current debugger stack frame |
| `get_breakpoints` | Lists all line breakpoints with file, line, enabled state, and condition |
| `add_conditional_breakpoint` | Adds a breakpoint with an optional condition expression |
| `set_breakpoint_condition` | Sets or removes a condition on an existing breakpoint |
| `mute_breakpoints` | Mutes or unmutes all breakpoints in the active debug session |

### Diagnostic & Processes
| Tool | Description |
|------|-------------|
| `get_ide_snapshot` | Lightweight snapshot (active file, selection, runs, debug, indexing, background tasks + recently-finished ones) — designed for frequent polling by a Claude Code hook. Multi-project: returns one entry per open project |
| `get_intellij_diagnostic` 🔬 | One-call diagnostic (per project: indexing status, notifications, running processes, recently-finished tasks) + IDE-wide idea.log WARN/ERROR tail |
| `get_running_processes` | Lists active background processes + tasks finished in the last 3 minutes (Gradle sync, indexing, compilation, etc.) — multi-project |
| `manage_process` | Pauses, resumes, or cancels a background process by title |
| `get_ide_settings` | Read IntelliJ settings: Gradle, SDK, compiler, encoding — search by keyword, direct key lookup, or prefix subtree with optional depth |

### Code Analysis
| Tool | Description |
|------|-------------|
| `get_file_problems` | IDE-detected errors/warnings for a file or all open editors, including available quick fixes per problem |
| `get_quick_fixes` | All quick fix suggestions for a file or a specific line, grouped by line |
| `apply_quick_fix` | Applies a quick fix by exact text at a given line (use fixes returned by get_file_problems or get_quick_fixes) |
| `list_inspections` | Lists all available inspections in the current profile, optionally filtered to a file or folder |
| `run_inspections` | Runs inspections on a file, folder, or whole project — works on closed files too; filter by inspection ID or severity |
| `refresh_project` 🔬 | Sync Gradle or Maven build system — detects the build tool automatically from the project root |
| `get_project_structure` | Returns SDK, modules, source roots, excluded folders, and module dependencies |

### Database *(requires Database Tools and SQL plugin — IntelliJ IDEA Ultimate)*
| Tool | Description |
|------|-------------|
| `list_database_sources` | Lists all configured data sources (name, URL, driver, user, DBMS) — call this first to discover available sources |
| `get_database_schema` | Schema tree already introspected by IntelliJ: namespaces → tables/views with keys, FK, indexes, and optionally columns |
| `execute_database_query` 🔒 | Executes a SQL query on a data source, returns JSON with columns + rows (or affected row count for DML) |

### VCS
| Tool | Description |
|------|-------------|
| `get_vcs_changes` | All locally modified, added, deleted, and moved files (any VCS). Pass `includeDiff=true` to include a unified diff per file |
| `get_vcs_branch` | Current branch and all local/remote branches for each Git repository |
| `get_vcs_log` | Recent commit history: hash, author, date, subject, and changed files. Filter by file or branch (Git) |
| `get_vcs_blame` | Line-by-line blame annotation: who last modified each line, when, and in which commit (any VCS) |
| `get_local_history` | IntelliJ Local History for a file, directory, or the whole project. Returns timestamped revisions with optional unified diffs |
| `get_vcs_file_history` | Commit history for a single file with optional `--follow` to track renames across commits |
| `get_vcs_diff_between_branches` | Unified diff (or `--stat` summary) between two branches, tags, or commits |
| `vcs_show_commit` | Full content of a commit: metadata, message, and unified diff of all changed files |
| `vcs_stage_files` | Stage or unstage files in the Git index (`action="stage"` / `"unstage"`) |
| `vcs_commit` | Create a Git commit from staged changes; pass `amend=true` to amend the last commit |
| `vcs_push` | Push the current branch to its remote tracking branch (`git push`) |
| `vcs_pull` | Pull from the remote tracking branch; pass `rebase=true` for `--rebase` |
| `vcs_stash` | Manage Git stashes: `action="push"` / `"pop"` / `"apply"` / `"drop"` / `"list"` |
| `vcs_create_branch` | Create a new Git branch; pass `checkout=true` (default) to switch to it immediately |
| `vcs_checkout_branch` | Switch the working tree to an existing branch (`git checkout <branch>`) |
| `vcs_delete_branch` 🔒 | Delete a Git branch locally, remotely, or both |
| `vcs_fetch` | Fetch from one or all remotes without merging (`git fetch`); pass `prune=true` to remove deleted remote branches |
| `vcs_merge_branch` | Merge a branch into the current one (`git merge`); pass `noFf=true` to force a merge commit |
| `vcs_rebase` | Rebase the current branch onto another (`git rebase`); supports `abort=true` and `continueRebase=true` |
| `get_vcs_conflicts` | List all conflicted files after a failed merge or rebase, with conflict type and optional file content |
| `vcs_open_merge_tool` | Opens IntelliJ's built-in three-way merge tool for all conflicted files (Resolve Conflicts… dialog) |
| `vcs_reset` | Reset the current branch to a previous commit (`--soft`/`--mixed`/`--hard`) |
| `vcs_revert` | Create a new commit that undoes a previous commit (safe — no history rewrite) |
| `vcs_cherry_pick` | Apply the changes from a specific commit on top of the current branch |

### General
| Tool | Description |
|------|-------------|
| `get_mcp_companion_overview` | Describes all available MCP Companion tools and how to use them |
| `execute_ide_action` 🔬 | Execute any IntelliJ action by ID (e.g. ShowSettings, ReformatCode), or search for action IDs by keyword |
| `replace_text_undoable` | Replace text in a file via IntelliJ's document API (supports Cmd+Z undo) |
| `delete_file` 🔒 | Deletes a file from the project (undoable) |

> 🔬 Core logic covered by headless tests. ⚠️ No automated test coverage. 🔒 Disabled by default — enable in Settings → Tools → MCP Server Companion.

### Multi-project support

All tools that target a single project accept an optional `projectPath` parameter (absolute path of the project root). This is useful when you have several IntelliJ windows open in the same JVM and want to target a specific one. When omitted, the currently-focused project is used.

`get_ide_snapshot`, `get_running_processes`, and `get_intellij_diagnostic` are **multi-project**: they return one entry per open project, so callers (like the Claude Code hook) can pick the right one based on their current working directory.

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

Add the JetBrains MCP proxy to your AI client config (e.g. `~/Library/Application Support/Claude/claude_desktop_config.json`):

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

Restart your AI client. The proxy auto-discovers the running IntelliJ instance — all tools are available immediately.

### 4. (Optional) Add the sandbox for plugin development

When developing this plugin, you can connect Claude Desktop to the `runIde` sandbox as a **second MCP server**, so AI tools call it directly without going through `curl`.

Claude Desktop only supports stdio-based MCP servers, so use `mcp-remote` as a bridge:

```json
{
  "mcpServers": {
    "jetbrains": {
      "command": "npx",
      "args": ["-y", "@jetbrains/mcp-proxy"]
    },
    "intellij-sandbox": {
      "command": "/opt/homebrew/bin/npx",
      "args": ["-y", "mcp-remote", "http://127.0.0.1:64343/sse"],
      "env": {
        "PATH": "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin"
      }
    }
  }
}
```

> **Why `/opt/homebrew/bin/npx` and the `env.PATH`?**
> Claude Desktop uses `/usr/local/bin` first in its PATH. If an older Node.js lives there (< 20.18.1), `mcp-remote` fails with a `ByteString` error. Forcing Homebrew's `npx` and its `PATH` ensures Node ≥ 20.18.1 is used.

After restarting Claude Desktop, sandbox tools appear prefixed as `intellij-sandbox__get_open_editors`, `intellij-sandbox__start_run_configuration`, etc.

> **Note:** When the sandbox is not running (`runIde` stopped), Claude Desktop will log connection errors for `intellij-sandbox` at startup — this is expected and harmless. Remove the entry when not actively developing the plugin.

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
