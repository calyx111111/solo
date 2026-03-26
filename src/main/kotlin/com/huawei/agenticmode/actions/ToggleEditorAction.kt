package com.huawei.agenticmode.actions

import com.huawei.agenticmode.SoloModeManager
import com.huawei.agenticmode.SoloModePanel
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.JBUI
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

/** MainToolbarRight 最右侧会被窗口裁剪，需预留右侧边距；DevEco Studio 下设为 0 */
private val toolBarRightInset = JBUI.scale(
    if (ApplicationNamesInfo.getInstance().fullProductName == "DevEco Studio") 0 else 80
)

/** 自定义按钮，与 SoloModeToggleButton 相同的 hover 样式 */
internal class ToggleEditorButton(
    private val action: ToggleEditorAction,
    private val presentation: Presentation
) : JComponent() {

    init {
        putClientProperty(CustomComponentAction.ACTION_KEY, action)
        preferredSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
        minimumSize = Dimension(BUTTON_SIZE, BUTTON_SIZE)
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
                g2.color = JBUI.CurrentTheme.CustomFrameDecorations.titlePaneButtonHoverBackground()
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
        toolTipText = presentation.text
        isEnabled = presentation.isEnabled
        repaint()
    }
}

class ToggleEditorAction : AnAction(), CustomComponentAction {

    private val splitIcon: Icon = IconLoader.getIcon("/icon/toggle_right.svg", javaClass).scaledTo(20, 20)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        val button = ToggleEditorButton(this, presentation)
        return JPanel(BorderLayout()).apply {
            add(button, BorderLayout.WEST)
            border = JBUI.Borders.emptyRight(toolBarRightInset)
            preferredSize = Dimension(BUTTON_SIZE + toolBarRightInset, BUTTON_SIZE)
            minimumSize = Dimension(BUTTON_SIZE + toolBarRightInset, BUTTON_SIZE)
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
            else -> {}
        }
    }

    companion object {
        const val ACTION_ID = "com.huawei.agenticmode.ToggleEditorAction"
    }
}
