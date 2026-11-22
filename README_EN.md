<div align="center">

<img src="docs/banner.jpg" alt="Shizako banner" width="820" />

# 🐱 Shizako

## Borrow privileged Android APIs through your catgirl assistant — no root, no flashing

*Your catgirl assistant for privileged Android APIs — no root, no drama.*

[简体中文](README.md) | **English**

Compatible with the [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) ecosystem · by [初然 (cr1437)](https://github.com/cr1437)

<!-- All badges are self-hosted under docs/ and referenced by relative path, so they are
     served by github.com itself and render reliably (the previous shields.io badges often
     failed to load). version / downloads / star are regenerated periodically and after
     each release by .github/workflows/badges.yml. -->
[![version](docs/badge-version.svg)](https://github.com/cr1437/Shizako/releases)
[![downloads](docs/badge-downloads.svg)](https://github.com/cr1437/Shizako/releases)
[![star](docs/badge-star.svg)](https://github.com/cr1437/Shizako)
[![platform](docs/badge-platform.svg)](https://github.com/cr1437/Shizako/releases)
[![based on](docs/badge-based-on.svg)](https://github.com/RikkaApps/Shizuku)
[![license](docs/badge-license.svg)](LICENSE)

[🌐 Website](https://cr1437.github.io/Shizako/) · [📦 Download latest](https://github.com/cr1437/Shizako/releases) · [🐛 Report an issue](https://github.com/cr1437/Shizako/issues) · ⭐ Star this project

**Current version: `zako4`** — a performance-focused release, plus app data backup/restore, a privileged storage proxy, a trusted-installer list with a batch queue, and EXEC_SHELL automation.

</div>

---

## 🆕 zako4: making everyday use smoother

The main theme of this release is **performance and battery**, with new features along for the ride:

| Optimization | Before | Now |
| --- | --- | --- |
| **Baseline Profile** | The build disabled `CompileArtProfileTask` globally, so the generated 421 KB `baseline-prof.txt` **never made it into the APK** and cold start was pure JIT | The override is gone; `assets/dexopt/baseline.prof` ships in the release APK, bringing Compose / Material3 / RecyclerView profiles back |
| **App-list icons** | Every new list item synchronously called `loadIcon` on the main thread (the cache existed but never hit) | Binding first checks `AppIconCache` (uid + target size); hits render immediately, misses load asynchronously |
| **List measurement** | Content changes triggered repeated `requestLayout` | `setHasFixedSize(true)` + fixed item heights, one less measure pass on filter/refresh |
| **Background wake-ups** | `AlarmManager.RTC_WAKEUP` for update checks; keep-alive every 45s | Update checks use `JobScheduler` (6h, persisted, network-constrained); keep-alive 90s; adaptive accessibility heartbeat (60s foreground / 5min screen-off / 10min battery saver) |
| **Home status** | A single failed binder probe marked the whole page as "service not running" | Single failures are logged; `onResume` re-checks, less reload churn and flicker |

Verify the Baseline Profile yourself:

```bash
unzip -l shizako-zako4-release.apk | grep dexopt   # should list assets/dexopt/baseline.prof
```

---

## 🐾 What she can do

| Feature | Description |
|------|------|
| No root required | Privileged APIs through ADB / wireless debugging — no unlocking, no flashing |
| Permission passes | Every app needs your explicit approval on first connect; revocable any time |
| Official ecosystem | Apps built with the official Shizuku-API work **without a single code change** |
| Dhizuku mode | She can become Device Owner herself, forwarding privileges to Dhizuku-API apps (Hail, IceBox, …) |
| Setup wizard | First-run guide: language / appearance / launch method, with activation right inside |
| Four activation methods | Root / wireless debugging / USB ADB / Dhizuku, all on one page |
| **App data backup / restore** | External data (`/sdcard/Android/data`, `obb`) streamed into a SAF folder you pick; replace-style restore, batch queue, optional OBB / APK |
| **Privileged storage proxy** | A whitelisted file API served by the shell/root service, with a four-layer fallback and full audit; third-party apps must be granted explicitly |
| **Trusted installers + batch queue** | Signature+uid verified installs, confirmation for unknown requesters, XAPK / APKS / apk.1, failures don't stop the queue |
| **EXEC_SHELL automation** | Token + signature protected broadcast for privileged commands; results are delivered package-scoped |
| **Batch grants / templates** | Multi-select grant/revoke with impact confirmation, built-in + custom templates, SAF export/import |
| One-tap injection | Give privileges to common tools (BlackBox, IceBox, …), download missing ones in-app |
| Compatibility bridge | Provider relay so Shizuku-API clients connect unchanged — using only our own authority |
| Tasker support | Broadcasts to start/stop, activate Dhizuku, query status, trigger downloads |
| API audit | Every privileged call is logged (including storage / install / automation details) and browsable in Settings |
| Auto update | Checks on every launch with an in-app download dialog; automatic mirror fallback when GitHub is blocked |
| Crash logs | Crashes are written to `Download/Shizako-crash-*.txt` |

---

## 💾 App data backup / restore (new in zako4)

Entry: **App management → top-right menu → "App data backup & restore"**.

- **Backup**: select one or more apps → pick a SAF folder → start. Only external data is packaged by default
  (`/sdcard/Android/data/<pkg>`, `/sdcard/Android/obb/<pkg>`); OBB / APK can be included.
  Data is streamed straight into the folder — **no temporary archive on the device** — and a
  `<pkg>.meta.json` (package, version, time, Android version, OBB/APK flags) is written next to it.
- **Restore**: pick the backup folder → backups are recognised by `<pkg>.meta.json` → **confirm overwrite** → restore.
  Restore is **replace-style**: extraction happens in a staging directory and is swapped in afterwards,
  so a failed extraction leaves your current data untouched. OBB failures are reported separately,
  missing apps are reported with an install hint (a backed-up APK can be handed to the installer).
- **Batch**: backups run in a queue — sequential, failures don't stop the queue, cancellable.
- **Scope**: a full `/data/data` backup is out of scope; only external data is handled.

---

## 📦 Installer: trust list + batch queue (new in zako4)

- **Trusted installs**: apps matching both signature and uid install without a confirmation dialog; the order of checks is fixed (caller → policy → install).
- **External requests**: unknown requesters get a confirmation dialog (deny by default) and are recorded in history and the audit log.
- **Batch queue**: queue several packages; sequential, failures don't stop the queue, pause/cancel/retry; supports plain APK, WeChat-style `.apk.1`, XAPK (splits + OBB) and APKS.
- **History**: source, format, duration, result and failure reason are kept (up to 200 entries).

---

## 🤖 Automation: EXEC_SHELL (new in zako4)

- Broadcast action `com.churan.shizako.action.EXEC_SHELL`, requiring an explicit component, a token and the caller's signature;
- Results come back via `com.churan.shizako.action.EXEC_SHELL_RESULT`, **package-scoped to the caller**;
- The token is generated/rotated in Settings → Automation; commands run under a whitelist with timeout and length limits.

---

## 🧩 Batch grants and permission templates

- Long-press (or "Select" in the top-right menu) to multi-select, then grant/revoke in bulk with impact confirmation, progress and a result report;
- 3 built-in templates plus custom ones, with SAF export/import (rename / skip / overwrite on conflict);
- Every batch operation is audited with a batch id and can be reviewed in "Permission templates → Batch history".

---

## 🚀 Get her home

### Step 1: Adopt

Download the latest APK from [Releases](https://github.com/cr1437/Shizako/releases) and install it.

### Step 2: Wake her up

| Method | Requirements | For |
|----------|------|---------|
| Wireless debugging | Android 11+, enable wireless debugging, pair in-app | People who hate cables |
| USB ADB | Run the `adb` command shown in the app once | People with a computer nearby |
| Root | Already rooted | Power users |
| Dhizuku (Device Owner) | One `adb shell dpm set-device-owner com.churan.shizako/moe.shizuku.manager.dhizuku.DhizukuAdminReceiver` (no accounts on the device); remove it later from the Dhizuku apps page | Set-and-forget |

The activation flow mirrors upstream Shizuku; see the [upstream guide](https://shizuku.rikka.app/guide/setup/) and replace "Shizuku" with "Shizako".

### Step 3: Hand out passes

Open the app you want to authorise — Shizako shows a permission dialog. Allow and the pass is issued; deny and that's it. Every pass is recorded and can be revoked in Settings.

---

## 🔌 Ecosystem compatibility

### Built on the Shizuku API

`api/` contains the full source of [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) (MIT). Protocols and calls are identical to upstream — see [api/SHIZAKO-CHANGES.md](api/SHIZAKO-CHANGES.md) for our changes.

### Official apps connect directly

Apps built with the official `dev.rikka.shizuku:api` (MT Manager, IceBox, SystemUI Tuner, …) connect **without recompiling**:

| App type | Official Shizuku | Shizako |
|----------|:---:|:---:|
| Built with official `dev.rikka.shizuku:api` | ✅ | ✅ |
| Built with this repo's `api/` | ✅ | ✅ |

The server verifies *that* an app requested the permission, not which permission name it used; the first connection still shows the approval dialog.

### Dhizuku compatibility: she is her own Device Owner

- Once set as Device Owner, Dhizuku-API apps treat Shizako as Dhizuku — **no code changes**
- Privilege forwarding happens inside the Shizako process; the system only ever sees Shizako as the caller
- Every Dhizuku-API app needs approval on first use and can be revoked from the "Dhizuku authorised apps" page
- The compatibility layer is independently written (wire-compatible), so Shizako stays Apache-2.0

### 🔏 Coexistence with official Shizuku, and verifying official builds

Shizako uses its **own** package name (`com.churan.shizako`) and permission names, and only declares its own provider authority `com.churan.shizako.shizuku`. It does **not** use the upstream package name, app name, permissions, authority or icon, so it can be installed alongside official Shizuku.

#### Official build signing certificate

| Item | Value |
| --- | --- |
| Subject | `CN=Shizako, OU=Churan, O=Churan, L=Beijing, ST=Beijing, C=CN` |
| SHA-256 | `515BAF0C727F2AFBABA8A22379A05F8F2CC98682E5BEB24FB166565ACC9F9C8A` |

Verify with `apksigner verify --print-certs shizako-*.apk` or the in-app "Settings → About" fingerprint. A mismatch means the APK was re-signed — do not install or authorise it. F-Droid builds are re-signed by F-Droid and differ by design.

---

## 🎨 The mascot

<div align="center">
<img src="manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="Shizako logo" />
</div>

Shizako is a white-haired catgirl with a crescent hairpin — a "catgirl system assistant hugging a terminal". Icons, in-app copy and promo material all revolve around her.

> Officially blessed: use her as a sticker if you like (ˊᗜˋ*), just don't do anything mean with her.

---

## 🛠️ For developers

### Option 1: Connect directly (recommended)

The official `dev.rikka.shizuku:api` works with both managers out of the box. See [docs/API.md](docs/API.md) for dependencies, permission requests, binder lifecycle and AIDL user services.

### Option 2: Source integration

Copy `api/` into your project and add the modules as shown in the Chinese README (the Gradle snippet is language-neutral).

Code looks exactly like the official API: `Shizuku.bindUserService(...)`, `Shizuku.requestPermission(...)`.

### Storage proxy (for trusted third parties)

Since zako4 the server exposes `IStorageProxy` (list / open / tar / restore / copy / delete / mkdir / exists).
A third-party app needs **both**:

1. regular Shizuku authorisation, and
2. the per-app "storage proxy" switch in **App data backup & restore → Third-party**.

Every call writes one audit line (caller uid, operation, path digest, result, duration). Paths are whitelisted and checked for traversal/injection before anything touches the filesystem.

---

## 📦 Build it yourself

```bash
git clone https://github.com/cr1437/Shizako.git
cd Shizako
./gradlew :manager:assembleRelease
```

| Dependency | Version |
|------|---------|
| JDK | **21** (17 will not compile) |
| Android SDK | compileSdk 36 |
| NDK | 29.0.14206865 |

- Aliyun Maven mirrors are configured for mainland China; no extra proxy needed
- Output: `manager/build/outputs/apk/release/`; the release APK embeds the Baseline Profile under `assets/dexopt/`
- Without `signing.properties` the build falls back to the debug key (fine for personal use; **releases must use the project key**)

### Tasker / MacroDroid broadcasts

| Action | Effect |
|--------|------|
| `com.churan.shizako.action.START` | Start the service using the last method |
| `com.churan.shizako.action.STOP` | Stop the service |
| `com.churan.shizako.action.START_ROOT` | Start with root |
| `com.churan.shizako.action.START_ADB` | Start with wireless debugging |
| `com.churan.shizako.action.ACTIVATE_DHIZUKU` | Activate Device Owner mode |
| `com.churan.shizako.action.QUERY_STATUS` | Query status (ordered broadcast returns `running`, `dhizuku`) |
| `com.churan.shizako.action.DOWNLOAD_UPDATE` | Download the APK at the `url` extra |
| `com.churan.shizako.action.NOTIFY_INSTALL` | Post a tappable install notification |
| `com.churan.shizako.action.EXEC_SHELL` | Privileged command execution (token + signature, see Automation) |

---

## 🗂️ Source layout

10 Gradle modules (see `settings.gradle`). `manager/` is the app (~50k lines, ~85%), `server/` is the privileged process (Java), `starter/` launches it, `shell/` builds the rish dex, `api/` vendors Shizuku-API (MIT), `docs/` powers the website.

---

## ❓ FAQ

**Q: Does it need root?**
A: No. Wireless debugging or USB ADB is enough; root is optional.

**Q: Why is this release called zako4?**
A: The changes are bigger than any previous release (performance + backup/restore + installer + automation), so we bumped the major version rather than calling it 3.20 (versionCode 320 → 400).

**Q: How is this related to Shizuku?**
A: It is API-compatible with the Shizuku ecosystem. Per upstream licensing, it uses its own package/permission names and can coexist with official Shizuku.

**Q: Does backup touch `/data/data`?**
A: No. Only external data (`/sdcard/Android/data`, `/sdcard/Android/obb`).

**Q: How do third-party apps use the storage proxy?**
A: Regular Shizuku authorisation **plus** the per-app switch under "App data backup & restore → Third-party"; otherwise calls are rejected and audited.

**Q: What exactly was optimised?**
A: Baseline Profile now ships, app-list icons hit a real cache, list measuring is cheaper, and background wake-ups were reduced (JobScheduler update checks, adaptive heartbeat, 90s keep-alive). See the table at the top.

**Q: Can it coexist with official Shizuku?**
A: Yes — different authority, different package; install both and authorise each.

**Q: Is authorising apps safe?**
A: Every app needs your explicit approval, the allow-list is local and revocable; Dhizuku forwarding happens inside Shizako; the storage proxy needs a second, separate grant and is fully audited.

**Q: Which Android versions are supported?**
A: Android 7.0+; wireless-debugging activation needs Android 11+.

**Q: Play Protect says "harmful app" — is it malware?**
A: No. It is a heuristic verdict on sideloaded privileged tools, made by Google and impossible to disable from code. Verify the certificate (`515baf0c…9f9c8a`) and keep the APK if it matches; you can also submit a false-positive appeal (no guaranteed outcome).

**Q: Why does it request so many "dangerous" permissions?**
A: They are required for real features: `QUERY_ALL_PACKAGES` to show authorised apps, `WRITE_SECURE_SETTINGS` to toggle wireless debugging and accessibility, DeviceAdmin for Dhizuku mode, `STORAGE_PROXY` as the capability marker for the storage proxy. Nothing is used silently; the allow-list is local and revocable.

---

## 💗 Credits

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) and its contributors — the core capability comes from here
- [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) — the client library
- Everyone who starred Shizako

## 📬 Feedback

| Channel | Link |
|------|------|
| GitHub Issues | [Issues](https://github.com/cr1437/Shizako/issues) |
| QQ (feedback) | [891276089](https://qm.qq.com/cgi-bin/qm/qr?k=891276089) |
| QQ (chat) | [1104445003](https://qm.qq.com/cgi-bin/qm/qr?k=1104445003) |

## 📄 License

Licensed under the upstream [Apache License 2.0](LICENSE); see also [NOTICE](NOTICE). The `api/` directory keeps its own MIT license.

### Name and artwork (brand)

Following the upstream additional terms under Apache-2.0 §6, this project states:

- **Do not** use `Shizako` (or confusing variants such as `Shizako+`, `Shizako Plus`, `Shizako Pro`) as the name of a third-party app, or as part of its package name / application id / permission names;
- **Do not** use the mascot artwork or the app icons (`manager/src/main/res/mipmap*/ic_launcher*.png`) to represent anything other than Shizako;
- **Do not** distribute non-official builds under the application id `com.churan.shizako`.

In short: fork the code freely, but **change the name, icon and package name**. This keeps the project compliant and makes official builds easy to tell apart.

---

<div align="center">

> ⭐ **Like Shizako? Leave a star** — every star is cat food `(ฅ'ω'ฅ)`
>
> [Feed her a star →](https://github.com/cr1437/Shizako)

</div>
