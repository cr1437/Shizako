package moe.shizuku.manager.batch

import org.json.JSONArray
import org.json.JSONObject

/** 批量操作类型：授予 / 撤销 */
enum class BatchType { GRANT, REVOKE }

/** 批量操作目标：Shizuku 运行时权限 / Dhizuku 委派（uid 白名单 + scopes） */
enum class BatchTarget { SHIZUKU, DHIZUKU_DELEGATE }

/** 单应用处理结果 */
enum class ItemStatus { SUCCESS, FAILED, SKIPPED }

/**
 * 参与批量操作的一个应用（UI 侧快照：包名 + uid + 展示名）。
 *
 * 展示名单独存一份：批量执行时应用可能已被卸载，回显结果时
 * PackageManager 已经查不到标签，总不能只显示包名。
 */
data class BatchApp(
    val packageName: String,
    val uid: Int,
    val label: String,
)

data class BatchItemResult(
    val packageName: String,
    val uid: Int,
    val status: ItemStatus,
    val reason: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("uid", uid)
        put("status", status.name)
        if (reason != null) put("reason", reason)
    }

    companion object {
        fun fromJson(o: JSONObject): BatchItemResult = BatchItemResult(
            packageName = o.optString("packageName"),
            uid = o.optInt("uid"),
            status = runCatching { ItemStatus.valueOf(o.optString("status")) }
                .getOrDefault(ItemStatus.FAILED),
            reason = if (o.has("reason")) o.optString("reason") else null,
        )
    }
}

/** 授予前的原始状态快照：撤销本批次时只还原这批新增的，不动用户原有授权。 */
data class AppPermissionSnapshot(
    val packageName: String,
    val uid: Int,
    val hadPermission: Boolean,
    val existingScopes: List<String>?,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName", packageName)
        put("uid", uid)
        put("hadPermission", hadPermission)
        if (existingScopes != null) put("existingScopes", JSONArray(existingScopes))
    }

    companion object {
        fun fromJson(o: JSONObject): AppPermissionSnapshot = AppPermissionSnapshot(
            packageName = o.optString("packageName"),
            uid = o.optInt("uid"),
            hadPermission = o.optBoolean("hadPermission"),
            existingScopes = o.optJSONArray("existingScopes")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            },
        )
    }
}

data class BatchOperationRecord(
    val id: String,
    val type: BatchType,
    val target: BatchTarget,
    val timestamp: Long,
    val items: List<BatchItemResult>,
    /** 授予前状态快照，用于撤销本批次（撤销批次里是执行前状态，不用于还原） */
    val snapshot: List<AppPermissionSnapshot>,
    /** 本批次使用的 Dhizuku scopes（空 = 只动 uid 白名单） */
    val scopes: List<String> = emptyList(),
    /** 模板套用批次带上模板名，便于历史里说清「这条是套了哪个模板」 */
    val templateName: String? = null,
    /** 撤销批次指向被撤销的原批次 id */
    val undoOf: String? = null,
) {
    val successCount: Int get() = items.count { it.status == ItemStatus.SUCCESS }
    val failedCount: Int get() = items.count { it.status == ItemStatus.FAILED }
    val skippedCount: Int get() = items.count { it.status == ItemStatus.SKIPPED }

    /** 只有「授予」批次能撤销（撤销批次本身不可再撤销） */
    val canUndo: Boolean get() = type == BatchType.GRANT && undoOf == null && successCount > 0

    fun snapshotOf(packageName: String): AppPermissionSnapshot? =
        snapshot.firstOrNull { it.packageName == packageName }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("target", target.name)
        put("timestamp", timestamp)
        put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        put("snapshot", JSONArray().apply { snapshot.forEach { put(it.toJson()) } })
        if (scopes.isNotEmpty()) put("scopes", JSONArray(scopes))
        if (templateName != null) put("templateName", templateName)
        if (undoOf != null) put("undoOf", undoOf)
    }

    companion object {
        fun fromJson(o: JSONObject): BatchOperationRecord = BatchOperationRecord(
            id = o.optString("id"),
            type = runCatching { BatchType.valueOf(o.optString("type")) }
                .getOrDefault(BatchType.GRANT),
            target = runCatching { BatchTarget.valueOf(o.optString("target")) }
                .getOrDefault(BatchTarget.SHIZUKU),
            timestamp = o.optLong("timestamp"),
            items = o.optJSONArray("items")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { BatchItemResult.fromJson(it) }
                }
            } ?: emptyList(),
            snapshot = o.optJSONArray("snapshot")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { AppPermissionSnapshot.fromJson(it) }
                }
            } ?: emptyList(),
            scopes = o.optJSONArray("scopes")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            } ?: emptyList(),
            templateName = if (o.has("templateName")) o.optString("templateName") else null,
            undoOf = if (o.has("undoOf")) o.optString("undoOf") else null,
        )
    }
}
