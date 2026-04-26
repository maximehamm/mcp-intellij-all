package io.nimbly.mcpcompanion

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Static-analysis unit test that prevents future tools from forgetting to wrap their `return`
 * statements with `captureResponse(...)`.
 *
 * Without this test, a freshly-added `@McpTool` would silently fail to populate the Response
 * tab in the Monitoring tool window — and the bug would only surface when somebody noticed the
 * empty tab. This test fails the build instead.
 *
 * Scope: every `return <expr>` line inside the body of an `@McpTool` annotated `suspend fun`
 * must be either:
 *  - wrapped: `return captureResponse(<expr>)`
 *  - a labeled return: `return@xxx <expr>`  (these are non-local returns from lambdas, not tool returns)
 *  - inside a NESTED helper function (private fun foo() { return ... }) — those bodies are skipped
 */
class CaptureResponseLintTest {

    @Test
    fun `every @McpTool return is wrapped with captureResponse`() {
        val toolsetsDir = File("src/main/kotlin/io/nimbly/mcpcompanion/toolsets")
        assertTrue(toolsetsDir.isDirectory, "Toolsets directory not found: $toolsetsDir")

        val violations = mutableListOf<String>()

        toolsetsDir.listFiles { f -> f.name.matches(Regex("McpCompanion.*Toolset\\.kt")) }
            ?.forEach { file ->
                val text = file.readText()
                for ((funcName, body) in extractMcpToolFunctionBodies(text)) {
                    for ((lineOffset, line) in body.withIndex()) {
                        val trimmed = line.trimStart()
                        if (!trimmed.startsWith("return ")) continue
                        val rest = trimmed.removePrefix("return ").trim()
                        if (rest.startsWith("@")) continue                  // labeled return
                        if (rest == "null") continue                         // helper signature
                        if (rest.startsWith("captureResponse(")) continue    // already wrapped
                        violations.add("${file.name}: $funcName(): line ${lineOffset + 1}: $trimmed")
                    }
                }
            }

        assertTrue(violations.isEmpty()) {
            buildString {
                appendLine("Found ${violations.size} unwrapped return(s) inside @McpTool functions.")
                appendLine("Each `return X` must be `return captureResponse(X)` so the response is")
                appendLine("persisted to disk and visible in the Monitoring tool window.")
                appendLine()
                violations.forEach { appendLine("  ✗ $it") }
            }
        }
    }

    /**
     * Returns pairs of (functionName, bodyLines) for every `@McpTool`-annotated function in [text].
     * Body lines exclude lines that belong to NESTED function definitions (those have their own
     * scope — wrapping their returns would be incorrect).
     */
    private fun extractMcpToolFunctionBodies(text: String): List<Pair<String, List<String>>> {
        val lines = text.lines()
        val result = mutableListOf<Pair<String, List<String>>>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].contains("@McpTool(")) { i++; continue }
            // Find `suspend fun NAME(` on a subsequent line
            var j = i
            while (j < lines.size && !lines[j].contains("suspend fun ")) j++
            if (j >= lines.size) break
            val nameMatch = Regex("""suspend fun (\w+)\s*\(""").find(lines[j])
            val funcName = nameMatch?.groupValues?.get(1) ?: "<unknown>"
            // Find opening brace of body
            var k = j
            while (k < lines.size && !lines[k].contains("{")) k++
            if (k >= lines.size) break
            // Collect body, excluding nested function bodies. Track depth to know when we're
            // inside a nested fun.
            val bodyStart = k + 1
            var depth = 0
            var nestedFunDepth: Int? = null
            val collected = mutableListOf<String>()
            for (line in bodyStart..(lines.size - 1)) {
                val l = lines[line]
                // Update depth char-by-char so nested fun tracking is precise
                for (c in l) {
                    if (c == '{') {
                        depth++
                        // If we're already inside a nested fun, just deepen
                    } else if (c == '}') {
                        depth--
                        if (nestedFunDepth != null && depth < nestedFunDepth!!) nestedFunDepth = null
                        if (depth < 0) {
                            result.add(funcName to collected.toList())
                            i = line + 1
                            break
                        }
                    }
                }
                if (depth < 0) break
                // Detect nested fun declaration on this line
                val nestedFun = Regex("""\bfun \w+\s*\(""").containsMatchIn(l)
                if (nestedFun && nestedFunDepth == null) {
                    nestedFunDepth = depth  // mark: when depth goes back below this, we exit nested
                    // Don't include this line in the body either
                    continue
                }
                if (nestedFunDepth == null) {
                    collected.add(l)
                }
            }
            if (depth >= 0) i++  // safety
        }
        return result
    }
}
