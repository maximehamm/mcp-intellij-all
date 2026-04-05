package io.nimbly.mcpcompanion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(name = "McpCompanionSettings", storages = [Storage("mcp-companion.xml")])
@Service(Service.Level.APP)
class McpCompanionSettings : PersistentStateComponent<McpCompanionSettings.State> {

    class State {
        @JvmField
        var enabledTools: MutableMap<String, Boolean> = mutableMapOf()
        @JvmField
        var firstLaunchDone: Boolean = false
        @JvmField
        var telemetryEnabled: Boolean = true   // opt-out — consent asked on first launch
        @JvmField
        var telemetryNotificationShown: Boolean = false
        @JvmField
        var anonymousId: String = java.util.UUID.randomUUID().toString()
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    fun isEnabled(toolName: String): Boolean = myState.enabledTools.getOrDefault(toolName, toolName !in DISABLED_BY_DEFAULT)

    fun setEnabled(toolName: String, enabled: Boolean) {
        myState.enabledTools[toolName] = enabled
    }

    fun isTelemetryEnabled(): Boolean = myState.telemetryEnabled
    fun setTelemetryEnabled(enabled: Boolean) { myState.telemetryEnabled = enabled }
    fun getAnonymousId(): String = myState.anonymousId
    fun isTelemetryNotificationShown(): Boolean = myState.telemetryNotificationShown
    fun setTelemetryNotificationShown(shown: Boolean) { myState.telemetryNotificationShown = shown }

    // In-memory call counts — reset on every IDE restart
    private val callCounts = mutableMapOf<String, Int>()

    fun trackCall(name: String) {
        callCounts[name] = (callCounts[name] ?: 0) + 1
        McpCompanionTelemetry.trackIfEnabled(name)
    }
    fun getCallCount(name: String): Int = callCounts[name] ?: 0
    fun getAllCallCounts(): Map<String, Int> = callCounts.toMap()
    fun maxCallCount(): Int = callCounts.values.maxOrNull() ?: 0

    companion object {

        /** Tools disabled by default — higher risk, require explicit opt-in in Settings. */
        val DISABLED_BY_DEFAULT = setOf("send_to_terminal", "delete_file")

        val TOOL_GROUPS = linkedMapOf(
            "Editor & Navigation" to listOf(
                "get_open_editors", "navigate_to", "select_text", "highlight_text", "clear_highlights"
            ),
            "Build & Tests" to listOf(
                "get_build_output", "get_console_output", "get_services_output", "get_test_results", "get_terminal_output", "send_to_terminal"
            ),
            "Debug" to listOf(
                "debug_run_configuration", "get_debug_variables",
                "get_breakpoints", "add_conditional_breakpoint", "set_breakpoint_condition", "mute_breakpoints"
            ),
            "Diagnostic & Processes" to listOf(
                "get_intellij_diagnostic", "get_running_processes", "manage_process", "get_ide_settings"
            ),
            "Code Analysis" to listOf(
                "get_file_problems", "get_quick_fixes", "refresh_project", "get_project_structure"
            ),
            "General" to listOf(
                "get_mcp_companion_overview", "execute_ide_action", "replace_text_undoable", "delete_file"
            )
        )

        fun getInstance(): McpCompanionSettings =
            ApplicationManager.getApplication().getService(McpCompanionSettings::class.java)
    }
}
