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
import com.intellij.openapi.ui.SimpleToolWindowPanel
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

/** Layout orientation for the Monitoring tool window — persisted via [PropertiesComponent]. */
enum class MonitoringOrientation { VERTICAL, HORIZONTAL }

internal class McpCompanionCallsPanel(private val project: Project) : SimpleToolWindowPanel(/* vertical = */ true, /* borderless = */ true) {

    private val model = DefaultListModel<McpCompanionSettings.CallRecord>()

    // Read-only JSON viewers — proper IntelliJ Editor with syntax highlighting + code folding.
    private val parametersDocument: Document = EditorFactory.getInstance().createDocument("")
    private val responseDocument: Document = EditorFactory.getInstance().createDocument("")
    private val errorsDocument: Document = EditorFactory.getInstance().createDocument("")
    private val parametersEditor: Editor = createJsonViewer(parametersDocument, project)
    private val responseEditor: Editor = createJsonViewer(responseDocument, project)
    // Errors are usually long single-line stack traces — enable soft wrap so the user can read them.
    private val errorsEditor: Editor = createJsonViewer(errorsDocument, project, softWrap = true)

    /** Removes the inner padding/insets that JBTabbedPane adds around tab content — keeps the
     *  Editor inside aligned with the tab edges (no visible gap on the left). */
    private fun com.intellij.ui.components.JBTabbedPane.flushContent() {
        tabComponentInsets = JBUI.emptyInsets()
        border = JBUI.Borders.empty()
    }

    /** Top tab in the "separate" layout: just "Parameters". */
    private val parametersTabbedPane = com.intellij.ui.components.JBTabbedPane().apply {
        addTab("Parameters", parametersEditor.component)
        flushContent()
    }

    /** Bottom tabs in the "separate" layout: Response + Errors. */
    private val responseErrorsTabbedPane = com.intellij.ui.components.JBTabbedPane().apply {
        addTab("Response", responseEditor.component)
        addTab("Errors", errorsEditor.component)
        flushContent()
    }

    /** "Grouped" layout: a single tabbed pane with all 3 panels side-by-side. */
    private val groupedTabbedPane = com.intellij.ui.components.JBTabbedPane().apply {
        addTab("Parameters", parametersEditor.component)
        addTab("Response", responseEditor.component)
        addTab("Errors", errorsEditor.component)
        flushContent()
    }

    // ── Persisted UI state (toolbar toggles + hidden tools) ───────────────
    private val props = com.intellij.ide.util.PropertiesComponent.getInstance()
    private val PROP_ORIENTATION = "io.nimbly.mcpcompanion.calls.orientation"
    private val PROP_GROUPED = "io.nimbly.mcpcompanion.calls.grouped"
    private val PROP_HIDDEN_TOOLS = "io.nimbly.mcpcompanion.calls.hiddenTools"

    private var orientationMode: MonitoringOrientation =
        runCatching { MonitoringOrientation.valueOf(props.getValue(PROP_ORIENTATION, MonitoringOrientation.VERTICAL.name)) }
            .getOrDefault(MonitoringOrientation.VERTICAL)
    private var groupedMode: Boolean = props.getBoolean(PROP_GROUPED, false)
    /** Tools hidden via right-click → "Hide …". Persisted across IDE restarts as a `\n`-joined
     *  list in [PropertiesComponent] under [PROP_HIDDEN_TOOLS]. Empty = no hiding. */
    private val hiddenTools: MutableSet<String> = loadHiddenTools()

    private fun loadHiddenTools(): MutableSet<String> {
        val raw = props.getValue(PROP_HIDDEN_TOOLS, "")
        return raw.split('\n').filter { it.isNotBlank() }.toMutableSet()
    }

    private fun saveHiddenTools() {
        if (hiddenTools.isEmpty()) {
            props.unsetValue(PROP_HIDDEN_TOOLS)
        } else {
            props.setValue(PROP_HIDDEN_TOOLS, hiddenTools.joinToString("\n"))
        }
    }

