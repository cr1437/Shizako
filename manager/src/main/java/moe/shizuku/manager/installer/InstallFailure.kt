package moe.shizuku.manager.installer

/**
 * 安装链上可预期的失败（解压/校验/提权安装），带分类码，由 UI 层翻译成文案。
 *
 * 不用 Exception 的 message 直接给用户看：这些 message 有英文的 `pm` 输出、
 * 有 ZIP 条目名，混进中文界面很难读；分类码 → 资源字符串才是稳定的。
 */
class InstallFailure(
    val error: InstallError,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception("$error${if (detail.isNullOrBlank()) "" else ": $detail"}", cause)

/** 归档解析的异常 → 安装失败的分类。 */
fun ArchiveException.toInstallFailure(): InstallFailure = when (code) {
    ArchiveError.UNKNOWN_ARCHIVE -> InstallFailure(InstallError.UNKNOWN_ARCHIVE, detail)
    ArchiveError.NO_APK -> InstallFailure(InstallError.UNKNOWN_ARCHIVE, "no apk")
    ArchiveError.MISSING_BASE -> InstallFailure(InstallError.MISSING_BASE, detail)
    ArchiveError.MISSING_SPLIT_ENTRY -> InstallFailure(InstallError.UNKNOWN_ARCHIVE, detail)
    ArchiveError.DUPLICATE_SPLIT -> InstallFailure(InstallError.UNKNOWN_ARCHIVE, detail)
}
