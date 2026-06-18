package io.nimbly.mcpcompanion.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
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
    val normalized = filePath.replace('\\', '/').trimEnd('/')
    if (normalized.startsWith("/")) return resolveAbsolute(normalized)
    // Relative: resolve against every content root (basePath first); return the first hit.
    // When several roots match, callers that care about ambiguity use [resolveFilePathOrError].
    return resolveRelativeCandidates(project, normalized).firstOrNull()
}

/** Absolute path — works for any module / content root, even files outside the project. */
private fun resolveAbsolute(absolute: String): VirtualFile? {
    val lfs = LocalFileSystem.getInstance()
    lfs.findFileByPath(absolute)?.let { return it }
    lfs.refreshAndFindFileByPath(absolute)?.let { return it }
    // Last resort: a real file IntelliJ has never indexed (~/.claude/*, /tmp/*, other projects).
    val ioFile = java.io.File(absolute)
    if (ioFile.exists()) VfsUtil.findFileByIoFile(ioFile, true)?.let { return it }
    return null
}

/**
 * The absolute base directories a relative path may be anchored to: the project `basePath`
 * plus every content root (so files in a *second module / attached content root* resolve too),
 * de-duplicated, basePath first.
 */
private fun rootBases(project: Project): List<String> {
    val bases = LinkedHashSet<String>()
    project.basePath?.trimEnd('/')?.let { bases.add(it) }
    runCatching {
        runReadAction { ProjectRootManager.getInstance(project).contentRoots.toList() }
            .forEach { bases.add(it.path.trimEnd('/')) }
    }
    return bases.toList()
}

/** Distinct files a relative [normalized] path resolves to across all [rootBases]. */
private fun resolveRelativeCandidates(project: Project, normalized: String): List<VirtualFile> {
    val lfs = LocalFileSystem.getInstance()
    val byPath = LinkedHashMap<String, VirtualFile>()
    for (base in rootBases(project)) {
        val full = "$base/$normalized"
        (lfs.findFileByPath(full) ?: lfs.refreshAndFindFileByPath(full))?.let { byPath[it.path] = it }
    }
    return byPath.values.toList()
}

/**
 * Like [resolveFilePath] but returns a descriptive error string when the file is not found OR is
 * ambiguous. With several content roots (multi-module project, or several attached projects in
 * one JVM) a single relative path can match more than one root — in that case we refuse to guess
 * and list the absolute candidates so the AI can re-issue the call with an unambiguous absolute path.
 */
internal fun resolveFilePathOrError(project: Project, filePath: String): Pair<VirtualFile?, String?> {
    val normalized = filePath.replace('\\', '/').trimEnd('/')

    if (normalized.startsWith("/")) {
        val file = resolveAbsolute(normalized)
        return if (file != null) file to null
        else null to "File not found: $filePath\nTried absolute path: $normalized\n" +
            "Hint: check the path, or pass a path relative to a project content root."
    }

    val candidates = resolveRelativeCandidates(project, normalized)
    return when (candidates.size) {
        1 -> candidates[0] to null
        0 -> {
            val bases = rootBases(project)
            val error = buildString {
                appendLine("File not found: $filePath")
                appendLine("Tried this relative path under each content root:")
                bases.forEach { appendLine("  - $it/$normalized") }
                if (bases.isEmpty()) appendLine("  (project has no content root / base path)")
                append("Hint: use an absolute path (e.g. '/Users/you/proj/module/src/Foo.java'), ")
                append("or a path relative to one of the content roots above.")
            }
            null to error
        }
        else -> {
            val error = buildString {
                appendLine("Ambiguous path '$filePath' — it matches a file under several content roots:")
                candidates.forEach { appendLine("  - ${it.path}") }
                append("Re-issue the call with the absolute path of the one you mean.")
            }
            null to error
        }
    }
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
