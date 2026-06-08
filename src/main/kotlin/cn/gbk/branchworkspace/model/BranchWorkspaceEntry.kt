package cn.gbk.branchworkspace.model

import java.nio.file.Path
import java.time.Instant

data class BranchWorkspaceEntry(
    val branchName: String,
    val patchPath: Path,
    val sizeBytes: Long,
    val modifiedAt: Instant,
)
