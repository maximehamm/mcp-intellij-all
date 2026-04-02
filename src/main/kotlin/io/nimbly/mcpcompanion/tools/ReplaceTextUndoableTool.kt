package io.nimbly.mcpcompanion.tools

import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class ReplaceTextUndoableTool : AbstractMcpTool<ReplaceTextArgs>(ReplaceTextArgs.serializer()) {

    override val name: String = "replace_text_undoable"

    override val description: String = """
        Replaces a specific text in a file while preserving IntelliJ's undo stack.
        After calling this tool, Cmd+Z (Undo "MCP Replace") works normally in the editor.
        Use this instead of replace_specific_text when you want the change to be undoable.

        Parameters:
        - pathInProject: relative path from the project root (e.g. "src/Main.java")
        - oldText: exact text to find and replace (first occurrence)
        - newText: replacement text
    """.trimIndent()

    override fun handle(project: Project, args: ReplaceTextArgs): Response {
        val projectPath = project.basePath
            ?: return Response("error: no project base path")

        val virtualFile = LocalFileSystem.getInstance()
            .findFileByPath("$projectPath/${args.pathInProject}")
            ?: return Response("error: file not found: ${args.pathInProject}")

        val document = invokeAndWaitIfNeeded {
            FileDocumentManager.getInstance().getDocument(virtualFile)
        } ?: return Response("error: cannot open document for: ${args.pathInProject}")

        val offset = document.text.indexOf(args.oldText)
        if (offset == -1) return Response("error: text not found in file")

        invokeAndWaitIfNeeded {
            WriteCommandAction.runWriteCommandAction(project, "MCP Replace", null, {
                document.replaceString(offset, offset + args.oldText.length, args.newText)
            })
        }

        return Response("ok")
    }
}

@Serializable
data class ReplaceTextArgs(
    val pathInProject: String,
    val oldText: String,
    val newText: String,
)
