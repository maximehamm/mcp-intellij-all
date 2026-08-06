plugins {
    // Lets Gradle locate/provision JVM toolchains. The JDK 21 already under ~/.jdks is
    // auto-detected; the resolver is the fallback if a toolchain isn't found locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "mcp-intellij-all"
