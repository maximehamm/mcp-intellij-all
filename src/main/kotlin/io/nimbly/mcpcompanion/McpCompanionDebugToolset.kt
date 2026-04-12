package io.nimbly.mcpcompanion

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import kotlin.coroutines.coroutineContext

class McpCompanionDebugToolset : McpToolset {

    override fun isEnabled(): Boolean = true

    private fun disabledMessage(toolName: String): String? {
        if (!McpCompanionSettings.getInstance().isEnabled(toolName)) {
            val extra = if (toolName in McpCompanionSettings.DISABLED_BY_DEFAULT)
                " This tool is disabled by default for safety reasons. Ask the user to enable it first."
            else ""
            return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion.$extra"
        }
        McpCompanionSettings.getInstance().trackCall(toolName)
        return null
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
        val frame = runOnEdt { session.currentStackFrame }
            ?: return "No stack frame — program may still be running"

        val children = mutableListOf<Pair<String, XValue>>()
        val childrenLatch = CountDownLatch(1)
        runOnEdt {
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

    // ── add_conditional_breakpoint ────────────────────────────────────────────

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
        return runOnEdt {
            val (file, err) = resolveFilePathOrError(project, filePath)
            if (err != null) return@runOnEdt err
            val normalizedPath = filePath.replace('\\', '/')
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val existing = manager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                .firstOrNull { it.line == line - 1 && it.presentableFilePath.endsWith(normalizedPath) }
            if (existing == null) {
                com.intellij.xdebugger.XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file!!, line - 1, false)
            }
            val bp = existing ?: run {
                var found: com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>? = null
                repeat(10) {
                    found = manager.allBreakpoints
                        .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                        .firstOrNull { it.line == line - 1 && it.presentableFilePath.endsWith(normalizedPath) }
                    if (found == null) Thread.sleep(100)
                }
                found ?: return@runOnEdt "Failed to create breakpoint at $filePath:$line"
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
        val breakpoints = runOnEdt {
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
        val count = runOnEdt {
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
        return runOnEdt {
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val bp = manager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                .firstOrNull { it.line == line - 1 && it.presentableFilePath.endsWith(filePath.replace('\\', '/')) }
                ?: return@runOnEdt "No breakpoint found at $filePath:$line"

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

        val settings = runOnEdt {
            com.intellij.execution.RunManager.getInstance(project).findConfigurationByName(configurationName)
        } ?: return "Configuration '$configurationName' not found"

        withContext(Dispatchers.EDT) {
            ProgramRunnerUtil.executeConfiguration(project, settings, DefaultDebugExecutor.getDebugExecutorInstance())
        }

        return "Debug session started for '$configurationName'. Use get_debug_variables to inspect variables if stopped at a breakpoint."
    }

    internal fun resolveDebugVariable(name: String, xValue: XValue): DebugVariable {
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
}

// ── Data classes ──────────────────────────────────────────────────────────────

@Serializable data class DebugVariablesOutput(val session: String, val variables: List<DebugVariable>)
@Serializable data class DebugVariable(val name: String, val type: String? = null, val value: String? = null)

@Serializable data class BreakpointInfo(val file: String, val line: Int, val enabled: Boolean, val condition: String? = null)
