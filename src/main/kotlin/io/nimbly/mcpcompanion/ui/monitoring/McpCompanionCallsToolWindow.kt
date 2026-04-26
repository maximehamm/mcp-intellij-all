package io.nimbly.mcpcompanion.ui.monitoring

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.ListCellRenderer
import io.nimbly.mcpcompanion.McpCompanionSettings

/**
 * Tool window listing the most recent MCP tool calls.
 *
 * - Each row shows: status icon (●/✓/✗), HH:MM:SS, tool name, parameters preview, duration.
 * - Calls from THIS plugin's tools render in normal color; calls from other plugins
 *   (built-in JetBrains toolsets, third-party MCP servers) render in gray.
 * - The bottom split panel previews the selected call's full pretty-printed JSON parameters.
 * - Double-click (or Enter) opens a modal dialog with all metadata + parameters + response.
 *
 * Backed by [McpCompanionSettings.getCallRecords] which is populated by
 * [McpCompanionToolCallListener] on every tool call.
 */
class McpCompanionCallsToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = McpCompanionCallsPanel(project)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        content.setDisposer { panel.dispose() }
        toolWindow.contentManager.addContent(content)
    }

    companion object {
        const val TOOL_WINDOW_ID = "MCP Companion Monitoring"
    }
}

internal class McpCompanionCallsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val model = DefaultListModel<McpCompanionSettings.CallRecord>()

    // Read-only JSON viewers — proper IntelliJ Editor with syntax highlighting + code folding.
    private val parametersDocument: Document = EditorFactory.getInstance().createDocument("")
    private val responseDocument: Document = EditorFactory.getInstance().createDocument("")
    private val errorsDocument: Document = EditorFactory.getInstance().createDocument("")
    private val parametersEditor: Editor = createJsonViewer(parametersDocument, project)
    private val responseEditor: Editor = createJsonViewer(responseDocument, project)
    // Errors are usually long single-line stack traces — enable soft wrap so the user can read them.
    private val errorsEditor: Editor = createJsonViewer(errorsDocument, project, softWrap = true)

    /** Top tab in the lower section: just "Parameters". A single-tab JBTabbedPane gives a header
     *  that visually mirrors the lower (Response/Errors) tabs for consistency. */
    private val parametersTabbedPane = com.intellij.ui.components.JBTabbedPane().apply {
        addTab("Parameters", parametersEditor.component)
    }

    /** Bottom tabs in the lower section: Response + Errors. */
    private val responseErrorsTabbedPane = com.intellij.ui.components.JBTabbedPane().apply {
        addTab("Response", responseEditor.component)
        addTab("Errors", errorsEditor.component)
    }

    private val list = JBList(model).apply {
        cellRenderer = CallRecordRenderer()
        background = UIUtil.getListBackground()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openDetailsDialog()
            }
        })
        addListSelectionListener { updateDetailsPanel() }
        // Cmd/Ctrl + C → copy the selected tool name to the system clipboard.
        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                val isCopy = e.keyCode == java.awt.event.KeyEvent.VK_C &&
                    (e.isMetaDown || e.isControlDown)
                if (!isCopy) return
                val name = selectedValue?.toolName ?: return
                java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(java.awt.datatransfer.StringSelection(name), null)
                e.consume()
            }
        })
    }

    private val refreshListener: () -> Unit = {
        ApplicationManager.getApplication().invokeLater(::refreshFromSettings)
    }

    /** Stored as a field so [updateDetailsPanel] can hide the whole lower section when nothing is selected. */
    private val detailsSplitter = JBSplitter(true, "io.nimbly.mcpcompanion.calls.detailsSplitter", 0.5f).apply {
        firstComponent = parametersTabbedPane
        secondComponent = responseErrorsTabbedPane
    }

    init {
        background = UIUtil.getListBackground()
        // Outer split: list on top, details below.
        val splitter = JBSplitter(true, "io.nimbly.mcpcompanion.calls.splitter", 0.55f).apply {
            firstComponent = JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                viewportBorder = JBUI.Borders.empty()
                viewport.background = UIUtil.getListBackground()
                background = UIUtil.getListBackground()
            }
            secondComponent = detailsSplitter
            background = UIUtil.getListBackground()
        }
        add(splitter, BorderLayout.CENTER)
        preferredSize = Dimension(600, 500)
        refreshFromSettings()
        McpCompanionSettings.getInstance().addCallRecordListener(refreshListener)
    }

    fun dispose() {
        McpCompanionSettings.getInstance().removeCallRecordListener(refreshListener)
        EditorFactory.getInstance().releaseEditor(parametersEditor)
        EditorFactory.getInstance().releaseEditor(responseEditor)
        EditorFactory.getInstance().releaseEditor(errorsEditor)
    }

    private fun refreshFromSettings() {
        val rememberedCallId = list.selectedValue?.callId
        val records = McpCompanionSettings.getInstance().getCallRecords()
        model.clear()
        records.forEach { model.addElement(it) }
        // Try to keep the same call selected after a refresh.
        if (rememberedCallId != null) {
            val newIndex = records.indexOfFirst { it.callId == rememberedCallId }
            if (newIndex >= 0) list.selectedIndex = newIndex
        } else if (records.isNotEmpty()) {
            // First record arriving while nothing was selected — auto-select it so the user
            // sees the details panel populate immediately (instead of staying blank).
            list.selectedIndex = 0
        }
        updateDetailsPanel()
    }

    private fun updateDetailsPanel() {
        val r = list.selectedValue
        // Hide the entire lower section (Parameters + Response/Errors) when nothing is selected
        // (e.g. on first open before any call has been recorded).
        val showDetails = r != null
        if (detailsSplitter.isVisible != showDetails) {
            detailsSplitter.isVisible = showDetails
            detailsSplitter.parent?.revalidate()
            detailsSplitter.parent?.repaint()
        }
        if (!showDetails) return

        // Lazy-load the heavy payload from disk only when the user selects a row.
        val payload = r?.callId?.let { io.nimbly.mcpcompanion.util.CallPayloadStorage.load(it) }
        val params = payload?.parameters ?: ""
        val response = payload?.response ?: ""
        val errors = r?.errorMessage ?: ""
        setDocumentText(parametersDocument, params)
        setDocumentText(responseDocument, response)
        setDocumentText(errorsDocument, errors)
        applyHighlighter(responseEditor as EditorEx, fileTypeFor(response))
        applyHighlighter(parametersEditor as EditorEx, fileTypeFor(params))

        // Hide the Response/Errors panel for tools from other plugins — we don't capture
        // their responses (no captureResponse() hook) and the framework's error path differs,
        // so showing two empty tabs is misleading.
        val showResponseErrors = r != null && r.isOwnTool
        if (responseErrorsTabbedPane.isVisible != showResponseErrors) {
            responseErrorsTabbedPane.isVisible = showResponseErrors
            responseErrorsTabbedPane.parent?.revalidate()
            responseErrorsTabbedPane.parent?.repaint()
        }
        // Auto-switch to Errors tab when an error record is selected, else show Response.
        if (showResponseErrors) {
            responseErrorsTabbedPane.selectedIndex = if (errors.isNotEmpty()) 1 else 0
        }
    }

    /** Returns "JSON" if [text] looks like a JSON object/array and parses, else PlainText. */
    private fun fileTypeFor(text: String): com.intellij.openapi.fileTypes.FileType {
        val trimmed = text.trim()
        val first = trimmed.firstOrNull()
        if (first == '{' || first == '[') {
            val ok = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(trimmed); true
            }.getOrDefault(false)
            if (ok) return FileTypeManager.getInstance().findFileTypeByName("JSON")
                ?: PlainTextFileType.INSTANCE
        }
        return PlainTextFileType.INSTANCE
    }

    private fun applyHighlighter(editor: EditorEx, fileType: com.intellij.openapi.fileTypes.FileType) {
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType)
    }

    private fun setDocumentText(doc: Document, text: String) {
        ApplicationManager.getApplication().runWriteAction {
            doc.setText(text)
        }
    }

    private fun createJsonViewer(doc: Document, project: Project, softWrap: Boolean = false): Editor {
        val factory = EditorFactory.getInstance()
        val editor = factory.createViewer(doc, project) as EditorEx
        // Use JSON syntax highlighting if the JSON file type is registered (it is in all IntelliJ-based IDEs).
        val jsonFileType = FileTypeManager.getInstance().findFileTypeByName("JSON") ?: PlainTextFileType.INSTANCE
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, jsonFileType)
        editor.settings.apply {
            isLineNumbersShown = false
            isLineMarkerAreaShown = false
            isFoldingOutlineShown = false
            isUseSoftWraps = softWrap
            additionalLinesCount = 0
            additionalColumnsCount = 0
            isCaretRowShown = false
            isRightMarginShown = false
            isIndentGuidesShown = false
        }
        // Hide the entire gutter component to drop the left margin completely.
        editor.gutterComponentEx.isVisible = false
        editor.setBorder(JBUI.Borders.empty())
        return editor
    }

    private fun openDetailsDialog() {
        val record = list.selectedValue ?: return
        CallDetailsDialog(project, record).show()
    }

}

