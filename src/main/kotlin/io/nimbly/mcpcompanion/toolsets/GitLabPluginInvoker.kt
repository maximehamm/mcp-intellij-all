package io.nimbly.mcpcompanion.toolsets

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.io.HttpRequests

/**
 * Talks to the GitLab REST API v4 for the Merge Request tools.
 *
 * Unlike the GitHub plugin (which exposes a clean `GithubApiRequestExecutor` we can drive by
 * reflection), the bundled GitLab plugin's API surface (`GitLabApi` / `GitLabApi.Rest`) is
 * heavily coroutine- and GraphQL-coupled and not practical to reflect into. So we reach the
 * principle-compliant middle ground:
 *
 *  - **Auth** comes from the GitLab account configured in the IDE (Settings → Version Control →
 *    GitLab), read reflectively from `GitLabAccountManager` — NOT from an env var / system store.
 *  - **Transport** goes through `com.intellij.util.io.HttpRequests`, the IDE's HTTP stack, so it
 *    honours the IDE proxy settings and SSL trust store. No `glab`/`curl` CLI, no raw sockets.
 *
 * This keeps us aligned with the "rely on the IDE, not system resources" project principle while
 * avoiding the brittle reflection the GitLab plugin's typed client would require.
 *
 * Responses are raw JSON text (passed straight through to the caller). Request bodies (POST/PUT)
 * are serialised from a `Map<String, Any?>` by [jsonBody].
 */
internal object GitLabPluginInvoker {

    /** GET. When [paged], auto-follows the `X-Next-Page` header until exhausted, concatenating
     *  the JSON arrays into one. */
    fun get(host: String, fullUrl: String, paged: Boolean): String {
        val token = tokenForHost(host) ?: return noTokenError(host)
        if (!paged) return httpJson(fullUrl, "GET", token, null)

        // GitLab paginates with `?page=N&per_page=…` and exposes the next page via the
        // `X-Next-Page` response header (empty when there is none). We merge the arrays.
        val merged = StringBuilder("[")
        var first = true
        var url: String? = fullUrl
        var safety = 50
        while (url != null && safety-- > 0) {
            val (bodyText, nextPage) = httpJsonWithHeader(url, token, "X-Next-Page")
            if (bodyText.startsWith("error:") || bodyText.startsWith("HTTP ")) return bodyText
            // Strip the surrounding [ ] of this page and append its elements.
            val inner = bodyText.trim().removePrefix("[").removeSuffix("]").trim()
            if (inner.isNotEmpty()) {
                if (!first) merged.append(",")
                merged.append(inner)
                first = false
            }
            url = if (nextPage.isNullOrBlank()) null else appendQueryParam(fullUrl, "page", nextPage)
        }
        merged.append("]")
        return merged.toString()
    }

    fun post(host: String, fullUrl: String, body: Map<String, Any?>): String {
        val token = tokenForHost(host) ?: return noTokenError(host)
        return httpJson(fullUrl, "POST", token, jsonBody(body))
    }

    fun put(host: String, fullUrl: String, body: Map<String, Any?>): String {
        val token = tokenForHost(host) ?: return noTokenError(host)
        return httpJson(fullUrl, "PUT", token, jsonBody(body))
    }

    // ── HTTP via the IDE network stack ─────────────────────────────────────────

    private fun httpJson(url: String, method: String, token: String, body: String?): String = runCatching {
        val builder = when (method) {
            "GET" -> HttpRequests.request(url)
            "POST" -> HttpRequests.post(url, "application/json")
            "PUT" -> HttpRequests.put(url, "application/json")
            else -> HttpRequests.request(url)
        }
        builder.connectTimeout(15_000).readTimeout(30_000).productNameAsUserAgent()
            .throwStatusCodeException(false)
            .tuner { it.setRequestProperty("Authorization", "Bearer $token") }
            .connect { req ->
                if (body != null) req.write(body)
                req.readString()
            }
    }.getOrElse { e -> "error: GitLab API call failed — ${e.javaClass.simpleName}: ${e.message}" }

