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
     * **仍要带上 `--apk=`**：不带的话 starter 会回退到「按 `starter.cpp` 里的 PACKAGE_NAME
     * 去 `pm path` 找 APK」。那个宏已改成本 fork 自己的包名（com.churan.shizako），兜底
     * 能成，但多一次 shell 调用、且要求 PACKAGE_NAME 与 applicationId 永远保持一致；
     * 直接把本体的 APK 路径显式告诉 starter 更稳。
     */
    val adbCommand = "adb shell $internalCommand"
}
