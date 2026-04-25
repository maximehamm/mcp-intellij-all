package io.nimbly.mcpcompanion

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.clientInfo
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskState
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.service.project.ProjectDataManager
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Gradle-related MCP tools.
 *
 * This class is registered via `mcp-companion-gradle.xml`, which is loaded ONLY when the
 * `com.intellij.gradle` plugin is present (see optional dependency in plugin.xml).
 * Users on IDEs without Gradle (PyCharm, WebStorm, …) never load this class — no risk of
 * `NoClassDefFoundError`.
 */
class McpCompanionGradleToolset : McpToolset {

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

    /** Resolves a Gradle project path: explicit `gradleProjectPath` > first linked Gradle project > project base path. */
    private fun resolveGradleProjectPath(project: Project, gradleProjectPath: String?): String? {
        if (!gradleProjectPath.isNullOrBlank()) return gradleProjectPath
        val linked = GradleSettings.getInstance(project).linkedProjectsSettings
        if (linked.isNotEmpty()) return linked.first().externalProjectPath
        return project.basePath
    }

    private fun listLinkedGradleProjectPaths(project: Project): List<String> =
        GradleSettings.getInstance(project).linkedProjectsSettings.map { it.externalProjectPath }

    // ── run_gradle_task ───────────────────────────────────────────────────────

