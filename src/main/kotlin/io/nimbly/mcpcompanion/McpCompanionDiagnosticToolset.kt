package io.nimbly.mcpcompanion

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.DumbService
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

class McpCompanionDiagnosticToolset : McpToolset {

    override fun isEnabled(): Boolean = true

    private fun disabledMessage(toolName: String): String? {
        if (!McpCompanionSettings.getInstance().isEnabled(toolName))
            return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion."
        McpCompanionSettings.getInstance().trackCall(toolName)
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
    """)
    suspend fun get_ide_settings(search: String? = null, key: String? = null, prefix: String? = null, depth: Int? = null): String {
        disabledMessage("get_ide_settings")?.let { return it }
        val project = coroutineContext.project
        return invokeAndWaitIfNeeded {
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
        }
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
        Returns all background processes currently running in IntelliJ — the same tasks visible
        in the status bar (indexing, Gradle sync, compilation, inspections, etc.).
        For each process:
        - title: main label of the task
        - details: secondary line (current file, step, etc.) if available
        - progress: 0.0–1.0, or null if indeterminate
        - cancellable: whether the task can be cancelled
        Call this when IntelliJ seems busy, slow, or stuck to understand what it is doing.
    """)
    suspend fun get_running_processes(): String {
        disabledMessage("get_running_processes")?.let { return it }
        val processes = collectRunningProcesses()
        return if (processes.isEmpty()) "No background processes running"
        else Json.encodeToString(processes)
    }

    fun collectRunningProcesses(): List<RunningProcess> {
        val seen = mutableSetOf<String>()
        val processes = mutableListOf<RunningProcess>()

        for (ind in currentIndicators()) {
            if (!ind.isRunning) continue
            val title = ind.taskTitle() ?: ind.text?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(title)) continue
            val details = ind.text2?.takeIf { it.isNotBlank() }
            val progress = if (ind.isIndeterminate) null else ind.fraction.takeIf { it in 0.0..1.0 }
            val cancellable = ind.reflectBoolean("isCancellable")
            processes += RunningProcess(title = title, details = details, progress = progress, cancellable = cancellable, paused = false)
        }

        for ((ind, suspender) in allSuspenderEntries()) {
            if (!suspender.isSuspended()) continue
            val title = ind.taskTitle() ?: ind.text?.takeIf { it.isNotBlank() } ?: continue
            if (!seen.add("paused:$title")) continue
            val progress = if (ind.isIndeterminate) null else ind.fraction.takeIf { it in 0.0..1.0 }
            processes += RunningProcess(title = title, progress = progress, paused = true)
        }

        return processes
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
    """)
    suspend fun manage_process(title: String, action: String): String {
        disabledMessage("manage_process")?.let { return it }
        var match = currentIndicators().firstOrNull { ind ->
            if (!ind.isRunning) return@firstOrNull false
            val label = ind.taskTitle() ?: ind.text ?: ""
            label.contains(title, ignoreCase = true)
        }
        var matchedSuspender: Any? = match?.let { getSuspender(it) }
        if (match == null) {
            val entry = allSuspenderEntries().firstOrNull { (ind, _) ->
                val label = ind.taskTitle() ?: ind.text ?: ""
                label.contains(title, ignoreCase = true)
            }
            match = entry?.first
            matchedSuspender = entry?.second
        }
        if (match == null) return "No running process found matching \"$title\""

        val label = match.taskTitle() ?: match.text?.takeIf { it.isNotBlank() } ?: title
        return when (action.lowercase().trim()) {
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
        }
    }

    private fun ProgressIndicator.taskTitle(): String? = try {
        val taskInfo = javaClass.getMethod("getTaskInfo").invoke(this)
        taskInfo?.javaClass?.getMethod("getTitle")?.invoke(taskInfo) as? String
    } catch (_: Exception) { null }?.takeIf { it.isNotBlank() }

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

    @Suppress("UNCHECKED_CAST")
    private fun currentIndicators(): List<ProgressIndicator> = try {
        Class.forName("com.intellij.openapi.progress.impl.CoreProgressManager")
            .getMethod("getCurrentIndicators")
            .invoke(null) as? List<ProgressIndicator> ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private fun getSuspender(indicator: ProgressIndicator): Any? = try {
        val cls = Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        cls.getMethod("getSuspender", ProgressIndicator::class.java).invoke(null, indicator)
    } catch (_: Exception) { null }

    @Suppress("UNCHECKED_CAST")
    private fun allSuspenderEntries(): List<Pair<ProgressIndicator, Any>> = try {
        val cls = Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        val field = cls.getDeclaredField("ourProgressToSuspenderMap").also { it.isAccessible = true }
        val map = field.get(null) as? Map<ProgressIndicator, Any> ?: return emptyList()
        map.entries.map { it.key to it.value }
    } catch (_: Exception) { emptyList() }

    private fun Any.isSuspended(): Boolean =
        runCatching { javaClass.getMethod("isSuspended").invoke(this) as? Boolean ?: false }.getOrDefault(false)

    private fun Any.suspendedText(): String? =
        runCatching { javaClass.getMethod("getSuspendedText").invoke(this) as? String }.getOrNull()

    // ── get_intellij_diagnostic ───────────────────────────────────────────────

    @McpTool(name = "get_intellij_diagnostic")
    @McpDescription(description = """
        Returns IntelliJ diagnostic information in one call. Use this as a first step when
        something is not working as expected in IntelliJ.
        Returns:
        - indexing: whether the IDE is currently indexing files (DumbService)
        - notifications: active notifications visible in the Event Log (errors, warnings)
        - processes: background processes currently running or paused (same as get_running_processes)
        - logEntries: log entries from idea.log in the last N minutes, including stack traces
        minutesBack: how many minutes back to scan idea.log (default: 5)
        level: minimum log level to include — "error" (ERROR+SEVERE only), "warn" (adds WARN), "info" (adds INFO). Default: "error"
    """)
    suspend fun get_intellij_diagnostic(minutesBack: Int = 5, level: String = "error"): String {
        disabledMessage("get_intellij_diagnostic")?.let { return it }
        val project = coroutineContext.project

        val indexing = invokeAndWaitIfNeeded {
            IndexingStatus(active = DumbService.getInstance(project).isDumb)
        }

        val notifications = invokeAndWaitIfNeeded {
            NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(Notification::class.java, project)
                .map { n ->
                    DiagnosticNotification(
                        type = n.type.name,
                        title = n.title,
                        content = n.content.takeIf { it.isNotBlank() },
                        groupId = n.groupId.takeIf { it.isNotBlank() }
                    )
                }.toList()
        }

        val processes = collectRunningProcesses()

        val logEntries = readIdeaLogErrors(minutesBack = minutesBack, level = level)

        return Json.encodeToString(IntellijDiagnostic(
            indexing = indexing,
            notifications = notifications,
            processes = processes,
            logEntries = logEntries
        ))
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

@Serializable data class IntellijDiagnostic(val indexing: IndexingStatus, val notifications: List<DiagnosticNotification>, val processes: List<RunningProcess>, val logEntries: List<String>)
@Serializable data class IndexingStatus(val active: Boolean)
@Serializable data class DiagnosticNotification(val type: String, val title: String, val content: String? = null, val groupId: String? = null)
