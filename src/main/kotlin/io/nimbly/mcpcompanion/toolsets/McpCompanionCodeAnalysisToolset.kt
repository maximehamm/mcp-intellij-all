package io.nimbly.mcpcompanion.toolsets

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.clientInfo
import com.intellij.mcpserver.project
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectRootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.intellij.openapi.application.EDT
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext
import io.nimbly.mcpcompanion.util.resolveFilePath
import io.nimbly.mcpcompanion.util.resolveProject
import io.nimbly.mcpcompanion.util.runOnEdt
import io.nimbly.mcpcompanion.McpCompanionSettings

class McpCompanionCodeAnalysisToolset : McpToolset {

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
        Each problem includes: file, line, column, severity, message, fixes.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_file_problems(filePath: String? = null, severity: String = "error", projectPath: String? = null): String {
        disabledMessage("get_file_problems")?.let { return it }
        val project = resolveProject(projectPath)

        val minSeverity = when (severity.lowercase()) {
            "warning", "warn" -> HighlightSeverity.WARNING
            "all", "info"     -> HighlightSeverity.INFORMATION
            else              -> HighlightSeverity.ERROR
        }

        val files = runOnEdt {
            if (filePath != null) {
                val vFile = resolveFilePath(project, filePath)
                    ?: return@runOnEdt emptyList()
                listOf(filePath to vFile)
            } else {
                FileEditorManager.getInstance(project).openFiles.map { vf ->
                    vf.path.removePrefix((project.basePath ?: "") + "/") to vf
                }
            }
        }

        if (files.isEmpty()) return if (filePath != null) "File not found: $filePath" else "No open editors"

        fun findField(obj: Any, name: String) = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.find { it.name == name }?.also { it.isAccessible = true }
        fun findDeclaredMethod(obj: Any, name: String, vararg params: Class<*>) = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }.find { m -> m.name == name && m.parameterTypes.contentEquals(params) }?.also { it.isAccessible = true }
        fun extractFixes(info: HighlightInfo, editor: com.intellij.openapi.editor.Editor?, psiFile: com.intellij.psi.PsiFile?): List<String> =
            runCatching {
                val offsetStore  = findField(info, "offsetStore")?.get(info) ?: return emptyList()
                val descriptors  = findDeclaredMethod(info, "getIntentionActionDescriptors", offsetStore.javaClass)
                    ?.invoke(info, offsetStore) as? List<*> ?: return emptyList()
                descriptors.mapNotNull { desc ->
                    runCatching {
                        val action = desc?.javaClass?.getMethod("getAction")?.invoke(desc) ?: return@runCatching null
                        if (editor != null && psiFile != null)
                            runCatching { action.javaClass.methods.find { it.name == "isAvailable" && it.parameterCount == 3 }?.invoke(action, project, editor, psiFile) }
                        action.javaClass.getMethod("getText").invoke(action)?.toString()
                    }.getOrNull()?.takeIf { it.isNotBlank() && it != "(not initialized)" }
                }
            }.getOrDefault(emptyList())

