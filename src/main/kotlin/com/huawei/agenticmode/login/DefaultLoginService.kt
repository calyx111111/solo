package com.huawei.agenticmode.login

import com.intellij.openapi.project.Project
import java.awt.Window

object DefaultLoginService : LoginServiceInterface {
    override fun hasLogin(): Boolean = true
    override fun login(project: Project) {}
    override fun init() {}
    override fun afterLogin(project: Project, window: Window) {}
}