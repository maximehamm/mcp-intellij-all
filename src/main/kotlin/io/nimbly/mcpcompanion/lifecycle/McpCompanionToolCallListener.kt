package io.nimbly.mcpcompanion.lifecycle

import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpToolDescriptor
import com.intellij.mcpserver.ToolCallListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import io.nimbly.mcpcompanion.McpCompanionSettings
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

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
 * Why a [Proxy] instead of `class … : ToolCallListener`: the signature of `afterMcpToolCall` is not
 * stable across the IDE builds we support. Up to 2026.2 (262) it is
 * `(descriptor, sideEffectEvents, throwable, callInfo)`; 2026.3 (263) replaced it with
 * `(descriptor, sideEffectEvents, throwable, callInfo, result: McpToolCallResult?)` and dropped the
 * old one. A compiled `override` of either signature silently stops being called on the other
 * build (the interface default runs instead), leaving every call "running" in the Monitoring
 * window. The proxy matches the method by name and picks its arguments by type, so the same
 * bytecode works on 253 → 263 and compiles against any of them. Every other listener method falls
 * through to the interface's own default implementation.
 */
class McpCompanionToolCallListener private constructor() {

    private val prettyJson = Json { prettyPrint = true }

    fun beforeMcpToolCall(descriptor: McpToolDescriptor, callInfo: McpCallInfo) {
        runCatching {
            val params = runCatching { prettyJson.encodeToString(JsonObject.serializer(), callInfo.rawArguments) }.getOrElse { "{}" }
            // Record `projectPath` ONLY when the call carries it as an explicit argument — that way
            // the per-project Monitoring filter can show project-scoped calls in their owning window
            // and global calls (no `projectPath` arg) in every window. We deliberately don't fall
            // back to `callInfo.project?.basePath`: the framework picks an arbitrary default when
            // several projects are open, which would mis-attribute global calls.
            val argProjectPath = runCatching {
                val v = callInfo.rawArguments["projectPath"]
                (v as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            }.getOrNull()?.trim()?.removeSuffix("/")?.takeIf { it.isNotEmpty() }
            McpCompanionSettings.getInstance().recordCallStart(
                callId = callInfo.callId,
                toolName = descriptor.name,
                parametersJson = params,
                client = callInfo.clientInfo?.name?.takeIf { it != "Unknown MCP client" },
                projectPath = argProjectPath,
            )
        }
    }

    fun afterMcpToolCall(throwable: Throwable?, callInfo: McpCallInfo) {
        runCatching {
            McpCompanionSettings.getInstance().recordCallEnd(
                callId = callInfo.callId,
                throwable = throwable,
            )
        }
    }

    companion object {
        /** The [ToolCallListener] to subscribe: a proxy dispatching to a new [McpCompanionToolCallListener]. */
        fun create(): ToolCallListener {
            val target = McpCompanionToolCallListener()
            val handler = InvocationHandler { proxy, method, args ->
                val a = args ?: emptyArray()
                fun <T> arg(type: Class<T>): T? = a.firstOrNull { type.isInstance(it) }?.let(type::cast)
                when {
                    method.declaringClass == Any::class.java -> when (method.name) {
                        "equals" -> proxy === a.firstOrNull()
                        "hashCode" -> System.identityHashCode(proxy)
                        else -> "McpCompanionToolCallListener"
                    }
                    method.name == "beforeMcpToolCall" -> {
                        val d = arg(McpToolDescriptor::class.java)
                        val c = arg(McpCallInfo::class.java)
                        if (d != null && c != null) target.beforeMcpToolCall(d, c)
                        null
                    }
                    method.name == "afterMcpToolCall" -> {
                        arg(McpCallInfo::class.java)?.let { target.afterMcpToolCall(arg(Throwable::class.java), it) }
                        null
                    }
                    method.isDefault -> InvocationHandler.invokeDefault(proxy, method, *a)
                    else -> null
                }
            }
            return Proxy.newProxyInstance(
                McpCompanionToolCallListener::class.java.classLoader,
                arrayOf(ToolCallListener::class.java),
                handler,
            ) as ToolCallListener
        }
    }
}