        val problems = runOnEdt {
            files.flatMap { (relPath, vFile) ->
                val document = FileDocumentManager.getInstance().getDocument(vFile)
                    ?: return@flatMap emptyList()
                val markupModel = DocumentMarkupModel.forDocument(document, project, false)
                    ?: return@flatMap emptyList()
                val editor  = FileEditorManager.getInstance(project).openTextEditor(
                    com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile), false)
                val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile)
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
                            message  = info.description,
                            fixes    = extractFixes(info, editor, psiFile)
                        )
                    }
            }
        }

        if (problems.isEmpty()) return "No problems (severity >= ${minSeverity.name})"
        return captureResponse(Json.encodeToString(problems))
    }

    // ── get_quick_fixes ───────────────────────────────────────────────────────

    @McpTool(name = "get_quick_fixes")
    @McpDescription(description = """
        Returns quick fix and intention action suggestions for a file (like Alt+Enter in the IDE).
        ⚠ The file must be open in the editor — use navigate_to first if needed.
        filePath: path relative to project root
        line: 1-based line number to restrict to a specific line; 0 (default) = whole file
        column: 1-based column, used only together with line > 0 (default: 1)
        Results are grouped by line: {"line": 5, "message": "...", "fixes": ["Fix A", "Fix B"]}

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_quick_fixes(filePath: String, line: Int = 0, column: Int = 1, projectPath: String? = null): String {
        disabledMessage("get_quick_fixes")?.let { return it }
        val project = resolveProject(projectPath)

        val vFile = runOnEdt { resolveFilePath(project, filePath) }
            ?: return "File not found: $filePath\nTried relative to: ${project.basePath}"

        val isAlreadyOpen = runOnEdt {
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

        val document = runOnEdt { FileDocumentManager.getInstance().getDocument(vFile) }
            ?: return "Cannot read document for $filePath"

        // Determine the search range: whole file (line=0) or a specific line
        val rangeStart: Int
        val rangeEnd: Int
        if (line <= 0) {
            rangeStart = 0
            rangeEnd   = document.textLength
        } else {
            if (line > document.lineCount) return "Line $line is out of range (file has ${document.lineCount} lines)"
            rangeStart = document.getLineStartOffset(line - 1)
            rangeEnd   = document.getLineEndOffset(line - 1)
        }

        // Collect all highlights that overlap with the search range (not just exact offset match)
        val highlights = runReadAction {
            val markupModel = DocumentMarkupModel.forDocument(document, project, false)
            markupModel?.allHighlighters
                ?.mapNotNull { it.errorStripeTooltip as? HighlightInfo }
                ?.filter { it.severity >= HighlightSeverity.INFORMATION
                        && it.startOffset < rangeEnd
                        && it.endOffset > rangeStart }
                ?: emptyList()
        }

        fun findField(obj: Any, name: String) = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.find { it.name == name }?.also { it.isAccessible = true }
        fun findDeclaredMethod(obj: Any, name: String, vararg params: Class<*>) = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }.find { m -> m.name == name && m.parameterTypes.contentEquals(params) }?.also { it.isAccessible = true }

        @Serializable
        data class FixGroup(val line: Int, val message: String, val fixes: List<String>)

        val groups = runOnEdt {
            val editor = FileEditorManager.getInstance(project)
                .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile), false)
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile)

            // 1. Fixes attached to highlight markers (errors, warnings, hints)
            val fromHighlights = highlights.mapNotNull { info ->
                runCatching {
                    val offsetStore = findField(info, "offsetStore")?.get(info) ?: return@runCatching null
                    val descriptors = findDeclaredMethod(info, "getIntentionActionDescriptors", offsetStore.javaClass)
                        ?.invoke(info, offsetStore) as? List<*> ?: return@runCatching null
                    val fixTexts = descriptors.mapNotNull { desc ->
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
                    if (fixTexts.isEmpty()) null
                    else FixGroup(
                        line    = document.getLineNumber(info.startOffset) + 1,
                        message = info.description?.takeIf { it.isNotBlank() } ?: info.severity.name,
                        fixes   = fixTexts
                    )
                }.getOrNull()
            }

            val allGroups = fromHighlights.toMutableList()
            allGroups
        }

        if (groups.isEmpty()) return "No quick fixes found in $filePath" + if (line > 0) " at line $line" else ""
        return captureResponse(Json.encodeToString(groups))
    }

    // ── apply_quick_fix ───────────────────────────────────────────────────────

    @McpTool(name = "apply_quick_fix")
    @McpDescription(description = """
        Applies a quick fix or intention action to a problem in a file.
        The file must be open in the editor.
        filePath: path relative to project root
        fixText: exact text of the fix to apply (as returned by get_file_problems or get_quick_fixes)
        line: restrict search to a specific 1-based line (0 or omitted = whole file, recommended)
        If multiple fixes match the text, the first one is applied.
        Returns a confirmation message or an error if the fix was not found.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun apply_quick_fix(filePath: String, fixText: String, line: Int = 0, projectPath: String? = null): String {
        disabledMessage("apply_quick_fix")?.let { return it }
        val project = resolveProject(projectPath)

        val vFile = runOnEdt { resolveFilePath(project, filePath) }
            ?: return "File not found: $filePath\nTried relative to: ${project.basePath}"

        val isAlreadyOpen = runOnEdt {
            FileEditorManager.getInstance(project).openFiles.any { it.path == vFile.path }
        }
        if (!isAlreadyOpen) return "File is not open in the editor. Open it first via navigate_to."

        val daemonRunning = runReadAction {
            runCatching {
                DaemonCodeAnalyzer.getInstance(project).javaClass.getMethod("isRunning").invoke(DaemonCodeAnalyzer.getInstance(project)) as? Boolean ?: false
            }.getOrDefault(false)
        }
        if (daemonRunning) return "IDE is still analysing the file — wait a moment and try again."

        val document = runOnEdt { FileDocumentManager.getInstance().getDocument(vFile) }
            ?: return "Cannot read document for $filePath"

        if (line > document.lineCount) return "Line $line is out of range (file has ${document.lineCount} lines)"
        val lineStart = if (line <= 0) 0 else document.getLineStartOffset(line - 1)
        val lineEnd   = if (line <= 0) document.textLength else document.getLineEndOffset(line - 1)

        fun findField(obj: Any, name: String) = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }.find { it.name == name }?.also { it.isAccessible = true }
        fun findDeclaredMethod(obj: Any, name: String, vararg params: Class<*>) = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }.find { m -> m.name == name && m.parameterTypes.contentEquals(params) }?.also { it.isAccessible = true }

        // Navigate to the target line so the daemon analyses that area before we read highlights
        runOnEdt {
            val targetOffset = if (line > 0) lineStart else 0
            FileEditorManager.getInstance(project)
                .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile, targetOffset), true)
        }
        // Wait up to 8s for the daemon to finish after navigation
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            val running = runReadAction {
                runCatching {
                    DaemonCodeAnalyzer.getInstance(project).javaClass.getMethod("isRunning")
                        .invoke(DaemonCodeAnalyzer.getInstance(project)) as? Boolean ?: false
                }.getOrDefault(false)
            }
            if (!running) break
            kotlinx.coroutines.delay(150)
        }

        // Collect highlights AND apply fix all on EDT (same as get_quick_fixes) to avoid stale HighlightInfo
        val applied = runOnEdt {
            val editor  = FileEditorManager.getInstance(project)
                .openTextEditor(com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vFile), false)
                ?: return@runOnEdt false
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile)
                ?: return@runOnEdt false

            val markupModel = DocumentMarkupModel.forDocument(document, project, false)
            val highlights = markupModel?.allHighlighters
                ?.mapNotNull { it.errorStripeTooltip as? HighlightInfo }
                ?.filter { it.severity >= HighlightSeverity.INFORMATION
                        && it.startOffset < lineEnd
                        && it.endOffset > lineStart }
                ?: emptyList()

            for (info in highlights) {
                runCatching {
                    val offsetStore = findField(info, "offsetStore")?.get(info) ?: return@runCatching
                    val descriptors = findDeclaredMethod(info, "getIntentionActionDescriptors", offsetStore.javaClass)
                        ?.invoke(info, offsetStore) as? List<*> ?: return@runCatching
                    for (desc in descriptors) {
                        runCatching {
                            val action = desc?.javaClass?.getMethod("getAction")?.invoke(desc) ?: return@runCatching
                            val text   = action.javaClass.getMethod("getText").invoke(action)?.toString() ?: return@runCatching
                            if (text == fixText) {
                                val isAvail = runCatching {
                                    action.javaClass.methods.find { it.name == "isAvailable" && it.parameterCount == 3 }
                                        ?.invoke(action, project, editor, psiFile) as? Boolean ?: true
                                }.getOrDefault(true)
                                if (isAvail) {
                                    action.javaClass.methods.find { it.name == "invoke" && it.parameterCount == 3 }
                                        ?.invoke(action, project, editor, psiFile)
                                    return@runOnEdt true
                                }
                            }
                        }
                    }
                }
            }
            false
        }

        if (applied) {
            val loc = if (line > 0) "$filePath:$line" else filePath
            return captureResponse("Quick fix applied: \"$fixText\" in $loc")
        }

        // Fallback: the fix may come from a batch-only inspection not visible in the daemon.
        // Step 1: find the fix via ProblemsHolder in a read action (NOT on EDT — inspections may access indexes).
        data class FoundFix(val fix: com.intellij.codeInspection.QuickFix<com.intellij.codeInspection.CommonProblemDescriptor>, val descriptor: com.intellij.codeInspection.ProblemDescriptor)
        val found: FoundFix? = runReadAction {
            val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile)
                ?: return@runReadAction null
            val profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project).currentProfile
            val manager = com.intellij.codeInspection.InspectionManager.getInstance(project)

            for (wrapper in profile.getInspectionTools(psiFile)) {
                val tool = wrapper.tool as? com.intellij.codeInspection.LocalInspectionTool ?: continue
                val key = com.intellij.codeInsight.daemon.HighlightDisplayKey.find(wrapper.shortName) ?: continue
                if (!profile.isToolEnabled(key, psiFile)) continue

                var holder = com.intellij.codeInspection.ProblemsHolder(manager, psiFile, false)
                runCatching {
                    var visitor = tool.buildVisitor(holder, false)
                    // Fallback: some inspections (e.g. SpellChecking) need both holder AND visitor with isOnTheFly=true
                    if (visitor === com.intellij.psi.PsiElementVisitor.EMPTY_VISITOR) {
                        val holderOnTheFly = com.intellij.codeInspection.ProblemsHolder(manager, psiFile, true)
                        visitor = tool.buildVisitor(holderOnTheFly, true)
                        holder = holderOnTheFly
                    }
                    if (!isApplicable(wrapper, psiFile, visitor)) return@runCatching
                    psiFile.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
                        override fun visitElement(element: com.intellij.psi.PsiElement) {
                            element.accept(visitor)
                            super.visitElement(element)
                        }
                    })
                }
                for (descriptor in holder.results) {
                    val offset = descriptor.psiElement?.textOffset ?: continue
                    if (offset < lineStart || offset > lineEnd) continue
                    val fix = descriptor.fixes?.find { it.name == fixText } ?: continue
                    return@runReadAction FoundFix(fix, descriptor)
                }
            }
            null
        }

        // Step 2: apply the fix from a write-safe context (invokeLater, not invokeAndWait).
        val appliedViaInspection = if (found != null) {
            val done = java.util.concurrent.atomic.AtomicBoolean(false)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                    found.fix.applyFix(project, found.descriptor)
                }
                done.set(true)
            }
            // Wait for the invokeLater to complete (up to 5s)
            val deadline = System.currentTimeMillis() + 5000
            while (!done.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            done.get()
        } else false

        val loc = if (line > 0) "$filePath:$line" else filePath
        return captureResponse(
            if (appliedViaInspection) "Quick fix applied (batch inspection): \"$fixText\" in $loc"
            else "Fix not found: \"$fixText\"${if (line > 0) " at line $line" else ""}. Use get_quick_fixes or run_inspections to list available fixes."
        )
    }

    // ── list_inspections ─────────────────────────────────────────────────────

    @McpTool(name = "list_inspections")
    @McpDescription(description = """
        Lists all code inspections available in the current inspection profile.
        Optionally filtered to show only inspections applicable to a given file or folder.
        path: file or folder path relative to project root (omit for all inspections)
        enabled: "true" (default) = only enabled inspections, "false" = only disabled, "all" = both
        Returns: id, displayName, category, severity, enabled for each inspection.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun list_inspections(path: String? = null, enabled: String = "true", projectPath: String? = null): String {
        disabledMessage("list_inspections")?.let { return it }
        val project = resolveProject(projectPath)

        val psiFiles: List<com.intellij.psi.PsiFile> = runReadAction {
            if (path != null) {
                val vFile = resolveFilePath(project, path)
                    ?: return@runReadAction emptyList()
                if (vFile.isDirectory) {
                    val result = mutableListOf<com.intellij.psi.PsiFile>()
                    com.intellij.openapi.vfs.VfsUtilCore.iterateChildrenRecursively(vFile,
                        { it.isDirectory || !it.isDirectory },
                        { f -> if (!f.isDirectory) com.intellij.psi.PsiManager.getInstance(project).findFile(f)?.let { result += it }; true }
                    )
                    result
                } else {
                    listOfNotNull(com.intellij.psi.PsiManager.getInstance(project).findFile(vFile))
                }
            } else emptyList()
        }

        val profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project).currentProfile

        @Serializable
        data class InspectionInfo(val id: String, val name: String, val category: String, val severity: String, val enabled: Boolean)

        val enabledFilter = when (enabled.lowercase()) {
            "false" -> false
            "all"   -> null
            else    -> true
        }

        val infos = runReadAction {
            val tools = if (psiFiles.isNotEmpty())
                psiFiles.flatMap { profile.getInspectionTools(it).toList() }.distinctBy { it.shortName }
            else
                profile.getInspectionTools(null)

            tools.mapNotNull { wrapper ->
                val isEnabled = if (psiFiles.isNotEmpty())
                    psiFiles.any { profile.isToolEnabled(com.intellij.codeInsight.daemon.HighlightDisplayKey.find(wrapper.shortName) ?: return@mapNotNull null, it) }
                else
                    profile.isToolEnabled(com.intellij.codeInsight.daemon.HighlightDisplayKey.find(wrapper.shortName) ?: return@mapNotNull null)
                if (enabledFilter != null && isEnabled != enabledFilter) return@mapNotNull null
                InspectionInfo(
                    id       = wrapper.shortName,
                    name     = wrapper.displayName,
                    category = wrapper.groupDisplayName,
                    severity = profile.getErrorLevel(com.intellij.codeInsight.daemon.HighlightDisplayKey.find(wrapper.shortName)!!, psiFiles.firstOrNull()).name,
                    enabled  = isEnabled
                )
            }.sortedWith(compareBy({ it.category }, { it.name }))
        }

        if (infos.isEmpty()) return "No inspections found"
        return captureResponse("${infos.size} inspections:\n" + Json.encodeToString(infos))
    }

    // ── run_inspections ───────────────────────────────────────────────────────

    @McpTool(name = "run_inspections")
    @McpDescription(description = """
        Runs code inspections on a file, folder, or the whole project and returns all problems found.
        Works on closed files too — unlike get_file_problems which only works on open editors.
        path: file or folder path relative to project root (omit for whole project — can be slow)
        inspections: comma-separated inspection IDs to run (omit = all enabled inspections).
                     Use list_inspections to discover IDs.
        severity: minimum severity to include — "error", "warning" (default), "all"
        Returns problems grouped by file: file, line, severity, inspection, message, fixes.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun run_inspections(path: String? = null, inspections: String? = null, severity: String = "warning", projectPath: String? = null): String {
        disabledMessage("run_inspections")?.let { return it }
        val project = resolveProject(projectPath)

        val minSeverity = when (severity.lowercase()) {
            "error"       -> com.intellij.lang.annotation.HighlightSeverity.ERROR
            "info"        -> com.intellij.lang.annotation.HighlightSeverity.INFORMATION
            "all"         -> com.intellij.lang.annotation.HighlightSeverity("__", 0)
            else          -> com.intellij.lang.annotation.HighlightSeverity.WARNING
        }

        val requestedIds: Set<String>? = inspections
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.toSet()
            ?.takeIf { it.isNotEmpty() }

        val vFiles: List<com.intellij.openapi.vfs.VirtualFile> = runReadAction {
            if (path != null) {
                val vFile = resolveFilePath(project, path)
                    ?: return@runReadAction emptyList()
                if (vFile.isDirectory) {
                    val result = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
                    com.intellij.openapi.vfs.VfsUtilCore.iterateChildrenRecursively(vFile,
                        { true },
                        { f -> if (!f.isDirectory) result += f; true }
                    )
                    result
                } else listOf(vFile)
            } else {
                val roots = mutableListOf<com.intellij.openapi.vfs.VirtualFile>()
                com.intellij.openapi.roots.ProjectRootManager.getInstance(project).contentSourceRoots.forEach { root ->
                    com.intellij.openapi.vfs.VfsUtilCore.iterateChildrenRecursively(root,
                        { true },
                        { f -> if (!f.isDirectory) roots += f; true }
                    )
                }
                roots
            }
        }

        if (vFiles.isEmpty()) return if (path != null) "Path not found: $path" else "No source files found in project"

        @Serializable
        data class InspectionProblem(val file: String, val line: Int, val severity: String, val inspection: String, val message: String, val fixes: List<String>)

        val profile = com.intellij.profile.codeInspection.InspectionProjectProfileManager.getInstance(project).currentProfile
        val manager = com.intellij.codeInspection.InspectionManager.getInstance(project)
        val basePath = project.basePath?.trimEnd('/') ?: ""

        val problems = runReadAction {
            val allProblems = mutableListOf<InspectionProblem>()
            for (vFile in vFiles) {
                val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(vFile) ?: continue
                val tools = profile.getInspectionTools(psiFile)
                for (wrapper in tools) {
                    val tool = wrapper.tool as? com.intellij.codeInspection.LocalInspectionTool ?: continue
                    val key = com.intellij.codeInsight.daemon.HighlightDisplayKey.find(wrapper.shortName) ?: continue
                    if (!profile.isToolEnabled(key, psiFile)) continue
                    if (requestedIds != null && wrapper.shortName !in requestedIds) continue
                    val toolSeverity = profile.getErrorLevel(key, psiFile).severity
                    if (toolSeverity < minSeverity) continue

                    runCatching {
                        if (!isLanguageCompatible(wrapper, psiFile)) return@runCatching
                        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vFile)
                        // Some inspections (e.g. SpellChecking) override checkFile() instead of buildVisitor().
                        // Try checkFile() first; fall back to buildVisitor() if it returns null.
                        // Some inspections (e.g. SpellChecking) return EMPTY_VISITOR for isOnTheFly=false
                        // but work correctly with isOnTheFly=true — use a dedicated holder with matching flag.
                        val descriptors: List<com.intellij.codeInspection.ProblemDescriptor> =
                            tool.checkFile(psiFile, manager, false)?.toList()
                            ?: run {
                                val holder = com.intellij.codeInspection.ProblemsHolder(manager, psiFile, false)
                                var visitor = tool.buildVisitor(holder, false)
                                var effectiveHolder = holder
                                // Fallback: some inspections (e.g. SpellChecking) need both holder AND visitor with isOnTheFly=true
                                if (visitor === com.intellij.psi.PsiElementVisitor.EMPTY_VISITOR) {
                                    val holderOnTheFly = com.intellij.codeInspection.ProblemsHolder(manager, psiFile, true)
                                    visitor = tool.buildVisitor(holderOnTheFly, true)
                                    effectiveHolder = holderOnTheFly
                                }
                                if (!isApplicable(wrapper, psiFile, visitor)) return@run emptyList()
                                psiFile.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
                                    override fun visitElement(element: com.intellij.psi.PsiElement) {
                                        element.accept(visitor)
                                        super.visitElement(element)
                                    }
                                })
                                effectiveHolder.results
                            }
                        for (descriptor in descriptors) {
                            val offset = descriptor.psiElement?.textOffset ?: continue
                            val lineNum = document?.getLineNumber(offset)?.plus(1) ?: 0
                            allProblems += InspectionProblem(
                                file       = vFile.path.removePrefix("$basePath/"),
                                line       = lineNum,
                                severity   = toolSeverity.name,
                                inspection = wrapper.shortName,
                                message    = descriptor.descriptionTemplate
                                    .replace(Regex("<[^>]+>"), "")
                                    .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&"),
                                fixes      = descriptor.fixes?.mapNotNull { it.name } ?: emptyList()
                            )
                        }
                    }
                }
            }
            allProblems.sortedWith(compareBy({ it.file }, { it.line }))
        }

        // Supplement with daemon highlights for files currently open in the editor.
        // This captures annotator-based items (e.g. SpellCheckingInspection which uses
        // SpellCheckerAnnotator instead of buildVisitor) that batch inspection misses.
        // Only runs for already-open files — never forces files open.
        val existingKeys = problems.map { Triple(it.file, it.line, it.inspection) }.toMutableSet()
        val daemonProblems = mutableListOf<InspectionProblem>()
        val openFilePaths = runOnEdt { com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.map { it.path }.toSet() }

        for (vFile in vFiles) {
            if (vFile.path !in openFilePaths) continue
            val relPath = vFile.path.removePrefix("$basePath/")
            val items = runOnEdt {
                val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vFile)
                    ?: return@runOnEdt emptyList<InspectionProblem>()
                val markupModel = com.intellij.openapi.editor.impl.DocumentMarkupModel.forDocument(document, project, false)
                    ?: return@runOnEdt emptyList()
                markupModel.allHighlighters.mapNotNull { h ->
                    val info = h.errorStripeTooltip as? com.intellij.codeInsight.daemon.impl.HighlightInfo
                        ?: return@mapNotNull null
                    if (info.severity.compareTo(minSeverity) < 0) return@mapNotNull null
                    val inspId = info.inspectionToolId ?: return@mapNotNull null  // skip non-inspection highlights
                    val lineNum = document.getLineNumber(info.startOffset) + 1
                    InspectionProblem(
                        file       = relPath,
                        line       = lineNum,
                        severity   = info.severity.name,
                        inspection = inspId,
                        message    = info.description ?: "",
                        fixes      = emptyList()  // daemon fixes need an editor context — omitted here
                    )
                }
            }
            for (p in items) {
                val key = Triple(p.file, p.line, p.inspection)
                if (key !in existingKeys) { existingKeys += key; daemonProblems += p }
            }
        }

        val allProblems = (problems + daemonProblems).sortedWith(compareBy({ it.file }, { it.line }))
        if (allProblems.isEmpty()) return "No problems found" + if (path != null) " in $path" else ""
        return captureResponse("${allProblems.size} problems found:\n" + Json.encodeToString(allProblems))
    }

    // ── refresh_project ───────────────────────────────────────────────────────

    @McpTool(name = "refresh_project")
    @McpDescription(description = """
        Refreshes (reimports/syncs) the project build system configuration.
        Automatically detects Gradle or Maven from the project root and triggers the appropriate sync action.
        Use this after modifying build.gradle, pom.xml, settings.gradle, or when dependencies are out of sync.
        Returns what was triggered, or an error if no build system is detected.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun refresh_project(projectPath: String? = null): String {
        disabledMessage("refresh_project")?.let { return it }
        val project = resolveProject(projectPath)
        val basePath = project.basePath ?: return "Cannot determine project base path"
        val am = ActionManager.getInstance()

        val rootFiles = java.io.File(basePath).listFiles()?.map { it.name } ?: emptyList()
        val hasGradle = rootFiles.any { it in listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts") }
        val hasMaven = "pom.xml" in rootFiles

        if (!hasGradle && !hasMaven)
            return captureResponse("No Gradle or Maven build files found in project root ($basePath)")

        val results = mutableListOf<String>()

        fun triggerAction(actionId: String, label: String) {
            val action = runOnEdt { am.getAction(actionId) } ?: run {
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

        return captureResponse(results.joinToString("\n"))
    }

    // ── get_project_structure ─────────────────────────────────────────────────

    @McpTool(name = "get_project_structure")
    @McpDescription(description = """
        Returns the structure of the IntelliJ project: active SDK (with homePath), all SDKs registered
        in IntelliJ (with homePath, useful to suggest switching), modules (with their per-module SDK
        when it overrides the project SDK), source roots, excluded folders, and module-to-module
        dependencies.
        Useful to understand the project layout (where sources, tests, and resources live) before
        navigating or editing files.
        Source root types: source, test, resource, testResource.
        All paths are relative to the project root.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_project_structure(projectPath: String? = null): String {
        disabledMessage("get_project_structure")?.let { return it }
        val project = resolveProject(projectPath)
        return captureResponse(runReadAction {
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
                val moduleSdk = rootManager.sdk?.takeIf { it.name != sdk?.name }?.let { s ->
                    SdkInfo(name = s.name, type = s.sdkType.name, version = s.versionString, homePath = s.homePath)
                }
                ModuleInfo(
                    name = module.name,
                    type = moduleType,
                    sdk = moduleSdk,
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
        })
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Returns the language declared for an inspection in the LocalInspectionEP extension point.
 * This is the most reliable source — wrapper.language can be null even for language-specific tools.
 */
private fun inspectionEpLanguage(shortName: String): String? =
    com.intellij.codeInspection.LocalInspectionEP.LOCAL_INSPECTION.extensionList
        .find { it.shortName == shortName }?.language

/**
 * Returns true if the given inspection should run on the given file.
 * Checks both wrapper.language and the EP-declared language.
 * A visitor that is EMPTY_VISITOR also signals "not applicable".
 */
/** Language-only check (no visitor needed). Used for both checkFile() and buildVisitor() paths. */
private fun isLanguageCompatible(
    wrapper: com.intellij.codeInspection.ex.InspectionToolWrapper<*, *>,
    psiFile: com.intellij.psi.PsiFile
): Boolean {
    val fileLang = psiFile.language.id
    val wrapperLang = wrapper.language
    if (wrapperLang != null && wrapperLang != "any" && !wrapperLang.equals(fileLang, ignoreCase = true)) return false
    val epLang = inspectionEpLanguage(wrapper.shortName)
    if (epLang != null && epLang != "any" && !epLang.equals(fileLang, ignoreCase = true)) return false
    // Kotlin-package inspections must not run on non-Kotlin files
    val toolPkg = wrapper.tool.javaClass.packageName
    if (toolPkg.startsWith("org.jetbrains.kotlin") && !fileLang.equals("kotlin", ignoreCase = true)) return false
    return true
}

private fun isApplicable(
    wrapper: com.intellij.codeInspection.ex.InspectionToolWrapper<*, *>,
    psiFile: com.intellij.psi.PsiFile,
    visitor: com.intellij.psi.PsiElementVisitor
): Boolean {
    if (visitor === com.intellij.psi.PsiElementVisitor.EMPTY_VISITOR) return false
    return isLanguageCompatible(wrapper, psiFile)
}

// ── Data classes ──────────────────────────────────────────────────────────────

@Serializable data class FileProblem(val file: String, val line: Int, val column: Int, val severity: String, val message: String, val fixes: List<String> = emptyList())

@Serializable data class ProjectStructure(val name: String, val basePath: String, val sdk: SdkInfo?, val availableSdks: List<SdkInfo>, val modules: List<ModuleInfo>)
@Serializable data class SdkInfo(val name: String, val type: String, val version: String?, val homePath: String? = null)
@Serializable data class ModuleInfo(val name: String, val type: String? = null, val sdk: SdkInfo? = null, val sourceRoots: List<SourceRootInfo>, val excludedFolders: List<String>? = null, val dependencies: List<String>? = null)
@Serializable data class SourceRootInfo(val path: String, val type: String)
