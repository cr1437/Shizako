# Shizako-KernelSU-UI 续做交接文档

> 写给下一位接手的大模型 / 开发者。读完本文即可直接开工，无需重新摸索项目结构。
> 日期：2026-09-11 ｜ 代码基线：git commit `83a60d9`

---

## 一、项目是什么

- **Shizako**（包名 `com.churan.shizako`）= Shizuku Manager 的深度定制 fork，UI 全面向 KernelSU / MIUIX 风格重构。
- 单 Activity 架构：`MainActivity` + Jetpack Navigation，4 个顶层 Tab（应用管理 / 首页 / 日志 / 设置，首页为默认）。
- 底栏是亚克力悬浮胶囊：BlurView 实时模糊 + 磨砂罩层，内部是 Material `BottomNavigationView`。
- 激活页 `fragment_activation.xml` 已重构为一站式页面（含 TCP 5555 端口激活）。
- 主题支持 M3E / MIUIX 双风格，通过主题属性（如 `?ksuCardStyle`、`?ksuSectionBackground`）切换。

## 二、当前代码状态（已完成，勿重复做）

git 历史（由旧到新）：

| commit | 内容 |
|---|---|
| `49353af` | init（原始项目 + 早期 UI 重构） |
| `12feac4` | 修复 aapt/kotlin 编译错误，激活页/主页重构 |
| `83a60d9` | 底栏改 Figma 胶囊设计、去掉加号、滑块动画去弹簧 |

`83a60d9` 这次提交做的事：

1. **底栏右侧「+」按钮已彻底移除**（布局里本就不存在，用户旧 APK 是丢失修订版构建的；新包已验证无 `nav_fab` 残留）。
2. **选中指示器 = 全圆角玻璃胶囊**：包裹选中 tab，宽度运行时对齐选中项，切换 Tab 时水平滑动。
3. **动画已去弹簧**：`SpringAnimation` 已全部删除，改为 `ValueAnimator`（280ms，`PathInterpolator(0.2, 0, 0, 1)` 减速曲线），首次出现 160ms 淡入。`springBack()`、`bounceNavIcon()` 等死代码已清理。
4. tab 显示为 24dp 图标 + 10sp 文字标签（`labelVisibilityMode="labeled"`）。

### 关键文件地图

| 文件 | 作用 |
|---|---|
| `manager/src/main/java/moe/shizuku/manager/MainActivity.kt` | 底栏一切逻辑：BlurView 接线、insets 避让、Tab 显隐、徽标、`setupNavPill()` 胶囊滑动动画 |
| `manager/src/main/res/layout/view_floating_nav.xml` | 底栏布局：BlurView ⊃ 胶囊 View(`@+id/nav_active_pill`) + BottomNavigationView(`@+id/nav`) |
| `manager/src/main/res/drawable/nav_active_pill_background.xml` | 胶囊外形：全圆角 + `nav_pill_fill` 薄纱填充 + 1dp `nav_pill_stroke` 描边 |
| `manager/src/main/res/drawable/nav_capsule_background.xml` | 底栏整体外形：透明底 + 细描边，圆角 `@dimen/ksu_nav_corner_radius`(100dp) |
| `manager/src/main/res/values/design_tokens.xml` | 所有尺寸 token 的唯一事实来源（见下） |
| `manager/src/main/res/color/nav_icon_tint.xml` | 图标/标签着色 selector：选中 `?colorPrimary`，未选中 `?colorOnSurfaceVariant` |
| `manager/src/main/res/color/nav_pill_fill.xml` + `color-night/` | 胶囊填充：主题色 18%（夜间 26%） |
| `manager/src/main/res/menu/navigation_menu.xml` | 4 个 tab 的 id/图标/标题 |
| `manager/src/main/res/values/styles.xml` | `TextAppearance.Ksu.NavLabel`（10sp 标签样式） |

### 底栏尺寸 token（design_tokens.xml 当前值）

```xml
<dimen name="ksu_nav_margin_horizontal">16dp</dimen>
<dimen name="ksu_nav_margin_bottom">16dp</dimen>
<dimen name="ksu_nav_corner_radius">100dp</dimen>      <!-- 全圆胶囊 -->
<dimen name="ksu_nav_height">59dp</dimen>              <!-- 底栏总高 -->
<dimen name="ksu_nav_inner_padding">6dp</dimen>        <!-- BlurView 左右内边距 -->
<dimen name="ksu_nav_tab_capsule_height">47dp</dimen>  <!-- 选中胶囊高 = 59-2×6 -->
<dimen name="ksu_nav_tab_icon_size">24dp</dimen>
<dimen name="ksu_nav_pill_stroke_width">1dp</dimen>
<dimen name="ksu_content_bottom_padding">115dp</dimen> <!-- 列表底部避让 -->
```

### 胶囊滑动动画核心逻辑（MainActivity.setupNavPill）

- 胶囊与 `nav` 同为 BlurView 直接子视图，目标位置 = `nav.left + itemView.left`，宽度 = `itemView.width`。
- 切 Tab：`pillAnimator`（ValueAnimator）从当前 `translationX` 滑到目标，连点 cancel 续滑。
- 首次定位 / 旋转 / 日志 Tab 显隐：`snapToSelection()` 无动画对齐。
- 常量：`PILL_SLIDE_DURATION = 280L`、`PILL_FADE_IN_DURATION = 160L`、`PILL_INTERPOLATOR = PathInterpolator(0.2, 0, 0, 1)`。

---

## 三、待办需求（用户最新反馈，未实现）

用户在真机上看了 `83a60d9` 的包后反馈：

