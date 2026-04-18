package io.nimbly.mcpcompanion

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.net.HttpConfigurable
import java.io.OutputStreamWriter
import java.net.HttpURLConnection

object McpCompanionTelemetry {

    private val LOG = Logger.getInstance(McpCompanionTelemetry::class.java)

    private const val BASE_URL = "https://mcp-intellij-all.vercel.app"
    private const val TRACK_URL = "$BASE_URL/api/track"
    const val STATS_URL = "$BASE_URL/api/stats"

    private const val SEND_TIMEOUT_MS = 3_000
    private const val FETCH_TIMEOUT_MS = 8_000

    /** Fire-and-forget: sends a tool_used event in a daemon thread. Silent on failure. */
    fun trackIfEnabled(toolName: String, aiClient: String? = null) {
        val settings = McpCompanionSettings.getInstance()
        if (!settings.isTelemetryEnabled()) return

        val clientId   = settings.getAnonymousId()
        val version    = pluginVersion()
        val ideProduct = ideProduct()
        val ideVersion = ideVersion()
        val locale     = java.util.Locale.getDefault().toLanguageTag()
        val aiClientJson = if (aiClient != null) ""","ai_client":"$aiClient"""" else ""
        val payload    = """{"client_id":"$clientId","tool_name":"$toolName","plugin_version":"$version","ide_product":"$ideProduct","ide_version":"$ideVersion","locale":"$locale"$aiClientJson}"""

        Thread {
            try {
                val conn = openConnection(TRACK_URL)
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = SEND_TIMEOUT_MS
                conn.readTimeout    = SEND_TIMEOUT_MS
                OutputStreamWriter(conn.outputStream).use { it.write(payload) }
                val code = conn.responseCode
                conn.disconnect()
                LOG.info("Telemetry sent: $toolName → HTTP $code")
            } catch (e: Exception) {
                LOG.warn("Telemetry failed for $toolName: ${e.message}")
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Fetches aggregated stats from the server.
     * Blocking — always call from a background thread.
     * Returns null on any error.
     */
    fun fetchGlobalStats(): Map<String, Int>? {
        return try {
            val conn = openConnection(STATS_URL)
            conn.requestMethod = "GET"
            conn.connectTimeout = FETCH_TIMEOUT_MS
            conn.readTimeout    = FETCH_TIMEOUT_MS
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseJsonIntMap(body)
        } catch (_: Exception) { null }
    }

    /** Opens a connection respecting the proxy configured in IntelliJ settings. */
    private fun openConnection(url: String): HttpURLConnection =
        HttpConfigurable.getInstance().openConnection(url) as HttpURLConnection

    private fun pluginVersion(): String =
        com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId("io.nimbly.mcp-companion")
        )?.version ?: "dev"

    private fun ideProduct(): String =
        ApplicationInfo.getInstance().versionName ?: "unknown"

    private fun ideVersion(): String =
        ApplicationInfo.getInstance().fullVersion ?: "unknown"

    /** Minimal {"key": number} JSON parser — no external dependency needed. */
    private fun parseJsonIntMap(json: String): Map<String, Int> {
        val result  = mutableMapOf<String, Int>()
        val pattern = Regex(""""([^"]+)"\s*:\s*(\d+)""")
        pattern.findAll(json).forEach { m ->
            result[m.groupValues[1]] = m.groupValues[2].toIntOrNull() ?: 0
        }
        return result
    }
}
