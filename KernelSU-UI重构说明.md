# Shizako · KernelSU 双风格 UI 全面重构说明

> 本次把 `manager` 模块整套界面**统一重构为 KernelSU 风格**：支持 **Material 3 Expressive（M3E）/ Miuix 双风格、设置内一键切换**；底部改为**悬浮胶囊导航**；首页改为 **KernelSU 式（状态大卡 + 设备信息卡网格）**；全套卡片圆角/间距**收拢到统一设计 Token**；并**清除了全部旧视觉与命名/零引用残留**。
>
> 配色沿用原有 **Sakura 粉 + 20 色板 + 跟随系统动态取色**（与 KernelSU 色板同源，未推翻）。

---

## 第三轮调整（2026-09-10）：底部导航纯图标紧凑化 + 果冻弹簧动画

- `view_floating_nav.xml`：`labelVisibilityMode` 由 `labeled` 改为 `unlabeled`，去掉文字说明、仅保留图标（无障碍朗读仍用 menu title）；高度由 `wrap_content` 收拢为 `ksu_nav_height`(48dp)，并覆盖 Material3 默认 `minHeight`(80dp) 使其生效。
- `design_tokens.xml`：新增 `ksu_nav_height`(48dp)；`ksu_content_bottom_padding` 随导航变矮由 112dp 下调为 88dp（四个 Tab 列表底部避让同步生效，无需改代码）。
- **选中态果冻弹簧动画**（交接文档 P1 完成）：参照 KernelSU-Next 底栏实现——新增共享滑动 pill（`nav_active_pill` + `nav_active_pill_background.xml`，全圆角胶囊，尺寸走 `ksu_nav_pill_*` Token），`itemActiveIndicatorEnabled=false` 关闭 M3 每项各自的静态指示器避免双重高亮；`MainActivity.setupNavPill()` 用 AndroidX DynamicAnimation 驱动：滑动弹簧参数对齐 KSU-Next（中弹性 0.5 + 低刚度 200），切换瞬间 pill 1.3/0.75 挤压回弹模拟果冻，选中图标 1.25→1 缩放弹跳。由 OnDestinationChanged 驱动，不触碰 NavigationUI 的 listener，menu/徽标/日志 Tab 显隐逻辑不受影响；旋转与 Tab 增删后经 OnLayoutChange 无动画对齐。弹簧动画库 `androidx.dynamicanimation` 由 material 1.12.0 传递依赖，无需新增依赖。
- **pill 亚克力化**（第三轮追加）：pill 皮肤由实色 `secondaryContainer` 改为 KernelSU FloatingBottomBar 同款三层亚克力结构——镜面高光底（全 pill 形状）+ `?colorPrimary` 18% 薄纱填充（透出 BlurView 模糊，顶部让出 `ksu_nav_pill_highlight_inset` 1dp 露出高光）+ 同色 32% 细描边。新增 `color/nav_pill_fill.xml`、`color/nav_pill_stroke.xml`（均有 `color-night/` 变体）与 `nav_pill_specular`（values + values-night，45%→25%）；全部随主题色板与深浅色自动变化。
- **第三轮修复**（底栏显示问题）：① 高光由「负边距裁剪」改为「全底 + 上层盖位」的确定性结构，不再依赖父容器 clip 行为，消除高光溢出白条；② 新增 `color/nav_icon_tint.xml` 显式指定图标着色（选中=主题色 / 未选中=onSurfaceVariant），关闭 M3 指示器后各主题色板下对比度稳定；③ `ksu_content_bottom_padding` 88→104dp，修复厚手势条机型列表末行被胶囊遮挡；④ **图标被竖直裁切的根因修复**：Material `BottomNavigationView.applyWindowInsets()` 会把系统手势条 inset 叠加为内部 padding（底+左右），固定 48dp 高度下 padding 吃掉内容区，图标被压成一条（带标签的 wrap_content 版本因高度富余不显现）。在 `MainActivity.setupAcrylicNav()` 中用 `ViewCompat.setOnApplyWindowInsetsListener(nav) { _, insets -> insets }` 覆盖为空实现——悬浮胶囊已由 `applyNavCapsuleInsets` 用外层 margin 避让，无需内部再加 padding。

## 修复记录（2026-09-10）

