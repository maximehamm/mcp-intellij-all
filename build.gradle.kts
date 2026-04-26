import java.util.Properties

plugins {
    id("java")
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
    kotlin("plugin.serialization") version "2.2.0"
}

val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) load(f.inputStream())
}

group = "io.nimbly"
version = "3.1.3"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Users/maxime/Applications/IntelliJ IDEA 2026.1.app")
        bundledPlugin("com.intellij.mcpServer")
        bundledPlugin("Git4Idea")
        bundledPlugin("com.intellij.gradle")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    testImplementation("junit:junit:4.13.2")
    compileOnly(files("/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/plugins/mcpserver/lib/mcpserver.jar"))
    compileOnly(files("/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/plugins/mcpserver/lib/mcpserver-frontend.jar"))
    testRuntimeOnly(files("/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/lib/intellij.platform.ide.impl.jar"))
    testRuntimeOnly(files("/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/lib/intellij.platform.core.impl.jar"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
        changeNotes = """
            <ul>
                <li><b>3.1.0</b> — Monitoring tool window now persists full untruncated parameters AND captured responses to a per-session disk store (cleared at IDE restart). Layout split: list on top, Parameters in the middle, Response+Errors tabs at the bottom — bottom hidden for tools from other plugins (no response capture available). JSON responses are auto pretty-printed; non-JSON responses (Markdown overviews, XML, plain text) render in plain text. <kbd>Cmd/Ctrl + C</kbd> on a row copies the tool name to the clipboard.</li>
                <li><b>3.0.0</b> — Major internal refactoring: codebase split into clearly-separated packages.</li>
                <li><b>2.18.0</b> — New <b>MCP Companion Monitoring</b> tool window: shows the 50 most recent MCP tool calls with HH:MM:SS timestamps, durations, and full JSON parameters (calls from other MCP plugins are also captured and shown in gray). Status bar widget click now opens a popup with two actions: "MCP Monitoring" and "MCP Companion settings…".</li>
                <li><b>2.17.0</b> — New status bar widget showing live MCP activity: animated icon during tool calls, tooltip with the currently-running tools and the 5 most recent completed (with durations), and click to open the plugin settings.</li>
                <li><b>2.16.0</b> — New Gradle tools: <code>run_gradle_task</code>, <code>get_gradle_tasks</code>, <code>refresh_gradle_project</code>, <code>get_gradle_dependencies</code>, <code>stop_gradle_task</code>, and <code>get_gradle_project_info</code> — full Gradle integration from your AI client (run/list/cancel tasks, inspect imported dependencies, wrapper version, subprojects).</li>
                <li><b>2.15.1</b> — Fix: <code>get_debug_variables</code> now works with non-JVM debuggers (JavaScript/TypeScript/Node, Python, Ruby, PHP). Variables exposed in scope groups (<code>Local</code>, <code>Closure</code>, <code>Global</code>, …) are returned with a dotted prefix. Thanks to @simontong for the report (issue #1).</li>
                <li><b>2.15.0</b> — New VCS tools: <code>get_vcs_file_history</code>, <code>get_vcs_diff_between_branches</code>, <code>vcs_show_commit</code>, <code>vcs_reset</code>, <code>vcs_revert</code>, <code>vcs_cherry_pick</code>, and <code>vcs_delete_branch</code> (disabled by default). Plus a new editor tool <code>show_diff</code> to open IntelliJ's built-in diff viewer for previewing proposed changes before applying them.</li>
                <li><b>2.14.0</b> — New VCS tools: <code>vcs_fetch</code>, <code>vcs_merge_branch</code>, <code>vcs_rebase</code>, <code>get_vcs_conflicts</code> (list conflicted files with content), and <code>vcs_open_merge_tool</code> (open IntelliJ's three-way merge dialog).</li>
                <li><b>2.13.0</b> — New VCS branch tools: <code>vcs_create_branch</code> (create and optionally switch to a new branch) and <code>vcs_checkout_branch</code> (switch to an existing branch).</li>
                <li><b>2.12.0</b> — New VCS write tools: <code>vcs_stage_files</code>, <code>vcs_commit</code>, <code>vcs_push</code>, <code>vcs_pull</code>, <code>vcs_stash</code> — stage/unstage files, commit, push, pull, and manage stashes directly from your AI client.</li>
                <li><b>2.11.2</b> — Multi-project support: <code>get_ide_snapshot</code>, <code>get_running_processes</code> and <code>get_intellij_diagnostic</code> now return one entry per open project (a single JVM can host several project windows). All other project-scoped tools accept an optional <code>projectPath</code> parameter to target a specific project.</li>
                <li><b>2.11.0</b> — New tool: <code>get_ide_snapshot</code> — compact snapshot of the current IDE context (active file + caret, selection, open files, runs, debug paused location, indexing, background tasks, recently-finished tasks) designed for frequent polling by a Claude Code <code>UserPromptSubmit</code> hook.</li>
                <li><b>2.10.0</b> — Run configurations can now be listed, launched, created from XML, and modified directly from your AI client: <code>list_run_configurations</code>, <code>start_run_configuration</code>, <code>get_run_configuration_xml</code>, <code>create_run_configuration_from_xml</code>, <code>modify_run_configuration</code>.</li>
                <li><b>2.9.0</b> — New tools: <code>list_inspections</code> (browse the active inspection profile) and <code>run_inspections</code> (run inspections on a file or project, with severity filter).</li>
                <li><b>2.8.0</b> — New tool: <code>apply_quick_fix</code> — applies an IDE quick fix by name (like Alt+Enter).</li>
                <li><b>2.6.1</b> — Minor plugin description improvements.</li>
                <li><b>2.6.0</b> — New VCS tools: <code>get_vcs_changes</code> lists modified/added/deleted/moved files with optional unified diff; <code>get_vcs_branch</code> shows current branch and all local/remote branches; <code>get_vcs_log</code> returns recent commit history with author, date, and changed files; <code>get_vcs_blame</code> provides line-by-line annotation. <code>get_vcs_changes</code> and <code>get_vcs_blame</code> work with any VCS; <code>get_vcs_branch</code> and <code>get_vcs_log</code> require the Git plugin (bundled in IntelliJ IDEA).</li>
                <li><b>2.5.1</b> — Minor plugin description improvements.</li>
                <li><b>2.5.0</b> — New Database tools (IntelliJ IDEA Ultimate): <code>list_database_sources</code> lists configured data sources with URL, driver, and user; <code>get_database_schema</code> returns the full introspected schema tree — namespaces, tables, views, columns with types, primary keys, foreign keys, and indexes; <code>execute_database_query</code> executes any SQL query and returns results as JSON (disabled by default). All implemented via reflection through the Database plugin classloader — no compile dependency. Settings UI greys out Database tools when the plugin is not installed.</li>
                <li><b>2.4.0</b> — New: usage statistics in Settings. See which tools you use most, or compare with all users worldwide. A one-time notification lets you opt out at any time.</li>
                <li><b>2.3.0</b> — New tool: <code>send_to_terminal</code> — executes a shell command in the embedded Terminal (disabled by default). <code>send_to_terminal</code> and <code>delete_file</code> are now disabled by default for safety; disabled-by-default tools show a clear message asking the user to enable them in Settings.</li>
                <li><b>2.2.0</b> — New tool: <code>get_terminal_output</code> — returns the visible content of all tabs in the embedded Terminal tool window.</li>
                <li><b>2.1.0</b> — Test suite: 44 automated headless tests covering all toolsets. New <code>SandboxToolsHeadlessTest</code> covers previously manual-only tools (get_ide_settings, get_intellij_diagnostic, execute_ide_action, get_build_output, get_test_results, debug_run_configuration, get_debug_variables, refresh_project). README updated with test markers (🔬 / ⚠️) and light/heavy test classification.</li>
                <li><b>2.0.0</b> — Major refactoring: plugin split into 6 focused toolset classes (Editor, Build, Debug, Diagnostic, CodeAnalysis, General) for better maintainability. No functional changes — all 29 tools remain identical.</li>
                <li><b>1.17.0</b> — New tools: <code>get_file_problems</code> (IDE errors/warnings for a file or all open editors) and <code>get_quick_fixes</code> (Alt+Enter fix suggestions at a specific position). New "Code Analysis" group in Settings also includes <code>refresh_project</code> and <code>get_project_structure</code>.</li>
                <li><b>1.16.0</b> — Settings: usage bars showing call counts per tool (session, auto-scaled). Diagnostic: idea.log now returns ERROR/SEVERE from last N minutes with stack traces; new <code>level</code> and <code>minutesBack</code> params.</li>
                <li><b>1.15.0</b> — New tool: <code>refresh_project</code> — syncs Gradle or Maven build system automatically detected from project root.</li>
                <li><b>1.14.0</b> — New tool: <code>execute_ide_action</code> — execute any IntelliJ action by ID, or search for action IDs by keyword. <code>get_ide_settings</code>: new <code>prefix</code> + <code>depth</code> parameters for subtree lookup; Gradle section silently skipped on non-Gradle IDEs.</li>
                <li><b>1.13.0</b> — New tool: <code>get_ide_settings</code> — read IntelliJ settings by keyword search or direct key lookup (Gradle, SDK, compiler, encoding…).</li>
                <li><b>1.12.0</b> — New tool: <code>get_console_output</code> — unified Run + Debug console output with active window and active tab indicated. Replaces <code>get_run_output</code> and <code>get_debug_output</code>.</li>
                <li><b>1.11.0</b> — <code>get_services_output</code> rewrite: sessions grouped by node name (console_1, script.sql…), SQL output log, result grids with real column names, selected session/tab indicated.</li>
                <li><b>1.10.3</b> — Marketplace overview: use h4 headers for tool groups for better spacing.</li>
                <li><b>1.10.2</b> — Marketplace overview: improve spacing between tool groups.</li>
                <li><b>1.10.1</b> — Marketplace overview restructured to match Settings page groups. Fix @ApiStatus.Internal violation on CoreProgressManager.</li>
                <li><b>1.10.0</b> — New tool: <code>get_intellij_diagnostic</code> — one-call diagnostic combining indexing status, active notifications, running processes, and idea.log WARN/ERROR tail.</li>
                <li><b>1.9.0</b> — New tools: <code>get_running_processes</code> (lists active/paused background processes) and <code>manage_process</code> (pause, resume, cancel). Added reflection API tests. Fixed binary compatibility with IntelliJ 2025.3.x (<code>McpToolset.isEnabled()</code>).</li>
                <li><b>1.8.0</b> — <code>get_project_structure</code>: now includes SDK homePath and all available SDKs registered in IntelliJ.</li>
                <li><b>1.7.0</b> — New tool: <code>get_project_structure</code> — returns SDK, modules, source roots (source/test/resource/testResource), excluded folders, and module dependencies.</li>
                <li><b>1.6.0</b> — Claude Code setup panel: Add to CLAUDE.md button (undoable), permissions snippet with Copy button. New get_mcp_companion_overview tool. Fix endCol inclusive in highlight_text and select_text.</li>
                <li><b>1.5.0</b> — New navigation and highlighting tools: navigate_to, select_text, highlight_text (theme-aware, Escape to clear), clear_highlights.</li>
                <li><b>1.4.0</b> — New breakpoint tools: get_breakpoints (with conditions), add_conditional_breakpoint, set_breakpoint_condition, mute_breakpoints, debug_run_configuration.</li>
                <li><b>1.3.1</b> — Fix settings page refresh: warning and grayed tools now update correctly after Apply.</li>
                <li><b>1.3.0</b> — Settings page: warning message + grayed tools when MCP Server is disabled. Auto-enable MCP Server on first launch with notification.</li>
                <li><b>1.2.1</b> — get_debug_output now works when session is finished or paused at breakpoint; severity field (ERROR/WARNING/SUCCESS) added to build/run tree nodes; all consoles per tab are now read.</li>
                <li><b>1.2.0</b> — New Settings page (Tools → MCP Server Companion) to enable/disable each tool individually.</li>
                <li><b>1.1.0</b> — New tool: <code>get_test_results</code> — returns last test run results (passed/failed/ignored status, duration, failure messages).</li>
                <li><b>1.0.1</b> — Migration to IntelliJ 2025.3+ MCP API (McpToolset).</li>
                <li><b>1.0.0</b> — Initial release. Adds 6 MCP tools: get_open_editors, get_build_output, get_run_output, get_debug_output, get_debug_variables, replace_text_undoable.</li>
            </ul>
        """.trimIndent()
    }
}

intellijPlatform {
    publishing {
        token = secrets.getProperty("marketplace.token", "")
    }
    pluginVerification {
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES
        )
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2025.3.4")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
        jvmArgs("-Xbootclasspath/a:/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/lib/nio-fs.jar")
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }
    named("publishPlugin") {
        dependsOn("test")
    }
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    buildSearchableOptions {
        enabled = false
    }
    runIde {
        jvmArgs("-Xbootclasspath/a:/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/lib/nio-fs.jar")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
