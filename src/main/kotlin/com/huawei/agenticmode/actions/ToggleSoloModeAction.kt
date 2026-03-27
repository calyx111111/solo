package com.huawei.agenticmode.actions

import com.huawei.agenticmode.SoloModeManager
import com.huawei.agenticmode.login.LoginManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.ui.JBUI
import java.awt.*
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
        toolTipText = presentation.text
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
                g2.color = JBUI.CurrentTheme.CustomFrameDecorations.titlePaneButtonHoverBackground()
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
        toolTipText = presentation.text
        isEnabled = presentation.isEnabled
        repaint()
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

        val isLogin = LoginManager.getInstance().hasLogin()
        println("SoloMode: try toggle solo panel, current login status $isLogin")

        if (!isLogin) {
            LoginManager.getInstance().login(project)
            val window = project.let { WindowManager.getInstance().getFrame(it) } as Window ?: return
            LoginManager.getInstance().afterLogin(project, window)
            return
        }

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
        const val ACTION_ID = "com.huawei.agenticmode.ToggleSoloModeAction"
    }
}
