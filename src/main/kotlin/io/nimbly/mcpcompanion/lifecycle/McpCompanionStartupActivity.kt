package io.nimbly.mcpcompanion.lifecycle

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
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
import io.nimbly.mcpcompanion.util.ProcessTracker
import io.nimbly.mcpcompanion.McpCompanionSettings
import io.nimbly.mcpcompanion.toolsets.MCP_HIGHLIGHT_KEY

class McpCompanionStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {

        // Eagerly start background-process polling so get_ide_snapshot reflects live activity.
        ProcessTracker.getInstance(project)

        // Subscribe (once per IDE) to MCP tool call events to populate the calls history.
        // Uses an AtomicBoolean guard because this ProjectActivity runs once per opened project
        // but we want a single application-level subscription.
        if (toolCallListenerSubscribed.compareAndSet(false, true)) {
            runCatching {
                ApplicationManager.getApplication().messageBus
                    .connect()  // tied to application disposable
                    .subscribe(
                        com.intellij.mcpserver.ToolCallListener.Companion.TOPIC,
                        McpCompanionToolCallListener(),
                    )
            }.onFailure {
                // MCP Server plugin not available or topic missing — non-fatal: history will be empty.
                toolCallListenerSubscribed.set(false)
            }
            // Silence the IDE "Internal Error" popup that the MCP framework triggers when a tool
            // call has missing/invalid parameters — those are user-facing errors (already shown in
            // the Monitoring tool window's Errors tab) and should not surface as an IDE bug.
            installMcpErrorFilter()
        }

        // Register Escape key listener on all current and future editors.
        // Guard with ESCAPE_LISTENER_KEY to prevent double-registration when multiple projects open.
        // editorReleased removes the listener so editors don't accumulate listeners after being closed.
        ApplicationManager.getApplication().invokeLater {
            EditorFactory.getInstance().allEditors.forEach { addEscapeListener(it) }
            EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) = addEscapeListener(event.editor)
                override fun editorReleased(event: EditorFactoryEvent) = removeEscapeListener(event.editor)
            }, project)
        }

        val ourSettings = McpCompanionSettings.getInstance()

        // ── Telemetry consent notification (shown once) ───────────────────────
        if (!ourSettings.isTelemetryNotificationShown()) {
            ourSettings.setTelemetryNotificationShown(true)  // mark shown immediately — dismiss = accept
            NotificationGroupManager.getInstance()
                .getNotificationGroup("MCP Server Companion")
                .createNotification(
                    "MCP Server Companion",
                    "To improve this plugin, anonymous usage statistics are shared.",
                    NotificationType.INFORMATION
                )
                .addAction(object : NotificationAction("OK, got it") {
                    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                        ourSettings.setTelemetryEnabled(true)
                        notification.expire()
                    }
                })
                .addAction(object : NotificationAction("Disable sharing") {
                    override fun actionPerformed(e: AnActionEvent, notification: Notification) {
                        ourSettings.setTelemetryEnabled(false)
                        notification.expire()
                    }
                })
                .notify(project)
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
                        "MCP Server Companion",
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
        // Prevent double-registration: if this editor already has our listener, skip.
        if (editor.getUserData(ESCAPE_LISTENER_KEY) != null) return
        val listener = object : KeyAdapter() {
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
        }
        editor.contentComponent.addKeyListener(listener)
        editor.putUserData(ESCAPE_LISTENER_KEY, listener)
    }

    private fun removeEscapeListener(editor: Editor) {
        val listener = editor.getUserData(ESCAPE_LISTENER_KEY) ?: return
        editor.contentComponent.removeKeyListener(listener)
        editor.putUserData(ESCAPE_LISTENER_KEY, null)
    }

    /**
     * Subscribes to `com.intellij.diagnostic.MessagePool` and auto-marks-as-read any IDE error
     * caused by an MCP framework parameter mismatch. Without this, every wrong-arg call surfaces
     * as a red "fatal IDE error" popup — annoying for the user and useless since the error is
     * already visible in the Monitoring tool window's Errors tab.
     *
     * Implementation: `MessagePool` / `MessagePoolListener` / `AbstractMessage` are all marked
     * `@ApiStatus.Internal` in the platform — using them directly trips the plugin verifier with
     * INTERNAL_API_USAGES. We therefore reach them through reflection + a `Proxy` for the listener.
     * The reflective shape is covered by [io.nimbly.mcpcompanion.ReflectionApiTest]; if the platform
     * ever renames any of these symbols, the test fails (or the runCatching silently skips).
     */
    private fun installMcpErrorFilter() {
        runCatching {
            val poolCls = Class.forName("com.intellij.diagnostic.MessagePool")
            val listenerCls = Class.forName("com.intellij.diagnostic.MessagePoolListener")
            val msgCls = Class.forName("com.intellij.diagnostic.AbstractMessage")

            val pool = poolCls.getMethod("getInstance").invoke(null) ?: return@runCatching
            val getFatalErrors = poolCls.getMethod("getFatalErrors", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            val addListener = poolCls.getMethod("addListener", listenerCls)

            val getMessage = msgCls.getMethod("getMessage")
            val getThrowable = msgCls.getMethod("getThrowable")
            val getThrowableText = msgCls.getMethod("getThrowableText")
            val setRead = msgCls.getMethod("setRead", Boolean::class.javaPrimitiveType)

            val handler = java.lang.reflect.InvocationHandler { _, method, _ ->
                if (method.name == "newEntryAdded") {
                    runCatching {
                        @Suppress("UNCHECKED_CAST")
                        val errors = getFatalErrors.invoke(pool, true, false) as? List<Any?> ?: emptyList()
                        for (msg in errors) {
                            if (msg == null) continue
                            val text = buildString {
                                append((getMessage.invoke(msg) as? String).orEmpty())
                                append(" ")
                                val t = getThrowable.invoke(msg) as? Throwable
                                append(t?.message.orEmpty())
                            }
                            val throwableText = (getThrowableText.invoke(msg) as? String).orEmpty()
                            if ("MCP tool call has been failed" in text ||
                                "No argument is passed for required parameter" in text ||
                                "com.intellij.mcpserver.impl.util.CallableBridge" in throwableText
                            ) {
                                setRead.invoke(msg, true)
                            }
                        }
                    }
                }
                null
            }

            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerCls.classLoader, arrayOf(listenerCls), handler,
            )
            addListener.invoke(pool, proxy)
        }
    }

    companion object {
        private val ESCAPE_LISTENER_KEY =
            com.intellij.openapi.util.Key.create<KeyAdapter>("mcp.companion.escape.listener")

        private val toolCallListenerSubscribed = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
