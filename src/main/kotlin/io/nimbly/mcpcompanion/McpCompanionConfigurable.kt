package io.nimbly.mcpcompanion

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.HierarchyEvent
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.Timer

class McpCompanionConfigurable : BoundConfigurable("MCP Server Companion") {

    private val toolCheckboxes = mutableListOf<JCheckBox>()
    private val descriptionLabels = mutableListOf<JLabel>()
    private var warningLabel: JLabel? = null
    private val refreshTimer = Timer(300) { refreshState() }

    override fun createPanel(): DialogPanel {
        val settings = McpCompanionSettings.getInstance()
        toolCheckboxes.clear()
        descriptionLabels.clear()

        return panel {
            row {
                warningLabel = label("").applyToComponent { updateWarning() }.component
            }.bottomGap(BottomGap.NONE)
            row {
                label("<html><b>Example:</b> <i>\"Add a breakpoint at line 18, start the debugger, stop when i == 3, then tell me the value of jj.\"</i></html>")
            }.bottomGap(BottomGap.NONE)

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
                                    toolCheckboxes.add(this)
                                    isEnabled = isMcpServerEnabled()
                                    toolTipText = tooltip
                                }
                            label("<html><font color='${UIUtil.getContextHelpForeground().toHex()}'>$shortDesc</font></html>")
                                .applyToComponent {
                                    descriptionLabels.add(this)
                                    toolTipText = tooltip
                                }
                        }
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

    // ── Descriptions via reflection on @McpDescription ───────────────────────

    private fun rawDescription(toolName: String): String? = try {
        McpCompanionToolset::class.java.methods
            .find { it.getAnnotation(McpTool::class.java)?.name == toolName }
            ?.getAnnotation(McpDescription::class.java)
            ?.description
            ?.trimIndent()
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
        toolCheckboxes.forEach { it.isEnabled = mcpEnabled }
        descriptionLabels.forEach { it.isEnabled = mcpEnabled }
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
        fun isMcpServerEnabled(): Boolean = try {
            val cls = Class.forName("com.intellij.mcpserver.settings.McpServerSettings")
            val instance = cls.methods.find { it.name == "getInstance" && it.parameterCount == 0 }?.invoke(null)
            val state = cls.methods.find { it.name == "getState" }?.invoke(instance)
            state?.javaClass?.methods?.find { it.name == "getEnableMcpServer" }?.invoke(state) as? Boolean ?: true
        } catch (_: Exception) { true }
    }
}
