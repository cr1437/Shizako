# Shizako 对 Shizuku-API 的本地修改

本目录源自 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)（MIT License），
自 zako2.1 起由 git 子模块改为直接内置，便于随本仓库一起分发与修改。

上游代码保持原样，仅做了以下改动：

| 文件 | 改动 | 原因 |
|------|------|------|
| `provider/src/main/AndroidManifest.xml` | 追加声明 `<uses-permission android:name="com.churan.shizako.permission.API_V23"/>` | 双管理器支持：使用本库的应用同时请求官方 Shizuku 与 Shizako 两个权限，两台服务器各自只推送、只授予自己定义的权限，因此同一份应用可连接任一管理器 |
| `shared/src/main/java/rikka/shizuku/ShizukuApiConstants.java` | 服务端版本常量与 Shizako 版本体系对齐 | 避免"激活后仍显示上游 13.6 版本号" |

## 为什么不直接声明官方权限让官方 API 应用直连？

上游 Shizuku 的许可（Apache 2.0 附加条款）明确禁止 fork 声明
`moe.shizuku.manager.permission.*` 权限；技术上，重复定义同名 runtime 权限也会在
与官方 Shizuku 并存安装时产生"定义归属取决于安装顺序、卸载即失效"的冲突。
因此本库选择"双权限请求"路线：**换用本库编译的应用**可同时兼容两个生态，
未改动的官方 `dev.rikka.shizuku:api` 应用仍按原样连接官方 Shizuku。

## 与上游同步

```bash
git remote add shizuku-api https://github.com/RikkaApps/Shizuku-API.git
git fetch shizuku-api
git diff HEAD shizuku-api/master -- api/   # 人工核对后合并，保留上述两处改动
```