- **修复 MainActivity 启动即崩溃（NoSuchMethodException）**：`view_floating_nav.xml` 误用了抽象父类 `NavigationBarView`——它没有 `(Context, AttributeSet)` 两参构造器，`LayoutInflater` 无法实例化。已改为具体子类 `BottomNavigationView`（功能完全一致，menu/选中态/徽标/removeItem 都继承自它）。`MainActivity` 里 `binding.nav as NavigationBarView` 是向上转型，不受影响。

---

## 第二轮重构（2026-09-10，本轮）

> 参照 KernelSU 官方实现（`tiann/KernelSU` Home.kt 与 KernelSU-Next 玻璃底栏）对上一轮半成品做了全面返工：修崩溃、亚克力导航、首页 KernelSU 化、双倍间距与残留清理。

### 1. 修复设置页崩溃（ClassCastException）
- **根因**：上一轮把 `preference_recyclerview.xml` 当"零引用"删了。实际 androidx.preference 库的 `preference_list_fragment.xml` 会 `include @layout/preference_recyclerview`，这是应用层覆盖库实现的挂点。删除后回退为库内普通 `RecyclerView`，`SettingsFragment.onCreateRecyclerView` 强转 `BorderRecyclerView` 崩溃。
- **修复**：恢复该布局（BorderRecyclerView 实现，附防再删注释），并按本应用结构调整（去掉了上游的 `paddingTop`/`fitsSystemWindowsInsets`，见第 4 点）。

### 2. 亚克力透明悬浮导航（本轮核心诉求）
- `view_floating_nav.xml`：`MaterialCardView` 换成 **`moe.shizuku.blurview.BlurView`**（项目内置的 Dimezis/BlurView，与磨砂顶栏同一套），实时模糊胶囊下方的滚动内容；新增 `nav_frost_scrim` 罩层色（亮 #B3FFFFFF / 暗 #B3171B21）；新增 `nav_capsule_background.xml` 圆角胶囊外形（透明填充 + `?colorOutlineVariant` 细描边），`clipToOutline` 把模糊裁成胶囊形。
- `activity_main.xml`：内容区改为**全高延伸**（滚动内容从胶囊下方穿过，模糊才有料），内容包进 `BlurTarget` 作为模糊快照根；胶囊后声明、浮于其上。
- `MainActivity.kt`：`setupAcrylicNav()` 接线 `setupWith(blurTarget) + setFrameClearDrawable(window背景) + setBlurRadius(20dp)`；新增 `applyNavCapsuleVisibility()`——**KernelSU 行为：次级页（激活/教程/终端等）自动隐藏底栏**，返回顶层 Tab 恢复。

### 3. 首页严格对齐 KernelSU
- **状态卡**：从大面积 `colorPrimary` 色块改为 KernelSU 原版 tonal 容器——运行中 `secondaryContainer` / 未运行 `errorContainer`（`ServerStatusViewHolder.applyContainerColors()` 动态切换，含图标与文字 on* 前景色）；24dp 内边距、20dp 图文间距；移除彩色阴影与生硬 elevation。
- **信息卡**：删掉自创的 2 列小卡网格（KernelSU 没有这种布局），改为 KernelSU 原版**单卡纵向信息行**（label bodyLarge + value bodyMedium，24dp 行距），含右下角**「复制」按钮**（一键复制全部信息到剪贴板，KernelSU 同款）。新增 `home_info_card.xml` + `InfoCardViewHolder.kt`，删除 `home_info_grid.xml` / `home_info_item.xml` / `InfoGridViewHolder.kt`。

### 4. 间距与 insets 修正（修"看着奇怪"的根因之一）
- **双倍间距**：`Widget.Ksu.Card` 不再设 `contentPadding`（之前与内容内边距叠加成 32dp），内边距由各布局按 Token 控制。
- **底部避让**：`internal_fragment_bottom_insets` 从 0 改为 `0x50`（Gravity.BOTTOM），内容区让开手势/三键导航条；四个 Tab 列表底部统一预留 `ksu_content_bottom_padding`（112dp），末项可完整滚到胶囊上方。
- 注意：`preference_recyclerview.xml` 刻意**不带** `app:fitsSystemWindowsInsets`——RikkaX WindowInsetsHelper 每次分发 insets 都会用"初始 padding + inset"重写 padding，会覆盖代码设置的底部避让。

