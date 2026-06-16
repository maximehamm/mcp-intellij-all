package io.nimbly.mcpcompanion.toolsets

import com.intellij.icons.AllIcons
import com.intellij.mcpserver.clientInfo
import com.intellij.mcpserver.mcpCallInfoOrNull
import com.intellij.mcpserver.project
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.RightGap
import com.intellij.ui.dsl.builder.TopGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import io.nimbly.mcpcompanion.McpCompanionSettings
import io.nimbly.mcpcompanion.util.CallPayloadStorage
import io.nimbly.mcpcompanion.util.runOnEdt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.awt.Dimension
import javax.swing.JComponent
import kotlin.coroutines.coroutineContext

/**
 * Single entry gate run at the top of every `@McpTool` (via each toolset's private
 * `disabledMessage`). It enforces, in order:
 *
 * 1. **Enabled check** — if the user disabled the tool in Settings, return an explanatory
 *    message so the AI knows to ask the user rather than retry.
 * 2. **Confirmation** — for higher-risk tools ([McpCompanionSettings.CONFIRM_BY_DEFAULT]) that
 *    have not been granted standing consent, show the native Allow/Deny dialog. A "Deny" returns
 *    a message telling the AI the call was declined. An "Always allow" tick persists consent.
 * 3. **Telemetry/usage tracking** — counted once the call is allowed to proceed.
 *
 * Returns a non-null String when the call must be short-circuited (disabled or declined);
 * returns null when the tool should run normally.
 */
internal suspend fun toolGate(toolName: String): String? {
    val settings = McpCompanionSettings.getInstance()
    val client = runCatching {
        coroutineContext.clientInfo?.name?.takeIf { it != "Unknown MCP client" }
    }.getOrNull()

    // Higher-risk tool: the checkbox is the consent. Checked → run silently; unchecked → prompt.
    if (toolName in McpCompanionSettings.CONFIRM_BY_DEFAULT) {
        settings.trackCall(toolName, client)
        if (settings.isEnabled(toolName)) return null   // ☑ "always allow" → run without asking
        val project = runCatching { coroutineContext.project }.getOrNull()
        val arguments = runCatching {
            coroutineContext.mcpCallInfoOrNull?.callId?.let { CallPayloadStorage.load(it)?.parameters }
        }.getOrNull()
        // Allow + "Always allow" ticked → ToolConfirmation checks the tool in Settings.
        val approved = ToolConfirmation.confirm(project, toolName, client, arguments)
        return if (approved) null
               else "Tool '$toolName' was declined by the user in the IDE. " +
                    "Do not retry — ask the user how they want to proceed."
    }

    // Regular tool: unchecked simply disables it.
    if (!settings.isEnabled(toolName)) {
        return "Tool '$toolName' is disabled. Enable it in Settings → Tools → MCP Server Companion."
    }
    settings.trackCall(toolName, client)
    return null
}

/**
 * Shows the confirmation dialog gating higher-risk MCP tools. Built as a plain [DialogWrapper]
 * with a JSON-highlighted [EditorTextField] for the arguments — same construction the bundled
 * MCP server uses for its own confirmation dialog — plus an "Always allow" checkbox that, when
 * ticked alongside Allow, checks the tool in Settings so it never prompts again. Public platform
 * API only (no internal usage), so it stays compatible with the Marketplace verifier.
 */
internal object ToolConfirmation {

    /** @return true if the user clicked Allow. Ticking "Always allow" + Allow also checks the tool. */
    fun confirm(project: Project?, toolName: String, client: String?, arguments: String?): Boolean {
        // No UI to show the dialog on (unit tests, headless runIde): there is no human to ask,
        // and a modal dialog would hang the EDT. Allow the call so automated/headless flows keep
        // working — the dialog is an interactive-use safety net, not a hard headless gate.
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (app == null || app.isHeadlessEnvironment || app.isUnitTestMode) return true
        return runOnEdt {
            val dialog = ToolConfirmationDialog(project, toolName, headline(toolName, client), prettyArgs(arguments))
            val approved = dialog.showAndGet()
            if (approved && dialog.isAlwaysAllow) McpCompanionSettings.getInstance().setEnabled(toolName, true)
            approved
        }
    }

