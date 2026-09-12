package moe.shizuku.manager.download

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import moe.shizuku.manager.R

/**
 * 推荐应用（原来「一键注入」那张卡的名单 + 后面补的几个搞机工具）。
 *
 * APK 全部指向主人自己的镜像仓库 `cr1437/apk-mirror` 的 release 资源，
 * 下载走应用内下载器（[moe.shizuku.manager.update.UpdateChecker.downloadFromUrl]，
 * 和「更新 Shizako」同一套：进度弹窗 + 通知 + 完成后拉起系统安装器）。
 *
 * 本页不做「注入」：授权由应用自己在运行时向 Shizako 申请。
 */
data class RecommendedApp(
    @StringRes val nameRes: Int,
    @StringRes val descRes: Int,
    val packageName: String,
    /** 镜像仓库里的 APK 直链 */
    val apkUrl: String,
    /** 图标（随包内置，装没装都能显示） */
    @DrawableRes val iconRes: Int,
    /** 自适应图标的底图；为空表示 [iconRes] 本身就是完整图标（老式 png） */
    @DrawableRes val iconBackgroundRes: Int? = null,
) {
    /** 下载到本地用的文件名：直接用包名，一眼看得懂 */
    val fileName: String get() = "$packageName.apk"
}

object RecommendedApps {

    /** 镜像仓库（Releases 页），顺序 = 页面顺序 */
    private const val MIRROR = "https://github.com/cr1437/apk-mirror/releases/download/v1.0.0"

    val ALL: List<RecommendedApp> = listOf(
        RecommendedApp(
            nameRes = R.string.download_app_brevent,
            descRes = R.string.download_app_brevent_desc,
            packageName = "me.piebridge.brevent",
            apkUrl = "$MIRROR/me.piebridge.brevent.apk",
            iconRes = R.drawable.app_icon_brevent,
        ),
        RecommendedApp(
            nameRes = R.string.download_app_stopapp,
            descRes = R.string.download_app_stopapp_desc,
            packageName = "web1n.stopapp",
            apkUrl = "$MIRROR/web1n.stopapp.apk",
            iconRes = R.drawable.app_icon_stopapp,
        ),
        RecommendedApp(
            nameRes = R.string.download_app_icebox,
            descRes = R.string.download_app_icebox_desc,
            packageName = "com.catchingnow.icebox",
            apkUrl = "$MIRROR/com.catchingnow.icebox.apk",
            iconRes = R.drawable.app_icon_icebox_fg,
            iconBackgroundRes = R.drawable.app_icon_icebox_bg,
        ),
        RecommendedApp(
            nameRes = R.string.download_app_island,
            descRes = R.string.download_app_island_desc,
            packageName = "com.oasisfeng.island",
            apkUrl = "$MIRROR/com.oasisfeng.island.apk",
            iconRes = R.drawable.app_icon_island_fg,
            iconBackgroundRes = R.drawable.app_icon_island_bg,
        ),
        RecommendedApp(
            nameRes = R.string.download_app_scene,
            descRes = R.string.download_app_scene_desc,
            packageName = "com.omarea.vtools",
            apkUrl = "$MIRROR/com.omarea.vtools.apk",
            iconRes = R.drawable.app_icon_scene,
        ),
        RecommendedApp(
            nameRes = R.string.download_app_hail,
            descRes = R.string.download_app_hail_desc,
            packageName = "com.aistra.hail",
            apkUrl = "$MIRROR/com.aistra.hail.apk",
            iconRes = R.drawable.app_icon_hail_fg,
            iconBackgroundRes = R.drawable.app_icon_hail_bg,
        ),
    )
}
