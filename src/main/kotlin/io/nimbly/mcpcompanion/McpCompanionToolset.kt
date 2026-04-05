package io.nimbly.mcpcompanion

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

class McpCompanionToolset : McpToolset {

    // Explicit override prevents Kotlin from generating an invokespecial bridge to
    // McpToolset.isEnabled() — which exists only in 2026.1+ and would cause
    // NoSuchMethodError on 2025.3.x at runtime.
    override fun isEnabled(): Boolean = true

    private fun disabledMessage(toolName: String): String? {
        if (!McpCompanionSettings.getInstance().isEnabled(toolName))
            return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion."
        McpCompanionSettings.getInstance().trackCall(toolName)
        return null
    }

    // ── get_mcp_companion_overview ────────────────────────────────────────────

    @McpTool(name = "get_mcp_companion_overview")
    @McpDescription(description = """
        Returns the usage guide for MCP Server Companion: all available tools, when to use them,
        and recommended workflows. Call this at the start of a session to know what you can do
        directly inside IntelliJ.
    """)
    suspend fun get_mcp_companion_overview(): String {
        disabledMessage("get_mcp_companion_overview")?.let { return it }
        return """
# MCP Server Companion — Usage Guide

## Golden rules
- This project is open in IntelliJ IDEA. ALWAYS prefer acting directly in the IDE over giving textual instructions.
- When talking about code: navigate to it, highlight it, select it — don't just describe where it is.
- When something fails: read the IDE output first, then explain.

## Tools and when to use them

### Editor & Navigation
- get_open_editors       → know which files are open and where the caret is
- navigate_to            → move the caret to a file:line:column (do this before explaining anything)
- select_text            → select an exact range (startCol/endCol are 1-based, endCol is INCLUSIVE)
- highlight_text         → highlight multiple zones at once (declaration + all usages)
                           ranges format: "startLine:startCol:endLine:endCol" comma-separated, endCol INCLUSIVE
- clear_highlights       → remove all highlights (user can also press Escape)

### Build & Tests
- get_build_output       → read compiler errors before answering a build question
- get_console_output     → read console output from Run AND Debug windows — use this whenever the user ran or debugged something
                           "activeWindow" tells you which window is currently visible; "active: true" marks the focused tab
- get_services_output    → SQL output log and result grids from the Services tool window
- get_test_results       → read test results (pass/fail/duration/message)

### Debug
- debug_run_configuration    → launch a named run config in debug mode
- get_debug_variables        → read local variables from the current stack frame
- get_breakpoints            → list all breakpoints with conditions
- add_conditional_breakpoint → add or update a breakpoint with an optional condition
- set_breakpoint_condition   → change the condition of an existing breakpoint
- mute_breakpoints           → enable/disable all breakpoints at once

### Diagnostic & Processes
- get_intellij_diagnostic    → notifications, errors, background tasks, ERROR/SEVERE from idea.log (last 5 min) — call this when something looks wrong
- get_running_processes      → list background tasks (indexing, Gradle sync, …) — call this when IntelliJ seems busy
- manage_process             → cancel / pause / resume a background task by title (partial match)
- get_ide_settings           → read IntelliJ settings (Gradle, compiler, SDK, encoding…)
                               no param = common settings overview
                               search="gradle" = filter all properties by keyword
                               key="x" = direct lookup of one property
                               prefix="gradle" = all settings whose key starts with "gradle"
                               prefix + depth=1 = limit to one dot-segment beyond the prefix

### Code Analysis
- get_file_problems      → IDE-detected errors/warnings for a file or all open editors
                           severity="error" (default) / "warning" / "all"
- get_quick_fixes        → quick fix suggestions at a specific file:line:column
                           ⚠ file must be open in editor; use after get_file_problems
- refresh_project        → sync Gradle or Maven — detects the build system automatically
                           use after modifying build.gradle, pom.xml, or when dependencies drift
- get_project_structure  → list modules, SDK, source roots, dependencies — call this first on a new project

### General
- get_mcp_companion_overview → this overview
- execute_ide_action         → execute any IntelliJ action by ID, or search for action IDs by keyword
                               search="settings" = list actions whose name/ID contains "settings"
                               actionId="ShowSettings", "GotoFile", "ReformatCode", "OptimizeImports", …
- replace_text_undoable      → replace text in a file already open in the editor (Cmd+Z undoable)
                               Use this for targeted edits to open files — the user can undo instantly.
- replace_file_text_by_path  → (built-in) overwrite a whole file via IntelliJ API — auto-refreshes the editor
                               params: pathInProject (relative), newFileText (full content)
- create_new_file_with_text  → (built-in) create a new file via IntelliJ API — auto-refreshes the editor
                               params: pathInProject (relative), text (full content)
- delete_file                → delete a file via IntelliJ VFS — IDE is immediately notified

IMPORTANT: Always prefer IntelliJ tools over native Write/Edit/Bash(rm) for any file operation
(create, modify, delete) on a project open in IntelliJ. This keeps the IDE in sync automatically.

## Typical workflows

**"Show me where X is used"**
  1. highlight_text — declaration + all usage sites

**"Why doesn't this compile?"**
  1. get_build_output — read the error
  2. navigate_to — go to the faulty line
  3. explain + fix with replace_text_undoable

**"Fix code problems"**
  1. get_file_problems — list errors/warnings
  2. get_quick_fixes — get fix suggestions at the problem position
  3. replace_text_undoable — apply the fix

**"Debug this"**
  1. add_conditional_breakpoint — set breakpoint with condition
  2. debug_run_configuration — start debug session
  3. get_debug_variables — inspect variables once paused
        """.trimIndent()
    }

