package io.nimbly.mcpcompanion

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JCheckBox

class McpCompanionConfigurable : BoundConfigurable("MCP Server Companion") {

    private val toolCheckboxes = mutableListOf<JCheckBox>()

    override fun createPanel(): DialogPanel {
        val settings = McpCompanionSettings.getInstance()
        toolCheckboxes.clear()

        return panel {
            if (!isMcpServerEnabled()) {
                row {
                    label("⚠\uFE0F MCP Server is disabled. Please enable it in Settings → Tools → MCP Server → Enable MCP Server.")
                        .applyToComponent {
                            foreground = com.intellij.ui.JBColor.RED
                        }
                }
            }
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
        }
    }

    override fun reset() {
        super.reset()
        val enabled = isMcpServerEnabled()
        toolCheckboxes.forEach { it.isEnabled = enabled }
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
