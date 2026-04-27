package io.nimbly.mcpcompanion.util

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * On-disk storage for the per-call **Parameters** and **Response** payloads displayed in the
 * MCP Companion Monitoring tool window.
 *
 * Why disk and not memory:
 *  - Parameters and responses can be large (whole files, query results, …); keeping all of them
 *    in RAM bloats the heap.
 *  - Keeping them on disk means the in-memory `CallRecord` only carries small metadata, so the
 *    upper list in the monitoring tool window stays instantly responsive.
 *
 * Why session-only:
 *  - Parameters can contain sensitive data (file paths, source code, queries). Keeping a record
 *    across restarts requires explicit user consent — out of scope for this iteration.
 *  - The directory is **wiped on first use after every IDE startup** so each session starts fresh.
 *
 * Layout: `<PathManager.systemPath>/mcp-companion-calls/<callId>.json` —
 * one JSON file per call: `{ "parameters": "…", "response": "…" }`.
 */
object CallPayloadStorage {

    private val LOG = Logger.getInstance(CallPayloadStorage::class.java)

    private val dir: Path by lazy { PathManager.getSystemDir().resolve("mcp-companion-calls") }
    private val wiped = AtomicBoolean(false)

    @Serializable
    data class Payload(val parameters: String, val response: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /** Wipes the storage directory once per IDE startup; subsequent calls are no-ops. */
    private fun ensureWipedAndExists() {
        if (wiped.compareAndSet(false, true)) {
            runCatching {
                if (dir.exists()) {
                    Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() }
                }
                Files.createDirectories(dir)
            }.onFailure { LOG.warn("Failed to wipe mcp-companion-calls dir at $dir", it) }
        } else {
            // Defensive: a concurrent call may have wiped already; just make sure dir exists.
            runCatching { Files.createDirectories(dir) }
        }
    }

    /**
     * Saves the [parameters] (and optionally [response]) for [callId].
     * Called by [io.nimbly.mcpcompanion.McpCompanionSettings.recordCallStart] on every tool call.
     */
    fun save(callId: Int, parameters: String, response: String? = null) {
        ensureWipedAndExists()
        runCatching {
            val file = dir.resolve("$callId.json").toFile()
            file.writeText(json.encodeToString(Payload.serializer(), Payload(parameters, response)))
        }.onFailure { LOG.warn("Failed to save payload for callId=$callId", it) }
    }

    /** Loads the payload for [callId] (returns null if not on disk or unreadable). */
    fun load(callId: Int): Payload? = runCatching {
        val file = dir.resolve("$callId.json").toFile()
        if (!file.exists()) return@runCatching null
        json.decodeFromString(Payload.serializer(), file.readText())
    }.onFailure { LOG.warn("Failed to read payload for callId=$callId", it) }.getOrNull()

    /** Updates only the response of an existing entry (used when we eventually capture it). */
    fun saveResponse(callId: Int, response: String) {
        val existing = load(callId)
        save(callId, parameters = existing?.parameters ?: "", response = response)
    }

    /** Removes every payload file from the directory (used by the "Clear" button). */
    fun clear() {
        ensureWipedAndExists()
        runCatching {
            Files.list(dir).use { stream ->
                stream.forEach { it.deleteIfExists() }
            }
        }
    }
}
