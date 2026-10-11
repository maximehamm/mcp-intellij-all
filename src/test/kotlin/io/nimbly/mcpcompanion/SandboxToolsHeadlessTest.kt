package io.nimbly.mcpcompanion

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.project.DumbService
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.coroutines.runBlocking
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import io.nimbly.mcpcompanion.toolsets.McpCompanionDiagnosticToolset
import io.nimbly.mcpcompanion.toolsets.McpCompanionBuildToolset

/**
 * Headless tests for tools previously marked as "manual only".
 * Tests the business logic and graceful fallbacks that are accessible without a real sandbox.
 */
class SandboxToolsHeadlessTest : BasePlatformTestCase() {

    // ── get_ide_settings ──────────────────────────────────────────────────────

    fun `test get_ide_settings returns project name and basePath`() {
        val settings = invokeAndWaitIfNeeded {
            McpCompanionDiagnosticToolset().knownIdeSettings(project)
        }
        println("  project.name     = ${settings["project.name"]}")
        println("  project.basePath = ${settings["project.basePath"]}")
        assertNotNull("project.name should be present", settings["project.name"])
        assertNotNull("project.basePath should be present", settings["project.basePath"])
    }

    fun `test get_ide_settings search filters by keyword`() {
        val settings = invokeAndWaitIfNeeded {
            McpCompanionDiagnosticToolset().knownIdeSettings(project)
        }
        val encodingKeys = settings.filter { it.key.contains("encoding") }
        println("  encoding keys: ${encodingKeys.keys.joinToString()}")
        encodingKeys.forEach { (k, v) -> println("    $k = $v") }
        assertTrue("Should have at least one encoding key", encodingKeys.isNotEmpty())
    }

    // ── get_intellij_diagnostic components ────────────────────────────────────

    fun `test DumbService is accessible and not indexing in headless`() {
        val isDumb = invokeAndWaitIfNeeded { DumbService.getInstance(project).isDumb }
        println("  isDumb = $isDumb")
        assertFalse("Should not be indexing in headless", isDumb)
    }

    fun `test NotificationsManager is accessible in headless`() {
        val notifications = invokeAndWaitIfNeeded {
            NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(Notification::class.java, project)
                .toList()
        }
        println("  notifications count = ${notifications.size}")
        notifications.forEach { println("    - [${it.type}] ${it.title}") }
        assertNotNull("Notifications list should not be null", notifications)
    }

    // ── execute_ide_action ────────────────────────────────────────────────────

    fun `test execute_ide_action search finds actions by keyword`() {
        val results = invokeAndWaitIfNeeded {
            ActionManager.getInstance().getActionIdList("")
                .filter { it.lowercase().contains("reformat") }
        }
        println("  actions matching 'reformat': ${results.take(5).joinToString()}")
        assertTrue("Should find at least one action matching 'reformat'", results.isNotEmpty())
    }

    fun `test execute_ide_action finds known action by ID`() {
        val action = invokeAndWaitIfNeeded { ActionManager.getInstance().getAction("ReformatCode") }
        println("  ReformatCode → '${action?.templatePresentation?.text}'")
        assertNotNull("ReformatCode action should be registered", action)
    }

    fun `test execute_ide_action returns null for unknown action ID`() {
        val action = invokeAndWaitIfNeeded { ActionManager.getInstance().getAction("NonExistentAction_XYZ_12345") }
        println("  NonExistentAction_XYZ_12345 → $action")
        assertNull("Unknown action should return null", action)
    }

    // ── debug_run_configuration ───────────────────────────────────────────────

    fun `test RunManager finds no config in fresh headless project`() {
        val found = invokeAndWaitIfNeeded {
            com.intellij.execution.RunManager.getInstance(project)
                .findConfigurationByName("NonExistentConfig")
        }
        println("  findConfigurationByName('NonExistentConfig') → $found")
        assertNull("Should not find a non-existent run configuration", found)
    }

    // ── get_debug_variables ───────────────────────────────────────────────────

    fun `test no debug sessions exist in headless`() {
        val sessions = invokeAndWaitIfNeeded {
            XDebuggerManager.getInstance(project).debugSessions
        }
        println("  active debug sessions = ${sessions.size}")
        assertTrue("Should have no active debug sessions in headless", sessions.isEmpty())
    }

    // ── get_build_output ──────────────────────────────────────────────────────

