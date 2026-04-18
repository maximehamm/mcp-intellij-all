package io.nimbly.mcpcompanion

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.clientInfo
import com.intellij.mcpserver.project
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import kotlin.coroutines.coroutineContext

class McpCompanionVcsToolset : McpToolset {

    override fun isEnabled(): Boolean = true

    private suspend fun disabledMessage(toolName: String): String? {
        if (!McpCompanionSettings.getInstance().isEnabled(toolName)) {
            return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion."
        }
        McpCompanionSettings.getInstance().trackCall(toolName, runCatching { coroutineContext.clientInfo?.name }.getOrNull())
        return null
    }

    // ── get_vcs_changes ───────────────────────────────────────────────────────

    @McpTool(name = "get_vcs_changes")
    @McpDescription(description = """
        Returns all locally modified, added, deleted, and moved files as tracked by IntelliJ's VCS.
        Works with Git, SVN, and any other VCS configured in IntelliJ.
        Also returns unversioned (new untracked) files.

        Parameters:
        - includeDiff: if true, include a unified diff for each changed file (default: false)
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_vcs_changes(includeDiff: Boolean = false, projectPath: String? = null): String {
        disabledMessage("get_vcs_changes")?.let { return it }
        val project = resolveProject(projectPath)
        return withContext(Dispatchers.IO) {
            try {
                val clm = ChangeListManager.getInstance(project)
                val allChanges = clm.allChanges
                val unversioned = clm.unversionedFilesPaths

                if (allChanges.isEmpty() && unversioned.isEmpty())
                    return@withContext "No local changes — working tree is clean."

                val base = project.basePath ?: ""

                val changeList = allChanges.map { change ->
                    val type = change.type.name  // MODIFICATION, NEW, DELETED, MOVED
                    val beforePath = change.beforeRevision?.file?.path?.relativeTo(base)
                    val afterPath  = change.afterRevision?.file?.path?.relativeTo(base)
                    val path       = afterPath ?: beforePath ?: "?"
                    val listName   = clm.getChangeList(change)?.name ?: "Default"

                    val diff = if (includeDiff) buildDiff(change, beforePath, afterPath) else null

                    VcsChange(
                        path       = path,
                        type       = type,
                        changeList = listName,
                        movedFrom  = if (change.type == Change.Type.MOVED) beforePath else null,
                        diff       = diff
                    )
                }

                val unversionedList = unversioned.map { it.path.relativeTo(base) }

                Json.encodeToString(VcsChanges(changes = changeList, unversioned = unversionedList))
            } catch (e: Exception) {
                "Error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── get_vcs_branch ────────────────────────────────────────────────────────

    @McpTool(name = "get_vcs_branch")
    @McpDescription(description = """
        Returns the current branch and all local/remote branches for each Git repository in the project.
        Requires the Git plugin (bundled in all IntelliJ IDEA editions).

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_vcs_branch(projectPath: String? = null): String {
        disabledMessage("get_vcs_branch")?.let { return it }
        val project = resolveProject(projectPath)
        return withContext(Dispatchers.IO) {
            try {
                val cl = git4ideaLoader() ?: return@withContext "Git plugin (Git4Idea) not available."
                val base = project.basePath ?: ""

                val mgr   = cl.loadClass("git4idea.repo.GitRepositoryManager")
                val repos = mgr.getMethod("getInstance",
                    com.intellij.openapi.project.Project::class.java)
                    .invoke(null, project)
                    .let { mgr.getMethod("getRepositories").invoke(it) }
                    .let { @Suppress("UNCHECKED_CAST") it as? List<Any> } ?: emptyList()

                if (repos.isEmpty()) return@withContext "No Git repositories found in this project."

                val result = repos.map { repo ->
                    val root = runCatching {
                        (invoke(repo, "getRoot") as? com.intellij.openapi.vfs.VirtualFile)
                            ?.path?.relativeTo(base) ?: "."
                    }.getOrDefault(".")

                    val currentBranch = runCatching {
                        invoke(repo, "getCurrentBranch")?.let { invoke(it, "getName")?.toString() }
                    }.getOrNull()

                    val branches = runCatching { invoke(repo, "getBranches") }.getOrNull()

                    @Suppress("UNCHECKED_CAST")
                    val local = runCatching {
                        (invoke(branches, "getLocalBranches") as? Collection<Any>)
                            ?.mapNotNull { invoke(it, "getName")?.toString() }?.sorted() ?: emptyList()
                    }.getOrDefault(emptyList())

                    @Suppress("UNCHECKED_CAST")
                    val remote = runCatching {
                        (invoke(branches, "getRemoteBranches") as? Collection<Any>)
                            ?.mapNotNull { invoke(it, "getName")?.toString() }?.sorted() ?: emptyList()
                    }.getOrDefault(emptyList())

                    VcsBranchInfo(
                        repo           = root,
                        currentBranch  = currentBranch,
                        localBranches  = local,
                        remoteBranches = remote
                    )
                }
                Json.encodeToString(result)
            } catch (e: Exception) {
                "Error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── get_vcs_log ───────────────────────────────────────────────────────────

    @McpTool(name = "get_vcs_log")
    @McpDescription(description = """
        Returns recent commit history for all Git repositories in the project.
        Requires the Git plugin (bundled in all IntelliJ IDEA editions).

        Parameters:
        - maxCount: maximum number of commits to return per repository (default: 20)
        - file: optional file path (relative to project root) — restricts log to commits touching that file
        - branch: optional branch name to read the log from (default: current branch)
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_vcs_log(maxCount: Int = 20, file: String = "", branch: String = "", projectPath: String? = null): String {
        disabledMessage("get_vcs_log")?.let { return it }
        val project = resolveProject(projectPath)
        return withContext(Dispatchers.IO) {
            try {
                val cl = git4ideaLoader() ?: return@withContext "Git plugin (Git4Idea) not available."
                val base = project.basePath ?: ""

                val mgr   = cl.loadClass("git4idea.repo.GitRepositoryManager")
                val repos = mgr.getMethod("getInstance",
                    com.intellij.openapi.project.Project::class.java)
                    .invoke(null, project)
                    .let { mgr.getMethod("getRepositories").invoke(it) }
                    .let { @Suppress("UNCHECKED_CAST") it as? List<Any> } ?: emptyList()

                if (repos.isEmpty()) return@withContext "No Git repositories found."

                val histCls = cl.loadClass("git4idea.history.GitHistoryUtils")
                val histMethod = histCls.getMethod("history",
                    com.intellij.openapi.project.Project::class.java,
                    com.intellij.openapi.vfs.VirtualFile::class.java,
                    Array<String>::class.java)

                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

                val result = repos.mapNotNull { repo ->
                    val root = runCatching {
                        invoke(repo, "getRoot") as? com.intellij.openapi.vfs.VirtualFile
                    }.getOrNull() ?: return@mapNotNull null

                    val rootStr = root.path.relativeTo(base).ifBlank { "." }

                    val params = buildList {
                        add("--max-count=$maxCount")
                        if (branch.isNotBlank()) add(branch)
                        if (file.isNotBlank()) { add("--"); add(file) }
                    }.toTypedArray()

                    @Suppress("UNCHECKED_CAST")
                    val commits = runCatching {
                        histMethod.invoke(null, project, root, params) as? List<Any>
                    }.getOrNull() ?: emptyList()

                    val commitList = commits.map { c ->
                        val hash = runCatching {
                            invoke(c, "getId")?.let { invoke(it, "asString")?.toString() }?.take(12)
                        }.getOrDefault("?") ?: "?"

                        val authorObj = runCatching { invoke(c, "getAuthor") }.getOrNull()
                        val author      = runCatching { invoke(authorObj, "getName")?.toString() }.getOrDefault("?") ?: "?"
                        val authorEmail = runCatching { invoke(authorObj, "getEmail")?.toString() }.getOrDefault("") ?: ""

                        val authorTime = runCatching { invoke(c, "getAuthorTime") as? Long }.getOrNull()
                        val subject    = runCatching { invoke(c, "getSubject")?.toString() }.getOrDefault("?") ?: "?"
                        val full       = runCatching { invoke(c, "getFullMessage")?.toString()?.trim()?.takeIf { it != subject } }.getOrNull()

                        @Suppress("UNCHECKED_CAST")
                        val files = runCatching {
                            (invoke(c, "getAffectedPaths") as? Set<*>)
                                ?.mapNotNull { fp -> runCatching { fp?.javaClass?.getMethod("getPath")?.invoke(fp)?.toString()?.relativeTo(base) }.getOrNull() }
                                ?.take(30)
                        }.getOrNull()

                        VcsCommit(
                            hash        = hash,
                            author      = author,
                            authorEmail = authorEmail,
                            date        = authorTime?.let { fmt.format(java.util.Date(it)) } ?: "?",
                            subject     = subject,
                            fullMessage = full,
                            filesChanged = files
                        )
                    }
                    VcsLog(repo = rootStr, commits = commitList)
                }
                Json.encodeToString(result)
            } catch (e: Exception) {
                "Error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── get_vcs_blame ─────────────────────────────────────────────────────────

    @McpTool(name = "get_vcs_blame")
    @McpDescription(description = """
        Returns line-by-line VCS annotation (blame) for a file: who last modified each line,
        when, and in which commit. Works with Git, SVN, and any VCS configured in IntelliJ.

        Parameters:
        - filePath: path to the file (relative to project root, or absolute)
        - startLine: first line to annotate, 1-based (default: 1)
        - endLine: last line to annotate, 1-based (default: all lines)
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_vcs_blame(filePath: String, startLine: Int = 1, endLine: Int = Int.MAX_VALUE, projectPath: String? = null): String {
        disabledMessage("get_vcs_blame")?.let { return it }
        val project = resolveProject(projectPath)
        return withContext(Dispatchers.IO) {
            try {
                val absPath = if (filePath.startsWith("/")) filePath else "${project.basePath}/$filePath"
                val vFile   = LocalFileSystem.getInstance().findFileByPath(absPath)
                    ?: return@withContext "File not found: $filePath"

                val vcsManager = ProjectLevelVcsManager.getInstance(project)
                val vcs = vcsManager.getVcsFor(vFile)
                    ?: return@withContext "No VCS configured for: $filePath"

                val provider = vcs.annotationProvider
                    ?: return@withContext "${vcs.name} does not support blame/annotation."

                val annotation = try { provider.annotate(vFile) }
                catch (e: VcsException) { return@withContext "Blame failed: ${e.message}" }

                try {
                    val lineCount   = annotation.lineCount
                    val authorAspect = annotation.aspects.firstOrNull { it.id == LineAnnotationAspect.AUTHOR }
                    val dateAspect   = annotation.aspects.firstOrNull { it.id == LineAnnotationAspect.DATE }
                    val revAspect    = annotation.aspects.firstOrNull { it.id == LineAnnotationAspect.REVISION }
                    val dateFmt      = SimpleDateFormat("yyyy-MM-dd")
                    val base         = project.basePath ?: ""

                    val from = (startLine - 1).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
                    val to   = endLine.coerceAtMost(lineCount)

                    val entries = (from until to).map { i ->
                        val author   = authorAspect?.getValue(i)?.takeIf { it.isNotBlank() }
                            ?: annotation.getLineRevisionNumber(i)?.asString()?.take(8) ?: "?"
                        val date     = dateAspect?.getValue(i)?.takeIf { it.isNotBlank() }
                            ?: annotation.getLineDate(i)?.let { dateFmt.format(it) } ?: "?"
                        val revision = revAspect?.getValue(i)?.takeIf { it.isNotBlank() }
                            ?: annotation.getLineRevisionNumber(i)?.asString()?.take(8) ?: "?"
                        val tip      = annotation.getToolTip(i)?.takeIf { it.isNotBlank() }

                        VcsBlameEntry(line = i + 1, author = author, date = date, revision = revision, message = tip)
                    }

                    Json.encodeToString(VcsBlame(file = absPath.relativeTo(base), entries = entries))
                } finally {
                    annotation.close()
                }
            } catch (e: Exception) {
                "Error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── get_local_history ─────────────────────────────────────────────────────

    @McpTool(name = "get_local_history")
    @McpDescription(description = """
        Returns IntelliJ's Local History for a file or directory: a list of revisions with
        timestamps, optional labels (e.g. "Before: Rename variable"), and optional diffs.
        - For a file: unified diff between consecutive revisions.
        - For a directory: list of added/modified/deleted paths between consecutive revisions.
        If no path is specified, returns recent project-wide change events.

        Parameters:
        - path: file or directory path (relative to project root, or absolute); omit for project-wide events
        - maxRevisions: max number of revisions to return (default: 20)
        - withDiff: if true, include changes between each revision and its predecessor (default: false)
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_local_history(
        path: String = "",
        maxRevisions: Int = 20,
        withDiff: Boolean = false,
        projectPath: String? = null
    ): String {
        disabledMessage("get_local_history")?.let { return it }
        val project = resolveProject(projectPath)
        return withContext(Dispatchers.IO) {
            try {
                val lhClass   = Class.forName("com.intellij.history.LocalHistory")
                val lh        = lhClass.getMethod("getInstance").invoke(null)
                val implClass = runCatching {
                    Class.forName("com.intellij.history.integration.LocalHistoryImpl")
                }.getOrNull() ?: return@withContext "LocalHistory implementation not accessible."

                val facade  = implClass.getMethod("getFacade").invoke(lh)
                    ?: return@withContext "LocalHistory facade not available."
                val gateway = implClass.getMethod("getGateway").invoke(lh)
                    ?: return@withContext "LocalHistory gateway not available."

                val facadeClass = facade.javaClass
                val base = project.basePath ?: ""
                val fmt  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

                // Force-register any unsaved documents so history is up to date
                runCatching {
                    generateSequence(gateway.javaClass as Class<*>?) { it.superclass }
                        .flatMap { it.declaredMethods.asSequence() }
                        .firstOrNull { it.name == "registerUnsavedDocuments" }
                        ?.also { it.isAccessible = true }
                        ?.invoke(gateway, facade)
                }

                if (path.isNotBlank()) {
                    // ── File / directory history ───────────────────────────
                    val absPath = if (path.startsWith("/")) path else "$base/$path"
                    val vFile   = LocalFileSystem.getInstance().refreshAndFindFileByPath(absPath)
                        ?: return@withContext "Path not found: $path"
                    val isDir   = vFile.isDirectory

                    // Translate to the path form used by LocalHistory
                    val lhPath = runCatching {
                        Class.forName("com.intellij.history.integration.IdeaGateway")
                            .getMethod("getPathOrUrl", com.intellij.openapi.vfs.VirtualFile::class.java)
                            .invoke(gateway, vFile) as? String
                    }.getOrNull() ?: absPath

                    // ── Approach 1: FileHistoryDialogModel for FILES only (returns Revision with valid ts) ──
                    // DirectoryHistoryDialogModel is skipped: its RevisionItems have broken timestamps
                    @Suppress("UNCHECKED_CAST")
                    val revisions: List<Any> = (if (!isDir) runCatching {
                        val modelCls = Class.forName(
                            "com.intellij.history.integration.ui.models.FileHistoryDialogModel")
                        val ctor = modelCls.constructors
                            .filter { c -> c.parameterCount in 4..5 &&
                                com.intellij.openapi.project.Project::class.java.isAssignableFrom(c.parameterTypes[0]) }
                            .minByOrNull { it.parameterCount }
                        val model = if (ctor?.parameterCount == 4) ctor.newInstance(project, gateway, facade, vFile)
                                    else                            ctor?.newInstance(project, gateway, facade, vFile, false)
                        val allModelMethods = generateSequence(modelCls as Class<*>?) { it.superclass }
                            .flatMap { it.declaredMethods.asSequence() }.toList()
                        listOf("getRevisions", "calcRevisionsInBackground", "getItems")
                            .firstNotNullOfOrNull { name ->
                                allModelMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
                                    ?.also { it.isAccessible = true }
                                    ?.let { runCatching { it.invoke(model) as? List<Any> }.getOrNull() }
                                    ?.takeIf { it.isNotEmpty() }
                            }
                    }.getOrNull()?.takeIf { it.isNotEmpty() } else null)

                    // ── Approach 2: ChangeList.iterChanges() → filter ChangeSets by path ──
                    // Used for: directories (always), files (fallback if dialog model fails)
                    ?: runCatching {
                        val changeList = generateSequence(facadeClass as Class<*>?) { it.superclass }
                            .flatMap { it.declaredMethods.asSequence() }
                            .firstOrNull { "getChangeList" in it.name }
                            ?.also { it.isAccessible = true }?.invoke(facade)

                        val iterM = changeList?.let {
                            generateSequence(it.javaClass as Class<*>?) { c -> c.superclass }
                                .flatMap { c -> c.declaredMethods.asSequence() }
                                .firstOrNull { m -> m.name == "iterChanges" }
                                ?.also { m -> m.isAccessible = true }
                        }
                        @Suppress("UNCHECKED_CAST")
                        val allSets = (iterM?.invoke(changeList) as? Iterable<Any>)?.toList() ?: emptyList()

                        // A ChangeSet matches if any of its inner Changes contains our path
                        allSets.filter { cs ->
                            runCatching {
                                val innerChanges = generateSequence(cs.javaClass as Class<*>?) { it.superclass }
                                    .flatMap { it.declaredMethods.asSequence() }
                                    .firstOrNull { it.name == "getChanges" && it.parameterCount == 0 }
                                    ?.also { it.isAccessible = true }
                                    ?.let { @Suppress("UNCHECKED_CAST") it.invoke(cs) as? Iterable<Any> }
                                    ?: listOf(cs)  // fallback: treat the changeset itself as the change
                                innerChanges.any { ch ->
                                    runCatching {
                                        val p = generateSequence(ch.javaClass as Class<*>?) { it.superclass }
                                            .flatMap { it.declaredMethods.asSequence() }
                                            .firstOrNull { it.name == "getPath" && it.parameterCount == 0 }
                                            ?.also { it.isAccessible = true }?.invoke(ch)?.toString()
                                        if (p != null) return@runCatching(
                                            if (isDir) p.startsWith("$lhPath/") || p == lhPath
                                            else p == lhPath
                                        )
                                        // Try affectsPath(String)
                                        generateSequence(ch.javaClass as Class<*>?) { it.superclass }
                                            .flatMap { it.declaredMethods.asSequence() }
                                            .firstOrNull { it.name == "affectsPath" && it.parameterCount == 1 }
                                            ?.also { it.isAccessible = true }
                                            ?.invoke(ch, lhPath) as? Boolean == true
                                    }.getOrDefault(false)
                                }
                            }.getOrDefault(false)
                        }.takeIf { it.isNotEmpty() }
                    }.getOrNull()

                    ?: run {
                        return@withContext "No local history found for: ${absPath.relativeTo(base)}"
                    }

                    if (revisions.isEmpty())
                        return@withContext "No local history for: ${absPath.relativeTo(base)}"

                    val entries = revisions.take(maxRevisions).mapIndexed { idx, rev ->
                        val ts    = changeSetTimestamp(rev)
                        val label = runCatching { invoke(rev, "getLabel") as? String }.getOrNull()
                        val cause = runCatching { invoke(rev, "getCauseChangeName") as? String }.getOrNull()

                        val diff: String? = if (withDiff && idx + 1 < revisions.size) runCatching {
                            // Try Entry-based content first (works with Revision objects from dialog model)
                            val entryNow  = invoke(rev,              "getEntry")
                            val entryPrev = invoke(revisions[idx+1], "getEntry")
                            val curFromEntry  = if (!isDir) entryContent(entryNow)  else null
                            val prevFromEntry = if (!isDir) entryContent(entryPrev) else null

                            if (curFromEntry != null && prevFromEntry != null) {
                                unifiedDiff("${vFile.name} (prev)", vFile.name, prevFromEntry, curFromEntry)
                            } else if (!isDir) {
                                // Fallback: use LocalHistory.getByteContent(vFile, { t -> t <= ts }) via proxy
                                val cur  = contentAtTimestamp(lh, lhClass, vFile, ts)
                                val prev = contentAtTimestamp(lh, lhClass, vFile,
                                    runCatching { invoke(revisions[idx+1], "getTimestamp") as? Long }.getOrNull() ?: 0L)
                                if (cur != null && prev != null && cur != prev)
                                    unifiedDiff("${vFile.name} (prev)", vFile.name, prev, cur)
                                else null
                            } else {
                                // Directory: compare entry trees
                                if (entryNow != null && entryPrev != null)
                                    directoryDiffSummary(entryPrev, entryNow).takeIf { it.isNotBlank() }
                                else null
                            }
                        }.getOrNull() else null

                        LocalHistoryEntry(
                            index     = idx,
                            timestamp = fmt.format(java.util.Date(ts)),
                            label     = label ?: cause,
                            diff      = diff
                        )
                    }
                    Json.encodeToString(LocalHistoryFile(
                        file           = absPath.relativeTo(base),
                        isDirectory    = isDir,
                        totalRevisions = revisions.size,
                        entries        = entries
                    ))

                } else {
                    // ── Project-wide recent changes ────────────────────────
                    // createTransientRootEntryForPath requires a ReadAction
                    val createRootM = generateSequence(gateway.javaClass as Class<*>?) { it.superclass }
                        .flatMap { it.declaredMethods.asSequence() }
                        .firstOrNull { it.name == "createTransientRootEntryForPath" && it.parameterCount == 2 }
                        ?.also { it.isAccessible = true }
                        ?: return@withContext "createTransientRootEntryForPath not found on gateway."
                    val rootEntry = runCatching {
                        com.intellij.openapi.application.ReadAction.compute<Any, Throwable> {
                            createRootM.invoke(gateway, base, false)
                        }
                    }.getOrElse { e ->
                        return@withContext "createTransientRootEntryForPath failed: ${e.cause?.message ?: e.message}"
                    }

                    // Use ChangeList.iterChanges() — returns most-recent-first
                    val changeList = generateSequence(facadeClass as Class<*>?) { it.superclass }
                        .flatMap { it.declaredMethods.asSequence() }
                        .firstOrNull { "getChangeList" in it.name }
                        ?.also { it.isAccessible = true }?.invoke(facade)
                        ?: return@withContext "ChangeList not accessible."

                    val iterM = generateSequence(changeList.javaClass as Class<*>?) { it.superclass }
                        .flatMap { it.declaredMethods.asSequence() }
                        .firstOrNull { it.name == "iterChanges" }
                        ?.also { it.isAccessible = true }
                        ?: return@withContext "iterChanges not found on ChangeList."

                    @Suppress("UNCHECKED_CAST")
                    val allSets = (iterM.invoke(changeList) as? Iterable<Any>)?.toList() ?: emptyList()

                    if (allSets.isEmpty())
                        return@withContext "No recent local changes recorded."

                    var entryIdx = 0
                    val entries = allSets.take(maxRevisions * 3).mapNotNull { cs ->
                        val ts    = changeSetTimestamp(cs)
                        val label = runCatching { invoke(cs, "getLabel") as? String }.getOrNull()
                        // Try to list affected paths from the changeset's inner changes
                        val affectedPaths = runCatching {
                            val m = generateSequence(cs.javaClass as Class<*>?) { it.superclass }
                                .flatMap { it.declaredMethods.asSequence() }
                                .firstOrNull { it.name == "getChanges" && it.parameterCount == 0 }
                                ?.also { it.isAccessible = true }
                            @Suppress("UNCHECKED_CAST")
                            (m?.invoke(cs) as? Iterable<Any>)?.mapNotNull { ch ->
                                runCatching {
                                    val p = generateSequence(ch.javaClass as Class<*>?) { it.superclass }
                                        .flatMap { it.declaredMethods.asSequence() }
                                        .firstOrNull { it.name == "getPath" && it.parameterCount == 0 }
                                        ?.also { it.isAccessible = true }?.invoke(ch)?.toString()
                                    // Keep only paths inside the project
                                    if (p != null && base.isNotBlank() && p.startsWith(base)) p.relativeTo(base)
                                    else null
                                }.getOrNull()
                            }?.distinct()?.take(10)
                        }.getOrNull()
                        // Skip changesets with no in-project paths
                        if (affectedPaths.isNullOrEmpty()) return@mapNotNull null
                        LocalHistoryRecentChange(
                            index         = entryIdx++,
                            timestamp     = fmt.format(java.util.Date(ts)),
                            label         = label ?: "(unlabeled change)",
                            affectedPaths = affectedPaths
                        )
                    }.take(maxRevisions)
                    Json.encodeToString(LocalHistoryRecent(changes = entries))
                }
            } catch (e: ClassNotFoundException) {
                "Local History API not available in this IDE: ${e.message}"
            } catch (e: Exception) {
                "Error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun git4ideaLoader(): ClassLoader? =
        runCatching {
            PluginManagerCore.getPlugin(PluginId.getId("Git4Idea"))?.pluginClassLoader
        }.getOrNull()

    /**
     * Extracts timestamp from a ChangeSet, Revision, RevisionItem, or RecentChange object.
     * Tries multiple strategies to handle the various wrapper types IntelliJ uses.
     */
    private fun changeSetTimestamp(obj: Any?): Long {
        if (obj == null) return 0L
        // 1. getTimestamp() directly
        runCatching { invoke(obj, "getTimestamp") as? Long }
            .getOrNull()?.takeIf { it > 0L }?.let { return it }
        // 2. Private field myTimestamp / timestamp
        runCatching {
            generateSequence(obj.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .firstOrNull { it.name == "myTimestamp" || it.name == "timestamp" }
                ?.also { it.isAccessible = true }?.getLong(obj)
        }.getOrNull()?.takeIf { it > 0L }?.let { return it }
        // 3. Timestamp from wrapped Revision: getRevision().getTimestamp()
        runCatching {
            invoke(invoke(obj, "getRevision"), "getTimestamp") as? Long
        }.getOrNull()?.takeIf { it > 0L }?.let { return it }
        // 4. Timestamp from first inner Change: getChanges().first().getTimestamp()
        runCatching {
            val m = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredMethods.asSequence() }
                .firstOrNull { it.name == "getChanges" && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (m?.invoke(obj) as? Iterable<Any>)?.firstOrNull()
                ?.let { invoke(it, "getTimestamp") as? Long }
        }.getOrNull()?.takeIf { it > 0L }?.let { return it }
        // 5. All Long fields — take the first that looks like a recent epoch ms timestamp
        runCatching {
            generateSequence(obj.javaClass as Class<*>?) { it.superclass }
                .flatMap { it.declaredFields.asSequence() }
                .filter { it.type == Long::class.java || it.type == java.lang.Long.TYPE }
                .mapNotNull { f -> runCatching { f.also { it.isAccessible = true }.getLong(obj) }.getOrNull() }
                .firstOrNull { it > 1_000_000_000_000L } // plausible epoch-ms (after year 2001)
        }.getOrNull()?.let { return it }
        return 0L
    }

    /**
     * Returns file content at the given timestamp using LocalHistory.getByteContent via a dynamic proxy
     * for FileRevisionTimestampComparator — works regardless of whether revisions are Revision or ChangeSet.
     */
    private fun contentAtTimestamp(lh: Any, lhClass: Class<*>, vFile: com.intellij.openapi.vfs.VirtualFile, timestamp: Long): String? {
        return runCatching {
            val comparatorClass = Class.forName("com.intellij.history.FileRevisionTimestampComparator")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                comparatorClass.classLoader, arrayOf(comparatorClass)
            ) { _, _, args -> (args[0] as Long) <= timestamp }
            val bytes = lhClass.getMethod("getByteContent",
                com.intellij.openapi.vfs.VirtualFile::class.java, comparatorClass)
                .invoke(lh, vFile, proxy) as? ByteArray
            bytes?.let { String(it, Charsets.UTF_8) }
        }.getOrNull()
    }

    /** Returns the text content of a LocalHistory Entry (file entry only), or null. */
    private fun entryContent(entry: Any?): String? {
        if (entry == null) return null
        return runCatching {
            val content = invoke(entry, "getContent") ?: return null
            val avail   = runCatching { invoke(content, "isAvailable") as? Boolean }.getOrDefault(true)
            if (avail == false) return null
            (invoke(content, "getBytes") as? ByteArray)?.let { String(it, Charsets.UTF_8) }
        }.getOrNull()
    }

    /**
     * Compares two LocalHistory directory Entry objects and returns a human-readable
     * summary of added, deleted, and modified paths (recursive).
     */
    private fun directoryDiffSummary(before: Any?, after: Any?, prefix: String = ""): String {
        @Suppress("UNCHECKED_CAST")
        val childrenBefore = runCatching {
            (invoke(before, "getChildren") as? List<Any>)
                ?.associateBy { invoke(it, "getName") as? String ?: "" } ?: emptyMap()
        }.getOrDefault(emptyMap())

        @Suppress("UNCHECKED_CAST")
        val childrenAfter = runCatching {
            (invoke(after, "getChildren") as? List<Any>)
                ?.associateBy { invoke(it, "getName") as? String ?: "" } ?: emptyMap()
        }.getOrDefault(emptyMap())

        val lines = mutableListOf<String>()
        for (name in (childrenBefore.keys + childrenAfter.keys).toSortedSet()) {
            val entBefore = childrenBefore[name]
            val entAfter  = childrenAfter[name]
            val p = if (prefix.isBlank()) name else "$prefix/$name"
            when {
                entBefore == null -> lines.add("+++ $p")
                entAfter  == null -> lines.add("--- $p")
                else -> {
                    val isSubDir = invoke(entAfter, "getChildren") != null
                    if (isSubDir) {
                        val sub = directoryDiffSummary(entBefore, entAfter, p)
                        if (sub.isNotBlank()) lines.add(sub)
                    } else {
                        val cBefore = entryContent(entBefore)
                        val cAfter  = entryContent(entAfter)
                        if (cBefore != cAfter) lines.add("~ $p")
                    }
                }
            }
        }
        return lines.joinToString("\n")
    }

    /** Invokes a no-arg method on any object via reflection (class hierarchy traversal). */
    private fun invoke(obj: Any?, methodName: String): Any? {
        if (obj == null) return null
        val method = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
            .flatMap { c -> c.declaredMethods.asSequence() + c.interfaces.flatMap { it.methods.asSequence() } }
            .firstOrNull { it.name == methodName && it.parameterCount == 0 }
            ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(obj) }.getOrNull()
    }

    /** Builds a unified diff string for a Change, or returns full content for new/deleted files. */
    private fun buildDiff(change: Change, beforePath: String?, afterPath: String?): String? {
        return when (change.type) {
            Change.Type.NEW -> runCatching {
                val content = change.afterRevision?.getContent() ?: return null
                content.lines().take(300).joinToString("\n") { "+$it" }
            }.getOrNull()

            Change.Type.DELETED -> runCatching {
                val content = change.beforeRevision?.getContent() ?: return null
                content.lines().take(300).joinToString("\n") { "-$it" }
            }.getOrNull()

            Change.Type.MODIFICATION, Change.Type.MOVED -> runCatching {
                val before = change.beforeRevision?.getContent() ?: return null
                val after  = change.afterRevision?.getContent()  ?: return null
                unifiedDiff(beforePath ?: "before", afterPath ?: "after", before, after)
            }.getOrNull()
        }
    }

    /** Generates a unified diff between two strings. No size limit — hunks are capped at MAX_HUNKS. */
    internal fun unifiedDiff(beforeLabel: String, afterLabel: String, before: String, after: String): String {
        if (before == after) return "(no changes)"
        val a = before.lines()
        val b = after.lines()
        val MAX_HUNKS = 20

        // Edit script: 'k'=keep, '-'=delete, '+'=add
        data class Edit(val op: Char, val text: String)

        // For small files use full LCS (exact diff).
        // For large files use prefix/suffix alignment + raw diff of the middle section
        // — handles the common case (version bump, small edits) without O(n²) cost.
        val edits: List<Edit> = if (a.size <= 800 && b.size <= 800) {
            val m = a.size; val n = b.size
            val dp = Array(m + 1) { IntArray(n + 1) }
            for (i in 1..m) for (j in 1..n)
                dp[i][j] = if (a[i-1] == b[j-1]) dp[i-1][j-1] + 1 else maxOf(dp[i-1][j], dp[i][j-1])
            val result = mutableListOf<Edit>()
            var i = m; var j = n
            while (i > 0 || j > 0) {
                when {
                    i > 0 && j > 0 && a[i-1] == b[j-1] -> { result.add(Edit('k', a[i-1])); i--; j-- }
                    i > 0 && (j == 0 || dp[i-1][j] >= dp[i][j-1]) -> { result.add(Edit('-', a[i-1])); i-- }
                    else -> { result.add(Edit('+', b[j-1])); j-- }
                }
            }
            result.reversed()
        } else {
            // Large file: align common prefix and suffix, diff the middle block
            var prefixLen = 0
            while (prefixLen < a.size && prefixLen < b.size && a[prefixLen] == b[prefixLen]) prefixLen++
            var suffixLen = 0
            val aMiddleEnd = a.size - suffixLen
            val bMiddleEnd = b.size - suffixLen
            while (suffixLen < a.size - prefixLen && suffixLen < b.size - prefixLen &&
                   a[a.size - 1 - suffixLen] == b[b.size - 1 - suffixLen]) suffixLen++
            buildList {
                for (i in 0 until prefixLen) add(Edit('k', a[i]))
                for (i in prefixLen until a.size - suffixLen) add(Edit('-', a[i]))
                for (i in prefixLen until b.size - suffixLen) add(Edit('+', b[i]))
                for (i in a.size - suffixLen until a.size) add(Edit('k', a[i]))
            }
        }

        // Format hunks with 3-line context, capped at MAX_HUNKS
        val CONTEXT = 3
        val sb = StringBuilder("--- $beforeLabel\n+++ $afterLabel\n")
        var ei = 0
        var hunkCount = 0
        while (ei < edits.size) {
            if (edits[ei].op == 'k') { ei++; continue }
            if (hunkCount >= MAX_HUNKS) {
                val remaining = edits.drop(ei).count { it.op != 'k' }
                sb.append("(... $remaining more changed line(s) in ${edits.size - ei} lines not shown — $MAX_HUNKS hunks limit reached)\n")
                break
            }
            val hStart = (ei - CONTEXT).coerceAtLeast(0)
            var hEnd = ei + 1
            while (hEnd < edits.size) {
                val nextChange = (hEnd until edits.size).indexOfFirst { edits[it].op != 'k' }
                if (nextChange < 0 || nextChange > CONTEXT) break
                hEnd = hEnd + nextChange + 1
            }
            hEnd = (hEnd + CONTEXT).coerceAtMost(edits.size)

            val bStart = edits.take(hStart).count { it.op != '+' } + 1
            val aStart = edits.take(hStart).count { it.op != '-' } + 1
            val bCount = edits.subList(hStart, hEnd).count { it.op != '+' }
            val aCount = edits.subList(hStart, hEnd).count { it.op != '-' }
            sb.append("@@ -$bStart,$bCount +$aStart,$aCount @@\n")
            for (e in edits.subList(hStart, hEnd))
                sb.append("${if (e.op == 'k') ' ' else e.op}${e.text}\n")
            ei = hEnd
            hunkCount++
        }
        return sb.toString().trimEnd()
    }

    private fun String.relativeTo(base: String): String =
        if (base.isNotBlank() && startsWith(base)) removePrefix(base).removePrefix("/") else this
}

// ── Data classes ──────────────────────────────────────────────────────────────

@Serializable data class VcsChange(
    val path: String,
    val type: String,
    val changeList: String,
    val movedFrom: String? = null,
    val diff: String? = null
)
@Serializable data class VcsChanges(
    val changes: List<VcsChange>,
    val unversioned: List<String>
)
@Serializable data class VcsBranchInfo(
    val repo: String,
    val currentBranch: String?,
    val localBranches: List<String>,
    val remoteBranches: List<String>
)
@Serializable data class VcsCommit(
    val hash: String,
    val author: String,
    val authorEmail: String,
    val date: String,
    val subject: String,
    val fullMessage: String? = null,
    val filesChanged: List<String>? = null
)
@Serializable data class VcsLog(val repo: String, val commits: List<VcsCommit>)
@Serializable data class VcsBlameEntry(
    val line: Int,
    val author: String,
    val date: String,
    val revision: String,
    val message: String? = null
)
@Serializable data class VcsBlame(val file: String, val entries: List<VcsBlameEntry>)
@Serializable data class LocalHistoryEntry(
    val index: Int,
    val timestamp: String,
    val label: String? = null,
    val diff: String? = null
)
@Serializable data class LocalHistoryFile(
    val file: String,
    val isDirectory: Boolean = false,
    val totalRevisions: Int,
    val entries: List<LocalHistoryEntry>
)
@Serializable data class LocalHistoryRecentChange(
    val index: Int,
    val timestamp: String,
    val label: String,
    val affectedPaths: List<String>? = null
)
@Serializable data class LocalHistoryRecent(val changes: List<LocalHistoryRecentChange>)
