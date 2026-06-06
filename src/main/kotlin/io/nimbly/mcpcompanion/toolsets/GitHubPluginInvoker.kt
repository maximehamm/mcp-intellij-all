package io.nimbly.mcpcompanion.toolsets

import com.intellij.openapi.application.ApplicationManager

/**
 * Invokes the bundled GitHub plugin's `GithubApiRequestExecutor` entirely via reflection.
 *
 * Why reflective: every class in `org.jetbrains.plugins.github.api.*` is `@ApiStatus.Internal`,
 * so a static reference would trip the Marketplace verifier (same regression we hit with
 * `PluginManagerCore.getPlugin` on 2026-05-20). Going through reflection lets us pick up:
 *
 *  - the user's configured GitHub account (any auth flow: PAT, OAuth, Enterprise);
 *  - the executor's automatic auth handling (token, refresh, headers);
 *  - the IDE's HTTP stack (proxies, SSL trust, IDE-controlled timeouts);
 *  - the plugin's retry/rate-limit awareness.
 *
 * For each REST endpoint we use one of the two concrete `GithubApiRequest.Get` subclasses the
 * plugin ships: `JsonMap` (single object → `Map<String, Object>`) and `JsonPage` (paginated
 * list → `GithubResponsePage<Object>`). The deserialised result is re-serialised to a JSON
 * string by [serializeJson] so the MCP caller sees the same shape as the raw REST endpoint.
 *
 * Any reflective failure (plugin missing, class renamed, no account configured) returns a
 * structured error string rather than throwing — the caller surfaces it verbatim.
 *
 * Covered by [io.nimbly.mcpcompanion.ReflectionApiTest] for the class names we depend on; if
 * JetBrains ever renames anything in this chain, the build fails before any user does.
 */
internal object GitHubPluginInvoker {

    private const val MEDIA = "application/vnd.github+json"

    /**
     * The GitHub plugin's ClassLoader. IntelliJ isolates each plugin behind its own ClassLoader,
     * and OUR plugin does not declare a `<depends>` on the GitHub plugin — so a plain
     * `Class.forName("org.jetbrains.plugins.github.…")` (which resolves against the caller's
     * ClassLoader) cannot see GitHub classes even when the plugin is installed and enabled.
     * We must load every GitHub class through the GitHub plugin's own ClassLoader, exactly like
     * McpCompanionVcsToolset does for Git4Idea. Resolved fresh each call (cheap) so enabling the
     * plugin at runtime doesn't require an IDE restart to take effect.
     */
    private fun ghLoader(): ClassLoader? =
        io.nimbly.mcpcompanion.util.pluginClassLoader("org.jetbrains.plugins.github")

    /** Loads a GitHub-plugin class through the plugin's own ClassLoader (see [ghLoader]). */
    private fun ghClass(loader: ClassLoader, name: String): Class<*> = loader.loadClass(name)

    /** GET request. When [paged] is true, auto-follows `Link: rel="next"` until exhausted. */
    fun get(host: String, fullUrl: String, paged: Boolean): String = withExecutor(host) { loader, executor, execute ->
        executeGet(loader, executor, execute, fullUrl, paged)
    }

    /** POST request with a JSON body (Map will be Jackson-serialised by the plugin). */
    fun post(host: String, fullUrl: String, body: Map<String, Any?>): String = withExecutor(host) { loader, executor, execute ->
        executeBodyRequest(loader, executor, execute, "Post", fullUrl, body)
    }

    /** PATCH request — used by GitHub for partial updates (PR state, title, body…). */
    fun patch(host: String, fullUrl: String, body: Map<String, Any?>): String = withExecutor(host) { loader, executor, execute ->
        executeBodyRequest(loader, executor, execute, "Patch", fullUrl, body)
    }

    /** PUT request — used by GitHub for merges, content uploads, etc. */
    fun put(host: String, fullUrl: String, body: Map<String, Any?>): String = withExecutor(host) { loader, executor, execute ->
        executeBodyRequest(loader, executor, execute, "Put", fullUrl, body)
    }

