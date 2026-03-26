package com.huawei.agenticmode.login

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

@Service(Service.Level.APP)
class LoginManager {
    companion object {
        @Volatile
        private var INSTANCE: LoginManager? = null

        @JvmStatic
        fun getInstance(): LoginManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LoginManager().also { INSTANCE = it }
            }
        }
    }

    private val service: LoginServiceInterface by lazy { loadService() }

    private fun loadService(): LoginServiceInterface {
        return try {
            val className = "${LoginManager::class.java.packageName}.HuaweiLoginService"
            val clazz = Class.forName(className)
            val instance = clazz.getField("INSTANCE").get(null) as LoginServiceInterface
            instance as? LoginServiceInterface ?: DefaultLoginService
        } catch (e: Throwable) {
            DefaultLoginService
        }
    }

    fun hasLogin(): Boolean = service.hasLogin()

    fun login(project: Project) = service.login(project)
}