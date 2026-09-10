# Shizako · KernelSU 风格 UI 重构 — AI 续做交接文档

> **写给下一个接手的 AI 模型**：本文档 = 项目现状 + 设计系统架构 + 血泪陷阱清单 + 待办任务 + 可直接粘贴的提示词。
> 读完后你应能无缝继续，不需要重新探索整个仓库。
> 截至 2026-09-10，第二轮 UI 重构已完成并**通过全部静态校验**（207 个 XML 语法 + 资源引用闭环），
> 但**尚未经过真实编译**（沙箱无 Android SDK）——你的第一优先任务是协助完成本地编译验证。

---

# 第一部分：项目关键事实（不要推翻）

| 事实 | 值 |
|---|---|
| 仓库 | Shizako（fork 自 Shizuku 13.x），manager 模块路径 `manager/` |
| 包名 / applicationId | `moe.shizuku.manager` / `com.churan.shizako` |
| UI 技术栈 | **View/XML（非 Compose）**、Material 1.12.0、RikkaX 全家桶、Kotlin、ViewBinding（已开启） |
| minSdk / targetSdk | 24 / 36（JDK 17+、NDK 29.0.13113456） |
| 主题生成 | Gradle 插件 `dev.rikka.tools.materialthemebuilder` 构建期生成 `Theme.Material3.*.Shizuku` + 20 个色板 ThemeOverlay，种子色 **#FF9CA8（Sakura 粉）** |
| 导航架构 | 单 Activity（`MainActivity`）+ Jetpack Navigation，4 顶层 Tab + 5 个次级页 destination（见 `res/navigation/main_nav.xml`） |
| 设计参照 | KernelSU 官方 manager（`tiann/KernelSU` 的 Home.kt）+ KernelSU-Next 玻璃底栏 |
| 编译命令 | `./gradlew :manager:assembleDebug`（仓库已配阿里云 Maven 镜像） |

**重要背景**：本项目 UI 经过两轮重构。第一轮把独立 Activity 群改为单 Activity + Tab 架构、LSPosed 风格转 KernelSU 风格；第二轮（本轮）修复第一轮半成品问题（崩溃、实心底栏、自创网格卡、双倍间距）。两轮的全部细节记录在 `KernelSU-UI重构说明.md`。

---

# 第二部分：设计系统架构（改了要守规矩）

## 2.1 设计 Token（唯一事实来源）

`res/values/design_tokens.xml` —— 所有页面/卡片/导航尺寸**必须**引用这里，禁止硬编码：

| Token | 值 | 用途 |
|---|---|---|
| `ksu_page_margin` | 16dp | 页面左右边距 |
| `ksu_card_spacing` | 12dp | 卡片垂直间距 |
| `ksu_card_padding` | 16dp | 卡片常规内边距 |
| `ksu_card_padding_large` | 24dp | 大卡内边距（KernelSU 状态卡/信息卡参照值） |
| `ksu_info_row_spacing` | 24dp | 信息卡行距 |
| `ksu_nav_margin_horizontal/bottom` | 16dp | 悬浮导航边距 |
| `ksu_nav_corner_radius` | 32dp | 导航胶囊圆角 |
| `ksu_nav_height` | 48dp | 纯图标导航栏高度（无文字标签，第三轮新增） |
| `ksu_nav_pill_width/height/corner_radius` | 56/32/16dp | 选中项滑动 pill 尺寸（第三轮新增） |
| `ksu_content_bottom_padding` | 104dp | 列表底部避让亚克力导航（第三轮：112→88→104，厚手势条机型防遮挡） |

## 2.2 双风格切换（M3E / Miuix）

- 入口：设置 → 用户界面 → 界面风格。`ThemeHelper.KEY_UI_STYLE` 存储，`AppActivity.onApplyUserThemeResource()` 在色板后叠加 `ThemeOverlay.Miuix`（`res/values/themes_miuix.xml`）。
- **实现核心**：圆角不硬编码——主题注入 `shapeAppearanceMediumComponent`（M3E 28dp / Miuix 20dp），卡片样式走语义属性 `?ksuCardStyle`（M3E → `Widget.Ksu.Card` / Miuix → `Widget.Ksu.Card.Miuix`，见 `themes.xml` 与 `themes_miuix.xml`）。**新卡片一律用 `style="?ksuCardStyle"`，不要写死圆角和底色。**
- `Widget.Ksu.Card` **故意不设 contentPadding**（见陷阱 T4）。

