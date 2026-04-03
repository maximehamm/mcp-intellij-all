package io.nimbly.mcpcompanion

import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.project
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import com.intellij.ui.tabs.JBTabs
import io.nimbly.mcpcompanion.tools.readEditorText
import io.nimbly.mcpcompanion.tools.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeModel
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

### Exploration & navigation
- get_open_editors       → know which files are open and where the caret is
- get_project_structure  → list modules, SDK, source roots, dependencies — call this first on a new project
- get_running_processes  → list background tasks (indexing, Gradle sync, …) — call this when IntelliJ seems busy
- manage_process         → cancel / pause / resume a background task by title (partial match)
- navigate_to            → move the caret to a file:line:column (do this before explaining anything)
- select_text            → select an exact range (startCol/endCol are 1-based, endCol is INCLUSIVE)
- highlight_text         → highlight multiple zones at once (declaration + all usages)
                           ranges format: "startLine:startCol:endLine:endCol" comma-separated, endCol INCLUSIVE
- clear_highlights       → remove all highlights (user can also press Escape)

### Build & run
- get_build_output       → read compiler errors before answering a build question
- get_console_output     → read console output from Run AND Debug windows — use this whenever the user ran or debugged something
                           "activeWindow" tells you which window is currently visible; "active: true" marks the focused tab
- get_test_results       → read test results (pass/fail/duration/message)

### Debug
- debug_run_configuration    → launch a named run config in debug mode
- add_conditional_breakpoint → add or update a breakpoint with an optional condition
- get_breakpoints            → list all breakpoints with conditions
- set_breakpoint_condition   → change the condition of an existing breakpoint
- mute_breakpoints           → enable/disable all breakpoints at once
- get_debug_variables        → read local variables from the current stack frame

### Diagnostic & settings
- get_intellij_diagnostic    → notifications, errors, background tasks, ERROR/SEVERE from idea.log (last 5 min) — call this when something looks wrong
- get_ide_settings           → read IntelliJ settings (Gradle, compiler, SDK, encoding…)
                               no param = common settings overview
                               search="gradle" = filter all properties by keyword
                               key="x" = direct lookup of one property
                               prefix="gradle" = all settings whose key starts with "gradle"
                               prefix + depth=1 = limit to one dot-segment beyond the prefix

### IDE actions
- refresh_project            → sync Gradle or Maven — detects the build system automatically
                               use after modifying build.gradle, pom.xml, or when dependencies drift
- execute_ide_action         → execute any IntelliJ action by ID, or search for action IDs by keyword
                               search="settings" = list actions whose name/ID contains "settings"
                               actionId="ShowSettings" = open the Settings dialog
                               actionId="GotoFile", "ReformatCode", "OptimizeImports", "Git.Branches", …

### Editing & file operations
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

