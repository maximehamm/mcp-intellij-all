# TODO

- Open into terminal 
- Open in Finder.Explorer
- Open in Associated App

### Gradle (bundled in IntelliJ IDEA Community/Ultimate & Android Studio — not in PyCharm/WebStorm/etc.)

> Implementation notes:
> - Mark these tools as requiring `com.intellij.gradle` in `McpCompanionSettings.TOOL_REQUIRED_PLUGIN` (degrades gracefully on IDEs without Gradle).
> - Use `compileOnly` for Gradle API imports in `build.gradle.kts` (no hard dependency — same approach as `mcpserver.jar`).
> - Runtime guard via `PluginManagerCore.isPluginInstalled(PluginId.getId("com.intellij.gradle"))` with a clear error message when absent.

**High value:**
- **run_gradle_task** — Execute one or more Gradle tasks. Params: `tasks: List<String>`, `args: List<String> = []`, `projectPath?: String`. Returns `{ status, exitCode, durationMs, stdout, stderr, failedTasks }`.
- **get_gradle_tasks** — List available Gradle tasks grouped by category (Build / Verification / Help / Other / custom). Returns `{ group: [{ name, description, project }] }`.
- **refresh_gradle_project** — Force a Gradle re-sync (equivalent of the 🔄 button in the Gradle tool window). Required after editing `build.gradle.kts` (new dependency, plugin, etc.) so IntelliJ picks up the changes.
- **get_gradle_dependencies** — Parsed dependency tree for a given configuration (e.g. `runtimeClasspath`). Lets the AI answer "why do I have this version of jackson?" or "what transitively pulls log4j?".
- **stop_gradle_task** — Cancel a running Gradle build (equivalent of the red square in the tool window).

- **git move** pàur déplacer ou renommer un fichier

**Comfort:**
- **get_gradle_project_info** — Wrapper version, JDK used, list of subprojects with their paths, source sets. Helps reasoning about monorepo structure.

### Other

- **create_scratch_file** — Create a scratch file with any extension and content

## New tools

- **find_references** — Find all usages of a symbol across the project
- **get_type_hierarchy** — Get inheritance hierarchy for classes
- **get_call_hierarchy** — Who calunals this method, and what does it call
- **get_todo_items** — List TODO/FIXME comments across the project

- **undo / redo**
- **mark_directory** — Mark a directory as source root, test root, resources, excluded, or generated

- **add_bookmark** — Add a bookmark on a file/line
- **get_bookmarks** — List all bookmarks in the project
- **navigate_to_bookmark** — Navigate to a bookmark by name/mnemonic

- **get_implementations** — Find implementations of an interface or abstract class

-debug : resume, step by step etc.

### Pull Requests (GitHub / GitLab)
- **list_pull_requests** — PR ouvertes sur la branche (via plugin GitHub/GitLab si installé)
- **get_pull_request_comments** — lire les review comments d'une PR

### Cross-language (JS/TS/Python/JVM/Ruby/PHP)
- **evaluate_debug_expression** — Evaluate an arbitrary expression in the current debug frame (IntelliJ "Evaluate" dialog equivalent). Complements `get_debug_variables`: lets the AI test hypotheses live without modifying code.
- **get_debug_call_stack** — Return the call stack of the current thread (file/line/method per frame). Lets the AI see the full execution path.
- **select_debug_frame** — Switch the active debugger frame to inspect variables of a caller higher up the stack.
- **get_type_at** (filePath, line, column) — Return the type / inferred type at a given offset (hover "Show type" equivalent). Critical for TS, useful for Python type hints and JVM languages.

### Python
- **list_python_packages** — `pip list` of the active venv (name + version). Lets the AI know whether a dependency is available before suggesting an import.

### JavaScript / TypeScript
- **list_npm_scripts** — Parse the project's `package.json` and return the defined scripts. AI can suggest "run `npm run dev`".
- **list_node_packages** — Return the installed dependencies (`node_modules`) with their versions.
