package com.huawei.agenticmode.login

import com.intellij.openapi.project.Project

interface LoginServiceInterface {
    fun hasLogin(): Boolean
    fun login(project: Project)
}