**"Debug this"**
  1. add_conditional_breakpoint — set breakpoint with condition
  2. debug_run_configuration — start debug session
  3. get_debug_variables — inspect variables once paused
        """.trimIndent()
    }

    // ── get_open_editors ──────────────────────────────────────────────────────

    @McpTool(name = "get_open_editors")
    @McpDescription(description = """
        Returns information about all files currently open in the IntelliJ editor.
        For each open file, the absolute path is returned.
        For the file that last had focus (active editor), also returns:
        - currentLine: 1-based line number of the caret
        - selection: if a selection exists, its text and start/end line numbers (1-based)
    """)
    suspend fun get_open_editors(): String {
        disabledMessage("get_open_editors")?.let { return it }
        val project = coroutineContext.project
        val state = runReadAction { buildEditorState(project) }
        return Json.encodeToString(state)
    }

    private fun buildEditorState(project: com.intellij.openapi.project.Project): EditorState {
        val fem = FileEditorManager.getInstance(project)
        val openFiles = fem.openFiles.map { it.path }
        val focusedEditor = fem.selectedTextEditor?.let { editor ->
            val filePath = fem.selectedFiles.firstOrNull()?.path ?: return@let null
            val document = editor.document
            val line = editor.caretModel.logicalPosition.line + 1
            val selectionModel = editor.selectionModel
            val selection = if (selectionModel.hasSelection()) {
                val startOffset = selectionModel.selectionStart
                val endOffset = selectionModel.selectionEnd
                SelectionInfo(
                    text = selectionModel.selectedText.orEmpty(),
                    startLine = document.getLineNumber(startOffset) + 1,
                    endLine = document.getLineNumber(endOffset) + 1,
                )
            } else null
            FocusedEditorInfo(path = filePath, currentLine = line, selection = selection)
        }
        return EditorState(openFiles = openFiles, focusedEditor = focusedEditor)
    }

    // ── get_build_output ──────────────────────────────────────────────────────

    @McpTool(name = "get_build_output")
    @McpDescription(description = """
        Returns the content of the "Build" tool window in IntelliJ.
        Includes all open tabs with:
        - tree: structured list of build nodes (tasks, errors, warnings) with file and line number when available
        - console: the raw text output
        Useful to read compilation errors, warnings, and build results.
    """)
    suspend fun get_build_output(): String {
        disabledMessage("get_build_output")?.let { return it }
        val project = coroutineContext.project
        val tabs = invokeAndWaitIfNeeded { extractBuildTabs(project) }
        return Json.encodeToString(BuildOutput(tabs))
    }

    private fun extractBuildTabs(project: com.intellij.openapi.project.Project): List<BuildTab> {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Build")
            ?: return listOf(BuildTab(name = "error", tree = null, console = "Build tool window not found"))
        return toolWindow.contentManager.contents.map { content ->
            val trees = UIUtil.findComponentsOfType(content.component, JTree::class.java)
            val firstTree = trees.firstOrNull()
            val treeNodes = firstTree?.model?.let { buildBuildNodes(it, it.root) }
            val consoles = UIUtil.findComponentsOfType(content.component, ConsoleViewImpl::class.java)
            val consoleText = consoles.mapNotNull { it.editor?.document?.text?.trim()?.ifEmpty { null } }
                .joinToString("\n---\n").ifEmpty { null }
            BuildTab(
                name = content.displayName ?: "Build",
                tree = treeNodes?.ifEmpty { null },
                console = consoleText,
            )
        }
    }

    private fun buildBuildNodes(model: TreeModel, node: Any): List<BuildNode> {
        val result = mutableListOf<BuildNode>()
        val childCount = model.getChildCount(node)
        for (i in 0 until childCount) result.add(toBuildNode(model, model.getChild(node, i)))
        return result
    }

    private fun toBuildNode(model: TreeModel, node: Any): BuildNode {
        val userObject = (node as? DefaultMutableTreeNode)?.userObject ?: node
        var text = userObject.toString().trim()
        var file: String? = null
        var line: Int? = null
        val cls = userObject.javaClass
        val title = try { cls.methods.find { it.name == "getTitle" }?.invoke(userObject) as? String } catch (_: Exception) { null }
        val hint  = try { cls.methods.find { it.name == "getHint"  }?.invoke(userObject) as? String } catch (_: Exception) { null }
        if (!title.isNullOrEmpty()) text = if (!hint.isNullOrEmpty()) "$title — $hint" else title
        val element: Any = try { cls.methods.find { it.name == "getElement" }?.invoke(userObject) ?: userObject } catch (_: Exception) { userObject }
        val elCls = element.javaClass
        val rawNavs = try { elCls.methods.find { it.name == "getNavigatables" }?.invoke(element) } catch (_: Exception) { null }
        val firstNav: Any? = when (rawNavs) {
            is Array<*> -> rawNavs.firstOrNull()
            is List<*>  -> rawNavs.firstOrNull()
            else -> try { elCls.methods.find { it.name == "getNavigatable" }?.invoke(element) } catch (_: Exception) { null }
        }
        if (firstNav != null) {
            val navCls = firstNav.javaClass
            val descriptor = try { navCls.methods.find { it.name == "getFileDescriptor" }?.invoke(firstNav) } catch (_: Exception) { null }
                ?: try { navCls.methods.find { it.name == "getFile" }?.invoke(firstNav) } catch (_: Exception) { null }
            if (descriptor != null) {
                val dCls = descriptor.javaClass
                val vFile = try { dCls.methods.find { it.name == "getFile" }?.invoke(descriptor) } catch (_: Exception) { null }
                if (vFile != null) {
                    file = try { vFile.javaClass.methods.find { it.name == "getName" }?.invoke(vFile) as? String } catch (_: Exception) { null }
                }
                val rawLine = try { dCls.methods.find { it.name == "getLine" }?.invoke(descriptor) as? Int } catch (_: Exception) { null }
                line = if (rawLine != null && rawLine >= 0) rawLine + 1 else null
            }
        }
        if (file == null) {
            val vFile = try { elCls.methods.find { it.name == "getVirtualFile" }?.invoke(element) } catch (_: Exception) { null }
            if (vFile != null) {
                file = try { vFile.javaClass.methods.find { it.name == "getName" }?.invoke(vFile) as? String } catch (_: Exception) { null }
            }
        }
        // Try standard methods first
        var detail = listOf("getDescription", "getTooltipText", "getMessage", "getDetails", "getHint")
            .firstNotNullOfOrNull { name ->
                try { cls.methods.find { it.name == name }?.invoke(userObject) as? String }
                catch (_: Exception) { null }
            }?.trim()?.takeIf { it.isNotEmpty() && it != text }

        // Try navigatables descriptions (for BuildIssue-based nodes like "Incompatible Gradle JVM")
        if (detail == null) {
            val navsObj = try { cls.methods.find { it.name == "getNavigatables" }?.invoke(userObject) } catch (_: Exception) { null }
            val navsList: List<*>? = when (navsObj) {
                is List<*> -> navsObj
                is Array<*> -> navsObj.toList()
                else -> null
            }
            detail = navsList?.firstNotNullOfOrNull { nav ->
                if (nav == null) return@firstNotNullOfOrNull null
                val nCls = nav.javaClass
                listOf("getDescription", "getMessage", "getTitle", "getText")
                    .firstNotNullOfOrNull { mName ->
                        try { nCls.methods.find { it.name == mName }?.invoke(nav) as? String }
                        catch (_: Exception) { null }
                    }?.trim()?.takeIf { it.isNotEmpty() && it != text }
            }
        }

        // Try private fields as last resort
        if (detail == null) {
            var c: Class<*>? = cls
            outer@ while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) {
                    if (f.name.lowercase() in setOf("description", "message", "detail", "details", "tooltip")) {
                        try {
                            f.isAccessible = true
                            val v = f.get(userObject) as? String
                            if (!v.isNullOrEmpty() && v != text) { detail = v.trim(); break@outer }
                        } catch (_: Exception) {}
                    }
                }
                c = c.superclass
            }
        }

        // Severity from BuildTreeNode flags
        val severity = when {
            (try { cls.methods.find { it.name == "getHasProblems" }?.invoke(userObject) as? Boolean } catch (_: Exception) { null }) == true -> "ERROR"
            (try { cls.methods.find { it.name == "getVisibleAsWarning" }?.invoke(userObject) as? Boolean } catch (_: Exception) { null }) == true -> "WARNING"
            (try { cls.methods.find { it.name == "getVisibleAsSuccessful" }?.invoke(userObject) as? Boolean } catch (_: Exception) { null }) == true -> "SUCCESS"
            else -> null
        }

        val children = buildBuildNodes(model, node).ifEmpty { null }
        return BuildNode(text = text, severity = severity, detail = detail, file = file, line = line, children = children)
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

    // ── get_services_output ───────────────────────────────────────────────────

    @McpTool(name = "get_services_output")
    @McpDescription(description = """
        Returns the output of all sessions visible in the "Services" tool window, grouped by session node.
        For each session (e.g. console_1, script.sql):
        - name: the session node name from the Services tree
        - output: raw text from the Output tab (SQL executed, logs, etc.)
        - results: list of result grids, each with a name and tabular data (column headers + rows)
        Covers database sessions, run configurations, Spring Boot apps, etc.
    """)
    suspend fun get_services_output(): String {
        disabledMessage("get_services_output")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Services")
                ?: return@invokeAndWaitIfNeeded Json.encodeToString(
                    ServicesOutput(sessions = listOf(ServiceSession(name = "error", output = "Services tool window not found"))))

            // Tabbed mode: "Open in new tab" creates ContentManager contents (one per session + "All Services")
            val contents = runCatching { toolWindow.contentManager.contents.toList() }.getOrElse { emptyList() }
            val sessionContents = contents.filter { it.displayName != "All Services" && !it.displayName.isNullOrBlank() }
            // Tabbed mode is only active when "All Services" tab exists (created by "Open in new tab")
            val isTabbedMode = sessionContents.isNotEmpty() && contents.any { it.displayName == "All Services" }

            val rootComponent = if (isTabbedMode)
                // Use the "All Services" content component for tree search, or fall back to tool window component
                (contents.firstOrNull { it.displayName == "All Services" }?.component as? javax.swing.JComponent
                    ?: toolWindow.component)
            else
                toolWindow.component

            // Step 1: find ServiceViewTree (JTree in the left panel) and collect leaf node names
            val tree = UIUtil.findComponentsOfType(rootComponent, javax.swing.JTree::class.java)
                .firstOrNull { it.javaClass.name.contains("ServiceViewTree") }

            fun nodeDisplayName(node: Any, sourceTree: javax.swing.JTree? = null): String {
                // Try cell renderer — it knows how to render the node
                sourceTree?.let { t ->
                    runCatching {
                        val comp = t.cellRenderer.getTreeCellRendererComponent(t, node, false, false, true, 0, false)
                        // Try accessible name (works for SimpleColoredComponent and JLabel)
                        comp.accessibleContext?.accessibleName?.takeIf { it.isNotBlank() }
                            ?: run {
                                // Try SimpleColoredComponent.getCharSequence()
                                runCatching {
                                    comp.javaClass.getMethod("getCharSequence", Boolean::class.javaPrimitiveType)
                                        .invoke(comp, false)?.toString()?.takeIf { it.isNotBlank() }
                                }.getOrNull()
                            }
                            ?: run {
                                // Walk hierarchy for JLabel
                                fun labelText(c: java.awt.Component): String? {
                                    if (c is javax.swing.JLabel) return c.text?.takeIf { it.isNotBlank() }
                                    if (c is java.awt.Container) c.components.forEach { labelText(it)?.let { txt -> return txt } }
                                    return null
                                }
                                labelText(comp)
                            }
                    }.getOrNull()?.let { return it }
                }
                // Try getViewDescriptor().getPresentation().getPresentableText()
                runCatching {
                    val desc = node.javaClass.getMethod("getViewDescriptor").invoke(node)
                    val pres = desc?.javaClass?.getMethod("getPresentation")?.invoke(desc)
                    pres?.javaClass?.getMethod("getPresentableText")?.invoke(pres)?.toString()
                }.getOrNull()?.let { return it }
                // Try getPresentation().getPresentableText()
                runCatching {
                    val pres = node.javaClass.getMethod("getPresentation").invoke(node)
                    pres?.javaClass?.getMethod("getPresentableText")?.invoke(pres)?.toString()
                }.getOrNull()?.let { return it }
                // Try getName() / getText() / getDisplayName()
                for (methodName in listOf("getName", "getText", "getDisplayName")) {
                    runCatching {
                        node.javaClass.getMethod(methodName).invoke(node)?.toString()
                    }.getOrNull()?.let { return it }
                }
                return node.toString()
            }
            // Strip trailing timing suffix added by the renderer (e.g. "script.sql  361 ms" → "script.sql")
            // Normalize all non-ASCII / Unicode-space chars to regular space before tokenizing
            fun cleanNodeName(raw: String): String {
                val normalized = raw.map { if (it.isWhitespace() || it.code > 127) ' ' else it }.joinToString("")
                val tokens = normalized.trim().split(Regex(" +")).filter { it.isNotEmpty() }.toMutableList()
                // Strip all trailing "<number> <unit>" pairs (e.g. "1 s 240 ms" → stripped twice)
                while (tokens.size >= 2 && tokens.last().lowercase() in listOf("ms", "s")) {
                    tokens.removeAt(tokens.size - 1)
                    if (tokens.isNotEmpty() && tokens.last().matches(Regex("""\d+(\.\d+)?""")))
                        tokens.removeAt(tokens.size - 1)
                }
                return if (tokens.isEmpty()) raw.trim() else tokens.joinToString(" ")
            }
            val leaves = mutableListOf<String>()
            if (tree != null) {
                fun collectLeaves(model: TreeModel, node: Any) {
                    val childCount = model.getChildCount(node)
                    if (childCount == 0) {
                        leaves += cleanNodeName(nodeDisplayName(node, tree))
                    } else {
                        for (i in 0 until childCount) collectLeaves(model, model.getChild(node, i))
                    }
                }
                val model = tree.model
                if (model != null && model.root != null) collectLeaves(model, model.root)
            }

            // Step 2: find ServiceViewComponentWrapper components (right panel per session)
            @Suppress("UNCHECKED_CAST")
            val wrappers = UIUtil.findComponentsOfType(rootComponent, javax.swing.JComponent::class.java)
                .filter { it.javaClass.name.contains("ServiceViewComponentWrapper") }

            // Step 3: extract session content from each wrapper
            fun extractGridText(table: javax.swing.JTable): String {
                val model = table.model
                val cols = (0 until table.columnModel.columnCount).map { colIdx ->
                    table.columnModel.getColumn(colIdx).headerValue?.toString()
                        ?: model.getColumnName(colIdx)
                }
                val rows = (0 until model.rowCount).map { row ->
                    cols.indices.map { col -> model.getValueAt(row, col)?.toString() ?: "" }
                }
                return buildString {
                    appendLine(cols.joinToString(" | "))
                    appendLine("-".repeat(cols.sumOf { it.length + 3 }))
                    rows.forEach { row -> appendLine(row.joinToString(" | ")) }
                    append("${rows.size} row(s)")
                }
            }

            // Determine selected session name from tree selection
            val selectedLeafName: String? = tree?.let { t ->
                t.selectionPath?.lastPathComponent?.let { node -> cleanNodeName(nodeDisplayName(node, t)) }
            }

            // Use JBTabs (public interface) via Class.forName to avoid @Internal JBTabsImpl reference
            @Suppress("UNCHECKED_CAST")
            val jbTabsClass = runCatching {
                Class.forName("com.intellij.ui.tabs.impl.JBTabsImpl") as Class<javax.swing.JComponent>
            }.getOrNull()

            fun findJBTabs(component: javax.swing.JComponent): List<com.intellij.ui.tabs.JBTabs> =
                if (jbTabsClass != null)
                    UIUtil.findComponentsOfType(component, jbTabsClass).filterIsInstance<com.intellij.ui.tabs.JBTabs>()
                else
                    UIUtil.findComponentsOfType(component, javax.swing.JComponent::class.java).filterIsInstance<JBTabs>()

            fun processWrapper(name: String, wrapper: javax.swing.JComponent): ServiceSession {
                val tabs = findJBTabs(wrapper).firstOrNull()
                var output: String? = null
                var outputSelected = false
                val results = mutableListOf<ServiceResult>()

                if (tabs != null) {
                    val selectedTabInfo = tabs.selectedInfo
                    for (tabInfo in tabs.tabs) {
                        val tabTitle = tabInfo.text
                        val component = tabInfo.component as? javax.swing.JComponent ?: continue
                        val isTabSelected = selectedTabInfo === tabInfo

                        // Output tab: ConsoleViewImpl or EditorComponentImpl
                        val console = UIUtil.findComponentOfType(component, ConsoleViewImpl::class.java)
                        if (console != null) {
                            output = console.readText()?.ifBlank { null }
                            outputSelected = isTabSelected
                            continue
                        }
                        val editorText = readEditorText(component)
                        if (editorText != null) {
                            output = editorText
                            outputSelected = isTabSelected
                            continue
                        }

                        // Result grid tab: TableResultView (JTable subclass)
                        val table = UIUtil.findComponentsOfType(component, javax.swing.JTable::class.java)
                            .firstOrNull { it.javaClass.name.contains("TableResult", ignoreCase = true) }
                        if (table != null) {
                            results += ServiceResult(name = tabTitle, selected = isTabSelected, data = extractGridText(table))
                        }
                    }
                } else {
                    // No JBTabs — try direct console or editor
                    val console = UIUtil.findComponentOfType(wrapper, ConsoleViewImpl::class.java)
                    if (console != null) output = console.readText()?.ifBlank { null }
                    else output = readEditorText(wrapper)
                }
                return ServiceSession(name = name, selected = name == selectedLeafName,
                    output = output, outputSelected = outputSelected, results = results)
            }

            // Mode detection: tabbed mode has an outer JBTabs containing an "All Services" tab
            val outerTabsImpl = findJBTabs(rootComponent)
                .firstOrNull { tabs -> tabs.tabs.any { it.text == "All Services" } }

            val selectedContentName = runCatching { toolWindow.contentManager.selectedContent?.displayName }.getOrNull()

            val sessions: List<ServiceSession> = when {
                isTabbedMode -> {
                    // Tabbed mode: ContentManager contents are the sessions
                    sessionContents.mapNotNull { content ->
                        val name = cleanNodeName(content.displayName ?: return@mapNotNull null)
                        val component = content.component as? javax.swing.JComponent ?: return@mapNotNull null
                        val isSelected = content.displayName == selectedContentName
                        processWrapper(name, component).copy(selected = isSelected)
                    }
                }
                outerTabsImpl != null -> {
                    // JBTabs outer mode (fallback)
                    val selectedTabInfo = outerTabsImpl.selectedInfo
                    outerTabsImpl.tabs
                        .filter { it.text != "All Services" }
                        .mapNotNull { tabInfo ->
                            val name = cleanNodeName(tabInfo.text)
                            val component = tabInfo.component as? javax.swing.JComponent ?: return@mapNotNull null
                            val isSelected = selectedTabInfo === tabInfo
                            processWrapper(name, component).copy(selected = isSelected)
                        }
                }
                wrappers.isNotEmpty() -> {
                    // En mode arbre, seul le wrapper de la session active est rendu.
                    // On utilise selectedLeafName pour le nommer correctement.
                    if (wrappers.size == 1 && selectedLeafName != null)
                        listOf(processWrapper(selectedLeafName, wrappers[0]))
                    else if (leaves.size == wrappers.size && leaves.isNotEmpty())
                        leaves.zip(wrappers).map { (name, wrapper) -> processWrapper(name, wrapper) }
                    else
                        wrappers.mapIndexed { i, wrapper -> processWrapper("session_${i + 1}", wrapper) }
                }
                else -> emptyList()
            }

            if (sessions.isEmpty())
                Json.encodeToString(ServicesOutput(sessions = listOf(ServiceSession(name = "info", output = "No service sessions found"))))
            else
                Json.encodeToString(ServicesOutput(sessions))
        }
    }

    // ── get_ide_settings ─────────────────────────────────────────────────────

    @McpTool(name = "get_ide_settings")
    @McpDescription(description = """
        Reads IntelliJ IDE settings.
        - No parameters: returns the most useful settings (build tool, SDK, Gradle, compiler, encoding…)
        - search: filters all known settings by keyword (substring match on key name)
        - key: reads one specific setting key directly
        - prefix: returns all settings whose key starts with the given prefix (e.g. "gradle")
          combine with depth to limit how many dot-separated segments are returned beyond the prefix
          (e.g. prefix="gradle", depth=1 → only "gradle.xxx" keys, not "gradle.xxx.yyy")
        Results are returned as a flat JSON map of key → value.
    """)
    suspend fun get_ide_settings(search: String? = null, key: String? = null, prefix: String? = null, depth: Int? = null): String {
        disabledMessage("get_ide_settings")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
            val known = knownIdeSettings(project)
            val results = linkedMapOf<String, String?>()
            when {
                key != null -> {
                    // Direct PropertiesComponent lookup, then known settings
                    val pc = com.intellij.ide.util.PropertiesComponent.getInstance()
                    val pcProj = com.intellij.ide.util.PropertiesComponent.getInstance(project)
                    results[key] = pc.getValue(key) ?: pcProj.getValue(key) ?: known[key]
                }
                prefix != null -> {
                    val normalizedPrefix = if (prefix.endsWith(".")) prefix else "$prefix."
                    val prefixSegments = normalizedPrefix.trimEnd('.').split(".").size
                    known.filter { (k, _) -> k.startsWith(normalizedPrefix) || k == prefix }
                        .filter { (k, _) ->
                            if (depth == null) true
                            else k.split(".").size <= prefixSegments + depth
                        }
                        .forEach { (k, v) -> results[k] = v }
                }
                search != null -> {
                    val lower = search.lowercase()
                    known.filter { (k, _) -> k.lowercase().contains(lower) }
                        .forEach { (k, v) -> results[k] = v }
                }
                else -> results.putAll(known)
            }
            Json.encodeToString(results as Map<String, String?>)
        }
    }

    private fun knownIdeSettings(project: com.intellij.openapi.project.Project): Map<String, String?> {
        val map = linkedMapOf<String, String?>()

        // Project name & path
        map["project.name"]     = project.name
        map["project.basePath"] = project.basePath

        // Project SDK
        runCatching {
            val sdk = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).projectSdk
            map["project.sdk.name"]    = sdk?.name
            map["project.sdk.type"]    = sdk?.sdkType?.name
            map["project.sdk.version"] = sdk?.versionString
        }

        // Gradle settings (via reflection — uses Gradle plugin's own classloader)
        // Silently skipped when Gradle plugin is not available (PyCharm, WebStorm, etc.)
        val gradlePlugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId("com.intellij.gradle")
        )
        if (gradlePlugin != null) {
            val gradleError = runCatching {
                val gsClass = Class.forName("org.jetbrains.plugins.gradle.settings.GradleSettings", true, gradlePlugin.classLoader)
                val gs = gsClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java).invoke(null, project)
                val linked = gs.javaClass.getMethod("getLinkedProjectsSettings").invoke(gs)
                val all = (linked as? Iterable<*>)?.toList() ?: emptyList<Any>()
                map["gradle.linkedProjects.count"] = all.size.toString()
                val first = all.firstOrNull()
                if (first != null) {
                    map["gradle.delegatedBuild"]           = runCatching { first.javaClass.getMethod("getDelegatedBuild").invoke(first)?.toString() }.getOrNull()
                    map["gradle.gradleJvm"]                = runCatching { first.javaClass.getMethod("getGradleJvm").invoke(first)?.toString() }.getOrNull()
                    map["gradle.gradleHome"]               = runCatching { first.javaClass.getMethod("getGradleHome").invoke(first)?.toString() }.getOrNull()
                    map["gradle.testRunner"]               = runCatching { first.javaClass.getMethod("getTestRunner").invoke(first)?.toString() }.getOrNull()
                    map["gradle.resolveModulePerSourceSet"]= runCatching { first.javaClass.getMethod("isResolveModulePerSourceSet").invoke(first)?.toString() }.getOrNull()
                }
            }.exceptionOrNull()
            if (gradleError != null) map["gradle.error"] = gradleError.message ?: gradleError.toString()
        }

        // Compiler configuration (via reflection)
        runCatching {
            val ccClass = Class.forName("com.intellij.compiler.CompilerConfiguration")
            val cc = ccClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java).invoke(null, project)
            map["compiler.projectBytecodeTarget"] = runCatching { cc.javaClass.getMethod("getProjectBytecodeTarget").invoke(cc)?.toString() }.getOrNull()
            map["compiler.addNotNullAssertions"]  = runCatching { cc.javaClass.getMethod("isAddNotNullAssertions").invoke(cc)?.toString() }.getOrNull()
        }

        // Encoding
        runCatching {
            val em = com.intellij.openapi.vfs.encoding.EncodingProjectManager.getInstance(project)
            map["encoding.default"] = em.defaultCharsetName
            runCatching { map["encoding.nativesToAscii"] = em.javaClass.getMethod("isNativesToAscii").invoke(em)?.toString() }
        }

        // Git settings (via reflection — silently skipped when Git plugin is absent)
        val gitPlugin = listOf("Git4Idea", "com.intellij.vcs.git", "git4idea")
            .mapNotNull { com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId(it)) }
            .firstOrNull()
        if (gitPlugin != null) {
            runCatching {
                val cl = gitPlugin.classLoader
                val gsClass = Class.forName("git4idea.config.GitVcsSettings", true, cl)
                val gs = gsClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java).invoke(null, project)
                map["git.pathToGit"]              = runCatching { gs.javaClass.getMethod("getPathToGit").invoke(gs)?.toString() }.getOrNull()
                map["git.updateMethod"]           = runCatching { gs.javaClass.getMethod("getUpdateMethod").invoke(gs)?.toString() }.getOrNull()
                map["git.syncSetting"]            = runCatching { gs.javaClass.getMethod("getSyncSetting").invoke(gs)?.toString() }.getOrNull()
                map["git.autoFetch"]              = runCatching { gs.javaClass.getMethod("isAutoFetch").invoke(gs)?.toString() }.getOrNull()
                map["git.autoFetchIntervalMin"]   = runCatching { gs.javaClass.getMethod("getAutoFetchSleepIntervalInMinutes").invoke(gs)?.toString() }.getOrNull()
                map["git.signOffCommit"]          = runCatching { gs.javaClass.getMethod("isSignOffCommit").invoke(gs)?.toString() }.getOrNull()
                map["git.commitMessageTempl"]     = runCatching { gs.javaClass.getMethod("getCommitMessageTemplate").invoke(gs)?.toString() }.getOrNull()
            }.onFailure { map["git.error"] = it.message ?: it.toString() }
        }

        return map
    }

    private fun buildTreePath(tree: javax.swing.JTree, target: Any): javax.swing.tree.TreePath? {
        val model = tree.model ?: return null
        fun search(node: Any, path: List<Any>): List<Any>? {
            if (node === target) return path + node
            for (i in 0 until model.getChildCount(node)) {
                val found = search(model.getChild(node, i), path + node)
                if (found != null) return found
            }
            return null
        }
        val nodes = search(model.root, emptyList()) ?: return null
        return javax.swing.tree.TreePath(nodes.toTypedArray())
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
                // Project Structure pages (SDKs, Modules, Libraries, Artifacts…) require a different dialog
                val isProjectStructurePage = lower in setOf("sdks", "modules", "libraries", "artifacts", "facets", "project", "problems", "global libraries")
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if (isProjectStructurePage) {
                        // Open Project Structure dialog via its action
                        val psAction = am.getAction("ShowProjectStructureSettings")
                        if (psAction != null) {
                            val frame = com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project)
                            // Start Timer BEFORE tryToExecute — tryToExecute blocks the EDT inside the modal loop
                            // so anything after it never runs. Timer fires inside the modal secondary loop.
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
                            // Open dialog AFTER starting the timer
                            am.tryToExecute(psAction, null, frame, com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN, true)
                        }
                    } else {
                        // Non-PS page: delegate to Settings dialog (works for Gradle, Editor, Plugins, SSH Terminal…)
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
                // Use invokeLater so the EDT is free to show modal dialogs (Settings, etc.)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
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

    // ── refresh_project ──────────────────────────────────────────────────────

    @McpTool(name = "refresh_project")
    @McpDescription(description = """
        Refreshes (reimports/syncs) the project build system configuration.
        Automatically detects Gradle or Maven from the project root and triggers the appropriate sync action.
        Use this after modifying build.gradle, pom.xml, settings.gradle, or when dependencies are out of sync.
        Returns what was triggered, or an error if no build system is detected.
    """)
    suspend fun refresh_project(): String {
        disabledMessage("refresh_project")?.let { return it }
        val project = coroutineContext.project
        val basePath = project.basePath ?: return "Cannot determine project base path"
        val am = com.intellij.openapi.actionSystem.ActionManager.getInstance()

        val rootFiles = java.io.File(basePath).listFiles()?.map { it.name } ?: emptyList()
        val hasGradle = rootFiles.any { it in listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") }
        val hasMaven = "pom.xml" in rootFiles

        if (!hasGradle && !hasMaven)
            return "No Gradle or Maven build files found in project root ($basePath)"

        val results = mutableListOf<String>()

        fun triggerAction(actionId: String, label: String) {
            val action = invokeAndWaitIfNeeded { am.getAction(actionId) } ?: run {
                results += "$label: action '$actionId' not available in this IDE"
                return
            }
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
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
            results += "$label sync triggered"
        }

        if (hasGradle) triggerAction("ExternalSystem.RefreshAllProjects", "Gradle")
        if (hasMaven)  triggerAction("Maven.Reimport", "Maven")

        return results.joinToString("\n")
    }

    // ── get_console_output ───────────────────────────────────────────────────

    @McpTool(name = "get_console_output")
    @McpDescription(description = """
        Returns console output from both the "Run" and "Debug" tool windows.
        Includes all open tabs from each window with their console text.
        - activeWindow: "run" or "debug" — whichever window is currently visible/focused
        - active: true on the tab that is currently selected within its window
        - Run tabs also include a structured error/warning tree when available
        Use this whenever the user ran or debugged something and you need to see the output.
    """)
    suspend fun get_console_output(): String {
        disabledMessage("get_console_output")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
            val runWindow = ToolWindowManager.getInstance(project).getToolWindow("Run")
            val debugWindow = ToolWindowManager.getInstance(project).getToolWindow("Debug")

            val runVisible = runWindow?.isVisible == true
            val debugVisible = debugWindow?.isVisible == true
            val activeWindow: String? = when {
                debugVisible && !runVisible -> "debug"
                runVisible && !debugVisible -> "run"
                debugVisible && runVisible ->
                    if (XDebuggerManager.getInstance(project).debugSessions.isNotEmpty()) "debug" else "run"
                else -> null
            }

            val selectedRunContent = runWindow?.contentManager?.selectedContent
            val runTabs = runWindow?.contentManager?.contents?.map { content ->
                val trees = UIUtil.findComponentsOfType(content.component, JTree::class.java)
                val treeNodes = trees.firstOrNull()?.model?.let { buildBuildNodes(it, it.root) }
                val consoles = UIUtil.findComponentsOfType(content.component, ConsoleViewImpl::class.java)
                val consoleText = consoles.mapNotNull { it.readText()?.ifEmpty { null } }
                    .joinToString("\n---\n").ifEmpty { null }
                ConsoleTab(
                    name = content.displayName ?: "Run",
                    active = content === selectedRunContent,
                    tree = treeNodes?.ifEmpty { null },
                    console = consoleText
                )
            } ?: emptyList()

            val activeSessions = XDebuggerManager.getInstance(project).debugSessions
            val sessionByName = activeSessions.associateBy { it.sessionName }
            val selectedDebugContent = debugWindow?.contentManager?.selectedContent
            val debugTabs = debugWindow?.contentManager?.contents?.map { content ->
                val name = content.displayName ?: "Debug"
                val session = sessionByName[name]
                val consoleFromSession = session?.consoleView?.let { cv ->
                    (cv as? ConsoleViewImpl)?.readText()
                        ?: UIUtil.findComponentsOfType(cv.component, ConsoleViewImpl::class.java)
                            .mapNotNull { it.readText()?.ifEmpty { null } }
                            .joinToString("\n---\n").ifEmpty { null }
                }
                val consoleText = consoleFromSession
                    ?: UIUtil.findComponentsOfType(content.component, ConsoleViewImpl::class.java)
                        .mapNotNull { it.readText()?.ifEmpty { null } }
                        .joinToString("\n---\n").ifEmpty { null }
                ConsoleTab(name = name, active = content === selectedDebugContent, console = consoleText)
            } ?: emptyList()

            Json.encodeToString(ConsoleOutput(activeWindow = activeWindow, run = runTabs, debug = debugTabs))
        }
    }

    // ── get_test_results ──────────────────────────────────────────────────────

    @McpTool(name = "get_test_results")
    @McpDescription(description = """
        Returns the results of the last test run from the IntelliJ test runner.
        Includes all test suites and individual tests with:
        - status: PASSED, FAILED, IGNORED, or RUNNING
        - duration: execution time in milliseconds (if available)
        - errorMessage: failure message and stack trace if the test failed
        Useful to check which tests passed or failed and why.
    """)
    suspend fun get_test_results(): String {
        disabledMessage("get_test_results")?.let { return it }
        val project = coroutineContext.project
        val output = invokeAndWaitIfNeeded { extractTestResults(project) }
        return Json.encodeToString(output)
    }

    private fun extractTestResults(project: com.intellij.openapi.project.Project): TestRunOutput {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Run")
            ?: return TestRunOutput(runs = emptyList(), error = "Run tool window not found")
        val runs = toolWindow.contentManager.contents.mapNotNull { content ->
            val form = UIUtil.findComponentOfType(content.component, SMTestRunnerResultsForm::class.java)
                ?: return@mapNotNull null
            val root = form.testsRootNode
            TestRun(
                name = content.displayName ?: "Test",
                tests = root.children.map { buildTestNode(it) }
            )
        }
        if (runs.isEmpty()) return TestRunOutput(runs = emptyList(), error = "No test results found")
        return TestRunOutput(runs = runs)
    }

    private fun buildTestNode(proxy: SMTestProxy): TestNode {
        val status = when {
            proxy.isPassed  -> "PASSED"
            proxy.isDefect  -> "FAILED"
            proxy.isIgnored -> "IGNORED"
            else            -> "RUNNING"
        }
        val durationMs = proxy.duration?.takeIf { it >= 0 }
        val children = proxy.children.takeIf { it.isNotEmpty() }?.map { buildTestNode(it) }
        return TestNode(
            name = proxy.name,
            status = status,
            duration = durationMs,
            errorMessage = proxy.errorMessage?.takeIf { it.isNotEmpty() },
            children = children
        )
    }

    // ── get_debug_variables ───────────────────────────────────────────────────

    @McpTool(name = "get_debug_variables")
    @McpDescription(description = """
        Returns the local variables and their values from the current debugger stack frame.
        Only available when a debug session is paused at a breakpoint.
        Useful to inspect variable state during debugging.
    """)
    suspend fun get_debug_variables(): String {
        disabledMessage("get_debug_variables")?.let { return it }
        val project = coroutineContext.project
        val sessions = XDebuggerManager.getInstance(project).debugSessions
        if (sessions.isEmpty()) return "No active debug session"
        val session = sessions.first()
        val frame = invokeAndWaitIfNeeded { session.currentStackFrame }
            ?: return "No stack frame — program may still be running"

        val children = mutableListOf<Pair<String, XValue>>()
        val childrenLatch = CountDownLatch(1)
        invokeAndWaitIfNeeded {
            frame.computeChildren(object : XCompositeNode {
                override fun addChildren(list: XValueChildrenList, last: Boolean) {
                    for (i in 0 until list.size()) children.add(list.getName(i) to list.getValue(i))
                    if (last) childrenLatch.countDown()
                }
                override fun tooManyChildren(remaining: Int) { childrenLatch.countDown() }
                override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) { childrenLatch.countDown() }
                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) { childrenLatch.countDown() }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { childrenLatch.countDown() }
                override fun setMessage(message: String, icon: Icon?, attributes: com.intellij.ui.SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
                override fun isObsolete() = false
            })
        }
        childrenLatch.await(5, TimeUnit.SECONDS)

        val variables = children.map { (name, xValue) -> resolveDebugVariable(name, xValue) }
        return Json.encodeToString(DebugVariablesOutput(session = session.sessionName, variables = variables))
    }

    // ── add_breakpoint ────────────────────────────────────────────────────────

    @McpTool(name = "add_conditional_breakpoint")
    @McpDescription(description = """
        Adds a line breakpoint with a condition in a single call.
        filePath: path relative to the project root (e.g. "src/main/java/Foo.java").
        line: 1-based line number.
        condition: condition expression (e.g. "i == 3"). Leave empty for unconditional.
        If a breakpoint already exists at that line, its condition is updated instead.
    """)
    suspend fun add_conditional_breakpoint(filePath: String, line: Int, condition: String = ""): String {
        disabledMessage("add_conditional_breakpoint")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
            val basePath = project.basePath ?: return@invokeAndWaitIfNeeded "Project base path not found"
            val normalizedPath = filePath.replace('\\', '/')
            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .findFileByPath("$basePath/$normalizedPath")
                ?: return@invokeAndWaitIfNeeded "File not found: $filePath"
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val existing = manager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                .firstOrNull { it.line == line - 1 && it.presentableFilePath.endsWith(normalizedPath) }
            if (existing == null) {
                com.intellij.xdebugger.XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file, line - 1, false)
            }
            val bp = existing ?: run {
                // toggleLineBreakpoint may be async — poll briefly
                var found: com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>? = null
                repeat(10) {
                    found = manager.allBreakpoints
                        .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                        .firstOrNull { it.line == line - 1 && it.presentableFilePath.endsWith(normalizedPath) }
                    if (found == null) Thread.sleep(100)
                }
                found ?: return@invokeAndWaitIfNeeded "Failed to create breakpoint at $filePath:$line"
            }
            val expr = if (condition.isNotBlank())
                com.intellij.xdebugger.XDebuggerUtil.getInstance()
                    .createExpression(condition, null, null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION)
            else null
            bp.conditionExpression = expr
            val action = if (existing != null) "updated" else "added"
            if (condition.isNotBlank()) "Breakpoint $action at $filePath:$line with condition: $condition"
            else "Breakpoint $action at $filePath:$line"
        }
    }

    // ── get_breakpoints ───────────────────────────────────────────────────────

    @McpTool(name = "get_breakpoints")
    @McpDescription(description = """
        Returns all line breakpoints in the project with their file, line, enabled state, and condition (if any).
        Use this to inspect existing breakpoints before modifying them with set_breakpoint_condition.
    """)
    suspend fun get_breakpoints(): String {
        disabledMessage("get_breakpoints")?.let { return it }
        val project = coroutineContext.project
        val breakpoints = invokeAndWaitIfNeeded {
            XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                .map { bp ->
                    BreakpointInfo(
                        file = bp.presentableFilePath,
                        line = bp.line + 1,
                        enabled = bp.isEnabled,
                        condition = bp.conditionExpression?.expression?.takeIf { it.isNotBlank() }
                    )
                }
        }
        if (breakpoints.isEmpty()) return "No line breakpoints found"
        return Json.encodeToString(breakpoints)
    }

    // ── mute_breakpoints ──────────────────────────────────────────────────────

    @McpTool(name = "mute_breakpoints")
    @McpDescription(description = """
        Enables or disables all line breakpoints in the project.
        Pass muted=true to disable all breakpoints (they become inactive but are not deleted).
        Pass muted=false to re-enable all breakpoints.
        Does not require an active debug session.
    """)
    suspend fun mute_breakpoints(muted: Boolean): String {
        disabledMessage("mute_breakpoints")?.let { return it }
        val project = coroutineContext.project
        val count = invokeAndWaitIfNeeded {
            val breakpoints = XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
            breakpoints.forEach { it.isEnabled = !muted }
            breakpoints.size
        }
        return if (muted) "$count breakpoints disabled" else "$count breakpoints enabled"
    }

    // ── set_breakpoint_condition ───────────────────────────────────────────────

    @McpTool(name = "set_breakpoint_condition")
    @McpDescription(description = """
        Sets or removes a condition on a breakpoint at a given file and line.
        filePath: path relative to the project root (e.g. "src/main/java/Foo.java").
        line: 1-based line number where the breakpoint is set.
        condition: the condition expression (e.g. "i == 5"). Pass empty string to remove the condition.
        The breakpoint must already exist at that line.
    """)
    suspend fun set_breakpoint_condition(filePath: String, line: Int, condition: String): String {
        disabledMessage("set_breakpoint_condition")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val bp = manager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                .firstOrNull { it.line == line - 1 && it.presentableFilePath.endsWith(filePath.replace('\\', '/')) }
                ?: return@invokeAndWaitIfNeeded "No breakpoint found at $filePath:$line"

            if (condition.isEmpty()) {
                bp.conditionExpression = null
                "Condition removed from breakpoint at $filePath:$line"
            } else {
                val expr = com.intellij.xdebugger.XDebuggerUtil.getInstance()
                    .createExpression(condition, null, null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION)
                bp.conditionExpression = expr
                "Condition set on breakpoint at $filePath:$line: $condition"
            }
        }
    }

    // ── debug_run_configuration ───────────────────────────────────────────────

    @McpTool(name = "debug_run_configuration")
    @McpDescription(description = """
        Launches a run configuration in debug mode and returns immediately.
        Does NOT wait for completion — use get_debug_variables to check if stopped at a breakpoint,
        or get_console_output to read console output.
        configurationName: exact name of the run configuration (use get_run_configurations to list them).
    """)
    suspend fun debug_run_configuration(configurationName: String): String {
        disabledMessage("debug_run_configuration")?.let { return it }
        val project = coroutineContext.project

        val settings = invokeAndWaitIfNeeded {
            com.intellij.execution.RunManager.getInstance(project).findConfigurationByName(configurationName)
        } ?: return "Configuration '$configurationName' not found"

        withContext(Dispatchers.EDT) {
            ProgramRunnerUtil.executeConfiguration(project, settings, DefaultDebugExecutor.getDebugExecutorInstance())
        }

        return "Debug session started for '$configurationName'. Use get_debug_variables to inspect variables if stopped at a breakpoint."
    }

    private fun resolveDebugVariable(name: String, xValue: XValue): DebugVariable {
        var type: String? = null
        var value: String? = null
        val latch = CountDownLatch(1)
        xValue.computePresentation(object : XValueNode {
            override fun setPresentation(icon: Icon?, type_: String?, value_: String, hasChildren: Boolean) {
                type = type_; value = value_; latch.countDown()
            }
            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                type = presentation.type
                val sb = StringBuilder()
                presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                    override fun renderValue(v: String) { sb.append(v) }
                    override fun renderValue(v: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) { sb.append(v) }
                    override fun renderStringValue(v: String) { sb.append("\"$v\"") }
                    override fun renderStringValue(v: String, extra: String?, maxLen: Int) { sb.append("\"$v\"") }
                    override fun renderNumericValue(v: String) { sb.append(v) }
                    override fun renderKeywordValue(v: String) { sb.append(v) }
                    override fun renderComment(v: String) {}
                    override fun renderSpecialSymbol(v: String) { sb.append(v) }
                    override fun renderError(v: String) { sb.append(v) }
                })
                value = sb.toString(); latch.countDown()
            }
            override fun setFullValueEvaluator(e: XFullValueEvaluator) {}
            override fun isObsolete() = false
        }, XValuePlace.TREE)
        latch.await(2, TimeUnit.SECONDS)
        return DebugVariable(name = name, type = type, value = value)
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
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                vFile.delete(this)
            }
            "Deleted: $filePath"
        }
    }

    // ── get_project_structure ─────────────────────────────────────────────────

    @McpTool(name = "get_project_structure")
    @McpDescription(description = """
        Returns the structure of the IntelliJ project: active SDK (with homePath), all SDKs registered
        in IntelliJ (with homePath, useful to suggest switching), modules, source roots, excluded folders,
        and module-to-module dependencies.
        Useful to understand the project layout (where sources, tests, and resources live) before
        navigating or editing files.
        Source root types: source, test, resource, testResource.
        All paths are relative to the project root.
    """)
    suspend fun get_project_structure(): String {
        disabledMessage("get_project_structure")?.let { return it }
        val project = coroutineContext.project
        return runReadAction {
            val basePath = project.basePath ?: ""
            val sdk = ProjectRootManager.getInstance(project).projectSdk?.let { sdk ->
                SdkInfo(name = sdk.name, type = sdk.sdkType.name, version = sdk.versionString, homePath = sdk.homePath)
            }
            val availableSdks = ProjectJdkTable.getInstance().allJdks.map { s ->
                SdkInfo(name = s.name, type = s.sdkType.name, version = s.versionString, homePath = s.homePath)
            }
            val modules = ModuleManager.getInstance(project).modules.map { module ->
                val rootManager = ModuleRootManager.getInstance(module)
                val sourceRoots = rootManager.contentEntries.flatMap { entry ->
                    entry.sourceFolders.map { sf ->
                        val path = sf.file?.path?.let { relativize(basePath, it) } ?: sf.url
                        val rootTypeStr = sf.rootType.toString().lowercase()
                        val type = when {
                            "resource" in rootTypeStr && sf.isTestSource -> "testResource"
                            "resource" in rootTypeStr -> "resource"
                            sf.isTestSource -> "test"
                            else -> "source"
                        }
                        SourceRootInfo(path = path, type = type)
                    }
                }
                val excluded = rootManager.contentEntries.flatMap { entry ->
                    entry.excludeFolders.mapNotNull { ef ->
                        ef.file?.path?.let { relativize(basePath, it) } ?: ef.url
                    }
                }
                val deps = rootManager.dependencies.map { it.name }
                val moduleType = try { ModuleType.get(module).id } catch (_: Exception) { null }
                ModuleInfo(
                    name = module.name,
                    type = moduleType,
                    sourceRoots = sourceRoots,
                    excludedFolders = excluded.ifEmpty { null },
                    dependencies = deps.ifEmpty { null }
                )
            }
            Json.encodeToString(ProjectStructure(
                name = project.name,
                basePath = basePath,
                sdk = sdk,
                availableSdks = availableSdks,
                modules = modules
            ))
        }
    }

    // ── get_running_processes ─────────────────────────────────────────────────

    @McpTool(name = "get_running_processes")
    @McpDescription(description = """
        Returns all background processes currently running in IntelliJ — the same tasks visible
        in the status bar (indexing, Gradle sync, compilation, inspections, etc.).
        For each process:
        - title: main label of the task
        - details: secondary line (current file, step, etc.) if available
        - progress: 0.0–1.0, or null if indeterminate
        - cancellable: whether the task can be cancelled
        Call this when IntelliJ seems busy, slow, or stuck to understand what it is doing.
    """)
    suspend fun get_running_processes(): String {
        disabledMessage("get_running_processes")?.let { return it }
        val processes = collectRunningProcesses()
        return if (processes.isEmpty()) "No background processes running"
        else Json.encodeToString(processes)
    }

    private fun collectRunningProcesses(): List<RunningProcess> {
        val seen = mutableSetOf<String>()
        val processes = mutableListOf<RunningProcess>()

        // Active processes (running threads)
        for (ind in currentIndicators()) {
            if (!ind.isRunning) continue
            val title = ind.taskTitle() ?: ind.text?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(title)) continue
            val details = ind.text2?.takeIf { it.isNotBlank() }
            val progress = if (ind.isIndeterminate) null else ind.fraction.takeIf { it in 0.0..1.0 }
            val cancellable = ind.reflectBoolean("isCancellable")
            processes += RunningProcess(title = title, details = details, progress = progress, cancellable = cancellable, paused = false)
        }

        // Paused processes (suspended via ProgressSuspender)
        for ((ind, suspender) in allSuspenderEntries()) {
            if (!suspender.isSuspended()) continue
            val title = ind.taskTitle() ?: ind.text?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add("paused:$title")) continue
            val progress = if (ind.isIndeterminate) null else ind.fraction.takeIf { it in 0.0..1.0 }
            processes += RunningProcess(title = title, progress = progress, paused = true)
        }

        return processes
    }

    // ── manage_process ────────────────────────────────────────────────────────

    @McpTool(name = "manage_process")
    @McpDescription(description = """
        Controls a running background process by its title (as returned by get_running_processes).
        title: partial, case-insensitive match (e.g. "index" matches "Indexing project…").
        action: one of:
          - "cancel" — stop the process (only if cancellable)
          - "pause"  — suspend the process temporarily
          - "resume" — resume a paused process
        Returns a confirmation message or an error if the process is not found / action not supported.
    """)
    suspend fun manage_process(title: String, action: String): String {
        disabledMessage("manage_process")?.let { return it }
        // Search active indicators first
        var match = currentIndicators().firstOrNull { ind ->
            if (!ind.isRunning) return@firstOrNull false
            val label = ind.taskTitle() ?: ind.text ?: ""
            label.contains(title, ignoreCase = true)
        }
        // Also search paused indicators via ProgressSuspender map
        var matchedSuspender: Any? = match?.let { getSuspender(it) }
        if (match == null) {
            val entry = allSuspenderEntries().firstOrNull { (ind, _) ->
                val label = ind.taskTitle() ?: ind.text ?: ""
                label.contains(title, ignoreCase = true)
            }
            match = entry?.first
            matchedSuspender = entry?.second
        }
        if (match == null) return "No running process found matching \"$title\""

        val label = match.taskTitle() ?: match.text?.takeIf { it.isNotBlank() } ?: title
        return when (action.lowercase().trim()) {
            "cancel" -> {
                if (match.reflectBoolean("isCancellable") == false) "Process \"$label\" is not cancellable"
                else {
                    // If paused, resume first so the thread can actually check cancellation
                    val suspender = matchedSuspender ?: getSuspender(match)
                    if (suspender?.isSuspended() == true) {
                        suspender.reflectInvoke("resumeProcess", "", "")
                        Thread.sleep(100)
                    }
                    match.cancel()
                    "Cancelled: \"$label\""
                }
            }
            "pause" -> {
                val suspender = matchedSuspender ?: getSuspender(match)
                    ?: return "Process \"$label\" does not support pause"
                suspender.reflectInvoke("suspendProcess", "Paused: \"$label\"", "Process \"$label\" does not support pause",
                    arrayOf(String::class.java), arrayOf("Paused by MCP"))
            }
            "resume" -> {
                val suspender = matchedSuspender ?: getSuspender(match)
                    ?: return "Process \"$label\" does not support resume"
                suspender.reflectInvoke("resumeProcess", "Resumed: \"$label\"", "Process \"$label\" does not support resume")
            }
            else -> "Unknown action \"$action\". Use: cancel, pause, resume"
        }
    }

    private fun ProgressIndicator.taskTitle(): String? = try {
        val taskInfo = javaClass.getMethod("getTaskInfo").invoke(this)
        taskInfo?.javaClass?.getMethod("getTitle")?.invoke(taskInfo) as? String
    } catch (_: Exception) { null }?.takeIf { it.isNotBlank() }

    private fun ProgressIndicator.reflectBoolean(method: String): Boolean? = try {
        javaClass.getMethod(method).invoke(this) as? Boolean
    } catch (_: Exception) { null }

    private fun ProgressIndicator.reflectInvoke(method: String, success: String, failure: String): String = try {
        javaClass.getMethod(method).invoke(this); success
    } catch (_: Exception) { failure }

    private fun Any.reflectInvoke(method: String, success: String, failure: String,
                                   paramTypes: Array<Class<*>> = emptyArray(),
                                   args: Array<Any?> = emptyArray()): String = try {
        javaClass.getMethod(method, *paramTypes).invoke(this, *args); success
    } catch (_: Exception) { failure }

    /** Calls CoreProgressManager.getCurrentIndicators() via reflection to avoid @ApiStatus.Internal violation. */
    @Suppress("UNCHECKED_CAST")
    private fun currentIndicators(): List<ProgressIndicator> = try {
        Class.forName("com.intellij.openapi.progress.impl.CoreProgressManager")
            .getMethod("getCurrentIndicators")
            .invoke(null) as? List<ProgressIndicator> ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun getSuspender(indicator: ProgressIndicator): Any? = try {
        val cls = Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        cls.getMethod("getSuspender", ProgressIndicator::class.java).invoke(null, indicator)
    } catch (_: Exception) { null }

    /** Returns all (indicator, suspender) pairs from ProgressSuspender.ourProgressToSuspenderMap */
    @Suppress("UNCHECKED_CAST")
    private fun allSuspenderEntries(): List<Pair<ProgressIndicator, Any>> = try {
        val cls = Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        val field = cls.getDeclaredField("ourProgressToSuspenderMap").also { it.isAccessible = true }
        val map = field.get(null) as? Map<ProgressIndicator, Any> ?: return emptyList()
        map.entries.map { it.key to it.value }
    } catch (_: Exception) { emptyList() }

    private fun Any.isSuspended(): Boolean =
        runCatching { javaClass.getMethod("isSuspended").invoke(this) as? Boolean ?: false }.getOrDefault(false)

    private fun Any.suspendedText(): String? =
        runCatching { javaClass.getMethod("getSuspendedText").invoke(this) as? String }.getOrNull()

    private fun relativize(basePath: String, path: String): String =
        if (basePath.isNotEmpty() && path.startsWith(basePath))
            path.removePrefix(basePath).trimStart('/')
        else path

    // ── get_intellij_diagnostic ───────────────────────────────────────────────

    @McpTool(name = "get_intellij_diagnostic")
    @McpDescription(description = """
        Returns IntelliJ diagnostic information in one call. Use this as a first step when
        something is not working as expected in IntelliJ.
        Returns:
        - indexing: whether the IDE is currently indexing files (DumbService)
        - notifications: active notifications visible in the Event Log (errors, warnings)
        - processes: background processes currently running or paused (same as get_running_processes)
        - logEntries: log entries from idea.log in the last N minutes, including stack traces
        minutesBack: how many minutes back to scan idea.log (default: 5)
        level: minimum log level to include — "error" (ERROR+SEVERE only), "warn" (adds WARN), "info" (adds INFO). Default: "error"
    """)
    suspend fun get_intellij_diagnostic(minutesBack: Int = 5, level: String = "error"): String {
        disabledMessage("get_intellij_diagnostic")?.let { return it }
        val project = coroutineContext.project

        val indexing = invokeAndWaitIfNeeded {
            IndexingStatus(active = DumbService.getInstance(project).isDumb)
        }

        val notifications = invokeAndWaitIfNeeded {
            NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(Notification::class.java, project)
                .map { n ->
                    DiagnosticNotification(
                        type = n.type.name,
                        title = n.title,
                        content = n.content.takeIf { it.isNotBlank() },
                        groupId = n.groupId.takeIf { it.isNotBlank() }
                    )
                }.toList()
        }

        val processes = collectRunningProcesses()

        val logEntries = readIdeaLogErrors(minutesBack = minutesBack, level = level)

        return Json.encodeToString(IntellijDiagnostic(
            indexing = indexing,
            notifications = notifications,
            processes = processes,
            logEntries = logEntries
        ))
    }

    private fun readIdeaLogErrors(minutesBack: Int, level: String = "error"): List<String> {
        val logFile = java.io.File(PathManager.getLogPath(), "idea.log")
        if (!logFile.exists()) return emptyList()
        return try {
            val cutoff = System.currentTimeMillis() - minutesBack * 60_000L
            val raf = java.io.RandomAccessFile(logFile, "r")
            val fileLen = raf.length()
            val startPos = maxOf(0L, fileLen - 524288L) // read last 512 KB
            raf.seek(startPos)
            val bytes = ByteArray((fileLen - startPos).toInt())
            raf.read(bytes)
            raf.close()

            val lines = String(bytes, Charsets.UTF_8).lines()
            val headerRegex = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}),\d+""")
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

            // Group lines into entries (header + following stack trace lines)
            data class Entry(val ts: Long, val isError: Boolean, val lines: MutableList<String>)
            val entries = mutableListOf<Entry>()

            for (line in lines) {
                val match = headerRegex.find(line)
                if (match != null) {
                    val ts = runCatching { sdf.parse(match.groupValues[1])?.time ?: 0L }.getOrDefault(0L)
                    val isError = when (level.lowercase()) {
                        "info" -> "ERROR" in line || "SEVERE" in line || "WARN" in line || " INFO" in line
                        "warn" -> "ERROR" in line || "SEVERE" in line || "WARN" in line
                        else   -> "ERROR" in line || "SEVERE" in line
                    }
                    entries += Entry(ts, isError, mutableListOf(line))
                } else {
                    entries.lastOrNull()?.lines?.add(line)
                }
            }

            entries
                .filter { it.isError && it.ts >= cutoff }
                .flatMap { it.lines }
                .take(300) // cap at 300 lines to avoid huge responses
        } catch (_: Exception) { emptyList() }
    }

    // ── navigate_to ───────────────────────────────────────────────────────────

    @McpTool(name = "navigate_to")
    @McpDescription(description = """
        Opens a file in the editor and places the cursor at the given line and column.
        filePath: path relative to the project root.
        line: 1-based line number.
        column: 1-based column number (default: 1).
    """)
    suspend fun navigate_to(filePath: String, line: Int, column: Int = 1): String {
        disabledMessage("navigate_to")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
            val basePath = project.basePath ?: return@invokeAndWaitIfNeeded "Project base path not found"
            val vFile = LocalFileSystem.getInstance().findFileByPath("$basePath/${filePath.replace('\\', '/')}")
                ?: return@invokeAndWaitIfNeeded "File not found: $filePath"
            com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile, line - 1, (column - 1).coerceAtLeast(0))
                .navigate(true)
            "Navigated to $filePath:$line:$column"
        }
    }

    // ── select_text ───────────────────────────────────────────────────────────

    @McpTool(name = "select_text")
    @McpDescription(description = """
        Opens a file and selects a range of text so the user can copy it directly (Cmd+C).
        filePath: path relative to the project root.
        startLine/startColumn: 1-based position of the first character to select.
        endLine/endColumn: 1-based position of the LAST character to select (inclusive).
        Example: to select "Hello" on line 3 starting at column 5, use startColumn=5, endColumn=9.
    """)
    suspend fun select_text(filePath: String, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): String {
        disabledMessage("select_text")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
            val basePath = project.basePath ?: return@invokeAndWaitIfNeeded "Project base path not found"
            val vFile = LocalFileSystem.getInstance().findFileByPath("$basePath/${filePath.replace('\\', '/')}")
                ?: return@invokeAndWaitIfNeeded "File not found: $filePath"
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile, startLine - 1, (startColumn - 1).coerceAtLeast(0)), true)
                ?: return@invokeAndWaitIfNeeded "Could not open editor"
            val doc = editor.document
            val startOffset = (doc.getLineStartOffset(startLine - 1) + (startColumn - 1)).coerceAtMost(doc.textLength)
            val endOffset = (doc.getLineStartOffset(endLine - 1) + endColumn).coerceAtMost(doc.textLength)
            editor.selectionModel.setSelection(startOffset, endOffset)
            "Selected $filePath:$startLine:$startColumn → $endLine:$endColumn"
        }
    }

    // ── highlight_text ────────────────────────────────────────────────────────

    @McpTool(name = "highlight_text")
    @McpDescription(description = """
        Highlights one or more exact text zones in a file using the IDE's standard search-result color (theme-aware).
        Useful to show where a variable is declared and all its usages at once.
        filePath: path relative to the project root.
        ranges: comma-separated list of "startLine:startCol:endLine:endCol" (1-based).
                startCol is the column of the first character; endCol is the column of the LAST character (inclusive).
                Example: to highlight "Random" at line 17 columns 34-49, use "17:34:17:49".
                Use get_file_text_by_path to read the file and identify exact column positions.
        Call clear_highlights to remove them.
    """)
    suspend fun highlight_text(filePath: String, ranges: String): String {
        disabledMessage("highlight_text")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
            val basePath = project.basePath ?: return@invokeAndWaitIfNeeded "Project base path not found"
            val normalizedPath = filePath.replace('\\', '/')
            val vFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$normalizedPath")
                ?: return@invokeAndWaitIfNeeded "File not found: $filePath"
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile, 0), true)
                ?: return@invokeAndWaitIfNeeded "Could not open editor"
            val doc = editor.document
            val attrs = EditorColorsManager.getInstance().globalScheme
                .getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES)
            var count = 0
            for (range in ranges.split(",")) {
                val parts = range.trim().split(":")
                if (parts.size != 4) continue
                val startLine = (parts[0].trim().toIntOrNull() ?: continue) - 1
                val startCol  = (parts[1].trim().toIntOrNull() ?: continue) - 1
                val endLine   = (parts[2].trim().toIntOrNull() ?: continue) - 1
                val endCol    = (parts[3].trim().toIntOrNull() ?: continue) - 1
                if (startLine < 0 || endLine >= doc.lineCount) continue
                val startOffset = (doc.getLineStartOffset(startLine) + startCol    ).coerceAtMost(doc.textLength)
                val endOffset   = (doc.getLineStartOffset(endLine)   + endCol + 1  ).coerceAtMost(doc.textLength)
                val h = editor.markupModel.addRangeHighlighter(
                    startOffset, endOffset,
                    com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1,
                    attrs,
                    com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
                )
                h.putUserData(MCP_HIGHLIGHT_KEY, true)
                count++
            }
            "$count zone(s) highlighted in $filePath"
        }
    }

    // ── clear_highlights ──────────────────────────────────────────────────────

    @McpTool(name = "clear_highlights")
    @McpDescription(description = """
        Removes all highlights previously added by highlight_text from all open editors.
        filePath: path relative to the project root. Leave empty to clear all open files.
    """)
    suspend fun clear_highlights(filePath: String = ""): String {
        disabledMessage("clear_highlights")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
            var count = 0
            val editors = if (filePath.isEmpty()) {
                FileEditorManager.getInstance(project).allEditors
                    .filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>()
                    .map { it.editor }
            } else {
                val basePath = project.basePath ?: return@invokeAndWaitIfNeeded "Project base path not found"
                val vFile = LocalFileSystem.getInstance().findFileByPath("$basePath/${filePath.replace('\\', '/')}")
                    ?: return@invokeAndWaitIfNeeded "File not found: $filePath"
                FileEditorManager.getInstance(project).getEditors(vFile)
                    .filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>()
                    .map { it.editor }
            }
            for (editor in editors) {
                val toRemove = editor.markupModel.allHighlighters.filter {
                    it.getUserData(MCP_HIGHLIGHT_KEY) == true
                }
                toRemove.forEach { editor.markupModel.removeHighlighter(it); count++ }
            }
            "$count highlight(s) cleared"
        }
    }
}

