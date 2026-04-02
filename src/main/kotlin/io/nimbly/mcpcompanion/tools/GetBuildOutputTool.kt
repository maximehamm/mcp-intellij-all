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
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeModel

class GetBuildOutputTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {

    override val name: String = "get_build_output"

    override val description: String = """
        Returns the content of the "Build" tool window in IntelliJ.
        Includes all open tabs with:
        - tree: structured list of build nodes (tasks, errors, warnings) with file and line number when available
        - console: the raw text output
        Useful to read compilation errors, warnings, and build results.
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val tabs = invokeAndWaitIfNeeded { extractBuildTabs(project) }
        return Response(Json.encodeToString(BuildOutput(tabs)))
    }

    private fun extractBuildTabs(project: Project): List<BuildTab> {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Build")
            ?: return listOf(BuildTab(name = "error", tree = null, console = "Build tool window not found"))

        return toolWindow.contentManager.contents.map { content ->
            val trees = UIUtil.findComponentsOfType(content.component, JTree::class.java)
            val treeNodes = trees.firstOrNull()?.model?.let { buildNodes(it, it.root) }

            val consoles = UIUtil.findComponentsOfType(content.component, ConsoleViewImpl::class.java)
            val consoleText = consoles.firstNotNullOfOrNull { it.editor?.document?.text }?.trim()

            BuildTab(
                name = content.displayName ?: "Build",
                tree = treeNodes?.ifEmpty { null },
                console = consoleText?.ifEmpty { null },
            )
        }
    }

    private fun buildNodes(model: TreeModel, node: Any): List<BuildNode> {
        val result = mutableListOf<BuildNode>()
        val childCount = model.getChildCount(node)
        for (i in 0 until childCount) {
            val child = model.getChild(node, i)
            result.add(toNode(model, child))
        }
        return result
    }

    private fun toNode(model: TreeModel, node: Any): BuildNode {
        val userObject = (node as? DefaultMutableTreeNode)?.userObject ?: node

        var text = userObject.toString().trim()
        var file: String? = null
        var line: Int? = null

        val cls = userObject.javaClass

        // Text: getTitle()/getHint() pour ExecutionNode
        val title = try { cls.methods.find { it.name == "getTitle" }?.invoke(userObject) as? String } catch (_: Exception) { null }
        val hint  = try { cls.methods.find { it.name == "getHint"  }?.invoke(userObject) as? String } catch (_: Exception) { null }
        if (!title.isNullOrEmpty()) text = if (!hint.isNullOrEmpty()) "$title — $hint" else title

        // Unwrap NodeDescriptor si besoin (ErrorTreeView)
        val element: Any = try { cls.methods.find { it.name == "getElement" }?.invoke(userObject) ?: userObject } catch (_: Exception) { userObject }
        val elCls = element.javaClass
        // Navigatable : getNavigatables() plural puis getNavigatable() singular
        val rawNavs = try { elCls.methods.find { it.name == "getNavigatables" }?.invoke(element) } catch (_: Exception) { null }
        val firstNav: Any? = when (rawNavs) {
            is Array<*> -> rawNavs.firstOrNull()
            is List<*>  -> rawNavs.firstOrNull()
            else -> try { elCls.methods.find { it.name == "getNavigatable" }?.invoke(element) } catch (_: Exception) { null }
        }
        if (firstNav != null) {
            val navCls = firstNav.javaClass
            // FileNavigatable → getFileDescriptor() → OpenFileDescriptor avec getFile()/getLine()
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

        // Fallback: getVirtualFile() sur l'element (GroupingElement)
        if (file == null) {
            val vFile = try { elCls.methods.find { it.name == "getVirtualFile" }?.invoke(element) } catch (_: Exception) { null }
            if (vFile != null) {
                file = try { vFile.javaClass.methods.find { it.name == "getName" }?.invoke(vFile) as? String } catch (_: Exception) { null }
            }
        }

        val children = buildNodes(model, node).ifEmpty { null }
        return BuildNode(text = text, file = file, line = line, children = children)
    }
}

@Serializable
data class BuildOutput(val tabs: List<BuildTab>)

@Serializable
data class BuildTab(
    val name: String,
    val tree: List<BuildNode>?,
    val console: String?,
)

@Serializable
data class BuildNode(
    val text: String,
    val file: String? = null,
    val line: Int? = null,
    val children: List<BuildNode>? = null,
)
