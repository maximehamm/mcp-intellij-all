package io.nimbly.mcpcompanion

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.intellij.openapi.application.EDT
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

class McpCompanionCodeAnalysisToolset : McpToolset {

    override fun isEnabled(): Boolean = true

    private fun disabledMessage(toolName: String): String? {
        if (!McpCompanionSettings.getInstance().isEnabled(toolName))
            return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion."
        McpCompanionSettings.getInstance().trackCall(toolName)
        return null
    }

    internal fun relativize(basePath: String, path: String): String =
        if (basePath.isNotEmpty() && path.startsWith(basePath))
            path.removePrefix(basePath).trimStart('/')
        else path

    // ── get_file_problems ─────────────────────────────────────────────────────

    @McpTool(name = "get_file_problems")
    @McpDescription(description = """
        Returns IDE-detected problems (errors, warnings, infos) for a file or all open editors,
        including the quick fix suggestions available for each problem.
        filePath: path relative to project root (default: all open editors)
        severity: minimum severity — "error" (default), "warning", "all"
        Each problem includes: file, line, column, severity, message.
        Use get_quick_fixes to retrieve fix suggestions for a specific position.
    """)
    suspend fun get_file_problems(filePath: String? = null, severity: String = "error"): String {
        disabledMessage("get_file_problems")?.let { return it }
        val project = coroutineContext.project

        val minSeverity = when (severity.lowercase()) {
            "warning", "warn" -> HighlightSeverity.WARNING
            "all", "info"     -> HighlightSeverity.INFORMATION
            else              -> HighlightSeverity.ERROR
        }

        val files = invokeAndWaitIfNeeded {
            if (filePath != null) {
                val basePath = project.basePath ?: return@invokeAndWaitIfNeeded emptyList()
                val vFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath")
                    ?: return@invokeAndWaitIfNeeded emptyList()
                listOf(filePath to vFile)
            } else {
                FileEditorManager.getInstance(project).openFiles.map { vf ->
                    vf.path.removePrefix((project.basePath ?: "") + "/") to vf
                }
            }
        }

        if (files.isEmpty()) return if (filePath != null) "File not found: $filePath" else "No open editors"

        val problems = runReadAction {
            files.flatMap { (relPath, vFile) ->
                val document = FileDocumentManager.getInstance().getDocument(vFile)
                    ?: return@flatMap emptyList()
                val markupModel = DocumentMarkupModel.forDocument(document, project, false)
                    ?: return@flatMap emptyList()
                markupModel.allHighlighters
                    .mapNotNull { it.errorStripeTooltip as? HighlightInfo }
                    .filter { it.severity >= minSeverity && !it.description.isNullOrBlank() }
                    .map { info ->
                        val line = document.getLineNumber(info.startOffset) + 1
                        val col  = info.startOffset - document.getLineStartOffset(line - 1) + 1
                        FileProblem(
                            file     = relPath,
                            line     = line,
                            column   = col,
                            severity = info.severity.name,
                            message  = info.description
                        )
                    }
            }
        }

        if (problems.isEmpty()) return "No problems (severity >= ${minSeverity.name})"
        return Json.encodeToString(problems)
    }

    // ── get_quick_fixes ───────────────────────────────────────────────────────

