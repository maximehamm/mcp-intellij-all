package io.nimbly.mcpcompanion

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.clientInfo
import com.intellij.mcpserver.project
import com.intellij.openapi.extensions.PluginId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.coroutineContext

class McpCompanionDatabaseToolset : McpToolset {

    override fun isEnabled(): Boolean = true

    private suspend fun disabledMessage(toolName: String): String? {
        if (!McpCompanionSettings.getInstance().isEnabled(toolName)) {
            val extra = if (toolName in McpCompanionSettings.DISABLED_BY_DEFAULT)
                " This tool is disabled by default for safety reasons. Ask the user to enable it first."
            else ""
            return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion.$extra"
        }
        McpCompanionSettings.getInstance().trackCall(toolName, runCatching { coroutineContext.clientInfo?.name?.takeIf { it != "Unknown MCP client" } }.getOrNull())
        McpCompanionSettings.getInstance().beginActiveCall(toolName, coroutineContext[kotlinx.coroutines.Job])
        return null
    }

    // ── list_database_sources ─────────────────────────────────────────────────

    @McpTool(name = "list_database_sources")
    @McpDescription(description = """
        Lists all data sources configured in the IntelliJ Database tool window.
        Returns name, URL, and driver for each data source.
        Requires the Database Tools plugin (IntelliJ IDEA Ultimate).
        Call this first to discover the data source name to pass to execute_database_query.

        projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun list_database_sources(projectPath: String? = null): String {
        disabledMessage("list_database_sources")?.let { return it }
        val project = resolveProject(projectPath)
        return withContext(Dispatchers.IO) {
            try {
                val sources = getDataSources(project)
                if (sources.isEmpty())
                    return@withContext "No data sources configured. Add data sources in the Database tool window (View → Tool Windows → Database)."
                val items = sources.map { source -> describeSource(source) }
                Json.encodeToString(items)
            } catch (e: DbPluginUnavailableException) {
                DB_PLUGIN_UNAVAILABLE_MSG
            } catch (e: Exception) {
                "Error listing data sources: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── get_database_schema ───────────────────────────────────────────────────

    @McpTool(name = "get_database_schema")
    @McpDescription(description = """
        Returns the schema tree for a configured data source: namespaces (schemas/catalogs),
        tables, views, and optionally columns — as already introspected by IntelliJ.
        Requires the Database Tools plugin (IntelliJ IDEA Ultimate).
        ⚠ Returns an empty tree if IntelliJ hasn't connected and introspected the database yet.
           In that case, open the Database tool window, connect to the source, and refresh the schema first.

        Parameters:
        - dataSource: data source name from list_database_sources (auto-selected if only one exists)
        - includeColumns: include column details for each table (default: false)
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.
    """)
    suspend fun get_database_schema(dataSource: String = "", includeColumns: Boolean = false, projectPath: String? = null): String {
        disabledMessage("get_database_schema")?.let { return it }
        val project = resolveProject(projectPath)
        return withContext(Dispatchers.IO) {
            try {
                val sources = getDataSources(project)
                if (sources.isEmpty()) return@withContext "No data sources configured."

                val source = resolveSource(sources, dataSource)
                    ?: return@withContext buildSourceNotFoundMessage(sources, dataSource)

                val sourceName = runCatching { source.javaClass.getMethod("getName").invoke(source)?.toString() }.getOrNull() ?: "?"
                val model = source.javaClass.getMethod("getModel").invoke(source)
                    ?: return@withContext "[$sourceName] No model available — connect to the database first."

                // getModelRoots() → JBIterable<DasObject>
                val roots = iterableToList(model.javaClass.getMethod("getModelRoots").invoke(model))
                if (roots.isEmpty())
                    return@withContext "[$sourceName] Schema not loaded yet — open the Database tool window, connect, and refresh the schema."

                val tree = roots.map { root -> buildSchemaNode(root, includeColumns) }
                Json.encodeToString(DbSchemaTree(dataSource = sourceName, nodes = tree))

            } catch (e: DbPluginUnavailableException) {
                DB_PLUGIN_UNAVAILABLE_MSG
            } catch (e: java.lang.reflect.InvocationTargetException) {
                "Database error: ${e.cause?.message ?: e.message}"
            } catch (e: Exception) {
                "Error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── execute_database_query ────────────────────────────────────────────────

    @McpTool(name = "execute_database_query")
    @McpDescription(description = """
        Executes a SQL query on a configured data source and returns the results.
        Requires the Database Tools plugin (IntelliJ IDEA Ultimate).
        ⚠ This tool is disabled by default — it can execute any SQL including UPDATE, DELETE, DROP.

        Parameters:
        - query: the SQL statement to execute
        - dataSource: data source name from list_database_sources. If omitted and only one source is
          configured, it is used automatically.
        - maxRows: maximum number of result rows to return (default: 100)
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project if omitted. Useful when several IntelliJ windows are open in the same JVM.

        Returns JSON with columns and rows for SELECT queries, or the number of affected rows for DML.
    """)
    suspend fun execute_database_query(query: String, dataSource: String = "", maxRows: Int = 100, projectPath: String? = null): String {
        disabledMessage("execute_database_query")?.let { return it }
        val project = resolveProject(projectPath)
        return withContext(Dispatchers.IO) {
            try {
                // 1. Resolve the requested data source
                val sources = getDataSources(project)
                if (sources.isEmpty()) return@withContext "No data sources configured."

                val source = resolveSource(sources, dataSource)
                    ?: return@withContext buildSourceNotFoundMessage(sources, dataSource)

                // 2. Build a blocking connection via DatabaseConnectionManager
                val connMgr = getConnectionManager()
                    ?: return@withContext "DatabaseConnectionManager not available."

                val buildMethod = connMgr.javaClass.methods
                    .firstOrNull { m -> m.name == "build" && m.parameterCount == 2 }
                    ?: return@withContext "DatabaseConnectionManager.build(project, source) not found"

                val builder = buildMethod.invoke(connMgr, project, source)
                    ?: return@withContext "Connection builder is null"

                val createBlockingMethod = builder.javaClass.methods
                    .firstOrNull { m -> m.name == "createBlocking" && m.parameterCount == 0 }
                    ?: return@withContext "Builder.createBlocking() not found"

                val guardedRef = createBlockingMethod.invoke(builder)
                    ?: return@withContext "Failed to create connection (guardedRef is null)"

                // 3. Retrieve the DatabaseConnection from the GuardedRef
                val connection = guardedRef.javaClass.getMethod("get").invoke(guardedRef)
                    ?: return@withContext "Failed to obtain database connection (get() returned null)"

                try {
                    executeOnConnection(connection, query, maxRows)
                } finally {
                    releaseConnection(guardedRef)
                }
            } catch (e: DbPluginUnavailableException) {
                DB_PLUGIN_UNAVAILABLE_MSG
            } catch (e: java.lang.reflect.InvocationTargetException) {
                "Database error: ${e.cause?.message ?: e.message}"
            } catch (e: Exception) {
                "Error: ${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }

    // ── Schema traversal helpers ──────────────────────────────────────────────

    /** Converts a JBIterable / Iterable to a plain List<Any>. */
    private fun iterableToList(iterable: Any?): List<Any> {
        if (iterable == null) return emptyList()
        val toList = iterable.javaClass.methods.firstOrNull { it.name == "toList" && it.parameterCount == 0 }
        if (toList != null) {
            @Suppress("UNCHECKED_CAST")
            return toList.invoke(iterable) as? List<Any> ?: emptyList()
        }
        // fallback: JBIterable/Iterable forEach
        val list = mutableListOf<Any>()
        runCatching {
            (iterable as? Iterable<*>)?.forEach { it?.let { v -> list += v } }
        }
        return list
    }

    /** Returns the children of a DasObject with the given ObjectKind name. */
    private fun getChildren(obj: Any, kindName: String): List<Any> = runCatching {
        val kindClass = dbClass("com.intellij.database.model.ObjectKind")
        val kind = kindClass.getField(kindName).get(null)
        iterableToList(invokeViaInterface(obj, "getDasChildren", kind))
    }.getOrDefault(emptyList())

    /**
     * Invokes a method on obj by looking it up through DB plugin interfaces first,
     * then falling back to the concrete class with setAccessible(true).
     * This is required because H2ImplModel inner classes are in restricted modules.
     */
    private fun invokeViaInterface(obj: Any, methodName: String, vararg args: Any?): Any? {
        // Try accessible interface methods first (DasNamed, DasObject, DasTypedObject, etc.)
        val ifaceNames = listOf(
            "com.intellij.database.model.DasNamed",
            "com.intellij.database.model.DasObject",
            "com.intellij.database.model.DasTypedObject",
            "com.intellij.database.model.DasPositioned",
            "com.intellij.database.model.DasTable",
            "com.intellij.database.model.DasTableChild",
            "com.intellij.database.model.DasConstraint",
            "com.intellij.database.model.DasForeignKey",
            "com.intellij.database.model.DasIndex",
            "com.intellij.database.model.MultiRef",
            "com.intellij.database.model.DasDataSource"
        )
        for (ifaceName in ifaceNames) {
            val iface = runCatching { dbClass(ifaceName) }.getOrNull() ?: continue
            if (!iface.isInstance(obj)) continue
            val method = runCatching {
                if (args.isEmpty()) iface.getMethod(methodName)
                else iface.getMethod(methodName, *args.map { it?.javaClass ?: Any::class.java }.toTypedArray())
            }.getOrNull() ?: continue
            return runCatching { method.invoke(obj, *args) }.getOrNull()
        }
        // Fallback: use concrete class with setAccessible
        val method = generateSequence(obj.javaClass as Class<*>?) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { it.name == methodName && it.parameterCount == args.size }
            ?: return null
        method.isAccessible = true
        return runCatching { method.invoke(obj, *args) }.getOrNull()
    }

    private fun getName(obj: Any): String =
        invokeViaInterface(obj, "getName")?.toString() ?: "?"

    private fun getKindName(obj: Any): String =
        invokeViaInterface(obj, "getKind")?.toString() ?: "?"

    /** Resolves column names from a MultiRef via its names() method. */
    private fun multiRefNames(obj: Any, methodName: String): List<String> = runCatching {
        val ref = invokeViaInterface(obj, methodName) ?: return@runCatching emptyList<String>()
        val names = invokeViaInterface(ref, "names") ?: return@runCatching emptyList<String>()
        (names as? Iterable<*>)?.mapNotNull { it?.toString() } ?: emptyList()
    }.getOrDefault(emptyList())

    /** Recursively builds a schema node (catalog / schema level). */
    private fun buildSchemaNode(obj: Any, includeColumns: Boolean): DbSchemaNode {
        val tables = (getChildren(obj, "TABLE") + getChildren(obj, "VIEW"))
            .map { buildTableInfo(it, includeColumns) }
        val childNodes = (getChildren(obj, "DATABASE") + getChildren(obj, "SCHEMA"))
            .map { buildSchemaNode(it, includeColumns) }
        return DbSchemaNode(
            name     = getName(obj),
            kind     = getKindName(obj),
            schemas  = childNodes.takeIf { it.isNotEmpty() },
            tables   = tables.takeIf { it.isNotEmpty() }
        )
    }

    private fun buildTableInfo(table: Any, includeColumns: Boolean): DbTableInfo {
        val columns = if (includeColumns) {
            getChildren(table, "COLUMN").mapNotNull { col ->
                runCatching {
                    val dt   = invokeViaInterface(col, "getDataType")
                    val type = if (dt != null) {
                        val typeName = runCatching { dt.javaClass.getField("typeName").get(dt)?.toString() }.getOrDefault("?")
                        val size     = runCatching { dt.javaClass.getField("size").get(dt) as? Int }.getOrNull() ?: 0
                        val scale    = runCatching { dt.javaClass.getField("scale").get(dt) as? Int }.getOrNull() ?: 0
                        if (size > 0 && size < 99999) "$typeName($size${if (scale > 0) ",$scale" else ""})" else typeName ?: "?"
                    } else "?"
                    val nullable = !(invokeViaInterface(col, "isNotNull") as? Boolean ?: false)
                    val default  = runCatching { invokeViaInterface(col, "getDefault")?.toString()?.takeIf { it.isNotBlank() } }.getOrNull()
                    val pos      = runCatching { (invokeViaInterface(col, "getPosition") as? Short)?.toInt() }.getOrDefault(0) ?: 0
                    DbColumnInfo(name = getName(col), type = type, nullable = nullable, default = default, position = pos)
                }.getOrNull()
            }.sortedBy { it.position }
        } else null

        val keys = getChildren(table, "KEY").map { key ->
            DbKeyInfo(name = getName(key), columns = multiRefNames(key, "getColumnsRef"))
        }
        val fks = getChildren(table, "FOREIGN_KEY").map { fk ->
            DbForeignKeyInfo(
                name       = getName(fk),
                columns    = multiRefNames(fk, "getColumnsRef"),
                refTable   = invokeViaInterface(fk, "getRefTableName")?.toString() ?: "?",
                refSchema  = invokeViaInterface(fk, "getRefTableSchema")?.toString()?.takeIf { it.isNotBlank() },
                refColumns = multiRefNames(fk, "getRefColumns")
            )
        }
        val indexes = getChildren(table, "INDEX").map { idx ->
            DbIndexInfo(
                name    = getName(idx),
                unique  = invokeViaInterface(idx, "isUnique") as? Boolean ?: false,
                columns = multiRefNames(idx, "getColumnsRef")
            )
        }
        return DbTableInfo(
            name        = getName(table),
            kind        = getKindName(table),
            columns     = columns,
            keys        = keys.takeIf { it.isNotEmpty() },
            foreignKeys = fks.takeIf { it.isNotEmpty() },
            indexes     = indexes.takeIf { it.isNotEmpty() }
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns the classloader of the Database Tools plugin, or throws DbPluginUnavailableException. */
    private fun dbClassLoader(): ClassLoader =
        PluginManagerCore.getPlugin(PluginId.getId("com.intellij.database"))?.pluginClassLoader
            ?: throw DbPluginUnavailableException()

    /** Loads a class via the Database plugin's own classloader. */
    private fun dbClass(name: String): Class<*> = dbClassLoader().loadClass(name)

    private fun getDataSources(project: com.intellij.openapi.project.Project): List<Any> {
        val managerClass = dbClass("com.intellij.database.dataSource.LocalDataSourceManager")
        val manager = managerClass.getMethod("getInstance", com.intellij.openapi.project.Project::class.java)
            .invoke(null, project)
        @Suppress("UNCHECKED_CAST")
        return managerClass.getMethod("getDataSources").invoke(manager) as? List<Any> ?: emptyList()
    }

    private fun describeSource(source: Any): DataSourceInfo {
        val name         = runCatching { source.javaClass.getMethod("getName").invoke(source)?.toString() }.getOrNull() ?: "unknown"
        val url          = runCatching { source.javaClass.getMethod("getUrl").invoke(source)?.toString() }.getOrNull()
        val driver       = runCatching {
            val d = source.javaClass.getMethod("getDatabaseDriver").invoke(source)
            d?.let { it.javaClass.getMethod("getName").invoke(it)?.toString() }
        }.getOrNull()
        val username     = runCatching { source.javaClass.getMethod("getUsername").invoke(source)?.toString()?.takeIf { it.isNotBlank() } }.getOrNull()
        val dbms         = runCatching { source.javaClass.getMethod("getDbms").invoke(source)?.toString()?.takeIf { it != "UNKNOWN" } }.getOrNull()
        val schemaFilter = runCatching { source.javaClass.getMethod("getSchemaControl").invoke(source)?.toString() }.getOrNull()
        val readOnly     = runCatching { source.javaClass.getMethod("isReadOnly").invoke(source) as? Boolean }.getOrNull()
        val autoCommit   = runCatching { source.javaClass.getMethod("isAutoCommit").invoke(source) as? Boolean }.getOrNull()
        return DataSourceInfo(
            name = name, url = url, driver = driver, username = username, dbms = dbms,
            schemaFilter = schemaFilter, readOnly = readOnly, autoCommit = autoCommit
        )
    }

    private fun resolveSource(sources: List<Any>, requested: String): Any? {
        if (requested.isBlank()) return if (sources.size == 1) sources[0] else null
        return sources.firstOrNull { src ->
            runCatching { src.javaClass.getMethod("getName").invoke(src)?.toString() == requested }
                .getOrDefault(false)
        }
    }

    private fun buildSourceNotFoundMessage(sources: List<Any>, requested: String): String {
        val names = sources.mapNotNull { runCatching { it.javaClass.getMethod("getName").invoke(it)?.toString() }.getOrNull() }
        return if (requested.isBlank())
            "Multiple data sources found — specify one with the 'dataSource' parameter: ${names.joinToString(", ")}"
        else
            "Data source '$requested' not found. Available: ${names.joinToString(", ")}"
    }

    private fun getConnectionManager(): Any? {
        val cls = runCatching { dbClass("com.intellij.database.dataSource.DatabaseConnectionManager") }.getOrNull()
            ?: return null
        return runCatching { cls.getMethod("getInstance").invoke(null) }.getOrNull()
            ?: runCatching { com.intellij.openapi.application.ApplicationManager.getApplication().getService(cls) }.getOrNull()
    }

    private fun executeOnConnection(connection: Any, query: String, maxRows: Int): String {
        val remoteConn = connection.javaClass.getMethod("getRemoteConnection").invoke(connection)
            ?: return "Failed to get remote connection"

        val stmt = remoteConn.javaClass.getMethod("createStatement").invoke(remoteConn)
            ?: return "Failed to create SQL statement"

        val trimmed = query.trim().uppercase()
        val isQuery = trimmed.startsWith("SELECT") || trimmed.startsWith("WITH") ||
                      trimmed.startsWith("SHOW")   || trimmed.startsWith("EXPLAIN") ||
                      trimmed.startsWith("DESCRIBE")

        return if (isQuery) {
            val rs = stmt.javaClass.getMethod("executeQuery", String::class.java).invoke(stmt, query)
                ?: return "Query returned no result set"

            val meta  = rs.javaClass.getMethod("getMetaData").invoke(rs)
            val count = (meta.javaClass.getMethod("getColumnCount").invoke(meta) as? Int) ?: 0
            val cols  = (1..count).map { i ->
                meta.javaClass.getMethod("getColumnLabel", Int::class.java).invoke(meta, i)?.toString() ?: "col$i"
            }

            val rows   = mutableListOf<List<String?>>()
            val next   = rs.javaClass.getMethod("next")
            val getObj = rs.javaClass.getMethod("getObject", Int::class.java)
            while (rows.size < maxRows && next.invoke(rs) as? Boolean == true) {
                rows.add((1..count).map { i -> runCatching { getObj.invoke(rs, i)?.toString() }.getOrNull() })
            }

            Json.encodeToString(QueryResult(columns = cols, rows = rows, rowCount = rows.size))
        } else {
            stmt.javaClass.getMethod("execute", String::class.java).invoke(stmt, query)
            val affected = runCatching {
                (stmt.javaClass.getMethod("getUpdateCount").invoke(stmt) as? Int) ?: -1
            }.getOrDefault(-1)
            if (affected >= 0) "Executed successfully. Rows affected: $affected" else "Executed successfully."
        }
    }

    private fun releaseConnection(guardedRef: Any) {
        runCatching { guardedRef.javaClass.getMethod("close").invoke(guardedRef) }
        runCatching { guardedRef.javaClass.getMethod("release").invoke(guardedRef) }
    }

    private class DbPluginUnavailableException : Exception()

    companion object {
        private const val DB_PLUGIN_UNAVAILABLE_MSG =
            "Database plugin not available. This tool requires IntelliJ IDEA Ultimate with the Database Tools and SQL plugin."
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────

@Serializable data class DataSourceInfo(
    val name: String,
    val url: String?,
    val driver: String?,
    val username: String? = null,
    val dbms: String? = null,
    val schemaFilter: String? = null,
    val readOnly: Boolean? = null,
    val autoCommit: Boolean? = null
)

@Serializable data class QueryResult(val columns: List<String>, val rows: List<List<String?>>, val rowCount: Int)

@Serializable data class DbSchemaTree(val dataSource: String, val nodes: List<DbSchemaNode>)
@Serializable data class DbSchemaNode(
    val name: String,
    val kind: String,
    val schemas: List<DbSchemaNode>? = null,
    val tables: List<DbTableInfo>? = null
)
@Serializable data class DbTableInfo(
    val name: String,
    val kind: String,
    val columns: List<DbColumnInfo>? = null,
    val keys: List<DbKeyInfo>? = null,
    val foreignKeys: List<DbForeignKeyInfo>? = null,
    val indexes: List<DbIndexInfo>? = null
)
@Serializable data class DbColumnInfo(val name: String, val type: String, val nullable: Boolean, val default: String? = null, val position: Int)
@Serializable data class DbKeyInfo(val name: String, val columns: List<String>)
@Serializable data class DbForeignKeyInfo(val name: String, val columns: List<String>, val refTable: String, val refSchema: String? = null, val refColumns: List<String>)
@Serializable data class DbIndexInfo(val name: String, val unique: Boolean, val columns: List<String>)