    // ── replace_text_undoable ─────────────────────────────────────────────────

    @McpTool(name = "replace_text_undoable")
    @McpDescription(description = """
        Replaces a specific text in a file while preserving IntelliJ's undo stack.
        After calling this tool, Cmd+Z (Undo "MCP Replace") works normally in the editor.
        Use this instead of replace_specific_text when you want the change to be undoable.
        Parameters:
        - pathInProject: relative path from the project root (e.g. "src/Main.java")
        - oldText: exact text to find and replace (first occurrence)
        - newText: replacement text
    """)
    suspend fun replace_text_undoable(pathInProject: String, oldText: String, newText: String): String {
        disabledMessage("replace_text_undoable")?.let { return it }
        val project = coroutineContext.project
        val projectPath = project.basePath ?: return "error: no project base path"
        val virtualFile = LocalFileSystem.getInstance().findFileByPath("$projectPath/$pathInProject")
            ?: return "error: file not found: $pathInProject"
        val document = invokeAndWaitIfNeeded {
            FileDocumentManager.getInstance().getDocument(virtualFile)
        } ?: return "error: cannot open document for: $pathInProject"
        val offset = document.text.indexOf(oldText)
        if (offset == -1) return "error: text not found in file"
        invokeAndWaitIfNeeded {
            WriteCommandAction.runWriteCommandAction(project, "MCP Replace", null, {
                document.replaceString(offset, offset + oldText.length, newText)
            })
        }
        return "ok"
    }

    // ── execute_ide_action ───────────────────────────────────────────────────

