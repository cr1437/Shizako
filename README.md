<div align="center">

<img src="docs/banner.jpg" alt="Shizako 酱抱着终端的横幅插画" width="820" />

# 🐱 Shizako

## 让猫耳看板娘替你「借」系统权限 —— 不 Root、不刷机

*Your catgirl assistant for privileged Android APIs — no root, no drama.*

**简体中文** | [English](README_EN.md)

基于 [Shizuku](https://github.com/RikkaApps/Shizuku) 二次开发 · 作者 [初然](https://github.com/cr1437)

<!-- 徽章全部自托管在 docs/ 下，用相对路径引用：这类图片由 github.com 自己提供，
     在国内网络也能稳定显示（以前用 shields.io 图床会整排裂图）。
     version / downloads / star 三个会变的指标由 .github/workflows/badges.yml
     每 6 小时（以及每次发版后）自动重新生成。 -->
[![版本](docs/badge-version.svg)](https://github.com/cr1437/Shizako/releases)
[![下载量](docs/badge-downloads.svg)](https://github.com/cr1437/Shizako/releases)
[![Star](docs/badge-star.svg)](https://github.com/cr1437/Shizako)
[![平台](docs/badge-platform.svg)](https://github.com/cr1437/Shizako/releases)
[![基于](docs/badge-based-on.svg)](https://github.com/RikkaApps/Shizuku)
[![许可](docs/badge-license.svg)](LICENSE)

[📦 下载最新版](https://github.com/cr1437/Shizako/releases) · [🐛 反馈问题](https://github.com/cr1437/Shizako/issues) · ⭐ Star 支持她

</div>

---

> 「你好呀，我是 **Shizako 酱** `(ฅ'ω'ฅ)`
>
> 手机里明明躺着那么多『系统限定』的好东西 —— 静默安装、冻结应用、调权限、读系统设置 ——
> 平时却只有系统自己碰得到，是不是很气？Root 当然能拿到钥匙，但代价嘛……变砖警告、保修飞走、
> 银行 App 翻脸不认人，懂的都懂。
>
> 所以我走了另一条路：先替你跑一个特权小进程（无线调试 / USB / Root 随便哪种姿势唤醒我），
> 然后把这个特权**借给你信得过的应用**。注意是『借』不是『送』—— 每个应用都要你亲自点头
> 才发通行证，想收回随时收。」

---

## 🐾 她能做到的事

| 特性 | 说明 |
|------|------|
| 免 Root 特权 | 通过 ADB / 无线调试获得近似 Root 的能力 —— 不拆机、不刷机、不心惊胆战 |
| 通行证制度 | 每个应用第一次连接都要你亲自授权，随时反悔、随时收回 |
| 官方生态直连 | 用官方 Shizuku-API 写的应用**一行代码不用改**就能连上她 |
| Dhizuku 模式 | 一键把她扶上「设备所有者」，特权常驻、免 Root 免无线调试；Dhizuku-API 应用（Hail、冰箱等）直接借用 |
| 新手引导 | 首次启动逐步向导：语言 / 外观 / 启动方式，引导内**直接完成激活**；免责声明 10 秒强制阅读 |
| 四种激活方式 | Root / 无线调试 / 电脑 ADB / Dhizuku，一页搞定；已配对可一键启动 |
| 一键注入 | 把权限分给常用工具（黑阈、小黑屋、冰箱、炼妖壶），没装的应用内直接下载 |
| 兼容桥 | 内置官方 provider 中转，认不出 Fork 包名的老客户端也能连上 |
| Tasker 支持 | 广播一键启停服务、激活 Dhizuku、查状态、触发下载 |
| API 审计 | 完整记录哪个应用调用了哪个特权 API，设置里随时查 |
| 自动更新 | 每次打开应用自动检查新版本，发现更新**就地弹出可下载的对话框**；网络拦截 GitHub 时自动切换可用源 |
| 崩溃日志 | 崩溃自动落盘 `Download/Shizako-crash-*.txt` |

---

## 🚀 把她带回家

### 第一步：领养

去 [Releases](https://github.com/cr1437/Shizako/releases) 下载最新版 APK，安装。

### 第二步：选一种姿势唤醒她

| 激活方式 | 要求 | 适用人群 |
|----------|------|---------|
| 无线调试 | Android 11+，开无线调试后扫码 / 配对即可 | 懒得插线的你 |
| 电脑 ADB | 电脑跑一次应用内给出的 `adb` 命令，无版本限制 | 手边有电脑的你 |
| Root | 已有 Root 环境直接唤醒 | 有 Root 的极致玩家 |
| Dhizuku（设备所有者） | 电脑执行一次 `adb shell dpm set-device-owner com.churan.shizako/.dhizuku.DhizukuAdminReceiver`（设备上不能有账户），之后免 Root、免无线调试 | 一劳永逸党 |

激活流程与上游 Shizuku 一致，可参考[上游配置指南](https://shizuku.rikka.app/zh-hans/guide/setup/)，把 Shizuku 换成 Shizako 即可。

### 第三步：发通行证

打开想授权的应用，Shizako 会弹出授权框 —— 点同意，权限到手；点拒绝，礼貌再见。每一张「通行证」都记在小本本上，随时可以在设置里收回。

---

## 🔌 生态兼容

### 底层就是 Shizuku 的 API

`api/` 目录装着 [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 的完整源码（MIT License）。通信协议、接口、调用方式与上游同一套 —— 具体改动见 [api/SHIZAKO-CHANGES.md](api/SHIZAKO-CHANGES.md)。

### 官方生态应用直连

改了名字，没改脾气。用官方 `dev.rikka.shizuku:api` 写的应用（MT 管理器、冰箱、SystemUI Tuner 等）**不用改代码、不用重编译**，装好就连：

| 什么应用 | 连官方 Shizuku | 连 Shizako |
|----------|:---:|:---:|
| 官方 `dev.rikka.shizuku:api` 写的 | ✅ | ✅ |
| 用本仓库 `api/` 写的 | ✅ | ✅ |

服务端只校验「是否请求了权限」，不校验「请求了哪个权限名」；首次连接照样弹授权框，你点头才算数。

### Dhizuku 兼容：她是自己的设备所有者

- 被设为 Device Owner 后，Dhizuku-API 应用把 Shizako 当 Dhizuku 用，**不用改代码**
- 特权转发全部发生在 Shizako 进程内，系统看到调用者是 Shizako 自己 —— 没给第三方开系统 binder 后门
- 每个 Dhizuku-API 应用首次借用都会弹授权框，同意后记入本地白名单，可在「Dhizuku 授权的应用」页面收回
- 兼容层为独立编写（wire 协议对齐 Dhizuku-API），不依赖 GPL 许可的 Dhizuku-API 库，Shizako 保持 Apache-2.0

### ⚠️ 一个重要的「不能」

Shizako 内置兼容桥占用了官方 provider authority `moe.shizuku.privileged.api.shizuku`，因此**不能与官方 Shizuku 同时安装**（Android 会报 provider 冲突）。二选一即可。

---

## 🎨 看板娘一角

<div align="center">
<img src="manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="Shizako logo" />
</div>

Shizako 酱：白发猫耳、月牙发饰，人设是「抱着终端机的猫娘系统助手」。项目图标、应用内文案、宣传物料都围绕她展开。

> 官方认证：欢迎拿她当表情包用 (ˊᗜˋ*)，只要别拿去干坏事。

---

## 🛠️ 给开发者

### 方案一：直接连（推荐）

官方 `dev.rikka.shizuku:api` 已能直连两个管理器，**啥都不用改**。完整用法见 [docs/API.md](docs/API.md)：依赖配置、权限申请、binder 生命周期、AIDL 用户服务全流程示例。

### 方案二：源码级引入

把 `api/` 目录搬进项目：

```gradle
// settings.gradle
include ':aidl', ':shared', ':api', ':provider'
project(':aidl').projectDir = file('api/aidl')
project(':shared').projectDir = file('api/shared')
project(':api').projectDir = file('api/api')
project(':provider').projectDir = file('api/provider')

// app/build.gradle
implementation project(':api')
implementation project(':provider')
```

代码写法与官方 API 完全一致：`Shizuku.bindUserService(...)`、`Shizuku.requestPermission(...)` 照旧。

---

## 📦 自己动手编译

```bash
git clone https://github.com/cr1437/Shizako.git
cd Shizako
./gradlew :manager:assembleRelease
```

| 依赖 | 版本 |
|------|------|
| JDK | 17 及以上 |
| Android SDK | compileSdk 36 |
| NDK | 29.0.13113456 |

- 国内网络已配好阿里云 Maven 镜像，无需额外代理
- 产物在 `manager/build/outputs/apk/release/`
- 未提供 `signing.properties` 时会自动回退到 debug 签名（仅自用够；**发布版必须使用项目签名**，否则用户无法覆盖升级）

### Tasker / MacroDroid 广播指令

| Action | 作用 |
|--------|------|
| `com.churan.shizako.action.START` | 按上次启动方式启动服务 |
| `com.churan.shizako.action.STOP` | 停止服务 |
| `com.churan.shizako.action.START_ROOT` | Root 启动 |
| `com.churan.shizako.action.START_ADB` | 无线调试启动 |
| `com.churan.shizako.action.ACTIVATE_DHIZUKU` | 一键激活设备所有者模式（需 Shizuku 模式运行中） |
| `com.churan.shizako.action.QUERY_STATUS` | 查询状态（有序广播返回 `running`、`dhizuku`） |
| `com.churan.shizako.action.DOWNLOAD_UPDATE` | 用内部下载器拉取 `url` extra 指向的 APK |
| `com.churan.shizako.action.NOTIFY_INSTALL` | 发可点击通知：点一下安装 Download 里最新的 APK |

---

## 🗂️ 源码结构

```
Shizako/
├── manager/    Android 应用本体（Kotlin）
├── server/     特权服务进程（Java，跑在 shell / root 里）
├── starter/    服务启动器
├── shell/      预编译的 shell 工具
├── stub/       兼容占位包（moe.shizuku.privileged.api）
├── common/     共享模块
├── api/        Shizuku-API 客户端库源码（MIT）
└── docs/       文档与物料
```

---

## ❓ FAQ

**Q：要 Root 吗？**
A：不要。无线调试 / USB 就能激活；有 Root 也行，姿势更多，但绝非必需。

**Q：跟 Shizuku 什么关系？**
A：她是 Shizuku 的二次开发 fork，核心能力源自上游；换了名字、包名与看板娘，另加 Dhizuku 兼容、一键注入等功能。

**Q：能不能和官方 Shizuku 一起装？**
A：不能，兼容桥会撞 provider。二选一。

**Q：授权给应用安全吗？**
A：每个应用都要你亲自点头才拿得到通行证，白名单本地记录、随时可收回；Dhizuku 特权转发也只在 Shizako 进程内完成。

**Q：支持哪些 Android 版本？**
A：Android 7.0+ 都能跑；无线调试激活需要 Android 11+。

**Q：为什么这么可爱？**
A：因为作者把她当女儿养（确信）。

---

## 💗 感谢

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) 及其贡献者 —— Shizako 的核心能力来自这里
- [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) —— 客户端通信库
- 每一个给 Shizako 酱点 Star 的你

## 📬 反馈

| 方式 | 入口 |
|------|------|
| GitHub Issues | [Issues](https://github.com/cr1437/Shizako/issues) |
| QQ 反馈 | [891276089](https://qm.qq.com/cgi-bin/qm/qr?k=891276089) |
| QQ 吹水群 | [1104445003](https://qm.qq.com/cgi-bin/qm/qr?k=1104445003) |

## 📄 许可

本项目继承上游 [Apache License 2.0](LICENSE)，另见 [NOTICE](NOTICE)。

- 原项目版权归 RikkaApps 所有；Shizako 的修改部分版权归 初然 所有
- 未使用上游 `Shizuku` 名称、`moe.shizuku.privileged.api` 包名、`moe.shizuku.manager.permission.*` 权限及上游图标
- `api/` 目录遵循其自身的 MIT License

---

<div align="center">

> ⭐ **喜欢 Shizako 酱？点个 Star 再走吧** —— 每一颗星都是她的猫粮 `(ฅ'ω'ฅ)`
>
> [点这里投喂 Star →](https://github.com/cr1437/Shizako)

</div>
