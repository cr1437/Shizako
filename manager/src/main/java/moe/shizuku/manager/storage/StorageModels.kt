package moe.shizuku.manager.storage

import moe.shizuku.manager.installer.MiniJson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 备份/恢复的数据结构（规格 §三）+ 纯函数工具。
 *
 * 这个文件刻意**不依赖 Android 类**（Uri / PackageManager 都在 [BackupEngine] 里）：
 * 元数据 JSON 读写、文件名规则、错误码解析都能被 JVM 单测直接覆盖，
 * 不用起模拟器也能验证「meta 写进去再读出来是否一致」。
 *
 * 也不用 `org.json`：本仓库的 JVM 单测跑在宿主机上，Android 的 org.json 在单测里
 * 是"每个方法都抛异常"的 stub（与 installer 的 MiniJson 注释同一个理由）。
 * meta 是固定字段的小对象，手写序列化 + MiniJson 解析足够，还能被单测完整覆盖。
 */

/** 备份格式版本：meta.json 里带上，将来改结构时旧客户端能明确拒绝。 */
const val BACKUP_FORMAT_VERSION = 1

/** 备份/恢复任务状态。CANCELLED 是规格 TaskStatus 之外补的（取消要能显示出来）。 */
enum class BackupStatus {
    WAITING, RUNNING, SUCCESS, FAILED, CANCELLED;

    val isFinished: Boolean
        get() = this == SUCCESS || this == FAILED || this == CANCELLED
}

/**
 * 备份元数据（规格 §三 `BackupMeta`）。
 *
 * 规格要求的 7 个字段之外还记了文件名与字节数：
 * - 文件名：用户可能把 tar 改名/多个版本放同一目录，恢复时按 meta 里记的实际名字找，
 *   比「自己按包名拼」可靠；
 * - 字节数：恢复前给用户看「这个备份多大」，也用来判断备份是否为空。
 */
data class BackupMeta(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val backupTime: Long,
    val androidVersion: Int,
    val includesObb: Boolean,
    val includesApk: Boolean,
    val label: String = "",
    val externalFile: String? = null,
    val obbFile: String? = null,
    val apkFile: String? = null,
    val externalBytes: Long = 0L,
    val obbBytes: Long = 0L,
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
) {
    /** 可读的 JSON 文本（2 空格缩进，用户打开 meta.json 能直接看懂）。 */
    fun toJsonText(): String {
        val fields = linkedMapOf<String, Any?>(
            "formatVersion" to formatVersion,
            "packageName" to packageName,
            "versionName" to versionName,
            "versionCode" to versionCode,
            "backupTime" to backupTime,
            "androidVersion" to androidVersion,
            "includesObb" to includesObb,
            "includesApk" to includesApk,
            "label" to label,
            "externalFile" to externalFile,
            "obbFile" to obbFile,
            "apkFile" to apkFile,
            "externalBytes" to externalBytes,
            "obbBytes" to obbBytes,
        )
        return fields.entries.joinToString(",\n", prefix = "{\n", postfix = "\n}\n") { (key, value) ->
            "  \"$key\": ${jsonValue(value)}"
        }
    }

    companion object {
        /**
         * 解析 meta.json。返回 null 表示不是本功能的备份（字段缺失/格式不认识），
         * 调用方据此跳过而不是抛异常 —— 用户选的目录里可能混着别的东西。
         */
        fun fromJson(text: String): BackupMeta? {
            val map = runCatching { MiniJson.asObject(MiniJson.parse(text)) }.getOrNull() ?: return null

            val packageName = (MiniJson.string(map, "packageName")
                ?: MiniJson.string(map, "package"))?.trim().orEmpty()
            if (packageName.isEmpty()) return null

            val version = (map["formatVersion"] as? Number)?.toInt() ?: BACKUP_FORMAT_VERSION
            if (version > BACKUP_FORMAT_VERSION) return null

            return BackupMeta(
                packageName = packageName,
                versionName = MiniJson.string(map, "versionName") ?: "",
                versionCode = MiniJson.long(map, "versionCode") ?: 0L,
                backupTime = MiniJson.long(map, "backupTime") ?: 0L,
                androidVersion = (map["androidVersion"] as? Number)?.toInt() ?: 0,
                includesObb = map["includesObb"] as? Boolean ?: false,
                includesApk = map["includesApk"] as? Boolean ?: false,
                label = MiniJson.string(map, "label") ?: "",
                externalFile = MiniJson.string(map, "externalFile"),
                obbFile = MiniJson.string(map, "obbFile"),
                apkFile = MiniJson.string(map, "apkFile"),
                externalBytes = MiniJson.long(map, "externalBytes") ?: 0L,
                obbBytes = MiniJson.long(map, "obbBytes") ?: 0L,
                formatVersion = version,
            )
        }
    }
}

