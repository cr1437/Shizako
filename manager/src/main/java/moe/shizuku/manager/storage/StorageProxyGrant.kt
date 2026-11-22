package moe.shizuku.manager.storage

import android.content.Context
import moe.shizuku.manager.authorization.AuthorizationManager
import rikka.shizuku.Shizuku

/**
 * 第三方应用的「存储代理」授权（规格 F5）。
 *
 * 权限模型是**两把锁**：
 * 1. 应用必须先在通行证里拿到 Shizuku 授权（[AuthorizationManager.getPackages] 里的那些）；
 * 2. 再单独勾上 [FLAG_STORAGE_PROXY] 才能调 IStorageProxy。
 *
 * 第 2 位独立成位（不是普通授权的一部分），所以「授权 API」不会顺手把存储代理也放开。
 * 服务端在 `ShizukuService.checkStorageProxyPermission` 里读同一份配置，两边都靠
 * `Shizuku.getFlagsForUid` / `updateFlagsForUid` 这套现成通道，不额外引入配置文件。
 */
object StorageProxyGrant {

    /** 必须与 `rikka.shizuku.server.ConfigManager.FLAG_STORAGE_PROXY` 保持同一个值。 */
    const val FLAG_STORAGE_PROXY = 1 shl 3

    data class Entry(
        val packageName: String,
        val label: String,
        val uid: Int,
        val granted: Boolean,
    )

    /** 列出所有声明了 Shizuku 权限的应用及其存储代理开关状态。 */
    fun load(context: Context): List<Entry> {
        val pm = context.packageManager
        return try {
            AuthorizationManager.getPackages().mapNotNull { info ->
                val ai = info.applicationInfo ?: return@mapNotNull null
                val uid = ai.uid
                Entry(
                    packageName = info.packageName,
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(info.packageName),
                    uid = uid,
                    granted = granted(uid),
                )
            }.sortedBy { it.label.lowercase() }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    fun granted(uid: Int): Boolean = try {
        (Shizuku.getFlagsForUid(uid, FLAG_STORAGE_PROXY) and FLAG_STORAGE_PROXY) != 0
    } catch (e: Throwable) {
        false
    }

    /** @return true 表示写成功（服务没跑 / 旧版本服务会抛，由调用方提示）。 */
    fun set(uid: Int, enabled: Boolean): Boolean = try {
        Shizuku.updateFlagsForUid(uid, FLAG_STORAGE_PROXY, if (enabled) FLAG_STORAGE_PROXY else 0)
        true
    } catch (e: Throwable) {
        false
    }
}
