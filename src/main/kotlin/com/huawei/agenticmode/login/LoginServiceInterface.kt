package com.huawei.agenticmode.login

import com.intellij.openapi.project.Project
import java.awt.Window

interface LoginServiceInterface {
    fun hasLogin(): Boolean
    fun login(project: Project)
    fun init()
    fun afterLogin(project: Project, window: Window)
}