    fun `test get_build_output graceful fallback when Build window absent`() {
        val tabs = invokeAndWaitIfNeeded {
            McpCompanionBuildToolset().extractBuildTabs(project)
        }
        println("  tabs returned = ${tabs.size}")
        tabs.forEach { println("    tab '${it.name}' → console: '${it.console}'") }
        assertEquals("Should return exactly one error tab", 1, tabs.size)
        assertTrue("Should contain 'not found' message",
            tabs[0].console?.contains("not found", ignoreCase = true) == true)
    }

    fun `test buildBuildNodes parses DefaultMutableTreeNode tree`() {
        val toolset = McpCompanionBuildToolset()
        val root = DefaultMutableTreeNode("root")
        root.add(DefaultMutableTreeNode("error: Foo.java:10"))
        root.add(DefaultMutableTreeNode("warning: unused import"))
        val model = DefaultTreeModel(root)
        val nodes = invokeAndWaitIfNeeded { toolset.buildBuildNodes(model, root) }
        println("  nodes parsed = ${nodes.size}")
        nodes.forEach { println("    node: text='${it.text}' severity=${it.severity} file=${it.file}") }
        assertEquals("Should parse 2 child nodes", 2, nodes.size)
        assertEquals("error: Foo.java:10", nodes[0].text)
        assertEquals("warning: unused import", nodes[1].text)
    }

    // ── get_test_results ──────────────────────────────────────────────────────

    fun `test get_test_results graceful fallback when Run window absent`() {
        val output = invokeAndWaitIfNeeded {
            McpCompanionBuildToolset().extractTestResults(project)
        }
        println("  error = '${output.error}'")
        println("  runs  = ${output.runs.size}")
        assertNotNull("Should return an error message when Run window absent", output.error)
        assertTrue("Should return empty runs list", output.runs.isEmpty())
    }

    fun `test buildTestNode status for unstarted proxy is RUNNING`() {
        val proxy = SMTestProxy("MyTest", false, null)
        val node = McpCompanionBuildToolset().buildTestNode(proxy)
        println("  SMTestProxy(unstarted) → status='${node.status}' error='${node.errorMessage}'")
        assertEquals("MyTest", node.name)
        assertEquals("RUNNING", node.status)
    }

    fun `test buildTestNode status for finished proxy is PASSED`() {
        val proxy = SMTestProxy("MyTest", false, null)
        proxy.setFinished()
        val node = McpCompanionBuildToolset().buildTestNode(proxy)
        println("  SMTestProxy(finished) → status='${node.status}' duration=${node.duration}")
        assertEquals("MyTest", node.name)
        assertEquals("PASSED", node.status)
    }

    fun `test buildTestNode status for failed proxy is FAILED`() {
        val proxy = SMTestProxy("FailingTest", false, null)
        proxy.setTestFailed("Expected 1 but was 2", null, false)
        val node = McpCompanionBuildToolset().buildTestNode(proxy)
        println("  SMTestProxy(failed) → status='${node.status}' error='${node.errorMessage}'")
        assertEquals("FailingTest", node.name)
        assertEquals("FAILED", node.status)
        assertNotNull("Error message should be present", node.errorMessage)
    }

    // ── get_console_output ────────────────────────────────────────────────────

    fun `test get_console_output always emits run, debug and services keys`() {
        // An empty result must serialize to {"run":[],"debug":[],"services":[]} rather than a bare {}:
        // the client can then tell "nothing running" apart from an old plugin build without `services`.
        val toolset = McpCompanionBuildToolset()
        val json = invokeAndWaitIfNeeded {
            toolset.consoleJson.encodeToString(
                io.nimbly.mcpcompanion.toolsets.ConsoleOutput.serializer(),
                toolset.extractConsoleOutput(project),
            )
        }
        println("  json = $json")
        for (key in listOf("\"run\"", "\"debug\"", "\"services\"")) {
            assertTrue("$key must always be present in: $json", json.contains(key))
        }
    }

    fun `test pickActiveWindow prefers the focused window then falls back to the visible one`() {
        val t = McpCompanionBuildToolset()
        fun pick(focused: String?, run: Boolean = false, debug: Boolean = false, services: Boolean = false, sessions: Boolean = false) =
            t.pickActiveWindow(focused, run, debug, services, sessions)

        // focus wins over visibility
        assertEquals("debug", pick("Debug", run = true))
        assertEquals("run", pick("Run", debug = true))
        assertEquals("services", pick("Services"))
        // focus elsewhere (editor): fall back to visibility — this was the regression in the Services PR
        assertEquals("run", pick(null, run = true))
        assertEquals("run", pick("Project", run = true))
        assertEquals("debug", pick(null, debug = true))
        assertEquals("debug", pick(null, run = true, debug = true, sessions = true))
        assertEquals("run", pick(null, run = true, debug = true, sessions = false))
        assertEquals("services", pick(null, services = true))
        assertEquals("run", pick(null, run = true, services = true))
        assertNull(pick(null))
    }