    @McpTool(name = "execute_ide_action")
    @McpDescription(description = """
        Executes an IntelliJ IDE action by its action ID, opens a specific Settings page, or searches for action IDs.
        - actionId: execute this action (e.g. "ReformatCode", "GotoFile", "Git.Branches")
        - configurable: open Settings directly at a named page (e.g. "Gradle", "Compiler", "Editor")
        - search: find action IDs and names matching a keyword (returns up to 30 matches)
        Tip: use search first to discover the right action ID, then call with actionId to trigger it.
        Common IDs: GotoFile, ReformatCode, OptimizeImports, GotoClass, FindUsages, Vcs.UpdateProject, Git.Branches
    """)
    suspend fun execute_ide_action(actionId: String? = null, configurable: String? = null, search: String? = null): String {
        disabledMessage("execute_ide_action")?.let { return it }
        val project = coroutineContext.project
        val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        return when {
            configurable != null -> {
                val lower = configurable.lowercase()
                val isProjectStructurePage = lower in setOf("sdks", "modules", "libraries", "artifacts", "facets", "project", "problems", "global libraries")
                ApplicationManager.getApplication().invokeLater {
                    if (isProjectStructurePage) {
                        val psAction = am.getAction("ShowProjectStructureSettings")
                        if (psAction != null) {
                            val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
                            val searchTerms = listOf(configurable, configurable.trimEnd('s'), configurable + "s")
                            javax.swing.Timer(1000) { _ ->
                                outer@ for (window in java.awt.Window.getWindows()) {
                                    if (!window.isVisible) continue
                                    val container = (window as? javax.swing.RootPaneContainer)
                                        ?.contentPane as? javax.swing.JComponent ?: continue
                                    for (list in UIUtil.findComponentsOfType(container, javax.swing.JList::class.java)) {
                                        val model = list.model
                                        for (i in 0 until model.size) {
                                            val item = model.getElementAt(i)?.toString() ?: continue
                                            if (searchTerms.any { item.equals(it, ignoreCase = true) }) {
                                                list.selectedIndex = i
                                                list.ensureIndexIsVisible(i)
                                                list.selectionModel.setSelectionInterval(i, i)
                                                break@outer
                                            }
                                        }
                                    }
                                }
                            }.apply { isRepeats = false; start() }
                            am.tryToExecute(psAction, null, frame, com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, true)
                        }
                    } else {
                        com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, configurable)
                    }
                }
                if (isProjectStructurePage) "Opening Project Structure at '$configurable'"
                else "Opening Settings searching for '$configurable'. If the wrong page opened, the name was not found — try a different spelling or use search to find the right actionId."
            }
            actionId != null -> {
                val action = invokeAndWaitIfNeeded { am.getAction(actionId) }
                    ?: return "Action '$actionId' not found. Use search parameter to find valid IDs."
                val label = action.templatePresentation.text
                ApplicationManager.getApplication().invokeLater {
                    val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                        .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                        .build()
                    @Suppress("DEPRECATION")
                    val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                        com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN,
                        action.templatePresentation.clone(),
                        dataContext
                    )
                    action.actionPerformed(event)
                }
                "Action '$actionId' triggered: $label"
            }
            search != null -> {
                invokeAndWaitIfNeeded {
                    val lower = search.lowercase()
                    val matches = am.getActionIdList("")
                        .filter { id ->
                            id.lowercase().contains(lower) ||
                            am.getAction(id)?.templatePresentation?.text?.lowercase()?.contains(lower) == true
                        }
                        .take(30)
                        .joinToString("\n") { id ->
                            val text = am.getAction(id)?.templatePresentation?.text?.takeIf { it.isNotBlank() }
                            if (text != null) "$id  →  $text" else id
                        }
                    if (matches.isEmpty()) "No actions found matching '$search'" else matches
                }
            }
            else -> "Provide 'actionId' to execute an action, or 'search' to find action IDs by keyword."
        }
    }

    // ── delete_file ───────────────────────────────────────────────────────────

    @McpTool(name = "delete_file")
    @McpDescription(description = """
        Deletes a file or an empty directory via the IntelliJ VFS so the IDE is immediately notified.
        Prefer this over shell rm commands when working on a project open in IntelliJ.
        filePath: path relative to the project root.
    """)
    suspend fun delete_file(filePath: String): String {
        disabledMessage("delete_file")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
            val basePath = project.basePath ?: return@invokeAndWaitIfNeeded "Project base path not found"
            val vFile = LocalFileSystem.getInstance().findFileByPath("$basePath/${filePath.replace('\\', '/')}")
                ?: return@invokeAndWaitIfNeeded "File not found: $filePath"
            WriteCommandAction.runWriteCommandAction(project) {
                vFile.delete(this)
            }
            "Deleted: $filePath"
        }
    }
}
