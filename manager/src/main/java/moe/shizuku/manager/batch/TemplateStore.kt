package moe.shizuku.manager.batch

import android.content.Context
import android.content.SharedPreferences
import moe.shizuku.manager.R
import moe.shizuku.manager.dhizuku.DhizukuScopes
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 可授予的 Shizuku 权限项（当前只有 API_V23 这一个运行时授权）。 */
object ShizukuPermissions {

    /** 与 AndroidManifest 里声明的运行时权限一致（复用现有授权项定义）。 */
    const val API_V23: String = "com.churan.shizako.permission.API_V23"

    data class Item(val id: String, val labelRes: Int)

    val ALL: List<Item> = listOf(Item(API_V23, R.string.perm_api_v23))

    fun labelRes(id: String): Int? = ALL.firstOrNull { it.id == id }?.labelRes
}

/**
 * 权限模板（F6-F8）。
 *
 * 数据形状与规格一致；`builtIn` 的模板不落盘，每次由 [TemplateStore.builtIns] 现算，
 * 这样「只读预设」永远不可能被写坏，也不会随用户数据被导出后重复导入。
 */
data class PermissionTemplate(
    val id: String,
    val name: String,
    val shizukuPermissions: List<String>,
    val dhizukuScopes: List<String>,
    val createdAt: Long,
    val builtIn: Boolean,
) {
    val itemCount: Int get() = shizukuPermissions.size + dhizukuScopes.size

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("shizukuPermissions", JSONArray(shizukuPermissions))
        put("dhizukuScopes", JSONArray(dhizukuScopes))
        put("createdAt", createdAt)
        put("builtIn", builtIn)
    }

    companion object {
        fun fromJson(o: JSONObject): PermissionTemplate = PermissionTemplate(
            id = o.optString("id"),
            name = o.optString("name"),
            shizukuPermissions = o.optJSONArray("shizukuPermissions")?.toStringList() ?: emptyList(),
            dhizukuScopes = o.optJSONArray("dhizukuScopes")?.toStringList() ?: emptyList(),
            createdAt = o.optLong("createdAt"),
            builtIn = o.optBoolean("builtIn"),
        )
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it, null) }

/**
 * 模板存储 + 导出/导入（F6 / F8）。
 *
 * 存储用 SharedPreferences + org.json，和 [moe.shizuku.manager.utils.BackupRestoreUtil]
 * 同一套取舍：不引入新依赖、纯文本、字段可以按版本演进。
 */
object TemplateStore {

    private const val PREFS_NAME = "permission_templates"
    private const val KEY_TEMPLATES = "templates"

    /** 导出格式版本（导入只接受这个版本；将来结构变了就 +1） */
    const val EXPORT_VERSION = 1