private class CallRecordRenderer : JPanel(), ListCellRenderer<McpCompanionSettings.CallRecord> {

    private val timeFormat = SimpleDateFormat("HH:mm:ss")
    private val ourIcon = IconLoader.getIcon("/icons/mcpStatusIdle.svg", McpCompanionCallsPanel::class.java)
    // Transparent placeholder of the same size keeps row height consistent for non-our tools.
    private val emptyIcon = com.intellij.util.ui.EmptyIcon.create(ourIcon.iconWidth, ourIcon.iconHeight)

    private val statusTimeLabel = JLabel()
    private val iconLabel = JLabel()
    private val nameDurationLabel = JLabel()

    init {
        layout = GridBagLayout()
        border = JBUI.Borders.empty(4, 8)
        isOpaque = true
        // Column 1 — status + time (fixed width, predictable column).
        add(statusTimeLabel, GridBagConstraints().apply {
            gridx = 0; anchor = GridBagConstraints.WEST
            ipadx = JBUI.scale(4)
        })
        // Column 2 — our MCP icon (16 px, fixed). Empty placeholder for non-our tools.
        add(iconLabel, GridBagConstraints().apply {
            gridx = 1; anchor = GridBagConstraints.WEST
            insets = JBUI.insets(0, 6, 0, 6)
        })
        // Column 3 — tool name + duration (takes remaining space).
        add(nameDurationLabel, GridBagConstraints().apply {
            gridx = 2; anchor = GridBagConstraints.WEST
            weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
        })
    }

