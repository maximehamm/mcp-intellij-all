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
    // The Database plugin (com.intellij.database) is optional and only available in
    // IntelliJ IDEA Ultimate. All tests use runCatching + WARNING/INFO — never fail().
    // Classes must be loaded via the Database plugin's own classloader.

    /** Returns the Database plugin classloader, or null if not available in this test environment. */
    private fun dbClassLoader(): ClassLoader? =
        runCatching {
            val pluginId = com.intellij.openapi.extensions.PluginId.getId("com.intellij.database")
            com.intellij.ide.plugins.PluginManagerCore.getPlugin(pluginId)?.pluginClassLoader
        }.getOrNull()

    private fun dbClass(name: String, cl: ClassLoader): Class<*>? =
        runCatching { cl.loadClass(name) }.getOrNull()

    @Test
    fun `Database plugin classloader is accessible`() {
        val cl = dbClassLoader()
        if (cl == null) {
            println("INFO: Database plugin (com.intellij.database) not available in this test environment — all DB reflection tests will be skipped")
        } else {
            println("OK: Database plugin classloader found: ${cl.javaClass.name}")
        }
    }

    @Test
    fun `LocalDataSourceManager getInstance and getDataSources exist`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping LocalDataSourceManager check")
            return
        }
        val cls = dbClass("com.intellij.database.dataSource.LocalDataSourceManager", cl) ?: run {
            println("WARNING: LocalDataSourceManager class not found — list_database_sources and get_database_schema will not work")
            return
        }
        val getInstance = runCatching {
            cls.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
        }.getOrNull()
        if (getInstance == null) println("WARNING: LocalDataSourceManager.getInstance(Project) not found — DB tools broken")
        else {
            assertTrue(java.lang.reflect.Modifier.isStatic(getInstance.modifiers),
                "LocalDataSourceManager.getInstance(Project) must be static")
            println("OK: LocalDataSourceManager.getInstance(Project) found")
        }

        val getDataSources = runCatching { cls.getMethod("getDataSources") }.getOrNull()
        if (getDataSources == null) println("WARNING: LocalDataSourceManager.getDataSources() not found — list_database_sources broken")
        else println("OK: LocalDataSourceManager.getDataSources() → ${getDataSources.returnType.simpleName}")
    }

    @Test
    fun `DasModel getModelRoots and traverser exist`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DasModel check")
            return
        }
        val cls = dbClass("com.intellij.database.model.DasModel", cl) ?: run {
            println("WARNING: DasModel interface not found — get_database_schema schema traversal broken")
            return
        }
        for (name in listOf("getModelRoots", "traverser")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: DasModel.$name() found")
            else println("WARNING: DasModel.$name() not found — schema traversal may fail")
        }
    }

    @Test
    fun `ObjectKind enum fields exist (SCHEMA TABLE VIEW COLUMN KEY FOREIGN_KEY INDEX DATABASE)`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping ObjectKind check")
            return
        }
        val cls = dbClass("com.intellij.database.model.ObjectKind", cl) ?: run {
            println("WARNING: ObjectKind class not found — getDasChildren traversal broken")
            return
        }
        for (fieldName in listOf("SCHEMA", "TABLE", "VIEW", "COLUMN", "KEY", "FOREIGN_KEY", "INDEX", "DATABASE")) {
            val field = runCatching { cls.getField(fieldName) }.getOrNull()
            if (field == null) println("WARNING: ObjectKind.$fieldName not found — children of that kind won't be traversed")
            else println("OK: ObjectKind.$fieldName found")
        }
    }

    @Test
    fun `DasNamed getName and getKind exist`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DasNamed check")
            return
        }
        val cls = dbClass("com.intellij.database.model.DasNamed", cl) ?: run {
            println("WARNING: DasNamed interface not found — getName/getKind reflection will fall back to concrete class")
            return
        }
        for (name in listOf("getName", "getKind")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: DasNamed.$name() found")
            else println("WARNING: DasNamed.$name() not found — invokeViaInterface will fall back to concrete class")
        }
    }

    @Test
    fun `DasObject getDasChildren exists`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DasObject check")
            return
        }
        val cls = dbClass("com.intellij.database.model.DasObject", cl) ?: run {
            println("WARNING: DasObject interface not found — getDasChildren will fail")
            return
        }
        val kindCls = dbClass("com.intellij.database.model.ObjectKind", cl)
        val method = if (kindCls != null) {
            runCatching { cls.getMethod("getDasChildren", kindCls) }.getOrNull()
        } else null
        if (method == null) println("WARNING: DasObject.getDasChildren(ObjectKind) not found — schema child traversal broken")
        else println("OK: DasObject.getDasChildren(${method.parameterTypes[0].simpleName}) → ${method.returnType.simpleName}")
    }

    @Test
    fun `DasTypedObject getDataType isNotNull getDefault exist`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DasTypedObject check")
            return
        }
        val cls = dbClass("com.intellij.database.model.DasTypedObject", cl) ?: run {
            println("WARNING: DasTypedObject interface not found — column type info will be '?'")
            return
        }
        for (name in listOf("getDataType", "isNotNull", "getDefault")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: DasTypedObject.$name() found")
            else println("WARNING: DasTypedObject.$name() not found — column detail for that field will degrade")
        }
    }

    @Test
    fun `DataType fields typeName size scale exist`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DataType check")
            return
        }
        val cls = dbClass("com.intellij.database.model.DataType", cl) ?: run {
            println("WARNING: DataType class not found — column type details will be '?'")
            return
        }
        for (fieldName in listOf("typeName", "size", "scale")) {
            val field = runCatching { cls.getField(fieldName) }.getOrNull()
                ?: runCatching { cls.getDeclaredField(fieldName).also { it.isAccessible = true } }.getOrNull()
            if (field == null) println("WARNING: DataType.$fieldName not found — column type info will degrade")
            else println("OK: DataType.$fieldName: ${field.type.simpleName}")
        }
    }

    @Test
    fun `DasPositioned getPosition exists`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DasPositioned check")
            return
        }
        val cls = dbClass("com.intellij.database.model.DasPositioned", cl) ?: run {
            println("WARNING: DasPositioned interface not found — column ordering will default to 0")
            return
        }
        val found = cls.methods.any { it.name == "getPosition" && it.parameterCount == 0 }
        if (found) println("OK: DasPositioned.getPosition() found")
        else println("WARNING: DasPositioned.getPosition() not found — column position will default to 0")
    }

    @Test
    fun `DasConstraint getColumnsRef exists`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DasConstraint check")
            return
        }
        val cls = dbClass("com.intellij.database.model.DasConstraint", cl) ?: run {
            println("WARNING: DasConstraint interface not found — key/index column names will be empty")
            return
        }
        val found = cls.methods.any { it.name == "getColumnsRef" && it.parameterCount == 0 }
        if (found) println("OK: DasConstraint.getColumnsRef() found")
        else println("WARNING: DasConstraint.getColumnsRef() not found — key column names will be empty")
    }

    @Test
    fun `DasForeignKey getRefTableName getRefTableSchema getRefColumns exist`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DasForeignKey check")
            return
        }
        val cls = dbClass("com.intellij.database.model.DasForeignKey", cl) ?: run {
            println("WARNING: DasForeignKey interface not found — foreign key details will be empty")
            return
        }
        for (name in listOf("getRefTableName", "getRefTableSchema", "getRefColumns")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: DasForeignKey.$name() found")
            else println("WARNING: DasForeignKey.$name() not found — foreign key detail for that field will be '?'")
        }
    }

    @Test
    fun `DasIndex isUnique and getColumnsRef exist`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DasIndex check")
            return
        }
        val cls = dbClass("com.intellij.database.model.DasIndex", cl) ?: run {
            println("WARNING: DasIndex interface not found — index details will be empty")
            return
        }
        for (name in listOf("isUnique", "getColumnsRef")) {
            val found = cls.methods.any { it.name == name && it.parameterCount == 0 }
            if (found) println("OK: DasIndex.$name() found")
            else println("WARNING: DasIndex.$name() not found — index detail for that field will degrade")
        }
    }

    @Test
    fun `MultiRef names exists`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping MultiRef check")
            return
        }
        val cls = dbClass("com.intellij.database.model.MultiRef", cl) ?: run {
            println("WARNING: MultiRef interface not found — column name lists for keys/indexes/FKs will be empty")
            return
        }
        val found = cls.methods.any { it.name == "names" && it.parameterCount == 0 }
        if (found) println("OK: MultiRef.names() found")
        else println("WARNING: MultiRef.names() not found — column names for constraints/indexes will be empty")
    }

    @Test
    fun `DatabaseConnectionManager getInstance and build exist`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DatabaseConnectionManager check")
            return
        }
        val cls = dbClass("com.intellij.database.dataSource.DatabaseConnectionManager", cl) ?: run {
            println("WARNING: DatabaseConnectionManager not found — execute_database_query will not work")
            return
        }
        val getInstance = runCatching { cls.getMethod("getInstance") }.getOrNull()
        if (getInstance == null) println("WARNING: DatabaseConnectionManager.getInstance() not found — will try ApplicationManager fallback")
        else {
            assertTrue(java.lang.reflect.Modifier.isStatic(getInstance.modifiers),
                "DatabaseConnectionManager.getInstance() must be static")
            println("OK: DatabaseConnectionManager.getInstance() found")
        }

        val build = cls.methods.firstOrNull { it.name == "build" && it.parameterCount == 2 }
        if (build == null) println("WARNING: DatabaseConnectionManager.build(project, source) not found — execute_database_query will not work")
        else println("OK: DatabaseConnectionManager.build(${build.parameterTypes.joinToString { it.simpleName }}) found")
    }

    @Test
    fun `DatabaseConnection getRemoteConnection exists and RemoteConnection createStatement exists`() {
        val cl = dbClassLoader() ?: run {
            println("INFO: Database plugin not available — skipping DatabaseConnection check")
            return
        }
        val connCls = dbClass("com.intellij.database.remote.jdbc.RemoteConnection", cl)
            ?: dbClass("com.intellij.database.connection.DatabaseConnection", cl)
        if (connCls == null) {
            println("WARNING: DatabaseConnection/RemoteConnection interface not found — execute_database_query reflection may degrade")
            return
        }
        val found = connCls.methods.any { it.name == "createStatement" && it.parameterCount == 0 }
        if (found) println("OK: ${connCls.simpleName}.createStatement() found")
        else println("WARNING: ${connCls.simpleName}.createStatement() not found — query execution will fail")
    }
}
