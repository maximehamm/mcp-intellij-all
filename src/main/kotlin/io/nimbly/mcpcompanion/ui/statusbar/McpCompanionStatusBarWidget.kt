package io.nimbly.mcpcompanion.ui.statusbar

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants
import javax.swing.Timer
import io.nimbly.mcpcompanion.settings.McpCompanionConfigurable
import io.nimbly.mcpcompanion.McpCompanionSettings
import io.nimbly.mcpcompanion.ui.monitoring.McpCompanionCallsToolWindowFactory

/**
 * Status bar widget that shows MCP tool activity in real time.
 *
 * - Idle (no active call): wireframe outline of the plugin icon (theme-aware).
 * - Active: solid green badge — replaces the wireframe icon.
 * - When N>1 calls are running, a small N is appended after the green badge.
 *
 * Tool names and durations live in the tooltip (last 5 completed + currently running).
 *
 * A small badge counter ("1, 2, 3…") appended after the icon counts MCP calls since the last
 * click; it is hidden at zero. The widget grows only while the badge is visible.
 *
 * Click → resets the badge (hides it) and opens an actions popup (MCP Monitoring / Settings).
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

    private val label = JLabel(iconIdle, SwingConstants.LEADING).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = " "
        iconTextGap = 3
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Clicking clears the call-count badge (back to hidden) and opens the actions menu.
                McpCompanionSettings.getInstance().resetBadge()
                refresh()
                showActionsPopup(e)
            }
        })
    }

    /**
     * Shows an IntelliJ-style action popup (like "Indents Detection" in the editor status bar)
     * with two actions, anchored above the widget.
     */
    private fun showActionsPopup(e: MouseEvent) {
        val showMonitoring = object : com.intellij.openapi.actionSystem.AnAction("MCP Monitoring") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    .getToolWindow(McpCompanionCallsToolWindowFactory.TOOL_WINDOW_ID)
                    ?.activate(null)
            }
        }
        val openSettings = object : com.intellij.openapi.actionSystem.AnAction("MCP Companion settings…") {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, McpCompanionConfigurable::class.java)
            }
        }
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup(showMonitoring, openSettings)
        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createActionGroupPopup(
            "MCP Server Companion",
            group,
            com.intellij.ide.DataManager.getInstance().getDataContext(e.component),
            com.intellij.openapi.ui.popup.JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true,
        )
        popup.show(
            com.intellij.ui.awt.RelativePoint(
                e.component,
                java.awt.Point(0, -popup.content.preferredSize.height),
            )
        )
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
        // Icon-sized (no reserved digit zone): the count is painted as a small badge overlaid on
        // the icon's top-right corner. A few extra px of right padding let the badge overhang.
        val fm = label.getFontMetrics(label.font)
        val width = iconIdle.iconWidth + JBUI.scale(9)
        val height = maxOf(iconIdle.iconHeight, fm.height) + 4
        val fixed = Dimension(width, height)
        label.preferredSize = fixed
        label.minimumSize = fixed
        label.maximumSize = fixed
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
        val base = if (active.isEmpty()) iconIdle else animationFrames[animationFrame % animationFrames.size]
        // Badge: number of MCP calls since the last click, painted as a small blue circle with
        // white digits over the icon's top-right corner. Hidden at 0 or when disabled in Settings.
        val badge = if (settings.isStatusBarBadgeEnabled()) settings.getBadgeCount() else 0
        label.icon = if (badge > 0) BadgeIcon(base, badge) else base
        label.text = ""
        label.toolTipText = buildTooltip(settings, active)
    }

    private fun buildTooltip(
        settings: McpCompanionSettings,
        active: List<McpCompanionSettings.CallRecord>,
    ): String {
        val recent = settings.getRecentCompletedCalls()
        val now = System.nanoTime()

        val sb = StringBuilder("<html><b>MCP Server Companion</b>")

        if (active.isEmpty()) {
            val total = settings.getAllCallCounts().values.sum()
            sb.append("<br><nobr>No active call &mdash; $total call(s) this session.</nobr>")
        } else {
            sb.append("<br><b>${active.size} active call(s):</b>")
            active.sortedBy { it.startedNanos }.forEach { c ->
                val ms = ((c.endedNanos ?: now) - c.startedNanos) / 1_000_000
                val prefix = if (c.isOwnTool) "" else "<i>(other)</i> "
                sb.append("<br><nobr>&nbsp;&nbsp;● ${prefix}<code>${c.toolName}</code> &mdash; ${formatElapsed(ms)}</nobr>")
            }
        }

        if (recent.isNotEmpty()) {
            sb.append("<br><br><b>Last ${recent.size} completed:</b>")
            recent.forEach { c ->
                val durationMs = c.durationMs ?: 0L
                val prefix = if (c.isOwnTool) "" else "<i>(other)</i> "
                sb.append("<br><nobr>&nbsp;&nbsp;✓ ${prefix}<code>${c.toolName}</code> &mdash; ${formatElapsed(durationMs)}</nobr>")
            }
        }

        val badge = settings.getBadgeCount()
        if (badge > 0) sb.append("<br><br><nobr><b>$badge</b> call(s) since last reset.</nobr>")
        sb.append("<br><br><i>Click to reset the counter and open actions</i></html>")
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

/**
 * Wraps [base] and paints a small notification badge — a blue rounded pill with white bold
 * digits — over its top-right corner. Reports the same footprint as [base], so the widget stays
 * icon-sized; the badge may overhang a couple of px to the right (absorbed by widget padding).
 * Counts above 99 render as "99+".
 */
private class BadgeIcon(private val base: Icon, count: Int) : Icon {

    private val text = if (count > 99) "99+" else count.toString()

    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        base.paintIcon(c, g, x, y)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val w = base.iconWidth
            val fontSize = (w * 0.55f).coerceAtLeast(8f)
            g2.font = (c?.font ?: JBUI.Fonts.label()).deriveFont(Font.BOLD, fontSize)
            val fm = g2.fontMetrics
            val tw = fm.stringWidth(text)
            val bh = fm.height - JBUI.scale(1)
            val bw = maxOf(bh, tw + JBUI.scale(4))

            // Anchor to the icon's top-right corner, overhanging ~2px into the widget's right padding.
            val bx = x + w + JBUI.scale(2) - bw
            val by = y
            g2.color = BADGE_BG
            g2.fillRoundRect(bx, by, bw, bh, bh, bh)

            g2.color = BADGE_FG
            val tx = bx + (bw - tw) / 2
            val ty = by + (bh - fm.height) / 2 + fm.ascent
            g2.drawString(text, tx, ty)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        // Background follows the platform's New-UI blue info badge ("IconBadge.infoBackground"),
        // so it matches native notification badges and tracks future look changes (falls back to a
        // notification blue). Foreground is kept plain white: white-on-blue is the standard,
        // always-readable badge look — the theme's "infoForeground" is a low-contrast gray here.
        val BADGE_BG = JBColor.namedColor("IconBadge.infoBackground", JBColor(0x3574F0, 0x548AF7))
        val BADGE_FG: JBColor = JBColor(0xFFFFFF, 0xFFFFFF)
    }
}
