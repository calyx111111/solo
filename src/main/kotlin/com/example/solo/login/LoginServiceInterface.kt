package com.example.solo.login

import com.intellij.openapi.project.Project

interface LoginServiceInterface {
    fun hasLogin(): Boolean
    fun login(project: Project)
}