    private val list = JBList(model).apply {
        cellRenderer = CallRecordRenderer()
        background = UIUtil.getListBackground()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(e) && e.clickCount == 2) openDetailsDialog()
            }
            override fun mousePressed(e: MouseEvent) { maybeShowPopup(e) }
            override fun mouseReleased(e: MouseEvent) { maybeShowPopup(e) }
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

    private fun maybeShowPopup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        // Right-click should also select the row underneath (UX expectation).
        val idx = list.locationToIndex(e.point)
        if (idx >= 0) list.selectedIndex = idx
        val record = list.selectedValue ?: return
        val popup = javax.swing.JPopupMenu()
        popup.add(javax.swing.JMenuItem("Hide \"${record.toolName}\"").apply {
            addActionListener { hideTool(record.toolName) }
        })
        if (hiddenTools.isNotEmpty()) {
            popup.add(javax.swing.JMenuItem("Show all hidden tools (${hiddenTools.size})").apply {
                addActionListener { resetHidden() }
            })
        }
        popup.show(e.component, e.x, e.y)
    }

    private fun hideTool(name: String) {
        hiddenTools.add(name)
        saveHiddenTools()
        refreshFromSettings()
    }

    private fun resetHidden() {
        hiddenTools.clear()
        saveHiddenTools()
        refreshFromSettings()
    }

    private val refreshListener: () -> Unit = {
        ApplicationManager.getApplication().invokeLater(::refreshFromSettings)
    }

    /** Holder for the currently active layout — replaced when toggles change. */
    private val centerHolder = JPanel(BorderLayout()).apply { background = UIUtil.getListBackground() }

    /** Reference to the details splitter (only used in "separate" mode); null in "grouped" mode. */
    private var detailsSplitter: JBSplitter? = null

    init {
        background = UIUtil.getListBackground()
        // SimpleToolWindowPanel: place toolbar in the tool window header strip (no double separator),
        // and the list/details splitter as the main content.
        val tb = buildToolbar()
        toolbar = tb.component
        setContent(centerHolder)
        rebuildLayout()
        preferredSize = Dimension(600, 500)
        refreshFromSettings()
        McpCompanionSettings.getInstance().addCallRecordListener(refreshListener)
    }

    private fun buildToolbar(): com.intellij.openapi.actionSystem.ActionToolbar {
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup().apply {
            add(ClearAction())
            addSeparator()
            add(OrientationAction())
            add(GroupedAction())
        }
        val toolbar = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .createActionToolbar("McpCompanionMonitoring", group, true)
        toolbar.targetComponent = this
        // Drop the default 1-px top border that JBToolbar paints by default — that's the thin gray
        // line between the tool window header and our actions.
        toolbar.component.border = JBUI.Borders.empty()
        return toolbar
    }

    private fun rebuildLayout() {
        centerHolder.removeAll()
        val effectiveVertical = orientationMode == MonitoringOrientation.VERTICAL
        val listPane = JBScrollPane(list).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            viewport.background = UIUtil.getListBackground()
            background = UIUtil.getListBackground()
        }
        val detailsComponent: JComponent = if (groupedMode) {
            // Single tabbed pane with 3 tabs side-by-side.
            detailsSplitter = null
            // Make sure all 3 editors are owned by groupedTabbedPane (re-add to handle rebuild after switch).
            groupedTabbedPane.removeAll()
            groupedTabbedPane.addTab("Parameters", parametersEditor.component)
            groupedTabbedPane.addTab("Response", responseEditor.component)
            groupedTabbedPane.addTab("Errors", errorsEditor.component)
            groupedTabbedPane
        } else {
            // Re-attach editors to the separate panes (after possible regrouping).
            parametersTabbedPane.removeAll()
            parametersTabbedPane.addTab("Parameters", parametersEditor.component)
            responseErrorsTabbedPane.removeAll()
            responseErrorsTabbedPane.addTab("Response", responseEditor.component)
            responseErrorsTabbedPane.addTab("Errors", errorsEditor.component)
            // The inner splitter follows the outer orientation: vertical mode → params/response stacked
            // top-bottom; horizontal mode → 3-column layout (list | params | response/errors).
            // Different splitter IDs per orientation so each persists its own proportion.
            val innerId = if (effectiveVertical)
                "io.nimbly.mcpcompanion.calls.detailsSplitter.vert.v1"
            else
                "io.nimbly.mcpcompanion.calls.detailsSplitter.horiz.v1"
            JBSplitter(effectiveVertical, innerId, if (effectiveVertical) 0.286f else 0.4f).apply {
                firstComponent = parametersTabbedPane
                secondComponent = responseErrorsTabbedPane
            }.also { detailsSplitter = it }
        }
        // Outer splitter ID also depends on orientation, again so each persists its own proportion.
        val outerId = if (effectiveVertical)
            "io.nimbly.mcpcompanion.calls.splitter.vert.v1"
        else
            "io.nimbly.mcpcompanion.calls.splitter.horiz.v1"
        val outerProportion = if (effectiveVertical) 0.30f else 0.25f
        val outer = JBSplitter(effectiveVertical, outerId, outerProportion).apply {
            firstComponent = listPane
            secondComponent = detailsComponent
            background = UIUtil.getListBackground()
        }
        centerHolder.add(outer, BorderLayout.CENTER)
        centerHolder.revalidate()
        centerHolder.repaint()
    }

    fun dispose() {
        McpCompanionSettings.getInstance().removeCallRecordListener(refreshListener)
        EditorFactory.getInstance().releaseEditor(parametersEditor)
        EditorFactory.getInstance().releaseEditor(responseEditor)
        EditorFactory.getInstance().releaseEditor(errorsEditor)
    }

    private fun refreshFromSettings() {
        val rememberedCallId = list.selectedValue?.callId
        val all = McpCompanionSettings.getInstance().getCallRecords()
        val records = all.filter { matchesFilters(it) }
        model.clear()
        records.forEach { model.addElement(it) }
        // Try to keep the same call selected after a refresh.
        if (rememberedCallId != null) {
            val newIndex = records.indexOfFirst { it.callId == rememberedCallId }
            if (newIndex >= 0) list.selectedIndex = newIndex
        } else if (records.isNotEmpty()) {
            list.selectedIndex = 0
        }
        updateDetailsPanel()
    }

    private fun matchesFilters(r: McpCompanionSettings.CallRecord): Boolean {
        if (r.toolName in hiddenTools) return false
        return true
    }

    private fun updateDetailsPanel() {
        val r = list.selectedValue
        val showDetails = r != null

        // Hide the entire lower section when nothing is selected (separate mode) or hide the
        // grouped tabbed pane (grouped mode).
        if (groupedMode) {
            if (groupedTabbedPane.isVisible != showDetails) {
                groupedTabbedPane.isVisible = showDetails
                groupedTabbedPane.parent?.revalidate()
                groupedTabbedPane.parent?.repaint()
            }
        } else {
            val ds = detailsSplitter
            if (ds != null && ds.isVisible != showDetails) {
                ds.isVisible = showDetails
                ds.parent?.revalidate()
                ds.parent?.repaint()
            }
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

        val showResponseErrors = r != null && r.isOwnTool
        if (groupedMode) {
            // Auto-switch to Errors tab WHEN the new record has an error — otherwise leave
            // whatever tab the user was on (don't yank them off Parameters or Response on each
            // selection change).
            if (showResponseErrors && errors.isNotEmpty()) {
                groupedTabbedPane.selectedIndex = 2
            } else if (!showResponseErrors) {
                // Other-tool record: only Parameters has content → snap to it.
                groupedTabbedPane.selectedIndex = 0
            }
            // No error and own tool → preserve current tab.
        } else {
            // Hide the Response/Errors panel for tools from other plugins.
            if (responseErrorsTabbedPane.isVisible != showResponseErrors) {
                responseErrorsTabbedPane.isVisible = showResponseErrors
                responseErrorsTabbedPane.parent?.revalidate()
                responseErrorsTabbedPane.parent?.repaint()
            }
            if (showResponseErrors && errors.isNotEmpty()) {
                // Auto-switch to Errors only when the selected record has an error.
                responseErrorsTabbedPane.selectedIndex = 1
            }
            // Otherwise preserve the user's current tab choice.
        }
    }

    // ── Toolbar actions ───────────────────────────────────────────────────

    private inner class ClearAction : com.intellij.openapi.actionSystem.AnAction(
        "Clear", "Clear all recorded MCP calls (in-memory + on-disk)",
        com.intellij.icons.AllIcons.Actions.GC
    ) {
        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
            McpCompanionSettings.getInstance().clearAllRecords()
            hiddenTools.clear()
        }
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }

    private inner class OrientationAction : com.intellij.openapi.actionSystem.AnAction(
        "Toggle Orientation",
        "Switch layout between vertical (stacked) and horizontal (3 columns)",
        IconLoader.getIcon("/icons/toggleOrientation.svg", McpCompanionCallsToolWindowFactory::class.java),
    ) {
        override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
            orientationMode = when (orientationMode) {
                MonitoringOrientation.VERTICAL -> MonitoringOrientation.HORIZONTAL
                MonitoringOrientation.HORIZONTAL -> MonitoringOrientation.VERTICAL
            }
            props.setValue(PROP_ORIENTATION, orientationMode.name)
            rebuildLayout()
            updateDetailsPanel()
        }
        override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
            e.presentation.text = "Orientation: ${orientationMode.name.lowercase().replaceFirstChar { it.uppercase() }}"
        }
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }

    private inner class GroupedAction : com.intellij.openapi.actionSystem.ToggleAction(
        "Group All Tabs Together",
        "When ON: all 3 panels (Parameters, Response, Errors) share a single tabbed pane.",
        com.intellij.icons.AllIcons.Actions.GroupBy,
    ) {
        override fun isSelected(e: com.intellij.openapi.actionSystem.AnActionEvent) = groupedMode
        override fun setSelected(e: com.intellij.openapi.actionSystem.AnActionEvent, state: Boolean) {
            groupedMode = state
            props.setValue(PROP_GROUPED, state)
            rebuildLayout()
            updateDetailsPanel()
        }
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
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
