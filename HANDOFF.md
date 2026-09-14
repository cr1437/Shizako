# Shizako 项目交接文档

> 写给下一个接手的模型/开发者。读完本文档应能直接继续开发，无需重新探索。
> 日期：2026-09-10 ｜ 版本：zako3.01 ｜ 状态：debug 构建通过

---

## 一、项目是什么

**Shizako** 是 [Shizuku](https://github.com/RikkaApps/Shizuku) 的一个 Fork（应用名为 Shizako，包名 `com.churan.shizako`），在原生 Shizuku Manager 基础上做了两层改造：

1. **UI 层**：整套界面改成 KernelSU 风格 —— 单 Activity + Jetpack Navigation、亚克力（Acrylic）悬浮胶囊底部导航、滑块型选中指示器、统一设计 Token。
2. **功能层**：新增 Dhizuku（Device Owner）集成、一键激活能力，以及本次刚完成的「一站式激活」首页卡片 + 详细页 + 悬浮窗配对。

源码目录：`/data/user/work/shizako/Shizako-KernelSU-UI`

主要代码都在 `manager/` 模块（应用本体）。其余模块：`api/`（Shizuku API）、`server/`（运行在 root/adb 下的服务端）、`starter/`（启动器）、`shell/`（终端）、`common/`（公共代码）。

---

## 二、构建环境（重要，别踩坑）

| 项 | 值 |
|---|---|
| JDK | **必须用 JDK 21**：`/opt/jdk21`（系统默认 java 是 11，会失败） |
| Android SDK | `/opt/android-sdk`（`local.properties` 已配好 `sdk.dir`） |
| Gradle | **不要用 `./gradlew`**（wrapper 会从腾讯镜像下载，被墙超时）。直接用本地：`/opt/gradle814/gradle-8.14/bin/gradle` |
| NDK/CMake | 已装好，C 层（`adb` pairing 的 SPAKE2 native 库）能正常编译 |

### 构建命令（验证可用）

```bash
cd /data/user/work/shizako/Shizako-KernelSU-UI && \
  JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH \
  /opt/gradle814/gradle-8.14/bin/gradle :manager:assembleDebug --console=plain
```

产物：`manager/build/outputs/apk/debug/shizako-zako3.01-debug.apk`（约 17MB）。

### 已知网络限制

- `:manager:assembleRelease` 目前**会失败**：`dev.rikka.rikkax.parcelablelist:parcelablelist:2.0.1` 的 **release AAR 没进过缓存**（debug AAR 有缓存），而 maven.aliyun.com 在此环境连不通。要出 release 包，需要先让该依赖的 release aar 进 Gradle 缓存（换能通的环境跑一次，或手动把 aar 放进 `~/.gradle/caches/modules-2/files-2.1/dev.rikka.rikkax.parcelablelist/parcelablelist/2.0.1/`）。
- 沙箱有 `HTTP(S)_PROXY=http://127.0.0.1:18080`，curl/wget 走代理没问题，但 Gradle 下载走直连会超时，所以能不动依赖就别动。

---

## 三、Git 状态

仓库已初始化，两个 commit：

```
12feac4 fix: real aapt/kotlin compile errors (itemActiveIndicatorStyle@null, STIFFNESS_LOW, pill stroke token)
49353af init
```

**当前工作区有大量未提交改动**（`git status` 可见），就是本次「一站式激活 + 悬浮窗配对」的工作。接手的模型可以先 `git add -A && git commit` 一个 checkpoint。

---

## 四、本次完成的工作（最新一轮）

### 4.1 底部导航（前几轮已完成，本次未动）

- **形态**：亚克力悬浮胶囊，纯图标（无文字标签），48dp 高。
- **实现**：`moe.shizuku.blurview.BlurView`（bundled Dimezis/BlurView）实时模糊内容区 + `nav_frost_scrim` 罩层 + `nav_capsule_background` 圆角轮廓（`clipToOutline=true` 裁成胶囊）。
- **选中指示器**：滑块型（38x4dp 细横条，贴图标下方），切换 Tab 时**水平弹簧滑动**过去（stiffness=220, dampingRatio=0.9），不做图标弹跳。
- **关键文件**：
  - `manager/src/main/res/layout/view_floating_nav.xml` — 导航布局（BlurView + 共享 pill + BottomNavigationView）
  - `manager/src/main/res/drawable/nav_active_pill_background.xml` — pill 三层 layer-list（顶缘 1dp 微光 / `?colorPrimary` 实心 / 同色描边）
  - `manager/src/main/res/values/design_tokens.xml` — **全部尺寸 Token 的唯一事实来源**（`ksu_nav_*`、`ksu_nav_pill_*`）
  - `manager/src/main/java/moe/shizuku/manager/MainActivity.kt` — `setupNavPill()` 弹簧动画、`applyNavCapsuleInsets()` 手势条避让

**踩过的坑（别再犯）**：
1. Material 的 `BottomNavigationView` 会把系统手势条 inset 叠加成**内部 padding**，把 48dp 固定高度吃掉、图标被裁成一条。修复：对 `nav` 设置空 insets listener（`MainActivity.setupAcrylicNav()` 里 `ViewCompat.setOnApplyWindowInsetsListener(binding.navCapsule.nav) { _, insets -> insets }`）。
2. Material 1.12 的 `NavigationBarView` **没有** `itemActiveIndicatorEnabled` 布尔属性，要用 `app:itemActiveIndicatorStyle="@null"` 关闭每项各自的静态指示器。
3. `SpringForce` 没有 `STIFFNESS_MEDIUM_LOW` 常量（只有 HIGH/MEDIUM/LOW/VERY_LOW），自填写数值即可（当前用 220f）。
4. XML 里必须用具体的 `BottomNavigationView`，不能用抽象父类 `NavigationBarView`（没有两参构造器，会 NoSuchMethodException）。

### 4.2 一站式激活（本轮核心）

**设计目标**：替代旧的 Brevent/小黑屋/冰箱/Island 4 张独立启动卡片 + 通知栏配对流程。首页一张卡片列出 4 种激活方式，点「详细指南」进入一站式激活页；无线调试用悬浮窗输入配对码，不再用通知。

#### 首页卡片

- `manager/src/main/res/layout/home_activation_item.xml` — 卡片布局（标题 + 描述 + `methods_container` 动态行容器 + 详细指南按钮）
- `manager/src/main/res/layout/item_activation_method.xml` — 单行布局（图标 + 名称/描述 + outlined 按钮）
- `manager/src/main/java/moe/shizuku/manager/home/ActivationViewHolder.kt` — ViewHolder：动态添加 4 行（Dhizuku / Root / 无线调试 / 电脑 ADB），按服务运行状态、root 状态、Android 版本启用/禁用按钮；Dhizuku 支持服务运行时**就地一键激活**（`ActivationRunner.run(DhizukuSettings.setDeviceOwnerCommand)`，后台线程执行，Toast 反馈结果）
- `HomeAdapter.kt` 中 `ID_ACTIVATION = 8L`，仅主用户（`UserHandleCompat.myUserId() == 0`）显示

#### 一站式激活详细页

- `manager/src/main/java/moe/shizuku/manager/activation/ActivationFragment.kt` + `manager/src/main/res/layout/fragment_activation.xml`
- 结构（从上到下）：
  1. **当前状态卡**：服务状态（未运行/运行中 root/运行中 adb）、Dhizuku 激活状态，`onResume()` 时刷新
  2. **方式一 Root**：描述 + 步骤 + 启动按钮（跳 `StarterFragment`，`EXTRA_IS_ROOT=true`）
  3. **方式二 无线调试**（Android 11+ 才显示 `method_wadb` 整卡）：描述 + 4 步教程 + 「悬浮窗配对」主按钮 + 「打开开发者选项」次按钮 + MIUI 注意事项（`DeviceCompatibility.isMiui()` 时显示）
  4. **方式三 电脑 ADB**：描述 + 步骤 + 命令框（等宽字体、可选中）+ 复制/发送按钮
  5. **方式四 Dhizuku**：描述 + 步骤 + 警告（需移除所有账号）+ 一键激活/查看命令按钮
  6. **FAQ 卡**：重启后是否要重新激活 / 选哪种方式 / 失败排查
- 页面壳用 `fragment_sub_page.xml`（大标题折叠栏 + 返回键 + `content_container`），所有次级页共用
- 导航：`main_nav.xml` 里 `activation_fragment`；从首页用 `MainActivity.destinationIntent(ctx, R.id.activation_fragment)` 单入口跳入（带 KernelSU 同款转场动画 `fragment_enter/exit`）

#### 悬浮窗配对（替代通知栏 RemoteInput）

- `manager/src/main/java/moe/shizuku/manager/pairing/FloatingPairService.kt` + `manager/src/main/res/layout/floating_pair.xml`
- **这是普通 Service，不是前台服务**（可见悬浮窗本身就保持前台重要性，Manifest 里无需 foregroundServiceType）
- 流程（两阶段状态机 `Phase.PAIR` / `Phase.CONNECT`）：
  1. `showWindowOnce()`：`WindowManager` 添加 300dp 宽 MaterialCardView（`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_TOUCH_MODAL`，窗外触摸照常落到系统设置上），初始位置顶部居中偏下，标题栏 `drag_handle` 可拖动
  2. **PAIR 阶段**：`AdbMdns(TLS_PAIRING)` 自动发现配对端口 → 填入端口框；用户输入 6 位配对码 → `AdbPairingClient(127.0.0.1, port, code, key).start()` 配对
  3. **CONNECT 阶段**：配对成功后 `AdbMdns(TLS_CONNECT)` 自动发现连接端口 → 拿到端口直接 `AdbClient.connect()` + `shellCommand(Starter.internalCommand)` 启动服务，全自动无需再点
  4. 成功后状态栏显示完成，3 秒自动关闭；整体 5 分钟超时自动 `stopSelf()`
- **ADB 密钥**：`AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")`，与 `StarterFragment` / 旧 `AdbPairingService` 同一别名，保证各入口用同一把持久化密钥
- 权限：Manifest 已声明 `SYSTEM_ALERT_WINDOW`；`ActivationFragment.launchFloatingPair()` 先查 `Settings.canDrawOverlays()`，未授权则弹对话框引导去授权，`onResume()` 检测授权完成后自动继续
- 错误处理：连接失败 / 配对码错（`AdbInvalidPairingCodeException`）/ 密钥失败（`AdbKeyException`）分别给不同提示

#### 字符串

`values/strings.xml`（英文默认）、`values-zh-rCN/strings.xml`、`values-zh-rTW/strings.xml` 三个文件均已补齐全部新字符串（`activation_*` 约 35 条 + `floating_pair_*` 约 21 条 + `close`）。其他语言缺失没关系，aapt 会回退到英文默认（构建日志里的 "removing resource ... without required default value" 警告是**旧激活 UI 删除后残留的非默认语言字符串**，无害，想清理的话去各 `values-*/strings.xml` 删掉 `activation_run/activation_title` 等旧键）。

#### Manifest 与导航接线（均已完成）

- `AndroidManifest.xml`：`SYSTEM_ALERT_WINDOW` 权限 + `.pairing.FloatingPairService` 注册
- `main_nav.xml`：`activation_fragment` 已加入
- 首页卡片「详细指南」按钮 → `activation_fragment`；无线调试行 → `activation_fragment`

---

## 五、已删除的旧文件（别再引用）

```
activation/ActivationTarget.kt
activation/OneClickActivationFragment.kt
home/AdbDialogFragment.kt
home/StartAdbViewHolder.kt / StartDhizukuViewHolder.kt / StartRootViewHolder.kt / StartWirelessAdbViewHolder.kt
home/WadbNotEnabledDialogFragment.kt
layout/adb_dialog.xml / home_start_adb.xml / home_start_dhizuku.xml / home_start_root.xml / home_start_wireless_adb.xml / item_activation_target.xml
```

注意：`adb/AdbPairingService.kt`（旧的通知栏配对服务）**还存在于代码库且 Manifest 仍注册**，目前没有任何入口调它了。可以保留（无害），也可以下轮清理：删文件 + 删 Manifest 里的 service 声明 + 删 `notification_channel_adb_pairing` 等专属字符串。

---

## 六、建议的下一步

按优先级：

1. **真机验证悬浮窗配对全流程**（Android 11+ 机器）：开启无线调试 → 首页卡片 → 详细指南 → 悬浮窗配对 → 授权悬浮窗权限 → 开发者选项开无线调试 → 配对对话框出码 → 悬浮窗输码 → 自动连接启动。重点看：mDNS 是否能在系统配对对话框打开时稳定发现端口；悬浮窗在系统设置上的层级和键盘弹出是否正常。
2. **悬浮窗 UI 打磨**：现在是 300dp 宽卡片 + 顶部居中初始位（`y = ksu_nav_margin_bottom * 4`，比较随意）。可以考虑：初始位置贴屏幕右上 1/4 处更像聊天悬浮头；加成功/失败的状态图标动画；配对码输入框做成 6 格分段样式。
3. **清理旧通知栏配对**（见第五节）。
4. **Release 包**：解决 parcelablelist release aar 缓存问题后跑 `:manager:assembleRelease`。
5. **git commit**：把当前工作区提交掉。

---

## 七、快速上手检查单

```bash
# 1. 构建（30 秒内应完成，增量）
cd /data/user/work/shizako/Shizako-KernelSU-UI && \
  JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:$PATH \
  /opt/gradle814/gradle-8.14/bin/gradle :manager:assembleDebug --console=plain

# 2. 看改动
git status && git diff --stat

# 3. 关键文件速览
cat manager/src/main/java/moe/shizuku/manager/pairing/FloatingPairService.kt
cat manager/src/main/java/moe/shizuku/manager/activation/ActivationFragment.kt
cat manager/src/main/res/layout/floating_pair.xml
```

交接包内容：`Shizako-KernelSU-UI/`（完整源码，已排除 build/.gradle 产物）、`HANDOFF.md`（本文档）、`shizako-zako3.01-debug.apk`（当前构建产物）。
