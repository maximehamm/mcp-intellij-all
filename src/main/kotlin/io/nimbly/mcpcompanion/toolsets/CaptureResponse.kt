package io.nimbly.mcpcompanion.toolsets

import com.intellij.mcpserver.mcpCallInfoOrNull
import io.nimbly.mcpcompanion.util.CallPayloadStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.coroutineContext

private val prettyJson = Json { prettyPrint = true }

/**
 * Returns [s] pretty-printed if it's a valid JSON value (object, array, string, number, …);
 * otherwise returns [s] unchanged. Used so that the Response tab in the Monitoring window
 * shows readable JSON for tools that return [Json.encodeToString], while still working for
 * tools that return plain text like "Navigated to foo.kts:10:1".
 */
private fun maybePretty(s: String): String {
    val trimmed = s.trim()
    if (trimmed.isEmpty()) return s
    val first = trimmed.first()
    // Quick heuristic: only attempt JSON parse for likely candidates.
    if (first != '{' && first != '[') return s
    return runCatching { prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(trimmed)) }
        .getOrElse { s }
}

/**
 * Pass-through helper that captures a tool's return value to the on-disk payload store
 * (so the Monitoring tool window can show the full untruncated response).
 *
 * Usage in every `@McpTool suspend fun` body:
 *
 * ```kotlin
 * @McpTool(name = "foo")
 * suspend fun foo(...): String {
 *     disabledMessage("foo")?.let { return captureResponse(it) }
 *     val result = compute()
 *     return captureResponse(result)
 * }
 * ```
 *
 * The unit test [io.nimbly.mcpcompanion.CaptureResponseLintTest] enforces that **every**
 * `return` statement inside an `@McpTool` function is wrapped in `captureResponse(...)` —
 * fails the build if a future tool forgets the pattern.
 */
internal suspend fun captureResponse(value: String): String {
    val callId = runCatching { coroutineContext.mcpCallInfoOrNull?.callId }.getOrNull()
    if (callId != null) {
        // Pretty-print JSON responses for the Monitoring tool window, leave plain text untouched.
        CallPayloadStorage.saveResponse(callId, maybePretty(value))
    }
    return value
}
