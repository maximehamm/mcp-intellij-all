plugins {
    id("java")
    kotlin("jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.5.0"
    kotlin("plugin.serialization") version "1.9.24"
}

group = "io.nimbly"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create("IC", "2024.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        plugin("com.intellij.mcpServer", "1.0.30")
    }
    // compileOnly pour éviter les conflits de classloader avec le plugin MCP
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
        }
        changeNotes = """
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