    /**
     * Common bootstrap: locates GHAccountManager → picks an account for [host] → reads the
     * credential → spins up an executor via the plugin's Factory. Then hands off the executor
     * + the `execute(GithubApiRequest)` reflective method to [block]. Any reflective failure
     * returns a structured error string directly without invoking [block].
     */
    private inline fun withExecutor(host: String, block: (loader: ClassLoader, executor: Any, execute: java.lang.reflect.Method) -> String): String {
        val loader = ghLoader() ?: return "error: GitHub plugin not installed or not enabled"
        val accountManagerCls = runCatching {
            ghClass(loader, "org.jetbrains.plugins.github.authentication.accounts.GHAccountManager")
        }.getOrNull() ?: return "error: GitHub plugin not installed"
        val accountManager = runCatching {
            ApplicationManager.getApplication().getService(accountManagerCls)
        }.getOrNull() ?: return "error: GHAccountManager service unavailable"
        val account = pickAccountForHost(accountManager, host)
            ?: return "error: no GitHub account configured for host '$host' — open Settings → Version Control → GitHub → '+' to add one"
        val token = readAccountCredentials(accountManager, account)
            ?: return "error: account found for '$host' but no credentials stored — try logging out and back in"

        val serverFromAccount = runCatching {
            account.javaClass.methods.firstOrNull { it.name == "getServer" && it.parameterCount == 0 }?.invoke(account)
        }.getOrNull() ?: return "error: account has no server (incompatible plugin version?)"
        val serverPathCls = runCatching {
            ghClass(loader, "org.jetbrains.plugins.github.api.GithubServerPath")
        }.getOrNull() ?: return "error: GithubServerPath class missing"

        val factoryCls = runCatching {
            ghClass(loader, "org.jetbrains.plugins.github.api.GithubApiRequestExecutor\$Factory")
        }.getOrNull() ?: return "error: GithubApiRequestExecutor.Factory class missing"
        val factory = runCatching { factoryCls.getMethod("getInstance").invoke(null) }.getOrNull()
            ?: return "error: GithubApiRequestExecutor.Factory.getInstance() unavailable"
        val executor = runCatching {
            factoryCls.getMethod("create", serverPathCls, String::class.java).invoke(factory, serverFromAccount, token)
        }.getOrNull() ?: return "error: factory.create(server, token) failed reflectively"

        val requestCls = runCatching {
            ghClass(loader, "org.jetbrains.plugins.github.api.GithubApiRequest")
        }.getOrNull() ?: return "error: GithubApiRequest class missing"
        val executorCls = runCatching {
            ghClass(loader, "org.jetbrains.plugins.github.api.GithubApiRequestExecutor")
        }.getOrNull() ?: return "error: GithubApiRequestExecutor class missing"
        val executeMethod = executorCls.methods.firstOrNull {
            it.name == "execute" && it.parameterCount == 1 && it.parameterTypes[0] == requestCls
        } ?: return "error: GithubApiRequestExecutor.execute(request) method missing"

        return block(loader, executor, executeMethod)
    }

    /** Runs a GET request, auto-paginating when [paged] is true. */
    private fun executeGet(loader: ClassLoader, executor: Any, executeMethod: java.lang.reflect.Method, fullUrl: String, paged: Boolean): String {
        val request = runCatching {
            if (paged) {
                val pageCls = ghClass(loader, "org.jetbrains.plugins.github.api.GithubApiRequest\$Get\$JsonPage")
                pageCls.getConstructor(String::class.java, Class::class.java, String::class.java)
                    .newInstance(fullUrl, Any::class.java, MEDIA)
            } else {
                val mapCls = ghClass(loader, "org.jetbrains.plugins.github.api.GithubApiRequest\$Get\$JsonMap")
                mapCls.getConstructor(String::class.java, String::class.java)
                    .newInstance(fullUrl, MEDIA)
            }
        }.getOrElse { return "error: cannot build GithubApiRequest.Get reflectively — ${it.javaClass.simpleName}: ${it.message}" }

        val raw = runCatching { executeMethod.invoke(executor, request) }.getOrElse { e ->
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
            return "error: GitHub API call failed — ${cause.javaClass.simpleName}: ${cause.message}"
        }

        if (!paged) return serializeJson(raw ?: return "error: execute returned null")

        // Auto-follow `Link: rel="next"` until exhausted; safety bound caps the loop.
        val allItems = mutableListOf<Any?>()
        var page = raw
        var safetyLimit = 50
        while (page != null && safetyLimit-- > 0) {
            val items = runCatching {
                page!!.javaClass.methods.firstOrNull { it.name == "getItems" && it.parameterCount == 0 }
                    ?.invoke(page) as? List<*>
            }.getOrNull() ?: break
            allItems.addAll(items)
            val nextLink = runCatching {
                page!!.javaClass.methods.firstOrNull { it.name == "getNextLink" && it.parameterCount == 0 }
                    ?.invoke(page) as? String
            }.getOrNull() ?: break
            val nextRequest = runCatching {
                val pageCls = ghClass(loader, "org.jetbrains.plugins.github.api.GithubApiRequest\$Get\$JsonPage")
                pageCls.getConstructor(String::class.java, Class::class.java, String::class.java)
                    .newInstance(nextLink, Any::class.java, MEDIA)
            }.getOrNull() ?: break
            page = runCatching { executeMethod.invoke(executor, nextRequest) }.getOrNull()
        }
        return serializeJson(allItems)
    }

