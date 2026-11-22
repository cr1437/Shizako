package moe.shizuku.manager.installer

import org.json.JSONObject
import java.util.UUID

/**
 * 安装相关的数据结构（规格 §三）+ 纯函数工具。
 *
 * 这个文件刻意不含任何 Android 依赖的逻辑：包格式判定、历史筛选、JSON 读写都在这里，
 * 方便 JVM 单测直接覆盖（见 `manager/src/test/.../installer/`）。真正碰
 * `PackageManager` / `Shizuku` 的部分在 [InstallPolicy]、[PackageArchive]、
 * [SessionInstaller]、[Installer] 里。
 */

/** 包格式。`APK1` 专指微信/QQ 那份被改名成 `xxx.apk.1` 的普通 APK。 */
enum class PackageFormat {
    APK, APK1, XAPK, APKS;

    companion object {
        /** 只看文件名（不打开文件）；ZIP 内容探测的结果优先于这里。 */
        fun fromFileName(name: String?): PackageFormat {
            val n = name?.trim()?.lowercase() ?: return APK
            return when {
                n.endsWith(".xapk") -> XAPK
                n.endsWith(".apks") -> APKS
                n.endsWith(".apk1") || n.endsWith(".apk.1") -> APK1
                else -> APK
            }
        }
    }
}

/** 队列任务状态（规格 §三）。 */
enum class TaskStatus {
    WAITING, INSTALLING, SUCCESS, FAILED, CANCELLED;

    val isFinished: Boolean
        get() = this == SUCCESS || this == FAILED || this == CANCELLED
}

/**
 * 安装来源（规格 §三的 UI / TRUSTED_APP / USER_APPROVED 三种 + 本实现补充的 SYSTEM）。
 *
 * 为什么要多一个 [SYSTEM]：`ACTION_VIEW` 的「用其他应用打开」是 system_server 的
 * Resolver 拉起我们的，`getLaunchedFromUid()` 拿到的是 **system uid（1000）**，
 * 不是用户点的那一下所在的 App。把它记成 UI 会让历史里的「来源」撒谎，记成
 * USER_APPROVED 又等于把一个系统行为当成用户对某个应用的放行，所以单列一档。
 */
enum class InstallSource {
    /** 本应用界面里手动选的（含设置页批量安装）。 */
    UI,

    /** 调用方在信任列表里且 UID + 签名双匹配。 */
    TRUSTED_APP,

    /** 外部应用请求，用户在弹出的确认框里点了「允许本次」。 */
    USER_APPROVED,

    /** 系统 Resolver（「用其他应用打开」）转进来的，用户已经在系统 UI 里选过本应用。 */
    SYSTEM,
}

/** 用户可见的失败分类（展示文案走资源字符串，不在这里写死中文）。 */
enum class InstallError {
    NO_SERVICE,
    NO_SPACE,
    UNKNOWN_ARCHIVE,
    MISSING_BASE,
    SIGNATURE_MISMATCH,
    IO,
    SESSION_CREATE_FAILED,
    SESSION_WRITE_FAILED,
    SESSION_COMMIT_FAILED,
    INSTALLER_REJECTED,
    CANCELLED,
    UNKNOWN,
}

/** 信任列表条目（规格 §三 `TrustedInstaller`）。 */
data class TrustedInstaller(
    val packageName: String,
    val uid: Int,
    val signatureHash: String,
    val note: String?,
    val addedAt: Long,
    val lastUsedAt: Long?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("package", packageName)
        .put("uid", uid)
        .put("sig", signatureHash)
        .put("note", note ?: JSONObject.NULL)
        .put("added", addedAt)
        .put("lastUsed", lastUsedAt ?: JSONObject.NULL)

    companion object {
        fun fromJson(obj: JSONObject): TrustedInstaller? {
            val pkg = obj.optString("package", "").ifBlank { return null }
            val sig = obj.optString("sig", "").ifBlank { return null }
            return TrustedInstaller(
                packageName = pkg,
                uid = obj.optInt("uid", -1),
                signatureHash = sig,
                note = if (obj.isNull("note")) null else obj.optString("note", null),
                addedAt = obj.optLong("added", 0L),
                lastUsedAt = if (obj.isNull("lastUsed")) null else obj.optLong("lastUsed", 0L),
            )
        }
    }
}

/**
 * 队列任务（规格 §三 `InstallTask`）。
 *
 * 比规格多两个字段：
 * - [sizeBytes]：F4 要求列表显示「名称/大小/格式/状态」，而 URI 的大小在任务入队时
 *   就能查到，不必每次绑行都去查一次 ContentProvider；
 * - [source] / [sourcePackage]：来源判定在**入队时**就已经做完（外部应用请求要走确认框），
 *   执行时再判一次等于让用户点两次，历史里写错来源也无从追查。
 */
