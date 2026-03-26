package com.huawei.agenticmode

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "SoloModeState", storages = [Storage("soloMode.xml")]
)
class SoloModeState : PersistentStateComponent<SoloModeState> {

    var isSoloModeEnabled: Boolean = false
    var splitterProportion: Float = 0.3f
    var rightSplitterProportion: Float = 0.6f  // Editor 在左侧占比，Project 在右侧

    override fun getState(): SoloModeState {
        return this
    }

    override fun loadState(state: SoloModeState) {
        XmlSerializerUtil.copyBean(state, this)
    }

    companion object {
        fun getInstance(project: Project): SoloModeState {
            return project.getService(SoloModeState::class.java)
        }
    }
}
