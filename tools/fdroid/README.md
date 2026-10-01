# 上架 F-Droid：准备清单与拦路虎

> 这份文档记录把 Shizako 提交到 F-Droid 需要做的事。**先说结论：现在还不能直接提交**，
> 有两个硬拦路虎必须先解决（见第 2 节）。recipe 草稿见 `com.churan.shizako.yml`。

## 1. 提交方式

F-Droid 不用 GitHub，走 **GitLab**：

1. 注册/登录 <https://gitlab.com>
2. Fork <https://gitlab.com/fdroid/fdroiddata>
3. 把 `com.churan.shizako.yml` 放到 fork 的 `metadata/` 目录
4. 提 Merge Request（模板会要求你自查一遍下面的清单）

## 2. 两个硬拦路虎（必须先解决）

### 2.1 仓库里有预编译二进制 —— 这是 F-Droid 的硬性红线

| 文件 | 问题 |
|---|---|
| `manager/src/main/assets/shizako-stub.apk` | 直接提交的 APK 产物 |
| `manager/src/main/assets/rish_shizuku.dex` | 直接提交的 dex 产物 |

F-Droid 要求**所有二进制都由源码构建**。可选做法：

- **A（推荐）**：把 stub 的源码放进本仓库（或作为 recipe 的 `srclibs`），构建时现编译，
  产物用 `prebuild:` 拷进 assets；`rish_shizuku.dex` 同理（`api/rish` 源码本来就在仓库里，
  让它参与构建即可）。
- **B**：如果不方便，就先移除这两个 assets 对应的功能，等 F-Droid 版本单独处理。

### 2.2 构建环境：离线 + 国内镜像 + NDK 版本

- F-Droid 的构建机**完全离线**，而本仓库的 `settings.gradle`/`build.gradle` 配了**阿里云镜像** ✗
  → recipe 里要么加 `prebuild:` 把它替换成官方源，要么把镜像配置改成「可被环境变量关闭」。
- 项目用 **NDK 29.0.13113456**；需要确认 F-Droid 构建机上有这一版（没有就得降级到他们有的版本）。
- 依赖必须都能从 F-Droid 允许的源获取（Maven Central / Google 仓库）。

## 3. 上架前还要知道的三件事

| 事项 | 影响 |
|---|---|
| **签名不同** | F-Droid 用它自己的密钥签名 APK → 用户**无法从 GitHub 版覆盖升级**（必须卸载重装）。这是 F-Droid 的固有限制，几乎所有应用都这样 |
| **版本号规范** | `versionName` 是 `zako3.12` 这种带前缀的字符串 ✔ 可以，但 recipe 里要写清 `versionCode: 312`，并保证**每次发版递增**（目前是 312 ✔） |
| **与官方 Shizuku 冲突** | 兼容桥占用了官方 provider authority，两者不能共存。F-Droid 审核可能会问，需要在 MR 里**主动说明**这是刻意设计（`README` 里已写明） |

## 4. 审核可能追问的点（提前准备答复）

1. **为什么用自有的权限名** → 上游许可禁止 fork 使用官方 app id 与权限名（`NOTICE` 已声明）
2. **更新检查请求 api.github.com** → 只是查询最新 release 判断有无新版本，不涉及专有服务依赖；
   如需彻底规避，可以做成 F-Droid 版关闭自动更新检查
3. **预编译二进制的来源与构建方式** → 见 2.1 的解决方案

## 5. 建议的执行顺序

1. 先按 2.1 把两个预编译二进制改成「构建时生成」→ 本地跑一次 `:manager:assembleRelease` 验证
2. 按 2.2 处理镜像与 NDK（可以做一个 `fdroid` 构建变体，把这些差异集中进去）
3. 发一个**与 recipe 完全对应的 tag**（例如 `zako3.13`），versionCode 递增
4. 再用 `com.churan.shizako.yml` 提 MR

> 注意：第 1、2 步会动到构建脚本与 assets 布局，属于**会影响现有构建**的改动，
> 建议先在一个分支上做，确认 GitHub 版构建仍然正常（尤其是签名的回退逻辑）再合并。