// ── Constants ─────────────────────────────────────────────────────────────────

val MCP_HIGHLIGHT_KEY = Key<Boolean>("mcp.companion.highlight")

// ── Data classes ──────────────────────────────────────────────────────────────

@Serializable data class EditorState(val openFiles: List<String>, val focusedEditor: FocusedEditorInfo?)
@Serializable data class FocusedEditorInfo(val path: String, val currentLine: Int, val selection: SelectionInfo?)
@Serializable data class SelectionInfo(val text: String, val startLine: Int, val endLine: Int)

@Serializable data class BuildOutput(val tabs: List<BuildTab>)
@Serializable data class BuildTab(val name: String, val tree: List<BuildNode>?, val console: String?)
@Serializable data class BuildNode(val text: String, val severity: String? = null, val detail: String? = null, val file: String? = null, val line: Int? = null, val children: List<BuildNode>? = null)

@Serializable data class ServicesOutput(val sessions: List<ServiceSession>)
@Serializable data class ServiceSession(val name: String, val selected: Boolean = false, val output: String? = null, val outputSelected: Boolean = false, val results: List<ServiceResult> = emptyList())
@Serializable data class ServiceResult(val name: String, val selected: Boolean = false, val data: String? = null)

@Serializable data class ConsoleOutput(val activeWindow: String? = null, val run: List<ConsoleTab> = emptyList(), val debug: List<ConsoleTab> = emptyList())
@Serializable data class ConsoleTab(val name: String, val active: Boolean = false, val tree: List<BuildNode>? = null, val console: String? = null)

