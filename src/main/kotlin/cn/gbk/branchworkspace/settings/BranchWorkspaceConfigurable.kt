package cn.gbk.branchworkspace.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class BranchWorkspaceConfigurable(private val project: Project) : SearchableConfigurable {

    private val settings: BranchWorkspaceSettings
        get() = BranchWorkspaceSettings.getInstance(project)

    private var enabled = JCheckBox("Enable Branch Workspace")
    private var includeUntrackedFiles = JCheckBox("Include untracked files in branch patches")
    private var clearWorkspaceBeforeCheckout = JCheckBox("Clear saved workspace before checkout")
    private var restoreWorkspaceAfterCheckout = JCheckBox("Restore target branch workspace after checkout")
    private var notifyWhenNoChanges = JCheckBox("Notify when the leaving branch has no local changes")
    private var panel: JPanel? = null

    override fun getId(): String = "branch.workspace.settings"

    override fun getDisplayName(): String = "Branch Workspace"

    override fun createComponent(): JComponent {
        panel = FormBuilder.createFormBuilder()
            .addComponent(enabled)
            .addComponent(includeUntrackedFiles)
            .addComponent(clearWorkspaceBeforeCheckout)
            .addComponent(restoreWorkspaceAfterCheckout)
            .addComponent(notifyWhenNoChanges)
            .addComponent(JBLabel("Patch storage: .idea/branch-workspace"))
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return enabled.isSelected != state.enabled ||
            includeUntrackedFiles.isSelected != state.includeUntrackedFiles ||
            clearWorkspaceBeforeCheckout.isSelected != state.clearWorkspaceBeforeCheckout ||
            restoreWorkspaceAfterCheckout.isSelected != state.restoreWorkspaceAfterCheckout ||
            notifyWhenNoChanges.isSelected != state.notifyWhenNoChanges
    }

    override fun apply() {
        settings.state.enabled = enabled.isSelected
        settings.state.includeUntrackedFiles = includeUntrackedFiles.isSelected
        settings.state.clearWorkspaceBeforeCheckout = clearWorkspaceBeforeCheckout.isSelected
        settings.state.restoreWorkspaceAfterCheckout = restoreWorkspaceAfterCheckout.isSelected
        settings.state.notifyWhenNoChanges = notifyWhenNoChanges.isSelected
    }

    override fun reset() {
        val state = settings.state
        enabled.isSelected = state.enabled
        includeUntrackedFiles.isSelected = state.includeUntrackedFiles
        clearWorkspaceBeforeCheckout.isSelected = state.clearWorkspaceBeforeCheckout
        restoreWorkspaceAfterCheckout.isSelected = state.restoreWorkspaceAfterCheckout
        notifyWhenNoChanges.isSelected = state.notifyWhenNoChanges
    }

    override fun disposeUIResources() {
        panel = null
    }
}
