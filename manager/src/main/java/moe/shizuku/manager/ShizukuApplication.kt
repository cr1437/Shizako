package moe.shizuku.manager

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.topjohnwu.superuser.Shell
import moe.shizuku.manager.dhizuku.DhizukuSettings
import moe.shizuku.manager.ktx.logd
import moe.shizuku.manager.utils.CrashLog
import moe.shizuku.manager.utils.StatusNotification
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.core.util.BuildUtils.atLeast30
import rikka.material.app.LocaleDelegate

lateinit var application: ShizukuApplication

class ShizukuApplication : Application() {

    companion object {

        init {
            logd("ShizukuApplication", "init")

            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_REDIRECT_STDERR))
            if (Build.VERSION.SDK_INT >= 28) {
                HiddenApiBypass.setHiddenApiExemptions("")
            }
            if (atLeast30) {
                System.loadLibrary("adb")
            }
        }
    }

    private fun init(context: Context?) {
        ShizukuSettings.initialize(context)
        DhizukuSettings.initialize(requireNotNull(context))
        LocaleDelegate.defaultLocale = ShizukuSettings.getLocale()
        AppCompatDelegate.setDefaultNightMode(ShizukuSettings.getNightMode())
    }

    override fun onCreate() {
        super.onCreate()
        application = this
        CrashLog.install(this)
        StatusNotification.install(this)
        init(this)
        // 使用模式（简单 / 进阶）在偏好初始化之后再读一次：
        // FeatureMode 是单例，若在 initialize() 之前被谁顺手读了一下，
        // 就会把默认档位锁死，用户上次选的进阶模式会「忘掉」。
        moe.shizuku.manager.feature.FeatureMode.refresh()
    }

}