@Serializable data class DebugVariablesOutput(val session: String, val variables: List<DebugVariable>)
@Serializable data class DebugVariable(val name: String, val type: String? = null, val value: String? = null)

@Serializable data class BreakpointInfo(val file: String, val line: Int, val enabled: Boolean, val condition: String? = null)

@Serializable data class TestRunOutput(val runs: List<TestRun>, val error: String? = null)
@Serializable data class TestRun(val name: String, val tests: List<TestNode>)
@Serializable data class TestNode(val name: String, val status: String, val duration: Long? = null, val errorMessage: String? = null, val children: List<TestNode>? = null)

@Serializable data class RunningProcess(val title: String, val details: String? = null, val progress: Double? = null, val cancellable: Boolean? = null, val paused: Boolean = false)

@Serializable data class ProjectStructure(val name: String, val basePath: String, val sdk: SdkInfo?, val availableSdks: List<SdkInfo>, val modules: List<ModuleInfo>)
@Serializable data class SdkInfo(val name: String, val type: String, val version: String?, val homePath: String? = null)
@Serializable data class ModuleInfo(val name: String, val type: String? = null, val sourceRoots: List<SourceRootInfo>, val excludedFolders: List<String>? = null, val dependencies: List<String>? = null)
@Serializable data class SourceRootInfo(val path: String, val type: String)

@Serializable data class IntellijDiagnostic(val indexing: IndexingStatus, val notifications: List<DiagnosticNotification>, val processes: List<RunningProcess>, val logEntries: List<String>)
@Serializable data class IndexingStatus(val active: Boolean)
@Serializable data class DiagnosticNotification(val type: String, val title: String, val content: String? = null, val groupId: String? = null)