    override fun getListCellRendererComponent(
        list: JList<out McpCompanionSettings.CallRecord>,
        value: McpCompanionSettings.CallRecord,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val statusIcon = when (value.status) {
            McpCompanionSettings.CallRecord.Status.RUNNING -> "●"
            McpCompanionSettings.CallRecord.Status.SUCCESS -> "✓"
            McpCompanionSettings.CallRecord.Status.ERROR -> "✗"
        }
        val time = timeFormat.format(Date(value.startedAtMillis))
        val durationStr = value.durationMs?.let { formatMs(it) } ?: "running…"
        val ownership = if (value.isOwnTool) "" else " (other)"

        statusTimeLabel.text = "<html><nobr>$statusIcon&nbsp;<code>$time</code></nobr></html>"
        // Show our wireframe icon ONLY for our own tools — empty placeholder for others (keeps alignment).
        iconLabel.icon = if (value.isOwnTool) ourIcon else emptyIcon
        nameDurationLabel.text = "<html><nobr><b>${value.toolName}</b>${ownership}&nbsp;&mdash; $durationStr</nobr></html>"

        val bg = if (isSelected) UIUtil.getListSelectionBackground(true) else UIUtil.getListBackground()
        val fg = when {
            isSelected -> UIUtil.getListSelectionForeground(true)
            !value.isOwnTool -> JBColor.GRAY
            value.status == McpCompanionSettings.CallRecord.Status.ERROR -> JBColor.RED
            else -> UIUtil.getListForeground()
        }
        background = bg
        statusTimeLabel.background = bg; statusTimeLabel.foreground = fg
        nameDurationLabel.background = bg; nameDurationLabel.foreground = fg
        statusTimeLabel.font = list.font
        nameDurationLabel.font = list.font
        return this
    }

    private fun formatMs(ms: Long): String = when {
        ms < 1_000 -> "${ms} ms"
        ms < 10_000 -> "%.1f s".format(ms / 1000.0)
        ms < 60_000 -> "${ms / 1000} s"
        else -> "${ms / 60_000} m ${(ms % 60_000) / 1000} s"
    }
}

private class CallDetailsDialog(
    project: Project,
    private val record: McpCompanionSettings.CallRecord,
) : DialogWrapper(project, true) {

    init {
        title = "MCP Call: ${record.toolName}"
        isModal = false
        init()
    }

    override fun createCenterPanel(): JComponent {
        val sb = StringBuilder()
        sb.appendLine("Tool: ${record.toolName}${if (record.isOwnTool) "" else "  (other plugin)"}")
        sb.appendLine("Call ID: ${record.callId}")
        sb.appendLine("Status: ${record.status}")
        sb.appendLine("Started: ${java.time.Instant.ofEpochMilli(record.startedAtMillis)}")
        record.durationMs?.let { sb.appendLine("Duration: $it ms") }
        record.client?.let { sb.appendLine("Client: $it") }
        record.errorMessage?.let { sb.appendLine("Error: $it") }
        sb.appendLine()
        val payload = io.nimbly.mcpcompanion.util.CallPayloadStorage.load(record.callId)
        sb.appendLine("─── Parameters (raw JSON) ───")
        sb.appendLine(payload?.parameters ?: "(not on disk)")
        sb.appendLine()
        sb.appendLine("─── Response ───")
        sb.appendLine(payload?.response ?: "(not captured — own tools capture via captureResponse(); other plugins do not)")

        val textArea = JTextArea(sb.toString()).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12))
            lineWrap = false
        }
        val scroll = JBScrollPane(textArea).apply {
            preferredSize = Dimension(700, 500)
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }
        val panel = JPanel(BorderLayout())
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }
}