## 2.3 亚克力悬浮导航（本轮核心，勿回退）

```
activity_main.xml
└─ CoordinatorLayout (root)
   └─ ConstraintLayout
      ├─ BlurTarget (blur_target)          ← 模糊快照根，全高
      │  └─ FragmentContainerView (NavHost, match_parent 全高)
      └─ include view_floating_nav (nav_capsule)  ← 后声明，浮于内容上
         └─ BlurView (nav_blur)            ← 实时模糊 + 罩层
            └─ BottomNavigationView (nav, 透明背景)
```

- `BlurView`/`BlurTarget` 是项目内置的 Dimezis/BlurView（`java/moe/shizuku/blurview/`，Apache-2.0），与 `AppBarActivity` 磨砂顶栏同一套。API 31+ 硬件渲染 / 29-30 OpenGL / 24-28 RenderScript 自动降级。
- 接线在 `MainActivity.setupAcrylicNav()`：`setupWith(blurTarget) + setFrameClearDrawable(window.decorView.background) + setBlurRadius(20dp)`。
- 罩层色 `nav_frost_scrim`（`values/colors.xml` #B3FFFFFF / `values-night` #B3171B21）；胶囊外形 `drawable/nav_capsule_background.xml`（透明填充 + `?colorOutlineVariant` 细描边 + 圆角，`clipToOutline` 裁切模糊）。
- `MainActivity.applyNavCapsuleVisibility()`：次级页（activation/adb_pairing/terminal/starter/dhizuku）自动隐藏底栏（KernelSU 行为），顶层 4 Tab 显示。
- 四个 Tab 列表底部 padding = `ksu_content_bottom_padding`（代码设置：`HomeFragment`/`AppsFragment`/`LogsFragment`/`SettingsFragment`），配合 insets（见 2.4）保证末项不被胶囊遮挡。

## 2.4 RikkaX insets 系统（容易踩坑，先读懂再改）

`res/values/integer.xml` 的整数是 **android.view.Gravity 位掩码**（RikkaX insets 库复用 Gravity 常量）：

| 整数 | 值 | 含义 |
|---|---|---|
| `internal_fragment_insets` | `0x00800007` | start\|end（含 RELATIVE_LAYOUT_DIRECTION 位） |
| `internal_fragment_top_insets` | `0x30` | Gravity.TOP |
| `internal_fragment_bottom_insets` | `0x50` | Gravity.BOTTOM（本轮从 0 改为此值：内容区让开手势/三键导航条） |

布局中 `app:fitsSystemWindowsInsets="@integer/xxx"` = 把对应 inset 加为该 view 的 padding；`app:consumeSystemWindowsInsets` = 消费不再下传。

## 2.5 首页结构（KernelSU 原版布局，勿自创）

`HomeAdapter` 卡片顺序：`状态大卡(ServerStatusViewHolder)` → `信息卡(InfoCardViewHolder)` → 功能卡（应用管理/终端/激活/启动方式，条件添加）→ LearnMore。

- **状态卡** `home_server_status.xml`：tonal 容器，运行中 `secondaryContainer` / 未运行 `errorContainer`，由 `ServerStatusViewHolder.applyContainerColors()` 用 `MaterialColors.getColor()` 动态切换（含图标/文字 on* 前景色）。
- **信息卡** `home_info_card.xml`：单卡纵向 label(bodyLarge)/value(bodyMedium) 行 + 右下角复制按钮（KernelSU `InfoCard` 同款，`InfoCardViewHolder.copyAll()`）。
- **功能卡**：容器 `home_item_container.xml`（`?ksuCardStyle`）+ 内容 `home_*_item.xml`（`?homeCardStyle` 内边距 + CardIcon 圆形图标 chip）。

---

# 第三部分：血泪陷阱清单（改动前必读！！）

> 每一个都是真实炸过的雷。违反任意一条都可能造成崩溃或视觉回退。