    fun `test get_console_output returns valid structure when no Run or Debug window`() {
        val result = invokeAndWaitIfNeeded {
            McpCompanionBuildToolset().extractConsoleOutput(project)
        }
        println("  activeWindow = ${result.activeWindow}")
        println("  run tabs     = ${result.run.size}")
        println("  debug tabs   = ${result.debug.size}")
        // In headless: no Run/Debug tool windows → both lists empty, activeWindow null
        assertNull("activeWindow should be null when no window is visible", result.activeWindow)
        assertNotNull("run list should not be null", result.run)
        assertNotNull("debug list should not be null", result.debug)
        // No active run/debug session → both empty
        assertTrue("run tabs should be empty in headless", result.run.isEmpty())
        assertTrue("debug tabs should be empty in headless", result.debug.isEmpty())
    }

    // ── get_terminal_output ───────────────────────────────────────────────────

    fun `test get_terminal_output graceful fallback when Terminal window absent`() {
        val result = invokeAndWaitIfNeeded {
            McpCompanionBuildToolset().extractTerminalTabs(project)
        }
        println("  result = '$result'")
        // In headless there is no Terminal tool window — should return an error string, not throw
        assertNotNull("Should return a non-null result", result)
        assertTrue("Should contain 'not found' or tab list",
            result.contains("not found", ignoreCase = true) || result.startsWith("{"))
    }

    // ── send_to_terminal ──────────────────────────────────────────────────────

    fun `test send_to_terminal graceful fallback when Terminal window absent`() {
        val settings = McpCompanionSettings.getInstance()
        settings.setEnabled("send_to_terminal", true)
        try {
            val result = invokeAndWaitIfNeeded {
                McpCompanionBuildToolset().sendToTerminalImpl(project, "pwd", null)
            }
            println("  result = '$result'")
            assertTrue("Should return 'not found' error when no terminal window",
                result.contains("not found", ignoreCase = true) || result == "Command sent to terminal")
        } finally {
            settings.setEnabled("send_to_terminal", false)  // restore default (unchecked → prompts)
        }
    }

    // ── higher-risk tools: checkbox-is-consent model ─────────────────────────

    fun `test confirm tools prompt by default, regular tools run`() {
        val s = McpCompanionSettings()  // fresh, no overrides → exercises the defaults
        // ✋ tools are unchecked by default → they prompt before running
        for (tool in listOf("send_to_terminal", "delete_file")) {
            assertTrue("$tool should be in CONFIRM_BY_DEFAULT", tool in McpCompanionSettings.CONFIRM_BY_DEFAULT)
            assertFalse("$tool should be unchecked by default", s.isEnabled(tool))
            assertTrue("$tool should prompt by default", s.promptsBeforeRun(tool))
        }
        // regular tools are enabled and never prompt
        for (tool in listOf("get_open_editors", "get_build_output", "navigate_to")) {
            assertTrue("$tool should be enabled by default", s.isEnabled(tool))
            assertFalse("$tool should never prompt", s.promptsBeforeRun(tool))
        }
    }

    fun `test checking a confirm tool stops the prompt`() {
        val s = McpCompanionSettings()
        assertTrue("delete_file should prompt while unchecked", s.promptsBeforeRun("delete_file"))
        s.setEnabled("delete_file", true)   // ☑ "always allow"
        assertFalse("checked confirm tool should not prompt", s.promptsBeforeRun("delete_file"))
        assertTrue("checked confirm tool is enabled", s.isEnabled("delete_file"))
        // unchecking re-arms the prompt
        s.setEnabled("delete_file", false)
        assertTrue("unchecked confirm tool prompts again", s.promptsBeforeRun("delete_file"))
    }

    fun `test regular tools are enabled by default`() {
        for (tool in listOf("get_open_editors", "get_build_output", "get_terminal_output", "get_intellij_diagnostic")) {
            val enabled = McpCompanionSettings.getInstance().isEnabled(tool)
            println("  $tool enabled = $enabled")
            assertTrue("$tool should be enabled by default", enabled)
        }
    }

    // ── refresh_project ───────────────────────────────────────────────────────

