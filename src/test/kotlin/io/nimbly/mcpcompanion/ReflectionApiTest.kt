package io.nimbly.mcpcompanion

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import io.nimbly.mcpcompanion.util.ProcessTracker
import io.nimbly.mcpcompanion.settings.McpCompanionConfigurable
import io.nimbly.mcpcompanion.toolsets.McpCompanionVcsToolset
import io.nimbly.mcpcompanion.toolsets.McpCompanionToolset
import io.nimbly.mcpcompanion.toolsets.McpCompanionDiagnosticToolset
import io.nimbly.mcpcompanion.toolsets.McpCompanionDatabaseToolset
import io.nimbly.mcpcompanion.toolsets.McpCompanionBuildToolset

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

    // ── StatusBarEx (ProcessTracker) ──────────────────────────────────────────
    // ProcessTracker polls StatusBarEx.getBackgroundProcessesModels() via reflection every
    // 500ms to catch Gradle sync / indexing / Maven import tasks that CoreProgressManager
    // does not expose. If this method goes away, backgroundProcesses in get_ide_snapshot
    // will silently stop reporting external-system tasks — catch it here.

    @Test
    fun `StatusBarEx getBackgroundProcesses returns List of Pair TaskInfo ProgressIndicator`() {
        val cls = Class.forName("com.intellij.openapi.wm.ex.StatusBarEx")
        val method = cls.getMethod("getBackgroundProcesses")
        assertTrue(List::class.java.isAssignableFrom(method.returnType),
            "StatusBarEx.getBackgroundProcesses() must return a List")
        // We can't easily assert the parametric type at runtime, but verify the supporting
        // classes (TaskInfo, ProgressIndicator, com.intellij.openapi.util.Pair) still exist.
        Class.forName("com.intellij.openapi.progress.TaskInfo")
        Class.forName("com.intellij.openapi.progress.ProgressIndicator")
        Class.forName("com.intellij.openapi.util.Pair")
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

    // ── MessagePool / MessagePoolListener / AbstractMessage ─────────────────────
    // Used in McpCompanionStartupActivity.installMcpErrorFilter() via reflection to silence
    // the IDE "Internal Error" pop-up that the MCP framework triggers on parameter mismatches.
    // These classes are @ApiStatus.Internal — direct usage trips the plugin verifier with
    // INTERNAL_API_USAGES, hence the reflective access. If the platform renames any of them,
    // these tests fail fast at build time rather than at user runtime.

    @Test
    fun `MessagePool getInstance is a public static method`() {
        val cls = Class.forName("com.intellij.diagnostic.MessagePool")
        val method = cls.getMethod("getInstance")
        assertTrue(java.lang.reflect.Modifier.isStatic(method.modifiers),
            "MessagePool.getInstance() must be static — error-filter is broken")
    }

    @Test
    fun `MessagePool getFatalErrors and addListener exist`() {
        val cls = Class.forName("com.intellij.diagnostic.MessagePool")
        val listenerCls = Class.forName("com.intellij.diagnostic.MessagePoolListener")
        assertNotNull(
            cls.getMethod("getFatalErrors", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
            "MessagePool.getFatalErrors(boolean, boolean) not found",
        )
        assertNotNull(
            cls.getMethod("addListener", listenerCls),
            "MessagePool.addListener(MessagePoolListener) not found",
        )
    }

    @Test
    fun `MessagePoolListener has newEntryAdded method`() {
        val cls = Class.forName("com.intellij.diagnostic.MessagePoolListener")
        assertNotNull(cls.getMethod("newEntryAdded"),
            "MessagePoolListener.newEntryAdded() not found — Proxy listener won't dispatch")
    }

    @Test
    fun `AbstractMessage exposes getMessage getThrowable getThrowableText setRead`() {
        val cls = Class.forName("com.intellij.diagnostic.AbstractMessage")
        assertNotNull(cls.getMethod("getMessage"), "AbstractMessage.getMessage() not found")
        assertNotNull(cls.getMethod("getThrowable"), "AbstractMessage.getThrowable() not found")
        assertNotNull(cls.getMethod("getThrowableText"), "AbstractMessage.getThrowableText() not found")
        assertNotNull(cls.getMethod("setRead", Boolean::class.javaPrimitiveType),
            "AbstractMessage.setRead(boolean) not found")
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

    // ── McpServerSettings.MyState.setEnableMcpServer ─────────────────────────
    // Called in McpCompanionStartupActivity to auto-enable the MCP server on first launch.

    @Test
    fun `McpServerSettings MyState setEnableMcpServer exists`() {
        val cls = Class.forName("com.intellij.mcpserver.settings.McpServerSettings\$MyState")
        val method = cls.methods.find { it.name == "setEnableMcpServer" && it.parameterCount == 1 }
        assertNotNull(method, "McpServerSettings.MyState.setEnableMcpServer(Boolean) not found — auto-enable on first launch is broken")
        assertEquals(Boolean::class.javaPrimitiveType, method!!.parameterTypes[0],
            "McpServerSettings.MyState.setEnableMcpServer must take a boolean parameter")
    }

    // ── ProgressSuspender ─────────────────────────────────────────────────────
    // Used in McpCompanionDiagnosticToolset to detect suspended progress indicators.

    @Test
    fun `ProgressSuspender getSuspender static method exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        }.getOrNull() ?: run {
            println("INFO: ProgressSuspender not on classpath — skipping (optional API)")
            return
        }
        val method = runCatching {
            cls.getMethod("getSuspender", com.intellij.openapi.progress.ProgressIndicator::class.java)
        }.getOrNull()
        if (method == null) println("WARNING: ProgressSuspender.getSuspender(ProgressIndicator) not found — manage_process suspend info unavailable")
        else println("OK: ProgressSuspender.getSuspender() → ${method.returnType.name}")
    }

    @Test
    fun `ProgressSuspender ourProgressToSuspenderMap field exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        }.getOrNull() ?: run {
            println("INFO: ProgressSuspender not on classpath — skipping")
            return
        }
        val field = runCatching {
            cls.getDeclaredField("ourProgressToSuspenderMap").also { it.isAccessible = true }
        }.getOrNull()
        if (field == null) println("WARNING: ProgressSuspender.ourProgressToSuspenderMap not found — allSuspenderEntries() will return empty")
        else println("OK: ProgressSuspender.ourProgressToSuspenderMap found (${field.type.name})")
    }

    @Test
    fun `ProgressSuspender isSuspended and getSuspendedText exist`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.progress.impl.ProgressSuspender")
        }.getOrNull() ?: run {
            println("INFO: ProgressSuspender not on classpath — skipping")
            return
        }
        for (name in listOf("isSuspended", "getSuspendedText")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: ProgressSuspender.$name() found")
            else println("WARNING: ProgressSuspender.$name() not found — diagnostic suspend status unavailable")
        }
    }

    // ── EditorComponentImpl (ConsoleUtil + get_services_output) ──────────────
    // ConsoleUtil.readText() and readEditorText() both use EditorComponentImpl → myEditor field.

    @Test
    fun `EditorComponentImpl class exists`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.editor.impl.EditorComponentImpl")
        }.getOrNull()
        assertNotNull(cls, "EditorComponentImpl not found — ConsoleUtil.readText() and readEditorText() are broken")
        println("OK: EditorComponentImpl found")
    }

    @Test
    fun `EditorComponentImpl has myEditor or editor field`() {
        val cls = runCatching {
            Class.forName("com.intellij.openapi.editor.impl.EditorComponentImpl")
        }.getOrNull() ?: run {
            println("INFO: EditorComponentImpl not on classpath — skipping field check")
            return
        }
        val field = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .find { it.name == "myEditor" || it.name == "editor" }
        assertNotNull(field,
            "EditorComponentImpl has no 'myEditor' or 'editor' field — ConsoleUtil editor extraction is broken")
        assertTrue(com.intellij.openapi.editor.Editor::class.java.isAssignableFrom(field!!.type),
            "EditorComponentImpl.${field.name} must be of type Editor")
        println("OK: EditorComponentImpl.${field.name}: ${field.type.simpleName}")
    }

    // ── Build node reflection (McpCompanionBuildToolset) ─────────────────────
    // Build output nodes are accessed via reflection on their actual runtime type.
    // The key contract: AbstractTreeNode-like objects expose getTitle/getHint/getElement.

    @Test
    fun `ExecutionNode getTitle and getHint exist`() {
        val cls = runCatching {
            Class.forName("com.intellij.build.ExecutionNode")
        }.getOrNull() ?: run {
            println("INFO: ExecutionNode not on test classpath — skipping (build node reflection verified at runtime)")
            return
        }
        for (name in listOf("getTitle", "getHint")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: ExecutionNode.$name() found")
            else println("WARNING: ExecutionNode.$name() not found — get_build_output title/hint extraction may degrade")
        }
    }

    // ── Database plugin reflection (McpCompanionDatabaseToolset) ─────────────
    // The Database plugin (com.intellij.database) is only available in IntelliJ IDEA Ultimate.
    // If the plugin is absent from the test environment → skip with INFO (acceptable).
    // If the plugin IS present → all assertions are hard failures: an API removal must break the build.

    /** Returns the Database plugin classloader, or null if not installed in this JVM. */
    private fun dbClassLoader(): ClassLoader? =
        runCatching {
            val pluginId = com.intellij.openapi.extensions.PluginId.getId("com.intellij.database")
            com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId)?.pluginClassLoader
        }.getOrNull()

    private fun dbClass(name: String, cl: ClassLoader): Class<*> =
        cl.loadClass(name)  // throws ClassNotFoundException → caught by caller to fail the test

    @Test
    fun `Database plugin classloader is accessible`() {
        val cl = dbClassLoader()
        if (cl == null) {
            println("INFO: Database plugin (com.intellij.database) not available — all DB reflection tests will be skipped")
        } else {
            println("OK: Database plugin classloader found: ${cl.javaClass.name}")
        }
    }

    @Test
    fun `LocalDataSourceManager getInstance and getDataSources exist`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.dataSource.LocalDataSourceManager", cl)
        val getInstance = cls.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
        assertTrue(java.lang.reflect.Modifier.isStatic(getInstance.modifiers),
            "LocalDataSourceManager.getInstance(Project) must be static")
        println("OK: LocalDataSourceManager.getInstance(Project) found")
        assertNotNull(cls.getMethod("getDataSources"),
            "LocalDataSourceManager.getDataSources() not found")
        println("OK: LocalDataSourceManager.getDataSources() found")
    }

    @Test
    fun `DasModel getModelRoots and traverser exist`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.DasModel", cl)
        for (name in listOf("getModelRoots", "traverser")) {
            assertTrue(cls.methods.any { it.name == name && it.parameterCount == 0 },
                "DasModel.$name() not found — get_database_schema schema traversal broken")
            println("OK: DasModel.$name() found")
        }
    }

    @Test
    fun `ObjectKind enum fields exist (SCHEMA TABLE VIEW COLUMN KEY FOREIGN_KEY INDEX DATABASE)`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.ObjectKind", cl)
        for (fieldName in listOf("SCHEMA", "TABLE", "VIEW", "COLUMN", "KEY", "FOREIGN_KEY", "INDEX", "DATABASE")) {
            assertNotNull(runCatching { cls.getField(fieldName) }.getOrNull(),
                "ObjectKind.$fieldName not found — getDasChildren traversal for that kind is broken")
            println("OK: ObjectKind.$fieldName found")
        }
    }

    @Test
    fun `DasNamed getName and getKind exist`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.DasNamed", cl)
        for (name in listOf("getName", "getKind")) {
            assertTrue(cls.methods.any { it.name == name && it.parameterCount == 0 },
                "DasNamed.$name() not found — invokeViaInterface primary path for $name is broken")
            println("OK: DasNamed.$name() found")
        }
    }

    @Test
    fun `DasObject getDasChildren exists`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.DasObject", cl)
        val kindCls = dbClass("com.intellij.database.model.ObjectKind", cl)
        val method = runCatching { cls.getMethod("getDasChildren", kindCls) }.getOrNull()
        assertNotNull(method, "DasObject.getDasChildren(ObjectKind) not found — schema child traversal broken")
        println("OK: DasObject.getDasChildren(${method!!.parameterTypes[0].simpleName}) → ${method.returnType.simpleName}")
    }

    @Test
    fun `DasTypedObject getDataType isNotNull getDefault exist`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.DasTypedObject", cl)
        for (name in listOf("getDataType", "isNotNull", "getDefault")) {
            assertTrue(cls.methods.any { it.name == name && it.parameterCount == 0 },
                "DasTypedObject.$name() not found — column type detail for that field is broken")
            println("OK: DasTypedObject.$name() found")
        }
    }

    @Test
    fun `DataType fields typeName size scale exist`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.DataType", cl)
        for (fieldName in listOf("typeName", "size", "scale")) {
            val field = runCatching { cls.getField(fieldName) }.getOrNull()
                ?: runCatching { cls.getDeclaredField(fieldName) }.getOrNull()
            assertNotNull(field, "DataType.$fieldName not found — column type info will be '?'")
            println("OK: DataType.$fieldName: ${field!!.type.simpleName}")
        }
    }

    @Test
    fun `DasPositioned getPosition exists`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.DasPositioned", cl)
        assertTrue(cls.methods.any { it.name == "getPosition" && it.parameterCount == 0 },
            "DasPositioned.getPosition() not found — column ordering will be broken")
        println("OK: DasPositioned.getPosition() found")
    }

    @Test
    fun `DasConstraint getColumnsRef exists`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.DasConstraint", cl)
        assertTrue(cls.methods.any { it.name == "getColumnsRef" && it.parameterCount == 0 },
            "DasConstraint.getColumnsRef() not found — key/index column names will be empty")
        println("OK: DasConstraint.getColumnsRef() found")
    }

    @Test
    fun `DasForeignKey getRefTableName getRefTableSchema getRefColumns exist`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.DasForeignKey", cl)
        for (name in listOf("getRefTableName", "getRefTableSchema", "getRefColumns")) {
            assertTrue(cls.methods.any { it.name == name && it.parameterCount == 0 },
                "DasForeignKey.$name() not found — foreign key detail for that field is broken")
            println("OK: DasForeignKey.$name() found")
        }
    }

    @Test
    fun `DasIndex isUnique and getColumnsRef exist`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.DasIndex", cl)
        for (name in listOf("isUnique", "getColumnsRef")) {
            assertTrue(cls.methods.any { it.name == name && it.parameterCount == 0 },
                "DasIndex.$name() not found — index detail for that field is broken")
            println("OK: DasIndex.$name() found")
        }
    }

    @Test
    fun `MultiRef names exists`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.model.MultiRef", cl)
        assertTrue(cls.methods.any { it.name == "names" && it.parameterCount == 0 },
            "MultiRef.names() not found — column name lists for keys/indexes/FKs will be empty")
        println("OK: MultiRef.names() found")
    }

    @Test
    fun `DatabaseConnectionManager getInstance and build exist`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val cls = dbClass("com.intellij.database.dataSource.DatabaseConnectionManager", cl)
        // getInstance may be absent if the service is registered differently — tolerate
        val getInstance = runCatching { cls.getMethod("getInstance") }.getOrNull()
        if (getInstance != null) {
            assertTrue(java.lang.reflect.Modifier.isStatic(getInstance.modifiers),
                "DatabaseConnectionManager.getInstance() must be static")
            println("OK: DatabaseConnectionManager.getInstance() found")
        } else {
            println("INFO: DatabaseConnectionManager.getInstance() absent — ApplicationManager service lookup will be used")
        }
        val build = cls.methods.firstOrNull { it.name == "build" && it.parameterCount == 2 }
        assertNotNull(build, "DatabaseConnectionManager.build(project, source) not found — execute_database_query is broken")
        println("OK: DatabaseConnectionManager.build(${build!!.parameterTypes.joinToString { it.simpleName }}) found")
    }

    @Test
    fun `RemoteConnection createStatement exists`() {
        val cl = dbClassLoader() ?: return println("INFO: Database plugin not available — skipping")
        val connCls = runCatching { dbClass("com.intellij.database.remote.jdbc.RemoteConnection", cl) }.getOrNull()
            ?: runCatching { dbClass("com.intellij.database.connection.DatabaseConnection", cl) }.getOrNull()
        assertNotNull(connCls, "Neither RemoteConnection nor DatabaseConnection found — execute_database_query is broken")
        assertTrue(connCls!!.methods.any { it.name == "createStatement" && it.parameterCount == 0 },
            "${connCls.simpleName}.createStatement() not found — query execution is broken")
        println("OK: ${connCls.simpleName}.createStatement() found")
    }

    // ── VCS / Git4Idea reflection (McpCompanionVcsToolset) ────────────────────
    // Git4Idea is bundled in all IntelliJ IDEA editions — tests are BLOCKING when the plugin is present.
    // If absent (e.g. Android Studio without Git plugin) → skip with INFO.
    // Platform VCS classes (ChangeListManager, ProjectLevelVcsManager, FileAnnotation, etc.) are
    // always on the compile classpath via local(...) — tested via direct Class.forName().

    @Test
    fun `ChangeListManager getInstance and getAllChanges exist`() {
        val cls = Class.forName("com.intellij.openapi.vcs.changes.ChangeListManager")
        val getInstance = cls.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
        assertTrue(java.lang.reflect.Modifier.isStatic(getInstance.modifiers),
            "ChangeListManager.getInstance(Project) must be static")
        assertNotNull(cls.methods.firstOrNull { it.name == "getAllChanges" && it.parameterCount == 0 },
            "ChangeListManager.getAllChanges() not found — get_vcs_changes is broken")
        assertNotNull(cls.methods.firstOrNull { it.name == "getUnversionedFilesPaths" && it.parameterCount == 0 },
            "ChangeListManager.getUnversionedFilesPaths() not found — get_vcs_changes unversioned list is broken")
        println("OK: ChangeListManager.getInstance / getAllChanges / getUnversionedFilesPaths found")
    }

    @Test
    fun `Change getType getBeforeRevision getAfterRevision exist`() {
        val cls = Class.forName("com.intellij.openapi.vcs.changes.Change")
        for (name in listOf("getType", "getBeforeRevision", "getAfterRevision")) {
            assertNotNull(cls.methods.firstOrNull { it.name == name && it.parameterCount == 0 },
                "Change.$name() not found — get_vcs_changes is broken")
            println("OK: Change.$name() found")
        }
    }

    @Test
    fun `Change Type enum values MODIFICATION NEW DELETED MOVED exist`() {
        val cls = Class.forName("com.intellij.openapi.vcs.changes.Change\$Type")
        for (name in listOf("MODIFICATION", "NEW", "DELETED", "MOVED")) {
            assertNotNull(runCatching { cls.getField(name) }.getOrNull(),
                "Change.Type.$name not found — get_vcs_changes type classification is broken")
            println("OK: Change.Type.$name found")
        }
    }

    @Test
    fun `ContentRevision getContent and getFile exist`() {
        val cls = Class.forName("com.intellij.openapi.vcs.changes.ContentRevision")
        for (name in listOf("getContent", "getFile")) {
            assertNotNull(cls.methods.firstOrNull { it.name == name && it.parameterCount == 0 },
                "ContentRevision.$name() not found — get_vcs_changes diff generation is broken")
            println("OK: ContentRevision.$name() found")
        }
    }

    @Test
    fun `ProjectLevelVcsManager getInstance and getVcsFor exist`() {
        val cls = Class.forName("com.intellij.openapi.vcs.ProjectLevelVcsManager")
        val getInstance = cls.methods.firstOrNull { it.name == "getInstance" }
        assertNotNull(getInstance, "ProjectLevelVcsManager.getInstance() not found — get_vcs_blame is broken")
        println("OK: ProjectLevelVcsManager.getInstance() found")
        val getVcsFor = cls.methods.firstOrNull { it.name == "getVcsFor" && it.parameterCount == 1 }
        assertNotNull(getVcsFor, "ProjectLevelVcsManager.getVcsFor(VirtualFile) not found — get_vcs_blame is broken")
        println("OK: ProjectLevelVcsManager.getVcsFor() found")
    }

    @Test
    fun `AbstractVcs getAnnotationProvider exists`() {
        val cls = Class.forName("com.intellij.openapi.vcs.AbstractVcs")
        assertNotNull(cls.methods.firstOrNull { it.name == "getAnnotationProvider" && it.parameterCount == 0 },
            "AbstractVcs.getAnnotationProvider() not found — get_vcs_blame is broken")
        println("OK: AbstractVcs.getAnnotationProvider() found")
    }

    @Test
    fun `FileAnnotation key methods exist`() {
        val cls = Class.forName("com.intellij.openapi.vcs.annotate.FileAnnotation")
        for (name in listOf("getLineCount", "getAspects", "getLineRevisionNumber", "getLineDate", "getToolTip", "close")) {
            assertNotNull(cls.methods.firstOrNull { it.name == name },
                "FileAnnotation.$name() not found — get_vcs_blame is broken")
            println("OK: FileAnnotation.$name() found")
        }
    }

    @Test
    fun `LineAnnotationAspect constants AUTHOR DATE REVISION exist`() {
        val cls = Class.forName("com.intellij.openapi.vcs.annotate.LineAnnotationAspect")
        for (name in listOf("AUTHOR", "DATE", "REVISION")) {
            assertNotNull(runCatching { cls.getField(name) }.getOrNull(),
                "LineAnnotationAspect.$name constant not found — get_vcs_blame aspect lookup is broken")
            println("OK: LineAnnotationAspect.$name found")
        }
        assertNotNull(cls.methods.firstOrNull { it.name == "getValue" && it.parameterCount == 1 },
            "LineAnnotationAspect.getValue(int) not found — get_vcs_blame aspect value retrieval is broken")
        println("OK: LineAnnotationAspect.getValue(int) found")
    }

    /** Returns Git4Idea classloader, or null if not installed. */
    private fun git4ideaClassLoader(): ClassLoader? =
        runCatching {
            val id = com.intellij.openapi.extensions.PluginId.getId("Git4Idea")
            com.intellij.ide.plugins.PluginManagerCore.getPlugin(id)?.pluginClassLoader
        }.getOrNull()

    @Test
    fun `Git4Idea GitRepositoryManager getInstance and getRepositories exist`() {
        val cl = git4ideaClassLoader() ?: return println("INFO: Git4Idea not available — skipping")
        val cls = cl.loadClass("git4idea.repo.GitRepositoryManager")
        val getInstance = cls.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
        assertTrue(java.lang.reflect.Modifier.isStatic(getInstance.modifiers),
            "GitRepositoryManager.getInstance(Project) must be static")
        assertNotNull(cls.methods.firstOrNull { it.name == "getRepositories" && it.parameterCount == 0 },
            "GitRepositoryManager.getRepositories() not found — get_vcs_branch and get_vcs_log are broken")
        println("OK: GitRepositoryManager.getInstance / getRepositories found")
    }

    @Test
    fun `Git4Idea GitRepository getCurrentBranch and getBranches exist`() {
        val cl = git4ideaClassLoader() ?: return println("INFO: Git4Idea not available — skipping")
        val cls = cl.loadClass("git4idea.repo.GitRepository")
        for (name in listOf("getCurrentBranch", "getBranches", "getRoot")) {
            assertNotNull(cls.methods.firstOrNull { it.name == name && it.parameterCount == 0 },
                "GitRepository.$name() not found — get_vcs_branch is broken")
            println("OK: GitRepository.$name() found")
        }
    }

    @Test
    fun `Git4Idea GitBranchesCollection getLocalBranches and getRemoteBranches exist`() {
        val cl = git4ideaClassLoader() ?: return println("INFO: Git4Idea not available — skipping")
        val cls = cl.loadClass("git4idea.branch.GitBranchesCollection")
        for (name in listOf("getLocalBranches", "getRemoteBranches")) {
            assertNotNull(cls.methods.firstOrNull { it.name == name && it.parameterCount == 0 },
                "GitBranchesCollection.$name() not found — get_vcs_branch branch listing is broken")
            println("OK: GitBranchesCollection.$name() found")
        }
    }

    @Test
    fun `Git4Idea GitHistoryUtils history method exists`() {
        val cl = git4ideaClassLoader() ?: return println("INFO: Git4Idea not available — skipping")
        val cls = cl.loadClass("git4idea.history.GitHistoryUtils")
        val method = cls.methods.firstOrNull { m ->
            m.name == "history" && m.parameterCount == 3 &&
            m.parameterTypes[0] == com.intellij.openapi.project.Project::class.java &&
            m.parameterTypes[1] == com.intellij.openapi.vfs.VirtualFile::class.java
        }
        assertNotNull(method, "GitHistoryUtils.history(Project, VirtualFile, String[]) not found — get_vcs_log is broken")
        assertTrue(java.lang.reflect.Modifier.isStatic(method!!.modifiers),
            "GitHistoryUtils.history() must be static")
        println("OK: GitHistoryUtils.history(Project, VirtualFile, String[]) found")
    }

    @Test
    fun `Git4Idea GitCommit getId getAuthor getAuthorTime getSubject getFullMessage getAffectedPaths exist`() {
        val cl = git4ideaClassLoader() ?: return println("INFO: Git4Idea not available — skipping")
        val cls = cl.loadClass("git4idea.GitCommit")
        for (name in listOf("getId", "getAuthor", "getAuthorTime", "getSubject", "getFullMessage", "getAffectedPaths")) {
            assertNotNull(cls.methods.firstOrNull { it.name == name && it.parameterCount == 0 },
                "GitCommit.$name() not found — get_vcs_log commit details are broken")
            println("OK: GitCommit.$name() found")
        }
    }

    // ── LocalHistory (get_local_history) ──────────────────────────────────────

    @Test
    fun `LocalHistory class and getInstance exist`() {
        val cls = Class.forName("com.intellij.history.LocalHistory")
        val getInstance = cls.methods.firstOrNull { it.name == "getInstance" && it.parameterCount == 0 }
        assertNotNull(getInstance, "LocalHistory.getInstance() not found — get_local_history is broken")
        assertTrue(java.lang.reflect.Modifier.isStatic(getInstance!!.modifiers),
            "LocalHistory.getInstance() must be static")
        println("OK: LocalHistory.getInstance() found")
    }

    @Test
    fun `LocalHistoryImpl getFacade and getGateway exist`() {
        val cls = Class.forName("com.intellij.history.integration.LocalHistoryImpl")
        for (name in listOf("getFacade", "getGateway")) {
            val m = cls.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
            assertNotNull(m, "LocalHistoryImpl.$name() not found — get_local_history is broken")
            println("OK: LocalHistoryImpl.$name() found")
        }
    }

    @Test
    fun `LocalHistoryImpl getFacade returns object with getChangeList method`() {
        // The facade's runtime class (LocalHistoryFacadeImpl or similar) has getChangeList;
        // we verify LocalHistoryImpl exists and getFacade() is accessible — the runtime check
        // happens in get_local_history itself via reflection on the live facade object.
        val implCls = Class.forName("com.intellij.history.integration.LocalHistoryImpl")
        val getFacade = implCls.methods.firstOrNull { it.name == "getFacade" && it.parameterCount == 0 }
        assertNotNull(getFacade, "LocalHistoryImpl.getFacade() not found — get_local_history cannot access facade")
        // Also verify the core ChangeList class has getChangeList-related methods accessible
        val changeListCls = runCatching { Class.forName("com.intellij.history.core.ChangeList") }.getOrNull()
        if (changeListCls != null) {
            println("OK: com.intellij.history.core.ChangeList is on classpath")
        } else {
            println("INFO: com.intellij.history.core.ChangeList not directly loadable — accessed via facade at runtime")
        }
        println("OK: LocalHistoryImpl.getFacade() found — getChangeList resolved at runtime on live facade object")
    }

    @Test
    fun `IdeaGateway getPathOrUrl and registerUnsavedDocuments exist`() {
        val cls = Class.forName("com.intellij.history.integration.IdeaGateway")
        val getPathOrUrl = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "getPathOrUrl" && it.parameterCount == 1 }
        assertNotNull(getPathOrUrl, "IdeaGateway.getPathOrUrl(VirtualFile) not found — get_local_history path resolution is broken")
        println("OK: IdeaGateway.getPathOrUrl() found")
        val registerUnsaved = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "registerUnsavedDocuments" }
        assertNotNull(registerUnsaved, "IdeaGateway.registerUnsavedDocuments() not found — get_local_history may miss unsaved edits")
        println("OK: IdeaGateway.registerUnsavedDocuments() found")
    }

    @Test
    fun `ChangeList iterChanges method exists`() {
        // ChangeList is obtained at runtime; we verify the class exists and has iterChanges
        val changeListCls = runCatching {
            Class.forName("com.intellij.history.core.ChangeList")
        }.getOrNull()
        if (changeListCls == null) {
            println("INFO: com.intellij.history.core.ChangeList not directly loadable — skipping (found via facade at runtime)")
            return
        }
        val iterChanges = generateSequence(changeListCls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == "iterChanges" }
        assertNotNull(iterChanges, "ChangeList.iterChanges() not found — get_local_history revision listing is broken")
        println("OK: ChangeList.iterChanges() found")
    }

    @Test
    fun `FileHistoryDialogModel optional — constructor and revision method exist`() {
        // Optional: used for file-specific history with valid timestamps; falls back to ChangeList if missing
        val cls = runCatching {
            Class.forName("com.intellij.history.integration.ui.models.FileHistoryDialogModel")
        }.getOrNull()
        if (cls == null) {
            println("WARNING: FileHistoryDialogModel not found — file history will use ChangeList fallback only")
            return
        }
        val ctor = cls.constructors.firstOrNull { it.parameterCount in 4..5 }
        if (ctor == null) {
            println("WARNING: FileHistoryDialogModel has no 4-or-5-param constructor — file history will use ChangeList fallback")
            return
        }
        val revisionMethod = generateSequence(cls as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name in listOf("getRevisions", "calcRevisionsInBackground", "getItems") && it.parameterCount == 0 }
        if (revisionMethod == null) {
            println("WARNING: FileHistoryDialogModel has no getRevisions/calcRevisionsInBackground/getItems — file history will use ChangeList fallback")
            return
        }
        println("OK: FileHistoryDialogModel constructor(${ctor.parameterCount} params) and ${revisionMethod.name}() found")
    }

    @Test
    fun `FileRevisionTimestampComparator optional — functional interface for getByteContent proxy`() {
        // Optional: used to retrieve file content at a specific timestamp via LocalHistory.getByteContent()
        val cls = runCatching {
            Class.forName("com.intellij.history.FileRevisionTimestampComparator")
        }.getOrNull()
        if (cls == null) {
            println("WARNING: FileRevisionTimestampComparator not found — withDiff on file history will be unavailable")
            return
        }
        assertTrue(cls.isInterface, "FileRevisionTimestampComparator should be an interface")
        val lhCls = Class.forName("com.intellij.history.LocalHistory")
        val getByteContent = lhCls.methods.firstOrNull { it.name == "getByteContent" && it.parameterCount == 2 }
        if (getByteContent == null) {
            println("WARNING: LocalHistory.getByteContent(VirtualFile, FileRevisionTimestampComparator) not found — diffs unavailable")
            return
        }
        println("OK: FileRevisionTimestampComparator + LocalHistory.getByteContent() found — diffs are available")
    }

    // ── create_run_configuration_from_xml / modify_run_configuration ─────────

    @Test
    fun `ConfigurationType has static CONFIGURATION_TYPE_EP field`() {
        val field = runCatching {
            com.intellij.execution.configurations.ConfigurationType::class.java
                .getField("CONFIGURATION_TYPE_EP")
        }.getOrNull()
        assertNotNull(field, "ConfigurationType.CONFIGURATION_TYPE_EP not found — create_run_configuration_from_xml cannot enumerate config types")
        println("OK: ConfigurationType.CONFIGURATION_TYPE_EP found (type: ${field!!.type.simpleName})")
    }

    @Test
    fun `CommonProgramRunConfigurationParameters interface exists with expected setter methods`() {
        // Optional: this interface is in the Java execution module which may not be on the test classpath.
        // modify_run_configuration uses runCatching per setter and gracefully reports unsupported configs.
        val iface = runCatching {
            Class.forName("com.intellij.execution.configurations.CommonProgramRunConfigurationParameters")
        }.getOrNull()
        if (iface == null) {
            println("WARNING: CommonProgramRunConfigurationParameters not found on test classpath — modify_run_configuration will report 'does not support modification' for Application configs")
            return
        }
        assertTrue(iface.isInterface, "CommonProgramRunConfigurationParameters should be an interface")
        println("OK: CommonProgramRunConfigurationParameters interface found")
        val methods = iface.methods.map { it.name }
        listOf("setVMParameters", "setProgramParameters", "setWorkingDirectory", "setEnvs").forEach { name ->
            if (methods.contains(name)) println("OK: CommonProgramRunConfigurationParameters.$name() found")
            else println("WARNING: CommonProgramRunConfigurationParameters.$name() not found — modify_run_configuration will silently skip this parameter")
        }
    }

    // ── get_ide_snapshot ─────────────────────────────────────────────────────

    @Test
    fun `ProcessHandler getExitCode is accessible by reflection on terminated processes`() {
        // get_ide_snapshot uses reflection on ProcessHandler.getExitCode() to read the exit status
        // of finished runs. The method may not be on the abstract ProcessHandler class (it's
        // declared on concrete implementations like OSProcessHandler), so we use reflection with
        // runCatching in production code. This test just verifies the method name is still used
        // by at least one known concrete implementation.
        val concrete = runCatching {
            Class.forName("com.intellij.execution.process.BaseProcessHandler")
        }.getOrNull() ?: runCatching {
            Class.forName("com.intellij.execution.process.ProcessHandler")
        }.getOrNull()
        if (concrete == null) {
            println("WARNING: ProcessHandler class not found on test classpath — get_ide_snapshot exitCode extraction may silently return null")
            return
        }
        val method = runCatching { concrete.getMethod("getExitCode") }.getOrNull()
        if (method != null) println("OK: ${concrete.simpleName}.getExitCode() found (returns ${method.returnType.simpleName})")
        else println("INFO: getExitCode() not on ${concrete.simpleName} directly — will rely on concrete subclasses (OSProcessHandler etc.)")
    }
}
