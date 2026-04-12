package io.nimbly.mcpcompanion

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Executes [action] on the EDT using ModalityState.any(), so the block runs
 * immediately even when a modal dialog (Settings, Find, etc.) is open.
 *
 * Unlike invokeAndWaitIfNeeded, which uses ModalityState.NON_MODAL from
 * coroutine threads and blocks until every modal is dismissed, this helper
 * guarantees immediate execution and prevents MCP tool calls from hanging.
 */
/**
 * Tries to resolve [filePath] to a VirtualFile using several strategies, in order:
 * 1. Absolute path as-is
 * 2. Relative to project basePath
 * 3. Absolute path that accidentally contains basePath as prefix (double-prefix bug)
 * 4. Fallback: VFS refresh + retry for recently created files not yet indexed
 *
 * Returns null only when all strategies fail.
 */
internal fun resolveFilePath(project: Project, filePath: String): VirtualFile? {
    val lfs = LocalFileSystem.getInstance()
    val normalized = filePath.replace('\\', '/').trimEnd('/')
    val basePath = project.basePath?.trimEnd('/')

    // 1. Absolute path
    if (normalized.startsWith("/")) {
        lfs.findFileByPath(normalized)?.let { return it }
    }

    // 2. Relative to project root
    if (basePath != null) {
        lfs.findFileByPath("$basePath/$normalized")?.let { return it }
    }

    // 3. Absolute but basePath doubled (e.g. caller passed absolute when relative expected)
    if (basePath != null && normalized.startsWith(basePath)) {
        val stripped = normalized.removePrefix(basePath).trimStart('/')
        lfs.findFileByPath("$basePath/$stripped")?.let { return it }
    }

    // 4. VFS refresh fallback (handles files created after the last VFS scan)
    if (normalized.startsWith("/")) {
        lfs.refreshAndFindFileByPath(normalized)?.let { return it }
    }
    if (basePath != null) {
        lfs.refreshAndFindFileByPath("$basePath/$normalized")?.let { return it }
    }

    return null
}

/**
 * Like [resolveFilePath] but returns a descriptive error string when the file is not found,
 * listing the paths that were tried so the AI can self-correct its input.
 */
internal fun resolveFilePathOrError(project: Project, filePath: String): Pair<VirtualFile?, String?> {
    val file = resolveFilePath(project, filePath)
    if (file != null) return file to null
    val basePath = project.basePath?.trimEnd('/') ?: return null to "Project base path not found"
    val normalized = filePath.replace('\\', '/').trimEnd('/')
    val error = buildString {
        appendLine("File not found: $filePath")
        appendLine("Tried:")
        if (normalized.startsWith("/")) appendLine("  - absolute:           $normalized")
        appendLine("  - relative to root:   $basePath/$normalized")
        if (normalized.startsWith(basePath)) appendLine("  - stripped duplicate: $basePath/${normalized.removePrefix(basePath).trimStart('/')}")
        appendLine("Project root: $basePath")
        append("Hint: prefer a path relative to the project root (e.g. 'src/main/java/Foo.java')")
    }
    return null to error
}

internal fun <T> runOnEdt(action: () -> T): T {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) return action()
    var result: T? = null
    app.invokeAndWait({ result = action() }, ModalityState.any())
    @Suppress("UNCHECKED_CAST")
    return result as T
}