    /** GET returning (body, namedResponseHeader) — used for pagination. */
    private fun httpJsonWithHeader(url: String, token: String, header: String): Pair<String, String?> = runCatching {
        HttpRequests.request(url)
            .connectTimeout(15_000).readTimeout(30_000).productNameAsUserAgent()
            .throwStatusCodeException(false)
            .tuner { it.setRequestProperty("Authorization", "Bearer $token") }
            .connect { req ->
                val text = req.readString()
                val next = runCatching { req.connection.getHeaderField(header) }.getOrNull()
                text to next
            }
    }.getOrElse { e -> "error: GitLab API call failed — ${e.javaClass.simpleName}: ${e.message}" to null }

    private fun appendQueryParam(url: String, key: String, value: String): String {
        val sep = if ('?' in url) "&" else "?"
        // Replace an existing page= if present, else append.
        return if (Regex("[?&]$key=").containsMatchIn(url))
            url.replace(Regex("([?&]$key=)[^&]*"), "$1$value")
        else "$url$sep$key=$value"
    }

    // ── Token from the GitLab plugin's configured account (reflection) ──────────

    private fun tokenForHost(host: String): String? = runCatching {
        val loader = io.nimbly.mcpcompanion.util.pluginClassLoader("org.jetbrains.plugins.gitlab") ?: return null
        val managerCls = runCatching {
            loader.loadClass("org.jetbrains.plugins.gitlab.authentication.accounts.GitLabAccountManager")
        }.getOrNull() ?: return null
        val manager = ApplicationManager.getApplication().getService(managerCls) ?: return null
        val account = pickAccountForHost(manager, host) ?: return null
        readAccountCredentials(manager, account)
    }.getOrNull()

    private fun noTokenError(host: String): String =
        "Authentication failed for GitLab (host '$host'). Configure an account in " +
        "Settings → Version Control → GitLab → '+' → Log In. (The git clone/pull credential " +
        "helper is a different mechanism and doesn't create an API token.)"

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
                val s = acc?.javaClass?.methods?.firstOrNull { it.name == "getServer" && it.parameterCount == 0 }?.invoke(acc)
                // GitLabServerPath exposes getUri() / toURI(); match on host substring.
                val uri = s?.javaClass?.methods?.firstOrNull { it.name == "getUri" && it.parameterCount == 0 }?.invoke(s) as? String
                uri?.contains(host)
            }.getOrNull() ?: false
            sh
        } ?: accounts.firstOrNull()
    }.getOrNull()

    /** Reads the (suspend) `findCredentials(account)` via the reflective-suspend bridge. */
    private fun readAccountCredentials(manager: Any, account: Any): String? = runCatching {
        val method = manager.javaClass.methods.firstOrNull {
            it.name == "findCredentials" && it.parameterCount == 2 &&
                it.parameterTypes[1] == kotlin.coroutines.Continuation::class.java
        } ?: return@runCatching null
        invokeSuspendReflectively(method, manager, account) as? String
    }.getOrNull()

    private fun invokeSuspendReflectively(method: java.lang.reflect.Method, target: Any, vararg args: Any?): Any? =
        kotlinx.coroutines.runBlocking {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { cont ->
                method.invoke(target, *args, cont)
            }
        }

    // ── Minimal JSON body serialiser (Map/List/primitive tree → JSON string) ────

    fun jsonBody(value: Any?): String {
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
                    writeJsonString(out, k?.toString() ?: "null"); out.append(':'); writeJson(out, v)
                }
                out.append('}')
            }
            is Collection<*> -> {
                out.append('[')
                value.forEachIndexed { i, v -> if (i > 0) out.append(','); writeJson(out, v) }
                out.append(']')
            }
            else -> writeJsonString(out, value.toString())
        }
    }

    private fun writeJsonString(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
        }
        out.append('"')
    }
}
