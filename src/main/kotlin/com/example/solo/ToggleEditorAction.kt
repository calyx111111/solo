package com.example.solo

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/** 28×28 方形热区，圆角半径 6 */
private const val BUTTON_SIZE = 28
private const val ARC_SIZE = 12  // 圆角直径，半径 6

/** MainToolbarRight 最右侧会被窗口裁剪，需预留右侧边距 */
// todo : change to 0 in deveco
private val TOOLBAR_RIGHT_INSET = JBUI.scale(80)

/** 图标 20x20 */
private fun Icon.scaledTo(width: Int, height: Int): Icon {
    val base = this
    return object : Icon {
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
            val g2 = g.create() as? Graphics2D ?: return
            try {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.translate(x, y)
                val scaleX = width.toDouble() / base.iconWidth
                val scaleY = height.toDouble() / base.iconHeight
                g2.scale(scaleX, scaleY)
                base.paintIcon(c, g2, 0, 0)
            } finally {
                g2.dispose()
            }
        }

        override fun getIconWidth(): Int = width
        override fun getIconHeight(): Int = height
    }
}

/** 自定义按钮，与 SoloModeToggleButton 相同的 hover 样式 */
internal class ToggleEditorButton(
    private val action: ToggleEditorAction,
    private val presentation: Presentation
) : JComponent() {

    init {
        putClientProperty(CustomComponentAction.ACTION_KEY, action)
        preferredSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
        minimumSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
        toolTipText = presentation.description
        isOpaque = false

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                isHovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                isHovered = false
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && isEnabled) {
                    ActionManager.getInstance().tryToExecute(
                        action, e, this@ToggleEditorButton, ActionPlaces.MAIN_TOOLBAR, true
                    )
                }
            }
        })
    }

    private var isHovered = false

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as? Graphics2D ?: return
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

            if (isHovered && isEnabled) {
                g2.color = HOVER_BACKGROUND
                g2.fill(
                    RoundRectangle2D.Float(
                        0f, 0f, width.toFloat(), height.toFloat(),
                        ARC_SIZE.toFloat(), ARC_SIZE.toFloat()
                    )
                )
            }

            val icon = presentation.icon ?: return
            val x = (width - icon.iconWidth) / 2
            val y = (height - icon.iconHeight) / 2
            icon.paintIcon(this, g2, x, y)
        } finally {
            g2.dispose()
        }
    }

    fun refreshFromPresentation() {
        toolTipText = presentation.description
        isEnabled = presentation.isEnabled
        repaint()
    }

    companion object {
        private val HOVER_BACKGROUND: Color = JBColor(
            Color(0xE8, 0xE8, 0xE8),
            Color(0x45, 0x45, 0x45)
        )
    }
}

class ToggleEditorAction : AnAction(), CustomComponentAction {

    private val splitIcon: Icon = IconLoader.getIcon("/icon/split.svg", javaClass).scaledTo(20, 20)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val button = ToggleEditorButton(this, presentation)
        return JPanel(BorderLayout()).apply {
            add(button, BorderLayout.WEST)
            border = JBUI.Borders.emptyRight(TOOLBAR_RIGHT_INSET)
            preferredSize = Dimension(BUTTON_SIZE + TOOLBAR_RIGHT_INSET, BUTTON_SIZE)
            minimumSize = Dimension(BUTTON_SIZE + TOOLBAR_RIGHT_INSET, BUTTON_SIZE)
            isOpaque = false
            putClientProperty(CustomComponentAction.ACTION_KEY, this@ToggleEditorAction)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SoloModeManager.getInstance(project).toggleEditorPanel()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project != null) {
            val manager = SoloModeManager.getInstance(project)
            if (manager.isSoloModeActive) {
                val collapsed = SoloModePanel.isEditorPanelCollapsed(project)
                e.presentation.text = if (collapsed) "Show Editor" else "Hide Editor"
                e.presentation.description =
                    if (collapsed) "Show editor and project panel" else "Hide editor and project panel"
                e.presentation.icon = splitIcon
                e.presentation.isVisible = true
            } else {
                e.presentation.icon = splitIcon
                e.presentation.isVisible = false
            }
        }
        e.presentation.isEnabled = project != null

        val comp = e.presentation.getClientProperty(CustomComponentAction.COMPONENT_KEY)
        when (comp) {
            is ToggleEditorButton -> comp.refreshFromPresentation()
            is JPanel -> comp.components.filterIsInstance<ToggleEditorButton>().firstOrNull()?.refreshFromPresentation()
        }
    }

    companion object {
        const val ACTION_ID = "com.example.solo.ToggleEditorAction"
    }
}