### 需求 1：底栏只显示图标，不要文字标签

改动点（都在 `view_floating_nav.xml` 和 `design_tokens.xml`）：

1. `BottomNavigationView` 上：`app:labelVisibilityMode="labeled"` → `"unlabeled"`。
2. 可删掉 `app:itemTextAppearanceActive` / `app:itemTextAppearanceInactive` / `app:itemTextColor` 三行及 `styles.xml` 里的 `TextAppearance.Ksu.NavLabel`（删引用即可，样式留着也不报错）。
3. 高度收拢回纯图标尺寸：`ksu_nav_height` 59dp → **48dp**，`ksu_nav_tab_capsule_height` 47dp → **36dp**（48-2×6），`ksu_content_bottom_padding` 115dp → **104dp**。
4. 胶囊宽度逻辑不用改（仍取 itemView 宽度，等分恒宽）。

### 需求 2：用户仍感觉有弹簧动画，要求"平滑过渡"

代码里确实已无任何 SpringAnimation，但用户仍有弹性观感，按以下顺序排查：

1. **先换更朴素的曲线和更短时长**：`PILL_INTERPOLATOR` 改为 `PathInterpolator(0.4f, 0f, 0.2f, 1f)`（M3 标准曲线，起停都更柔和）或直接 `LinearInterpolator`；`PILL_SLIDE_DURATION` 降到 200~240ms 试手感。
2. **检查 fragment 转场动画**：`res/anim/fragment_enter.xml` / `fragment_exit.xml` / `*_pop.xml`，若插值器带 overshoot/cycle 类效果一并换掉。
3. **Material 自身的 item 选中动画**：`BottomNavigationView` 切换 item 时图标 tint 有默认渐变动效，属正常反馈，一般不用动；如用户指的就是它，可在 selector 层面或 `itemRippleColor` 上收敛。
4. **BlurView 帧率**：低端机上模糊刷新慢会显得"黏"，属硬件表现，不是动画问题。
5. 铁律：**不要重新引入 `androidx.dynamicanimation` 的 SpringAnimation**。

---

## 四、构建指南（沙箱从零起步）

沙箱每次会话会清空非 `/workspace` 目录，JDK/SDK/Gradle 都要重装。全程约 10~15 分钟。

```bash
# 0. 解压源码（zip 在 /workspace）
mkdir -p /data/user/work/shizako && cd /data/user/work/shizako
unzip -oq /workspace/Shizako-KernelSU-UI-v2.12-nav.zip

# 1. JDK 21（必须 21，项目 sourceCompatibility = VERSION_21；17 不行）
cd /tmp && curl -sL -o jdk21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse" \
  && tar xzf jdk21.tar.gz   # 得到 jdk-21.x 目录

# 2. Android SDK（cmdline-tools 从 dl.google.com 可直连）
#    需要：platform-tools、platforms;android-36、build-tools;36.0.0、
#          ndk;29.0.14206865（root build.gradle 写死 ndkVersion）、cmake;3.31.6
#    装到 /opt/android-sdk（local.properties 里 sdk.dir 已指向它）

# 3. Gradle 8.14：wrapper 下载在沙箱里会 SSL 失败，
#    直接 curl 下载 https://mirrors.cloud.tencent.com/gradle/gradle-8.14-bin.zip
#    解压到 /opt/gradle-8.14，用 /opt/gradle-8.14/bin/gradle 代替 ./gradlew

# 4. ★ 关键坑：沙箱出口走 HTTP 代理 127.0.0.1:18080，
#    curl 读环境变量没问题，但 Java/Gradle 不读！必须写：
cat > /root/.gradle/gradle.properties <<'EOF'
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=18080
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=18080
systemProp.http.nonProxyHosts=localhost|127.0.0.1
systemProp.https.nonProxyHosts=localhost|127.0.0.1
EOF
#    不配的话 Gradle 会卡在 "Evaluating project ':aidl'" 无限挂起（缓存不增长就是这个问题）

# 5. 构建（首次下载依赖约 1.1GB，含 NDK 原生编译，约 4~5 分钟）
export JAVA_HOME=/tmp/jdk-21.x && export PATH=$JAVA_HOME/bin:$PATH
export ANDROID_HOME=/opt/android-sdk
cd /data/user/work/shizako/Shizako-KernelSU-UI
/opt/gradle-8.14/bin/gradle :manager:assembleDebug --console=plain

# 产物：manager/build/outputs/apk/debug/shizako-zako3.01-debug.apk
```

### 构建相关注意事项

- `versionCode` 由 `git rev-list --count HEAD` 生成（当前 = 3），`versionName = zako3.01`。改代码后记得 `git commit`，versionCode 才会涨。
- **签名**：无 `signing.properties`，走 debug 自动签名。每个新沙箱的 debug 密钥都不同 → 用户安装新包前**必须先卸载旧版**（签名冲突），应用数据会重置。
- 原生代码（`manager/src/main/jni`）用 CMake 编 4 个 ABI，NDK 版本不对会直接配置失败。
- 验证 APK 无某资源：`unzip -p app.apk resources.arsc | strings | grep nav_fab`。

## 五、用户偏好（设计语言）

- 一切动画：平滑、朴素、无回弹，拒绝弹簧/橡皮筋效果。
- 底栏设计基准是 Figma 稿「Home - Bottom」（玻璃胶囊 + 滑动选中指示器）。
- 中文沟通；界面文案以 `values-zh-rCN/strings.xml` 为准，改文案要同步 `values/`（英文）和 `values-zh-rTW/`。
- 尺寸一律引用 `design_tokens.xml`，禁止硬编码。
