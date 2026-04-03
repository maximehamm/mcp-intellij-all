package io.nimbly.mcpcompanion

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class McpCompanionStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val ourSettings = McpCompanionSettings.getInstance()
        if (ourSettings.state.firstLaunchDone) return
        ourSettings.state.firstLaunchDone = true

        try {
            val mcpSettingsClass = Class.forName("com.intellij.mcpserver.settings.McpServerSettings")
            val mcpSettings = mcpSettingsClass.methods.find { it.name == "getInstance" && it.parameterCount == 0 }
                ?.invoke(null) ?: return
            val state = mcpSettingsClass.methods.find { it.name == "getState" }
                ?.invoke(mcpSettings) ?: return
            val isEnabled = state.javaClass.methods.find { it.name == "getEnableMcpServer" }
                ?.invoke(state) as? Boolean

            if (isEnabled == false) {
                state.javaClass.methods.find { it.name == "setEnableMcpServer" }?.invoke(state, true)
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("MCP Server Companion")
                    .createNotification(
                        "MCP Server Companion activated",
                        "MCP Server was disabled and has been automatically enabled.",
                        NotificationType.INFORMATION)
                    .addAction(com.intellij.notification.NotificationAction.createSimple("Open MCP Server Companion Settings") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(project, "MCP Server Companion")
                    })
                    .notify(project)
            }
        } catch (_: Exception) {
            // MCP Server plugin not installed — nothing to do
        }
    }
}
