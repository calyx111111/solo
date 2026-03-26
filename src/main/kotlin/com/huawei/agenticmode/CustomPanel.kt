package com.huawei.agenticmode

import com.intellij.openapi.project.Project
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class CustomPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    companion object {
        private var browserInstance: JBCefBrowser? = null
    }
    
    private val browser: JBCefBrowser
    
    init {
        browser = getOrCreateBrowser()
        setupUi()
    }
    
    private fun getOrCreateBrowser(): JBCefBrowser {
        // 修复：安全处理可空的browserInstance，避免NullPointerException
        if (browserInstance == null) {
            browserInstance = JBCefBrowser("https://www.jetbrains.com")
        }
        return browserInstance ?: throw IllegalStateException("Browser instance should not be null")
    }
    
    private fun setupUi() {
        border = JBUI.Borders.empty()
        preferredSize = Dimension(300, 400)
        minimumSize = Dimension(200, 100)
        
        val browserComponent = browser.component
        add(browserComponent, BorderLayout.CENTER)
    }
    
    fun refresh() {
        browser.cefBrowser.reload()
    }
    
    fun goBack() {
        browser.cefBrowser.goBack()
    }
    
    fun goForward() {
        browser.cefBrowser.goForward()
    }
    
    fun dispose() {
        // 不销毁browser实例，保持其状态
        // browser.dispose()
    }
    
    fun removeBrowser() {
        val browserComponent = browser.component
        remove(browserComponent)
    }
    
    fun addBrowser() {
        val browserComponent = browser.component
        add(browserComponent, BorderLayout.CENTER)
    }
}
