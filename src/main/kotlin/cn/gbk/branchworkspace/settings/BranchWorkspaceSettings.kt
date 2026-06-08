package cn.gbk.branchworkspace.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.PROJECT)
@State(
    name = "BranchWorkspaceSettings",
    storages = [Storage("branchWorkspace.xml")],
)
class BranchWorkspaceSettings : PersistentStateComponent<BranchWorkspaceSettings.State> {

    data class State(
        var enabled: Boolean = true,
        var includeUntrackedFiles: Boolean = true,
        var clearWorkspaceBeforeCheckout: Boolean = true,
        var restoreWorkspaceAfterCheckout: Boolean = true,
        var notifyWhenNoChanges: Boolean = false,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    companion object {
        fun getInstance(project: Project): BranchWorkspaceSettings = project.service()
    }
}
