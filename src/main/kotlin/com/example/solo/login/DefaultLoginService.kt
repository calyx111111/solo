package com.example.solo.login

import com.intellij.openapi.project.Project

object DefaultLoginService : LoginServiceInterface {
    override fun hasLogin(): Boolean = true
    override fun login(project: Project) {}
}