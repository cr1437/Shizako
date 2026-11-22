<div align="center">

<img src="docs/banner.jpg" alt="Shizako 酱抱着终端的横幅插画" width="820" />

# 🐱 Shizako

## 让猫耳看板娘替你「借」系统权限 —— 不 Root、不刷机

*Your catgirl assistant for privileged Android APIs — no root, no drama.*

**简体中文** | [English](README_EN.md)

兼容 [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 生态 · 作者 [初然](https://github.com/cr1437)

<!-- 徽章全部自托管在 docs/ 下，用相对路径引用：这类图片由 github.com 自己提供，
     在国内网络也能稳定显示（以前用 shields.io 图床会整排裂图）。
     version / downloads / star 三个会变的指标由 .github/workflows/badges.yml
     定时（以及每次发版后）自动重新生成。 -->
[![版本](docs/badge-version.svg)](https://github.com/cr1437/Shizako/releases)
[![下载量](docs/badge-downloads.svg)](https://github.com/cr1437/Shizako/releases)
[![Star](docs/badge-star.svg)](https://github.com/cr1437/Shizako)
[![平台](docs/badge-platform.svg)](https://github.com/cr1437/Shizako/releases)
[![基于](docs/badge-based-on.svg)](https://github.com/RikkaApps/Shizuku)
[![许可](docs/badge-license.svg)](LICENSE)

[🌐 官网](https://cr1437.github.io/Shizako/) · [📦 下载最新版](https://github.com/cr1437/Shizako/releases) · [🐛 反馈问题](https://github.com/cr1437/Shizako/issues) · ⭐ Star 支持她

**当前版本：`zako4`** —— 性能优化主线版；新增应用数据备份/恢复、特权存储代理、安装信任列表与批量队列、EXEC_SHELL 自动化。

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

## 🆕 zako4：把「顺手」放在第一位

这一版的主线是**性能与耗电**，功能更新只是顺路带上车：

| 优化 | 之前 | 现在 |
| --- | --- | --- |
| **Baseline Profile** | 构建脚本把 `CompileArtProfileTask` 全局禁用，421 KB 的 `baseline-prof.txt` 每次生成却**从未进 APK**，冷启动只能靠 JIT | 移除禁用，release 包内 `assets/dexopt/baseline.prof` 真正生效，Compose / Material3 / RecyclerView 的基线配置一起回到包内 |
| **应用列表图标** | 每次新建列表项都在主线程同步 `loadIcon`（缓存建了但没命中） | 绑定先查 `AppIconCache`（uid + 目标尺寸），命中直接上屏，未命中才异步替换 |
| **列表测量** | 内容变化时反复 `requestLayout` | `setHasFixedSize(true)` + 固定条目标高，过滤/刷新少一轮测量 |
| **后台唤醒** | `AlarmManager.RTC_WAKEUP` 定时唤醒查更新；保活 45s 一轮 | 更新检查改 `JobScheduler`（6h、持久化、按网络触发）；保活 90s；无障碍心跳前台 60s / 息屏 5min / 省电 10min |
| **首页状态** | 一次 binder 探活失败就把整页判成「服务未运行」 | 单点失败只记日志，`onResume` 再校准，减少无效重载与闪烁 |

想自己核对「Baseline Profile 真的进包了」：

```bash
unzip -l shizako-zako4-release.apk | grep dexopt   # 应看到 assets/dexopt/baseline.prof
```

---

## 🐾 她能做到的事

| 特性 | 说明 |
|------|------|
| 免 Root 特权 | 通过 ADB / 无线调试获得近似 Root 的能力 —— 不拆机、不刷机、不心惊胆战 |
| 通行证制度 | 每个应用第一次连接都要你亲自授权，随时反悔、随时收回 |
| 官方生态直连 | 用官方 Shizuku-API 写的应用**一行代码不用改**就能连上她 |
| Dhizuku 模式 | 一键把她扶上「设备所有者」，特权常驻、免 Root 免无线调试；不需要时应用内可「取消设备所有者」 |
| 新手引导 | 首次启动逐步向导：语言 / 外观 / 启动方式，引导内**直接完成激活**；免责声明 10 秒强制阅读 |
| 四种激活方式 | Root / 无线调试 / 电脑 ADB / Dhizuku，一页搞定；已配对可一键启动 |
| **应用数据备份 / 恢复** | 外部数据（`/sdcard/Android/data`、`obb`）打包进你选的 SAF 目录，替换式恢复、批量队列、可选保存 OBB / APK |
| **特权存储代理** | 服务进程以 shell/root 身份提供受限文件接口，四层访问降级 + 路径白名单 + 全量审计；第三方应用需单独授权 |
| **安装信任列表 + 批量队列** | 受信应用免确认直装、外部请求弹确认框、XAPK / APKS / apk.1 全支持、单项失败不中断 |
| **EXEC_SHELL 自动化** | 带令牌与签名校验的广播入口，Tasker / MacroDroid 可执行特权命令并按包名收回结果 |
| **批量授权 / 权限模板** | 多选批量授予撤销、影响确认、内置模板 + 自定义模板、SAF 导出导入 |
| 一键注入 | 把权限分给常用工具（黑阈、小黑屋、冰箱、炼妖壶），没装的应用内直接下载 |
| 兼容桥 | 内置 provider 中转，Shizuku-API 客户端零改动直连；只用自己的 authority，**不占用上游任何标识** |
| Tasker 支持 | 广播一键启停服务、激活 Dhizuku、查状态、触发下载 |
| API 审计 | 完整记录哪个应用调用了哪个特权 API（含存储代理/安装/自动化的明细），设置里随时查 |
| 自动更新 | 每次打开应用自动检查新版本，发现更新**就地弹出可下载的对话框**；网络拦截 GitHub 时自动切换可用源 |
| 崩溃日志 | 崩溃自动落盘 `Download/Shizako-crash-*.txt` |

---

## 💾 应用数据备份与恢复（zako4 新增）

入口：**应用管理 → 右上角「应用数据备份与恢复」**。

- **备份**：勾选一个或多个应用 → 选一个 SAF 目录 → 开始。默认只打包外部数据
  （`/sdcard/Android/data/<包名>`、`/sdcard/Android/obb/<包名>`），可勾选一并保存 OBB / APK。
  数据边读边写进你选的目录，**设备上不生成临时归档**；同时写入 `<包名>.meta.json`（包名、版本、时间、Android 版本、是否含 OBB/APK）。
- **恢复**：选备份目录 → 认出 `<包名>.meta.json` → **二次确认覆盖** → 恢复。
  恢复是**替换式**：先解到 staging 再整体替换，解压失败时你原来的数据一个字节都不会动；
  OBB 失败会单独报告（不影响外部数据）；目标应用未安装会提示先安装，备份里带了 APK 可直接交给安装器。
- **批量**：备份走队列，顺序执行、单项失败不中断、可取消。
- **边界**：`/data/data` 的完整备份不在这个功能范围内（只处理外部数据）。

---

## 📦 安装器：信任列表 + 批量队列（zako4 新增）

- **受信应用直装**：签名 + uid 双校验通过的应用，请求安装时不再弹确认框；判定顺序固定（先调用方、再策略、最后才动安装），
  信任列表可在「设置 → 安装」里维护。
- **外部应用请求**：不在信任列表里的请求会弹确认框（默认拒绝），并写入历史与审计。
- **批量队列**：一次投递多个安装包，顺序执行、单项失败不中断、可暂停/取消/重试；
  支持普通 APK、微信式 `.apk.1`、XAPK（含 split 与 OBB）、APKS（bundletool 产物）。
- **历史**：每次安装记录来源、格式、耗时、结果与失败原因，最多保留 200 条。

---

## 🤖 自动化：EXEC_SHELL（zako4 新增）

- 广播 action：`com.churan.shizako.action.EXEC_SHELL`，需要显式组件 + 令牌 + 发送方签名校验；
- 结果通过 `com.churan.shizako.action.EXEC_SHELL_RESULT` **按调用方包名**回传，令牌泄漏也不会把输出发给别人；
- 令牌在「设置 → 自动化」里生成与轮换，命令在白名单内执行、带超时与长度限制。

---

## 🧩 批量授权与权限模板

- 应用管理页长按（或右上角菜单「选择」）进入多选，批量授予 / 撤销，带影响确认、进度与结果报告；
- 3 个内置模板 + 自定义模板，支持 SAF 导出 / 导入（冲突可选重命名 / 跳过 / 覆盖）；
- 每次批量操作写入审计日志（带批次 ID），可在「权限模板 → 批次历史」里回看。

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
| Dhizuku（设备所有者） | 电脑执行一次 `adb shell dpm set-device-owner com.churan.shizako/moe.shizuku.manager.dhizuku.DhizukuAdminReceiver`（设备上不能有账户），之后免 Root、免无线调试；不需要时在应用内「Dhizuku 应用」页点「取消设备所有者」即可退回 | 一劳永逸党 |

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

### 🔏 与官方 Shizuku 共存，以及「怎么确认你装的是官方构建」

Shizako 用**自己的**包名（`com.churan.shizako`）和权限名，provider 只声明自己的 authority
`com.churan.shizako.shizuku`，不使用上游的 `moe.shizuku.privileged.api` 包名、`Shizuku` 应用名、
`moe.shizuku.manager.permission.*` 权限、上游 provider authority 或上游图标。
因此它**可以与官方 Shizuku 同时安装**，两者互不干扰 —— 哪个在跑，就用哪个。
（历史上 GitHub 版曾用一个占用上游包名的「兼容占位包」来讨好把老包名写死的客户端，该方案已彻底移除：
它逐字违反上游 Apache-2.0 §6 的附加条款，且从外部看与一份「重打包改名的仿冒品」无法区分。）

#### 官方构建的签名指纹

所有官方构建都使用同一个发布证书签名：

| 项 | 值 |
| --- | --- |
| 证书主体 | `CN=Shizako, OU=Churan, O=Churan, L=Beijing, ST=Beijing, C=CN` |
| 证书 SHA-256 | `515BAF0C727F2AFBABA8A22379A05F8F2CC98682E5BEB24FB166565ACC9F9C8A` |

校验方式（二选一）：

1. 电脑上对该 APK 执行 `apksigner verify --print-certs shizako-*.apk`，比对上面这一行。
2. 手机上的应用内「设置 → 关于」会显示**当前这份安装包自己的**签名指纹，与上表对照即可。
   如果指纹对不上，App 启动时也会弹警告并引导打开官方发布页核对（只警告、不阻断；F-Droid 版不弹）。

指纹对不上 = 这份 APK 被人重新打包并换过签名，**不要安装、不要授权**。
F-Droid 版由 F-Droid 用自己的密钥重新签名，指纹与上表不同，属正常；请以 F-Droid 页面标注为准。

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

### 存储代理（给受信第三方）

zako4 起服务端多了一个 `IStorageProxy`（列目录 / 开文件 / tar 打包 / 安全解压 / 复制 / 删除 / 建目录 / 存在性）。
第三方应用要同时满足两个条件才能用：

1. 已在 Shizako 里拿到普通 Shizuku 授权；
2. 在「数据备份与恢复 → 第三方调用」里**单独打开**该应用的「存储代理」开关。

每次调用都会写一行审计（调用方 uid、操作、目标路径摘要、结果、耗时）。接口路径先过白名单与注入校验，越权、`../`、shell 元字符都会被拒。

---

## 📦 自己动手编译

```bash
git clone https://github.com/cr1437/Shizako.git
cd Shizako
./gradlew :manager:assembleRelease
```

| 依赖 | 版本 |
|------|------|
| JDK | **21**（根 `build.gradle` 是 `VERSION_21`、`manager` 是 `jvmTarget = "21"`；仓库未声明 toolchain，直接用 `JAVA_HOME`，**17 编译不过**） |
| Android SDK | compileSdk 36 |
| NDK | 29.0.14206865 |

- 国内网络已配好阿里云 Maven 镜像，无需额外代理
- 产物在 `manager/build/outputs/apk/release/`；release 会把 Baseline Profile 打进 `assets/dexopt/`
- 未提供 `signing.properties` 时会自动回退到 debug 签名（仅自用够；**发布版必须使用项目签名**，否则用户无法覆盖升级）
- 更细的构建/测试说明见 [docs/BUILD.md](docs/BUILD.md)

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
| `com.churan.shizako.action.EXEC_SHELL` | 特权命令执行（需令牌 + 签名校验，见「自动化」一节） |

---

## 🗂️ 源码结构

共 **10 个 Gradle 模块**（以 `settings.gradle` 为准）：

```
Shizako/
├── manager/          Android 应用本体（Kotlin，约 5 万行，占全仓约 85%）
│   └── storage/      应用数据备份/恢复（zako4 新增）
├── server/           特权服务进程（Java，跑在 shell / root 里）
│   └── storage/      特权存储代理实现（zako4 新增）
├── starter/          服务启动器（启动 server 进程）
├── shell/            构建 rish_shizuku.dex（`ShizukuShellLoader`），供 manager 打包进 APK
├── common/           共享工具（BuildUtils / OsUtils）
├── api/              内置的 Shizuku-API（MIT）—— 下含 6 个子模块：
│   ├── aidl/           AIDL 接口定义
│   ├── shared/         客户端与服务端共用的常量
│   ├── api/            客户端库（rikka.shizuku.Shizuku）
│   ├── provider/       客户端侧 ShizukuProvider
│   ├── server-shared/  服务端共用基类
│   └── rish/           rish 命令行工具的 native 部分
├── hidden-api-stub/  hidden API 编译期桩
└── docs/             文档与物料（官网由 tools/md2html.py 从这里生成）
```

> `api/` 内的 6 个模块在 `settings.gradle` 里被改写 projectDir 指向 `api/<name>`，
> 所以顶层看不到它们的目录名。

---

## ❓ FAQ

**Q：要 Root 吗？**
A：不要。无线调试 / USB 就能激活；有 Root 也行，姿势更多，但绝非必需。

**Q：这版为什么叫 zako4？**
A：改动面比过往任何一次都大（性能主线 + 备份恢复 + 安装器 + 自动化），继续叫 3.20 容易让人低估；版本号同步从 320 跳到 400。

**Q：跟 Shizuku 什么关系？**
A：它兼容 Shizuku-API 生态 —— 用官方 SDK 写的应用零改动直连。按上游许可要求，本项目使用自己的包名与权限名、不占用上游任何 authority，因此可以和官方 Shizuku 装在同一台机器上；在此之上还加了 Dhizuku 兼容、一键注入、数据备份等自家功能。

**Q：备份会碰 `/data/data` 吗？**
A：不会。只处理外部数据（`/sdcard/Android/data`、`/sdcard/Android/obb`）；完整的 `/data/data` 备份不在这个功能范围内。

**Q：第三方备份工具怎么用存储代理？**
A：先给它正常的 Shizuku 授权，再到「数据备份与恢复 → 第三方调用」里单独打开它的「存储代理」开关；只有 Shizuku 授权、没有这个开关时，接口会明确拒绝并记审计。

**Q：性能优化到底改了啥？**
A：主要是四处：Baseline Profile 真正进包（冷启动）、应用列表图标真正命中缓存、列表测量收敛、后台唤醒（更新检查改 JobScheduler / 心跳自适应 / 保活 90s）。核对方法见上面的 `unzip -l | grep dexopt`。

**Q：能不能和官方 Shizuku 一起装？**
A：可以。Shizako 的 provider 只声明自己的 authority，不会和官方 Shizuku 撞。两个都装、各自授权即可。

**Q：授权给应用安全吗？**
A：每个应用都要你亲自点头才拿得到通行证，白名单本地记录、随时可收回；Dhizuku 特权转发也只在 Shizako 进程内完成；存储代理还要额外单独授权，且每次调用都写审计。

**Q：支持哪些 Android 版本？**
A：Android 7.0+ 都能跑；无线调试激活需要 Android 11+。

**Q：配对时一直停在「正在搜索配对服务」，端口填不了？**
A：自动发现配对端口靠 mDNS 组播。GrapheneOS 开启 VPN 时组播不会送进应用，华为 / 鸿蒙部分机型同样会拦截 —— 这类设备上自动发现必然失败。配对对话框的端口框**始终可见可手输**：从设备「开发者选项 → 无线调试 → 使用配对码配对设备」页面上读出**配对端口**填进去，配对码一起输入即可。如果设备上 adbd 已经监听在固定端口（比如 5555），也可以直接用激活页的「手动连接端口」，跳过配对。

**Q：安装/更新时 Play Protect 提示「应用可能有害」，是病毒吗？**
A：不是。Shizako 是侧载应用，又具备「下载安装」「用 app_process 起特权进程」「设备所有者」这类能力，Play Protect 的启发式模型很容易把 Shizuku 生态工具判成风险项 —— 判定发生在 Google 侧，开发者无法在代码里关掉它。建议按顺序自查：① 只从 [Releases](https://github.com/cr1437/Shizako/releases) 下载，并用 `apksigner verify --print-certs <apk>` 核对证书 SHA-256 为 `515baf0c…9f9c8a`；② 如果你装的是 Actions 产物（debug 签名）或来源不明的包，请换正式签名包；③ 确认无误后，可在 Play Protect 提示里点「更多详情 → 仍然保留」，或在「设置 → Google → 安全 → Play Protect」关闭「扫描应用」（这会降低整机防护，不建议长期关闭）。判定权在 Google，可以按[官方指引](https://developers.google.com/android/play-protect/warning-dev-guidance)提交误报申诉，但申诉是单向流程、不承诺结果与时限。

**Q：为什么装包里有那么多「危险权限」？**
A：都是功能必需：`QUERY_ALL_PACKAGES` 用来显示已授权应用的名称与图标；`WRITE_SECURE_SETTINGS` 用来一键开无线调试、管理无障碍服务；DeviceAdmin 就是 Dhizuku 模式本身；`STORAGE_PROXY` 是存储代理的能力标记（真正的开关在应用内）。应用不会在后台偷偷使用它们，权限白名单本地记录、随时可收回。

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
- 未使用上游 `Shizuku` 名称、`moe.shizuku.privileged.api` 包名、`moe.shizuku.manager.permission.*` 权限、上游 provider authority 及上游图标
- `api/` 目录遵循其自身的 MIT License

### 名称与物料（品牌）

沿用上游在 Apache-2.0 §6 下的附加条款形式，本项目同样声明：

- **禁止**以 `Shizako`（含 `Shizako+`、`Shizako Plus`、`Shizako Pro` 等任何易混淆变体）作为
  第三方应用的名称，或用作其包名 / application id / 权限名的组成部分；
- **禁止**使用本项目的看板娘立绘、应用图标（`manager/src/main/res/mipmap*/ic_launcher*.png`
  等全部物料）用于展示 Shizako 之外的任何东西；
- **禁止**以 `com.churan.shizako` 为 application id 分发任何非官方构建。

换句话说：代码可以（也欢迎）fork，**名字、图标、包名要换掉**。
这样既符合 Apache-2.0，也让用户一眼能分辨官方构建 —— 这正是防仿冒最有效的一环。

---

<div align="center">

> ⭐ **喜欢 Shizako 酱？点个 Star 再走吧** —— 每一颗星都是她的猫粮 `(ฅ'ω'ฅ)`
>
> [点这里投喂 Star →](https://github.com/cr1437/Shizako)

</div>
