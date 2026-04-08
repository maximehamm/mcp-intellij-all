package io.nimbly.mcpcompanion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for McpCompanionVcsToolset.unifiedDiff().
 * Pure Kotlin logic — no IntelliJ platform context required.
 */
class VcsDiffTest {

    private val diff = McpCompanionVcsToolset()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun diff(before: String, after: String) =
        diff.unifiedDiff("before", "after", before, after)

    private fun lines(vararg l: String) = l.joinToString("\n")

    // ── Basic cases ───────────────────────────────────────────────────────────

    @Test
    fun `identical files return no changes`() {
        val content = "line1\nline2\nline3"
        assertEquals("(no changes)", diff(content, content))
    }

    @Test
    fun `single line added`() {
        val before = lines("a", "b", "c")
        val after  = lines("a", "b", "NEW", "c")
        val result = diff(before, after)
        assertTrue(result.contains("+NEW"), "Should contain added line: $result")
        assertTrue(result.contains("@@"),   "Should contain hunk header: $result")
        assertFalse(result.contains("-NEW"), "Should not mark new line as removed: $result")
    }

    @Test
    fun `single line removed`() {
        val before = lines("a", "b", "c")
        val after  = lines("a", "c")
        val result = diff(before, after)
        assertTrue(result.contains("-b"),  "Should contain removed line: $result")
        assertFalse(result.contains("+b"), "Should not mark removed line as added: $result")
    }

    @Test
    fun `single line modified`() {
        val before = lines("a", "old", "c")
        val after  = lines("a", "new", "c")
        val result = diff(before, after)
        assertTrue(result.contains("-old"), "Should show old line as removed: $result")
        assertTrue(result.contains("+new"), "Should show new line as added: $result")
    }

    @Test
    fun `context lines around change are included`() {
        val before = (1..10).joinToString("\n") { "line$it" }
        val after  = before.replace("line5", "CHANGED")
        val result = diff(before, after)
        // 3 lines of context before and after the change
        assertTrue(result.contains(" line4"), "Should include context line before: $result")
        assertTrue(result.contains(" line6"), "Should include context line after: $result")
        assertTrue(result.contains("-line5"), "Should show removed line: $result")
        assertTrue(result.contains("+CHANGED"), "Should show added line: $result")
    }

    @Test
    fun `diff header contains before and after labels`() {
        val result = diff("x\n", "y\n")
        assertTrue(result.startsWith("--- before\n+++ after\n"), "Header missing: $result")
    }

    @Test
    fun `multiple separate changes produce multiple hunks`() {
        val before = (1..20).joinToString("\n") { "line$it" }
        val after  = before.replace("line2", "CHANGE_A").replace("line18", "CHANGE_B")
        val result = diff(before, after)
        val hunkCount = result.lines().count { it.startsWith("@@") }
        assertEquals(2, hunkCount, "Expected 2 hunks for 2 separate changes: $result")
    }

    @Test
    fun `nearby changes are merged into a single hunk`() {
        val before = (1..10).joinToString("\n") { "line$it" }
        // Changes on lines 4 and 6 — within 3-line context of each other → single hunk
        val after = before.replace("line4", "CHANGE_A").replace("line6", "CHANGE_B")
        val result = diff(before, after)
        val hunkCount = result.lines().count { it.startsWith("@@") }
        assertEquals(1, hunkCount, "Expected nearby changes to merge into 1 hunk: $result")
    }

    // ── Large file path (prefix/suffix algorithm) ─────────────────────────────

    @Test
    fun `large file single line change uses prefix-suffix algorithm`() {
        // File > 800 lines — triggers the prefix/suffix fast path
        val lines = (1..1000).map { "line $it" }
        val before = lines.joinToString("\n")
        val after  = lines.toMutableList().also { it[499] = "CHANGED LINE 500" }.joinToString("\n")
        val result = diff(before, after)
        assertTrue(result.contains("-line 500"),       "Should show removed line: $result")
        assertTrue(result.contains("+CHANGED LINE 500"), "Should show added line: $result")
        assertTrue(result.contains("@@"),              "Should have hunk header: $result")
        assertFalse(result.contains("file too large"), "Should not show size error: $result")
    }

    @Test
    fun `large file identical returns no changes`() {
        val content = (1..1000).joinToString("\n") { "line $it" }
        assertEquals("(no changes)", diff(content, content))
    }

    @Test
    fun `large file version bump in pom style`() {
        // Simulate a pom.xml where only the version line changes
        val header = (1..22).joinToString("\n") { "<tag$it>value</tag$it>" }
        val footer = (24..519).joinToString("\n") { "<tag$it>value</tag$it>" }
        val before = "$header\n<version>2.13.2</version>\n$footer"
        val after  = "$header\n<version>2.13.3-SNAPSHOT</version>\n$footer"
        val result = diff(before, after)
        assertTrue(result.contains("-<version>2.13.2</version>"),          "Should show old version: $result")
        assertTrue(result.contains("+<version>2.13.3-SNAPSHOT</version>"), "Should show new version: $result")
        assertFalse(result.contains("file too large"),                      "Should not block on size: $result")
    }

    // ── Hunk limit ────────────────────────────────────────────────────────────

    @Test
    fun `hunk limit caps output at 20 hunks`() {
        // 30 isolated changes — each is far enough from the next to produce its own hunk
        val beforeLines = (1..300).map { "line $it" }.toMutableList()
        val afterLines  = beforeLines.toMutableList()
        for (i in 0 until 30) afterLines[i * 10] = "CHANGED ${i * 10 + 1}"
        val result = diff(beforeLines.joinToString("\n"), afterLines.joinToString("\n"))
        val hunkCount = result.lines().count { it.startsWith("@@") }
        assertTrue(hunkCount <= 20, "Should cap at 20 hunks, got $hunkCount: $result")
        assertTrue(result.contains("hunks limit reached"), "Should note truncation: $result")
    }

    @Test
    fun `exactly 20 hunks shows no truncation message`() {
        val beforeLines = (1..200).map { "line $it" }.toMutableList()
        val afterLines  = beforeLines.toMutableList()
        for (i in 0 until 20) afterLines[i * 10] = "CHANGED ${i * 10 + 1}"
        val result = diff(beforeLines.joinToString("\n"), afterLines.joinToString("\n"))
        val hunkCount = result.lines().count { it.startsWith("@@") }
        assertTrue(hunkCount <= 20, "Should have at most 20 hunks: $result")
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `empty before is a full addition`() {
        val result = diff("", "line1\nline2")
        assertTrue(result.contains("+line1"), "Should show added lines: $result")
        assertTrue(result.contains("+line2"), "Should show added lines: $result")
    }

    @Test
    fun `empty after is a full deletion`() {
        val result = diff("line1\nline2", "")
        assertTrue(result.contains("-line1"), "Should show removed lines: $result")
        assertTrue(result.contains("-line2"), "Should show removed lines: $result")
    }

    @Test
    fun `hunk header line counts are correct`() {
        // 10 lines, change on line 5 — context (3 lines) fits within the file
        val before = lines("a", "b", "c", "d", "e", "f", "g", "h", "i", "j")
        val after  = lines("a", "b", "c", "d", "X", "f", "g", "h", "i", "j")
        val result = diff(before, after)
        // Change on line 5 (-e, +X), 3 lines before (lines 2-4) and 3 after (lines 6-8)
        // → hunk from line 2 to line 8 = 7 lines in both before and after
        assertTrue(result.contains("@@ -2,7 +2,7 @@"), "Wrong hunk header: $result")
    }
}
