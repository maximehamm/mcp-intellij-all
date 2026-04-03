import java.util.Properties

plugins {
    id("java")
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
    kotlin("plugin.serialization") version "2.2.0"
}

val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) load(f.inputStream())
}

group = "io.nimbly"
version = "1.10.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Users/maxime/Applications/IntelliJ IDEA 2026.1.app")
        bundledPlugin("com.intellij.mcpServer")
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
    compileOnly(files("/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/plugins/mcpserver/lib/mcpserver.jar"))
testRuntimeOnly(files("/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/lib/intellij.platform.ide.impl.jar"))
    testRuntimeOnly(files("/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/lib/intellij.platform.core.impl.jar"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
        changeNotes = """
            <ul>
                <li><b>1.10.1</b> — Marketplace overview restructured to match Settings page groups. Fix @ApiStatus.Internal violation on CoreProgressManager.</li>
                <li><b>1.10.0</b> — New tool: <code>get_intellij_diagnostic</code> — one-call diagnostic combining indexing status, active notifications, running processes, and idea.log WARN/ERROR tail.</li>
                <li><b>1.9.0</b> — New tools: <code>get_running_processes</code> (lists active/paused background processes) and <code>manage_process</code> (pause, resume, cancel). Added reflection API tests. Fixed binary compatibility with IntelliJ 2025.3.x (<code>McpToolset.isEnabled()</code>).</li>
                <li><b>1.8.0</b> — <code>get_project_structure</code>: now includes SDK homePath and all available SDKs registered in IntelliJ.</li>
                <li><b>1.7.0</b> — New tool: <code>get_project_structure</code> — returns SDK, modules, source roots (source/test/resource/testResource), excluded folders, and module dependencies.</li>
                <li><b>1.6.0</b> — Claude Code setup panel: Add to CLAUDE.md button (undoable), permissions snippet with Copy button. New get_mcp_companion_overview tool. Fix endCol inclusive in highlight_text and select_text.</li>
                <li><b>1.5.0</b> — New navigation and highlighting tools: navigate_to, select_text, highlight_text (theme-aware, Escape to clear), clear_highlights.</li>
                <li><b>1.4.0</b> — New breakpoint tools: get_breakpoints (with conditions), add_conditional_breakpoint, set_breakpoint_condition, mute_breakpoints, debug_run_configuration.</li>
                <li><b>1.3.1</b> — Fix settings page refresh: warning and grayed tools now update correctly after Apply.</li>
                <li><b>1.3.0</b> — Settings page: warning message + grayed tools when MCP Server is disabled. Auto-enable MCP Server on first launch with notification.</li>
                <li><b>1.2.1</b> — get_debug_output now works when session is finished or paused at breakpoint; severity field (ERROR/WARNING/SUCCESS) added to build/run tree nodes; all consoles per tab are now read.</li>
                <li><b>1.2.0</b> — New Settings page (Tools → MCP Server Companion) to enable/disable each tool individually.</li>
                <li><b>1.1.0</b> — New tool: <code>get_test_results</code> — returns last test run results (passed/failed/ignored status, duration, failure messages).</li>
                <li><b>1.0.1</b> — Migration to IntelliJ 2025.3+ MCP API (McpToolset).</li>
                <li><b>1.0.0</b> — Initial release. Adds 6 MCP tools: get_open_editors, get_build_output, get_run_output, get_debug_output, get_debug_variables, replace_text_undoable.</li>
            </ul>
        """.trimIndent()
    }
}

intellijPlatform {
    publishing {
        token = secrets.getProperty("marketplace.token", "")
    }
    pluginVerification {
        failureLevel = listOf(
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES
        )
        ides {
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2025.3.4")
            ide(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdeaUltimate, "2026.1")
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
        jvmArgs("-Xbootclasspath/a:/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/lib/nio-fs.jar")
    }
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    buildSearchableOptions {
        enabled = false
    }
    runIde {
        jvmArgs("-Xbootclasspath/a:/Users/maxime/Applications/IntelliJ IDEA 2026.1.app/Contents/lib/nio-fs.jar")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
