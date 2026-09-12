package moe.shizuku.manager.module

import java.io.File

/**
 * ADB Modules（照搬 Shevery 的设计）里的一个已安装模块。
 *
 * 目录结构（装在 App 私有目录 `files/adb_modules/<id>`）：
 * ```
 * module.prop        元数据（id / name / version / versionCode / author / description
 *                    / banner / webui / action / usesShellBridge ...）
 * action.sh          用户手动触发的动作脚本
 * service.sh         受策略控制的后台脚本
 * banner.png         卡片横幅（也支持 jpg/webp，或在 module.prop 里用 banner= 指定）
 * webui/index.html   本地 WebUI（可在 module.prop 里用 webui= 指定目录）
 * logs/              最近一次运行的日志（action-last.log / service-last.log）
 * disable            存在即表示"已停用"（和 Magisk 模块一个约定）
 * ```
 */
data class AdbModule(
    val id: String,
    val name: String,
    val version: String?,
    val versionCode: Long?,
    val author: String?,
    val description: String?,
    val directory: File,
    val banner: File?,
    val webRoot: File?,
    val declaresShellBridge: Boolean,
    val actionScript: File?,
    val serviceScript: File?,
    val logsDir: File,
    val sizeBytes: Long,
    val enabled: Boolean,
    val url: String? = null,
) {
    val hasWebUi: Boolean get() = webRoot?.resolve("index.html")?.isFile == true

    val hasAction: Boolean get() = actionScript?.isFile == true

    val hasService: Boolean get() = serviceScript?.isFile == true

    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            return if (kb < 1024.0) "%.1f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
        }

    val lastActionLog: File get() = logsDir.resolve("action-last.log")

    val lastServiceLog: File get() = logsDir.resolve("service-last.log")
}

/** 脚本执行结果。 */
data class ModuleActionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trim())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(stderr.trim())
            }
            if (isBlank()) append("（没有输出）")
        }
}
