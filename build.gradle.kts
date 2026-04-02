plugins {
    id("java")
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "io.nimbly"
version = "1.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("/Users/maxime/Applications/IntelliJ IDEA 2025.3.3.app")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        bundledPlugin("com.intellij.mcpServer")
    }
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    compileOnly(files("/Users/maxime/Applications/IntelliJ IDEA 2025.3.3.app/Contents/plugins/mcpserver/lib/mcpserver.jar"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
        }
        changeNotes = """
            1.0.1 — Migration to IntelliJ 2025.3+ MCP API (McpToolset).
            1.0.0 — Initial release.
            Adds 6 MCP tools: get_open_editors, get_build_output, get_run_output,
            get_debug_output, get_debug_variables, replace_text_undoable.
        """.trimIndent()
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    buildSearchableOptions {
        enabled = false
    }
    runIde {
        jvmArgs("-Xbootclasspath/a:/Users/maxime/Applications/IntelliJ IDEA 2025.3.3.app/Contents/lib/nio-fs.jar")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