    private const val BUILTIN_READONLY_SHELL = "builtin-readonly-shell"
    private const val BUILTIN_INSTALLER = "builtin-installer"
    private const val BUILTIN_MODULE = "builtin-module"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val p = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cached = p
            return p
        }
    }

    /**
     * 内置只读预设（3 个）：
     * - 只读 shell：只给 Shizuku 权限，不动 Device Owner；
     * - 安装器专用：不给 Shizuku 权限（即不开放 shell 命令执行），只给安装相关委派；
     * - 模块管理：Shizuku 权限（模块目录/脚本要 shell 身份）+ 安装相关委派。
     *
     * 注意：仓库现有模型里 Shizuku 侧只有 API_V23 一个**整包**授权，做不到
     * 「只给 shell、不给安装」的细分；安装/模块能力实际由 Dhizuku 委派 scopes 表达。
     * 这一点在 UI 上有说明（见 settings 之外的模板页副标题）。
     */
    fun builtIns(context: Context): List<PermissionTemplate> = listOf(
        PermissionTemplate(
            id = BUILTIN_READONLY_SHELL,
            name = context.getString(R.string.template_builtin_readonly_shell),
            shizukuPermissions = listOf(ShizukuPermissions.API_V23),
            dhizukuScopes = emptyList(),
            createdAt = 0L,
            builtIn = true,
        ),
        PermissionTemplate(
            id = BUILTIN_INSTALLER,
            name = context.getString(R.string.template_builtin_installer),
            shizukuPermissions = emptyList(),
            dhizukuScopes = DhizukuScopes.INSTALL_RELATED,
            createdAt = 0L,
            builtIn = true,
        ),
        PermissionTemplate(
            id = BUILTIN_MODULE,
            name = context.getString(R.string.template_builtin_module),
            shizukuPermissions = listOf(ShizukuPermissions.API_V23),
            dhizukuScopes = DhizukuScopes.INSTALL_RELATED,
            createdAt = 0L,
            builtIn = true,
        ),
    )

    /** 用户模板（按创建时间倒序，新的在前）。 */
    @Synchronized
    fun userTemplates(context: Context): List<PermissionTemplate> {
        val raw = prefs(context).getString(KEY_TEMPLATES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<PermissionTemplate>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            runCatching { PermissionTemplate.fromJson(obj) }.getOrNull()
                ?.takeIf { it.name.isNotBlank() }
                ?.let { out.add(it.copy(builtIn = false)) }
        }
        return out.sortedByDescending { it.createdAt }
    }

    /** 全部模板：内置在前，用户模板在后。 */
    fun all(context: Context): List<PermissionTemplate> =
        builtIns(context) + userTemplates(context)

    fun find(context: Context, id: String): PermissionTemplate? =
        all(context).firstOrNull { it.id == id }

    fun isNameTaken(context: Context, name: String, exceptId: String? = null): Boolean {
        val target = name.trim()
        return all(context).any {
            it.id != exceptId && it.name.trim().equals(target, ignoreCase = true)
        }
    }

    /**
     * 新建或更新一个用户模板。
     * @return null = 成功；否则是错误文案（重名等）
     */
    @Synchronized
    fun save(context: Context, template: PermissionTemplate): String? {
        val name = template.name.trim()
        if (name.isEmpty()) return context.getString(R.string.template_name_required)
        if (template.builtIn) return context.getString(R.string.template_builtin_readonly)
        if (isNameTaken(context, name, exceptId = template.id)) {
            return context.getString(R.string.template_name_duplicate)
        }

        val list = userTemplates(context).toMutableList()
        val index = list.indexOfFirst { it.id == template.id }
        val toStore = template.copy(
            name = name,
            shizukuPermissions = template.shizukuPermissions.distinct(),
            dhizukuScopes = template.dhizukuScopes.distinct(),
            builtIn = false,
            createdAt = if (index >= 0) list[index].createdAt else template.createdAt,
        )
        if (index >= 0) list[index] = toStore else list.add(0, toStore)
        persist(context, list)
        return null
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        val list = userTemplates(context).toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) persist(context, list)
        return removed
    }

    @Synchronized
    private fun persist(context: Context, list: List<PermissionTemplate>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_TEMPLATES, array.toString()).apply()
    }

    // ---------------- F8 导出 ----------------

    /**
     * 导出全部模板。
     *
     * 规格格式：`{"version":1,"exportedAt":<epochMillis>,"templates":[{name,shizukuPermissions,dhizukuScopes}]}`
     * —— 不含 id / createdAt / builtIn，导入端重新生成。
     */
    fun exportJson(context: Context): String {
        val templates = JSONArray()
        all(context).forEach { t ->
            templates.put(
                JSONObject().apply {
                    put("name", t.name)
                    put("shizukuPermissions", JSONArray(t.shizukuPermissions))
                    put("dhizukuScopes", JSONArray(t.dhizukuScopes))
                },
            )
        }
        return JSONObject().apply {
            put("version", EXPORT_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("templates", templates)
        }.toString(2)
    }

    // ---------------- F8 导入 ----------------

    data class ImportedTemplate(
        val name: String,
        val shizukuPermissions: List<String>,
        val dhizukuScopes: List<String>,
    )

    class ParsedImport(
        val templates: List<ImportedTemplate>,
        /** 被丢弃的非法条目数（结构不对 / 名称为空 / 字段类型不对） */
        val invalidCount: Int,
    )

    /**
     * 解析导入 JSON。
     *
     * 逐条校验，**单条错误不影响整体**：
     * - 数组元素不是对象 / name 为空 → 丢弃并计入 invalidCount；
     * - shizukuPermissions / dhizukuScopes 里的非法项（非字符串、未知 id）单独丢弃，
     *   不牵连整个模板。
     *
     * @return null = 整个文件结构就不对（不是 JSON / version 不认识 / 没有 templates 数组）
     */
    fun parseImport(json: String): ParsedImport? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (root.has("version") && root.optInt("version", -1) != EXPORT_VERSION) return null
        val array = root.optJSONArray("templates") ?: return null

        val knownPerms = ShizukuPermissions.ALL.mapTo(HashSet()) { it.id }
        val knownScopes = DhizukuScopes.ALL.mapTo(HashSet()) { it.id }

        val out = ArrayList<ImportedTemplate>(array.length())
        var invalid = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
            if (obj == null) {
                invalid++
                continue
            }
            val name = obj.optString("name", "").trim()
            if (name.isEmpty()) {
                invalid++
                continue
            }
            val perms = obj.optJSONArray("shizukuPermissions")?.toStringList()
                ?.filter { it in knownPerms } ?: emptyList()
            val scopes = obj.optJSONArray("dhizukuScopes")?.toStringList()
                ?.filter { it in knownScopes } ?: emptyList()
            out.add(ImportedTemplate(name, perms.distinct(), scopes.distinct()))
        }
        return ParsedImport(out, invalid)
    }

    /** 给一个不与现有模板重名的名字（冲突时「重命名」用）：`名字 (2)`、`名字 (3)`… */
    fun suggestUniqueName(context: Context, baseName: String): String {
        val base = baseName.trim().ifEmpty { "imported" }
        if (!isNameTaken(context, base)) return base
        var i = 2
        while (i < 1000) {
            val candidate = "$base ($i)"
            if (!isNameTaken(context, candidate)) return candidate
            i++
        }
        return "$base (${UUID.randomUUID().toString().take(4)})"
    }

    /** 以给定名称写入一个导入模板（调用方负责决定覆盖/跳过/重命名）。 */
    @Synchronized
    fun addImported(
        context: Context,
        name: String,
        shizukuPermissions: List<String>,
        dhizukuScopes: List<String>,
        overwriteExistingUserTemplate: Boolean = false,
    ): String? {
        val existing = userTemplates(context).firstOrNull {
            it.name.trim().equals(name.trim(), ignoreCase = true)
        }
        if (existing != null && overwriteExistingUserTemplate) {
            return save(
                context,
                existing.copy(
                    shizukuPermissions = shizukuPermissions,
                    dhizukuScopes = dhizukuScopes,
                ),
            )
        }
        if (isNameTaken(context, name)) return context.getString(R.string.template_name_duplicate)
        return save(
            context,
            PermissionTemplate(
                id = UUID.randomUUID().toString(),
                name = name,
                shizukuPermissions = shizukuPermissions,
                dhizukuScopes = dhizukuScopes,
                createdAt = System.currentTimeMillis(),
                builtIn = false,
            ),
        )
    }
}
