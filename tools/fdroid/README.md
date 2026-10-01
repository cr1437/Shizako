# 上架 F-Droid：准备清单与拦路虎

> 这份文档记录把 Shizako 提交到 F-Droid 需要做的事。recipe 草稿见 `com.churan.shizako.yml`，
> 实施方案与进度见 `变体方案.md`（第六节有逐项勾选）。
>
> **当前状态（2026-10-01）：两个硬拦路虎都已解决**（预编译二进制 → 构建时生成；
> 镜像 → `-Pfdroid` 自动关闭），剩发 tag + 提 MR，以及第 4 节里两个 MR 时验证项。

## 1. 提交方式

F-Droid 不用 GitHub，走 **GitLab**：

1. 注册/登录 <https://gitlab.com>
2. Fork <https://gitlab.com/fdroid/fdroiddata>
3. 把 `com.churan.shizako.yml` 放到 fork 的 `metadata/` 目录
4. 提 Merge Request（模板会要求你自查一遍下面的清单）

## 2. 两个硬拦路虎 —— 均已解决

### 2.1 仓库里有预编译二进制 —— 已解决（提交 `ee9a0d7`）

| 文件 | 现状 |
|---|---|
| `manager/src/main/assets/shizako-stub.apk` | ✅ 已解除 git 跟踪；构建时由 `:stub` 源码构建 + `copyStubApk` 拷入 assets |
| `manager/src/main/assets/rish_shizuku.dex` | ✅ 本就未入库（`.gitignore` 忽略），由 `:shell:assemble` 构建时生成 |

验证过：删掉盘上的 stub 后重新构建，产物里仍含这两个文件（新鲜克隆等价场景）。

### 2.2 构建环境：离线 + 国内镜像 + NDK —— 已解决

- 镜像：`settings.gradle` 检测到 `-Pfdroid` 时**自动关闭全部阿里云镜像**，
  改走 google() / mavenCentral() / gradlePluginPortal()。GitHub 版（不带参数）不变。
  recipe 因此**不需要** `prebuild:` sed。
- 离线验证：`-Pfdroid --offline` 配置与解析通过（本机缓存热；F-Droid 构建机
  对允许源有预置/代理机制 —— 他们 metadata 里 `sudo: curl nodejs.org` 是白名单网络的旁证）。
- NDK：根 `build.gradle` 与 recipe 均为 **29.0.14206865**（一致 ✔）。
  仍需 MR 时确认 F-Droid 构建机有这一版，没有就降级并同步改两处。

## 3. 上架前还要知道的三件事

| 事项 | 影响 |
|---|---|
| **签名不同** | F-Droid 用它自己的密钥签名 APK → 用户**无法从 GitHub 版覆盖升级**（必须卸载重装）。这是 F-Droid 的固有限制，几乎所有应用都这样。stub 的权限是 `dangerous` 级（校验在本体），换签名后运行时无碍 ✔ |
| **版本号规范** | `versionName` 是 `zako3.12`（F-Droid 版 `-f` 后缀），recipe 里 `versionCode: 312`，**每次发版递增**（目前 312 ✔） |
| **与官方 Shizuku 冲突** | 兼容桥占用了官方 provider authority，两者不能共存。F-Droid 审核可能会问，需要在 MR 里**主动说明**这是刻意设计（`README` 里已写明） |

## 4. 审核可能追问的点（提前准备答复）

1. **为什么用自有的权限名** → 上游许可禁止 fork 使用官方 app id 与权限名（`NOTICE` 已声明）
2. **更新检查请求 api.github.com** → F-Droid 版已彻底关闭自更新与更新检查
   （`FdroidBuild.allowSelfUpdate`，设置页连入口都不显示），该问题不复存在
3. **预编译二进制的来源与构建方式** → 见 2.1，全部构建时从本仓库源码生成
4. ⚠️ **MR 时验证**：libsu（`com.github.topjohnwu.libsu`）只发布在 jitpack.io
   （Maven Central 实测 404）→ 需确认 jitpack 在构建机依赖源允许范围内；
   备选：srclibs 源码构建，或换一个 Maven Central 上的 root 库
5. ⚠️ **MR 时验证**：recipe `UpdateCheckData` 从根 build.gradle 提取的 versionName
   不带 `-f` 后缀 → 若 fdroiddata linter 报不一致，把后缀下沉进根 versionName

## 5. 执行顺序（与 变体方案.md 第六节同步）

1. ✅ 预编译二进制改成「构建时生成」→ 双变体构建验证
2. ✅ 镜像与 NDK（`-Pfdroid` 变体集中差异）→ 纯官方源 + `--offline` 模拟验证
3. ⬜ 发一个**与 recipe 完全对应的 tag**（例如 `zako3.13`），versionCode 递增
4. ⬜ 用 `com.churan.shizako.yml` 提 MR（先过第 4 节两个 ⚠️）

## 6. 本机构建环境备忘（给以后的会话）

- **必须经 ASCII junction 构建**：项目在 `E:\工作区\Shizako-KernelSU-UI`（中文路径），
  AGP 拒绝非 ASCII 路径（`android.overridePathCheck` 可绕，但 AIDL 增量任务仍会
  撞 GBK/UTF-8 解码错乱）。仓库已建 junction：
  `mklink /J E:\build\Shizako-KernelSU-UI "E:\工作区\Shizako-KernelSU-UI"`
  —— **一律在 `E:\build\Shizako-KernelSU-UI` 下执行 gradlew**（不复制、不产生第二份仓库）。
- 环境变量：`JAVA_HOME=E:\jdk21`，`GRADLE_USER_HOME=E:\gh`，tmp `-Djava.io.tmpdir=E:/tmp`。
- **切换变体时给 `-Pkotlin.incremental=false`**：`-Pfdroid` 翻转 BuildConfig 常量
  触发 K2 增量重编，会出现一批「同模块兄弟类 Unresolved」的幽灵报错
  （报错位置漂移、import 行起头）→ 见到这个形态先怀疑增量缓存，加参数重跑，
  或删 `manager/build/kotlin` + 杀 Kotlin daemon。
- 本机 Gradle 访问国外仓库（google/central/plugin portal）需临时代理：
  `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890`
  （系统代理 127.0.0.1:7890 只有 PowerShell/WinINET 用，Java 不读）。
- 发版构建 GitHub 版时记得：AGP 会清掉同变体旧产物，两个包不会同时留在
  `manager/build/outputs/apk/release/`，需要两个就及时把产物拷进 `out/apk/`。