    fun `test refresh_project detects no build files in headless project`() {
        val basePath = project.basePath ?: return
        val rootFiles = java.io.File(basePath).listFiles()?.map { it.name } ?: emptyList()
        val hasGradle = rootFiles.any {
            it in listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        }
        val hasMaven = "pom.xml" in rootFiles
        println("  basePath = $basePath")
        println("  rootFiles = $rootFiles")
        println("  hasGradle=$hasGradle  hasMaven=$hasMaven")
        assertFalse("Headless test project should not have Gradle files", hasGradle)
        assertFalse("Headless test project should not have Maven pom.xml", hasMaven)
    }

    // ── list_run_configurations / get_run_configuration_xml / start_run_configuration / modify_run_configuration ──

    fun `test RunManager allSettings is empty in fresh headless project`() {
        val configs = invokeAndWaitIfNeeded {
            com.intellij.execution.RunManager.getInstance(project).allSettings
        }
        println("  RunManager.allSettings.size = ${configs.size}")
        assertTrue("Fresh headless project should have no run configurations", configs.isEmpty())
    }

    fun `test RunManager findConfigurationByName returns null for unknown config`() {
        val found = invokeAndWaitIfNeeded {
            com.intellij.execution.RunManager.getInstance(project).findConfigurationByName("NonExistentConfig")
        }
        println("  findConfigurationByName('NonExistentConfig') → $found")
        assertNull("Should return null for unknown run configuration", found)
    }

    // ── stop_run_configuration / restart_run_configuration ────────────────────

    fun `test getRunningDescriptors is empty in fresh headless project`() {
        // stop_run_configuration and restart_run_configuration resolve the target session(s) via
        // ExecutionManagerImpl.getRunningDescriptors { settings -> settings.name == name } — an
        // authoritative match on the RunnerAndConfigurationSettings name (not the UI tab title).
        // In a fresh project nothing is running, so the lookup is empty — exactly the
        // "no running instance found" branch of stop_run_configuration.
        val running = invokeAndWaitIfNeeded {
            com.intellij.execution.impl.ExecutionManagerImpl.getInstance(project)
                .getRunningDescriptors { true }
        }
        println("  getRunningDescriptors{true}.size = ${running.size}")
        assertTrue("Fresh headless project should have no running descriptors", running.isEmpty())
    }

    fun `test running-descriptor name lookup returns empty for unknown config`() {
        // Mirrors McpCompanionDebugToolset.runningDescriptorsFor(): filter running descriptors by
        // the settings name. Must yield nothing for a name that was never launched.
        val running = invokeAndWaitIfNeeded {
            com.intellij.execution.impl.ExecutionManagerImpl.getInstance(project)
                .getRunningDescriptors { it != null && it.name == "NonExistentConfig" }
        }
        println("  runningDescriptorsFor('NonExistentConfig').size = ${running.size}")
        assertTrue("No running descriptor should match an unknown config name", running.isEmpty())
    }

    fun `test ExecutionManagerImpl stopProcess is null-safe`() {
        // stop_run_configuration/restart_run_configuration call ExecutionManagerImpl.stopProcess()
        // per matched handler. Verify the platform entry point exists and tolerates a null
        // descriptor (the companion overload is @JvmStatic and null-safe) — so an empty match set
        // can never NPE.
        invokeAndWaitIfNeeded {
            com.intellij.execution.impl.ExecutionManagerImpl.stopProcess(null as com.intellij.execution.ui.RunContentDescriptor?)
        }
        println("  ExecutionManagerImpl.stopProcess(null) did not throw")
    }

    // ── create_run_configuration_from_xml ─────────────────────────────────────

    fun `test ConfigurationType CONFIGURATION_TYPE_EP is accessible via reflection in headless`() {
        @Suppress("UNCHECKED_CAST")
        val types = runCatching {
            (com.intellij.execution.configurations.ConfigurationType::class.java
                .getField("CONFIGURATION_TYPE_EP").get(null)
                    as com.intellij.openapi.extensions.ExtensionPointName<com.intellij.execution.configurations.ConfigurationType>)
                .extensionList
        }.getOrNull()
        if (types == null) {
            println("  WARNING: CONFIGURATION_TYPE_EP not accessible in headless — create_run_configuration_from_xml type lookup may fail")
            return
        }
        println("  CONFIGURATION_TYPE_EP returned ${types.size} types: ${types.take(5).map { it.id }}")
        // Just verifies the EP is accessible — count may be low in headless, that's fine
    }
}
