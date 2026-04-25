package io.nimbly.mcpcompanion

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * Status bar widget that shows MCP tool activity in real time.
 *
 * - Idle (no active call): wireframe outline of the plugin icon (theme-aware).
 * - Active: solid green badge — replaces the wireframe icon.
 * - When N>1 calls are running, a small N is appended after the green badge.
 *
 * Tool names and durations live in the tooltip (last 5 completed + currently running).
 *
 * Implemented as a [CustomStatusBarWidget] so the underlying [JLabel] can have a fixed
 * preferred size — that way the status bar layout never reflows.
 *
 * Click → opens Settings → Tools → MCP Server Companion.
 */
class McpCompanionStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "MCP Companion Activity"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = McpCompanionStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) { Disposer.dispose(widget) }
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val WIDGET_ID = "io.nimbly.mcpcompanion.activityWidget"
    }
}

class McpCompanionStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    private var statusBar: StatusBar? = null

    private val iconIdle = IconLoader.getIcon("/icons/mcpStatusIdle.svg", javaClass)

    /**
     * 8-frame "data flow" animation cycle (each frame shown 250 ms → full cycle = 2 s):
     *  - Phase A (IDE → AI):
     *      0. IDE square (top-left) green
     *      1. Top horizontal trait green
     *      2. Right vertical trait green
     *      3. Pause (all gray)
     *  - Phase B (AI → IDE):
     *      4. AI square (bottom-right) green
     *      5. Bottom horizontal trait green
     *      6. Left vertical trait green
     *      7. Pause (all gray)
     */
    private val animationFrames = listOf(
        IconLoader.getIcon("/icons/mcpStatusFrame1.svg", javaClass),
        IconLoader.getIcon("/icons/mcpStatusFrame2.svg", javaClass),
        IconLoader.getIcon("/icons/mcpStatusFrame3.svg", javaClass),
        iconIdle,
        IconLoader.getIcon("/icons/mcpStatusFrame5.svg", javaClass),
        IconLoader.getIcon("/icons/mcpStatusFrame6.svg", javaClass),
        IconLoader.getIcon("/icons/mcpStatusFrame7.svg", javaClass),
        iconIdle,
    )
    private var animationFrame = 0

    private val label = JLabel(iconIdle, SwingConstants.CENTER).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = " "
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, McpCompanionConfigurable::class.java)
            }
        })
    }

    /**
     * 100 ms refresh — drives both the tooltip update and the active-state animation flip.
     * 8 frames × 100 ms = 800 ms full cycle (kept in sync with [McpCompanionSettings.MIN_VISIBLE_MS]
     * so every call shows at least one complete rotation).
     */
    private val timer = Timer(100) {
        animationFrame++
        refresh()
    }

    override fun ID(): String = McpCompanionStatusBarWidgetFactory.WIDGET_ID

    override fun getComponent(): JComponent = label

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        // Lock the preferred size — only an icon, both states are the same width.
        val fm = label.getFontMetrics(label.font)
        val width = iconIdle.iconWidth + 8
        val height = maxOf(iconIdle.iconHeight, fm.height) + 4
        val fixed = Dimension(width, height)
        label.preferredSize = fixed
        label.minimumSize = fixed
        refresh()
        timer.start()
    }

    override fun dispose() {
        timer.stop()
        statusBar = null
    }

    // ── refresh logic ────────────────────────────────────────────────────────

    private fun refresh() {
        val settings = McpCompanionSettings.getInstance()
        val active = settings.getActiveCalls()
        label.icon = if (active.isEmpty()) {
            iconIdle
        } else {
            animationFrames[animationFrame % animationFrames.size]
        }
        label.text = ""
        label.toolTipText = buildTooltip(settings, active)
    }

    private fun buildTooltip(
        settings: McpCompanionSettings,
        active: List<McpCompanionSettings.ActiveCall>,
    ): String {
        val recent = settings.getRecentCalls()
        val now = System.nanoTime()

        val sb = StringBuilder("<html><b>MCP Server Companion</b>")

        if (active.isEmpty()) {
            val total = settings.getAllCallCounts().values.sum()
            sb.append("<br><nobr>No active call &mdash; $total call(s) this session.</nobr>")
        } else {
            sb.append("<br><b>${active.size} active call(s):</b>")
            active.sortedBy { it.startNanos }.forEach { c ->
                val ms = ((c.endNanos ?: now) - c.startNanos) / 1_000_000
                sb.append("<br><nobr>&nbsp;&nbsp;● <code>${c.name}</code> &mdash; ${formatElapsed(ms)}</nobr>")
            }
        }

        if (recent.isNotEmpty()) {
            sb.append("<br><br><b>Last ${recent.size} completed:</b>")
            recent.forEach { c ->
                sb.append("<br><nobr>&nbsp;&nbsp;✓ <code>${c.name}</code> &mdash; ${formatElapsed(c.durationMs)}</nobr>")
            }
        }

        sb.append("<br><br><i>Click to open MCP Companion settings</i></html>")
        return sb.toString()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Uses U+00A0 (non-breaking space) between number and unit to keep them on the same line. */
    private fun formatElapsed(ms: Long): String {
        val nbsp = ' '
        return when {
            ms < 1_000 -> "${ms}${nbsp}ms"
            ms < 10_000 -> "%.1f${nbsp}s".format(ms / 1000.0)
            ms < 60_000 -> "${ms / 1000}${nbsp}s"
            else -> "${ms / 60_000}${nbsp}m${nbsp}${(ms % 60_000) / 1000}${nbsp}s"
        }
    }
}
