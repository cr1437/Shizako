# 编译与参与开发

> 这份文档同时是官网「自己编译」页的源文件（由 `tools/md2html.py` 生成）。

## 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | **17 及以上** | 构建脚本与 AGP 需要；JDK 21 亦可 |
| Android SDK | **compileSdk 36** | 需要 `platforms;android-36` 与 `build-tools;36.0.0` |
| NDK | **29.0.13113456** | `manager` / `rish` 里含 JNI 代码，缺了会在 CMake 阶段失败 |
| Gradle | 随仓库的 wrapper | 不要用系统里的旧 Gradle |

## 克隆与编译

```bash
git clone https://github.com/cr1437/Shizako.git
cd Shizako

# 只编译管理器应用（最常用）
./gradlew :manager:assembleRelease

# 调试包（启动更快、带启动耗时打点）
./gradlew :manager:assembleDebug
```

产物位置：

```
manager/build/outputs/apk/release/shizako-<版本>-release.apk
manager/build/outputs/apk/debug/shizako-<版本>-debug.apk
```

## 关于签名（重要）

仓库里的 `signing.gradle` 会读取**根目录的 `signing.properties`**（该文件不入库）：

```properties
KEYSTORE_FILE=../shizako.jks
KEYSTORE_PASSWORD=你的口令
KEYSTORE_ALIAS=shizako
KEYSTORE_ALIAS_PASSWORD=你的口令
```

- **没有这个文件时会自动回退到 debug 签名**，自己玩足够；
- 但**发布版必须使用项目签名**，否则用户无法覆盖升级（必须卸载重装、数据丢失）。
- 也就是说：想给别人安装的包，请务必配上 `signing.properties`。

## 代码结构

```
Shizako/
├── manager/    Android 应用本体（Kotlin + Jetpack Compose，176 个源文件）
├── server/     特权服务进程（Java，跑在 shell / root 身份下）
├── starter/    服务启动器（负责拉起 server 并校验）
├── shell/      预编译的 shell 工具
├── stub/       兼容占位包（moe.shizuku.privileged.api）
├── common/     共享模块
├── api/        Shizuku-API 客户端库源码（MIT，vendored 进本仓库）
├── docs/       文档、官网与物料（GitHub Pages 直接服务此目录）
└── tools/      维护脚本：徽章/数据刷新、Markdown 转网页、站点部署
```

## 常用维护脚本

| 脚本 | 作用 |
|------|------|
| `tools/update_badges.py` | 拉取真实数据，重新生成 `docs/badge-*.svg` 与 `docs/stats.json`（后者是官网卡片在 api.github.com 被拦时的兜底数据） |
| `tools/md2html.py` | 把 Markdown 文档渲染成官网上的网页（`docs/dev/*.html`） |
| `tools/deploy-site.sh` | 把 `docs/` 静态站点部署到自己的服务器（也可忽略，改用 GitHub Pages） |

## 国内网络

- 仓库已配置阿里云 Maven 镜像，一般不需要额外代理；
- `api.github.com` 在部分网络下会被 DNS 拦掉。官网数据卡片的数字因此走三级：
  HTML 里的兜底值 → 同源 `stats.json`（`badges.yml` 每小时刷新）→
  浏览器直连 GitHub API 的实时值（`docs/assets/site.js`）。实时这一路失败就静默
  停在上一步 —— 页面仍显示最多旧一小时的数字，不会空着，也不会报错。

## 提交改动

1. 尽量一次只做一件事，提交信息写清「改了什么、为什么」；
2. 改了 `docs/*.md` 或 `api/*.md` 之后，跑一次 `python3 tools/md2html.py` 重新生成网页；
3. 涉及签名、权限、包名的改动请**不要**提交 `signing.properties`、`*.jks` 等敏感文件
   （`.gitignore` 已拦住，别用 `-f` 绕过）。
