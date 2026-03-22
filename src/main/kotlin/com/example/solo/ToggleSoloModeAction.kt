package com.example.solo

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities

/** 按钮尺寸与 SVG 一致；圆角半径 rx=14，RoundRectangle2D 的 arcWidth/arcHeight 为直径 = 28 */
private const val BUTTON_WIDTH = 80
private const val BUTTON_SIZE_28 = 28  // 按钮高度与圆角直径共用

/**
 * 将图标缩放到指定尺寸显示，解决 toolbar 将 SVG 限制为 16x16 导致 80x28 图标显示过小的问题。
 */
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

/**
 * 自定义 toolbar 按钮组件，hover 背景为 80×28、rx=14 圆角，与图标区域完全一致。
 */
class SoloModeToggleButton(
    private val action: ToggleSoloModeAction,
    private val presentation: Presentation
) : JComponent() {

    init {
        putClientProperty(CustomComponentAction.ACTION_KEY, action)
        preferredSize = Dimension(BUTTON_WIDTH, BUTTON_SIZE_28)
        minimumSize = Dimension(BUTTON_WIDTH, BUTTON_SIZE_28)
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
                        action, e, this@SoloModeToggleButton, ActionPlaces.MAIN_TOOLBAR, true
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
                        0f,
                        0f,
                        width.toFloat(),
                        height.toFloat(),
                        BUTTON_SIZE_28.toFloat(),
                        BUTTON_SIZE_28.toFloat()
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

class ToggleSoloModeAction : AnAction(), CustomComponentAction {

    private val offIcon: Icon = IconLoader.getIcon("/icon/off.svg", javaClass).scaledTo(BUTTON_WIDTH, BUTTON_SIZE_28)
    private val onIcon: Icon = IconLoader.getIcon("/icon/on.svg", javaClass).scaledTo(BUTTON_WIDTH, BUTTON_SIZE_28)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        return SoloModeToggleButton(this, presentation)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        SoloModeManager.getInstance(project).toggleSoloMode()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project != null) {
            val manager = SoloModeManager.getInstance(project)
            if (manager.isSoloModeActive) {
                e.presentation.text = "Exit Solo Mode"
                e.presentation.description = "Return to standard IDE mode"
                e.presentation.icon = onIcon
            } else {
                e.presentation.text = "Enter Solo Mode"
                e.presentation.description = "Switch to focused editing mode"
                e.presentation.icon = offIcon
            }
        }
        e.presentation.isEnabled = project != null

        (e.presentation.getClientProperty(CustomComponentAction.COMPONENT_KEY) as? SoloModeToggleButton)?.refreshFromPresentation()
    }

    companion object {
        const val ACTION_ID = "com.example.solo.ToggleSoloModeAction"
    }
}
