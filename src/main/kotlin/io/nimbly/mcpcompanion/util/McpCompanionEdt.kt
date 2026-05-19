package io.nimbly.mcpcompanion.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil

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
 * 1. Absolute path as-is (works for files outside the project)
 * 2. Relative to project basePath
 * 3. Absolute path that accidentally contains basePath as prefix (double-prefix bug)
 * 4. VFS refresh + retry for recently created files not yet indexed
 * 5. VfsUtil.findFileByIoFile with refresh=true — last resort for files outside the project
 *    that IntelliJ has never indexed (e.g. ~/.claude/ files, /tmp/ files)
 *
 * Returns null only when all strategies fail (file does not exist on disk).
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

    // 5. VfsUtil with refresh — handles files outside the project root that IntelliJ
    //    has never indexed (e.g. ~/.claude/*, scratch files, files in other projects)
    if (normalized.startsWith("/")) {
        val ioFile = java.io.File(normalized)
        if (ioFile.exists()) {
            VfsUtil.findFileByIoFile(ioFile, true)?.let { return it }
        }
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
        append("Hint: use an absolute path (e.g. '/Users/you/file.md') or a path relative to the project root (e.g. 'src/main/java/Foo.java'). Files outside the project are supported with absolute paths.")
    }
    return null to error
}

/**
 * Runs [action] on the EDT and waits for the result.
 *
 * Uses `ModalityState.defaultModalityState()` rather than `ModalityState.any()` — the latter
 * was historically chosen to bypass modal dialogs but turned out to poison every nested
 * write/transaction with `TransactionGuardImpl` "Write-unsafe context!" SEVERE log entries
 * (see bug report 2026-05-19). On WSL2 those SEVEREs additionally froze the EDT for 5–34 s.
 *
 * `defaultModalityState()` is write-safe: when invoked from a background thread it captures
 * the modality of the running task (NON_MODAL with no dialog open, the dialog's modality
 * otherwise), and any nested `WriteCommandAction` then runs in a transaction-safe context.
 * Trade-off: if the user has a modal dialog open we now wait for it, which is the correct
 * behaviour anyway — MCP tools shouldn't sneak writes underneath a modal Settings panel.
 */
internal fun <T> runOnEdt(action: () -> T): T {
    val app = ApplicationManager.getApplication()
    if (app.isDispatchThread) return action()
    var result: T? = null
    app.invokeAndWait({ result = action() }, ModalityState.defaultModalityState())
    @Suppress("UNCHECKED_CAST")
    return result as T
}
