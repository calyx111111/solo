package com.huawei.agenticmode

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.Timer

class TabbedPanelContainer : JPanel(BorderLayout()) {
    
    private val tabPanel: JPanel
    private val contentPanel: JPanel
    private val tabs = mutableListOf<TabInfo>()
    private var selectedIndex = 0
    private var onTabChangeListener: ((Int) -> Unit)? = null
    
    private var animationProgress = 1.0
    private var animationTimer: Timer? = null
    private var previousSelectedIndex = 0
    
    private val activeColor: Color
        get() = JBColor(Color(0x3D, 0x7E, 0xFA), Color(0x2B, 0x5E, 0xD0))
    
    private val hoverColor: Color
        get() = JBColor(Color(0x5A, 0x9D, 0xFF), Color(0x3D, 0x7E, 0xFA))
    
    private val inactiveColor: Color
        get() = JBColor(Color(0x6C, 0x75, 0x7D), Color(0x8B, 0x95, 0xA5))
    
    private val tabBackgroundColor: Color
        get() = JBColor(Color(0xF8, 0xF9, 0xFA), Color(0x2B, 0x2B, 0x2B))
    
    private val contentBackgroundColor: Color
        get() = JBColor(Color.WHITE, Color(0x1E, 0x1E, 0x1E))
    
    private val borderColor: Color
        get() = JBColor(Color(0xE9, 0xEC, 0xEF), Color(0x3C, 0x3F, 0x44))
    
    private val tabButtons = mutableListOf<TabButtonPanel>()
    
    data class TabInfo(
        val title: String,
        val component: JComponent,
        val icon: Icon? = null
    )
    
