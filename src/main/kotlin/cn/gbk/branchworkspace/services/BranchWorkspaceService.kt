package cn.gbk.branchworkspace.services

import cn.gbk.branchworkspace.model.BranchWorkspaceEntry
import cn.gbk.branchworkspace.settings.BranchWorkspaceSettings
import cn.gbk.branchworkspace.util.BranchNameCodec
import com.intellij.dvcs.repo.Repository
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diff.impl.patch.ApplyPatchStatus
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.diff.impl.patch.formove.PatchApplier
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.VcsNotifier
import com.intellij.openapi.vcs.changes.BinaryContentRevision
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.ContentRevision
import com.intellij.openapi.vcs.changes.patch.PatchWriter
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.messages.Topic
import com.intellij.vcsUtil.VcsUtil
import git4idea.commands.Git
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import git4idea.reset.GitResetMode
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

@Service(Service.Level.PROJECT)
class BranchWorkspaceService(private val project: Project) : Disposable {

    private val initialized = AtomicBoolean(false)
    private val pendingCleanup = AtomicReference<WorkspaceCapture?>()
    private val operationLock = Any()

    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return

        project.messageBus.connect(this).subscribe(
            BranchChangeListener.VCS_BRANCH_CHANGED,
            object : BranchChangeListener {
                override fun branchWillChange(branchName: String) {
                    val state = BranchWorkspaceSettings.getInstance(project).state
                    if (!state.enabled) return
                    val capture = captureWorkspace(
                        branchName = branchName,
                        includeUntracked = state.includeUntrackedFiles,
                    )

                    runBackground("Saving Branch Workspace for $branchName") {
                        saveCapturedWorkspace(
                            capture = capture,
                            clearAfterSave = state.clearWorkspaceBeforeCheckout,
                            notifyNoChanges = state.notifyWhenNoChanges,
                            automatic = true,
                        )
                    }
                }

                override fun branchHasChanged(branchName: String) {
                    val state = BranchWorkspaceSettings.getInstance(project).state
                    if (!state.enabled || !state.restoreWorkspaceAfterCheckout) return

                    runBackground("Restoring Branch Workspace") {
                        val targetBranch = currentBranchName() ?: branchName
                        cleanupPendingWorkspace(automatic = true)
                        restoreWorkspace(branchName = targetBranch, deletePatchOnSuccess = true, automatic = true)
                    }
                }
            },
        )
    }

    fun currentBranchName(): String? {
        return edtCompute {
            currentBranchNameOnEdt()
        }
    }

    private fun currentBranchNameOnEdt(): String? {
        val names = repositories()
            .mapNotNull { it.currentBranchName }
            .distinct()

        return when (names.size) {
            0 -> null
            1 -> names.single()
            else -> names.joinToString("+")
        }
    }

    fun saveCurrentWorkspace(clearAfterSave: Boolean = false): OperationResult {
        val branchName = currentBranchName()
            ?: return OperationResult(false, "No Git branch is active for this project.")
        val includeUntracked = edtCompute {
            BranchWorkspaceSettings.getInstance(project).state.includeUntrackedFiles
        }
        val capture = captureWorkspace(
            branchName = branchName,
            includeUntracked = includeUntracked,
        )
        return saveCapturedWorkspace(capture, clearAfterSave, notifyNoChanges = true, automatic = false)
    }

    fun restoreCurrentWorkspace(): OperationResult {
        val branchName = currentBranchName()
            ?: return OperationResult(false, "No Git branch is active for this project.")
        return restoreWorkspace(branchName, deletePatchOnSuccess = true, automatic = false)
    }

    fun restoreWorkspace(branchName: String, deletePatchOnSuccess: Boolean = true, automatic: Boolean = false): OperationResult {
        synchronized(operationLock) {
            val baseDir = projectBasePath()
                ?: return OperationResult(false, "Project base path is not available.")
            val baseVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(baseDir)
                ?: return OperationResult(false, "Project base directory cannot be resolved.")
            val patchPath = patchPath(branchName)

            if (!patchPath.exists()) {
                return OperationResult(true, "No saved workspace for $branchName.")
            }

            val currentCapture = captureWorkspace(
                branchName = branchName,
                includeUntracked = true,
                saveDocuments = false,
            )
            if (!currentCapture.result.success) {
                notifyError("Branch Workspace restore skipped", currentCapture.result.message, automatic)
                return currentCapture.result
            }
            if (!currentCapture.snapshot.isEmpty()) {
                val message = "Current workspace has local changes. Save or clean them before restoring $branchName."
                notifyError("Branch Workspace restore skipped", message, automatic)
                return OperationResult(false, message)
            }

            return try {
                val reader = PatchReader(patchPath)
                reader.parseAllPatches()

                val status = PatchApplier(
                    project,
                    baseVirtualFile,
                    reader.allPatches,
                    edtCompute { ChangeListManager.getInstance(project).defaultChangeList },
                    CommitContext(),
                ).execute(false, false)

                refreshProjectBase(baseDir)

                if (status == ApplyPatchStatus.SUCCESS || status == ApplyPatchStatus.ALREADY_APPLIED) {
                    if (deletePatchOnSuccess) {
                        Files.deleteIfExists(patchPath)
                    }
                    publishWorkspacesChanged()
                    val message = "Restored workspace for $branchName."
                    notifyInfo("Branch Workspace restored", message, automatic)
                    OperationResult(true, message)
                } else {
                    val message = "Patch apply status: $status. The patch was kept at ${patchPath.toAbsolutePath()}."
                    notifyError("Branch Workspace restore failed", message, automatic)
                    OperationResult(false, message)
                }
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                notifyError("Branch Workspace restore failed", message, automatic)
                OperationResult(false, message)
            }
        }
    }

    fun deleteWorkspace(branchName: String): OperationResult {
        synchronized(operationLock) {
            val patchPath = patchPath(branchName)
            return try {
                val deleted = Files.deleteIfExists(patchPath)
                publishWorkspacesChanged()
                OperationResult(true, if (deleted) "Deleted workspace for $branchName." else "No saved workspace for $branchName.")
            } catch (t: Throwable) {
                OperationResult(false, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun listWorkspaces(): List<BranchWorkspaceEntry> {
        val dir = storageDir()
        if (!Files.isDirectory(dir)) return emptyList()

        return Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && it.name.endsWith(".patch") }
                .map {
                    BranchWorkspaceEntry(
                        branchName = BranchNameCodec.fromPatchFileName(it.name),
                        patchPath = it,
                        sizeBytes = it.fileSize(),
                        modifiedAt = Instant.ofEpochMilli(it.getLastModifiedTime().toMillis()),
                    )
                }
                .sorted(Comparator.comparing(BranchWorkspaceEntry::branchName))
                .toList()
        }
    }

    fun storageDir(): Path = projectBasePath()
        ?.resolve(".idea")
        ?.resolve("branch-workspace")
        ?: Path.of(".idea", "branch-workspace")

    fun patchPath(branchName: String): Path = storageDir().resolve(BranchNameCodec.toPatchFileName(branchName))

    private fun saveCapturedWorkspace(
        capture: WorkspaceCapture,
        clearAfterSave: Boolean,
        notifyNoChanges: Boolean,
        automatic: Boolean,
    ): OperationResult {
        synchronized(operationLock) {
            if (!capture.result.success) {
                notifyError("Branch Workspace save skipped", capture.result.message, automatic)
                return capture.result
            }

            return try {
                val patchPath = patchPath(capture.branchName)

                if (capture.snapshot.isEmpty()) {
                    Files.deleteIfExists(patchPath)
                    publishWorkspacesChanged()
                    val message = "No local changes for ${capture.branchName}."
                    if (notifyNoChanges) notifyInfo("Branch Workspace", message, automatic)
                    return OperationResult(true, message)
                }

                Files.createDirectories(patchPath.parent)
                val patches = IdeaTextPatchBuilder.buildPatch(project, capture.snapshot.changes, capture.baseDir, false, false)
                val temporaryPatch = patchPath.resolveSibling("${patchPath.name}.tmp")
                Files.deleteIfExists(temporaryPatch)
                PatchWriter.writePatches(project, temporaryPatch, capture.baseDir, patches, CommitContext())
                Files.move(temporaryPatch, patchPath, StandardCopyOption.REPLACE_EXISTING)

                if (clearAfterSave) {
                    if (currentBranchName() == capture.branchName) {
                        clearWorkspace(capture)
                    } else {
                        pendingCleanup.set(capture)
                        notifyInfo(
                            "Branch Workspace cleanup delayed",
                            "Branch changed before cleanup finished; cleanup will run after checkout.",
                            automatic,
                        )
                    }
                }

                refreshProjectBase(capture.baseDir)
                publishWorkspacesChanged()
                val message = "Saved workspace for ${capture.branchName} to ${patchPath.toAbsolutePath()}."
                notifyInfo("Branch Workspace saved", message, automatic)
                OperationResult(true, message)
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                notifyError("Branch Workspace save failed", message, automatic)
                OperationResult(false, message)
            }
        }
    }

    private fun cleanupPendingWorkspace(automatic: Boolean): OperationResult {
        synchronized(operationLock) {
            val capture = pendingCleanup.getAndSet(null)
                ?: return OperationResult(true, "No pending workspace cleanup.")
            val currentBranch = currentBranchName()
            if (currentBranch == null || currentBranch == capture.branchName) {
                return OperationResult(true, "Pending cleanup ignored because the branch did not change.")
            }

            return try {
                clearWorkspace(capture)
                refreshProjectBase(capture.baseDir)
                val message = "Cleaned workspace carried over from ${capture.branchName}."
                notifyInfo("Branch Workspace cleanup complete", message, automatic)
                OperationResult(true, message)
            } catch (t: Throwable) {
                val message = t.message ?: t.javaClass.simpleName
                pendingCleanup.compareAndSet(null, capture)
                notifyError("Branch Workspace cleanup failed", message, automatic)
                OperationResult(false, message)
            }
        }
    }

    private fun captureWorkspace(
        branchName: String,
        includeUntracked: Boolean,
        saveDocuments: Boolean = true,
    ): WorkspaceCapture {
        return edtCompute {
            try {
                if (saveDocuments) {
                    FileDocumentManager.getInstance().saveAllDocuments()
                }

                val baseDir = projectBasePath()
                    ?: return@edtCompute WorkspaceCapture.failed(branchName, "Project base path is not available.")
                val repositories = repositories()
                val unsafeRepository = repositories.firstOrNull { it.state != Repository.State.NORMAL }
                if (unsafeRepository != null) {
                    return@edtCompute WorkspaceCapture.failed(
                        branchName,
                        "Repository ${unsafeRepository.presentableUrl} is ${unsafeRepository.state}; workspace was not saved.",
                    )
                }
                val roots = repositories.map { Path.of(it.root.path).normalized() }
                WorkspaceCapture(
                    branchName = branchName,
                    baseDir = baseDir,
                    repositories = repositories,
                    gitRootPaths = roots,
                    snapshot = collectWorkspaceSnapshot(includeUntracked, roots),
                    result = OperationResult(true, "Workspace captured for $branchName."),
                )
            } catch (t: Throwable) {
                WorkspaceCapture.failed(branchName, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun collectWorkspaceSnapshot(includeUntracked: Boolean, roots: List<Path>): WorkspaceSnapshot {
        val changeListManager = ChangeListManager.getInstance(project)
        val trackedChanges = changeListManager.getAllChanges()
            .filter { change ->
                val path = changePath(change)
                path != null && path.isUnderAny(roots) && !isStorageOrParent(path)
            }
            .map(::snapshotChange)

        val untrackedPaths = if (includeUntracked) {
            changeListManager.unversionedFilesPaths
                .map { it.ioFile.toPath().normalized() }
                .filter { it.isUnderAny(roots) && !isStorageOrParent(it) }
                .distinct()
        } else {
            emptyList()
        }

        val expandedUntracked = untrackedPaths
            .map { it to expandUntrackedPath(it) }
            .filter { (_, files) -> files.isNotEmpty() }

        val untrackedChanges = expandedUntracked
            .flatMap { (_, files) -> files }
            .map { filePath ->
                Change(null, snapshotRevision(filePath), FileStatus.ADDED)
            }

        return WorkspaceSnapshot(trackedChanges + untrackedChanges, expandedUntracked.map { (path, _) -> path })
    }

    private fun snapshotChange(change: Change): Change =
        Change(
            change.beforeRevision,
            change.afterRevision?.let { snapshotRevision(it) },
            change.fileStatus,
        )

    private fun snapshotRevision(revision: ContentRevision): ContentRevision =
        snapshotRevision(
            file = revision.file,
            revisionNumber = revision.revisionNumber,
            bytes = when (revision) {
                is BinaryContentRevision -> revision.binaryContent ?: ByteArray(0)
                is ByteBackedContentRevision -> revision.contentAsBytes ?: ByteArray(0)
                else -> revision.content?.toByteArray(revision.file.charset) ?: ByteArray(0)
            },
            binary = revision is BinaryContentRevision || revision.file.fileType.isBinary,
        )

    private fun snapshotRevision(file: FilePath): ContentRevision =
        snapshotRevision(
            file = file,
            revisionNumber = VcsRevisionNumber.NULL,
            bytes = Files.readAllBytes(file.ioFile.toPath()),
            binary = file.fileType.isBinary,
        )

    private fun snapshotRevision(
        file: FilePath,
        revisionNumber: VcsRevisionNumber,
        bytes: ByteArray,
        binary: Boolean,
    ): ContentRevision =
        if (binary) {
            SnapshotBinaryContentRevision(file, revisionNumber, bytes)
        } else {
            SnapshotTextContentRevision(file, revisionNumber, bytes)
        }

    private fun expandUntrackedPath(path: Path) = when {
        Files.isDirectory(path) -> Files.walk(path).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { !isStorageOrParent(it.normalized()) }
                .map { VcsUtil.getFilePath(it, false) }
                .toList()
        }

        Files.isRegularFile(path) -> listOf(VcsUtil.getFilePath(path, false))
        else -> emptyList()
    }

    private fun clearWorkspace(capture: WorkspaceCapture) {
        if (capture.snapshot.changes.isNotEmpty()) {
            val git = Git.getInstance()
            capture.repositories.forEach { repository ->
                val result = git.reset(repository, GitResetMode.HARD, "HEAD")
                if (!result.success()) {
                    throw IllegalStateException(result.errorOutputAsJoinedString)
                }
            }
        }

        capture.snapshot.untrackedPaths.forEach { path ->
            if (!isStorageOrParent(path) && path.isUnderAny(capture.gitRootPaths)) {
                deleteRecursively(path)
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return

        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun repositories() = GitRepositoryManager.getInstance(project).repositories

    private fun <T> edtCompute(action: () -> T): T {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            return action()
        }

        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        application.invokeAndWait {
            try {
                result.set(action())
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private fun runBackground(title: String, action: () -> Unit) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, title, false) {
                override fun run(indicator: ProgressIndicator) {
                    action()
                }
            },
        )
    }

    private fun changePath(change: Change): Path? =
        (change.afterRevision?.file ?: change.beforeRevision?.file)?.ioFile?.toPath()?.normalized()

    private fun projectBasePath(): Path? = project.basePath?.let { Path.of(it).normalized() }

    private fun isStorageOrParent(path: Path): Boolean {
        val storage = storageDir().normalized()
        val normalizedPath = path.normalized()
        return normalizedPath.startsWith(storage) || storage.startsWith(normalizedPath)
    }

    private fun refreshProjectBase(baseDir: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(baseDir)?.refresh(false, true)
    }

    private fun publishWorkspacesChanged() {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) {
                project.messageBus.syncPublisher(TOPIC).workspacesChanged()
            }
        }
    }

    private fun notifyInfo(title: String, message: String, automatic: Boolean) {
        if (automatic) {
            VcsNotifier.getInstance(project).notifyMinorInfo("branch.workspace", false, title, message)
        } else {
            VcsNotifier.getInstance(project).notifyInfo("branch.workspace", title, message)
        }
    }

    private fun notifyError(title: String, message: String, automatic: Boolean) {
        if (automatic) {
            VcsNotifier.getInstance(project).notifyMinorWarning("branch.workspace", title, message)
        } else {
            VcsNotifier.getInstance(project).notifyError("branch.workspace", title, message)
        }
    }

    override fun dispose() = Unit

    data class OperationResult(val success: Boolean, val message: String)

    private data class WorkspaceSnapshot(
        val changes: List<Change>,
        val untrackedPaths: List<Path>,
    ) {
        fun isEmpty(): Boolean = changes.isEmpty() && untrackedPaths.isEmpty()
    }

    private data class WorkspaceCapture(
        val branchName: String,
        val baseDir: Path,
        val repositories: List<GitRepository>,
        val gitRootPaths: List<Path>,
        val snapshot: WorkspaceSnapshot,
        val result: OperationResult,
    ) {
        companion object {
            fun failed(branchName: String, message: String) = WorkspaceCapture(
                branchName = branchName,
                baseDir = Path.of("."),
                repositories = emptyList(),
                gitRootPaths = emptyList(),
                snapshot = WorkspaceSnapshot(emptyList(), emptyList()),
                result = OperationResult(false, message),
            )
        }
    }

    interface Listener {
        fun workspacesChanged()
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic.create("Branch Workspace changes", Listener::class.java)
    }
}

private fun Path.normalized(): Path = toAbsolutePath().normalize()

private fun Path.isUnderAny(roots: List<Path>): Boolean = roots.any { startsWith(it) }

private class SnapshotTextContentRevision(
    private val file: FilePath,
    private val revisionNumber: VcsRevisionNumber,
    private val bytes: ByteArray,
) : ContentRevision, ByteBackedContentRevision {
    override fun getContent(): String = String(bytes, file.charset)

    override fun getContentAsBytes(): ByteArray = bytes

    override fun getFile(): FilePath = file

    override fun getRevisionNumber(): VcsRevisionNumber = revisionNumber
}

private class SnapshotBinaryContentRevision(
    private val file: FilePath,
    private val revisionNumber: VcsRevisionNumber,
    private val bytes: ByteArray,
) : BinaryContentRevision {
    override fun getBinaryContent(): ByteArray = bytes

    override fun getContent(): String? = null

    override fun getFile(): FilePath = file

    override fun getRevisionNumber(): VcsRevisionNumber = revisionNumber
}