- **T1 `preference_recyclerview.xml` 不是"零引用"！** androidx.preference 库的 `preference_list_fragment.xml` 会 `include @layout/preference_recyclerview`，应用层同名文件是**覆盖库实现的挂点**。删除它 → 设置页 `ClassCastException: RecyclerView cannot be cast to BorderRecyclerView`（真实崩溃日志见仓库外 `Shizako-crash-20260910-173407.txt`）。**同理：删除任何看似无用的布局前，先想它会不会是库的覆盖点**（同名覆盖是 Android 资源合并机制，grep 不到引用）。
- **T2 `NavigationBarView` 是抽象类**，没有 `(Context, AttributeSet)` 两参构造器，XML 里写它会 `NoSuchMethodException` 启动即崩。必须用具体子类 `BottomNavigationView`（代码里向上转型不受影响）。
- **T3 RikkaX WindowInsetsHelper 会重写 padding**：挂了 `app:fitsSystemWindowsInsets` 的 view，每次 insets 分发都会 `setPadding(初始padding + inset)`，**覆盖你在代码里 later 设置的 padding**。要运行时改 padding 必须用 `rikka.insets.setInitialPadding()` 系列；或者像 `preference_recyclerview.xml` 那样干脆不挂该 attr。
- **T4 `Widget.Ksu.Card` 不要加 contentPadding**：各布局内容已有自己的内边距（Token 控制），样式里再加会叠加成双倍间距（32dp，第一轮"看着奇怪"的根因之一）。
- **T5 `@color/home_card_background_color` 来自 RikkaX 库**（项目 res 里 grep 不到定义是正常的），旧 Shizuku 视觉残留，已全部替换为 `?colorSurfaceContainer`，**不要再引入**。
- **T6 drawable XML 里引用主题属性**（如 `?colorOutlineVariant`）要求 API 21+，本项目 minSdk 24 安全，但别给更低版本的项目照搬。
- **T7 `window.decorView.background` 即主题的 `windowBackground`**（`@drawable/window_bg`），BlurView 的 `setFrameClearDrawable` 依赖它保证磨砂层不透出黑底，改主题背景时注意。
- **T8 字符串改动要同步 `values/`（英文默认）和 `values-zh-rCN/`**，其他 60+ 语言目录不用管（缺失自动回退英文）。新字符串只加这两个文件。
- **T9 ViewBinding 类名跟随布局文件名**：重命名/新建布局后，引用它的 Kotlin 代码里的 Binding 类名要同步（本轮 `HomeInfoGridBinding` → `HomeInfoCardBinding`）。改完全局 grep 旧类名确认零引用。

---

# 第四部分：本轮（第二轮）改动清单

**新增**：`res/layout/preference_recyclerview.xml`（恢复，T1）、`res/layout/home_info_card.xml`、`res/drawable/nav_capsule_background.xml`、`home/InfoCardViewHolder.kt`、本文档。

**删除**：`res/layout/home_info_grid.xml`、`res/layout/home_info_item.xml`、`home/InfoGridViewHolder.kt`（自创 2 列网格，KernelSU 无此布局）。

**修改**：
- 亚克力导航：`view_floating_nav.xml`（BlurView 化）、`activity_main.xml`（BlurTarget + 内容全高）、`MainActivity.kt`（`setupAcrylicNav()` + `applyNavCapsuleVisibility()`）
- 首页 KernelSU 化：`home_server_status.xml`（tonal）、`ServerStatusViewHolder.kt`（`applyContainerColors()`）、`HomeAdapter.kt`（换 InfoCardViewHolder）
- Token/资源：`design_tokens.xml`（重构）、`colors.xml` ×2（nav_frost_scrim）、`dimens.xml`（删 home_primary_elevation）、`integer.xml`（bottom insets）、`styles.xml`（Widget.Ksu.Card 去 contentPadding）、`strings.xml` ×2（信息卡标签 + 复制文案，删 home_info_title）
- 底部避让：`HomeFragment.kt`、`AppsFragment.kt`、`LogsFragment.kt`、`SettingsFragment.kt`（各加 `updatePadding(bottom = ksu_content_bottom_padding)` + import）
- 残留清理：`home_item_container.xml`（?ksuCardStyle）、`about_dialog.xml` / `fragment_activation.xml` / `item_activation_target.xml`（旧色 → ?colorSurfaceContainer）

---

# 第五部分：待办任务（按优先级）

## P0 本地编译验证（必须最先做）
沙箱无 Android SDK，全部改动只做过静态校验。在真机/本机执行：
```bash
cd Shizako-KernelSU-UI
./gradlew :manager:assembleDebug
# 产物：manager/build/outputs/apk/debug/shizako-v<version>-debug.apk
```
可能的报错与对策见 `KernelSU-UI重构说明.md` 第三节。修复后跑一遍真机验收清单（同文档第四节）。

