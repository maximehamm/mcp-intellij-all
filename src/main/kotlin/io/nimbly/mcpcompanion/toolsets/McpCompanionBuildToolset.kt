package io.nimbly.mcpcompanion.toolsets

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.SMTestRunnerResultsForm
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.clientInfo
import com.intellij.mcpserver.project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.tabs.JBTabs
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebuggerManager
import io.nimbly.mcpcompanion.util.readEditorText
import io.nimbly.mcpcompanion.util.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeModel
import kotlin.coroutines.coroutineContext
import io.nimbly.mcpcompanion.util.resolveProject
import io.nimbly.mcpcompanion.util.runOnEdt
import io.nimbly.mcpcompanion.McpCompanionSettings

class McpCompanionBuildToolset : McpToolset {

    override fun isEnabled(): Boolean = true

    private suspend fun disabledMessage(toolName: String): String? {
        if (!McpCompanionSettings.getInstance().isEnabled(toolName)) {
            val extra = if (toolName in McpCompanionSettings.DISABLED_BY_DEFAULT)
                " This tool is disabled by default for safety reasons. Ask the user to enable it first."
            else ""
            return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion.$extra"
        }
        McpCompanionSettings.getInstance().trackCall(toolName, runCatching { coroutineContext.clientInfo?.name?.takeIf { it != "Unknown MCP client" } }.getOrNull())
        return null
    }

    // ── get_build_output ──────────────────────────────────────────────────────