    private fun headline(toolName: String, client: String?): String {
        val who = client?.let { "“$it”" } ?: "An AI assistant"
        val action = ACTION_DESCRIPTIONS[toolName] ?: "run a higher-risk operation"
        return "$who wants to $action."
    }

    /** Pretty-prints the JSON args, or returns null when there are none worth showing. */
    private fun prettyArgs(raw: String?): String? {
        val args = raw?.trim()
        if (args.isNullOrEmpty() || args == "{}") return null
        val pretty = runCatching {
            PRETTY.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(args))
        }.getOrElse { args }
        return if (pretty.length > MAX_ARG_CHARS) pretty.take(MAX_ARG_CHARS) + "\n…(truncated)" else pretty
    }

    private val PRETTY = Json { prettyPrint = true }

    private const val MAX_ARG_CHARS = 800

    /** Human-readable, one-line summaries of what each gated tool does. */
    private val ACTION_DESCRIPTIONS = mapOf(
        "send_to_terminal"               to "run a command in a terminal tab",
        "delete_file"                    to "delete a file from the project",
        "execute_database_query"         to "execute a SQL query (may UPDATE / DELETE / DROP)",
        "vcs_delete_branch"              to "delete a Git branch",
        "vcs_rename_branch"              to "rename a Git branch (and possibly the remote one)",
        "add_pull_request_comment"       to "post a comment on a GitHub pull request",
        "update_pull_request"            to "update a GitHub pull request (title / body / state)",
        "merge_pull_request"             to "merge a GitHub pull request",
        "request_pull_request_reviewers" to "request reviewers on a GitHub pull request",
        "create_pull_request"            to "open a new GitHub pull request",
        "add_merge_request_comment"      to "post a note on a GitLab merge request",
        "update_merge_request"           to "update a GitLab merge request (title / description / state)",
        "merge_merge_request"            to "merge a GitLab merge request",
        "create_merge_request"           to "open a new GitLab merge request",
    )
}

/**
 * The Allow/Deny dialog. Shows the headline, the call arguments as syntax-highlighted JSON
 * (read-only [EditorTextField] using the IDE's JSON file type, so it follows the editor theme),
 * and an "Always allow" checkbox.
 */
private class ToolConfirmationDialog(
    project: Project?,
    private val toolName: String,
    private val headlineText: String,
    private val prettyJsonArgs: String?,
) : DialogWrapper(project, true) {

    private val theProject = project
    private val alwaysAllowCheckbox = JBCheckBox("Always allow “$toolName” without asking")

    val isAlwaysAllow: Boolean get() = alwaysAllowCheckbox.isSelected

    init {
        title = "MCP Companion — allow “$toolName”?"
        setOKButtonText("Allow")
        setCancelButtonText("Deny")
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        row {
            icon(AllIcons.General.WarningDialog).align(AlignY.TOP).gap(RightGap.SMALL)
            text(headlineText).resizableColumn()
        }.bottomGap(BottomGap.SMALL)
        prettyJsonArgs?.let { json ->
            row {
                cell(buildJsonViewer(json)).align(Align.FILL)
            }.resizableRow()
        }
        row { cell(alwaysAllowCheckbox) }.topGap(TopGap.SMALL)
    }

    /** Read-only, syntax-highlighted JSON view that follows the editor theme. */
    private fun buildJsonViewer(json: String): EditorTextField {
        val jsonType = FileTypeManager.getInstance().getFileTypeByExtension("json")
        return EditorTextField(json, theProject, jsonType).apply {
            setOneLineMode(false)
            isViewer = true
            val lines = json.count { it == '\n' } + 1
            preferredSize = Dimension(JBUI.scale(480), JBUI.scale(lines.coerceIn(2, 18) * 18 + 10))
            addSettingsProvider { editor: EditorEx ->
                editor.settings.apply {
                    isLineNumbersShown = false
                    isLineMarkerAreaShown = false
                    isFoldingOutlineShown = false
                    isRightMarginShown = false
                    isCaretRowShown = false
                    additionalColumnsCount = 0
                    additionalLinesCount = 0
                }
                // Always show scrollbars so long argument blobs are scrollable within the capped height.
                editor.setVerticalScrollbarVisible(true)
                editor.setHorizontalScrollbarVisible(true)
                editor.setBorder(JBUI.Borders.empty(6))
            }
        }
    }
}
