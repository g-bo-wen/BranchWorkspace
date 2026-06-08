package cn.gbk.branchworkspace.startup

import cn.gbk.branchworkspace.services.BranchWorkspaceService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class BranchWorkspaceStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<BranchWorkspaceService>().initialize()
    }
}
