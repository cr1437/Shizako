package moe.shizuku.manager.activation

/**
 * An app that normally requires `adb shell` commands from a computer to be
 * activated, along with the exact command the official sources publish.
 *
 * Commands verified against:
 * - Brevent: https://brevent.sh/
 * - Stopapp (小黑屋): https://stopapp.https.gs/nonroot.html
 * - Ice Box (冰箱): https://iceboxdoc.catchingnow.cn/
 * - Island (炼妖壶): https://github.com/oasisfeng/island/blob/gh-pages/setup.md
 */
data class ActivationTarget(
    /** Display name of the target app. */
    val label: String,
    /** Package name used to check whether the app is installed. */
    val packageName: String,
    /**
     * The activation command, executed with `sh -c` on the device by the
     * Shizaku server (shell or root uid).
     */
    val command: String,
    /** How the activation state of this target can be detected. */
    val detection: Detection,
    /** Human readable notes shown before activation. */
    val notesRes: Int,
    /**
     * Official download location shown when the app is not installed.
     * Direct APK links (ending in .apk) are downloaded in-app and installed;
     * other URLs open in a Custom Tab.
     */
    val downloadUrl: String? = null
) {
    enum class Detection {
        /** Device owner apps: `dpm set-device-owner`, detect via dumpsys device_policy. */
        DEVICE_OWNER,
        /** Server bootstrap apps (Brevent): needs re-activation on every reboot, no persistent state. */
        BOOTSTRAP
    }

    companion object {
        const val NOTE_BREVENT = 0
        const val NOTE_DEVICE_OWNER = 1
    }
}

object ActivationTargets {

    val BREVENT = ActivationTarget(
        label = "Brevent 黑域",
        packageName = "me.piebridge.brevent",
        // Official one-liner from https://brevent.sh/
        command = "output=\$(pm path me.piebridge.brevent); export CLASSPATH=\${output#*:}; " +
                "app_process /system/bin me.piebridge.brevent.server.BreventServer bootstrap; " +
                "/system/bin/sh /data/local/tmp/brevent.sh",
        detection = ActivationTarget.Detection.BOOTSTRAP,
        notesRes = ActivationTarget.NOTE_BREVENT,
        downloadUrl = "https://github.com/xm1437/apk-mirror/releases/download/v1.0.0/me.piebridge.brevent.apk"
    )

    val STOPAPP = ActivationTarget(
        label = "小黑屋",
        packageName = "web1n.stopapp",
        command = "dpm set-device-owner web1n.stopapp/.receiver.AdminReceiver",
        detection = ActivationTarget.Detection.DEVICE_OWNER,
        notesRes = ActivationTarget.NOTE_DEVICE_OWNER,
        downloadUrl = "https://github.com/xm1437/apk-mirror/releases/download/v1.0.0/web1n.stopapp.apk"
    )

    val ICEBOX = ActivationTarget(
        label = "冰箱 Ice Box",
        packageName = "com.catchingnow.icebox",
        command = "dpm set-device-owner com.catchingnow.icebox/.receiver.DPMReceiver",
        detection = ActivationTarget.Detection.DEVICE_OWNER,
        notesRes = ActivationTarget.NOTE_DEVICE_OWNER,
        downloadUrl = "https://github.com/xm1437/apk-mirror/releases/download/v1.0.0/com.catchingnow.icebox.apk"
    )

    val ISLAND = ActivationTarget(
        label = "炼妖壶 Island",
        packageName = "com.oasisfeng.island",
        command = "dpm set-device-owner com.oasisfeng.island/.IslandDeviceAdminReceiver",
        detection = ActivationTarget.Detection.DEVICE_OWNER,
        notesRes = ActivationTarget.NOTE_DEVICE_OWNER,
        downloadUrl = "https://github.com/xm1437/apk-mirror/releases/download/v1.0.0/com.oasisfeng.island.apk"
    )

    val ALL = listOf(BREVENT, STOPAPP, ICEBOX, ISLAND)
}
