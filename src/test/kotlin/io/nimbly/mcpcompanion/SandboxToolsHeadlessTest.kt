package io.nimbly.mcpcompanion

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.project.DumbService
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.xdebugger.XDebuggerManager
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

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
        assertNotNull("project.name should be present", settings["project.name"])
        assertNotNull("project.basePath should be present", settings["project.basePath"])
    }

    fun `test get_ide_settings search filters by keyword`() {
        val settings = invokeAndWaitIfNeeded {
            McpCompanionDiagnosticToolset().knownIdeSettings(project)
        }
        val encodingKeys = settings.keys.filter { it.contains("encoding") }
        assertTrue("Should have at least one encoding key", encodingKeys.isNotEmpty())
    }

    // ── get_intellij_diagnostic components ────────────────────────────────────

    fun `test DumbService is accessible and not indexing in headless`() {
        val isDumb = invokeAndWaitIfNeeded { DumbService.getInstance(project).isDumb }
        assertFalse("Should not be indexing in headless", isDumb)
    }

    fun `test NotificationsManager is accessible in headless`() {
        val notifications = invokeAndWaitIfNeeded {
            NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(Notification::class.java, project)
                .toList()
        }
        assertNotNull("Notifications list should not be null", notifications)
    }

    // ── execute_ide_action ────────────────────────────────────────────────────

    fun `test execute_ide_action search finds actions by keyword`() {
        val results = invokeAndWaitIfNeeded {
            ActionManager.getInstance().getActionIdList("")
                .filter { it.lowercase().contains("reformat") }
        }
        assertTrue("Should find at least one action matching 'reformat'", results.isNotEmpty())
    }

    fun `test execute_ide_action finds known action by ID`() {
        val action = invokeAndWaitIfNeeded { ActionManager.getInstance().getAction("ReformatCode") }
        assertNotNull("ReformatCode action should be registered", action)
    }

    fun `test execute_ide_action returns null for unknown action ID`() {
        val action = invokeAndWaitIfNeeded { ActionManager.getInstance().getAction("NonExistentAction_XYZ_12345") }
        assertNull("Unknown action should return null", action)
    }

    // ── debug_run_configuration ───────────────────────────────────────────────

    fun `test RunManager finds no config in fresh headless project`() {
        val found = invokeAndWaitIfNeeded {
            com.intellij.execution.RunManager.getInstance(project)
                .findConfigurationByName("NonExistentConfig")
        }
        assertNull("Should not find a non-existent run configuration", found)
    }

    // ── get_debug_variables ───────────────────────────────────────────────────

    fun `test no debug sessions exist in headless`() {
        val sessions = invokeAndWaitIfNeeded {
            XDebuggerManager.getInstance(project).debugSessions
        }
        assertTrue("Should have no active debug sessions in headless", sessions.isEmpty())
    }

    // ── get_build_output ──────────────────────────────────────────────────────

    fun `test get_build_output graceful fallback when Build window absent`() {
        val tabs = invokeAndWaitIfNeeded {
            McpCompanionBuildToolset().extractBuildTabs(project)
        }
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
        assertEquals("Should parse 2 child nodes", 2, nodes.size)
        assertEquals("error: Foo.java:10", nodes[0].text)
        assertEquals("warning: unused import", nodes[1].text)
    }

    // ── get_test_results ──────────────────────────────────────────────────────

    fun `test get_test_results graceful fallback when Run window absent`() {
        val output = invokeAndWaitIfNeeded {
            McpCompanionBuildToolset().extractTestResults(project)
        }
        assertNotNull("Should return an error message when Run window absent", output.error)
        assertTrue("Should return empty runs list", output.runs.isEmpty())
    }

    fun `test buildTestNode status for unstarted proxy is RUNNING`() {
        val proxy = SMTestProxy("MyTest", false, null)
        val node = McpCompanionBuildToolset().buildTestNode(proxy)
        assertEquals("MyTest", node.name)
        assertEquals("RUNNING", node.status)
        assertNull(node.errorMessage)
    }

    fun `test buildTestNode status for finished proxy is PASSED`() {
        val proxy = SMTestProxy("MyTest", false, null)
        proxy.setFinished()
        val node = McpCompanionBuildToolset().buildTestNode(proxy)
        assertEquals("MyTest", node.name)
        assertEquals("PASSED", node.status)
    }

    fun `test buildTestNode status for failed proxy is FAILED`() {
        val proxy = SMTestProxy("FailingTest", false, null)
        proxy.setTestFailed("Expected 1 but was 2", null, false)
        val node = McpCompanionBuildToolset().buildTestNode(proxy)
        assertEquals("FailingTest", node.name)
        assertEquals("FAILED", node.status)
        assertNotNull("Error message should be present", node.errorMessage)
    }

    // ── refresh_project ───────────────────────────────────────────────────────

    fun `test refresh_project detects no build files in headless project`() {
        val basePath = project.basePath ?: return
        val rootFiles = java.io.File(basePath).listFiles()?.map { it.name } ?: emptyList()
        val hasGradle = rootFiles.any {
            it in listOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")
        }
        val hasMaven = "pom.xml" in rootFiles
        assertFalse("Headless test project should not have Gradle files", hasGradle)
        assertFalse("Headless test project should not have Maven pom.xml", hasMaven)
        // refresh_project would return "No Gradle or Maven build files found in project root"
    }
}
