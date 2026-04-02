package io.nimbly.mcpcompanion.tools

import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool

class GetOpenEditorsTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {

    override val name: String = "get_open_editors"

    override val description: String = """
        Returns information about all files currently open in the IntelliJ editor.

        For each open file, the absolute path is returned.
        For the file that last had focus (active editor), also returns:
        - currentLine: 1-based line number of the caret
        - selection: if a selection exists, its text and start/end line numbers (1-based)
    """.trimIndent()

    override fun handle(project: Project, args: NoArgs): Response {
        val state = runReadAction { buildEditorState(project) }
        return Response(Json.encodeToString(state))
    }

    private fun buildEditorState(project: Project): EditorState {
        val fem = FileEditorManager.getInstance(project)

        val openFiles = fem.openFiles.map { it.path }

        val focusedEditor = fem.selectedTextEditor?.let { editor ->
            val filePath = fem.selectedFiles.firstOrNull()?.path ?: return@let null
            val document = editor.document
            val line = editor.caretModel.logicalPosition.line + 1 // 1-based

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

            FocusedEditorInfo(
                path = filePath,
                currentLine = line,
                selection = selection,
            )
        }

        return EditorState(
            openFiles = openFiles,
            focusedEditor = focusedEditor,
        )
    }
}

@Serializable
data class EditorState(
    val openFiles: List<String>,
    val focusedEditor: FocusedEditorInfo?,
)

@Serializable
data class FocusedEditorInfo(
    val path: String,
    val currentLine: Int,
    val selection: SelectionInfo?,
)

@Serializable
data class SelectionInfo(
    val text: String,
    val startLine: Int,
    val endLine: Int,
)
