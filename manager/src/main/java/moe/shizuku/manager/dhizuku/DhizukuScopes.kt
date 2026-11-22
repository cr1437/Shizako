package moe.shizuku.manager.dhizuku

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import moe.shizuku.manager.R

/**
 * Dhizuku 委派作用域（delegated scopes）目录 + 读写封装。
 *
 * 为什么单独一份目录：
 * - 系统侧只有 `DevicePolicyManager.DELEGATION_*` 这些**常量字符串**，没有「可用列表」；
 *   批量授予 / 权限模板 / 差异预览都需要同一份带名称与说明的目录，否则三处各写一份必然漂移。
 * - `DELEGATION_*` 是 API 26 的 static final field，minSdk 24 的工程直接引用会触发
 *   lint NewApi（本项目此前刚把 NewApi 清零，见交接文档 §4.4）。这里存**同值字面量**，
 *   真正的 API 调用统一用 [isSupported] 守卫。
 */
object DhizukuScopes {

    data class Scope(
        val id: String,
        @StringRes val labelRes: Int,
    )

    /** 与 `DevicePolicyManager.DELEGATION_*` 同值（系统按字符串匹配，值不能改）。 */
    private const val CERT_INSTALL = "delegation-cert-install"
    private const val APP_RESTRICTIONS = "delegation-app-restrictions"
    private const val BLOCK_UNINSTALL = "delegation-block-uninstall"
    private const val PERMISSION_GRANT = "delegation-permission-grant"
    private const val PACKAGE_ACCESS = "delegation-package-access"
    private const val ENABLE_SYSTEM_APP = "delegation-enable-system-app"
    private const val INSTALL_EXISTING_PACKAGE = "delegation-install-existing-package"
    private const val KEEP_UNINSTALLED_PACKAGES = "delegation-keep-uninstalled-packages"

    /** 供模板 / 批量授予选择的全部作用域（顺序即 UI 顺序）。 */
    val ALL: List<Scope> = listOf(
        Scope(CERT_INSTALL, R.string.scope_cert_install),
        Scope(APP_RESTRICTIONS, R.string.scope_app_restrictions),
        Scope(BLOCK_UNINSTALL, R.string.scope_block_uninstall),
        Scope(PERMISSION_GRANT, R.string.scope_permission_grant),
        Scope(PACKAGE_ACCESS, R.string.scope_package_access),
        Scope(ENABLE_SYSTEM_APP, R.string.scope_enable_system_app),
        Scope(INSTALL_EXISTING_PACKAGE, R.string.scope_install_existing),
        Scope(KEEP_UNINSTALLED_PACKAGES, R.string.scope_keep_uninstalled),
    )

    /** 安装相关（「安装器专用」预设使用）。 */
    val INSTALL_RELATED: List<String> = listOf(PACKAGE_ACCESS, INSTALL_EXISTING_PACKAGE)

    fun labelRes(id: String): Int? = ALL.firstOrNull { it.id == id }?.labelRes

    /** API 26+ 才有委派能力；Android 7.x（API 24）上所有委派操作都会失败。 */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /**
     * 读某个包当前被委派的 scopes。
     * 未激活 / 非 DO / 系统太旧时返回空列表（等价于「没有委派」）。
     */
    fun read(context: Context, packageName: String): List<String> {
        if (!isSupported()) return emptyList()
        if (!DhizukuSettings.isDeviceOwner(context)) return emptyList()
        return readInternal(context, packageName)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun readInternal(context: Context, packageName: String): List<String> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return emptyList()
        return runCatching {
            dpm.getDelegatedScopes(DhizukuSettings.adminComponent, packageName)?.toList()
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 覆盖写回某个包的委派 scopes。
     *
     * @return null = 成功；否则是给用户看的失败原因
     */
    fun write(context: Context, packageName: String, scopes: List<String>): String? {
        if (!isSupported()) return context.getString(R.string.batch_reason_not_supported)
        if (!DhizukuSettings.isDeviceOwner(context)) {
            return context.getString(R.string.batch_reason_not_owner)
        }
        return try {
            writeInternal(context, packageName, scopes)
            null
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun writeInternal(context: Context, packageName: String, scopes: List<String>) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: throw IllegalStateException("DevicePolicyManager unavailable")
        // 去重 + 过滤掉目录里没有的项：非法 scope 会让 setDelegatedScopes 抛
        // IllegalArgumentException，一条坏数据不该让整个批次失败。
        val known = ALL.mapTo(HashSet()) { it.id }
        val clean = scopes.distinct().filter { it in known }
        dpm.setDelegatedScopes(DhizukuSettings.adminComponent, packageName, clean)
    }
}
