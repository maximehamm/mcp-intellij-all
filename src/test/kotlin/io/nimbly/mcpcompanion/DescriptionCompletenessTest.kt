package io.nimbly.mcpcompanion

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Anti-regression test that prevents the plugin.xml `<description>` rubric from falling behind
 * the actual tool set. Every tool listed in `McpCompanionSettings.TOOL_GROUPS` MUST be mentioned
 * in the Marketplace description (inside a `<b>tool_name</b>` or `<code>tool_name</code>` tag),
 * AND the headline tool count (`<b>NN tools …</b>`) must equal the real number of tools.
 *
 * Without this test the description silently drifts: tools get added to the code + Settings + README
 * but forgotten in the Marketplace listing (exactly the backlog discovered on 2026-06-06, when 26
 * tools were missing). The build fails here instead.
 */
class DescriptionCompletenessTest {

    private val settingsFile = File("src/main/kotlin/io/nimbly/mcpcompanion/McpCompanionSettings.kt")
    private val pluginXml = File("src/main/resources/META-INF/plugin.xml")

    /** Tool names declared in TOOL_GROUPS — the source of truth for "what tools exist". */
    private fun realTools(): Set<String> {
        val txt = settingsFile.readText()
        val block = Regex("""TOOL_GROUPS\s*=\s*linkedMapOf\((.*?)\n\s{8}\)""", RegexOption.DOT_MATCHES_ALL)
            .find(txt)?.groupValues?.get(1) ?: error("TOOL_GROUPS block not found in ${settingsFile.name}")
        // Tool names are lowercase snake_case quoted strings; group keys contain spaces/uppercase.
        return Regex("\"([a-z][a-z0-9_]+)\"").findAll(block).map { it.groupValues[1] }.toSet()
    }

    /** The text of the <description> CDATA block in plugin.xml. */
    private fun descriptionText(): String {
        val xml = pluginXml.readText()
        val desc = Regex("""<description>(.*?)</description>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: error("<description> not found in plugin.xml")
        return desc
    }

    @Test
    fun `every tool in TOOL_GROUPS is described in plugin xml`() {
        val desc = descriptionText()
        // Collect tool names mentioned inside <b>…</b> or <code>…</code> tags.
        val mentioned = Regex("""<(?:b|code)>([a-z][a-z0-9_]+)</(?:b|code)>""")
            .findAll(desc).map { it.groupValues[1] }.toSet()
        val missing = (realTools() - mentioned).sorted()
        assertTrue(
            missing.isEmpty(),
            "These tools are in TOOL_GROUPS but NOT described in plugin.xml <description>:\n" +
                missing.joinToString("\n") { "  - $it" } +
                "\n\nAdd a <li><b>$.</b> — …</li> entry for each in the matching <h4> section.",
        )
    }

    @Test
    fun `description sections mirror the Settings tool groups`() {
        val desc = descriptionText()
        // Group names that appear as <h4> sections in the description. Must include every
        // TOOL_GROUPS key (the Settings page sections), in the same order.
        val groupNames = settingsGroupNames()
        // Extract the <h4> headers, normalising HTML entities (&amp; → &).
        // Normalise only the HTML entity — section names must match the Settings group keys
        // verbatim (incl. any parenthetical like "Pull Requests (GitHub)").
        val h4 = Regex("""<h4>(.*?)</h4>""").findAll(desc)
            .map { it.groupValues[1].replace("&amp;", "&").trim() }
            .toList()
        for (g in groupNames) {
            assertTrue(g in h4, "Settings group '$g' has no matching <h4> section in plugin.xml. Found h4s: $h4")
        }
        // Order check: the Settings groups must appear in the same relative order in the description.
        val indices = groupNames.map { g -> h4.indexOf(g) }
        assertTrue(indices == indices.sorted(),
            "The <h4> sections are not in the same order as Settings TOOL_GROUPS.\n" +
                "Settings order: $groupNames\nDescription h4 order: $h4")
    }

    /** TOOL_GROUPS keys (group display names) in declaration order. */
    private fun settingsGroupNames(): List<String> {
        val txt = settingsFile.readText()
        val block = Regex("""TOOL_GROUPS\s*=\s*linkedMapOf\((.*?)\n\s{8}\)""", RegexOption.DOT_MATCHES_ALL)
            .find(txt)?.groupValues?.get(1) ?: error("TOOL_GROUPS block not found")
        return Regex(""""([^"]+)"\s*to\s*listOf""").findAll(block).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `headline tool count matches the real number of tools`() {
        val desc = descriptionText()
        val declared = Regex("""<b>(\d+) tools""").find(desc)?.groupValues?.get(1)?.toInt()
            ?: error("Headline '<b>NN tools …' not found in plugin.xml <description>")
        val real = realTools().size
        assertTrue(
            declared == real,
            "plugin.xml headline says $declared tools but TOOL_GROUPS contains $real. " +
                "Update the '<b>NN tools …' count in the description.",
        )
    }
}
