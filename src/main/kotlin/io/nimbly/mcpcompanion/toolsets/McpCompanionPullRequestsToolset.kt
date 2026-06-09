package io.nimbly.mcpcompanion.toolsets

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.clientInfo
import com.intellij.openapi.project.Project
import io.nimbly.mcpcompanion.McpCompanionSettings
import io.nimbly.mcpcompanion.util.resolveProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

/**
 * Tools for inspecting and acting on **GitHub Pull Requests** from inside the IDE.
 *
 * Everything goes through the bundled JetBrains GitHub plugin (`org.jetbrains.plugins.github`),
 * reached via reflection (see [GitHubPluginInvoker]): the plugin's `GithubApiRequestExecutor`
 * provides token-aware auth, IDE proxy support, retries and rate-limit handling, and the typed
 * request templates do JSON (de)serialisation. No `gh` CLI, no shelling out — consistent with
 * the project principle that an AI consumer of this plugin has no terminal access.
 *
 * The repository is auto-detected from the `origin` remote URL (read via the Git4Idea plugin).
 * Authentication uses the GitHub account configured in Settings → Version Control → GitHub.
 *
 * GitLab support is intentionally out of scope for this version.
 */
class McpCompanionPullRequestsToolset : McpToolset {

    override fun isEnabled(): Boolean = true

    private suspend fun disabledMessage(toolName: String): String? {
        if (!McpCompanionSettings.getInstance().isEnabled(toolName)) {
            val extra = if (toolName in McpCompanionSettings.DISABLED_BY_DEFAULT)
                " This tool is disabled by default for safety reasons. Ask the user to enable it first."
            else ""
            return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion.$extra"
        }
        McpCompanionSettings.getInstance().trackCall(
            toolName,
            runCatching { coroutineContext.clientInfo?.name?.takeIf { it != "Unknown MCP client" } }.getOrNull(),
        )
        return null
    }

    // ── list_pull_requests ────────────────────────────────────────────────────

