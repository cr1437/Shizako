package moe.shizuku.manager.authorization

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Parcel
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.shizuku.server.ServerConstants
import rikka.parcelablelist.ParcelableListSlice
import rikka.shizuku.Shizuku
import java.util.*

object AuthorizationManager {

    private const val FLAG_ALLOWED = 1 shl 1
    private const val FLAG_DENIED = 1 shl 2
    private const val MASK_PERMISSION = FLAG_ALLOWED or FLAG_DENIED

    private fun getApplications(userId: Int): List<PackageInfo> {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("moe.shizuku.server.IShizukuService")
            data.writeInt(userId)
            try {
                Shizuku.getBinder()!!.transact(ServerConstants.BINDER_TRANSACTION_getApplications, data, reply, 0)
            } catch (e: Throwable) {
                throw RuntimeException(e)
            }
            reply.readException()
            @Suppress("UNCHECKED_CAST")
            (ParcelableListSlice.CREATOR.createFromParcel(reply) as ParcelableListSlice<PackageInfo>).list!!
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    fun getPackages(): List<PackageInfo> {
        val packages: MutableList<PackageInfo> = ArrayList()
        // 服务没在跑时拿不到列表（binder 都没有），直接返回空
        if (!isServiceAvailable()) return packages
        if (Shizuku.isPreV11() || (Shizuku.getVersion() == 11 && Shizuku.getServerPatchVersion() < 3)) {
            val allPackages: MutableList<PackageInfo> = ArrayList()
            for (user in ShizukuSystemApis.getUsers(useCache = false)) {
                try {
                    allPackages.addAll(ShizukuSystemApis.getInstalledPackages((PackageManager.GET_META_DATA or PackageManager.GET_PERMISSIONS).toLong(), user.id))
                } catch (e: Throwable) {
                    LOGGER.w(e, "getInstalledPackages")
                }
            }
            for (pi in allPackages) {
                if (BuildConfig.APPLICATION_ID == pi.packageName) continue
                if (pi.applicationInfo?.metaData?.getBoolean("moe.shizuku.client.V3_SUPPORT") != true) continue
                if (pi.requestedPermissions?.contains(Manifest.permission.API_V23) != true) continue

                packages.add(pi)
            }
        } else {
            packages.addAll(getApplications(-1))
        }
        return packages
    }

    /**
     * 服务是否可用。
     *
     * 服务没起来（未激活 / 刚被杀掉）时调用 [Shizuku] 的接口会抛
     * `IllegalStateException: binder haven't been received` —— 界面在绑定列表项时
     * 必须能安全地问「授权了吗」，所以这里先探一下 binder。
     */
    private fun isServiceAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun granted(packageName: String, uid: Int): Boolean {
        return try {
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, packageName, uid / 100000) == PackageManager.PERMISSION_GRANTED
            } else {
                (Shizuku.getFlagsForUid(uid, MASK_PERMISSION) and FLAG_ALLOWED) == FLAG_ALLOWED
            }
        } catch (e: Throwable) {
            // 服务没在跑：当作没授权（列表页此时会显示「请先激活」）
            LOGGER.w(e, "granted($packageName)")
            false
        }
    }

    fun grant(packageName: String, uid: Int) {
        if (!isServiceAvailable()) return
        try {
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.grantRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
            } else {
                Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, FLAG_ALLOWED)
            }
        } catch (e: Throwable) {
            LOGGER.w(e, "grant($packageName)")
        }
    }

    fun revoke(packageName: String, uid: Int) {
        if (!isServiceAvailable()) return
        try {
            if (Shizuku.isPreV11()) {
                ShizukuSystemApis.revokeRuntimePermission(packageName, Manifest.permission.API_V23, uid / 100000)
            } else {
                Shizuku.updateFlagsForUid(uid, MASK_PERMISSION, 0)
            }
        } catch (e: Throwable) {
            LOGGER.w(e, "revoke($packageName)")
        }
    }

    }
