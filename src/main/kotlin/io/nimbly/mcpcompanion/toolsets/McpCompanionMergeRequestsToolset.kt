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
 * Tools for inspecting and acting on **GitLab Merge Requests** from inside the IDE.
 *
 * Auth comes from the GitLab account configured in Settings → Version Control → GitLab (read
 * reflectively from the bundled plugin); transport goes through the IDE's HTTP stack
 * (`HttpRequests`, proxy/SSL-aware). No `glab` CLI, no shell-out — see [GitLabPluginInvoker].
 *
 * The repository is auto-detected from the `origin` remote URL (read via Git4Idea). GitLab's
 * native vocabulary is used throughout: "merge request" (MR), `iid`, "notes", "approvals".
 */
class McpCompanionMergeRequestsToolset : McpToolset {

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

    // ── list_merge_requests ────────────────────────────────────────────────────

    @McpTool(name = "list_merge_requests")
    @McpDescription(description = """
        Lists merge requests of the current project's GitLab repository (auto-detected from the
        `origin` remote).

        Parameters:
        - state: "opened" (default), "closed", "merged", or "all".
        - sourceBranch: optional source branch filter (e.g. "feature/foo").
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.

        Authentication uses the GitLab account configured in Settings → Version Control → GitLab.
    """)
    suspend fun list_merge_requests(
        state: String = "opened",
        sourceBranch: String? = null,
        projectPath: String? = null,
    ): String {
        disabledMessage("list_merge_requests")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val s = when (state.lowercase()) { "open" -> "opened"; else -> state.lowercase() }
            val b = sourceBranch?.let { "&source_branch=${URLEncoder.encode(it, Charsets.UTF_8)}" }.orEmpty()
            GitLabPluginInvoker.get(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/merge_requests?state=$s$b&per_page=50", paged = true)
        })
    }

    // ── get_merge_request ──────────────────────────────────────────────────────

    @McpTool(name = "get_merge_request")
    @McpDescription(description = """
        Returns the full metadata of a single GitLab merge request: title, description, author,
        source/target branches, state, merge status, reviewers, pipeline status, timestamps.

        Parameters:
        - iid: the MR's internal ID (the number shown in the GitLab UI / URL).
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun get_merge_request(iid: Int, projectPath: String? = null): String {
        disabledMessage("get_merge_request")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            GitLabPluginInvoker.get(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/merge_requests/$iid", paged = false)
        })
    }

    // ── get_merge_request_comments (notes) ──────────────────────────────────────

    @McpTool(name = "get_merge_request_comments")
    @McpDescription(description = """
        Returns the notes (comments and system notes) on a GitLab merge request. Discussion
        comments and diff-anchored comments are both returned, with author and timestamps.

        Parameters:
        - iid: the MR's internal ID.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun get_merge_request_comments(iid: Int, projectPath: String? = null): String {
        disabledMessage("get_merge_request_comments")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            GitLabPluginInvoker.get(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/merge_requests/$iid/notes?per_page=100", paged = true)
        })
    }

    // ── get_merge_request_changes (files) ───────────────────────────────────────

    @McpTool(name = "get_merge_request_changes")
    @McpDescription(description = """
        Returns the files changed in a GitLab merge request, each with old/new path, flags
        (new/deleted/renamed file), and the per-file diff. GitLab's equivalent of "PR files".

        Parameters:
        - iid: the MR's internal ID.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun get_merge_request_changes(iid: Int, projectPath: String? = null): String {
        disabledMessage("get_merge_request_changes")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            GitLabPluginInvoker.get(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/merge_requests/$iid/changes", paged = false)
        })
    }

    // ── get_merge_request_commits ───────────────────────────────────────────────

    @McpTool(name = "get_merge_request_commits")
    @McpDescription(description = """
        Returns the list of commits on a GitLab merge request, each with SHA, author, title and
        message. Auto-paginated.

        Parameters:
        - iid: the MR's internal ID.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun get_merge_request_commits(iid: Int, projectPath: String? = null): String {
        disabledMessage("get_merge_request_commits")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            GitLabPluginInvoker.get(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/merge_requests/$iid/commits?per_page=100", paged = true)
        })
    }

    // ── get_merge_request_approvals ─────────────────────────────────────────────

    @McpTool(name = "get_merge_request_approvals")
    @McpDescription(description = """
        Returns the approval state of a GitLab merge request: who has approved, how many
        approvals are required/given, and whether the MR is approved. (GitLab's review model is
        approvals rather than GitHub-style reviews.)

        Parameters:
        - iid: the MR's internal ID.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun get_merge_request_approvals(iid: Int, projectPath: String? = null): String {
        disabledMessage("get_merge_request_approvals")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            GitLabPluginInvoker.get(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/merge_requests/$iid/approvals", paged = false)
        })
    }

    // ── search_gitlab ───────────────────────────────────────────────────────────

    @McpTool(name = "search_gitlab")
    @McpDescription(description = """
        Searches the current GitLab project using the GitLab Search API.

        Parameters:
        - scope: what to search — "merge_requests" (default), "issues", "commits", "blobs", "milestones".
        - search: the search term.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun search_gitlab(search: String, scope: String = "merge_requests", projectPath: String? = null): String {
        disabledMessage("search_gitlab")?.let { return it }
        if (search.isBlank()) return captureResponse("error: search is empty")
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val sc = URLEncoder.encode(scope, Charsets.UTF_8)
            val q = URLEncoder.encode(search, Charsets.UTF_8)
            GitLabPluginInvoker.get(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/search?scope=$sc&search=$q&per_page=50", paged = true)
        })
    }

    // ── add_merge_request_comment (disabled by default) ─────────────────────────

    @McpTool(name = "add_merge_request_comment")
    @McpDescription(description = """
        Posts a comment (note) on a GitLab merge request. Disabled by default (creates content
        visible to the project + notifies participants).

        Parameters:
        - iid: the MR's internal ID.
        - body: the comment text (Markdown supported).
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun add_merge_request_comment(iid: Int, body: String, projectPath: String? = null): String {
        disabledMessage("add_merge_request_comment")?.let { return it }
        if (body.isBlank()) return captureResponse("error: body is empty")
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            GitLabPluginInvoker.post(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/merge_requests/$iid/notes", mapOf("body" to body))
        })
    }

    // ── update_merge_request (disabled by default) ──────────────────────────────

    @McpTool(name = "update_merge_request")
    @McpDescription(description = """
        Updates a GitLab merge request's title, description, state and/or target branch. Disabled
        by default (mutates remote state).

        Parameters (all optional except `iid`):
        - iid: the MR's internal ID.
        - title: new title.
        - description: new description (Markdown).
        - state: "close" or "reopen" (maps to GitLab's `state_event`).
        - targetBranch: change the MR's target branch.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun update_merge_request(
        iid: Int,
        title: String? = null,
        description: String? = null,
        state: String? = null,
        targetBranch: String? = null,
        projectPath: String? = null,
    ): String {
        disabledMessage("update_merge_request")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val payload = buildMap<String, Any?> {
                title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                description?.let { put("description", it) }
                state?.takeIf { it.isNotBlank() }?.let {
                    val ev = when (it.lowercase()) { "close", "closed" -> "close"; "reopen", "open", "opened" -> "reopen"; else -> it.lowercase() }
                    put("state_event", ev)
                }
                targetBranch?.takeIf { it.isNotBlank() }?.let { put("target_branch", it) }
            }
            if (payload.isEmpty()) return@withContext "error: at least one of title/description/state/targetBranch must be provided"
            GitLabPluginInvoker.put(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/merge_requests/$iid", payload)
        })
    }

    // ── merge_merge_request (disabled by default) ───────────────────────────────

    @McpTool(name = "merge_merge_request")
    @McpDescription(description = """
        Merges (accepts) a GitLab merge request. Disabled by default (destructive).

        Parameters:
        - iid: the MR's internal ID.
        - squash: squash commits on merge (default false).
        - mergeCommitMessage: optional custom merge commit message.
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun merge_merge_request(
        iid: Int,
        squash: Boolean = false,
        mergeCommitMessage: String? = null,
        projectPath: String? = null,
    ): String {
        disabledMessage("merge_merge_request")?.let { return it }
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val payload = buildMap<String, Any?> {
                if (squash) put("squash", true)
                mergeCommitMessage?.takeIf { it.isNotBlank() }?.let { put("merge_commit_message", it) }
            }
            GitLabPluginInvoker.put(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/merge_requests/$iid/merge", payload)
        })
    }

    // ── create_merge_request (disabled by default) ──────────────────────────────

    @McpTool(name = "create_merge_request")
    @McpDescription(description = """
        Opens a new GitLab merge request. Completes the create-branch → commit → push → open-MR
        flow entirely inside the IDE — no terminal needed. Disabled by default (creates content
        + notifies).

        Parameters:
        - title: the MR title (required).
        - sourceBranch: the branch with your changes (required).
        - targetBranch: the branch to merge into (required, e.g. "main").
        - description: optional MR description (Markdown).
        - removeSourceBranch: delete the source branch after merge (default false).
        - projectPath: absolute path of the target project's root — defaults to the currently-focused project.
    """)
    suspend fun create_merge_request(
        title: String,
        sourceBranch: String,
        targetBranch: String,
        description: String = "",
        removeSourceBranch: Boolean = false,
        projectPath: String? = null,
    ): String {
        disabledMessage("create_merge_request")?.let { return it }
        if (title.isBlank()) return captureResponse("error: title is empty")
        if (sourceBranch.isBlank() || targetBranch.isBlank()) return captureResponse("error: both sourceBranch and targetBranch are required")
        return captureResponse(withContext(Dispatchers.IO) {
            val ctx = detectGitLabRepo(resolveProject(projectPath)) ?: return@withContext NO_REMOTE
            val payload = buildMap<String, Any?> {
                put("title", title)
                put("source_branch", sourceBranch)
                put("target_branch", targetBranch)
                if (description.isNotBlank()) put("description", description)
                if (removeSourceBranch) put("remove_source_branch", true)
            }
            GitLabPluginInvoker.post(ctx.host, "${ctx.apiBase}/projects/${ctx.projectId}/merge_requests", payload)
        })
    }
}

// ── GitLab repository context + detection ─────────────────────────────────────

private data class GitLabRepoContext(
    val host: String,        // e.g. "gitlab.com" or a self-managed host
    val projectId: String,   // URL-encoded "owner/name" (GitLab accepts this as the project id)
    val apiBase: String,     // "https://<host>/api/v4"
    val project: Project,
)

private const val NO_REMOTE = "No GitLab 'origin' remote detected. The Merge Request tools require " +
    "the project's `origin` remote to point at a gitlab.com (or self-managed GitLab) repository."

/**
 * Detects the GitLab repo from the `origin` remote (parsed via the shared [parseOriginRemote]).
 * Returns null when the host isn't recognised as GitLab.
 */
private fun detectGitLabRepo(project: Project): GitLabRepoContext? {
    val remote = parseOriginRemote(project) ?: return null
    val isGitLab = remote.host == "gitlab.com" || remote.host.contains("gitlab")
    if (!isGitLab) return null
    val projectId = URLEncoder.encode(remote.repo, Charsets.UTF_8)
    return GitLabRepoContext(remote.host, projectId, "https://${remote.host}/api/v4", project)
}
