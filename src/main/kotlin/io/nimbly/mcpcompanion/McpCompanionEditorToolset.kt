package io.nimbly.mcpcompanion

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Key
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

class McpCompanionEditorToolset : McpToolset {

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

    internal fun buildEditorState(project: com.intellij.openapi.project.Project): EditorState {
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
        return runOnEdt {
            val (vFile, err) = resolveFilePathOrError(project, filePath)
            if (err != null) return@runOnEdt err
            com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile!!, line - 1, (column - 1).coerceAtLeast(0))
                .navigate(true)
            "Navigated to $filePath:$line:$column"
        }
    }

    // ── select_text ───────────────────────────────────────────────────────────

    @McpTool(name = "select_text")
    @McpDescription(description = """
        Opens a file (if not already open), brings it to the foreground, scrolls to the target
        range and selects it so the user can copy it directly (Cmd+C).
        filePath: path relative to the project root.
        startLine/startColumn: 1-based position of the first character to select.
        endLine/endColumn: 1-based position of the LAST character to select (inclusive).
        Example: to select "Hello" on line 3 starting at column 5, use startColumn=5, endColumn=9.
    """)
    suspend fun select_text(filePath: String, startLine: Int, startColumn: Int, endLine: Int, endColumn: Int): String {
        disabledMessage("select_text")?.let { return it }
        val project = coroutineContext.project
        return runOnEdt {
            val (vFile, err) = resolveFilePathOrError(project, filePath)
            if (err != null) return@runOnEdt err
            // Open the file, bring it to the foreground and place the caret at the start of the range
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile!!, startLine - 1, (startColumn - 1).coerceAtLeast(0)), true)
                ?: return@runOnEdt "Could not open editor"
            val doc = editor.document
            val startOffset = (doc.getLineStartOffset(startLine - 1) + (startColumn - 1)).coerceAtMost(doc.textLength)
            val endOffset   = (doc.getLineStartOffset(endLine   - 1) + endColumn        ).coerceAtMost(doc.textLength)
            editor.selectionModel.setSelection(startOffset, endOffset)
            // Move the caret to the start of the selection and scroll it into view
            editor.caretModel.moveToOffset(startOffset)
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            "Selected $filePath:$startLine:$startColumn → $endLine:$endColumn"
        }
    }

    // ── highlight_text ────────────────────────────────────────────────────────

    @McpTool(name = "highlight_text")
    @McpDescription(description = """
        Opens a file (if not already open), brings it to the foreground, highlights one or more
        exact text zones using the IDE's standard search-result color (theme-aware), and scrolls
        to the first highlighted range.
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
        return runOnEdt {
            val (vFile, err) = resolveFilePathOrError(project, filePath)
            if (err != null) return@runOnEdt err
            // Parse ranges first so we can navigate to the first one when opening the file
            data class ParsedRange(val startLine: Int, val startCol: Int, val endLine: Int, val endCol: Int)
            val parsed = ranges.split(",").mapNotNull { range ->
                val parts = range.trim().split(":")
                if (parts.size != 4) return@mapNotNull null
                ParsedRange(
                    startLine = (parts[0].trim().toIntOrNull() ?: return@mapNotNull null) - 1,
                    startCol  = (parts[1].trim().toIntOrNull() ?: return@mapNotNull null) - 1,
                    endLine   = (parts[2].trim().toIntOrNull() ?: return@mapNotNull null) - 1,
                    endCol    = (parts[3].trim().toIntOrNull() ?: return@mapNotNull null) - 1,
                )
            }
            val firstRange = parsed.firstOrNull()
            // Open the file, bring it to the foreground and place the caret at the first range
            val navLine = firstRange?.startLine ?: 0
            val navCol  = firstRange?.startCol  ?: 0
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile!!, navLine, navCol), true)
                ?: return@runOnEdt "Could not open editor"
            val doc = editor.document
            val attrs = EditorColorsManager.getInstance().globalScheme
                .getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES)
            var count = 0
            var firstStartOffset: Int? = null
            for (r in parsed) {
                if (r.startLine < 0 || r.endLine >= doc.lineCount) continue
                val startOffset = (doc.getLineStartOffset(r.startLine) + r.startCol    ).coerceAtMost(doc.textLength)
                val endOffset   = (doc.getLineStartOffset(r.endLine)   + r.endCol + 1  ).coerceAtMost(doc.textLength)
                val h = editor.markupModel.addRangeHighlighter(
                    startOffset, endOffset,
                    com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1,
                    attrs,
                    com.intellij.openapi.editor.markup.HighlighterTargetArea.EXACT_RANGE
                )
                h.putUserData(MCP_HIGHLIGHT_KEY, true)
                if (firstStartOffset == null) firstStartOffset = startOffset
                count++
            }
            // Scroll to the first highlighted range
            firstStartOffset?.let { offset ->
                editor.caretModel.moveToOffset(offset)
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
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
        return runOnEdt {
            var count = 0
            val editors = if (filePath.isEmpty()) {
                FileEditorManager.getInstance(project).allEditors
                    .filterIsInstance<com.intellij.openapi.fileEditor.TextEditor>()
                    .map { it.editor }
            } else {
                val (vFile, err) = resolveFilePathOrError(project, filePath)
                if (err != null) return@runOnEdt err
                FileEditorManager.getInstance(project).getEditors(vFile!!)
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
