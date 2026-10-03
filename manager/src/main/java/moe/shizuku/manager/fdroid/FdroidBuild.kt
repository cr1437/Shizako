package moe.shizuku.manager.fdroid

import moe.shizuku.manager.BuildConfig

/**
 * F-Droid 变体开关 —— **集中一处**，业务代码只引用这里，不直接读 BuildConfig。
 *
 * 为什么要单独一个变体：F-Droid 的收录政策与本项目默认行为有几处直接冲突，
 * 而不冲突的那部分（绝大多数功能）应当两个变体完全一致。把差异收在这一个文件里，
 * 审查时一眼能看清「F-Droid 版关了什么、为什么关」。
 *
 * 构建方式（见 tools/fdroid/变体方案.md）：
 * ```
 * ./gradlew :manager:assembleRelease            # GitHub 版（zako3.13）
 * ./gradlew :manager:assembleRelease -Pfdroid   # F-Droid 版（zako3.13-f）
 * ```
 */
object FdroidBuild {

    /** 当前是否为 F-Droid 版（版本名带 -f 后缀的那个）。 */
    val isFdroid: Boolean get() = BuildConfig.FDROID_BUILD

    /**
     * 自动更新 / 下载并安装 APK。
     *
     * F-Droid 用自己的密钥签名，应用自我更新会换成 GitHub 版的签名，
     * 在 F-Droid 用户设备上**必然安装失败**，同时绕过 F-Droid 的分发渠道 ——
     * 这是 F-Droid 明确不接受的模式，因此 F-Droid 版彻底关闭更新入口。
     */
    val allowSelfUpdate: Boolean get() = !isFdroid

    /**
     * 更新检查里的第三方 GitHub 反代兜底（gh-proxy / ghfast / ghproxy）。
     * 属 NonFreeNet 的边缘情形；F-Droid 版只走官方 api.github.com。
     */
    val allowUpdateMirrors: Boolean get() = !isFdroid

    /**
     * 一键注入：会把常用工具（黑阈、小黑屋等）的 APK 下载到本机并调用安装。
     * 既是分发第三方二进制，也与「不得绕过商店安装应用」的政策相抵触。
     */
    val allowOneClickInject: Boolean get() = !isFdroid

    /**
     * AI 解释（内置 OpenAI / Gemini / Moonshot / DeepSeek / 阿里云百炼 / SiliconFlow
     * 等**专有**服务商入口）。关掉之后，F-Droid 版**不需要**申报 NonFreeNet 反特性。
     */
    val allowAiExplain: Boolean get() = !isFdroid

    /**
     * 兼容性占位包（[moe.shizuku.manager.compat.StubManager]）：装一个 applicationId 为
     * 上游 `moe.shizuku.privileged.api` 的空壳，好让把该包名写死在代码里的第三方应用
     * 「看得见」本应用。
     *
     * 关掉的原因：上游 README 的 FORBIDDEN 条款禁止 fork 使用该 application id，而
     * F-Droid 维护者在 MR 里明确要求「稳妥优先、在该变体里关掉」。F-Droid 版因此
     * **不构建、不打包、不安装**占位包，也不显示相关入口（连包名字面量都不进 DEX）。
     */
    val allowCompatStub: Boolean get() = !isFdroid
}