### 5. 残留清理（第二轮）
- 旧卡片色 `@color/home_card_background_color` 6 处引用全部替换为 `?colorSurfaceContainer`（about_dialog / fragment_activation ×3 / item_activation_target / home_item_container）。
- `home_item_container.xml` 改用 `?ksuCardStyle`，首页所有卡片同属一套 Token。
- 删除无用资源：`home_primary_elevation`、`home_info_title`、`ksu_nav_height`、`ksu_nav_item_pill_padding`、`ksu_info_grid_spacing`。
- 新增 Token：`ksu_card_padding_large`（24dp）、`ksu_info_row_spacing`（24dp）；`ksu_content_bottom_padding` 提升为 112dp。

---

## 一、怎么用（用户视角）

| 功能 | 入口 |
|---|---|
| **切换界面风格（M3E / Miuix）** | 设置 → 用户界面 → **界面风格** → 选 Material 3 Expressive 或 Miuix（小米风）。切换后界面立即重建，圆角与卡片背景随之改变（M3E 28dp / Miuix 20dp）。 |
| 悬浮胶囊导航 | 主界面底部，圆角胶囊、与屏幕底部留间距，手势导航手机上悬浮于手势条上方。 |
| 首页信息卡 | 首页状态大卡下方新增「设备信息」卡网格：Shizako 版本 / Android / ABI / 服务状态 / Dhizuku / 已授权应用数。 |
| 主题色 / 动态取色 | 设置 → 用户界面（原有，未改动）。 |
| 日志 Tab 显隐 | 设置 → 用户界面（原有，未改动）。 |

---

## 二、改了什么（按模块）

### 1. 双风格主题骨架（M3E / Miuix 可切换）
- 新建 `res/values/design_tokens.xml`：统一设计 Token（页面边距 / 卡片间距 / 内边距 / 悬浮导航尺寸）。
- 新建 `res/values/themes_miuix.xml`：`ThemeOverlay.Miuix`，覆盖形状系统（20dp 圆角）+ 分组背景 + Miuix 卡片样式。
- `res/values/attrs.xml`：新增 `ksuCardStyle`、`ksuSectionBackground` 两个语义化属性。
- `res/values/styles.xml`：新增 `ShapeAppearance.Ksu.{Medium,Large}`（M3E 28dp）、`ShapeAppearance.Ksu.Miuix.{Medium,Large}`（20dp）、`Widget.Ksu.Card{,.Miuix}`。
- `res/values/themes.xml`：两套基础主题注入统一形状 + `ksuCardStyle` + `ksuSectionBackground`（M3E 默认）。
- `ThemeHelper.java`：新增 `KEY_UI_STYLE`、`getUiStyle()`、`isUsingMiuix()`。
- `AppActivity.kt`：`computeUserThemeKey()` 纳入 ui_style；`onApplyUserThemeResource()` 在色板之后叠加 `ThemeOverlay.Miuix`。
- `settings.xml` / `SettingsFragment.kt` / `arrays.xml` / `strings.xml`(中英)：新增「界面风格」设置项，切换后 recreate 生效。

> 实现要点：卡片圆角不再硬编码，统一跟随主题 `shapeAppearanceMediumComponent`，因此**切换 M3E/Miuix 即全局切换圆角与卡片背景**，一处定义全局生效。

### 2. 悬浮胶囊底部导航（KernelSU 标志性）
- 新建 `res/layout/view_floating_nav.xml`：圆角胶囊 `MaterialCardView` 包裹**透明背景** `NavigationBarView` —— 完整复用原有 menu / 选中态 / 徽标 / `removeItem` 逻辑。
- `res/layout/activity_main.xml`：移除贴底 `BottomNavigationView`，改为 `include` 悬浮胶囊，内容区避让。
- `MainActivity.kt`：新增 `applyNavCapsuleInsets()`，在 16dp 基础上叠加手势导航条高度，使胶囊悬浮于手势条上方；徽标与日志 Tab 显隐逻辑不变。

