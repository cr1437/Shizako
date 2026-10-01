package moe.shizuku.manager.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.widget.ImageView
import androidx.collection.LruCache
import kotlinx.coroutines.*
import me.zhanghai.android.appiconloader.AppIconLoader
import moe.shizuku.manager.R
import rikka.core.util.BuildUtils
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

object AppIconCache : CoroutineScope {

    private class AppIconLruCache constructor(maxSize: Int) : LruCache<Triple<String, Int, Int>, Bitmap>(maxSize) {

        override fun sizeOf(key: Triple<String, Int, Int>, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    override val coroutineContext: CoroutineContext get() = Dispatchers.Main

    private val lruCache: LruCache<Triple<String, Int, Int>, Bitmap>

    private val dispatcher: CoroutineDispatcher

    private var appIconLoaders = mutableMapOf<Int, AppIconLoader>()

    init {
        // Initialize app icon lru cache
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024
        val availableCacheSize = (maxMemory / 4).toInt()
        lruCache = AppIconLruCache(availableCacheSize)

        // Initialize load icon scheduler
        val availableProcessorsCount = try {
            Runtime.getRuntime().availableProcessors()
        } catch (ignored: Exception) {
            1
        }
        val threadCount = 1.coerceAtLeast(availableProcessorsCount / 2)
        val loadIconExecutor: Executor = Executors.newFixedThreadPool(threadCount)
        dispatcher = loadIconExecutor.asCoroutineDispatcher()
    }

    fun dispatcher(): CoroutineDispatcher {
        return dispatcher
    }

    private fun get(packageName: String, userId: Int, size: Int): Bitmap? {
        return lruCache[Triple(packageName, userId, size)]
    }

    private fun put(packageName: String, userId: Int, size: Int, bitmap: Bitmap) {
        if (get(packageName, userId, size) == null) {
            lruCache.put(Triple(packageName, userId, size), bitmap)
        }
    }

    private fun remove(packageName: String, userId: Int, size: Int) {
        lruCache.remove(Triple(packageName, userId, size))
    }

    @SuppressLint("NewApi")
    fun getOrLoadBitmap(context: Context, info: ApplicationInfo, userId: Int, size: Int): Bitmap? {
        val cachedBitmap = get(info.packageName, userId, size)
        if (cachedBitmap != null) {
            return cachedBitmap
        }
        var loader = appIconLoaders[size]
        if (loader == null) {
            val shrinkNonAdaptiveIcons = BuildUtils.atLeast30 && context.applicationInfo.loadIcon(context.packageManager) is AdaptiveIconDrawable
            loader = AppIconLoader(size, shrinkNonAdaptiveIcons, context)
            appIconLoaders[size] = loader
        }
        val bitmap = loader.loadIcon(info, false)
        put(info.packageName, userId, size, bitmap)
        return bitmap
    }

    /**
     * 图标统一按这个尺寸解码与缓存。
     *
     * 【性能】以前两条路径各算各的尺寸：`peekCachedBitmap` 用调用方传进来的
     * `icon.measuredWidth`（新建的 view 还没布局，**恒为 0** → 退到 default_app_icon_size），
     * 而 `loadIconBitmapAsync` 用 `view.measuredWidth`（布局后等于布局里的 32dp）——
     * 两边的缓存键永远对不上。后果是每次新建 view 绑定都缓存未命中，落到
     * `AppViewHolder` 里那句主线程 `ai.loadIcon(pm)`（IPC + 解码），滚动时恰好卡在这一下。
     * 现在两条路径都用同一个尺寸，缓存才真正命中。
     */
    private fun iconSizePx(context: Context): Int =
        context.resources.getDimensionPixelSize(R.dimen.default_app_icon_size)

    /**
     * 只读缓存查询（不触发加载、不解码）：命中就能立即上屏。
     * 用于列表绑定：先查缓存，未命中再走占位加载 —— 避免每次绑定都同步全量加载图标。
     */
    fun peekCachedBitmap(context: Context, info: ApplicationInfo, userId: Int): Bitmap? =
        get(info.packageName, userId, iconSizePx(context))

    @JvmStatic
    fun loadIconBitmapAsync(context: Context,
                            info: ApplicationInfo, userId: Int,
                            view: ImageView): Job {
        return launch {
            val size = iconSizePx(context)
            val cachedBitmap = get(info.packageName, userId, size)
            if (cachedBitmap != null) {
                view.setImageBitmap(cachedBitmap)
                return@launch
            }

            val bitmap = try {
                withContext(dispatcher) {
                    getOrLoadBitmap(context, info, userId, size)
                }
            } catch (e: CancellationException) {
                // do nothing if canceled
                return@launch
            } catch (e: Throwable) {
                null
            }

            if (bitmap != null) {
                view.setImageBitmap(bitmap)
            } else {
                if (Build.VERSION.SDK_INT >= 26) {
                    view.setImageResource(R.drawable.ic_default_app_icon)
                } else {
                    view.setImageDrawable(null)
                }
            }
        }
    }
}