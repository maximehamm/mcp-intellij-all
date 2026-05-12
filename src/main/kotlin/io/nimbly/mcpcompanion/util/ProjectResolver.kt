package io.nimbly.mcpcompanion.util

import com.intellij.mcpserver.project
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import kotlin.coroutines.coroutineContext

/**
 * Resolves the target project for a tool call.
 *
 * Resolution order:
 *  1. If [projectPath] is provided, searches [ProjectManager.openProjects] for an open project
 *     whose `basePath` matches [projectPath] (exact match, or prefix of [projectPath] followed by "/").
 *  2. If [projectPath] is blank/null **and exactly one project is open**, return that one.
 *     This sidesteps the JetBrains MCP framework's `coroutineContext.project` which throws
 *     `McpExpectedError("Unable to determine the target project for the current MCP tool call.")`
 *     even when only one project is open in the JVM — that error surprises callers who used to
 *     rely on automatic detection. See bug report 2026-05-12.
 *  3. Otherwise (0 or 2+ open projects), defer to the framework resolver — it may use call/session
 *     headers, and will throw the structured "currently open projects" error if it can't decide.
 *
 * This lets each tool deterministically target a specific project when a single IntelliJ JVM
 * hosts several open projects, while staying fully backwards compatible with callers that don't
 * pass a path in the single-project case.
 */
suspend fun resolveProject(projectPath: String? = null): Project {
    if (!projectPath.isNullOrBlank()) {
        val normalized = projectPath.trim().removeSuffix("/")
        val projects = ProjectManager.getInstance().openProjects
        projects.firstOrNull { it.basePath?.removeSuffix("/") == normalized }?.let { return it }
        projects.firstOrNull { base ->
            val bp = base.basePath?.removeSuffix("/") ?: return@firstOrNull false
            normalized.startsWith("$bp/")
        }?.let { return it }
        // Path supplied but no match — fall through to the framework so it can produce the
        // canonical error listing the open projects.
    }
    // Single-project shortcut: avoids the framework's "Unable to determine the target project"
    // when callers don't (and shouldn't have to) pass projectPath in this trivial case.
    val open = ProjectManager.getInstance().openProjects
    if (open.size == 1) return open[0]
    return coroutineContext.project
}