    @McpTool(name = "get_quick_fixes")
    @McpDescription(description = """
        Returns quick fix and intention action suggestions at a specific position in a file.
        Uses the same actions as Alt+Enter / "Show Context Actions" in the IDE.
        ⚠ The file must be open in the editor — returns empty if not open.
        filePath: path relative to project root
        line: 1-based line number
        column: 1-based column number (default: 1)
    """)
    suspend fun get_quick_fixes(filePath: String, line: Int, column: Int = 1): String {
        disabledMessage("get_quick_fixes")?.let { return it }
        val project = coroutineContext.project
        val basePath = project.basePath ?: return "Cannot determine project base path"

        val vFile = invokeAndWaitIfNeeded {
            LocalFileSystem.getInstance().findFileByPath("$basePath/$filePath")
        } ?: return "File not found: $filePath"

        val isAlreadyOpen = invokeAndWaitIfNeeded {
            FileEditorManager.getInstance(project).openFiles.any { it.path == vFile.path }
        }
        if (!isAlreadyOpen) return "File is not open in the editor. Open it first (e.g. via navigate_to), wait a moment for the IDE to analyse it, then call get_quick_fixes again."

        // Check if the daemon is still running — if so, highlights (and fixes) are not ready yet
        val daemonRunning = runReadAction {
            runCatching {
                val daemon = DaemonCodeAnalyzer.getInstance(project)
                daemon.javaClass.getMethod("isRunning").invoke(daemon) as? Boolean ?: false
            }.getOrDefault(false)
        }
        if (daemonRunning) return "IDE is still analysing the file — inspections not ready yet. Wait a moment and call get_quick_fixes again."

        val document = invokeAndWaitIfNeeded { FileDocumentManager.getInstance().getDocument(vFile) }
            ?: return "Cannot read document for $filePath"

        if (line < 1 || line > document.lineCount) return "Line $line is out of range (file has ${document.lineCount} lines)"
        val lineStart = document.getLineStartOffset(line - 1)
        val offset    = (lineStart + column - 1).coerceIn(lineStart, document.getLineEndOffset(line - 1))

        val highlights = runReadAction {
            val markupModel = DocumentMarkupModel.forDocument(document, project, false)
            markupModel?.allHighlighters
                ?.mapNotNull { it.errorStripeTooltip as? HighlightInfo }
                ?.filter { it.severity >= HighlightSeverity.INFORMATION && it.startOffset <= offset && it.endOffset >= offset }
                ?: emptyList()
        }
        if (highlights.isEmpty()) return "No quick fixes found at $filePath:$line:$column"

        fun findField(obj: Any, name: String) = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.find { it.name == name }?.also { it.isAccessible = true }
        fun findDeclaredMethod(obj: Any, name: String, vararg params: Class<*>) = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }.find { m -> m.name == name && m.parameterTypes.contentEquals(params) }?.also { it.isAccessible = true }