## P1 悬浮导航选中态弹簧动画 —— ✅ 已完成（第三轮）
已实现 KernelSU-Next 同款果冻弹簧 pill：共享滑动 pill（`nav_active_pill`）+ `itemActiveIndicatorEnabled=false` 关闭 M3 静态指示器；`MainActivity.setupNavPill()` 用 `androidx.dynamicanimation`（material 传递依赖）驱动弹簧滑动（0.5/200）、挤压回弹与图标弹跳；由 OnDestinationChanged 驱动，`menu/removeItem/getOrCreateBadge` 逻辑未触碰。细节见《KernelSU-UI重构说明.md》第三轮段落。

## P2 授权弹窗（GrantPermissions）按钮圆角统一
`styles.xml` 的 `GrantPermissionsButtons.*` 仍是旧直角分段样式（`grant_permissions_buttons_top/bottom` drawable），可改为跟随主题 `shapeAppearanceMediumComponent`。

## P3 Miuix 首页专属分组布局
当前 Miuix 靠 `?ksuCardStyle` 逼近小米分组卡片观感。如需更强的小米分组列表（组间无间距、组内分割线、大圆角组容器），可在 `ThemeOverlay.Miuix` 下加分组专用样式 + 首页条件布局。

## P4 低性能设备 BlurView 降级检查
`BlurView` 在 API 24-28 走 RenderScript（已弃用但可用）。如遇低端机卡顿，可调大 `setupWith` 的 scaleFactor（降采样）或提供"关闭模糊"设置项（改回 `colorSurfaceContainerHigh` 实心胶囊即可，`view_floating_nav.xml` 一处改动）。

## P5 真机回归点
- 深色模式下亚克力导航罩层观感（`nav_frost_scrim` 暗色值可按需微调透明度）
- 三键导航机型：胶囊应浮于按键条上方、列表末项不被遮挡
- M3E/Miuix 切换后状态卡/信息卡圆角跟随变化
- 设置页打开不再崩溃（T1 修复点）、设置列表底部避让正常

---

# 第六部分：可直接粘贴给下一个模型的提示词

````text
你是 Android 开发助手，接手 Shizako（fork 自 Shizuku）manager 模块的 UI 重构续做。

【第一步·必读】先完整阅读仓库根目录的《AI续做交接文档.md》和《KernelSU-UI重构说明.md》，
特别是"血泪陷阱清单"（T1-T9），然后告诉我你的理解，不要直接动手。

【关键事实】
- View/XML 技术栈（非 Compose），Material 1.12.0，RikkaX 库，Kotlin + ViewBinding，minSdk 24
- 单 Activity（MainActivity）+ Navigation，4 Tab + 5 次级页（res/navigation/main_nav.xml）
- 设计 Token 集中在 res/values/design_tokens.xml；新卡片用 style="?ksuCardStyle"，禁止硬编码圆角/卡片色
- 亚克力悬浮导航用项目内置 moe.shizuku.blurview.BlurView，接线在 MainActivity.setupAcrylicNav()
- 沙箱/你的环境如果没有 Android SDK 就不能编译，只能静态校验（XML 语法 + 资源引用闭环 + ViewBinding 一致性），编译验证由我在本地做
- 字符串改动只同步 values/ 和 values-zh-rCN/ 两个文件

【本轮任务】（按交接文档第五部分的优先级执行，当前做 P__）
（在此描述你要它做的具体任务）

【约束】
- 不要推翻已完成的两轮重构架构；新增代码风格与现有一致（中文注释、Token 引用）
- 每改一个文件，说明改动理由；改完做静态校验并列出校验结果
- 不确定是不是库的覆盖点（布局/dimen/样式）时，先问我再删
````

---

# 附：静态校验速查脚本

```bash
cd manager/src/main

# 1. 全部 XML 语法校验
python3 -c "
import xml.etree.ElementTree as ET, glob
errs = []
for f in glob.glob('res/**/*.xml', recursive=True):
    try: ET.parse(f)
    except Exception as e: errs.append(f'{f}: {e}')
print(f'{len(errs)} errors'); [print(e) for e in errs]
"

# 2. 残留扫描（按需替换关键词）
grep -rn "home_card_background_color\|home_info_grid\|home_info_item\|InfoGridViewHolder\|home_info_title" res/ java/

# 3. 删除某文件前：确认不是库覆盖点 + 全局零引用
grep -rn "文件名（不含扩展名）" res/ java/ ../../
```
