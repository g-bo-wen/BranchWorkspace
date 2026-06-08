package cn.gbk.branchworkspace.toolWindow

import cn.gbk.branchworkspace.model.BranchWorkspaceEntry
import cn.gbk.branchworkspace.services.BranchWorkspaceService
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Desktop
import java.awt.FlowLayout
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

class BranchWorkspaceToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val disposable = Disposer.newDisposable("Branch Workspace ToolWindow")
        val panel = BranchWorkspacePanel(project, disposable)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        content.setDisposer(disposable)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

private class BranchWorkspacePanel(
    private val project: Project,
    disposable: Disposable,
) {
    private val service = project.service<BranchWorkspaceService>()
    private val statusLabel = JBLabel()
    private val currentBranchLabel = JBLabel()
    private val tableModel = object : DefaultTableModel(arrayOf("Branch", "Patch", "Size", "Modified"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JBTable(tableModel)
    private var rows: List<BranchWorkspaceEntry> = emptyList()

    val component: JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(createToolbar(), BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(createStatusPanel(), BorderLayout.SOUTH)
    }

    init {
        table.emptyText.text = "No saved branch workspaces"
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        project.messageBus.connect(disposable).subscribe(
            BranchWorkspaceService.TOPIC,
            object : BranchWorkspaceService.Listener {
                override fun workspacesChanged() {
                    reload()
                }
            },
        )
        reload()
    }

    private fun createToolbar(): JPanel {
        val refreshButton = JButton("Refresh").apply {
            addActionListener { reload() }
        }
        val saveButton = JButton("Save Current").apply {
            addActionListener {
                runBackground("Saving Branch Workspace") {
                    service.saveCurrentWorkspace(clearAfterSave = false)
                }
            }
        }
        val restoreCurrentButton = JButton("Restore Current").apply {
            addActionListener {
                runBackground("Restoring Branch Workspace") {
                    service.restoreCurrentWorkspace()
                }
            }
        }
        val restoreSelectedButton = JButton("Restore Selected").apply {
            addActionListener {
                val branch = selectedBranch()
                if (branch == null) {
                    setStatus("Select a workspace to restore.", false)
                } else {
                    runBackground("Restoring Branch Workspace") {
                        service.restoreWorkspace(branch, deletePatchOnSuccess = true, automatic = false)
                    }
                }
            }
        }
        val deleteButton = JButton("Delete").apply {
            addActionListener {
                val branch = selectedBranch()
                if (branch == null) {
                    setStatus("Select a workspace to delete.", false)
                } else {
                    runBackground("Deleting Branch Workspace") {
                        service.deleteWorkspace(branch)
                    }
                }
            }
        }
        val openDirectoryButton = JButton("Open Directory").apply {
            addActionListener {
                val directory = service.storageDir().toFile()
                if (directory.exists()) {
                    runCatching { Desktop.getDesktop().open(directory) }
                        .onFailure { setStatus(it.message ?: it.javaClass.simpleName, false) }
                } else {
                    setStatus("Workspace directory does not exist yet.", false)
                }
            }
        }

        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(refreshButton)
            add(saveButton)
            add(restoreCurrentButton)
            add(restoreSelectedButton)
            add(deleteButton)
            add(openDirectoryButton)
        }
    }

    private fun createStatusPanel(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.emptyTop(8)
        add(currentBranchLabel, BorderLayout.WEST)
        add(statusLabel, BorderLayout.EAST)
    }

    private fun selectedBranch(): String? {
        val row = table.selectedRow
        if (row < 0) return null
        val modelRow = table.convertRowIndexToModel(row)
        return rows.getOrNull(modelRow)?.branchName
    }

    private fun reload() {
        rows = service.listWorkspaces()
        tableModel.rowCount = 0
        rows.forEach { entry ->
            tableModel.addRow(
                arrayOf(
                    entry.branchName,
                    entry.patchPath.fileName.toString(),
                    FileSizeFormatter.format(entry.sizeBytes),
                    MODIFIED_FORMATTER.format(entry.modifiedAt),
                ),
            )
        }
        currentBranchLabel.text = "Current branch: ${service.currentBranchName() ?: "No Git branch"}"
        setStatus("${rows.size} saved workspace(s)", true)
    }

    private fun runBackground(
        title: String,
        action: () -> BranchWorkspaceService.OperationResult,
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, title, false) {
                private lateinit var result: BranchWorkspaceService.OperationResult

                override fun run(indicator: ProgressIndicator) {
                    result = runCatching { action() }
                        .getOrElse { BranchWorkspaceService.OperationResult(false, it.message ?: it.javaClass.simpleName) }
                }

                override fun onFinished() {
                    ApplicationManager.getApplication().invokeLater {
                        reload()
                        setStatus(result.message, result.success)
                    }
                }
            },
        )
    }

    private fun setStatus(message: String, success: Boolean) {
        statusLabel.text = message
        statusLabel.foreground = if (success) JBColor.foreground() else JBColor.RED
    }

    companion object {
        private val MODIFIED_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
    }
}

private object FileSizeFormatter {
    fun format(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}