    init {
        tabPanel = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                g2d.color = borderColor
                g2d.drawLine(0, height - 1, width, height - 1)
            }
        }
        tabPanel.layout = BoxLayout(tabPanel, BoxLayout.X_AXIS)
        tabPanel.background = tabBackgroundColor
        tabPanel.border = JBUI.Borders.empty(8, 12, 0, 12)
        tabPanel.isOpaque = true
        
        contentPanel = JPanel(CardLayout()).apply {
            background = contentBackgroundColor
            border = JBUI.Borders.empty()
        }
        
        val container = JPanel(BorderLayout()).apply {
            add(tabPanel, BorderLayout.NORTH)
            add(contentPanel, BorderLayout.CENTER)
            background = tabBackgroundColor
        }
        
        add(container, BorderLayout.CENTER)
    }
    
    fun addTab(title: String, component: JComponent, icon: Icon? = null) {
        val tabInfo = TabInfo(title, component, icon)
        tabs.add(tabInfo)
        
        val tabButton = createTabButton(title, tabs.size - 1, icon)
        tabButtons.add(tabButton)
        tabPanel.add(tabButton)
        tabPanel.add(Box.createHorizontalStrut(4))
        
        contentPanel.add(component, title)
        
        if (tabs.size == 1) {
            updateTabSelection(0, true)
        }
        
        revalidate()
        repaint()
    }
    
    private fun createTabButton(title: String, index: Int, icon: Icon?): TabButtonPanel {
        val panel = TabButtonPanel(title, index, icon, this)
        
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    selectTab(index)
                }
            }
            
            override fun mouseEntered(e: MouseEvent) {
                if (index != selectedIndex) {
                    panel.setHovered(true)
                }
            }
            
            override fun mouseExited(e: MouseEvent) {
                panel.setHovered(false)
            }
        })
        
        return panel
    }
    
    fun selectTab(index: Int) {
        if (index < 0 || index >= tabs.size || index == selectedIndex) return
        
        previousSelectedIndex = selectedIndex
        
        updateTabSelection(index, false)
        
        SwingUtilities.invokeLater {
            val cardLayout = contentPanel.layout as CardLayout
            cardLayout.show(contentPanel, tabs[index].title)
            contentPanel.revalidate()
            contentPanel.repaint()
        }
        
        onTabChangeListener?.invoke(index)
    }
    
    private fun updateTabSelection(newIndex: Int, isInitial: Boolean) {
        if (!isInitial && newIndex == selectedIndex) return
        
        previousSelectedIndex = selectedIndex
        selectedIndex = newIndex
        
        for (tabButton in tabButtons) {
            val tabIndex = tabButton.index
            tabButton.setSelected(tabIndex == newIndex)
        }
        
        tabPanel.repaint()
    }
    
    fun setOnTabChangeListener(listener: (Int) -> Unit) {
        onTabChangeListener = listener
    }
    
    fun getSelectedIndex(): Int = selectedIndex
    
    fun getTabCount(): Int = tabs.size
    
    fun getTabComponent(index: Int): JComponent? {
        return if (index in tabs.indices) tabs[index].component else null
    }
    
    fun getSelectedComponent(): JComponent? {
        return if (selectedIndex in tabs.indices) tabs[selectedIndex].component else null
    }
    
    private inner class TabButtonPanel(
        val title: String,
        val index: Int,
        val icon: Icon?,
        val container: TabbedPanelContainer
    ) : JPanel(BorderLayout()) {
        
        private var isSelected = false
        private var isHovered = false
        private var hoverAlpha = 0f
        
        private val label: JLabel
        
        private var hoverAnimationTimer: Timer? = null
        
        init {
            isOpaque = false
            border = JBUI.Borders.empty(10, 18, 10, 18)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            
            label = JLabel(title).apply {
                foreground = if (isSelected) Color.WHITE else inactiveColor
                horizontalAlignment = SwingConstants.CENTER
                font = font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN, 13f)
                this.icon = icon
                horizontalTextPosition = SwingConstants.TRAILING
                iconTextGap = if (icon != null) 8 else 0
            }
            
            add(label, BorderLayout.CENTER)
        }
        
        fun setSelected(selected: Boolean) {
            if (isSelected != selected) {
                isSelected = selected
                updateAppearance()
            }
        }
        
        fun setHovered(hovered: Boolean) {
            if (isHovered != hovered) {
                isHovered = hovered
                animateHover()
            }
        }
        
        private fun updateAppearance() {
            label.foreground = if (isSelected) Color.WHITE else inactiveColor
            label.font = label.font.deriveFont(if (isSelected) Font.BOLD else Font.PLAIN, 13f)
            repaint()
        }
        
        private fun animateHover() {
            val targetAlpha = if (isHovered && !isSelected) 0.15f else 0f
            val startAlpha = hoverAlpha
            val steps = 8
            var step = 0
            
            hoverAnimationTimer?.stop()
            
            hoverAnimationTimer = Timer(12) { e ->
                step++
                val progress = step.toFloat() / steps
                hoverAlpha = startAlpha + (targetAlpha - startAlpha) * easeOutCubic(progress)
                repaint()
                
                if (step >= steps) {
                    hoverAlpha = targetAlpha
                    (e.source as Timer).stop()
                }
            }
            hoverAnimationTimer?.start()
        }
        
        private fun easeOutCubic(x: Float): Float {
            return 1f - (1f - x) * (1f - x) * (1f - x)
        }
        
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            
            val g2d = g as Graphics2D
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            
            val width = width
            val height = height
            val arc = 8
            
            if (isSelected) {
                g2d.color = activeColor
                val rect = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.toFloat(), arc.toFloat())
                g2d.fill(rect)
                
                g2d.color = activeColor.darker()
                g2d.stroke = BasicStroke(1f)
                g2d.draw(rect)
            } else if (isHovered && hoverAlpha > 0) {
                val baseColor = hoverColor
                val alpha = (hoverAlpha * 255).toInt().coerceIn(0, 255)
                g2d.color = Color(baseColor.red, baseColor.green, baseColor.blue, alpha)
                val rect = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.toFloat(), arc.toFloat())
                g2d.fill(rect)
            }
        }
        
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            return Dimension(size.width.coerceAtLeast(80), size.height)
        }
    }
}
