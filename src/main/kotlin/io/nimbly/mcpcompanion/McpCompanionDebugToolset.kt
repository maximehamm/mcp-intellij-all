package io.nimbly.mcpcompanion

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentManager
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.clientInfo
import com.intellij.mcpserver.project
import com.intellij.openapi.application.EDT
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import kotlin.coroutines.coroutineContext

class McpCompanionDebugToolset : McpToolset {

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

    // ── get_debug_variables ───────────────────────────────────────────────────

    @McpTool(name = "get_debug_variables")
    @McpDescription(description = """
        Returns the local variables and their values from the current debugger stack frame.
        Only available when a debug session is paused at a breakpoint.
        Useful to inspect variable state during debugging.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_debug_variables(projectPath: String? = null): String {
        disabledMessage("get_debug_variables")?.let { return it }
        val project = resolveProject(projectPath)
        val sessions = XDebuggerManager.getInstance(project).debugSessions
        if (sessions.isEmpty()) return "No active debug session"
        val session = sessions.first()
        val frame = runOnEdt { session.currentStackFrame }
            ?: return "No stack frame — program may still be running"

        val children = mutableListOf<Pair<String, XValue>>()
        val childrenLatch = CountDownLatch(1)
        runOnEdt {
            frame.computeChildren(object : XCompositeNode {
                override fun addChildren(list: XValueChildrenList, last: Boolean) {
                    for (i in 0 until list.size()) children.add(list.getName(i) to list.getValue(i))
                    if (last) childrenLatch.countDown()
                }
                override fun tooManyChildren(remaining: Int) { childrenLatch.countDown() }
                override fun tooManyChildren(remaining: Int, addNextChildren: Runnable) { childrenLatch.countDown() }
                override fun setAlreadySorted(alreadySorted: Boolean) {}
                override fun setErrorMessage(errorMessage: String) { childrenLatch.countDown() }
                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) { childrenLatch.countDown() }
                override fun setMessage(message: String, icon: Icon?, attributes: com.intellij.ui.SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) {}
                override fun isObsolete() = false
            })
        }
        childrenLatch.await(5, TimeUnit.SECONDS)

        val variables = children.map { (name, xValue) -> resolveDebugVariable(name, xValue) }
        return Json.encodeToString(DebugVariablesOutput(session = session.sessionName, variables = variables))
    }

    // ── add_conditional_breakpoint ────────────────────────────────────────────

    @McpTool(name = "add_conditional_breakpoint")
    @McpDescription(description = """
        Adds a line breakpoint with a condition in a single call.
        filePath: path relative to the project root (e.g. "src/main/java/Foo.java").
        line: 1-based line number.
        condition: condition expression (e.g. "i == 3"). Leave empty for unconditional.
        If a breakpoint already exists at that line, its condition is updated instead.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun add_conditional_breakpoint(filePath: String, line: Int, condition: String = "", projectPath: String? = null): String {
        disabledMessage("add_conditional_breakpoint")?.let { return it }
        val project = resolveProject(projectPath)
        return runOnEdt {
            val (file, err) = resolveFilePathOrError(project, filePath)
            if (err != null) return@runOnEdt err
            val normalizedPath = filePath.replace('\\', '/')
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val existing = manager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                .firstOrNull { it.line == line - 1 && it.presentableFilePath.endsWith(normalizedPath) }
            if (existing == null) {
                com.intellij.xdebugger.XDebuggerUtil.getInstance().toggleLineBreakpoint(project, file!!, line - 1, false)
            }
            val bp = existing ?: run {
                var found: com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>? = null
                repeat(10) {
                    found = manager.allBreakpoints
                        .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                        .firstOrNull { it.line == line - 1 && it.presentableFilePath.endsWith(normalizedPath) }
                    if (found == null) Thread.sleep(100)
                }
                found ?: return@runOnEdt "Failed to create breakpoint at $filePath:$line"
            }
            val expr = if (condition.isNotBlank())
                com.intellij.xdebugger.XDebuggerUtil.getInstance()
                    .createExpression(condition, null, null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION)
            else null
            bp.conditionExpression = expr
            val action = if (existing != null) "updated" else "added"
            if (condition.isNotBlank()) "Breakpoint $action at $filePath:$line with condition: $condition"
            else "Breakpoint $action at $filePath:$line"
        }
    }

    // ── get_breakpoints ───────────────────────────────────────────────────────

    @McpTool(name = "get_breakpoints")
    @McpDescription(description = """
        Returns all line breakpoints in the project with their file, line, enabled state, and condition (if any).
        Use this to inspect existing breakpoints before modifying them with set_breakpoint_condition.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_breakpoints(projectPath: String? = null): String {
        disabledMessage("get_breakpoints")?.let { return it }
        val project = resolveProject(projectPath)
        val breakpoints = runOnEdt {
            XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                .map { bp ->
                    BreakpointInfo(
                        file = bp.presentableFilePath,
                        line = bp.line + 1,
                        enabled = bp.isEnabled,
                        condition = bp.conditionExpression?.expression?.takeIf { it.isNotBlank() }
                    )
                }
        }
        if (breakpoints.isEmpty()) return "No line breakpoints found"
        return Json.encodeToString(breakpoints)
    }

    // ── mute_breakpoints ──────────────────────────────────────────────────────

    @McpTool(name = "mute_breakpoints")
    @McpDescription(description = """
        Enables or disables all line breakpoints in the project.
        Pass muted=true to disable all breakpoints (they become inactive but are not deleted).
        Pass muted=false to re-enable all breakpoints.
        Does not require an active debug session.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun mute_breakpoints(muted: Boolean, projectPath: String? = null): String {
        disabledMessage("mute_breakpoints")?.let { return it }
        val project = resolveProject(projectPath)
        val count = runOnEdt {
            val breakpoints = XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
            breakpoints.forEach { it.isEnabled = !muted }
            breakpoints.size
        }
        return if (muted) "$count breakpoints disabled" else "$count breakpoints enabled"
    }

    // ── set_breakpoint_condition ───────────────────────────────────────────────

    @McpTool(name = "set_breakpoint_condition")
    @McpDescription(description = """
        Sets or removes a condition on a breakpoint at a given file and line.
        filePath: path relative to the project root (e.g. "src/main/java/Foo.java").
        line: 1-based line number where the breakpoint is set.
        condition: the condition expression (e.g. "i == 5"). Pass empty string to remove the condition.
        The breakpoint must already exist at that line.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun set_breakpoint_condition(filePath: String, line: Int, condition: String, projectPath: String? = null): String {
        disabledMessage("set_breakpoint_condition")?.let { return it }
        val project = resolveProject(projectPath)
        return runOnEdt {
            val manager = XDebuggerManager.getInstance(project).breakpointManager
            val bp = manager.allBreakpoints
                .filterIsInstance<com.intellij.xdebugger.breakpoints.XLineBreakpoint<*>>()
                .firstOrNull { it.line == line - 1 && it.presentableFilePath.endsWith(filePath.replace('\\', '/')) }
                ?: return@runOnEdt "No breakpoint found at $filePath:$line"

            if (condition.isEmpty()) {
                bp.conditionExpression = null
                "Condition removed from breakpoint at $filePath:$line"
            } else {
                val expr = com.intellij.xdebugger.XDebuggerUtil.getInstance()
                    .createExpression(condition, null, null, com.intellij.xdebugger.evaluation.EvaluationMode.EXPRESSION)
                bp.conditionExpression = expr
                "Condition set on breakpoint at $filePath:$line: $condition"
            }
        }
    }

    // ── get_run_configuration_xml ─────────────────────────────────────────────

    @McpTool(name = "get_run_configuration_xml")
    @McpDescription(description = """
        Returns the full XML definition of an existing run configuration.
        The XML uses the same format as .idea/runConfigurations/*.xml (IntelliJ storage format).
        Use this to inspect configuration details or as a template for create_run_configuration_from_xml.
        configurationName: exact name from list_run_configurations.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_run_configuration_xml(configurationName: String, projectPath: String? = null): String {
        disabledMessage("get_run_configuration_xml")?.let { return it }
        val project = resolveProject(projectPath)

        return runOnEdt {
            val settings = RunManager.getInstance(project).findConfigurationByName(configurationName)
                ?: return@runOnEdt "Run configuration '$configurationName' not found. Use list_run_configurations to see available configurations."

            val element = org.jdom.Element("configuration")
            settings.configuration.writeExternal(element)
            // Add metadata attributes so the XML is self-contained and usable by create_run_configuration_from_xml
            element.setAttribute("name", settings.name)
            element.setAttribute("type", settings.type.id)
            element.setAttribute("factoryName", settings.factory.name)
            org.jdom.output.XMLOutputter(org.jdom.output.Format.getPrettyFormat()).outputString(element)
        }
    }

    // ── create_run_configuration_from_xml ─────────────────────────────────────

    @McpTool(name = "create_run_configuration_from_xml")
    @McpDescription(description = """
        Creates a new run configuration from an XML definition.
        Works for ALL configuration types (Application, Gradle, Maven, Docker, JUnit, Kotlin, etc.).
        Typical workflow:
          1. Call get_run_configuration_xml on an existing config to get the XML template.
          2. Modify the XML (change tasks, mainClass, arguments, etc.).
          3. Call this tool with the modified XML and a new name.
        name: name for the new configuration.
        xml: full XML string as returned by get_run_configuration_xml (must include type and factoryName attributes).

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun create_run_configuration_from_xml(name: String, xml: String, projectPath: String? = null): String {
        disabledMessage("create_run_configuration_from_xml")?.let { return it }
        val project = resolveProject(projectPath)

        return runOnEdt {
            val runManager = RunManager.getInstance(project)

            if (runManager.findConfigurationByName(name) != null)
                return@runOnEdt "A run configuration named '$name' already exists. Delete it first or choose a different name."

            // Parse XML
            val element = runCatching {
                org.jdom.input.SAXBuilder().build(java.io.StringReader(xml)).rootElement
            }.getOrElse { e -> return@runOnEdt "Invalid XML: ${e.message}" }

            // Resolve configuration type
            val typeId = element.getAttributeValue("type")
                ?: return@runOnEdt "XML is missing the 'type' attribute (e.g. type=\"Application\")."

            @Suppress("UNCHECKED_CAST")
            val allTypes = runCatching {
                (com.intellij.execution.configurations.ConfigurationType::class.java
                    .getField("CONFIGURATION_TYPE_EP").get(null)
                        as com.intellij.openapi.extensions.ExtensionPointName<com.intellij.execution.configurations.ConfigurationType>)
                    .extensionList
            }.getOrElse { emptyList() }

            val configType = allTypes.find { it.id == typeId }
                ?: return@runOnEdt "Unknown configuration type '$typeId'. " +
                    "Available types: ${allTypes.joinToString(", ") { "${it.id} (${it.displayName})" }}"

            val factoryName = element.getAttributeValue("factoryName")
            val factory = (if (factoryName != null)
                configType.configurationFactories.find { it.name == factoryName }
                else null) ?: configType.configurationFactories.firstOrNull()
                ?: return@runOnEdt "No factory found for type '$typeId'."

            val settings = runManager.createConfiguration(name, factory)
            runCatching { settings.configuration.readExternal(element) }
                .onFailure { return@runOnEdt "Failed to apply XML: ${it.message}" }
            settings.name = name  // override name from XML with requested name

            runManager.addConfiguration(settings)
            runManager.selectedConfiguration = settings

            "Run configuration '$name' created (type: ${configType.displayName}). Use start_run_configuration to launch it."
        }
    }

    // ── list_run_configurations ───────────────────────────────────────────────

    @McpTool(name = "list_run_configurations")
    @McpDescription(description = """
        Lists all run configurations defined in the project.
        Returns name, type, folder (if any), and running status for each configuration.
        Use the exact name with start_run_configuration or debug_run_configuration.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun list_run_configurations(projectPath: String? = null): String {
        disabledMessage("list_run_configurations")?.let { return it }
        val project = resolveProject(projectPath)

        @Serializable
        data class RunConfigInfo(val name: String, val type: String, val folder: String? = null, val running: Boolean)

        val configs = runOnEdt {
            // Running status via public API: match open run tabs with non-terminated process handlers
            val runningNames = RunContentManager.getInstance(project).allDescriptors
                .filter { it.processHandler?.isProcessTerminated == false }
                .map { it.displayName }
                .toSet()

            RunManager.getInstance(project).allSettings.map { settings ->
                RunConfigInfo(
                    name    = settings.name,
                    type    = settings.type.displayName,
                    folder  = settings.folderName,
                    running = settings.name in runningNames
                )
            }
        }

        if (configs.isEmpty()) return "No run configurations found in this project"
        return Json.encodeToString(configs)
    }

    // ── start_run_configuration ───────────────────────────────────────────────

    @McpTool(name = "start_run_configuration")
    @McpDescription(description = """
        Launches a run configuration by name and returns immediately (non-blocking).
        mode: "run" (default) or "debug".
        configurationName: exact name from list_run_configurations.
        Use get_console_output to read output after launch.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun start_run_configuration(configurationName: String, mode: String = "run", projectPath: String? = null): String {
        disabledMessage("start_run_configuration")?.let { return it }
        val project = resolveProject(projectPath)

        val settings = runOnEdt {
            RunManager.getInstance(project).findConfigurationByName(configurationName)
        } ?: return "Run configuration '$configurationName' not found. Use list_run_configurations to see available configurations."

        val executor = if (mode == "debug") DefaultDebugExecutor.getDebugExecutorInstance()
                       else DefaultRunExecutor.getRunExecutorInstance()

        withContext(Dispatchers.EDT) {
            ProgramRunnerUtil.executeConfiguration(project, settings, executor)
        }

        return "Run configuration '$configurationName' started in $mode mode. Use get_console_output to follow output."
    }

    // ── modify_run_configuration ──────────────────────────────────────────────

    @McpTool(name = "modify_run_configuration")
    @McpDescription(description = """
        Modifies common parameters of an existing run configuration (any type).
        configurationName: exact name of the configuration to modify (use list_run_configurations).
        vmOptions: JVM options string (e.g. "-Xmx1g -Dfoo=bar"). Set to "" to clear.
        programArguments: program arguments string. Set to "" to clear.
        workingDirectory: working directory path. Set to "" to use project root.
        envVariables: environment variables as "KEY1=VALUE1,KEY2=VALUE2". Set to "" to clear.
        Only parameters explicitly provided are changed — omitted ones are left untouched.
        Works for Application, Gradle, Maven, JUnit, Kotlin and any config implementing CommonProgramRunConfigurationParameters.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun modify_run_configuration(
        configurationName: String,
        vmOptions: String? = null,
        programArguments: String? = null,
        workingDirectory: String? = null,
        envVariables: String? = null,
        projectPath: String? = null
    ): String {
        disabledMessage("modify_run_configuration")?.let { return it }
        val project = resolveProject(projectPath)

        return runOnEdt {
            val runManager = RunManager.getInstance(project)
            val settings = runManager.findConfigurationByName(configurationName)
                ?: return@runOnEdt "Run configuration '$configurationName' not found. Use list_run_configurations to see available configurations."

            val config = settings.configuration
            val iface = runCatching {
                Class.forName("com.intellij.execution.configurations.CommonProgramRunConfigurationParameters")
            }.getOrNull()

            if (iface == null || !iface.isInstance(config))
                return@runOnEdt "Configuration '$configurationName' (${settings.type.displayName}) does not support parameter modification via this tool."

            val changed = mutableListOf<String>()

            if (vmOptions != null) {
                runCatching { iface.getMethod("setVMParameters", String::class.java).invoke(config, vmOptions) }
                    .onSuccess { changed += "vmOptions" }
            }
            if (programArguments != null) {
                runCatching { iface.getMethod("setProgramParameters", String::class.java).invoke(config, programArguments) }
                    .onSuccess { changed += "programArguments" }
            }
            if (workingDirectory != null) {
                val dir = workingDirectory.ifEmpty { project.basePath ?: "" }
                runCatching { iface.getMethod("setWorkingDirectory", String::class.java).invoke(config, dir) }
                    .onSuccess { changed += "workingDirectory" }
            }
            if (envVariables != null) {
                val envMap = if (envVariables.isEmpty()) emptyMap()
                else envVariables.split(",").mapNotNull {
                    val eq = it.indexOf('=')
                    if (eq > 0) it.substring(0, eq).trim() to it.substring(eq + 1).trim() else null
                }.toMap()
                runCatching {
                    iface.getMethod("setEnvs", Map::class.java).invoke(config, envMap)
                }.onSuccess { changed += "envVariables" }
            }

            if (changed.isEmpty())
                "No parameters were modified (configuration type may not support them)"
            else
                "Run configuration '$configurationName' updated: ${changed.joinToString(", ")}. Use start_run_configuration to launch it."
        }
    }

    // ── debug_run_configuration ───────────────────────────────────────────────

    @McpTool(name = "debug_run_configuration")
    @McpDescription(description = """
        Launches a run configuration in debug mode and returns immediately.
        Does NOT wait for completion — use get_debug_variables to check if stopped at a breakpoint,
        or get_console_output to read console output.
        configurationName: exact name of the run configuration (use get_run_configurations to list them).

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun debug_run_configuration(configurationName: String, projectPath: String? = null): String {
        disabledMessage("debug_run_configuration")?.let { return it }
        val project = resolveProject(projectPath)

        val settings = runOnEdt {
            com.intellij.execution.RunManager.getInstance(project).findConfigurationByName(configurationName)
        } ?: return "Configuration '$configurationName' not found"

        withContext(Dispatchers.EDT) {
            ProgramRunnerUtil.executeConfiguration(project, settings, DefaultDebugExecutor.getDebugExecutorInstance())
        }

        return "Debug session started for '$configurationName'. Use get_debug_variables to inspect variables if stopped at a breakpoint."
    }

    internal fun resolveDebugVariable(name: String, xValue: XValue): DebugVariable {
        var type: String? = null
        var value: String? = null
        val latch = CountDownLatch(1)
        xValue.computePresentation(object : XValueNode {
            override fun setPresentation(icon: Icon?, type_: String?, value_: String, hasChildren: Boolean) {
                type = type_; value = value_; latch.countDown()
            }
            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                type = presentation.type
                val sb = StringBuilder()
                presentation.renderValue(object : XValuePresentation.XValueTextRenderer {
                    override fun renderValue(v: String) { sb.append(v) }
                    override fun renderValue(v: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) { sb.append(v) }
                    override fun renderStringValue(v: String) { sb.append("\"$v\"") }
                    override fun renderStringValue(v: String, extra: String?, maxLen: Int) { sb.append("\"$v\"") }
                    override fun renderNumericValue(v: String) { sb.append(v) }
                    override fun renderKeywordValue(v: String) { sb.append(v) }
                    override fun renderComment(v: String) {}
                    override fun renderSpecialSymbol(v: String) { sb.append(v) }
                    override fun renderError(v: String) { sb.append(v) }
                })
                value = sb.toString(); latch.countDown()
            }
            override fun setFullValueEvaluator(e: XFullValueEvaluator) {}
            override fun isObsolete() = false
        }, XValuePlace.TREE)
        latch.await(2, TimeUnit.SECONDS)
        return DebugVariable(name = name, type = type, value = value)
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

@Serializable data class DebugVariablesOutput(val session: String, val variables: List<DebugVariable>)
@Serializable data class DebugVariable(val name: String, val type: String? = null, val value: String? = null)

@Serializable data class BreakpointInfo(val file: String, val line: Int, val enabled: Boolean, val condition: String? = null)
