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

    private fun disabledMessage(toolName: String): String? {
        if (!McpCompanionSettings.getInstance().isEnabled(toolName))
            return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion."
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
- navigate_to            → move the caret to a file:line:column (do this before explaining anything)
- select_text            → select an exact range (startCol/endCol are 1-based, endCol is INCLUSIVE)
- highlight_text         → highlight multiple zones at once (declaration + all usages)
                           ranges format: "startLine:startCol:endLine:endCol" comma-separated, endCol INCLUSIVE
- clear_highlights       → remove all highlights (user can also press Escape)

### Build & run
- get_build_output       → read compiler errors before answering a build question
- get_run_output         → read console output after a run
- get_test_results       → read test results (pass/fail/duration/message)

### Debug
- debug_run_configuration    → launch a named run config in debug mode
- add_conditional_breakpoint → add or update a breakpoint with an optional condition
- get_breakpoints            → list all breakpoints with conditions
- set_breakpoint_condition   → change the condition of an existing breakpoint
- mute_breakpoints           → enable/disable all breakpoints at once
- get_debug_variables        → read local variables from the current stack frame
- get_debug_output           → read the debug console

### Editing
- replace_text_undoable  → replace text in a file (Cmd+Z undoable)

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

    // ── get_run_output ────────────────────────────────────────────────────────

    @McpTool(name = "get_run_output")
    @McpDescription(description = """
        Returns the content of the "Run" tool window in IntelliJ.
        Includes all open run tabs with:
        - tree: structured list of run nodes (tasks, errors, warnings) with file and line number when available
        - console: the raw text output
        Useful to check the output of a running or recently run program, including Gradle task errors.
    """)
    suspend fun get_run_output(): String {
        disabledMessage("get_run_output")?.let { return it }
        val project = coroutineContext.project
        val tabs = invokeAndWaitIfNeeded { extractRunTabs(project) }
        return Json.encodeToString(RunOutput(tabs))
    }

    private fun extractRunTabs(project: com.intellij.openapi.project.Project): List<RunTab> {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Run")
            ?: return listOf(RunTab(name = "error", tree = null, console = "Run tool window not found"))
        return toolWindow.contentManager.contents.map { content ->
            val trees = UIUtil.findComponentsOfType(content.component, JTree::class.java)
            val firstTree = trees.firstOrNull()
            val treeNodes = firstTree?.model?.let { buildBuildNodes(it, it.root) }
            val consoles = UIUtil.findComponentsOfType(content.component, ConsoleViewImpl::class.java)
            val consoleText = consoles.mapNotNull { it.readText()?.ifEmpty { null } }
                .joinToString("\n---\n").ifEmpty { null }
            RunTab(
                name = content.displayName ?: "Run",
                tree = treeNodes?.ifEmpty { null },
                console = consoleText
            )
        }
    }

    // ── get_debug_output ──────────────────────────────────────────────────────

    @McpTool(name = "get_debug_output")
    @McpDescription(description = """
        Returns the console output from the "Debug" tool window in IntelliJ.
        Includes all open debug tabs with their name and console text.
        Works whether the debug session is active or already finished.
        Useful to check the output of a program running under the debugger.
    """)
    suspend fun get_debug_output(): String {
        disabledMessage("get_debug_output")?.let { return it }
        val project = coroutineContext.project
        val tabs = invokeAndWaitIfNeeded { extractDebugTabs(project) }
        return Json.encodeToString(DebugOutput(tabs))
    }

    private fun extractDebugTabs(project: com.intellij.openapi.project.Project): List<DebugTab> {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Debug")
            ?: return listOf(DebugTab(name = "error", console = "Debug tool window not found"))
        val contents = toolWindow.contentManager.contents
        if (contents.isEmpty()) return listOf(DebugTab(name = "error", console = "No debug tabs found"))

        // Active sessions: get console text directly from session.consoleView (bypasses tab focus)
        val activeSessions = XDebuggerManager.getInstance(project).debugSessions
        val sessionByName = activeSessions.associateBy { it.sessionName }

        return contents.map { content ->
            val name = content.displayName ?: "Debug"
            val session = sessionByName[name]

            // Try session.consoleView first (always initialized, regardless of tab focus)
            val consoleFromSession = session?.consoleView?.let { cv ->
                (cv as? ConsoleViewImpl)?.readText()
                    ?: UIUtil.findComponentsOfType(cv.component, ConsoleViewImpl::class.java)
                        .mapNotNull { it.readText()?.ifEmpty { null } }
                        .joinToString("\n---\n").ifEmpty { null }
            }

            // Fall back to scanning the content component (works for finished sessions)
            val consoleText = consoleFromSession
                ?: UIUtil.findComponentsOfType(content.component, ConsoleViewImpl::class.java)
                    .mapNotNull { it.readText()?.ifEmpty { null } }
                    .joinToString("\n---\n").ifEmpty { null }

            DebugTab(name = name, console = consoleText)
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
        or get_debug_output to read console output.
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

@Serializable data class RunOutput(val tabs: List<RunTab>)
@Serializable data class RunTab(val name: String, val tree: List<BuildNode>? = null, val console: String?)

@Serializable data class DebugOutput(val tabs: List<DebugTab>)
@Serializable data class DebugTab(val name: String, val console: String?)

@Serializable data class DebugVariablesOutput(val session: String, val variables: List<DebugVariable>)
@Serializable data class DebugVariable(val name: String, val type: String? = null, val value: String? = null)

@Serializable data class BreakpointInfo(val file: String, val line: Int, val enabled: Boolean, val condition: String? = null)

@Serializable data class TestRunOutput(val runs: List<TestRun>, val error: String? = null)
@Serializable data class TestRun(val name: String, val tests: List<TestNode>)
@Serializable data class TestNode(val name: String, val status: String, val duration: Long? = null, val errorMessage: String? = null, val children: List<TestNode>? = null)
