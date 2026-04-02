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
    }

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    fun isEnabled(toolName: String): Boolean = myState.enabledTools.getOrDefault(toolName, true)

    fun setEnabled(toolName: String, enabled: Boolean) {
        myState.enabledTools[toolName] = enabled
    }

    companion object {

        val TOOL_GROUPS = linkedMapOf(
            "Editor & Navigation" to listOf(
                "get_open_editors", "navigate_to", "select_text", "highlight_text", "clear_highlights"
            ),
            "Build & Tests" to listOf(
                "get_build_output", "get_run_output", "get_services_output", "get_test_results"
            ),
            "Debug" to listOf(
                "debug_run_configuration", "get_debug_output", "get_debug_variables",
                "get_breakpoints", "add_conditional_breakpoint", "set_breakpoint_condition", "mute_breakpoints"
            ),
            "Diagnostic & Processes" to listOf(
                "get_intellij_diagnostic", "get_running_processes", "manage_process"
            ),
            "General" to listOf(
                "get_mcp_companion_overview", "get_project_structure", "replace_text_undoable", "delete_file"
            )
        )

        fun getInstance(): McpCompanionSettings =
            ApplicationManager.getApplication().getService(McpCompanionSettings::class.java)
    }
}
