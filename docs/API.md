# Shizako API 使用指南

> 「想把我也接进你的应用里吗？这篇就是给你的驯服手册喵 (ฅ'ω'ฅ)」—— Shizako酱

本文面向**想把 Shizako（或 Shizuku）能力接入自己应用的开发者**，覆盖依赖配置、权限申请、binder 生命周期、AIDL 用户服务全流程。所有示例均基于本仓库 `api/` 目录的源码（对应上游 Shizuku API v13）编写，并在 Shizako zako2.6 上验证过接口签名。

---

## 目录

1. [两条接入路线](#两条接入路线)
2. [依赖配置](#依赖配置)
3. [Manifest 配置](#manifest-配置)
4. [binder 生命周期](#binder-生命周期)
5. [权限申请流程](#权限申请流程)
6. [AIDL 用户服务（重点）](#aidl-用户服务重点)
7. [`UserServiceArgs` 参数详解](#userserviceargs-参数详解)
8. [能力与状态查询 API](#能力与状态查询-api)
9. [完整示例：带权限检查的连接流程](#完整示例带权限检查的连接流程)
10. [常见坑](#常见坑)

---

## 两条接入路线

| 路线 | 做法 | 能连什么 |
|------|------|---------|
| **A. 官方 Maven 依赖** | `dev.rikka.shizuku:api` + `dev.rikka.shizuku:provider` | Shizako 对官方 API 应用是**免改码直连**的，官方 Shizuku 自然也能连 |
| **B. 本仓库 `api/` 源码依赖** | 把 `api/` 目录作为源码模块引入 | 同上，且应用会**同时声明两个权限**，在两台管理器并存时都能正常授权 |

怎么选：只想用 Shizuku 能力、不在乎管理器是谁 → 选 A；希望明确兼容 Shizako 生态（比如做 Shizako 专属功能、参与双管理器测试）→ 选 B。

两条路线的**代码写法完全一致**，都从 `rikka.shizuku.Shizuku` 这个入口开始。

## 依赖配置

### 路线 A：官方 Maven 依赖

```gradle
dependencies {
    implementation "dev.rikka.shizuku:api:13.1.5"
    implementation "dev.rikka.shizuku:provider:13.1.5"
}
```

（版本号为撰写时的 13.x 最新版，任何 13.x 均可；Shizako 服务端实现的协议就是 v13。）

### 路线 B：本仓库源码依赖

```gradle
// settings.gradle
include ':aidl', ':shared', ':api', ':provider'
project(':aidl').projectDir = file('api/aidl')
project(':shared').projectDir = file('api/shared')
project(':api').projectDir = file('api/api')
project(':provider').projectDir = file('api/provider')

// app/build.gradle
dependencies {
    implementation project(':api')
    implementation project(':provider')
}
```

源码路线的区别在 `api/provider/src/main/AndroidManifest.xml`：它同时声明了官方权限 `moe.shizuku.manager.permission.API_V23` 和 Shizako 权限 `com.churan.shizako.permission.API_V23`。每个管理器只授予自己定义的那个权限，所以同一份 APK 装在只有官方 Shizuku、只有 Shizako、或两者并存的设备上都能工作。

## Manifest 配置

无论哪条路线，**你自己的应用**都要在 `AndroidManifest.xml` 里声明接收 binder 的 provider：

```xml
<application ...>

    <provider
        android:name="rikka.shizuku.ShizukuProvider"
        android:authorities="${applicationId}.shizuku"
        android:multiprocess="false"
        android:exported="true"
        android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />

</application>
```

四个属性都有讲究：

| 属性 | 值 | 原因 |
|------|-----|------|
| `authorities` | `${applicationId}.shizuku` | 服务端按“包名 + `.shizuku`”拼出 authority 来推送 binder |
| `exported` | `true` | 服务端跑在 adb/root 身份下，必须能跨进程访问此 provider |
| `multiprocess` | `false` | 服务端只在应用进程启动时投递一次，多进程会导致其他进程拿不到 |
| `permission` | `INTERACT_ACROSS_USERS_FULL` | 普通 App 没有此权限，只有 shell/root 能调 —— 防止别人伪造投递 |

## binder 生命周期

Shizako 服务端会在你的应用进程启动时把 binder 塞进 `ShizukuProvider`。你的代码要做的是监听“收到 / 死亡”两个事件：

```java
// 收到 binder（sticky：注册时若 binder 已在，会立刻回调一次）
Shizuku.addBinderReceivedListenerSticky(() -> {
    // binder 可用，可以开始 checkSelfPermission / bindUserService 了
});

// binder 死亡（用户停掉了 Shizako 服务、设备重启等）
Shizuku.addBinderDeadListener(() -> {
    // 提示用户重新启动服务，禁用相关功能入口
});
```

> `Sticky` 版本和普通版本的区别：sticky 在注册当下如果 binder 已经收到过，立即触发回调，适合在 `onCreate` 里注册；普通版只在状态**变化**时回调。带 `Handler` 参数的重载可以指定回调线程。

主动检查用：

```java
if (Shizuku.pingBinder()) {   // 轻量探测，binder 存活且可用
    ...
}
IBinder b = Shizuku.getBinder(); // 拿原始 binder（transactRemote 高级用法才需要）
```

## 权限申请流程

权限模型和 Android runtime permission 类似：**先查，没有再申请，结果走回调**。

```java
if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
    // 已授权，直接干活
} else if (Shizuku.shouldShowRequestPermissionRationale()) {
    // 用户之前拒绝过，先解释一下你要用特权干嘛
} else {
    Shizuku.requestPermission(requestCode); // 弹出 Shizako 的授权对话框
}
```

结果回调：

```java
Shizuku.addRequestPermissionResultListener((requestCode, result) -> {
    if (resultCode == PackageManager.PERMISSION_GRANTED) { ... }
});
```

> ⚠️ 在官方 Shizuku 上授权过 ≠ 在 Shizako 上授权过。两个管理器各自维护授权表，切换管理器后第一次使用会重新弹窗 —— 这是设计使然，不是 bug。

## AIDL 用户服务（重点）

用户服务（UserService）是这套 API 最强大的部分：**你写一个 AIDL 接口 + 实现类，Shizako 会把它的副本放进特权进程里运行**，从此你拥有一个以 shell/root 身份常驻的服务端。

### 第 1 步：定义 AIDL

`app/src/main/aidl/com/example/myapp/IHelperService.aidl`：

```aidl
package com.example.myapp;

interface IHelperService {

    // Shizuku 服务端保留的销毁方法，事务码必须一致
    void destroy() = 16777114;

    // 以下是你自己的方法，事务码从 1 开始编号
    String whoami() = 1;
    int installApk(String apkPath) = 2;
}
```

要点：

- `destroy()` 的事务码 `16777114` 是服务端约定，用于进程退出，**别改**
- 自定义方法的事务码从 `1` 递增，接口一旦发布就不要复用旧码（兼容性）

### 第 2 步：实现服务类

```java
package com.example.myapp;

import android.content.Context;
import android.os.RemoteException;
import android.system.Os;

import androidx.annotation.Keep;

public class HelperService extends IHelperService.Stub {

    // 必须保留无参构造器
    public HelperService() {}

    // API v13 起支持带 Context 的构造器（需 @Keep 防 ProGuard 混淆删除）
    @Keep
    public HelperService(Context context) {
        // 这个 context 是以特权身份 createPackageContextAsUser 出来的
    }

    @Override
    public void destroy() {
        System.exit(0); // 服务端要求：destroy 即退出进程
    }

    @Override
    public String whoami() throws RemoteException {
        // 在特权进程里执行！uid 是 2000(adb) 或 0(root)
        return "uid=" + Os.getuid() + ", pid=" + Os.getpid();
    }

    @Override
    public int installApk(String apkPath) throws RemoteException {
        // 这里跑的是特权代码：pm install、am、settings……
        Process p;
        try {
            p = Runtime.getRuntime().exec(new String[]{"pm", "install", "-r", apkPath});
            return p.waitFor();
        } catch (Exception e) {
            throw new RemoteException(e.toString());
        }
    }
}
```

**这个类会被加载进 Shizako 的特权进程运行**，注意：

- 类和构造器都要 `@Keep`（或加入 ProGuard 白名单）
- 不要引用你应用的 `Activity`、UI、单例状态 —— 特权进程里没有这些
- 需要执行命令就在这个类里 `Runtime.exec` / `ProcessBuilder`，API 没有公开独立的"远程执行命令"入口

### 第 3 步：绑定与解绑

```java
private final UserServiceArgs serviceArgs = new Shizuku.UserServiceArgs(
        new ComponentName("com.example.myapp", "com.example.myapp.HelperService"))
        .processNameSuffix("helper")   // 进程名变成 com.example.myapp:helper
        .version(BuildConfig.VERSION_CODE) // 服务代码变了就换个号，服务端会重建进程
        .debuggable(BuildConfig.DEBUG)
        .daemon(true);                     // 见下文

private final ServiceConnection serviceConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        IHelperService helper = IHelperService.Stub.asInterface(service);
        // 现在可以跨进程调特权方法了
        String s = helper.whoami();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // 特权进程死了（stopService / 服务端被杀）
    }
};

// 绑定（内部会先检查权限，未授权会抛 SecurityException）
Shizuku.bindUserService(serviceArgs, serviceConnection);

// 解绑；第三个参数 true 表示同时让服务端销毁这个特权进程
Shizuku.unbindUserService(serviceArgs, serviceConnection, true);

// 只探测服务是否已在运行，不创建：返回 code 见 UserService 泛型常量
int code = Shizuku.peekUserService(serviceArgs, serviceConnection);
```

## `UserServiceArgs` 参数详解

| 方法 | 默认值 | 说明 |
|------|--------|------|
| `daemon(boolean)` | `true` | **daemon 模式下特权进程常驻**，直到你 `unbindUserService(..., true)`；`false` 时你的应用进程一死，特权进程跟着死。做后台工具选 `true`，做一次性操作选 `false` 更省心 |
| `version(int)` | `1` | 用来区分服务版本。**改了服务实现就 +1**，服务端会自动销毁旧进程、用新代码重建 |
| `tag(String)` | 无 | 同一应用多个不同服务时的区分标记；混淆了服务类名的话必须设一个稳定 tag |
| `processNameSuffix(String)` | 必填 | 最终进程名 = `你的包名:后缀` |
| `debuggable(boolean)` | `false` | `true` 时在"显示所有进程"里可见，便于调试 |
| `use32BitAppProcess(boolean)` | `false` | 64 位设备强制用 32 位 `app_process`。除非你明确知道为什么需要，否则别碰（API 里就是 private 的） |

## 能力与状态查询 API

| 方法 | 返回 | 用途 |
|------|------|------|
| `Shizuku.pingBinder()` | `boolean` | binder 是否存活 |
| `Shizuku.getUid()` | `int` | 服务端 uid：`2000` = adb 激活，`0` = root 激活。**据此判断能力上限**（root 能做的比 adb 多） |
| `Shizuku.getVersion()` | `int` | 协议版本，Shizako 恒返回 `13`。做能力分支用，比如用户服务要求 `>= 10` |
| `Shizuku.getServerPatchVersion()` | `int` | 协议补丁版本（Shizako 当前为 `6`） |
| `Shizuku.getSELinuxContext()` | `String` | 服务端 SELinux 上下文（进阶诊断用） |
| `Shizuku.checkRemotePermission(String)` | `int` | 以服务端身份检查某个系统权限 |
| `Shizuku.exit()` | `void` | 让 Shizako 服务端自己退出（一般只在调试工具里用） |

## 完整示例：带权限检查的连接流程

```java
public class ShizakoHelper {

    public interface Callback {
        void onReady();
        void onNeedUser(String reason); // 引导用户去启动/授权
    }

    public static void connect(Context context, Callback cb) {
        Shizuku.addBinderReceivedListenerSticky(() -> {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                cb.onReady();
            } else {
                Shizuku.addRequestPermissionResultListener((requestCode, result) -> {
                    if (result == PackageManager.PERMISSION_GRANTED) cb.onReady();
                    else cb.onNeedUser("授权被拒绝");
                });
                Shizuku.requestPermission(101);
            }
        });

        Shizuku.addBinderDeadListener(() ->
                cb.onNeedUser("Shizako 服务未运行"));
    }
}
```

配套的用户引导建议：`onNeedUser` 时跳转 Shizako（`com.churan.shizako`）主页，或至少给出 GitHub Releases 下载链接。

## 常见坑

1. **绑定时报 SecurityException**：权限没批。`bindUserService` 不会帮你弹授权框，先 `checkSelfPermission`，未授权走 `requestPermission`。
2. **改了服务代码不生效**：忘了升 `version()`。服务端看到同版本号就复用旧进程，改完代码务必 +1。
3. **ProGuard 混淆后绑定失败**：服务类、无参构造器、带 Context 构造器都要 `@Keep`；混淆场景下记得设 `tag()`。
4. **`multiprocess="true"` 导致拿不到 binder**：改成 `false`。服务端只在应用主进程启动事件时投递。
5. **应用杀掉后特权进程还在**：`daemon(true)` 的正常行为。想让它跟着死，用 `daemon(false)` 或在 `onDestroy` 里 `unbindUserService(args, conn, true)`。
6. **在 Shizako 上弹了两次授权**：检查你是否同时注册了两个 `addRequestPermissionResultListener`，或 sticky binder listener 里重复调了 `requestPermission`。
7. **多用户（分身/应用双开）**：用户服务按"包名 + tag + 版本"在服务端建记录，分身空间里的实例是独立的；开发期不必处理，但诊断日志里看到别的 userId 的记录不要奇怪。

---

## 附：接口与实现的边界

- 本文档只覆盖**客户端库**（`api/` 目录）的用法；服务端行为（binder 推送、授权存储、用户服务进程管理）由 Shizako 的 `server/` 模块实现，协议细节见上游 [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) 文档。
- 对 Shizako 本地修改的完整清单见 [api/SHIZAKO-CHANGES.md](../api/SHIZAKO-CHANGES.md)。
- 可运行的完整 demo 在 `api/demo/`（上游原样保留），其中 `DemoActivity.java` 覆盖了本文所有 API 的调用示例。

有问题？开 [Issue](https://github.com/xm1437/Shizako/issues) 或 README 里的 QQ 群找我们喵～