data class InstallTask(
    val id: String,
    val fileUri: String,
    val fileName: String,
    val format: PackageFormat,
    val status: TaskStatus,
    val reason: String?,
    val retryCount: Int,
    val sizeBytes: Long = 0L,
    val source: InstallSource = InstallSource.UI,
    val sourcePackage: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("uri", fileUri)
        .put("name", fileName)
        .put("fmt", format.name)
        .put("status", status.name)
        .put("reason", reason ?: JSONObject.NULL)
        .put("retry", retryCount)
        .put("size", sizeBytes)
        .put("src", source.name)
        .put("srcPkg", sourcePackage ?: JSONObject.NULL)

    /** 执行安装时用的一次请求（格式以探测结果为准，这里只是入队时的初值）。 */
    fun toRequest(): InstallRequest = InstallRequest(
        fileUri = fileUri,
        fileName = fileName,
        format = format,
        source = source,
        sourcePackage = sourcePackage,
        sizeBytes = sizeBytes,
    )

    companion object {
        fun fromJson(obj: JSONObject): InstallTask? {
            val id = obj.optString("id", "").ifBlank { return null }
            val uri = obj.optString("uri", "").ifBlank { return null }
            return InstallTask(
                id = id,
                fileUri = uri,
                fileName = obj.optString("name", uri.substringAfterLast('/')),
                format = runCatching { PackageFormat.valueOf(obj.optString("fmt", "APK")) }
                    .getOrDefault(PackageFormat.APK),
                status = runCatching { TaskStatus.valueOf(obj.optString("status", "WAITING")) }
                    .getOrDefault(TaskStatus.WAITING),
                reason = if (obj.isNull("reason")) null else obj.optString("reason", null),
                retryCount = obj.optInt("retry", 0),
                sizeBytes = obj.optLong("size", 0L),
                source = runCatching { InstallSource.valueOf(obj.optString("src", "UI")) }
                    .getOrDefault(InstallSource.UI),
                sourcePackage = if (obj.isNull("srcPkg")) null else obj.optString("srcPkg", null),
            )
        }

        fun new(
            fileUri: String,
            fileName: String,
            format: PackageFormat,
            sizeBytes: Long = 0L,
            source: InstallSource = InstallSource.UI,
            sourcePackage: String? = null,
        ): InstallTask = InstallTask(
            id = UUID.randomUUID().toString(),
            fileUri = fileUri,
            fileName = fileName,
            format = format,
            status = TaskStatus.WAITING,
            reason = null,
            retryCount = 0,
            sizeBytes = sizeBytes,
            source = source,
            sourcePackage = sourcePackage,
        )
    }
}

/** 安装历史（规格 §三 `InstallHistoryEntry`，最多保留 [InstallHistoryStore.MAX_ENTRIES] 条）。 */
data class InstallHistoryEntry(
    val id: String,
    val fileName: String,
    val packageName: String?,
    val versionName: String?,
    val format: PackageFormat,
    val source: InstallSource,
    val sourcePackage: String?,
    val result: TaskStatus,
    val reason: String?,
    val durationMs: Long,
    val timestamp: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("file", fileName)
        .put("pkg", packageName ?: JSONObject.NULL)
        .put("ver", versionName ?: JSONObject.NULL)
        .put("fmt", format.name)
        .put("src", source.name)
        .put("srcPkg", sourcePackage ?: JSONObject.NULL)
        .put("result", result.name)
        .put("reason", reason ?: JSONObject.NULL)
        .put("dur", durationMs)
        .put("at", timestamp)

    companion object {
        fun fromJson(obj: JSONObject): InstallHistoryEntry? {
            val id = obj.optString("id", "").ifBlank { return null }
            return InstallHistoryEntry(
                id = id,
                fileName = obj.optString("file", ""),
                packageName = if (obj.isNull("pkg")) null else obj.optString("pkg", null),
                versionName = if (obj.isNull("ver")) null else obj.optString("ver", null),
                format = runCatching { PackageFormat.valueOf(obj.optString("fmt", "APK")) }
                    .getOrDefault(PackageFormat.APK),
                source = runCatching { InstallSource.valueOf(obj.optString("src", "UI")) }
                    .getOrDefault(InstallSource.UI),
                sourcePackage = if (obj.isNull("srcPkg")) null else obj.optString("srcPkg", null),
                result = runCatching { TaskStatus.valueOf(obj.optString("result", "FAILED")) }
                    .getOrDefault(TaskStatus.FAILED),
                reason = if (obj.isNull("reason")) null else obj.optString("reason", null),
                durationMs = obj.optLong("dur", 0L),
                timestamp = obj.optLong("at", 0L),
            )
        }
    }
}

/** 一次安装请求（入队前的最小信息集）。 */
data class InstallRequest(
    val fileUri: String,
    val fileName: String,
    val format: PackageFormat,
    val source: InstallSource,
    val sourcePackage: String?,
    val sizeBytes: Long = 0L,
)

/**
 * 历史筛选（规格 F5「按结果/来源/时间筛选」）。
 *
 * 纯函数：三个条件都是可选的，`null` 表示不过滤 —— 这样单测能直接断言组合行为，
 * UI 只需要把选中的筛选项填进来。
 */
fun filterInstallHistory(
    entries: List<InstallHistoryEntry>,
    result: TaskStatus? = null,
    source: InstallSource? = null,
    sinceMs: Long? = null,
): List<InstallHistoryEntry> = entries.filter { entry ->
    (result == null || entry.result == result) &&
        (source == null || entry.source == source) &&
        (sinceMs == null || entry.timestamp >= sinceMs)
}

/** 文件大小的短写法（列表 UI 用；不引入 android.text.format.Formatter 以便单测）。 */
fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return if (value >= 100) String.format("%.0f %s", value, units[unit])
    else String.format("%.1f %s", value, units[unit])
}
