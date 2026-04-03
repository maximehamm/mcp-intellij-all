package io.nimbly.mcpcompanion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Verifies that every method accessed via reflection in this plugin still exists
 * in the current IntelliJ version. A failure here means an IntelliJ API has moved
 * or been removed — update the corresponding code before shipping.
 */
class ReflectionApiTest {

    // ── CoreProgressManager ───────────────────────────────────────────────────

    @Test
    fun `CoreProgressManager getCurrentIndicators is a public static method`() {
        val cls = Class.forName("com.intellij.openapi.progress.impl.CoreProgressManager")
        val method = cls.getMethod("getCurrentIndicators")
        assertTrue(java.lang.reflect.Modifier.isStatic(method.modifiers),
            "CoreProgressManager.getCurrentIndicators() must be static")
        assertTrue(List::class.java.isAssignableFrom(method.returnType),
            "CoreProgressManager.getCurrentIndicators() must return a List")
    }

    // ── TaskInfo ──────────────────────────────────────────────────────────────

    @Test
    fun `TaskInfo getTitle exists`() {
        val cls = Class.forName("com.intellij.openapi.progress.TaskInfo")
        val method = cls.getMethod("getTitle")
        assertEquals(String::class.java, method.returnType,
            "TaskInfo.getTitle() must return String")
    }

    // ── BackgroundableProcessIndicator ────────────────────────────────────────
    // Optional: class is in intellij.platform.ide.impl.jar which may not be on all test classpaths.

    @Test
    fun `BackgroundableProcessIndicator getTaskInfo exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.progress.BackgroundableProcessIndicator")
        }.getOrNull()
        if (cls == null) {
            println("INFO: BackgroundableProcessIndicator not on test classpath — skipping (verified at runtime)")
            return
        }
        val method = cls.getMethod("getTaskInfo")
        val taskInfoCls = Class.forName("com.intellij.openapi.progress.TaskInfo")
        assertTrue(taskInfoCls.isAssignableFrom(method.returnType),
            "BackgroundableProcessIndicator.getTaskInfo() must return a TaskInfo")
    }

    // ── McpToolset.isEnabled ──────────────────────────────────────────────────
    // We override this explicitly to avoid a Kotlin-generated invokespecial bridge.
    // If isEnabled() disappears from McpToolset, our override becomes a no-op and
    // can be removed; if it changes signature, this test will catch it.

    @Test
    fun `McpToolset isEnabled signature is boolean if present`() {
        val cls = Class.forName("com.intellij.mcpserver.McpToolset")
        val method = runCatching { cls.getMethod("isEnabled") }.getOrNull()
        if (method == null) {
            println("INFO: McpToolset.isEnabled() no longer exists — override in McpCompanionToolset can be removed")
            return
        }
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType,
            "McpToolset.isEnabled() must return boolean")
    }

    // ── McpServerSettings ─────────────────────────────────────────────────────
    // Used in McpCompanionConfigurable.isMcpServerEnabled() via reflection to avoid
    // a hard compile-time dependency on the MCP Server plugin.

    @Test
    fun `McpServerSettings getInstance is a public static method`() {
        val cls = Class.forName("com.intellij.mcpserver.settings.McpServerSettings")
        val method = cls.getMethod("getInstance")
        assertTrue(java.lang.reflect.Modifier.isStatic(method.modifiers),
            "McpServerSettings.getInstance() must be static")
    }

    @Test
    fun `McpServerSettings getState exists`() {
        val cls = Class.forName("com.intellij.mcpserver.settings.McpServerSettings")
        assertNotNull(cls.getMethod("getState"),
            "McpServerSettings.getState() not found")
    }

    @Test
    fun `McpServerSettings MyState getEnableMcpServer returns boolean`() {
        val cls = Class.forName("com.intellij.mcpserver.settings.McpServerSettings\$MyState")
        val method = cls.getMethod("getEnableMcpServer")
        assertEquals(Boolean::class.javaPrimitiveType, method.returnType,
            "McpServerSettings.MyState.getEnableMcpServer() must return boolean")
    }

    // ── Optional: isCancellable / suspend / resume ────────────────────────────
    // These are best-effort: our code handles their absence gracefully.
    // Tests print a warning rather than failing — a future removal surfaces
    // in the test output without breaking the build.

    @Test
    fun `AbstractProgressIndicatorBase optional methods isCancellable suspend resume`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.progress.util.AbstractProgressIndicatorBase")
        }.getOrNull()
        if (cls == null) {
            println("WARNING: AbstractProgressIndicatorBase not found — manage_process pause/resume/cancellable may not work")
            return
        }
        for (name in listOf("isCancellable", "suspend", "resume")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: AbstractProgressIndicatorBase.$name() found")
            else println("WARNING: AbstractProgressIndicatorBase.$name() not found — manage_process($name) will fall back gracefully")
        }
    }
}