### 3. 首页 KernelSU 式重组
- 新建 `res/layout/home_info_item.xml`、`res/layout/home_info_grid.xml`、`home/InfoGridViewHolder.kt`：2 列信息卡网格（Shizako 版本 / Android / ABI / 服务状态 / Dhizuku / 授权应用数）。
- `home/HomeAdapter.kt`：新增 `ID_INFO`，在状态大卡之后插入信息卡网格；**保留**原有全部功能卡（应用管理 / 终端 / 激活 / 启动方式）。
- `home_server_status.xml`：状态大卡移除硬编码 28dp，圆角跟随主题。

> 方案优化：信息卡用 `?ksuCardStyle` 驱动，Miuix 下自动变为 20dp 圆角 + 分组背景，故**无需单独的 Miuix 首页布局**，一套布局双风格。

### 4. 全局卡片圆角统一
移除以下文件中的硬编码 `cardCornerRadius`，全部改为跟随主题：`about_dialog.xml`、`fragment_activation.xml`(3 处)、`home_item_container.xml`（所有 home 卡片容器）、`item_activation_target.xml`、`setup_activity.xml`、`styles.xml` 的 `FilledCard`。

### 5. 残留清理（"无残留"）
- 删除零引用布局：`preference_recyclerview.xml`、`shell_dialog.xml`。
- 注释残留 `LSPosed` → `KernelSU`（14 处，含 layout/java/values）。
- 命名残留：5 个已是 Fragment 却仍叫 `activity_*` 的布局重命名为 `fragment_*`，并同步 ViewBinding 引用：
  - `activity_activation` → `fragment_activation`
  - `adb_pairing_tutorial_activity` → `fragment_adb_pairing_tutorial`
  - `starter_activity` → `fragment_starter`
  - `terminal_tutorial_activity` → `fragment_terminal_tutorial`
  - `dhizuku_apps_activity` → `fragment_dhizuku_apps`
- “Shizuku” 文案经甄别属**上游归属声明**与**“Shizuku 模式”产品术语**（README 同款），非残留，予以保留。

---

## 三、本地编译验证（重要）

沙箱**没有 Android SDK，本次改动只做了静态校验**（XML 语法、资源引用闭环、ViewBinding 一致性、无残留均已通过），**尚未经过真实编译**。请务必本地验证：

```bash
cd Shizako-KernelSU-UI
./gradlew :manager:assembleDebug
# 产物：manager/build/outputs/apk/debug/shizako-v<version>-debug.apk
```

> 需要 JDK 17+、Android SDK（compileSdk 36）、NDK 29.0.13113456。仓库已配阿里云 Maven 镜像。

**如有报错请把报错发回**，最可能出现的几类及对策：

| 可能报错 | 原因 | 对策 |
|---|---|---|
| `colorSurfaceContainerHigh` 找不到 | 个别 `materialThemeBuilder` 版本未生成该色 | 把 `view_floating_nav.xml` 与 `Widget.Ksu.Card` 中的 `?colorSurfaceContainerHigh` 降级为 `?colorSurfaceContainer` |
| 某个 dimen/style 找不到 | 静态校验未覆盖到库资源 | 发回报错，按需补定义 |
| Kotlin 编译错（InfoGridViewHolder / MainActivity） | 个别 API 签名差异 | 发回报错定位修复 |

## 四、验收清单

| 检查点 | 预期 |
|---|---|
| 设置 → 界面风格 | 可在 M3E / Miuix 间切换，切换后界面重建、圆角与卡片背景变化 |
| 底部导航 | 悬浮圆角胶囊，与屏幕底部有间距；手势条手机上在其上方；应用管理 Tab 徽标正常 |
| 首页 | 状态大卡 + 设备信息卡网格（6 项数据正确） |
| 卡片圆角 | 全套卡片统一（M3E 28dp / Miuix 20dp），无个别突兀圆角 |
| 主题色 / 动态取色 / 日志 Tab 显隐 | 原有功能不受影响 |
| `./gradlew :manager:assembleDebug` | 编译通过 |

## 五、已知说明与后续可选

- **Miuix 为风格近似**：View/XML 无现成 Miuix 库，用「主题 + 卡片样式」逼近小米风（大圆角分组卡片），非像素级复刻；M3E 为主线做精。
- **首页 Miuix 专属分组布局**（如需要更强的小米分组列表观感）可作为后续增强。
- **悬浮导航选中态动画**（KernelSU 弹簧动画）、**授权弹窗按钮圆角**统一，可作为后续打磨点。
