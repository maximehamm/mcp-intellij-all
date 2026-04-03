package io.nimbly.mcpcompanion

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import java.awt.event.HierarchyEvent
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.Timer

class McpCompanionConfigurable : BoundConfigurable("MCP Server Companion") {

    private val toolCheckboxes = mutableListOf<JCheckBox>()
    private var warningLabel: JLabel? = null
    private val refreshTimer = Timer(300) { refreshState() }

    override fun createPanel(): DialogPanel {
        val settings = McpCompanionSettings.getInstance()
        toolCheckboxes.clear()

        return panel {
            row {
                label("<html><b>Example:</b> <i>\"Add a breakpoint at line 18, start the debugger, stop when i == 3, then tell me the value of jj.\"</i></html>")
                warningLabel = label("").applyToComponent { updateWarning() }.component
            }.bottomGap(BottomGap.NONE)
            group("Exposed Tools") {
                McpCompanionSettings.ALL_TOOLS.forEach { (name, description) ->
                    row {
                        checkBox(name)
                            .comment(description)
                            .bindSelected(
                                getter = { settings.isEnabled(name) },
                                setter = { settings.setEnabled(name, it) }
                            )
                            .applyToComponent {
                                toolCheckboxes.add(this)
                                isEnabled = isMcpServerEnabled()
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

    private fun updateWarning() {
        val mcpEnabled = isMcpServerEnabled()
        warningLabel?.text = if (!mcpEnabled)
            "<html><b>MCP is disabled, please enable it to enable MCP Server Companion.</b><br>" +
            "<small>Settings → Tools → MCP Server → Enable MCP Server</small></html>"
        else ""
    }

    private fun refreshState() {
        updateWarning()
        val mcpEnabled = isMcpServerEnabled()
        toolCheckboxes.forEach { it.isEnabled = mcpEnabled }
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