    @McpTool(name = "get_build_output")
    @McpDescription(description = """
        Returns the content of the "Build" tool window in IntelliJ.
        Includes all open tabs with:
        - tree: structured list of build nodes (tasks, errors, warnings) with file and line number when available
        - console: the raw text output
        Useful to read compilation errors, warnings, and build results.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_build_output(projectPath: String? = null): String {
        disabledMessage("get_build_output")?.let { return it }
        val project = resolveProject(projectPath)
        val tabs = runOnEdt { extractBuildTabs(project) }
        return captureResponse(Json.encodeToString(BuildOutput(tabs)))
    }

    internal fun extractBuildTabs(project: com.intellij.openapi.project.Project): List<BuildTab> {
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

    internal fun buildBuildNodes(model: TreeModel, node: Any): List<BuildNode> {
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
        var detail = listOf("getDescription", "getTooltipText", "getMessage", "getDetails", "getHint")
            .firstNotNullOfOrNull { name ->
                try { cls.methods.find { it.name == name }?.invoke(userObject) as? String }
                catch (_: Exception) { null }
            }?.trim()?.takeIf { it.isNotEmpty() && it != text }

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

        val severity = when {
            (try { cls.methods.find { it.name == "getHasProblems" }?.invoke(userObject) as? Boolean } catch (_: Exception) { null }) == true -> "ERROR"
            (try { cls.methods.find { it.name == "getVisibleAsWarning" }?.invoke(userObject) as? Boolean } catch (_: Exception) { null }) == true -> "WARNING"
            (try { cls.methods.find { it.name == "getVisibleAsSuccessful" }?.invoke(userObject) as? Boolean } catch (_: Exception) { null }) == true -> "SUCCESS"
            else -> null
        }

        val children = buildBuildNodes(model, node).ifEmpty { null }
        return BuildNode(text = text, severity = severity, detail = detail, file = file, line = line, children = children)
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

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_services_output(projectPath: String? = null): String {
        disabledMessage("get_services_output")?.let { return it }
        val project = resolveProject(projectPath)
        return captureResponse(runOnEdt {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Services")
                ?: return@runOnEdt Json.encodeToString(
                    ServicesOutput(sessions = listOf(ServiceSession(name = "error", output = "Services tool window not found"))))

            val contents = runCatching { toolWindow.contentManager.contents.toList() }.getOrElse { emptyList() }
            val sessionContents = contents.filter { it.displayName != "All Services" && !it.displayName.isNullOrBlank() }
            val isTabbedMode = sessionContents.isNotEmpty() && contents.any { it.displayName == "All Services" }

            val rootComponent = if (isTabbedMode)
                (contents.firstOrNull { it.displayName == "All Services" }?.component as? javax.swing.JComponent
                    ?: toolWindow.component)
            else
                toolWindow.component

            val tree = UIUtil.findComponentsOfType(rootComponent, javax.swing.JTree::class.java)
                .firstOrNull { it.javaClass.name.contains("ServiceViewTree") }

            fun nodeDisplayName(node: Any, sourceTree: javax.swing.JTree? = null): String {
                sourceTree?.let { t ->
                    runCatching {
                        val comp = t.cellRenderer.getTreeCellRendererComponent(t, node, false, false, true, 0, false)
                        comp.accessibleContext?.accessibleName?.takeIf { it.isNotBlank() }
                            ?: run {
                                runCatching {
                                    comp.javaClass.getMethod("getCharSequence", Boolean::class.javaPrimitiveType)
                                        .invoke(comp, false)?.toString()?.takeIf { it.isNotBlank() }
                                }.getOrNull()
                            }
                            ?: run {
                                fun labelText(c: java.awt.Component): String? {
                                    if (c is javax.swing.JLabel) return c.text?.takeIf { it.isNotBlank() }
                                    if (c is java.awt.Container) c.components.forEach { labelText(it)?.let { txt -> return txt } }
                                    return null
                                }
                                labelText(comp)
                            }
                    }.getOrNull()?.let { return it }
                }
                runCatching {
                    val desc = node.javaClass.getMethod("getViewDescriptor").invoke(node)
                    val pres = desc?.javaClass?.getMethod("getPresentation")?.invoke(desc)
                    pres?.javaClass?.getMethod("getPresentableText")?.invoke(pres)?.toString()
                }.getOrNull()?.let { return it }
                runCatching {
                    val pres = node.javaClass.getMethod("getPresentation").invoke(node)
                    pres?.javaClass?.getMethod("getPresentableText")?.invoke(pres)?.toString()
                }.getOrNull()?.let { return it }
                for (methodName in listOf("getName", "getText", "getDisplayName")) {
                    runCatching {
                        node.javaClass.getMethod(methodName).invoke(node)?.toString()
                    }.getOrNull()?.let { return it }
                }
                return node.toString()
            }

            fun cleanNodeName(raw: String): String {
                val normalized = raw.map { if (it.isWhitespace() || it.code > 127) ' ' else it }.joinToString("")
                val tokens = normalized.trim().split(Regex(" +")).filter { it.isNotEmpty() }.toMutableList()
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

            @Suppress("UNCHECKED_CAST")
            val wrappers = UIUtil.findComponentsOfType(rootComponent, javax.swing.JComponent::class.java)
                .filter { it.javaClass.name.contains("ServiceViewComponentWrapper") }

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

            val selectedLeafName: String? = tree?.let { t ->
                t.selectionPath?.lastPathComponent?.let { node -> cleanNodeName(nodeDisplayName(node, t)) }
            }

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

                        val table = UIUtil.findComponentsOfType(component, javax.swing.JTable::class.java)
                            .firstOrNull { it.javaClass.name.contains("TableResult", ignoreCase = true) }
                        if (table != null) {
                            results += ServiceResult(name = tabTitle, selected = isTabSelected, data = extractGridText(table))
                        }
                    }
                } else {
                    val console = UIUtil.findComponentOfType(wrapper, ConsoleViewImpl::class.java)
                    if (console != null) output = console.readText()?.ifBlank { null }
                    else output = readEditorText(wrapper)
                }
                return ServiceSession(name = name, selected = name == selectedLeafName,
                    output = output, outputSelected = outputSelected, results = results)
            }

            val outerTabsImpl = findJBTabs(rootComponent)
                .firstOrNull { tabs -> tabs.tabs.any { it.text == "All Services" } }

            val selectedContentName = runCatching { toolWindow.contentManager.selectedContent?.displayName }.getOrNull()

            val sessions: List<ServiceSession> = when {
                isTabbedMode -> {
                    sessionContents.mapNotNull { content ->
                        val name = cleanNodeName(content.displayName ?: return@mapNotNull null)
                        val component = content.component as? javax.swing.JComponent ?: return@mapNotNull null
                        val isSelected = content.displayName == selectedContentName
                        processWrapper(name, component).copy(selected = isSelected)
                    }
                }
                outerTabsImpl != null -> {
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
        })
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

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_console_output(projectPath: String? = null): String {
        disabledMessage("get_console_output")?.let { return it }
        val project = resolveProject(projectPath)
        return captureResponse(runOnEdt { Json.encodeToString(extractConsoleOutput(project)) })
    }

    internal fun extractConsoleOutput(project: com.intellij.openapi.project.Project): ConsoleOutput {
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

        return ConsoleOutput(activeWindow = activeWindow, run = runTabs, debug = debugTabs)
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

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_test_results(projectPath: String? = null): String {
        disabledMessage("get_test_results")?.let { return it }
        val project = resolveProject(projectPath)
        val output = runOnEdt { extractTestResults(project) }
        return captureResponse(Json.encodeToString(output))
    }

    internal fun extractTestResults(project: com.intellij.openapi.project.Project): TestRunOutput {
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

    internal fun buildTestNode(proxy: SMTestProxy): TestNode {
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

    // ── get_terminal_output ───────────────────────────────────────────────────

    @McpTool(name = "get_terminal_output")
    @McpDescription(description = """
        Returns the visible output of all tabs in the embedded Terminal tool window.
        For each tab:
        - name: tab label (e.g. "Local", "bash")
        - active: true on the currently selected tab
        - output: visible terminal content (screen buffer + recent history)
        Use this to read command output, script results, or any text typed in the terminal.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_terminal_output(projectPath: String? = null): String {
        disabledMessage("get_terminal_output")?.let { return it }
        val project = resolveProject(projectPath)
        return captureResponse(runOnEdt { extractTerminalTabs(project) })
    }

    internal fun extractTerminalTabs(project: com.intellij.openapi.project.Project): String {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            ?: return "Terminal tool window not found"
        val selectedContent = toolWindow.contentManager.selectedContent
        val tabs = toolWindow.contentManager.contents.mapNotNull { content ->
            val name = content.displayName ?: "Terminal"
            val component = content.component as? javax.swing.JComponent ?: return@mapNotNull null
            val isActive = content === selectedContent
            val text = readEditorText(component) ?: readJediTermText(component)
            TerminalTab(name = name, active = isActive, output = text)
        }
        return if (tabs.isEmpty()) "No terminal tabs found"
        else Json.encodeToString(TerminalOutput(tabs))
    }

    // ── send_to_terminal ──────────────────────────────────────────────────────

    @McpTool(name = "send_to_terminal")
    @McpDescription(description = """
        Sends a command to the embedded Terminal tool window and executes it (presses Enter).
        Parameters:
        - command: the shell command to run (e.g. "ls -la", "gradle test")
        - tab: optional tab name to target; defaults to the currently active tab
        Use get_terminal_output afterwards to read the result.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun send_to_terminal(command: String, tab: String? = null, projectPath: String? = null): String {
        disabledMessage("send_to_terminal")?.let { return it }
        val project = resolveProject(projectPath)
        return captureResponse(runOnEdt { sendToTerminalImpl(project, command, tab) })
    }

    internal fun sendToTerminalImpl(project: com.intellij.openapi.project.Project, command: String, tab: String?): String {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Terminal")
            ?: return "Terminal tool window not found"
        val content = if (tab != null)
            toolWindow.contentManager.contents.firstOrNull { it.displayName == tab }
                ?: return "Terminal tab '$tab' not found"
        else
            toolWindow.contentManager.selectedContent
                ?: toolWindow.contentManager.contents.firstOrNull()
                ?: return "No terminal tab available"
        // Strategy 1: get TerminalWidget via TerminalView.WIDGET_KEY stored on Content
        val widget = getTerminalWidget(content)
        if (widget != null) {
            val result = invokeTerminalSendCommand(widget, command)
            if (result != null) return result
        }
        // Strategy 2..N: scan component tree
        val component = content.component as? javax.swing.JComponent
            ?: return "Terminal component not accessible"
        return sendTerminalCommand(component, project, command)
    }

    /** Retrieve the terminal widget stored by the Terminal plugin on a Content via TerminalView.WIDGET_KEY. */
    private fun getTerminalWidget(content: com.intellij.ui.content.Content): Any? = runCatching {
        val termViewCls = Class.forName("org.jetbrains.plugins.terminal.TerminalView")
        val keyField = (termViewCls.declaredFields + termViewCls.fields)
            .find { it.name == "WIDGET_KEY" } ?: return@runCatching null
        keyField.isAccessible = true
        val key = keyField.get(null) as? com.intellij.openapi.util.Key<*> ?: return@runCatching null
        @Suppress("UNCHECKED_CAST")
        content.getUserData(key as com.intellij.openapi.util.Key<Any>)
    }.getOrNull()

    /** Call executeCommand or sendCommandToExecute on any object that exposes it. */
    private fun invokeTerminalSendCommand(widget: Any, command: String): String? {
        val method = generateSequence(widget.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() + it.methods.asSequence() }
            .find { (it.name == "executeCommand" || it.name == "sendCommandToExecute")
                && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
            ?: return null
        return runCatching {
            method.isAccessible = true
            method.invoke(widget, command)
            "Command sent to terminal"
        }.getOrNull()
    }

    private fun sendTerminalCommand(component: javax.swing.JComponent, project: com.intellij.openapi.project.Project, command: String): String {
        // Strategy 2: TerminalViewImpl.createSendTextBuilder() — new frontend terminal (2024.2+)
        runCatching {
            val termPanelCls = Class.forName("com.intellij.terminal.frontend.view.impl.TerminalViewImpl\$TerminalPanel")
            val termPanel = UIUtil.findComponentsOfType(component, termPanelCls as Class<javax.swing.JComponent>).firstOrNull()
            if (termPanel != null) {
                val outerField = termPanel.javaClass.declaredFields.find { it.name.startsWith("this$") }
                outerField?.isAccessible = true
                val termViewImpl = outerField?.get(termPanel)
                if (termViewImpl != null) {
                    val result = sendViaTerminalViewImpl(termViewImpl, command)
                    if (result != null) return result
                }
            }
        }
        // Strategy 3: scan component tree for executeCommand/sendCommandToExecute on any AWT component
        val hit = findComponentWithTerminalMethod(component)
        if (hit != null) {
            val (target, method) = hit
            runCatching {
                method.isAccessible = true
                method.invoke(target, command)
                return "Command sent to terminal"
            }.onFailure { e ->
                return "Found method ${method.name} but invocation failed: ${e.message}"
            }
        }
        // Strategy 3: new block terminal — find the writable input EditorEx, set text, dispatch Enter
        runCatching {
            val compCls = Class.forName("com.intellij.openapi.editor.impl.EditorComponentImpl")
            val editorComps = UIUtil.findComponentsOfType(component, compCls as Class<javax.swing.JComponent>)
            for (editorComp in editorComps) {
                val editorField = generateSequence(editorComp.javaClass as Class<*>?) { it.superclass }
                    .flatMap { it.declaredFields.asSequence() }
                    .find { it.name == "myEditor" || it.name == "editor" } ?: continue
                editorField.isAccessible = true
                val editor = editorField.get(editorComp) as? com.intellij.openapi.editor.Editor ?: continue
                if (editor.isViewer || !editor.document.isWritable) continue
                com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction {
                    editor.document.setText(command)
                }
                val contentComp = editor.contentComponent
                contentComp.requestFocusInWindow()
                val enterEvent = java.awt.event.KeyEvent(
                    contentComp, java.awt.event.KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), 0,
                    java.awt.event.KeyEvent.VK_ENTER, java.awt.event.KeyEvent.CHAR_UNDEFINED
                )
                java.awt.Toolkit.getDefaultToolkit().systemEventQueue.postEvent(enterEvent)
                return "Command sent to terminal"
            }
        }
        // Strategy 4: TtyConnector.write — scan tree for any component with getTtyConnector()
        val tty = findTtyConnector(component)
        if (tty != null) {
            runCatching {
                val write = generateSequence(tty.javaClass as Class<*>?) { it.superclass }
                    .flatMap { it.methods.asSequence() }
                    .find { it.name == "write" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
                if (write != null) {
                    write.invoke(tty, command + "\n")
                    return "Command sent to terminal"
                }
            }
        }
        return "Could not find a way to send command to this terminal type"
    }

    private fun findTtyConnector(root: java.awt.Component): Any? {
        fun scan(comp: java.awt.Component): Any? {
            val method = generateSequence(comp.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.methods.asSequence() }
                .find { it.name == "getTtyConnector" && it.parameterCount == 0 }
            if (method != null) return runCatching { method.invoke(comp) }.getOrNull()
            if (comp is java.awt.Container) { for (child in comp.components) { scan(child)?.let { return it } } }
            return null
        }
        return scan(root)
    }

    /**
     * Send command via TerminalViewImpl.doSendText() — new frontend terminal (IntelliJ 2024.2+).
     * Constructs TerminalSendTextOptions directly (no builder) to avoid setText() side-effects.
     * Returns success message or null if the API is not available.
     */
    private fun sendViaTerminalViewImpl(termViewImpl: Any, command: String): String? {
        // Find doSendText(TerminalSendTextOptions) on TerminalViewImpl
        val doSendText = generateSequence(termViewImpl.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .find { it.name == "doSendText" && it.parameterCount == 1 }
            ?: return null
        doSendText.isAccessible = true
        val optsCls = doSendText.parameterTypes[0]
        // Construct TerminalSendTextOptions: first String param = command+"\n", Booleans = true, Ints = 0
        val ctor = optsCls.constructors
            .sortedBy { it.parameterCount }
            .find { it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java }
            ?: return null
        return runCatching {
            ctor.isAccessible = true
            val args = Array(ctor.parameterCount) { i ->
                val type = ctor.parameterTypes[i]
                when {
                    i == 0 -> command
                    type == Boolean::class.javaPrimitiveType || type == Boolean::class.javaObjectType -> true
                    type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> 0
                    else -> null
                }
            }
            val opts = ctor.newInstance(*args)
            doSendText.invoke(termViewImpl, opts)
            "Command sent to terminal"
        }.getOrNull()
    }

    /** Depth-first scan of the component tree for any component with executeCommand or sendCommandToExecute(String). */
    private fun findComponentWithTerminalMethod(root: java.awt.Component): Pair<Any, java.lang.reflect.Method>? {
        val targetNames = setOf("executeCommand", "sendCommandToExecute")
        fun scan(comp: java.awt.Component): Pair<Any, java.lang.reflect.Method>? {
            val method = generateSequence(comp.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .find { it.name in targetNames && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
            if (method != null) return comp to method
            if (comp is java.awt.Container) {
                for (child in comp.components) {
                    scan(child)?.let { return it }
                }
            }
            return null
        }
        return scan(root)
    }

    private fun readJediTermText(component: javax.swing.JComponent): String? = try {
        val widgetCls = Class.forName("org.jetbrains.plugins.terminal.ShellTerminalWidget")
        val widget = UIUtil.findComponentsOfType(component, widgetCls as Class<javax.swing.JComponent>)
            .firstOrNull() ?: return null
        // ShellTerminalWidget → getTerminalPanel() → getTerminalTextBuffer()
        val panel = generateSequence(widget.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.methods.asSequence() }
            .find { it.name == "getTerminalPanel" }
            ?.invoke(widget) ?: return null
        val buffer = generateSequence(panel.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.methods.asSequence() }
            .find { it.name == "getTerminalTextBuffer" }
            ?.invoke(panel) ?: return null
        val height = (generateSequence(buffer.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.methods.asSequence() }
            .find { it.name == "getHeight" }
            ?.invoke(buffer) as? Int) ?: 24
        val getLine = generateSequence(buffer.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.methods.asSequence() }
            .find { it.name == "getLine" && it.parameterCount == 1 }
        val lines = (0 until height).mapNotNull { row ->
            val line = runCatching { getLine?.invoke(buffer, row) }.getOrNull() ?: return@mapNotNull null
            (generateSequence(line.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.methods.asSequence() }
                .find { it.name == "getText" }
                ?.invoke(line) as? String)?.trimEnd()
        }
        lines.joinToString("\n").trim().ifEmpty { null }
    } catch (_: Exception) { null }
}

// ── Data classes ──────────────────────────────────────────────────────────────

@Serializable data class BuildOutput(val tabs: List<BuildTab>)
@Serializable data class BuildTab(val name: String, val tree: List<BuildNode>?, val console: String?)
@Serializable data class BuildNode(val text: String, val severity: String? = null, val detail: String? = null, val file: String? = null, val line: Int? = null, val children: List<BuildNode>? = null)

@Serializable data class ServicesOutput(val sessions: List<ServiceSession>)
@Serializable data class ServiceSession(val name: String, val selected: Boolean = false, val output: String? = null, val outputSelected: Boolean = false, val results: List<ServiceResult> = emptyList())
@Serializable data class ServiceResult(val name: String, val selected: Boolean = false, val data: String? = null)

@Serializable data class ConsoleOutput(val activeWindow: String? = null, val run: List<ConsoleTab> = emptyList(), val debug: List<ConsoleTab> = emptyList())
@Serializable data class ConsoleTab(val name: String, val active: Boolean = false, val tree: List<BuildNode>? = null, val console: String? = null)

@Serializable data class TestRunOutput(val runs: List<TestRun>, val error: String? = null)
@Serializable data class TestRun(val name: String, val tests: List<TestNode>)
@Serializable data class TestNode(val name: String, val status: String, val duration: Long? = null, val errorMessage: String? = null, val children: List<TestNode>? = null)

@Serializable data class TerminalOutput(val tabs: List<TerminalTab>)
@Serializable data class TerminalTab(val name: String, val active: Boolean = false, val output: String? = null)
