package io.nimbly.mcpcompanion.tools

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class GetDebugOutputTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {

    override val name: String = "get_debug_output"

    override val description: String = """
        Returns the console output from the "Debug" tool window in IntelliJ.
        Includes all open debug sessions with their name and console text.
        Useful to check the output of a program running under the debugger.
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val tabs = invokeAndWaitIfNeeded { extractDebugTabs(project) }
        return Response(Json.encodeToString(DebugOutput(tabs)))
    }

    private fun extractDebugTabs(project: Project): List<DebugTab> {
        val sessions = XDebuggerManager.getInstance(project).debugSessions
        if (sessions.isEmpty()) {
            return listOf(DebugTab(name = "error", console = "No active debug session"))
        }
        return sessions.map { session ->
            val console = session.consoleView
            val text = (console as? ConsoleViewImpl)?.readText()
            DebugTab(
                name = session.sessionName,
                console = text,
            )
        }
    }
}

@Serializable
data class DebugOutput(val tabs: List<DebugTab>)

@Serializable
data class DebugTab(
    val name: String,
    val console: String?,
)
