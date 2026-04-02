package io.nimbly.mcpcompanion.tools

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.UIUtil
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class GetRunOutputTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {

    override val name: String = "get_run_output"

    override val description: String = """
        Returns the console output from the "Run" tool window in IntelliJ.
        Includes all open run tabs with their name and console text.
        Useful to check the output of a running or recently run program.
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val tabs = invokeAndWaitIfNeeded { extractRunTabs(project) }
        return Response(Json.encodeToString(RunOutput(tabs)))
    }

    private fun extractRunTabs(project: Project): List<RunTab> {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Run")
            ?: return listOf(RunTab(name = "error", console = "Run tool window not found"))

        return toolWindow.contentManager.contents.map { content ->
            val consoles = UIUtil.findComponentsOfType(content.component, ConsoleViewImpl::class.java)
            val consoleText = consoles.firstNotNullOfOrNull { it.readText() }
            RunTab(
                name = content.displayName ?: "Run",
                console = consoleText?.ifEmpty { null },
            )
        }
    }
}

@Serializable
data class RunOutput(val tabs: List<RunTab>)

@Serializable
data class RunTab(
    val name: String,
    val console: String?,
)