    @McpTool(name = "list_pull_requests")
    @McpDescription(description = """
        Lists pull requests of the current project's GitHub repository (auto-detected from the
        `origin` remote).

        Parameters:
        - state: "open" (default), "closed", "merged", or "all".
        - branch: optional source branch filter (e.g. "feature/foo"). Omit to list every PR.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.

        Authentication uses the GitHub account configured in Settings → Version Control → GitHub.
    """)
    suspend fun list_pull_requests(
        state: String = "open",
        branch: String? = null,
        projectPath: String? = null,
    ): String {
        disabledMessage("list_pull_requests")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val s = when (state.lowercase()) {
                "merged" -> "closed" // GitHub: merged is a sub-state of closed; the AI filters client-side.
                else -> state.lowercase()
            }
            val b = branch?.let { "&head=${URLEncoder.encode(it, Charsets.UTF_8)}" }.orEmpty()
            githubGet(ctx, "/repos/${ctx.repo}/pulls?state=$s$b&per_page=50", paged = true)
        })
    }

    // ── get_pull_request ──────────────────────────────────────────────────────

    @McpTool(name = "get_pull_request")
    @McpDescription(description = """
        Returns the full metadata of a single GitHub pull request: title, description, author,
        source/target branches, state, mergeability, reviewers, CI checks status, timestamps.

        Parameters:
        - number: the PR number (the integer shown in the GitHub UI).
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun get_pull_request(number: Int, projectPath: String? = null): String {
        disabledMessage("get_pull_request")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            githubGet(ctx, "/repos/${ctx.repo}/pulls/$number", paged = false)
        })
    }

    // ── get_pull_request_comments ─────────────────────────────────────────────

    @McpTool(name = "get_pull_request_comments")
    @McpDescription(description = """
        Returns review and discussion comments on a GitHub pull request. The result combines both
        "issue comments" (general PR discussion) and "review comments" (anchored to specific diff
        lines), under two labelled sections so the AI can tell them apart.

        Parameters:
        - number: the PR number.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun get_pull_request_comments(number: Int, projectPath: String? = null): String {
        disabledMessage("get_pull_request_comments")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val issue = githubGet(ctx, "/repos/${ctx.repo}/issues/$number/comments?per_page=100", paged = true)
            val review = githubGet(ctx, "/repos/${ctx.repo}/pulls/$number/comments?per_page=100", paged = true)
            "── issue_comments ──\n$issue\n\n── review_comments ──\n$review"
        })
    }

    // ── get_pull_request_files ───────────────────────────────────────────────

    @McpTool(name = "get_pull_request_files")
    @McpDescription(description = """
        Returns the list of files changed in a GitHub pull request, each with: filename, status
        (added/modified/removed/renamed), additions, deletions, and a `patch` field containing
        the per-file unified diff snippet. Auto-paginated.

        Parameters:
        - number: the PR number.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun get_pull_request_files(number: Int, projectPath: String? = null): String {
        disabledMessage("get_pull_request_files")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            githubGet(ctx, "/repos/${ctx.repo}/pulls/$number/files?per_page=100", paged = true)
        })
    }

    // ── get_pull_request_commits ──────────────────────────────────────────────

    @McpTool(name = "get_pull_request_commits")
    @McpDescription(description = """
        Returns the list of commits on a GitHub pull request. Each entry contains the commit SHA,
        author, message, and file change stats. Auto-paginated.

        Parameters:
        - number: the PR number.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun get_pull_request_commits(number: Int, projectPath: String? = null): String {
        disabledMessage("get_pull_request_commits")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            githubGet(ctx, "/repos/${ctx.repo}/pulls/$number/commits?per_page=100", paged = true)
        })
    }

    // ── get_pull_request_reviews ──────────────────────────────────────────────

    @McpTool(name = "get_pull_request_reviews")
    @McpDescription(description = """
        Returns the list of reviews on a GitHub pull request. Each entry contains the reviewer,
        state (APPROVED/CHANGES_REQUESTED/COMMENTED/DISMISSED/PENDING), and the review body text.
        Use get_pull_request_comments for per-line review comments.

        Parameters:
        - number: the PR number.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun get_pull_request_reviews(number: Int, projectPath: String? = null): String {
        disabledMessage("get_pull_request_reviews")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            githubGet(ctx, "/repos/${ctx.repo}/pulls/$number/reviews?per_page=100", paged = true)
        })
    }

    // ── search_issues_or_prs ──────────────────────────────────────────────────

    @McpTool(name = "search_issues_or_prs")
    @McpDescription(description = """
        Searches GitHub issues and pull requests using the same query syntax as the github.com
        search bar. Examples:
          - "is:pr is:open author:@me"
          - "repo:owner/name label:bug state:closed"
          - "type:issue assignee:alice updated:>2026-01-01"
        Scope: the repository's owning host (github.com or GH Enterprise) — not limited to the
        current repo unless the query includes `repo:owner/name`.

        Parameters:
        - q: the GitHub search query string.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun search_issues_or_prs(q: String, projectPath: String? = null): String {
        disabledMessage("search_issues_or_prs")?.let { return it }
        if (q.isBlank()) return captureResponse("error: q is empty")
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val encoded = URLEncoder.encode(q, Charsets.UTF_8)
            // /search/issues returns {"total_count":..., "items":[…]} — serialise the envelope.
            githubGet(ctx, "/search/issues?q=$encoded&per_page=50", paged = false)
        })
    }

    // ── add_pull_request_comment (disabled by default) ─────────────────────────

    @McpTool(name = "add_pull_request_comment")
    @McpDescription(description = """
        Posts a top-level comment on a GitHub pull request (the PR's "Conversation" tab — not
        anchored to a diff line). Disabled by default (creates public content).

        Parameters:
        - number: the PR number.
        - body: the comment text (Markdown supported).
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun add_pull_request_comment(number: Int, body: String, projectPath: String? = null): String {
        disabledMessage("add_pull_request_comment")?.let { return it }
        if (body.isBlank()) return captureResponse("error: body is empty")
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            GitHubPluginInvoker.post(ctx.host, "${ctx.apiBase}/repos/${ctx.repo}/issues/$number/comments", mapOf("body" to body))
        })
    }

    // ── update_pull_request (disabled by default) ──────────────────────────────

    @McpTool(name = "update_pull_request")
    @McpDescription(description = """
        Updates a GitHub pull request's title, body, state and/or base branch. Disabled by
        default (mutates remote state).

        Parameters (all optional except `number`):
        - number: the PR number.
        - title: new title.
        - body: new description (Markdown).
        - state: "open" or "closed" — passing "closed" closes the PR.
        - base: change the PR's base branch (rare).
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun update_pull_request(
        number: Int,
        title: String? = null,
        body: String? = null,
        state: String? = null,
        base: String? = null,
        projectPath: String? = null,
    ): String {
        disabledMessage("update_pull_request")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val payload = buildMap<String, Any?> {
                title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                body?.let { put("body", it) }
                state?.takeIf { it.isNotBlank() }?.let { put("state", it.lowercase()) }
                base?.takeIf { it.isNotBlank() }?.let { put("base", it) }
            }
            if (payload.isEmpty()) return@withContext "error: at least one of title/body/state/base must be provided"
            GitHubPluginInvoker.patch(ctx.host, "${ctx.apiBase}/repos/${ctx.repo}/pulls/$number", payload)
        })
    }

    // ── merge_pull_request (disabled by default) ───────────────────────────────

    @McpTool(name = "merge_pull_request")
    @McpDescription(description = """
        Merges a GitHub pull request. Disabled by default (destructive).

        Parameters:
        - number: the PR number.
        - strategy: "merge" (default — merge commit), "squash" (squash all commits), or "rebase".
        - commitTitle: optional commit title override. Ignored for "rebase".
        - commitMessage: optional commit message body. Ignored for "rebase".
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun merge_pull_request(
        number: Int,
        strategy: String = "merge",
        commitTitle: String? = null,
        commitMessage: String? = null,
        projectPath: String? = null,
    ): String {
        disabledMessage("merge_pull_request")?.let { return it }
        val method = when (strategy.lowercase()) {
            "merge", "squash", "rebase" -> strategy.lowercase()
            else -> return captureResponse("error: strategy must be 'merge', 'squash' or 'rebase'")
        }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val payload = buildMap<String, Any?> {
                put("merge_method", method)
                commitTitle?.takeIf { it.isNotBlank() }?.let { put("commit_title", it) }
                commitMessage?.takeIf { it.isNotBlank() }?.let { put("commit_message", it) }
            }
            GitHubPluginInvoker.put(ctx.host, "${ctx.apiBase}/repos/${ctx.repo}/pulls/$number/merge", payload)
        })
    }

    // ── request_pull_request_reviewers (disabled by default) ───────────────────

    @McpTool(name = "request_pull_request_reviewers")
    @McpDescription(description = """
        Requests reviews on a GitHub pull request from one or more users and/or teams. Disabled
        by default (notifies users).

        Parameters:
        - number: the PR number.
        - reviewers: comma-separated GitHub usernames (e.g. "alice,bob").
        - teamReviewers: comma-separated team slugs scoped to the org (e.g. "backend,frontend").
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun request_pull_request_reviewers(
        number: Int,
        reviewers: String = "",
        teamReviewers: String = "",
        projectPath: String? = null,
    ): String {
        disabledMessage("request_pull_request_reviewers")?.let { return it }
        if (reviewers.isBlank() && teamReviewers.isBlank()) {
            return captureResponse("error: at least one of reviewers or teamReviewers must be set")
        }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val payload = buildMap<String, Any?> {
                if (reviewers.isNotBlank()) put("reviewers", reviewers.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                if (teamReviewers.isNotBlank()) put("team_reviewers", teamReviewers.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }
            GitHubPluginInvoker.post(ctx.host, "${ctx.apiBase}/repos/${ctx.repo}/pulls/$number/requested_reviewers", payload)
        })
    }

    // ── create_pull_request (disabled by default) ──────────────────────────────

    @McpTool(name = "create_pull_request")
    @McpDescription(description = """
        Opens a new GitHub pull request. Closes the loop so an AI can do the whole flow inside
        the IDE — create a branch + commit + push via the VCS tools, then open the PR here —
        without ever needing a terminal. Disabled by default (creates public content + notifies).

        Parameters:
        - title: the PR title (required).
        - head: the source branch with your changes (e.g. "feature/foo"). For a cross-fork PR use "owner:branch".
        - base: the target branch to merge into (e.g. "main").
        - body: optional PR description (Markdown).
        - draft: open as a draft PR (default false).
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun create_pull_request(
        title: String,
        head: String,
        base: String,
        body: String = "",
        draft: Boolean = false,
        projectPath: String? = null,
    ): String {
        disabledMessage("create_pull_request")?.let { return it }
        if (title.isBlank()) return captureResponse("error: title is empty")
        if (head.isBlank() || base.isBlank()) return captureResponse("error: both head and base branches are required")
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitHubRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val payload = buildMap<String, Any?> {
                put("title", title)
                put("head", head)
                put("base", base)
                if (body.isNotBlank()) put("body", body)
                if (draft) put("draft", true)
            }
            GitHubPluginInvoker.post(ctx.host, "${ctx.apiBase}/repos/${ctx.repo}/pulls", payload)
        })
    }
}

// ── GitHub repository context + detection ─────────────────────────────────────

private data class GitHubRepoContext(
    val host: String,        // e.g. "github.com" or a GH Enterprise host
    val repo: String,        // "owner/name"
    val apiBase: String,     // "https://api.github.com" or "https://<host>/api/v3"
    val project: Project,
)

private const val NO_REMOTE = "No GitHub 'origin' remote detected. The Pull Request tools require " +
    "the project's `origin` remote to point at a github.com (or GitHub Enterprise) repository."

/**
 * Detects the GitHub repo from the `origin` remote (parsed via the shared [parseOriginRemote],
 * which reads Git4Idea — no `git` CLI). Returns null when the host isn't GitHub.
 */
private fun detectGitHubRepo(project: Project): GitHubRepoContext? {
    val remote = parseOriginRemote(project) ?: return null
    if (!remote.host.endsWith("github.com")) return null  // GitHub only for this toolset
    val apiBase = if (remote.host == "github.com") "https://api.github.com" else "https://${remote.host}/api/v3"
    return GitHubRepoContext(remote.host, remote.repo, apiBase, project)
}

/**
 * Dispatches a GET to GitHub through [GitHubPluginInvoker] (the plugin's GithubApiRequestExecutor),
 * which supplies token-aware auth, IDE proxy support, retries and rate-limit handling. On a
 * missing/unconfigured account it returns the invoker's structured error verbatim.
 */
private fun githubGet(ctx: GitHubRepoContext, path: String, paged: Boolean): String =
    GitHubPluginInvoker.get(ctx.host, ctx.apiBase + path, paged)
