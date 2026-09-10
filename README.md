<div align="center">

<img src="docs/banner.jpg" alt="Shizako酱抱着终端的横幅插画" width="820" />

# 🐱 Shizako

## 让猫耳看板娘替你「借」系统权限 —— 不 Root、不刷机、不点烟

*Your catgirl assistant for privileged Android APIs — no root, no drama.*

基于 [Shizuku](https://github.com/RikkaApps/Shizuku) 二次开发 · 作者 [初然](https://github.com/xm1437)

[![Release](https://img.shields.io/github/v/release/xm1437/Shizako?style=flat-square&label=%E7%89%88%E6%9C%AC)](https://github.com/xm1437/Shizako/releases)
[![Downloads](https://img.shields.io/github/downloads/xm1437/Shizako/total?style=flat-square&label=%E4%B8%8B%E8%BD%BD)](https://github.com/xm1437/Shizako/releases)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-34A853?style=flat-square&logo=android&logoColor=white)](https://github.com/xm1437/Shizako/releases)
[![Based on](https://img.shields.io/badge/%E5%9F%BA%E4%BA%8E-Shizuku%2013.x-7C6FD0?style=flat-square)](https://github.com/RikkaApps/Shizuku)
[![License](https://img.shields.io/github/license/xm1437/Shizako?style=flat-square)](LICENSE)
[![Star](https://img.shields.io/github/stars/xm1437/Shizako?style=for-the-badge&label=%E2%AD%90%20Star%20Shizako%20%E7%8C%AE%E7%BB%99%E5%A5%B9&color=FFD700)](https://github.com/xm1437/Shizako)

</div>

---

> 「你好呀，我是 **Shizako酱** `(ฅ'ω'ฅ)`
>
> 手机里明明躺着那么多『系统限定』的好东西——静默安装、冻结应用、调权限、读系统设置——
> 平时却只有系统自己碰得到，是不是很气？Root 当然能拿到钥匙，但代价嘛……变砖警告、保修飞走、
> 银行 App 翻脸不认人，懂的都懂。
>
> 所以我走了另一条路：先替你跑一个特权小进程（无线调试 / USB / Root 随便哪种姿势唤醒我），
> 然后把这个特权**借给你信得过的应用**。注意是『借』不是『送』——每个应用都要你亲自点头
> 才发通行证，想收回随时收，比某些前任干脆多了。」

---

## 🐾 她能做到的事（功能一览）

| 特性 | 说明 |
|------|------|
| 免 Root 特权 | 通过 ADB / 无线调试获得近似 Root 的能力——不拆机、不刷机、不心惊胆战 |
| 通行证制度 | 每个应用第一次连接都要你亲自授权，随时反悔、随时收回，权限不外借给陌生猫 |
| 官方生态直连 | 用官方 Shizuku-API 写的应用**一行代码不用改**就能连上她，白嫖党狂喜 |
| Dhizuku 模式 | 一键把她扶上「设备所有者」，特权常驻、免 Root 免无线调试；Dhizuku-API 应用（Hail、冰箱等）直接借用 |
| 新手引导 | 首次启动逐步向导：语言 / 外观 / 启动方式，引导内**直接完成激活**（启动服务、一键 Dhizuku）；免责声明 10 秒强制阅读，仪式感拉满 |
| 一键注入 | 一键把权限分给常用工具（黑阈、小黑屋、冰箱、炼妖壶），没装的**应用内直接下载**（进度条 + 速度，和更新器同款体验） |
| 兼容桥 | 内置官方 `moe.shizuku.privileged.api.shizuku` provider 中转，认不出 Fork 包名的老客户端也能连上 |
| Tasker 支持 | 广播一键启停服务、激活 Dhizuku、查状态、触发下载——自动化玩家请随意调教 |
| API 审计 | 完整记录哪个应用调用了哪个特权 API，设置里随时查，谁动了你的猫条一目了然 |
| 崩溃日志 | 崩溃自动落盘 `Download/Shizako-crash-*.txt`，出了事甩日志给作者，省得来回拉扯 |

---

## 🎬 这猫是怎么来的？（正经废话时间）

一天，作者盯着手机里那些「看得见摸不着」的系统 API，陷入了沉思：*为什么好用的东西总是被锁起来？*
然后他看了看旁边的 Shizuku——好东西！再看了看屏幕上的猫娘壁纸——好可爱！

于是，**Shizako** 诞生了：Shizu**ko** = Shizuku + 「子」（没错，名字的梗就是这么硬核）。

她不是 Shizuku 的复制品，而是一个**有自己名字、自己的包名、自己的看板娘**的独立实现：

| 项目 | Shizuku（上游） | Shizako（本猫） |
|------|:---:|:---:|
| 包名 | `moe.shizuku.manager` | `com.churan.shizako` |
| 权限名 | `moe.shizuku.manager.permission.*` | `com.churan.shizako.permission.API_V23` |
| 看板娘 | 无（很遗憾） | 🐱 白发猫耳，月牙发饰，天下第一可爱 |

> 为什么改名？上游许可条款说得明明白白：fork 项目不能占用官方那套名字。咱是遵纪守法好猫，
> 自己起名自己闯，连图标都是原创的——绝不碰瓷，光明正大。

---

## 🚀 把她带回家（激活指南）

### 第一步：领养

去 [Releases](https://github.com/xm1437/Shizako/releases) 下载最新版 APK，安装，摸摸她的头（没有这个步骤，但建议有）。

### 第二步：选一种姿势唤醒她

| 激活方式 | 要求 | 适用人群 |
|----------|------|---------|
| 无线调试 | Android 11+，开无线调试扫码/配对即可 | 懒得插线的你 |
| USB 连接 | 电脑跑一次 `adb`，没有版本限制 | 手边有电脑的你 |
| Root | 已有 Root 环境直接唤醒 | 有 Root 还想要 Root 的极致玩家 |
| Dhizuku（设备所有者） | 电脑执行一次 `adb shell dpm set-device-owner com.churan.shizako/.dhizuku.DhizukuAdminReceiver`（注意：设备上不能有账户），之后免 Root、免无线调试 | 一劳永逸党 |

激活教程可以直接参考上游 Shizuku 的[配置指南](https://shizuku.rikka.app/zh-hans/guide/setup/)，流程一模一样，把 Shizuku 换成 Shizako 就行。

### 第三步：发通行证

打开想授权的应用，Shizako 会弹出授权框——点同意，权限到手；点拒绝，礼貌再见。每一张「通行证」都记在小本本上，随时可以在设置里收回。

---

## 🔌 生态兼容：她跟谁都能玩

### 底层就是 Shizuku 的 API

`api/` 目录装着 [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 的完整源码（MIT License）。通信协议、接口、调用方式和上游同一套——想知道具体改了啥，看 [api/SHIZAKO-CHANGES.md](api/SHIZAKO-CHANGES.md)。

### 官方生态应用直连（重点，敲黑板）

改了名字，没改脾气。用官方 `dev.rikka.shizuku:api` 写的应用——MT 管理器、冰箱、SystemUI Tuner——**不用改代码、不用重编译**，装好就连：

| 什么应用 | 连官方 Shizuku | 连 Shizako |
|----------|:---:|:---:|
| 官方 `dev.rikka.shizuku:api` 写的 | ✅ | ✅ |
| 用本仓库 `api/` 写的 | ✅ | ✅ |

秘密在于服务端只认「你请求了权限」这件事，不认「你请求了哪个权限名」。第一次连接照样弹授权框，你点头才算数，全程没有声明任何上游权限名——合规，是一种态度。

### Dhizuku 兼容：她是自己的设备所有者

Dhizuku-API 应用靠「设备所有者（Device Owner）」特权吃饭，Shizako 直接兼任这个角色：

- 被设为 Device Owner 后，Dhizuku-API 应用把 Shizako 当 Dhizuku 用，**不用改代码**
- 特权转发全部发生在 Shizako 进程内，系统看到调用者是 Shizako 自己——没给第三方开任何系统 binder 后门
- 每个 Dhizuku-API 应用首次借用都会弹授权框，同意后记入本地白名单，在「Dhizuku 授权的应用」页面随时可收回
- 协议是独立编写的兼容层（wire 协议对齐 Dhizuku-API），不碰 GPL 许可的 Dhizuku-API 库，Shizako 继续保持 Apache-2.0

### ⚠️ 一个重要的「不能」

Shizako 内置兼容桥占用了官方 provider authority `moe.shizuku.privileged.api.shizuku`，所以**她不能和官方 Shizuku 同时安装**（Android 会报 provider 冲突）。二选一即可——反正 Shizako 全功能覆盖官方，选她，不亏。

---

## 🎨 看板娘一角

<div align="center">
<img src="manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="Shizako logo" />
</div>

Shizako酱：白发猫耳、月牙发饰，人设是「抱着终端机的猫娘系统助手」。项目图标、应用内文案、宣传物料全都围绕她展开。

> 官方认证：欢迎拿她当表情包用 (ˊᗜˋ*)，只要别拿去干坏事，她都不会咬人。

---

## 🛠️ 给开发者：想跟猫娘合作？

### 方案一：直接连（推荐）

官方 `dev.rikka.shizuku:api` 已经能直连两个管理器，**啥都不用改**。完整 API 用法见 [docs/API.md](docs/API.md)——依赖配置、权限申请、binder 生命周期、AIDL 用户服务全流程代码示例，手把手教学。

### 方案二：源码级搬砖

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

代码怎么写？和官方 API 一模一样：`Shizuku.bindUserService(...)`、`Shizuku.requestPermission(...)` 该怎么写还怎么写。换了个依赖来源，你的应用就同时拥抱两个生态——一次开发，两头通吃。

---

## 📦 自己动手编译

```bash
git clone https://github.com/xm1437/Shizako.git
cd Shizako
./gradlew :manager:assembleRelease
```

| 你需要什么 | 版本 |
|----------|------|
| JDK | 17 及以上 |
| Android SDK | compileSdk 36 |
| NDK | 29.0.13113456 |

国内网络不用慌，仓库已配好阿里云 Maven 镜像。编译产物在 `manager/build/outputs/apk/release/`，没配签名会自动用 debug 签名顶上——自己玩完全够。

### Tasker / MacroDroid 广播指令（自动化の完全手册）

| Action | 作用 |
|--------|------|
| `com.churan.shizako.action.START` | 按上次启动方式启动服务 |
| `com.churan.shizako.action.STOP` | 停止服务 |
| `com.churan.shizako.action.START_ROOT` | Root 启动 |
| `com.churan.shizako.action.START_ADB` | 无线调试启动 |
| `com.churan.shizako.action.ACTIVATE_DHIZUKU` | 一键激活设备所有者模式（需 Shizuku 模式运行中） |
| `com.churan.shizako.action.QUERY_STATUS` | 查询状态（有序广播返回 extras：`running`、`dhizuku`） |
| `com.churan.shizako.action.DOWNLOAD_UPDATE` | 用内部下载器拉取 `url` extra 指向的 APK（进度条 + 自动弹安装） |
| `com.churan.shizako.action.NOTIFY_INSTALL` | 发可点击通知：点一下直接安装 Download 里最新的 APK |

示例（Tasker 里发广播）：

```
Action: com.churan.shizako.action.QUERY_STATUS
Package: com.churan.shizako
Broadcast Receiver: 动态注册即可，返回 extras 里读 running / dhizuku
```

---

## 🗂️ 源码长这样

```
Shizako/
├── manager/    Android 应用本体（Kotlin）
├── server/     特权服务进程（Java，跑在 shell / root 里）
├── starter/    服务启动器
├── shell/      预编译的 shell 工具
├── common/     共享模块
├── docs/       看板娘物料
└── api/        Shizuku-API 客户端库源码（MIT），已内置
```

---

## ❓ 灵魂拷问 FAQ

**Q：要 Root 吗？**
A：不要！无线调试 / USB 就能激活。有 Root 也行，姿势更多，但绝不是必需品。

**Q：跟 Shizuku 什么关系？**
A：她是 Shizuku 的二次开发 fork，核心能力全部源自上游，但换了名字、换了包名、换了看板娘，还加了 Dhizuku 兼容和一键注入等私房功能。

**Q：能不能和官方 Shizuku 一起装？**
A：不能，兼容桥会撞 provider。二选一，鱼与熊掌不可兼得（但她功能全覆盖，选她不亏）。

**Q：授权给应用安全吗？**
A：每个应用都要你亲自点头才拿得到通行证，白名单本地记录，随时可收回；Dhizuku 特权转发也只在 Shizako 进程内完成，不给第三方开后门。

**Q：支持哪些 Android 版本？**
A：Android 7.0+ 都能跑；无线调试激活需要 Android 11+。

**Q：她为什么这么可爱？**
A：因为作者把她当女儿养（确信）。

---

## 💗 感谢（挨个摸头）

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) 和它的贡献者们——Shizako 的全部核心能力来自这里，敬礼！
- [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)——客户端通信库，稳如老狗
- 每一个给 Shizako酱点 Star 的你——你们是她的猫粮（不是）

## 📬 勾搭看板娘

遇到 Bug 想报、有了想法想聊、或者单纯想夸她可爱（欢迎）：

| 方式 | 入口 |
|------|------|
| QQ 反馈 | [891276089](https://qm.qq.com/cgi-bin/qm/qr?k=891276089) |
| QQ 吹水群 | [1104445003](https://qm.qq.com/cgi-bin/qm/qr?k=1104445003) |
| GitHub Issues | [Issues](https://github.com/xm1437/Shizako/issues) |

## 📄 许可

本项目继承上游 [Apache License 2.0](LICENSE)，另见 [NOTICE](NOTICE)。

- 原项目版权归 RikkaApps 所有；Shizako 的修改部分版权归 初然 所有
- 未使用上游 `Shizuku` 名称、`moe.shizuku.privileged.api` 包名、`moe.shizuku.manager.permission.*` 权限及上游图标
- `api/` 目录遵循其自身的 MIT License

---

<div align="center">

<div align="center">

> ⭐ **喜欢 Shizako酱？点个 Star 再走吧** —— 每一颗星都是她的猫粮，
> 她会对着星星喵喵叫感谢你的 (ฅ'ω'ฅ)
>
> [点这里投喂 Star →](https://github.com/xm1437/Shizako)

</div>

---

<sub>English summary: Shizako is an Apache-2.0 licensed fork of Shizuku with an original catgirl mascot. It runs a privileged service via ADB / wireless debugging / root and lends elevated APIs to apps you trust — including apps built with the official `dev.rikka.shizuku:api`, which connect without any code change. Star it or the catgirl will be sad. (She forgives easily though.)</sub>

</div>
