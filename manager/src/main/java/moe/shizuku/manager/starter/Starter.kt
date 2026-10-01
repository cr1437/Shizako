package moe.shizuku.manager.starter

import moe.shizuku.manager.application
import java.io.File

object Starter {

    private val starterFile = File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")

    val userCommand: String = starterFile.absolutePath

    // 注意顺序：object 的属性按声明顺序初始化，下面 adbCommand 引用了 internalCommand，
    // 所以 internalCommand 必须写在前面（否则编译期就报 "must be initialized"）。
    val internalCommand = "$userCommand --apk=${application.applicationInfo.sourceDir}"

    /**
     * 让用户在**电脑**上执行的命令（「电脑 ADB」激活方式）。
     *
     * **必须带上 `--apk=`**：不带的话 starter 会回退到「按写死的 PACKAGE_NAME 去 `pm path` 找 APK」，
     * 而 `starter.cpp` 里那个包名仍是上游的 `moe.shizuku.privileged.api` —— 在本 fork 里它只是
     * 兼容性占位壳（[moe.shizuku.manager.compat.StubManager]），里面根本没有
     * `rikka.shizuku.server.ShizukuService`，于是必然启动失败（没装占位壳时则直接 exit 7）。
     * 所以这里直接复用 [internalCommand]，把本体的 APK 路径显式告诉 starter。
     */
    val adbCommand = "adb shell $internalCommand"
}
