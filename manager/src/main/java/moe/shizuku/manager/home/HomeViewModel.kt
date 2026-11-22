package moe.shizuku.manager.home

import android.content.pm.PackageManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.Manifest
import moe.shizuku.manager.model.ServiceStatus
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.manager.utils.ShizukuSystemApis
import rikka.lifecycle.Resource
import rikka.shizuku.Shizuku

class HomeViewModel : ViewModel() {

    private val _serviceStatus = MutableLiveData<Resource<ServiceStatus>>()
    val serviceStatus = _serviceStatus as LiveData<Resource<ServiceStatus>>

    private fun load(): ServiceStatus {
        val ping = try {
            Shizuku.pingBinder()
        } catch (tr: Throwable) {
            LOGGER.w(tr, "pingBinder")
            false
        }
        if (!ping) {
            return ServiceStatus()
        }

        // binder 在：先拿到 uid（拿不到也按「在运行」处理，绝不因为一个探测失败就显示睡懒觉）
        val uid = try {
            Shizuku.getUid()
        } catch (tr: Throwable) {
            LOGGER.w(tr, "getUid")
            1
        }

        return try {
            loadFromService(uid)
        } catch (tr: Throwable) {
            // 典型场景：hidden API 被系统拦/某个服务端查询被拒。
            // 以前这里直接 postValue(error, ServiceStatus())，首页就永远显示「睡懒觉」，
            // 而应用列表、授权这些功能都正常 —— 用户看到的就是状态自相矛盾。
            LOGGER.w(tr, "load service status")
            ServiceStatus(
                uid = uid,
                apiVersion = runCatching { Shizuku.getVersion() }.getOrDefault(-1),
                patchVersion = runCatching { Shizuku.getServerPatchVersion() }.getOrDefault(-1),
            )
        }
    }

    private fun loadFromService(uid: Int): ServiceStatus {
        val apiVersion = Shizuku.getVersion()
        val patchVersion = Shizuku.getServerPatchVersion().let { if (it < 0) 0 else it }
        val seContext = if (apiVersion >= 6) {
            try {
                Shizuku.getSELinuxContext()
            } catch (tr: Throwable) {
                LOGGER.w(tr, "getSELinuxContext")
                null
            }
        } else null
        val permissionTest = try {
            Shizuku.checkRemotePermission("android.permission.GRANT_RUNTIME_PERMISSIONS") == PackageManager.PERMISSION_GRANTED
        } catch (tr: Throwable) {
            LOGGER.w(tr, "checkRemotePermission")
            false
        }

        // Before a526d6bb, server will not exit on uninstall, manager installed later will get not permission
        // Run a random remote transaction here, report no permission as not running
        // （探活失败只记日志：服务端拒绝这一笔不代表服务没在跑）
        runCatching {
            ShizukuSystemApis.checkPermission(Manifest.permission.API_V23, BuildConfig.APPLICATION_ID, 0)
        }.onFailure { LOGGER.w(it, "checkPermission(API_V23) probe") }

        return ServiceStatus(uid, apiVersion, patchVersion, seContext, permissionTest)
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val status = load()
                _serviceStatus.postValue(Resource.success(status))
            } catch (e: CancellationException) {

            } catch (e: Throwable) {
                _serviceStatus.postValue(Resource.error(e, ServiceStatus()))
            }
        }
    }
}
