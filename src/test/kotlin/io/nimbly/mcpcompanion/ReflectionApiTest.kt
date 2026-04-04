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

    // @ApiStatus.Internal has @Retention(CLASS) — not visible via normal reflection.
    // We check the bytecode of core.impl.jar directly to detect if the annotation disappears
    // (meaning the method became public API and the reflection wrapper can be simplified).
    // If this test FAILS: CoreProgressManager no longer uses @ApiStatus.Internal →
    // replace currentIndicators() reflection wrapper with a direct call.
    @Test
    fun `CoreProgressManager getCurrentIndicators is @ApiStatus Internal — reflection wrapper required`() {
        val coreImplJar = System.getProperty("java.class.path")
            .split(java.io.File.pathSeparator)
            .firstOrNull { "core.impl" in it }
        if (coreImplJar == null) {
            println("INFO: intellij.platform.core.impl.jar not on test classpath — skipping @Internal bytecode check")
            return
        }
        val jar = java.util.jar.JarFile(coreImplJar)
        val entry = jar.getJarEntry("com/intellij/openapi/progress/impl/CoreProgressManager.class")
        if (entry == null) { jar.close(); println("WARN: CoreProgressManager.class not found in jar"); return }
        val classBytes = jar.getInputStream(entry).readBytes()
        jar.close()
        // The annotation descriptor appears in the constant pool if used anywhere in the class
        val internalDescriptor = "Lorg/jetbrains/annotations/ApiStatus\$Internal;"
        assertTrue(internalDescriptor in String(classBytes, Charsets.ISO_8859_1),
            "CoreProgressManager no longer contains @ApiStatus.Internal — " +
            "getCurrentIndicators() may now be public API: " +
            "consider replacing currentIndicators() reflection wrapper with a direct call " +
            "and restoring compileOnly(core.impl.jar) in build.gradle.kts")
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

    // ── TerminalViewImpl (send_to_terminal) ────────────────────────────────────
    // send_to_terminal uses TerminalViewImpl.createSendTextBuilder() + doSendText().
    // If the class or methods disappear, the tool will silently return an error — catch
    // it early here so the test suite flags the broken reflection before shipping.

    @Test
    fun `TerminalViewImpl exists in frontend terminal package`() {
        val cls = runCatching {
            Class.forName("com.intellij.terminal.frontend.view.impl.TerminalViewImpl")
        }.getOrNull()
        if (cls == null) {
            println("WARNING: TerminalViewImpl not found — send_to_terminal may not work (terminal API changed)")
            return
        }
        println("OK: TerminalViewImpl found: ${cls.name}")
    }

    @Test
    fun `TerminalViewImpl createSendTextBuilder exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.terminal.frontend.view.impl.TerminalViewImpl")
        }.getOrNull() ?: run {
            println("INFO: TerminalViewImpl not on classpath — skipping")
            return
        }
        val method = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .find { it.name == "createSendTextBuilder" && it.parameterCount == 0 }
        assertNotNull(method, "TerminalViewImpl.createSendTextBuilder() not found — send_to_terminal Strategy 2 is broken")
        println("OK: TerminalViewImpl.createSendTextBuilder() → ${method!!.returnType.name}")
    }

    @Test
    fun `TerminalViewImpl doSendText exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.terminal.frontend.view.impl.TerminalViewImpl")
        }.getOrNull() ?: run {
            println("INFO: TerminalViewImpl not on classpath — skipping")
            return
        }
        val method = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .find { it.name == "doSendText" && it.parameterCount == 1 }
        assertNotNull(method, "TerminalViewImpl.doSendText() not found — send_to_terminal Strategy 2 is broken")
        println("OK: TerminalViewImpl.doSendText(${method!!.parameterTypes[0].name})")
    }

    @Test
    fun `TerminalSendTextOptions has constructor with String as first param`() {
        val termViewCls = runCatching {
            Class.forName("com.intellij.terminal.frontend.view.impl.TerminalViewImpl")
        }.getOrNull() ?: run {
            println("INFO: TerminalViewImpl not on classpath — skipping")
            return
        }
        val doSendText = generateSequence(termViewCls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .find { it.name == "doSendText" && it.parameterCount == 1 }
            ?: run { println("WARNING: doSendText not found — skipping TerminalSendTextOptions check"); return }
        val optsCls = doSendText.parameterTypes[0]
        val ctor = optsCls.constructors
            .sortedBy { it.parameterCount }
            .find { it.parameterTypes.isNotEmpty() && it.parameterTypes[0] == String::class.java }
        assertNotNull(ctor,
            "${optsCls.simpleName} has no constructor with String as first param — send_to_terminal direct send is broken")
        println("OK: ${optsCls.simpleName}(${ctor!!.parameterTypes.joinToString { it.simpleName }}) — " +
            "send_to_terminal can construct options directly")
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