    /**
     * Runs a body-bearing request (POST / PATCH / PUT) via the plugin's `*.Json` subclasses.
     * Body is a `Map<String, Any?>` which the plugin (Jackson) serialises automatically.
     * Response is deserialised to `Object` (most commonly a Map for object endpoints) then
     * re-serialised by [serializeJson] for the MCP caller.
     */
    private fun executeBodyRequest(
        loader: ClassLoader,
        executor: Any,
        executeMethod: java.lang.reflect.Method,
        kind: String,        // "Post" | "Patch" | "Put"
        fullUrl: String,
        body: Map<String, Any?>,
    ): String {
        val request = runCatching {
            val cls = ghClass(loader, "org.jetbrains.plugins.github.api.GithubApiRequest\$$kind\$Json")
            // Post.Json: (url, body, clazz, mediaType) — Patch/Put.Json inherit + add their own (url, body, clazz).
            // We pick the constructor that matches: prefer 4-arg Post.Json; fall back to 3-arg Patch/Put.Json.
            val ctor4 = cls.constructors.firstOrNull {
                it.parameterCount == 4 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[2] == Class::class.java &&
                    it.parameterTypes[3] == String::class.java
            }
            val ctor3 = cls.constructors.firstOrNull {
                it.parameterCount == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[2] == Class::class.java
            }
            ctor4?.newInstance(fullUrl, body, Any::class.java, MEDIA)
                ?: ctor3?.newInstance(fullUrl, body, Any::class.java)
                ?: error("no matching constructor on $kind.Json")
        }.getOrElse { return "error: cannot build GithubApiRequest.$kind reflectively — ${it.javaClass.simpleName}: ${it.message}" }

        val raw = runCatching { executeMethod.invoke(executor, request) }.getOrElse { e ->
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.cause ?: e
            return "error: GitHub API call failed — ${cause.javaClass.simpleName}: ${cause.message}"
        }
        return serializeJson(raw)
    }


    // ── Account helpers — delegate to the shared reflective helpers in the PR toolset file
    //    (same package), so account discovery + suspend-credential reading live in one place.

    private fun pickAccountForHost(manager: Any, host: String): Any? = runCatching {
        val getter = manager.javaClass.methods.firstOrNull {
            it.name in setOf("getAccounts", "getAccountsState") && it.parameterCount == 0
        } ?: return@runCatching null
        val raw = getter.invoke(manager)
        val accounts: Collection<*> = when (raw) {
            is Collection<*> -> raw
            is kotlinx.coroutines.flow.StateFlow<*> -> (raw.value as? Collection<*>).orEmpty()
            else -> return@runCatching null
        }
        accounts.firstOrNull { acc ->
            val sh = runCatching {
                val s = acc?.javaClass?.methods
                    ?.firstOrNull { it.name == "getServer" && it.parameterCount == 0 }?.invoke(acc)
                s?.javaClass?.methods
                    ?.firstOrNull { it.name == "getHost" && it.parameterCount == 0 }?.invoke(s) as? String
            }.getOrNull()
            sh == host
        } ?: accounts.firstOrNull()
    }.getOrNull()

    /** Reads the (suspend) `findCredentials(account)` via the reflective-suspend bridge below. */
    private fun readAccountCredentials(manager: Any, account: Any): String? = runCatching {
        val method = manager.javaClass.methods.firstOrNull {
            it.name == "findCredentials" && it.parameterCount == 2 &&
                it.parameterTypes[1] == kotlin.coroutines.Continuation::class.java
        } ?: return@runCatching null
        invokeSuspendReflectively(method, manager, account) as? String
    }.getOrNull()

    /**
     * Invokes a Kotlin `suspend` function reflectively and blocks for its result.
     *
     * A suspend fun `f(a): R` compiles to `f(a, Continuation): Object` returning either the
     * result or the COROUTINE_SUSPENDED marker. We drive it from `runBlocking` via
     * `suspendCoroutineUninterceptedOrReturn`, returning whatever `Method.invoke` produced —
     * the coroutine machinery then uses the immediate value or waits for the continuation.
     * Used for `AccountManagerBase.findCredentials`.
     */
    private fun invokeSuspendReflectively(method: java.lang.reflect.Method, target: Any, vararg args: Any?): Any? =
        kotlinx.coroutines.runBlocking {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { cont ->
                method.invoke(target, *args, cont)
            }
        }

    /**
     * Walks an arbitrary Map/List/primitive tree (what Jackson produced) and emits a JSON
     * string. We avoid pulling in Jackson at compile-time and instead format the tree
     * ourselves — the structure is well-known (deserialised from JSON in the first place),
     * so cycle/special-case handling isn't necessary.
     */
    private fun serializeJson(value: Any?): String {
        val sb = StringBuilder()
        writeJson(sb, value)
        return sb.toString()
    }

    private fun writeJson(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean, is Number -> out.append(value.toString())
            is String -> writeJsonString(out, value)
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    writeJsonString(out, k?.toString() ?: "null")
                    out.append(':')
                    writeJson(out, v)
                }
                out.append('}')
            }
            is Collection<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    writeJson(out, v)
                }
                out.append(']')
            }
            is Array<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(',')
                    writeJson(out, v)
                }
                out.append(']')
            }
            else -> writeJsonString(out, value.toString())
        }
    }

    private fun writeJsonString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"'  -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }
}
