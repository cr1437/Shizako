package moe.shizuku.manager.utils

import java.io.File

/**
 * 模块/网页资源的路径安全校验（API 24 兼容）。
 *
 * 之前三处 `ensureInside` 用的是
 * `root.canonicalFile.toPath()` + `Path.startsWith(...)` —— `java.nio.file` 需要
 * **API 26**，而本项目 minSdk 是 24：Android 7.x 上第一次走模块安装 / 模块 WebUI
 * 就会抛 `NoSuchMethodError` 崩溃（lint NewApi）。
 *
 * 这里改成 canonicalPath 字符串比较：
 * - `canonicalPath` 会解析 `..` 与符号链接，逃逸不出去；
 * - 比较时补上分隔符，避免 `/data/x/foo` 误匹配 `/data/x/foobar`；
 * - 取不到 canonicalPath（IO 异常）时**判为不安全**（fail closed）。
 */
object FileSafety {

    /** [target] 是否位于 [root] 目录内（含 root 本身）。 */
    fun isInside(root: File, target: File): Boolean {
        val rootPath = canonicalPath(root) ?: return false
        val targetPath = canonicalPath(target) ?: return false
        if (targetPath == rootPath) return true
        val prefix = if (rootPath.endsWith(File.separator)) rootPath else rootPath + File.separator
        return targetPath.startsWith(prefix)
    }

    private fun canonicalPath(file: File): String? = runCatching { file.canonicalPath }.getOrNull()
}
