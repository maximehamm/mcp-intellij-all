package io.nimbly.mcpcompanion.lifecycle

import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.McpToolSideEffectEvent
import com.intellij.mcpserver.ToolCallListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import io.nimbly.mcpcompanion.McpCompanionSettings

/**
 * Application-level listener for every MCP tool invocation.
 *
 * Subscribes to [ToolCallListener.Companion.TOPIC] (a [com.intellij.util.messages.Topic]) so it
 * receives notifications for ALL MCP tool calls in the IDE — including:
 *  - tools defined by this plugin (mark `isOwnTool=true` in [McpCompanionSettings.CallRecord])
 *  - tools defined by JetBrains' built-in toolsets (`isOwnTool=false`)
 *  - tools defined by other third-party MCP plugins (`isOwnTool=false`)
 *
 * For each call we record: `callId`, tool name, raw JSON arguments, client info, status, error.
 *
 * Note: the platform's `afterMcpToolCall` does not expose the return value of the suspend tool
 * function, so the response payload is not captured here — this is a limitation of the current
 * IntelliJ MCP API. We display "(not captured)" in the UI for now.
 */
class McpCompanionToolCallListener : ToolCallListener {

    private val prettyJson = Json { prettyPrint = true }

    override fun beforeMcpToolCall(descriptor: McpToolDescriptor, callInfo: McpCallInfo) {
        runCatching {
            val params = runCatching { prettyJson.encodeToString(JsonObject.serializer(), callInfo.rawArguments) }.getOrElse { "{}" }
            // If the call carries an explicit `projectPath` argument, honor it — the framework's
            // `callInfo.project` is just the default chosen when several projects are open in the
            // same JVM, so it can mislabel the record. Falls back to the framework project.
            val argProjectPath = runCatching {
                val v = callInfo.rawArguments["projectPath"]
                (v as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            }.getOrNull()?.trim()?.removeSuffix("/")?.takeIf { it.isNotEmpty() }
            val effectiveProjectPath = argProjectPath
                ?: runCatching { callInfo.project?.basePath?.removeSuffix("/") }.getOrNull()
            McpCompanionSettings.getInstance().recordCallStart(
                callId = callInfo.callId,
                toolName = descriptor.name,
                parametersJson = params,
                client = callInfo.clientInfo?.name?.takeIf { it != "Unknown MCP client" },
                projectPath = effectiveProjectPath,
            )
        }
    }

    override fun afterMcpToolCall(
        descriptor: McpToolDescriptor,
        sideEffectEvents: List<McpToolSideEffectEvent>,
        throwable: Throwable?,
        callInfo: McpCallInfo,
    ) {
        runCatching {
            McpCompanionSettings.getInstance().recordCallEnd(
                callId = callInfo.callId,
                throwable = throwable,
            )
        }
    }
}
