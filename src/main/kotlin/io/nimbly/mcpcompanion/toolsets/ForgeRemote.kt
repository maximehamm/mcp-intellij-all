package io.nimbly.mcpcompanion.toolsets

import com.intellij.openapi.project.Project
import java.net.URI

/**
 * Shared git-forge remote detection used by both the GitHub (Pull Requests) and GitLab
 * (Merge Requests) toolsets.
 *
 * Everything is read through the Git4Idea plugin (reached via its own ClassLoader) — never via
 * a `git` CLI shell-out — consistent with the project principle that the AI consumer has no
 * terminal access.
 */

/** A parsed `origin` remote: the host (e.g. "github.com") and the "owner/name" path. */
internal data class ForgeRemote(val host: String, val repo: String)

/**
 * Returns the URL of the `origin` remote (or the first remote if no `origin`) via Git4Idea.
 * Returns null when there's no Git repo or no remote.
 */
internal fun originRemoteUrl(project: Project): String? = runCatching {
    val cl = io.nimbly.mcpcompanion.util.pluginClassLoader("Git4Idea") ?: return null
    val mgrCls = cl.loadClass("git4idea.repo.GitRepositoryManager")
    val mgr = mgrCls.getMethod("getInstance", Project::class.java).invoke(null, project)
    @Suppress("UNCHECKED_CAST")
    val repos = mgrCls.getMethod("getRepositories").invoke(mgr) as? List<Any> ?: return null
    val repo = repos.firstOrNull() ?: return null
    @Suppress("UNCHECKED_CAST")
    val remotes = repo.javaClass.methods.firstOrNull { it.name == "getRemotes" && it.parameterCount == 0 }
        ?.invoke(repo) as? Collection<Any> ?: return null
    fun remoteName(r: Any) = r.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }?.invoke(r) as? String
    fun remoteUrl(r: Any) = r.javaClass.methods.firstOrNull { it.name == "getFirstUrl" && it.parameterCount == 0 }?.invoke(r) as? String
    val chosen = remotes.firstOrNull { remoteName(it) == "origin" } ?: remotes.firstOrNull()
    chosen?.let { remoteUrl(it) }
}.getOrNull()

/**
 * Parses the `origin` remote URL into (host, "owner/name"), handling SSH and HTTPS forms.
 * Returns null when there's no remote or the URL can't be parsed.
 */
internal fun parseOriginRemote(project: Project): ForgeRemote? {
    val url = originRemoteUrl(project)?.takeIf { it.isNotBlank() } ?: return null
    val (host, path) = when {
        url.startsWith("git@") -> {
            val rest = url.removePrefix("git@")
            val colon = rest.indexOf(':')
            if (colon == -1) return null
            rest.substring(0, colon) to rest.substring(colon + 1)
        }
        url.startsWith("https://") || url.startsWith("http://") -> {
            val u = runCatching { URI(url) }.getOrNull() ?: return null
            (u.host ?: return null) to u.path.removePrefix("/")
        }
        else -> return null
    }
    val repo = path.removeSuffix(".git").removeSuffix("/")
    if (repo.isBlank() || host.isBlank()) return null
    return ForgeRemote(host, repo)
}
