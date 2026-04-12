package io.nimbly.mcpcompanion

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.gridLayout.UnscaledGaps
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingUtilities
import javax.swing.Timer

class McpCompanionConfigurable : BoundConfigurable("MCP Server Companion") {

    private enum class StatsMode { SESSION, GLOBAL }
    private var statsMode = StatsMode.SESSION
    private var globalStats: Map<String, Int>? = null
    private var globalStatsLoadedAt: Long = 0L
    private var isLoadingGlobal = false

    private var sessionToggle: JToggleButton? = null
    private var globalToggle: JToggleButton? = null
    private var refreshButton: JButton? = null
    private var lastUpdatedLabel: JLabel? = null
    private var telemetryCheckbox: JCheckBox? = null

    private val toolCheckboxes = mutableListOf<JCheckBox>()
    /** Checkboxes whose tool requires an optional plugin that is not currently installed. */
    private val pluginUnavailableCheckboxes = mutableSetOf<JCheckBox>()
    private val descriptionLabels = mutableListOf<JLabel>()
    private var warningLabel: JLabel? = null
    private val refreshTimer = Timer(300) { refreshState() }

    private data class UsageRow(val toolName: String, val bar: UsageBarPanel)
    private val usageRows = mutableListOf<UsageRow>()

    private inner class UsageBarPanel(val toolName: String, val countProvider: () -> Int) : JPanel() {
        var scale: Int = 10
        init {
            preferredSize = Dimension(BAR_W + COUNT_W, 18)
            isOpaque = false
        }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            val count = countProvider()
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val fg = UIUtil.getLabelForeground()
            val barH = 5
            val y = (height - barH) / 2
            val trackW = BAR_W - 2
            // Track
            g2.color = java.awt.Color(fg.red, fg.green, fg.blue, 35)
            g2.fillRoundRect(0, y, trackW, barH, barH, barH)
            // Fill
            if (count > 0) {
                val fillW = ((trackW * count.toFloat()) / scale).toInt().coerceAtLeast(barH)
                g2.color = java.awt.Color(fg.red, fg.green, fg.blue, 180)
                g2.fillRoundRect(0, y, fillW, barH, barH, barH)
            }
            // Count number — left-aligned just after the track, with a small gap
            if (count > 0) {
                val countStr = "$count"
                g2.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 10f)
                val fm = g2.fontMetrics
                g2.color = UIUtil.getContextHelpForeground()
                g2.drawString(countStr, BAR_W + 3, (height + fm.ascent - fm.descent) / 2)
            }
        }
    }

    override fun createPanel(): DialogPanel {
        val settings = McpCompanionSettings.getInstance()
        toolCheckboxes.clear()
        pluginUnavailableCheckboxes.clear()
        descriptionLabels.clear()
        usageRows.clear()

        // Fixed checkbox width = widest tool name — ensures bars align across all groups
        val longestName = McpCompanionSettings.TOOL_GROUPS.values.flatten().maxByOrNull { it.length } ?: ""
        val maxCbWidth = JCheckBox(longestName).preferredSize.width

        return panel {
            row {
                warningLabel = label("").applyToComponent { updateWarning() }.component
            }.bottomGap(BottomGap.NONE)
            row {
                label("<html><b>Example:</b> <i>\"Add a breakpoint at line 18, start the debugger, stop when i == 3, then tell me the value of jj.\"</i></html>")
            }.bottomGap(BottomGap.NONE)

            McpCompanionSettings.TOOL_GROUPS.forEach { (groupName, toolNames) ->
                group(groupName) {
                    toolNames.forEach { name ->
                        val tooltip = buildTooltip(name)
                        val shortDesc = buildShortDescription(name)
                        row {
                            checkBox(name)
                                .bindSelected(
                                    getter = { settings.isEnabled(name) },
                                    setter = { settings.setEnabled(name, it) }
                                )
                                .applyToComponent {
                                    preferredSize = Dimension(maxCbWidth, preferredSize.height)
                                    minimumSize = Dimension(maxCbWidth, minimumSize.height)
                                    toolCheckboxes.add(this)
                                    val pluginAvailable = McpCompanionSettings.isPluginAvailable(name)
                                    if (!pluginAvailable) {
                                        pluginUnavailableCheckboxes.add(this)
                                        toolTipText = "<html>Requires the <b>Database Tools and SQL</b> plugin<br>" +
                                            "(available in IntelliJ IDEA Ultimate)</html>"
                                    } else {
                                        toolTipText = tooltip
                                    }
                                    isEnabled = isMcpServerEnabled() && pluginAvailable
                                }
                                .gap(RightGap.SMALL)
                            val bar = UsageBarPanel(name) {
                                when (statsMode) {
                                    StatsMode.SESSION -> settings.getCallCount(name)
                                    StatsMode.GLOBAL  -> globalStats?.get(name) ?: 0
                                }
                            }
                            usageRows += UsageRow(name, bar)
                            cell(bar).applyToComponent {
                                toolTipText = buildUsageTooltip(name)
                            }.customize(UnscaledGaps(right = 6))
                            label("<html><font color='${UIUtil.getContextHelpForeground().toHex()}'>$shortDesc</font></html>")
                                .applyToComponent {
                                    descriptionLabels.add(this)
                                    toolTipText = tooltip
                                }
                        }
                    }
                }
            }

            // ── Analytics opt-in + toggle ─────────────────────────────────────
            group("Analytics") {
                row {
                    checkBox("Share anonymous usage statistics")
                        .bindSelected(
                            getter = { settings.isTelemetryEnabled() },
                            setter = { settings.setTelemetryEnabled(it) }
                        )
                        .applyToComponent {
                            telemetryCheckbox = this
                            addActionListener {
                                if (!isSelected) {
                                    statsMode = StatsMode.SESSION
                                    sessionToggle?.isSelected = true
                                    globalToggle?.isSelected = false
                                }
                                refreshState()
                            }
                        }
                }
                row {
                    comment(
                        "Sends tool name + call count anonymously on each tool use. " +
                        "No code, no file paths, no project data."
                    )
                }.bottomGap(BottomGap.NONE)

                row {
                    val sessionBtn = JToggleButton("My statistics", statsMode == StatsMode.SESSION)
                    val globalBtn  = JToggleButton("All users statistics", statsMode == StatsMode.GLOBAL)
                    sessionToggle = sessionBtn
                    globalToggle  = globalBtn

                    sessionBtn.addActionListener {
                        statsMode = StatsMode.SESSION
                        globalBtn.isSelected = false
                        sessionBtn.isSelected = true
                        refreshState()
                    }
                    globalBtn.addActionListener {
                        if (!isStatsToggleEnabled()) return@addActionListener
                        statsMode = StatsMode.GLOBAL
                        sessionBtn.isSelected = false
                        globalBtn.isSelected = true
                        if (globalStats == null && !isLoadingGlobal) fetchGlobalStats()
                        else refreshState()
                    }

                    cell(sessionBtn)
                    cell(globalBtn).gap(RightGap.SMALL)

                    val refreshBtn = JButton("↺").also {
                        it.toolTipText = "Refresh global statistics"
                        it.isVisible = statsMode == StatsMode.GLOBAL
                        it.addActionListener { fetchGlobalStats() }
                        refreshButton = it
                    }
                    cell(refreshBtn).gap(RightGap.SMALL)

                    lastUpdatedLabel = label("").applyToComponent {
                        font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 10f)
                        foreground = UIUtil.getContextHelpForeground()
                    }.component
                }
            }

            group("Claude Code Setup") {
                row {
                    button("Add to CLAUDE.md") { addClaudeMd() }
                        .comment("Adds a one-liner to CLAUDE.md in the current project so Claude calls get_mcp_companion_overview on startup")
                }
                row {
                    comment("""To skip permission prompts, add <code>"mcp__intellij__*"</code> to the <code>permissions.allow</code> array in <code>~/.claude/settings.json</code> and restart Claude Code:""")
                }
                row {
                    val snippet = """  "permissions": { "allow": ["mcp__intellij__*"] }"""
                    textField()
                        .applyToComponent {
                            text = snippet
                            isEditable = false
                            columns = 52
                            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                        }
                    button("Copy") {
                        Toolkit.getDefaultToolkit().systemClipboard
                            .setContents(StringSelection(snippet.trim()), null)
                    }
                }
            }

        }.also { panel ->
            panel.addHierarchyListener { e ->
                if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) {
                    if (panel.isShowing) { refreshState(); refreshTimer.start() }
                    else refreshTimer.stop()
                }
            }
        }
    }

    // ── Global stats fetch ────────────────────────────────────────────────────

    /** Returns true if the telemetry checkbox is currently checked (even before Apply). */
    private fun isStatsToggleEnabled(): Boolean =
        telemetryCheckbox?.isSelected ?: McpCompanionSettings.getInstance().isTelemetryEnabled()

    private fun fetchGlobalStats() {
        if (isLoadingGlobal) return
        isLoadingGlobal = true
        refreshButton?.isEnabled = false
        lastUpdatedLabel?.text = "Loading…"

        ApplicationManager.getApplication().executeOnPooledThread {
            val stats = McpCompanionTelemetry.fetchGlobalStats()
            SwingUtilities.invokeLater {
                isLoadingGlobal = false
                refreshButton?.isEnabled = true
                if (stats != null) {
                    globalStats = stats
                    globalStatsLoadedAt = System.currentTimeMillis()
                } else {
                    lastUpdatedLabel?.text = "Failed to load"
                }
                refreshState()
            }
        }
    }

    // ── Descriptions via reflection on @McpDescription ───────────────────────

    private val toolsetClasses: List<Class<*>> by lazy {
        val pkg = McpCompanionConfigurable::class.java.packageName
        val classLoader = McpCompanionConfigurable::class.java.classLoader
        runCatching {
            classLoader.getResources(pkg.replace('.', '/'))
                .asSequence()
                .flatMap { url ->
                    when (url.protocol) {
                        "jar" -> (url.openConnection() as java.net.JarURLConnection).jarFile
                            .entries().asSequence()
                            .filter { !it.isDirectory && it.name.endsWith(".class") && '$' !in it.name }
                            .mapNotNull { runCatching { classLoader.loadClass(it.name.removeSuffix(".class").replace('/', '.')) }.getOrNull() }
                        "file" -> {
                            val dir = java.io.File(url.toURI())
                            dir.walkTopDown()
                                .filter { it.isFile && it.extension == "class" && '$' !in it.name }
                                .mapNotNull { f ->
                                    val relative = f.relativeTo(dir).path.removeSuffix(".class").replace(java.io.File.separatorChar, '.')
                                    runCatching { classLoader.loadClass("$pkg.$relative") }.getOrNull()
                                }
                        }
                        else -> emptySequence()
                    }
                }
                .filter { com.intellij.mcpserver.McpToolset::class.java.isAssignableFrom(it) && !it.isInterface }
                .toList()
        }.getOrElse { emptyList() }
    }

    private fun rawDescription(toolName: String): String? = try {
        toolsetClasses.firstNotNullOfOrNull { cls ->
            cls.methods
                .find { it.getAnnotation(McpTool::class.java)?.name == toolName }
                ?.getAnnotation(McpDescription::class.java)
                ?.description
                ?.trimIndent()
        }
    } catch (_: Exception) { null }

    private fun buildShortDescription(toolName: String): String {
        val raw = rawDescription(toolName) ?: return ""
        val firstLine = raw.lines().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        return if (firstLine.length > 80) firstLine.take(80) + "…" else firstLine
    }

    private fun buildTooltip(toolName: String): String? {
        val raw = rawDescription(toolName) ?: return null
        val escaped = raw.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br/>")
        return "<html>$escaped</html>"
    }

    private fun java.awt.Color.toHex() = "#%02x%02x%02x".format(red, green, blue)

    private fun buildUsageTooltip(toolName: String): String {
        return when (statsMode) {
            StatsMode.SESSION -> {
                val count = McpCompanionSettings.getInstance().getCallCount(toolName)
                if (count == 0) "Not called since IDE launch"
                else "$count call${if (count > 1) "s" else ""} since IDE launch"
            }
            StatsMode.GLOBAL -> {
                val count = globalStats?.get(toolName)
                when {
                    globalStats == null -> "Global stats not loaded yet"
                    count == null || count == 0 -> "No calls recorded"
                    else -> "$count total call${if (count > 1) "s" else ""} across all users"
                }
            }
        }
    }

    // ── Button actions ────────────────────────────────────────────────────────

    private fun addClaudeMd() {
        val projects = ProjectManager.getInstance().openProjects
        if (projects.isEmpty()) {
            Messages.showWarningDialog("No project is currently open.", "CLAUDE.md")
            return
        }

        val project = if (projects.size == 1) {
            projects[0]
        } else {
            val names = projects.map { it.name }.toTypedArray()
            val choice = Messages.showChooseDialog(
                "Select the project to update:", "Add to CLAUDE.md", names, names[0], null)
            if (choice < 0) return
            projects[choice]
        }

        val basePath = project.basePath ?: run {
            Messages.showErrorDialog("Cannot determine project base path.", "CLAUDE.md")
            return
        }

        val instruction =
            "# IntelliJ MCP Server Companion\n" +
            "This project is open in IntelliJ IDEA. " +
            "Call `get_mcp_companion_overview` to discover available tools and how to use them.\n"

        val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: run {
            Messages.showErrorDialog("Cannot find project directory in VFS.", "CLAUDE.md")
            return
        }

        val existingFile = baseDir.findChild("CLAUDE.md")
        if (existingFile != null) {
            val doc = FileDocumentManager.getInstance().getDocument(existingFile)
            if (doc != null && doc.text.contains("get_mcp_companion_overview")) {
                Messages.showInfoMessage("CLAUDE.md already contains MCP instructions.", "CLAUDE.md")
                return
            }
        }

        WriteCommandAction.runWriteCommandAction(project, "Add MCP Instructions to CLAUDE.md", null, {
            val vFile = existingFile ?: baseDir.createChildData(this, "CLAUDE.md")
            val doc = FileDocumentManager.getInstance().getDocument(vFile) ?: return@runWriteCommandAction
            val prefix = if (doc.textLength > 0) "\n" else ""
            doc.insertString(doc.textLength, prefix + instruction)
            FileEditorManager.getInstance(project).openFile(vFile, true)
        })

        Messages.showInfoMessage(
            if (existingFile == null) "CLAUDE.md created." else "MCP instructions added to CLAUDE.md.",
            "CLAUDE.md")
    }

    // ── UI refresh ────────────────────────────────────────────────────────────

    private fun updateWarning() {
        val mcpEnabled = isMcpServerEnabled()
        warningLabel?.text = if (!mcpEnabled)
            "<html><b>⚠ MCP is disabled, please enable it to enable MCP Server Companion.</b><br>" +
            "<small>Settings → Tools → MCP Server → Enable MCP Server</small></html>"
        else ""
    }

    private fun refreshState() {
        updateWarning()
        val mcpEnabled = isMcpServerEnabled()
        toolCheckboxes.forEach { cb ->
            cb.isEnabled = mcpEnabled && cb !in pluginUnavailableCheckboxes
        }
        descriptionLabels.forEach { it.isEnabled = mcpEnabled }

        // Scale bars to current data source
        val maxCalls = when (statsMode) {
            StatsMode.SESSION -> McpCompanionSettings.getInstance().maxCallCount()
            StatsMode.GLOBAL  -> globalStats?.values?.maxOrNull() ?: 0
        }
        val scale = if (maxCalls <= 0) 10 else ((maxCalls + 9) / 10) * 10
        usageRows.forEach { row ->
            row.bar.scale = scale
            row.bar.toolTipText = buildUsageTooltip(row.toolName)
            row.bar.repaint()
        }

        // Toggle state + grayed when telemetry disabled
        val statsEnabled = isStatsToggleEnabled()
        sessionToggle?.isSelected = statsMode == StatsMode.SESSION
        sessionToggle?.isEnabled  = statsEnabled
        globalToggle?.isSelected  = statsMode == StatsMode.GLOBAL
        globalToggle?.isEnabled   = statsEnabled
        refreshButton?.isVisible  = statsMode == StatsMode.GLOBAL
        refreshButton?.isEnabled  = statsEnabled && !isLoadingGlobal

        // Last-updated label
        if (statsMode == StatsMode.GLOBAL && !isLoadingGlobal) {
            lastUpdatedLabel?.text = when {
                globalStats == null -> ""
                else -> {
                    val ago = (System.currentTimeMillis() - globalStatsLoadedAt) / 1000
                    if (ago < 60) "Updated ${ago}s ago" else "Updated ${ago / 60}min ago"
                }
            }
        } else if (statsMode == StatsMode.SESSION) {
            lastUpdatedLabel?.text = ""
        }
    }

    override fun reset() {
        super.reset()
        refreshState()
    }

    override fun disposeUIResources() {
        refreshTimer.stop()
        super.disposeUIResources()
    }

    companion object {
        private const val BAR_W = 68
        private const val COUNT_W = 26

        fun isMcpServerEnabled(): Boolean = try {
            val cls = Class.forName("com.intellij.mcpserver.settings.McpServerSettings")
            val instance = cls.methods.find { it.name == "getInstance" && it.parameterCount == 0 }?.invoke(null)
            val state = cls.methods.find { it.name == "getState" }?.invoke(instance)
            state?.javaClass?.methods?.find { it.name == "getEnableMcpServer" }?.invoke(state) as? Boolean ?: true
        } catch (_: Exception) { true }
    }
}
