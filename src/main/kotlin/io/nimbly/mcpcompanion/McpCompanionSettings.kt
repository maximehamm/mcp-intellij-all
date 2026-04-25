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

    fun isEnabled(toolName: String): Boolean =
        myState.enabledTools.getOrDefault(toolName, toolName !in DISABLED_BY_DEFAULT)

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

    // Tools from ONCE_PER_SESSION_TOOLS already sent to telemetry this session
    private val telemetrySentOnce = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun trackCall(name: String, aiClient: String? = null) {
        callCounts[name] = (callCounts[name] ?: 0) + 1
        if (name in ONCE_PER_SESSION_TOOLS) {
            // High-frequency tools (e.g. called by a Claude Code UserPromptSubmit hook on every prompt)
            // — send to the analytics backend only on first call per IDE session to avoid flooding.
            if (!telemetrySentOnce.add(name)) return
        }
        McpCompanionTelemetry.trackIfEnabled(name, aiClient)
    }
    fun getCallCount(name: String): Int = callCounts[name] ?: 0
    fun getAllCallCounts(): Map<String, Int> = callCounts.toMap()
    fun maxCallCount(): Int = callCounts.values.maxOrNull() ?: 0

    companion object {

        /** Tools disabled by default — higher risk, require explicit opt-in in Settings. */
        val DISABLED_BY_DEFAULT = setOf("send_to_terminal", "delete_file", "execute_database_query", "vcs_delete_branch")

        /**
         * Tools polled at high frequency (e.g. called by a Claude Code UserPromptSubmit hook on every
         * user prompt). Local call counts still increment, but analytics events are sent only once
         * per IDE session to keep the backend light.
         */
        val ONCE_PER_SESSION_TOOLS = setOf("get_ide_snapshot")

        val TOOL_GROUPS = linkedMapOf(
            "Editor & Navigation" to listOf(
                "get_open_editors", "navigate_to", "select_text", "highlight_text", "clear_highlights", "show_diff"
            ),
            "Build & Tests" to listOf(
                "get_build_output", "get_console_output", "get_services_output", "get_test_results", "get_terminal_output", "send_to_terminal"
            ),
            "Debug" to listOf(
                "list_run_configurations", "start_run_configuration", "modify_run_configuration",
                "get_run_configuration_xml", "create_run_configuration_from_xml",
                "debug_run_configuration", "get_debug_variables",
                "get_breakpoints", "add_conditional_breakpoint", "set_breakpoint_condition", "mute_breakpoints"
            ),
            "Diagnostic & Processes" to listOf(
                "get_ide_snapshot", "get_intellij_diagnostic", "get_running_processes", "manage_process", "get_ide_settings"
            ),
            "Code Analysis" to listOf(
                "get_file_problems", "get_quick_fixes", "apply_quick_fix",
                "list_inspections", "run_inspections",
                "refresh_project", "get_project_structure"
            ),
            "Database" to listOf(
                "list_database_sources", "get_database_schema", "execute_database_query"
            ),
            "Gradle" to listOf(
                "run_gradle_task", "get_gradle_tasks", "refresh_gradle_project",
                "get_gradle_dependencies", "stop_gradle_task", "get_gradle_project_info"
            ),
            "VCS" to listOf(
                "get_vcs_changes", "get_vcs_branch", "get_vcs_log", "get_vcs_blame", "get_local_history",
                "get_vcs_file_history", "get_vcs_diff_between_branches", "vcs_show_commit",
                "vcs_stage_files", "vcs_commit", "vcs_push", "vcs_pull", "vcs_stash",
                "vcs_create_branch", "vcs_checkout_branch", "vcs_delete_branch",
                "vcs_fetch", "vcs_merge_branch", "vcs_rebase", "get_vcs_conflicts", "vcs_open_merge_tool",
                "vcs_reset", "vcs_revert", "vcs_cherry_pick"
            ),
            "General" to listOf(
                "get_mcp_companion_overview", "execute_ide_action", "replace_text_undoable", "delete_file"
            )
        )

        /**
         * Maps tool names to the IntelliJ plugin ID required for the tool to work.
         * Used by the Settings UI to grey out tools when their required plugin is not installed.
         */
        val TOOL_REQUIRED_PLUGIN: Map<String, String> = mapOf(
            "list_database_sources"     to "com.intellij.database",
            "get_database_schema"       to "com.intellij.database",
            "execute_database_query"    to "com.intellij.database",
            "run_gradle_task"           to "com.intellij.gradle",
            "get_gradle_tasks"          to "com.intellij.gradle",
            "refresh_gradle_project"    to "com.intellij.gradle",
            "get_gradle_dependencies"   to "com.intellij.gradle",
            "stop_gradle_task"          to "com.intellij.gradle",
            "get_gradle_project_info"   to "com.intellij.gradle"
        )

        /** Returns true if the optional plugin required by this tool is installed and enabled. */
        fun isPluginAvailable(toolName: String): Boolean {
            val pluginId = TOOL_REQUIRED_PLUGIN[toolName] ?: return true
            return try {
                val id = com.intellij.openapi.extensions.PluginId.getId(pluginId)
                com.intellij.ide.plugins.PluginManagerCore.getPlugin(id)?.pluginClassLoader != null
            } catch (_: Exception) { false }
        }

        fun getInstance(): McpCompanionSettings =
            ApplicationManager.getApplication().getService(McpCompanionSettings::class.java)

    }
}
