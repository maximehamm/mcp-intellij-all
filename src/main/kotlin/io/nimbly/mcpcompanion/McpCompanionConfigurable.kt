package io.nimbly.mcpcompanion

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class McpCompanionConfigurable : BoundConfigurable("MCP Server Companion") {

    override fun createPanel(): DialogPanel {
        val settings = McpCompanionSettings.getInstance()
        return panel {
            group("Exposed Tools") {
                McpCompanionSettings.ALL_TOOLS.forEach { (name, description) ->
                    row {
                        checkBox(name)
                            .comment(description)
                            .bindSelected(
                                getter = { settings.isEnabled(name) },
                                setter = { settings.setEnabled(name, it) }
                            )
                    }
                }
            }
        }
    }
}
