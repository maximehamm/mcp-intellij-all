package io.nimbly.mcpcompanion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState

/**
 * Executes [action] on the EDT using ModalityState.any(), so the block runs
 * immediately even when a modal dialog (Settings, Find, etc.) is open.
 *
 * Unlike invokeAndWaitIfNeeded, which uses ModalityState.NON_MODAL from
 * coroutine threads and blocks until every modal is dismissed, this helper
 * guarantees immediate execution and prevents MCP tool calls from hanging.
 */
internal fun <T> runOnEdt(action: () -> T): T {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) return action()
    var result: T? = null
    app.invokeAndWait({ result = action() }, ModalityState.any())
    @Suppress("UNCHECKED_CAST")
    return result as T
}