    @McpTool(name = "run_gradle_task")
    @McpDescription(description = """
        Executes one or more Gradle tasks in the IDE's Gradle integration (same as clicking
        a task in the Gradle tool window). Captures stdout/stderr and returns a structured
        result. Use get_gradle_tasks first to discover available task names.

        Parameters:
        - tasks: list of task names (e.g. ["clean", "build"], ["test", "--tests", "Foo*"] is wrong —
          flags go to "args"). For sub-projects use ":sub:taskName".
        - args: extra Gradle arguments (e.g. ["--info", "--stacktrace", "-Pversion=1.2.3", "--tests", "FooTest"])
        - gradleProjectPath: absolute path of a specific linked Gradle project (defaults to the first
          linked Gradle project in the IDE). Useful only when the IntelliJ project links several Gradle roots.
        - timeoutSeconds: max wait for completion (default 600). The build keeps running on timeout —
          use stop_gradle_task to cancel.

        projectPath: absolute path of the target IntelliJ project's root — defaults to the currently-focused project.
    """)
    suspend fun run_gradle_task(
        tasks: List<String>,
        args: List<String> = emptyList(),
        gradleProjectPath: String? = null,
        timeoutSeconds: Int = 600,
        projectPath: String? = null
    ): String {
        disabledMessage("run_gradle_task")?.let { return it }
        if (tasks.isEmpty()) return "Error: 'tasks' must contain at least one task name."

        val project = resolveProject(projectPath)
        val gradleRoot = resolveGradleProjectPath(project, gradleProjectPath)
            ?: return "Error: no linked Gradle project found in '${project.name}'. Open the project in IntelliJ and let Gradle import finish."

        return withContext(Dispatchers.IO) {
            try {
                val settings = ExternalSystemTaskExecutionSettings().apply {
                    externalProjectPath = gradleRoot
                    externalSystemIdString = GradleConstants.SYSTEM_ID.id
                    taskNames = tasks
                    scriptParameters = args.joinToString(" ")
                }

                val latch = CountDownLatch(1)
                var success = false
                val error = StringBuilder()

                val callback = object : TaskCallback {
                    override fun onSuccess() { success = true; latch.countDown() }
                    override fun onFailure() { success = false; latch.countDown() }
                }

                val started = System.currentTimeMillis()
                ExternalSystemUtil.runTask(
                    settings,
                    DefaultRunExecutor.EXECUTOR_ID,
                    project,
                    GradleConstants.SYSTEM_ID,
                    callback,
                    ProgressExecutionMode.IN_BACKGROUND_ASYNC
                )

                val finished = latch.await(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                val durationMs = System.currentTimeMillis() - started

                Json.encodeToString(
                    GradleRunResult(
                        status = when {
                            !finished -> "timeout"
                            success -> "success"
                            else -> "failure"
                        },
                        tasks = tasks,
                        args = args,
                        durationMs = durationMs,
                        gradleProjectPath = gradleRoot,
                        message = when {
                            !finished -> "Build still running after ${timeoutSeconds}s — use stop_gradle_task to cancel."
                            success -> "Build successful. See the Run/Build tool window or call get_console_output for full output."
                            else -> "Build failed. See the Run/Build tool window or call get_console_output for full output."
                        }
                    )
                )
            } catch (e: Exception) {
                "Error running Gradle task: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── get_gradle_tasks ──────────────────────────────────────────────────────

    @McpTool(name = "get_gradle_tasks")
    @McpDescription(description = """
        Lists all Gradle tasks discovered by IntelliJ for the linked Gradle project(s),
        grouped by category (build, verification, help, other, or custom group declared
        in the build script). Returned tasks come from the IDE's imported model — if the
        project hasn't been imported yet, call refresh_gradle_project first.

        gradleProjectPath: absolute path of a specific linked Gradle project. If omitted,
        tasks from all linked Gradle projects are returned.

        projectPath: absolute path of the target IntelliJ project's root — defaults to the currently-focused project.
    """)
    suspend fun get_gradle_tasks(gradleProjectPath: String? = null, projectPath: String? = null): String {
        disabledMessage("get_gradle_tasks")?.let { return it }
        val project = resolveProject(projectPath)

        return withContext(Dispatchers.IO) {
            try {
                val roots = if (!gradleProjectPath.isNullOrBlank())
                    listOf(gradleProjectPath)
                else
                    listLinkedGradleProjectPaths(project)

                if (roots.isEmpty())
                    return@withContext "No linked Gradle project in '${project.name}'. Open or import a Gradle project first."

                val byGroup = linkedMapOf<String, MutableList<GradleTaskInfo>>()
                for (root in roots) {
                    val data = ProjectDataManager.getInstance()
                        .getExternalProjectData(project, GradleConstants.SYSTEM_ID, root)
                    val projectStructure = data?.externalProjectStructure
                        ?: continue

                    // Walk all module nodes and collect their TASK children.
                    val moduleNodes = ExternalSystemApiUtil.findAll(projectStructure, ProjectKeys.MODULE)
                    for (moduleNode in moduleNodes) {
                        val moduleName = moduleNode.data.externalName
                        val tasks = ExternalSystemApiUtil.findAll(moduleNode, ProjectKeys.TASK)
                        for (taskNode in tasks) {
                            val td = taskNode.data
                            val groupName = (td.group ?: "other").lowercase().ifBlank { "other" }
                            byGroup.getOrPut(groupName) { mutableListOf() }.add(
                                GradleTaskInfo(
                                    name = td.name,
                                    description = td.description?.takeIf { it.isNotBlank() },
                                    project = moduleName
                                )
                            )
                        }
                    }
                }

                if (byGroup.isEmpty())
                    return@withContext "No tasks found. The Gradle project may not be imported yet — try refresh_gradle_project."

                // Stable ordering: known groups first, then the rest alphabetically.
                val knownOrder = listOf("build", "verification", "help", "documentation", "other", "ide")
                val ordered = linkedMapOf<String, List<GradleTaskInfo>>()
                for (k in knownOrder) byGroup[k]?.let { ordered[k] = it.sortedBy { t -> t.name } }
                byGroup.keys.filter { it !in knownOrder }.sorted()
                    .forEach { ordered[it] = byGroup.getValue(it).sortedBy { t -> t.name } }

                Json.encodeToString(GradleTasks(groups = ordered))
            } catch (e: Exception) {
                "Error listing Gradle tasks: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── refresh_gradle_project ────────────────────────────────────────────────

    @McpTool(name = "refresh_gradle_project")
    @McpDescription(description = """
        Forces a Gradle re-sync (equivalent of the refresh button in the Gradle tool window).
        Call this after editing build.gradle / build.gradle.kts / settings.gradle (added a
        dependency, plugin, …) so IntelliJ picks up the changes.

        Returns immediately after triggering the sync — the actual import happens asynchronously
        in the background; watch the Build / Sync tool window for progress.

        gradleProjectPath: absolute path of a specific linked Gradle project. If omitted,
        all linked Gradle projects are refreshed.

        projectPath: absolute path of the target IntelliJ project's root — defaults to the currently-focused project.
    """)
    suspend fun refresh_gradle_project(gradleProjectPath: String? = null, projectPath: String? = null): String {
        disabledMessage("refresh_gradle_project")?.let { return it }
        val project = resolveProject(projectPath)

        return withContext(Dispatchers.IO) {
            try {
                val roots = if (!gradleProjectPath.isNullOrBlank())
                    listOf(gradleProjectPath)
                else
                    listLinkedGradleProjectPaths(project)

                if (roots.isEmpty())
                    return@withContext "No linked Gradle project in '${project.name}'."

                for (root in roots) {
                    ExternalSystemUtil.refreshProject(
                        root,
                        ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
                            .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                    )
                }
                "Triggered Gradle re-sync for ${roots.size} project(s): ${roots.joinToString(", ")}. Watch the Build tool window for progress."
            } catch (e: Exception) {
                "Error refreshing Gradle project: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── get_gradle_dependencies ───────────────────────────────────────────────

    @McpTool(name = "get_gradle_dependencies")
    @McpDescription(description = """
        Returns the dependency tree for each module of the linked Gradle project, as already
        imported by IntelliJ. For each module: list of library and module dependencies with
        scope (compile/test/…) and resolved version.

        ⚠ Returns the imported model — if you've just edited build.gradle.kts, call
        refresh_gradle_project first.

        gradleProjectPath: absolute path of a specific linked Gradle project. If omitted,
        dependencies of all linked Gradle projects are returned.
        scope: filter by scope (e.g. "compile", "test", "runtime"). Default: all scopes.

        projectPath: absolute path of the target IntelliJ project's root — defaults to the currently-focused project.
    """)
    suspend fun get_gradle_dependencies(
        gradleProjectPath: String? = null,
        scope: String? = null,
        projectPath: String? = null
    ): String {
        disabledMessage("get_gradle_dependencies")?.let { return it }
        val project = resolveProject(projectPath)

        return withContext(Dispatchers.IO) {
            try {
                val roots = if (!gradleProjectPath.isNullOrBlank())
                    listOf(gradleProjectPath)
                else
                    listLinkedGradleProjectPaths(project)

                if (roots.isEmpty())
                    return@withContext "No linked Gradle project in '${project.name}'."

                val modules = mutableListOf<GradleModuleDeps>()
                val scopeFilter = scope?.lowercase()?.takeIf { it.isNotBlank() }

                for (root in roots) {
                    val data = ProjectDataManager.getInstance()
                        .getExternalProjectData(project, GradleConstants.SYSTEM_ID, root)
                    val projectStructure = data?.externalProjectStructure ?: continue

                    // Walk the tree recursively. In modern Gradle Java setups, dependencies are
                    // attached not to the top-level Module node but to its child SourceSet nodes
                    // (one per source set: main, test, …). We aggregate deps per top-level module.
                    val moduleNodes = ExternalSystemApiUtil.findAll(projectStructure, ProjectKeys.MODULE)
                    for (moduleNode in moduleNodes) {
                        val moduleName = moduleNode.data.externalName
                        val deps = mutableListOf<GradleDep>()

                        // Recursive walker collecting LIBRARY_DEPENDENCY + MODULE_DEPENDENCY at any depth.
                        fun collect(node: com.intellij.openapi.externalSystem.model.DataNode<*>) {
                            for (child in node.children) {
                                when (child.key) {
                                    ProjectKeys.LIBRARY_DEPENDENCY -> {
                                        val d = child.data as com.intellij.openapi.externalSystem.model.project.LibraryDependencyData
                                        val depScope = d.scope.name.lowercase()
                                        if (scopeFilter == null || scopeFilter == depScope) {
                                            deps.add(GradleDep(
                                                name = d.target.externalName,
                                                scope = depScope,
                                                type = "library"
                                            ))
                                        }
                                    }
                                    ProjectKeys.MODULE_DEPENDENCY -> {
                                        val d = child.data as com.intellij.openapi.externalSystem.model.project.ModuleDependencyData
                                        val depScope = d.scope.name.lowercase()
                                        if (scopeFilter == null || scopeFilter == depScope) {
                                            deps.add(GradleDep(
                                                name = d.target.externalName,
                                                scope = depScope,
                                                type = "module"
                                            ))
                                        }
                                    }
                                }
                                collect(child)
                            }
                        }
                        collect(moduleNode)

                        // Deduplicate: same dep can appear in compileClasspath + runtimeClasspath of the same source set.
                        val uniqueDeps = deps.distinctBy { Triple(it.name, it.scope, it.type) }

                        if (uniqueDeps.isNotEmpty()) {
                            modules.add(GradleModuleDeps(
                                module = moduleName,
                                dependencies = uniqueDeps.sortedWith(compareBy({ it.scope }, { it.name }))
                            ))
                        }
                    }
                }

                if (modules.isEmpty())
                    return@withContext "No dependencies found${scopeFilter?.let { " for scope '$it'" } ?: ""}. The project may not be imported yet — try refresh_gradle_project."

                Json.encodeToString(GradleDependencies(modules = modules))
            } catch (e: Exception) {
                "Error listing Gradle dependencies: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── stop_gradle_task ──────────────────────────────────────────────────────

    @McpTool(name = "stop_gradle_task")
    @McpDescription(description = """
        Cancels all currently-running Gradle tasks in this project (equivalent of clicking
        the red stop button in the Run/Build tool window).

        Returns the number of tasks that were cancelled.

        projectPath: absolute path of the target IntelliJ project's root — defaults to the currently-focused project.
    """)
    suspend fun stop_gradle_task(projectPath: String? = null): String {
        disabledMessage("stop_gradle_task")?.let { return it }
        val project = resolveProject(projectPath)

        return withContext(Dispatchers.IO) {
            try {
                val pm = ExternalSystemProcessingManager.getInstance()
                // 1) Cancel via ExternalSystemProcessingManager (covers tasks tracked there).
                val tracked = pm.findTasksOfState(
                    GradleConstants.SYSTEM_ID,
                    ExternalSystemTaskState.NOT_STARTED,
                    ExternalSystemTaskState.IN_PROGRESS
                )
                var cancelled = 0
                for (task in tracked) {
                    if (task.cancel()) cancelled++
                }

                // 2) Also stop any running Gradle process via the standard Run/Build tool windows.
                //    ExternalSystemUtil.runTask routes through the Run system, so the process handler
                //    is the most reliable cancel point for builds initiated by run_gradle_task.
                var killedProcesses = 0
                try {
                    val em = com.intellij.execution.ExecutionManager.getInstance(project)
                    val running = em.getRunningProcesses()
                    for (handler in running) {
                        if (handler.isProcessTerminated || handler.isProcessTerminating) continue
                        // Identify Gradle processes via the CommandLineInfo system property or class name heuristic.
                        val name = handler.javaClass.name
                        if ("Gradle" in name || "External" in name || handler.toString().contains("gradle", ignoreCase = true)) {
                            handler.destroyProcess()
                            killedProcesses++
                        }
                    }
                } catch (_: Throwable) { /* fall through */ }

                val total = cancelled + killedProcesses
                if (total == 0) "No running Gradle task to cancel."
                else "Cancelled $total Gradle task(s) ($cancelled tracked + $killedProcesses process(es))."
            } catch (e: Exception) {
                "Error cancelling Gradle task: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── get_gradle_project_info ───────────────────────────────────────────────

    @McpTool(name = "get_gradle_project_info")
    @McpDescription(description = """
        Returns Gradle-level information about the linked Gradle project(s):
        - distribution type (WRAPPED / LOCAL / BUNDLED / DEFAULT_WRAPPED)
        - Gradle wrapper version (parsed from gradle/wrapper/gradle-wrapper.properties)
        - JDK / JVM used by Gradle (gradleJvm setting)
        - subprojects with their relative path and a list of source sets
        Useful to reason about monorepo structure and Gradle setup before suggesting
        commands or edits to build.gradle / settings.gradle.

        gradleProjectPath: absolute path of a specific linked Gradle project. If omitted,
        info for all linked Gradle projects is returned.

        projectPath: absolute path of the target IntelliJ project's root — defaults to the currently-focused project.
    """)
    suspend fun get_gradle_project_info(gradleProjectPath: String? = null, projectPath: String? = null): String {
        disabledMessage("get_gradle_project_info")?.let { return it }
        val project = resolveProject(projectPath)

        return withContext(Dispatchers.IO) {
            try {
                val settings = GradleSettings.getInstance(project)
                val candidates = if (!gradleProjectPath.isNullOrBlank())
                    settings.linkedProjectsSettings.filter { it.externalProjectPath == gradleProjectPath }
                else
                    settings.linkedProjectsSettings.toList()

                if (candidates.isEmpty())
                    return@withContext "No linked Gradle project in '${project.name}'${gradleProjectPath?.let { " matching '$it'" } ?: ""}."

                val results = candidates.map { gps ->
                    val rootPath = gps.externalProjectPath
                    val distributionType = gps.distributionType?.name
                    val gradleJvm = gps.gradleJvm
                    val gradleHome = gps.gradleHome

                    // Wrapper version from gradle-wrapper.properties (distributionUrl line).
                    val wrapperVersion = readWrapperVersion(rootPath)

                    // Subprojects + source sets from the imported model.
                    val data = ProjectDataManager.getInstance()
                        .getExternalProjectData(project, GradleConstants.SYSTEM_ID, rootPath)
                    val structure = data?.externalProjectStructure
                    val subprojects = mutableListOf<GradleSubproject>()
                    if (structure != null) {
                        val moduleNodes = ExternalSystemApiUtil.findAll(structure, ProjectKeys.MODULE)
                        for (m in moduleNodes) {
                            val md = m.data
                            // Source sets — collect names from any GradleSourceSetData child.
                            // GradleSourceSetData extends platform ModuleData, so a safe cast works
                            // without depending on the Gradle plugin class directly.
                            val sourceSets = mutableListOf<String>()
                            for (child in m.children) {
                                val keyName = child.key.dataType
                                if ("SourceSet" in keyName) {
                                    val ext = (child.data as? com.intellij.openapi.externalSystem.model.project.ModuleData)?.externalName
                                    if (ext != null) {
                                        // externalName like ":app:main" → take the last segment.
                                        sourceSets.add(ext.substringAfterLast(':'))
                                    }
                                }
                            }
                            subprojects.add(GradleSubproject(
                                name = md.externalName,
                                path = md.linkedExternalProjectPath,
                                sourceSets = sourceSets.ifEmpty { null }
                            ))
                        }
                    }

                    GradleProjectInfo(
                        rootPath = rootPath,
                        distributionType = distributionType,
                        wrapperVersion = wrapperVersion,
                        gradleHome = gradleHome,
                        gradleJvm = gradleJvm,
                        subprojects = subprojects.sortedBy { it.name }
                    )
                }
                Json.encodeToString(GradleProjectInfoResponse(projects = results))
            } catch (e: Exception) {
                "Error reading Gradle project info: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    private fun readWrapperVersion(rootPath: String): String? {
        val propsFile = java.io.File(rootPath, "gradle/wrapper/gradle-wrapper.properties")
        if (!propsFile.exists()) return null
        return try {
            val props = java.util.Properties().apply { propsFile.inputStream().use { load(it) } }
            val url = props.getProperty("distributionUrl") ?: return null
            // distributionUrl example: https\://services.gradle.org/distributions/gradle-9.3.0-bin.zip
            Regex("gradle-([0-9.]+(?:-[a-z0-9]+)?)-(?:bin|all)\\.zip").find(url)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

@Serializable
private data class GradleRunResult(
    val status: String,
    val tasks: List<String>,
    val args: List<String>,
    val durationMs: Long,
    val gradleProjectPath: String,
    val message: String
)

@Serializable
private data class GradleTaskInfo(
    val name: String,
    val description: String? = null,
    val project: String
)

@Serializable
private data class GradleTasks(val groups: Map<String, List<GradleTaskInfo>>)

@Serializable
private data class GradleDep(val name: String, val scope: String, val type: String)

@Serializable
private data class GradleModuleDeps(val module: String, val dependencies: List<GradleDep>)

@Serializable
private data class GradleDependencies(val modules: List<GradleModuleDeps>)

@Serializable
private data class GradleSubproject(
    val name: String,
    val path: String? = null,
    val sourceSets: List<String>? = null
)

@Serializable
private data class GradleProjectInfo(
    val rootPath: String,
    val distributionType: String? = null,
    val wrapperVersion: String? = null,
    val gradleHome: String? = null,
    val gradleJvm: String? = null,
    val subprojects: List<GradleSubproject>
)

@Serializable
private data class GradleProjectInfoResponse(val projects: List<GradleProjectInfo>)