        val fixes = invokeAndWaitIfNeeded {
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile), false)
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile)

            highlights.flatMap { info ->
                runCatching {
                    val offsetStore = findField(info, "offsetStore")?.get(info) ?: return@runCatching emptyList<String>()
                    val descriptors = findDeclaredMethod(info, "getIntentionActionDescriptors", offsetStore.javaClass)
                        ?.invoke(info, offsetStore) as? List<*> ?: return@runCatching emptyList()
                    descriptors.mapNotNull { desc ->
                        runCatching {
                            val action = desc?.javaClass?.getMethod("getAction")?.invoke(desc) ?: return@runCatching null
                            if (editor != null && psiFile != null) {
                                runCatching {
                                    action.javaClass.methods.find { it.name == "isAvailable" && it.parameterCount == 3 }
                                        ?.invoke(action, project, editor, psiFile)
                                }
                            }
                            action.javaClass.getMethod("getText").invoke(action)?.toString()
                        }.getOrNull()?.takeIf { it.isNotBlank() && it != "(not initialized)" }
                    }
                }.getOrDefault(emptyList())
            }.distinct()
        }

        if (fixes.isEmpty()) return "No quick fixes found at $filePath:$line:$column"
        return Json.encodeToString(fixes)
    }

    // ── refresh_project ───────────────────────────────────────────────────────

    @McpTool(name = "refresh_project")
    @McpDescription(description = """
        Refreshes (reimports/syncs) the project build system configuration.
        Automatically detects Gradle or Maven from the project root and triggers the appropriate sync action.
        Use this after modifying build.gradle, pom.xml, settings.gradle, or when dependencies are out of sync.
        Returns what was triggered, or an error if no build system is detected.
    """)
    suspend fun refresh_project(): String {
        disabledMessage("refresh_project")?.let { return it }
        val project = coroutineContext.project
        val basePath = project.basePath ?: return "Cannot determine project base path"
        val am = ActionManager.getInstance()

        val rootFiles = java.io.File(basePath).listFiles()?.map { it.name } ?: emptyList()
        val hasGradle = rootFiles.any { it in listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") }
        val hasMaven = "pom.xml" in rootFiles

        if (!hasGradle && !hasMaven)
            return "No Gradle or Maven build files found in project root ($basePath)"

        val results = mutableListOf<String>()

        fun triggerAction(actionId: String, label: String) {
            val action = invokeAndWaitIfNeeded { am.getAction(actionId) } ?: run {
                results += "$label: action '$actionId' not available in this IDE"
                return
            }
            ApplicationManager.getApplication().invokeLater {
                val dataContext = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
                    .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
                    .build()
                @Suppress("DEPRECATION")
                val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
                    com.intellij.openapi.actionSystem.ActionPlaces.UNKNOWN,
                    action.templatePresentation.clone(),
                    dataContext
                )
                action.actionPerformed(event)
            }
            results += "$label sync triggered"
        }

        if (hasGradle) triggerAction("ExternalSystem.RefreshAllProjects", "Gradle")
        if (hasMaven)  triggerAction("Maven.Reimport", "Maven")

        return results.joinToString("\n")
    }

    // ── get_project_structure ─────────────────────────────────────────────────

    @McpTool(name = "get_project_structure")
    @McpDescription(description = """
        Returns the structure of the IntelliJ project: active SDK (with homePath), all SDKs registered
        in IntelliJ (with homePath, useful to suggest switching), modules, source roots, excluded folders,
        and module-to-module dependencies.
        Useful to understand the project layout (where sources, tests, and resources live) before
        navigating or editing files.
        Source root types: source, test, resource, testResource.
        All paths are relative to the project root.
    """)
    suspend fun get_project_structure(): String {
        disabledMessage("get_project_structure")?.let { return it }
        val project = coroutineContext.project
        return runReadAction {
            val basePath = project.basePath ?: ""
            val sdk = ProjectRootManager.getInstance(project).projectSdk?.let { sdk ->
                SdkInfo(name = sdk.name, type = sdk.sdkType.name, version = sdk.versionString, homePath = sdk.homePath)
            }
            val availableSdks = ProjectJdkTable.getInstance().allJdks.map { s ->
                SdkInfo(name = s.name, type = s.sdkType.name, version = s.versionString, homePath = s.homePath)
            }
            val modules = ModuleManager.getInstance(project).modules.map { module ->
                val rootManager = ModuleRootManager.getInstance(module)
                val sourceRoots = rootManager.contentEntries.flatMap { entry ->
                    entry.sourceFolders.map { sf ->
                        val path = sf.file?.path?.let { relativize(basePath, it) } ?: sf.url
                        val rootTypeStr = sf.rootType.toString().lowercase()
                        val type = when {
                            "resource" in rootTypeStr && sf.isTestSource -> "testResource"
                            "resource" in rootTypeStr -> "resource"
                            sf.isTestSource -> "test"
                            else -> "source"
                        }
                        SourceRootInfo(path = path, type = type)
                    }
                }
                val excluded = rootManager.contentEntries.flatMap { entry ->
                    entry.excludeFolders.mapNotNull { ef ->
                        ef.file?.path?.let { relativize(basePath, it) } ?: ef.url
                    }
                }
                val deps = rootManager.dependencies.map { it.name }
                val moduleType = try { ModuleType.get(module).id } catch (_: Exception) { null }
                ModuleInfo(
                    name = module.name,
                    type = moduleType,
                    sourceRoots = sourceRoots,
                    excludedFolders = excluded.ifEmpty { null },
                    dependencies = deps.ifEmpty { null }
                )
            }
            Json.encodeToString(ProjectStructure(
                name = project.name,
                basePath = basePath,
                sdk = sdk,
                availableSdks = availableSdks,
                modules = modules
            ))
        }
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

@Serializable data class FileProblem(val file: String, val line: Int, val column: Int, val severity: String, val message: String)

@Serializable data class ProjectStructure(val name: String, val basePath: String, val sdk: SdkInfo?, val availableSdks: List<SdkInfo>, val modules: List<ModuleInfo>)
@Serializable data class SdkInfo(val name: String, val type: String, val version: String?, val homePath: String? = null)
@Serializable data class ModuleInfo(val name: String, val type: String? = null, val sourceRoots: List<SourceRootInfo>, val excludedFolders: List<String>? = null, val dependencies: List<String>? = null)
@Serializable data class SourceRootInfo(val path: String, val type: String)