/** 单值 JSON 编码（meta 只有标量字段，不需要通用序列化器）。 */
private fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Number -> value.toString()
    else -> "\"" + escapeJsonString(value.toString()) + "\""
}

private fun escapeJsonString(text: String): String = buildString(text.length + 8) {
    for (c in text) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }
}


/** 一个待备份的应用（UI 列表 + 队列输入）。 */
data class BackupTarget(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val debuggable: Boolean,
)

/** 队列任务（规格 §三 `BackupTask`）。 */
data class BackupTask(
    val id: String,
    val packageName: String,
    val label: String,
    val versionName: String,
    val status: BackupStatus,
    val reason: String? = null,
    val outputUri: String? = null,
    val bytes: Long = 0L,
    val includesObb: Boolean = false,
) {
    companion object {
        fun new(target: BackupTarget): BackupTask = BackupTask(
            id = UUID.randomUUID().toString(),
            packageName = target.packageName,
            label = target.label,
            versionName = target.versionName,
            status = BackupStatus.WAITING,
        )
    }
}

/** 目录里找到的一套备份文件（URI 用字符串：模型层保持 Android 无关）。 */
data class BackupFiles(
    val meta: BackupMeta,
    val externalUri: String? = null,
    val obbUri: String? = null,
    val apkUri: String? = null,
) {
    val totalBytes: Long get() = meta.externalBytes + meta.obbBytes
}

/** 存储代理失败分类（与服务端 `storage:<code>:` 前缀对齐）。 */
enum class StorageError {
    NO_SERVICE, NO_SPACE, DENIED, NOT_FOUND, IO, CANCELLED, UNKNOWN;

    companion object {
        /**
         * 从异常信息里解析服务端错误码。
         *
         * 服务端不能用 IOException 跨 binder（会被包成 RuntimeException），所以统一
         * 抛 `IllegalStateException("storage:<code>:<message>")`；这里把 code 提出来，
         * 失败原因就能本地化成一句人话，而不是把英文堆栈丢给用户。
         */
        fun parse(message: String?): StorageError {
            if (message == null) return UNKNOWN
            val marker = "storage:"
            var index = message.indexOf(marker)
            while (index >= 0) {
                val end = message.indexOf(':', index + marker.length)
                if (end > 0) {
                    when (message.substring(index + marker.length, end)) {
                        "no_space" -> return NO_SPACE
                        "denied" -> return DENIED
                        "not_found" -> return NOT_FOUND
                        "cancelled" -> return CANCELLED
                        "io" -> return IO
                    }
                }
                index = message.indexOf(marker, index + marker.length)
            }
            if (message.contains("not running") || message.contains("binder haven't been received")) {
                return NO_SERVICE
            }
            if (message.contains("SecurityException") || message.contains("Permission Denial")) {
                return DENIED
            }
            return UNKNOWN
        }
    }
}

/** 文件命名规则（规格 F3：`<包名>_<版本名>_<时间戳>.tar.gz`）。 */
object BackupNames {

    // SimpleDateFormat 不是线程安全的：队列线程写文件名、UI 线程也在算时间，
    // 统一走一把锁，比 ThreadLocal（API 24 上不保证有 withInitial）更稳。
    private val TIME_FORMAT = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun timestamp(millis: Long): String = synchronized(TIME_FORMAT) {
        TIME_FORMAT.format(Date(millis))
    }

    /** 版本名可能带 `+`、空格、中文；文件名只保留安全字符。 */
    fun sanitizeVersion(versionName: String?): String {
        val raw = versionName?.trim().orEmpty()
        if (raw.isEmpty()) return "unknown"
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            sb.append(
                if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
            )
        }
        return sb.toString().take(32)
    }

    /** 去掉路径分隔符等，防止包名（来自 meta，可被用户改）拼出目录外路径。 */
    fun sanitizePackage(packageName: String): String {
        val sb = StringBuilder(packageName.length)
        for (c in packageName) {
            sb.append(if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-') c else '_')
        }
        return sb.toString()
    }

    fun base(packageName: String, versionName: String, backupTime: Long): String =
        "${sanitizePackage(packageName)}_${sanitizeVersion(versionName)}_${timestamp(backupTime)}"

    fun externalTar(packageName: String, versionName: String, backupTime: Long): String =
        base(packageName, versionName, backupTime) + ".tar.gz"

    fun obbTar(packageName: String, versionName: String, backupTime: Long): String =
        base(packageName, versionName, backupTime) + ".obb.tar.gz"

    fun apk(packageName: String, versionName: String, backupTime: Long): String =
        base(packageName, versionName, backupTime) + ".apk"

    fun metaFile(packageName: String): String = "${sanitizePackage(packageName)}.meta.json"

    fun isMeta(name: String?): Boolean = name?.endsWith(".meta.json") == true
}
