package io.nimbly.mcpcompanion.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.clientInfo
import com.intellij.mcpserver.project
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext
import io.nimbly.mcpcompanion.util.ProcessTracker
import io.nimbly.mcpcompanion.util.resolveProject
import io.nimbly.mcpcompanion.util.runOnEdt
import io.nimbly.mcpcompanion.McpCompanionSettings

class McpCompanionDiagnosticToolset : McpToolset {

    override fun isEnabled(): Boolean = true

    private suspend fun disabledMessage(toolName: String): String? {
        if (!McpCompanionSettings.getInstance().isEnabled(toolName)) {
            val extra = if (toolName in McpCompanionSettings.DISABLED_BY_DEFAULT)
                " This tool is disabled by default for safety reasons. Ask the user to enable it first."
            else ""
            return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion.$extra"
        }
        McpCompanionSettings.getInstance().trackCall(toolName, runCatching { coroutineContext.clientInfo?.name?.takeIf { it != "Unknown MCP client" } }.getOrNull())
        return null
    }

    // ── get_ide_settings ─────────────────────────────────────────────────────

    @McpTool(name = "get_ide_settings")
    @McpDescription(description = """
        Reads IntelliJ IDE settings.
        - No parameters: returns the most useful settings (build tool, SDK, Gradle, compiler, encoding…)
        - search: filters all known settings by keyword (substring match on key name)
        - key: reads one specific setting key directly
        - prefix: returns all settings whose key starts with the given prefix (e.g. "gradle")
          combine with depth to limit how many dot-separated segments are returned beyond the prefix
          (e.g. prefix="gradle", depth=1 → only "gradle.xxx" keys, not "gradle.xxx.yyy")
        Results are returned as a flat JSON map of key → value.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_ide_settings(search: String? = null, key: String? = null, prefix: String? = null, depth: Int? = null, projectPath: String? = null): String {
        disabledMessage("get_ide_settings")?.let { return it }
        val project = resolveProject(projectPath)
        return captureResponse(runOnEdt {
            val known = knownIdeSettings(project)
            val results = linkedMapOf<String, String?>()
            when {
                key != null -> {
                    val pc = com.intellij.ide.util.PropertiesComponent.getInstance()
                    val pcProj = com.intellij.ide.util.PropertiesComponent.getInstance(project)
                    results[key] = pc.getValue(key) ?: pcProj.getValue(key) ?: known[key]
                }
                prefix != null -> {
                    val normalizedPrefix = if (prefix.endsWith(".")) prefix else "$prefix."
                    val prefixSegments = normalizedPrefix.trimEnd('.').split(".").size
                    known.filter { (k, _) -> k.startsWith(normalizedPrefix) || k == prefix }
                        .filter { (k, _) ->
                            if (depth == null) true
                            else k.split(".").size <= prefixSegments + depth
                        }
                        .forEach { (k, v) -> results[k] = v }
                }
                search != null -> {
                    val lower = search.lowercase()
                    known.filter { (k, _) -> k.lowercase().contains(lower) }
                        .forEach { (k, v) -> results[k] = v }
                }
                else -> results.putAll(known)
            }
            Json.encodeToString(results as Map<String, String?>)
        })
    }

    internal fun knownIdeSettings(project: com.intellij.openapi.project.Project): Map<String, String?> {
        val map = linkedMapOf<String, String?>()

        map["project.name"]     = project.name
        map["project.basePath"] = project.basePath

        runCatching {
            val sdk = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).projectSdk
            map["project.sdk.name"]    = sdk?.name
            map["project.sdk.type"]    = sdk?.sdkType?.name
            map["project.sdk.version"] = sdk?.versionString
        }

        val gradlePlugin = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
            com.intellij.openapi.extensions.PluginId.getId("com.intellij.gradle")
        )
        if (gradlePlugin != null) {
            val gradleError = runCatching {
                val gsClass = Class.forName("org.jetbrains.plugins.gradle.settings.GradleSettings", true, gradlePlugin.classLoader)
                val gs = gsClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java).invoke(null, project)
                val linked = gs.javaClass.getMethod("getLinkedProjectsSettings").invoke(gs)
                val all = (linked as? Iterable<*>)?.toList() ?: emptyList<Any>()
                map["gradle.linkedProjects.count"] = all.size.toString()
                val first = all.firstOrNull()
                if (first != null) {
                    map["gradle.delegatedBuild"]           = runCatching { first.javaClass.getMethod("getDelegatedBuild").invoke(first)?.toString() }.getOrNull()
                    map["gradle.gradleJvm"]                = runCatching { first.javaClass.getMethod("getGradleJvm").invoke(first)?.toString() }.getOrNull()
                    map["gradle.gradleHome"]               = runCatching { first.javaClass.getMethod("getGradleHome").invoke(first)?.toString() }.getOrNull()
                    map["gradle.testRunner"]               = runCatching { first.javaClass.getMethod("getTestRunner").invoke(first)?.toString() }.getOrNull()
                    map["gradle.resolveModulePerSourceSet"]= runCatching { first.javaClass.getMethod("isResolveModulePerSourceSet").invoke(first)?.toString() }.getOrNull()
                }
            }.exceptionOrNull()
            if (gradleError != null) map["gradle.error"] = gradleError.message ?: gradleError.toString()
        }

        runCatching {
            val ccClass = Class.forName("com.intellij.compiler.CompilerConfiguration")
            val cc = ccClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java).invoke(null, project)
            map["compiler.projectBytecodeTarget"] = runCatching { cc.javaClass.getMethod("getProjectBytecodeTarget").invoke(cc)?.toString() }.getOrNull()
            map["compiler.addNotNullAssertions"]  = runCatching { cc.javaClass.getMethod("isAddNotNullAssertions").invoke(cc)?.toString() }.getOrNull()
        }

        runCatching {
            val em = com.intellij.openapi.vfs.encoding.EncodingProjectManager.getInstance(project)
            map["encoding.default"] = em.defaultCharsetName
            runCatching { map["encoding.nativesToAscii"] = em.javaClass.getMethod("isNativesToAscii").invoke(em)?.toString() }
        }

        val gitPlugin = listOf("Git4Idea", "com.intellij.vcs.git", "git4idea")
            .mapNotNull { com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId(it)) }
            .firstOrNull()
        if (gitPlugin != null) {
            runCatching {
                val cl = gitPlugin.classLoader
                val gsClass = Class.forName("git4idea.config.GitVcsSettings", true, cl)
                val gs = gsClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java).invoke(null, project)
                map["git.pathToGit"]              = runCatching { gs.javaClass.getMethod("getPathToGit").invoke(gs)?.toString() }.getOrNull()
                map["git.updateMethod"]           = runCatching { gs.javaClass.getMethod("getUpdateMethod").invoke(gs)?.toString() }.getOrNull()
                map["git.syncSetting"]            = runCatching { gs.javaClass.getMethod("getSyncSetting").invoke(gs)?.toString() }.getOrNull()
                map["git.autoFetch"]              = runCatching { gs.javaClass.getMethod("isAutoFetch").invoke(gs)?.toString() }.getOrNull()
                map["git.autoFetchIntervalMin"]   = runCatching { gs.javaClass.getMethod("getAutoFetchSleepIntervalInMinutes").invoke(gs)?.toString() }.getOrNull()
                map["git.signOffCommit"]          = runCatching { gs.javaClass.getMethod("isSignOffCommit").invoke(gs)?.toString() }.getOrNull()
                map["git.commitMessageTempl"]     = runCatching { gs.javaClass.getMethod("getCommitMessageTemplate").invoke(gs)?.toString() }.getOrNull()
            }.onFailure { map["git.error"] = it.message ?: it.toString() }
        }

        return map
    }

    private fun buildTreePath(tree: javax.swing.JTree, target: Any): javax.swing.tree.TreePath? {
        val model = tree.model ?: return null
        fun search(node: Any, path: List<Any>): List<Any>? {
            if (node === target) return path + node
            for (i in 0 until model.getChildCount(node)) {
                val found = search(model.getChild(node, i), path + node)
                if (found != null) return found
            }
            return null
        }
        val nodes = search(model.root, emptyList()) ?: return null
        return javax.swing.tree.TreePath(nodes.toTypedArray())
    }

    // ── get_running_processes ─────────────────────────────────────────────────

    @McpTool(name = "get_running_processes")
    @McpDescription(description = """
        Returns IntelliJ background-task activity — the same tasks visible in the status bar
        (indexing, Gradle sync, compilation, inspections, etc.) — for every project open in
        this IDE. A single JVM can host several project windows, so the response is keyed
        by project.

        { "projects": [ { projectPath, projectName, running, recentlyFinished }, ... ] }

        - running: tasks currently in progress
          * title: main label of the task
          * details: secondary line (current file, step, etc.) if available
          * progress: 0.0–1.0, or null if indeterminate
          * cancellable: whether the task can be cancelled
          * paused: true if the task is currently suspended

        - recentlyFinished: up to 10 tasks that finished in the last 3 minutes
          * title, ranMs (how long it ran), endedAgoMs (how long ago it ended)

        Call this when IntelliJ seems busy, slow, or stuck to understand what it is doing,
        or right after an event to see what just completed. Useful for CPU diagnostics
        across multiple projects at once.
    """)
    suspend fun get_running_processes(): String {
        disabledMessage("get_running_processes")?.let { return it }
        val projects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
        val entries = projects.map { p ->
            ProjectProcessesSnapshot(
                projectPath = p.basePath ?: "",
                projectName = p.name,
                running = collectRunningProcesses(p),
                recentlyFinished = collectRecentlyFinished(p)
            )
        }
        if (entries.all { it.running.isEmpty() && it.recentlyFinished.isEmpty() })
            return captureResponse("No background processes running")
        return captureResponse(Json.encodeToString(RunningProcessesResult(projects = entries)))
    }

    /** Snapshots the tracker's recently-finished queue for inclusion in diagnostic tools. */
    fun collectRecentlyFinished(project: com.intellij.openapi.project.Project): List<SnapshotFinishedProcess> {
        val now = System.currentTimeMillis()
        return ProcessTracker.getInstance(project).recentlyFinished().map {
            SnapshotFinishedProcess(title = it.title, ranMs = it.ranMs, endedAgoMs = now - it.endedAt)
        }
    }

    /**
     * Returns all active background processes (same set as the "Processes" popup), fed by
     * [ProcessTracker] which polls `StatusBarEx.getBackgroundProcesses()` — this catches
     * external-system tasks (Gradle sync, "Indexing files" on sync, Maven import) that
     * `CoreProgressManager.getCurrentIndicators()` misses.
     */
    fun collectRunningProcesses(project: com.intellij.openapi.project.Project): List<RunningProcess> {
        val tracker = ProcessTracker.getInstance(project)
        val seen = mutableSetOf<String>()
        val result = mutableListOf<RunningProcess>()
        for (task in tracker.active()) {
            if (!seen.add(task.title)) continue
            val ind = task.indicator
            val details = runCatching { ind.text2 }.getOrNull()?.takeIf { it.isNotBlank() }
            val progress = runCatching {
                if (ind.isIndeterminate) null else ind.fraction.takeIf { it in 0.0..1.0 }
            }.getOrNull()
            val cancellable = ind.reflectBoolean("isCancellable")
            val paused = runCatching { getSuspender(ind)?.isSuspended() == true }.getOrDefault(false)
            result += RunningProcess(
                title = task.title,
                details = details,
                progress = progress,
                cancellable = cancellable,
                paused = paused
            )
        }
        return result
    }

    // ── manage_process ────────────────────────────────────────────────────────

    @McpTool(name = "manage_process")
    @McpDescription(description = """
        Controls a running background process by its title (as returned by get_running_processes).
        title: partial, case-insensitive match (e.g. "index" matches "Indexing project…").
        action: one of:
          - "cancel" — stop the process (only if cancellable)
          - "pause"  — suspend the process temporarily
          - "resume" — resume a paused process
        Returns a confirmation message or an error if the process is not found / action not supported.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun manage_process(title: String, action: String, projectPath: String? = null): String {
        disabledMessage("manage_process")?.let { return it }
        val project = resolveProject(projectPath)
        val tracker = ProcessTracker.getInstance(project)
        val active = tracker.findActiveByTitle(title)
        val match: ProgressIndicator? = active?.indicator
        val matchedSuspender: Any? = match?.let { getSuspender(it) }
        if (match == null) return "No running process found matching \"$title\""

        val label = active.title
        return captureResponse(when (action.lowercase().trim()) {
            "cancel" -> {
                if (match.reflectBoolean("isCancellable") == false) "Process \"$label\" is not cancellable"
                else {
                    val suspender = matchedSuspender ?: getSuspender(match)
                    if (suspender?.isSuspended() == true) {
                        suspender.reflectInvoke("resumeProcess", "", "")
                        Thread.sleep(100)
                    }
                    match.cancel()
                    "Cancelled: \"$label\""
                }
            }
            "pause" -> {
                val suspender = matchedSuspender ?: getSuspender(match)
                    ?: return "Process \"$label\" does not support pause"
                suspender.reflectInvoke("suspendProcess", "Paused: \"$label\"", "Process \"$label\" does not support pause",
                    arrayOf(String::class.java), arrayOf("Paused by MCP"))
            }
            "resume" -> {
                val suspender = matchedSuspender ?: getSuspender(match)
                    ?: return "Process \"$label\" does not support resume"
                suspender.reflectInvoke("resumeProcess", "Resumed: \"$label\"", "Process \"$label\" does not support resume")
            }
            else -> "Unknown action \"$action\". Use: cancel, pause, resume"
        })
    }

    private fun ProgressIndicator.reflectBoolean(method: String): Boolean? = try {
        javaClass.getMethod(method).invoke(this) as? Boolean
    } catch (_: Exception) { null }

    private fun ProgressIndicator.reflectInvoke(method: String, success: String, failure: String): String = try {
        javaClass.getMethod(method).invoke(this); success
    } catch (_: Exception) { failure }

    private fun Any.reflectInvoke(method: String, success: String, failure: String,
                                   paramTypes: Array<Class<*>> = emptyArray(),
                                   args: Array<Any?> = emptyArray()): String = try {
        javaClass.getMethod(method, *paramTypes).invoke(this, *args); success
    } catch (_: Exception) { failure }

    private fun getSuspender(indicator: ProgressIndicator): Any? = try {
        val cls = Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        cls.getMethod("getSuspender", ProgressIndicator::class.java).invoke(null, indicator)
    } catch (_: Exception) { null }

    private fun Any.isSuspended(): Boolean =
        runCatching { javaClass.getMethod("isSuspended").invoke(this) as? Boolean ?: false }.getOrDefault(false)

    // ── get_ide_snapshot ──────────────────────────────────────────────────────

    @McpTool(name = "get_ide_snapshot")
    @McpDescription(description = """
        Lightweight snapshot of what the developer is currently doing in the IDE.
        Designed for frequent polling (e.g. a Claude Code UserPromptSubmit hook)
        so the AI assistant always knows the current context without being told.

        Returns a compact JSON payload with ONE entry per open project in the IDE —
        a single JVM can host several project windows, so the hook/AI must pick the
        project whose projectPath matches its current working directory.

        { "projects": [ ProjectSnapshot, ... ] }

        Each ProjectSnapshot contains:
        - projectPath: absolute path of the project root (used to match against Claude Code's CWD)
        - projectName: the project display name
        - activeFile: path + line of the focused editor in this project (e.g. "/path/File.java:42")
        - selection: currently selected text (truncated to 100 chars) if any
        - openFiles: list of open file paths in this project (first 10)
        - activeFileProblems: count of errors and warnings in the active editor (from IDE analysis)
        - build: last build summary — total ERROR and WARNING nodes in the Build tool window
        - runs: all run/debug tabs currently open, each with:
            * name, mode ("run"/"debug")
            * status: "running", "paused" (debug only), "finishedOk", "finishedError", or "finished"
            * exitCode for finished runs (if available)
            * pausedAt: "file:line" of the top stack frame when the debugger is paused
        - indexing: true if this project is currently indexing
        - backgroundProcesses: in-progress tasks from the status bar (Gradle sync, indexing, etc.),
          each with title, startedAgoMs, and progress (0..1 if determinate, null if indeterminate)
        - recentlyFinished: up to 10 tasks that finished in the last 3 minutes (title, ranMs, endedAgoMs)

        This tool is read-only and fast. Safe to call on every turn.
    """)
    suspend fun get_ide_snapshot(): String {
        disabledMessage("get_ide_snapshot")?.let { return it }

        val snapshot = runOnEdt {
            val projects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
            IdeSnapshot(projects = projects.map { buildProjectSnapshot(it) })
        }
        return captureResponse(Json.encodeToString(snapshot))
    }

    /** Builds the per-project section of a snapshot. Must be called on the EDT. */
    private fun buildProjectSnapshot(project: com.intellij.openapi.project.Project): ProjectSnapshot {
        val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val openPaths = fem.openFiles.map { it.path }
        val editor = fem.selectedTextEditor
        val selectedFilePath = fem.selectedFiles.firstOrNull()?.path

        var activeFile: String? = null
        var selection: String? = null
        var activeFileProblems: SnapshotProblems? = null
        if (editor != null && selectedFilePath != null) {
            val line = editor.caretModel.logicalPosition.line + 1
            activeFile = "$selectedFilePath:$line"
            if (editor.selectionModel.hasSelection()) {
                val text = editor.selectionModel.selectedText
                if (!text.isNullOrEmpty()) {
                    selection = if (text.length > 100) text.take(100) + "…" else text
                }
            }
            activeFileProblems = countEditorProblems(editor.document, project)
        }

        val runs = collectRunsSnapshot(project)
        val indexing = DumbService.getInstance(project).isDumb
        val build = countBuildProblems(project)

        val now = System.currentTimeMillis()
        val tracker = ProcessTracker.getInstance(project)
        val bg = tracker.active().map { task ->
            val ind = task.indicator
            val progress = runCatching {
                if (ind.isIndeterminate) null else ind.fraction.takeIf { it in 0.0..1.0 }
            }.getOrNull()
            SnapshotProcess(title = task.title, startedAgoMs = now - task.startedAt, progress = progress)
        }
        val finished = collectRecentlyFinished(project)

        return ProjectSnapshot(
            projectPath = project.basePath ?: "",
            projectName = project.name,
            activeFile = activeFile,
            selection = selection,
            openFileCount = openPaths.size,
            openFiles = openPaths.take(10),
            activeFileProblems = activeFileProblems,
            build = build,
            runs = runs,
            indexing = indexing,
            backgroundProcesses = bg,
            recentlyFinished = finished
        )
    }

    /** Collects all open run/debug tabs with their current status (running, paused, finished ok/error). */
    @Suppress("DEPRECATION")
    private fun collectRunsSnapshot(project: com.intellij.openapi.project.Project): List<SnapshotRun> {
        val debugSessions = com.intellij.xdebugger.XDebuggerManager.getInstance(project).debugSessions
        // Index debug sessions by their ProcessHandler (stable identity across the lifetime of the process),
        // with a fallback index by RunContentDescriptor reference.
        val debugByHandler = debugSessions
            .mapNotNull { sess -> runCatching { sess.debugProcess.processHandler to sess }.getOrNull() }
            .toMap()
        val debugByDescriptor = debugSessions
            .mapNotNull { sess -> sess.runContentDescriptor?.let { it to sess } }
            .toMap()

        return com.intellij.execution.ui.RunContentManager.getInstance(project)
            .allDescriptors
            .map { desc ->
                val ph = desc.processHandler
                val session = (ph?.let { debugByHandler[it] }) ?: debugByDescriptor[desc]
                val mode = if (session != null || desc.contentToolWindowId == "Debug") "debug" else "run"

                val exitCode = if (ph != null && ph.isProcessTerminated)
                    runCatching { ph.javaClass.getMethod("getExitCode").invoke(ph) as? Int }.getOrNull()
                else null

                val status = when {
                    ph == null -> "unknown"
                    !ph.isProcessTerminated -> if (session?.isPaused == true) "paused" else "running"
                    exitCode == null -> "finished"
                    exitCode == 0 -> "finishedOk"
                    else -> "finishedError"
                }

                // For paused debug sessions, report the source location of the top frame
                val pausedAt = if (status == "paused" && session != null) {
                    val pos = runCatching { session.currentPosition }.getOrNull()
                        ?: runCatching { session.topFramePosition }.getOrNull()
                    pos?.let { "${it.file.path}:${it.line + 1}" }
                } else null

                SnapshotRun(
                    name     = desc.displayName,
                    mode     = mode,
                    status   = status,
                    exitCode = exitCode,
                    pausedAt = pausedAt
                )
            }
    }

    /** Counts daemon highlights (errors + warnings) in an editor document. Keeps the snapshot light. */
    private fun countEditorProblems(document: com.intellij.openapi.editor.Document, project: com.intellij.openapi.project.Project): SnapshotProblems? {
        val markupModel = com.intellij.openapi.editor.impl.DocumentMarkupModel.forDocument(document, project, false)
            ?: return null
        var errors = 0
        var warnings = 0
        markupModel.allHighlighters
            .mapNotNull { it.errorStripeTooltip as? com.intellij.codeInsight.daemon.impl.HighlightInfo }
            .filter { !it.description.isNullOrBlank() }
            .forEach { info ->
                when {
                    info.severity >= com.intellij.lang.annotation.HighlightSeverity.ERROR -> errors++
                    info.severity >= com.intellij.lang.annotation.HighlightSeverity.WARNING -> warnings++
                }
            }
        return SnapshotProblems(errors = errors, warnings = warnings)
    }

    /** Counts ERROR/WARNING nodes in the Build tool window tree, across all tabs. */
    private fun countBuildProblems(project: com.intellij.openapi.project.Project): BuildSummary? {
        val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Build")
            ?: return null
        var errors = 0
        var warnings = 0
        var hasContent = false
        tw.contentManager.contents.forEach { content ->
            val trees = com.intellij.util.ui.UIUtil.findComponentsOfType(content.component, javax.swing.JTree::class.java)
            trees.firstOrNull()?.let { tree ->
                val model = tree.model
                val root = model.root ?: return@let
                hasContent = true
                walkTreeForSeverity(model, root) { sev ->
                    when (sev) {
                        "ERROR"   -> errors++
                        "WARNING" -> warnings++
                    }
                }
            }
        }
        return if (hasContent) BuildSummary(errors = errors, warnings = warnings) else null
    }

    private fun walkTreeForSeverity(model: javax.swing.tree.TreeModel, node: Any, onSeverity: (String) -> Unit) {
        val userObject = (node as? javax.swing.tree.DefaultMutableTreeNode)?.userObject
        if (userObject != null) {
            val cls = userObject.javaClass
            val sev = when {
                (runCatching { cls.methods.find { it.name == "getHasProblems" }?.invoke(userObject) as? Boolean }.getOrNull()) == true -> "ERROR"
                (runCatching { cls.methods.find { it.name == "getVisibleAsWarning" }?.invoke(userObject) as? Boolean }.getOrNull()) == true -> "WARNING"
                else -> null
            }
            if (sev != null) onSeverity(sev)
        }
        val count = model.getChildCount(node)
        for (i in 0 until count) walkTreeForSeverity(model, model.getChild(node, i), onSeverity)
    }

    // ── get_intellij_diagnostic ───────────────────────────────────────────────

    @McpTool(name = "get_intellij_diagnostic")
    @McpDescription(description = """
        Returns IntelliJ diagnostic information in one call — for every project open in this
        IDE. Use this as a first step when something is not working as expected.

        { "projects": [ { projectPath, projectName, indexing, notifications, processes,
                          recentlyFinished }, ... ],
          "logEntries": [...] }

        Per project:
        - indexing: whether that project is currently indexing files (DumbService)
        - notifications: active notifications visible in the Event Log (errors, warnings)
        - processes: background processes currently running or paused
        - recentlyFinished: up to 10 tasks that finished in the last 3 minutes

        Global:
        - logEntries: log entries from idea.log in the last N minutes, including stack traces
          (idea.log is shared across all projects in this IDE)

        minutesBack: how many minutes back to scan idea.log (default: 5)
        level: minimum log level to include — "error" (ERROR+SEVERE only), "warn" (adds WARN), "info" (adds INFO). Default: "error"
    """)
    suspend fun get_intellij_diagnostic(minutesBack: Int = 5, level: String = "error"): String {
        disabledMessage("get_intellij_diagnostic")?.let { return it }

        val projects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
        val entries = runOnEdt {
            projects.map { p ->
                val indexing = IndexingStatus(active = DumbService.getInstance(p).isDumb)
                val notifications = NotificationsManager.getNotificationsManager()
                    .getNotificationsOfType(Notification::class.java, p)
                    .map { n ->
                        DiagnosticNotification(
                            type = n.type.name,
                            title = n.title,
                            content = n.content.takeIf { it.isNotBlank() },
                            groupId = n.groupId.takeIf { it.isNotBlank() }
                        )
                    }
                ProjectDiagnostic(
                    projectPath = p.basePath ?: "",
                    projectName = p.name,
                    indexing = indexing,
                    notifications = notifications,
                    processes = collectRunningProcesses(p),
                    recentlyFinished = collectRecentlyFinished(p)
                )
            }
        }

        val logEntries = readIdeaLogErrors(minutesBack = minutesBack, level = level)

        return captureResponse(Json.encodeToString(IntellijDiagnostic(
            projects = entries,
            logEntries = logEntries
        )))
    }

    private fun readIdeaLogErrors(minutesBack: Int, level: String = "error"): List<String> {
        val logFile = java.io.File(PathManager.getLogPath(), "idea.log")
        if (!logFile.exists()) return emptyList()
        return try {
            val cutoff = System.currentTimeMillis() - minutesBack * 60_000L
            val raf = java.io.RandomAccessFile(logFile, "r")
            val fileLen = raf.length()
            val startPos = maxOf(0L, fileLen - 524288L)
            raf.seek(startPos)
            val bytes = ByteArray((fileLen - startPos).toInt())
            raf.read(bytes)
            raf.close()

            val lines = String(bytes, Charsets.UTF_8).lines()
            val headerRegex = Regex("""^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}),\d+""")
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

            data class Entry(val ts: Long, val isError: Boolean, val lines: MutableList<String>)
            val entries = mutableListOf<Entry>()

            for (line in lines) {
                val match = headerRegex.find(line)
                if (match != null) {
                    val ts = runCatching { sdf.parse(match.groupValues[1])?.time ?: 0L }.getOrDefault(0L)
                    val isError = when (level.lowercase()) {
                        "info" -> "ERROR" in line || "SEVERE" in line || "WARN" in line || " INFO" in line
                        "warn" -> "ERROR" in line || "SEVERE" in line || "WARN" in line
                        else   -> "ERROR" in line || "SEVERE" in line
                    }
                    entries += Entry(ts, isError, mutableListOf(line))
                } else {
                    entries.lastOrNull()?.lines?.add(line)
                }
            }

            entries
                .filter { it.isError && it.ts >= cutoff }
                .flatMap { it.lines }
                .take(300)
        } catch (_: Exception) { emptyList() }
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

@Serializable data class RunningProcess(val title: String, val details: String? = null, val progress: Double? = null, val cancellable: Boolean? = null, val paused: Boolean = false)
@Serializable data class ProjectProcessesSnapshot(
    val projectPath: String,
    val projectName: String,
    val running: List<RunningProcess>,
    val recentlyFinished: List<SnapshotFinishedProcess> = emptyList()
)
@Serializable data class RunningProcessesResult(val projects: List<ProjectProcessesSnapshot>)

@Serializable data class IdeSnapshot(val projects: List<ProjectSnapshot>)
@Serializable data class ProjectSnapshot(
    val projectPath: String,
    val projectName: String,
    val activeFile: String? = null,
    val selection: String? = null,
    val openFileCount: Int,
    val openFiles: List<String>,
    val activeFileProblems: SnapshotProblems? = null,
    val build: BuildSummary? = null,
    val runs: List<SnapshotRun>,
    val indexing: Boolean,
    val backgroundProcesses: List<SnapshotProcess>,
    val recentlyFinished: List<SnapshotFinishedProcess> = emptyList()
)
@Serializable data class SnapshotRun(val name: String, val mode: String, val status: String, val exitCode: Int? = null, val pausedAt: String? = null)
@Serializable data class SnapshotProblems(val errors: Int, val warnings: Int)
@Serializable data class BuildSummary(val errors: Int, val warnings: Int)
@Serializable data class SnapshotProcess(val title: String, val startedAgoMs: Long, val progress: Double? = null)
@Serializable data class SnapshotFinishedProcess(val title: String, val ranMs: Long, val endedAgoMs: Long)

@Serializable data class ProjectDiagnostic(
    val projectPath: String,
    val projectName: String,
    val indexing: IndexingStatus,
    val notifications: List<DiagnosticNotification>,
    val processes: List<RunningProcess>,
    val recentlyFinished: List<SnapshotFinishedProcess> = emptyList()
)
@Serializable data class IntellijDiagnostic(val projects: List<ProjectDiagnostic>, val logEntries: List<String>)
@Serializable data class IndexingStatus(val active: Boolean)
@Serializable data class DiagnosticNotification(val type: String, val title: String, val content: String? = null, val groupId: String? = null)
