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
        val ALL_TOOLS = listOf(
            "get_open_editors"     to "Returns all open files, focused editor, caret position and selection",
            "get_build_output"     to "Returns the Build tool window: error tree + console output",
            "get_run_output"       to "Returns the console output from the Run tool window",
            "get_debug_output"     to "Returns the console output from the Debug tool window",
            "get_debug_variables"  to "Returns local variables from the current debugger stack frame",
            "get_test_results"     to "Returns last test run results: status, duration, failure messages",
            "replace_text_undoable" to "Replaces text in a file, undoable with Cmd+Z"
        )

        fun getInstance(): McpCompanionSettings =
            ApplicationManager.getApplication().getService(McpCompanionSettings::class.java)
    }
}
