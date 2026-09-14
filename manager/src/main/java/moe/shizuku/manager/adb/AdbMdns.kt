package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * mDNS 发现无线调试的端口（连接端口 / 配对端口）。
 *
 * 基于上游实现，吸收 Shevery 的健壮性修复：
 * 1. Android 11~13 的 NsdManager 同一时刻只能 resolve 一个服务 —— 用队列逐个来，
 *    并发 resolve 在部分 ROM 上会丢回调甚至直接崩；
 * 2. 回调线程不确定 —— 队列读写上锁，防止竞态；
 * 3. 端口必须先确认真的有人在监听（adbd），再上报给观察者。
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context, private val serviceType: String,
    private val observer: Observer<Int>
) {

    @Volatile
    private var registered = false

    @Volatile
    private var running = false

    @Volatile
    private var serviceName: String? = null

    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    // Android 11~13 上用队列逐个 resolve（照搬 Shevery）
    @Volatile
    private var pendingResolve: NsdServiceInfo? = null
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val queueLock = Any()

    fun start() {
        if (running) return
        running = true
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service discovery", e)
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        // 清掉未完成的 resolve，别让孤儿回调再触发新的
        synchronized(queueLock) {
            resolveQueue.clear()
            pendingResolve = null
        }
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop service discovery: ${e.message}")
        }
    }

    private fun onDiscoveryStart() {
        registered = true
    }

    private fun onDiscoveryStop() {
        registered = false
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        // synchronized 防止与 drainResolveQueue 的竞态（回调可能来自不同线程）
        synchronized(queueLock) {
            if (pendingResolve == null) {
                pendingResolve = info
                nsdManager.resolveService(info, ResolveListener(this))
            } else {
                resolveQueue.add(info)
            }
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) observer.onChanged(-1)
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        // 端口必须真的有人在监听（adbd 起来后才会 bind），再上报
        if (running && isPortInUse(resolvedService.port)) {
            serviceName = resolvedService.serviceName
            observer.onChanged(resolvedService.port)
        }
        drainResolveQueue()
    }

    private fun onResolveFailed() {
        drainResolveQueue()
    }

    private fun drainResolveQueue() {
        synchronized(queueLock) {
            pendingResolve = null
            if (running) {
                val next = resolveQueue.poll()
                if (next != null) {
                    pendingResolve = next
                    nsdManager.resolveService(next, ResolveListener(this))
                }
            }
        }
    }

    // 端口被占用 = bind 失败 = adbd 在监听；能 bind 上说明还没人起来。
    private fun isPortInUse(port: Int): Boolean = try {
        ServerSocket().use {
            it.bind(InetSocketAddress("127.0.0.1", port), 1)
            false
        }
    } catch (e: IOException) {
        true
    } catch (e: SecurityException) {
        // 部分新系统对回环 bind 有额外限制：宁可放行，也别把正常服务丢掉。
        Log.w(TAG, "SecurityException checking port $port: ${e.message}")
        true
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")

            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")

            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")

            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")

            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, i: Int) {
            Log.w(TAG, "onResolveFailed: ${nsdServiceInfo.serviceName}, code=$i")
            adbMdns.onResolveFailed()
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"
    }
}