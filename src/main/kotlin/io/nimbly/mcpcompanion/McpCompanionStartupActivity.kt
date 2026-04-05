package io.nimbly.mcpcompanion

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

class McpCompanionStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {

        // Register Escape key listener on all current and future editors
        ApplicationManager.getApplication().invokeLater {
            EditorFactory.getInstance().allEditors.forEach { addEscapeListener(it) }
            EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) = addEscapeListener(event.editor)
            }, project)
        }

        val ourSettings = McpCompanionSettings.getInstance()

        // ── Telemetry consent notification (shown once) ───────────────────────
        if (!ourSettings.isTelemetryNotificationShown()) {
            ourSettings.setTelemetryNotificationShown(true)  // mark shown immediately — dismiss = accept
            ApplicationManager.getApplication().invokeLater {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("MCP Server Companion")
                    .createNotification(
                        "MCP Server Companion",
                        "To improve this plugin, anonymous usage statistics are shared.",
                        NotificationType.INFORMATION
                    )
                    .addAction(object : AnAction("OK, got it") {
                        override fun actionPerformed(e: AnActionEvent) {
                            ourSettings.setTelemetryEnabled(true)
                        }
                    })
                    .addAction(object : AnAction("Disable sharing") {
                        override fun actionPerformed(e: AnActionEvent) {
                            ourSettings.setTelemetryEnabled(false)
                        }
                    })
                    .notify(project)
            }
        }

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

    private fun addEscapeListener(editor: Editor) {
        editor.contentComponent.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ESCAPE) {
                    val toRemove = editor.markupModel.allHighlighters
                        .filter { it.getUserData(MCP_HIGHLIGHT_KEY) == true }
                    if (toRemove.isNotEmpty()) {
                        toRemove.forEach { editor.markupModel.removeHighlighter(it) }
                        e.consume()
                    }
                }
            }
        })
    }
}
