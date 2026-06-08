package cn.gbk.branchworkspace.util

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object BranchNameCodec {
    private const val PATCH_EXTENSION = ".patch"

    fun toPatchFileName(branchName: String): String =
        URLEncoder.encode(branchName, StandardCharsets.UTF_8).replace("+", "%20") + PATCH_EXTENSION

    fun fromPatchFileName(fileName: String): String {
        val encoded = fileName.removeSuffix(PATCH_EXTENSION)
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8)
    }
}
