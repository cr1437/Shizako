<div align="center">

<img src="docs/banner.jpg" alt="Shizako banner" width="820" />

# 🐱 Shizako

## Borrow privileged Android APIs through your catgirl assistant — no root, no flashing

*Your catgirl assistant for privileged Android APIs — no root, no drama.*

[简体中文](README.md) | **English**

Compatible with the [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) ecosystem · by [初然 (cr1437)](https://github.com/cr1437)

<!-- All badges are self-hosted under docs/ and referenced by relative path, so they are
     served by github.com itself and render reliably (the previous shields.io badges often
     failed to load). version / downloads / star are regenerated every 6 hours and after
     each release by .github/workflows/badges.yml. -->
[![version](docs/badge-version.svg)](https://github.com/cr1437/Shizako/releases)
[![downloads](docs/badge-downloads.svg)](https://github.com/cr1437/Shizako/releases)
[![star](docs/badge-star.svg)](https://github.com/cr1437/Shizako)
[![platform](docs/badge-platform.svg)](https://github.com/cr1437/Shizako/releases)
[![based on](docs/badge-based-on.svg)](https://github.com/RikkaApps/Shizuku)
[![license](docs/badge-license.svg)](LICENSE)

[🌐 Website](https://cr1437.github.io/Shizako/) · [📦 Download latest](https://github.com/cr1437/Shizako/releases) · [🐛 Report an issue](https://github.com/cr1437/Shizako/issues) · ⭐ Star this project

</div>

---

> "Hi there, I'm **Shizako** `(ฅ'ω'ฅ)`
>
> Your phone is full of 'system-only' goodies — silent installs, freezing apps, tweaking permissions,
> reading system settings — yet only the system itself gets to touch them. Annoying, right?
> Root can hand you the keys, but the price is… brick warnings, a voided warranty, and banking apps
> that stop trusting you.
>
> So I took another path: I run one small privileged process for you (wake me up via wireless debugging,
> USB, or root — your choice), then **lend** that privilege to apps you trust. Lend, not give:
> every app needs your explicit approval, and you can revoke it any time."

---

## 🐾 What she can do

| Feature | Description |
|---------|-------------|
| Privileges without root | Approximate root-level power through ADB / wireless debugging — no unlocking, no flashing |
| Passport system | Every app needs your explicit approval on first connect; revocable at any time |
| Works with the official ecosystem | Apps built on the official Shizuku-API connect **without a single line of changes** |
| Dhizuku mode | Promote her to Device Owner for always-on privileges without root or wireless debugging; Dhizuku-API apps (Hail, Ice Box, …) can borrow directly |
| First-run wizard | Step-by-step setup (language / appearance / activation method) with activation built in; 10-second forced disclaimer |
| Four activation paths | Root / wireless debugging / computer ADB / Dhizuku, all on one page; one-tap start once paired |
| One-tap injection | Grant privileges to common tools (Thanox, Ice Box, 炼妖壶 …), with in-app download for missing ones |
| Compatibility bridge | Built-in provider relay so Shizuku-API clients connect unchanged; it declares **only its own authority and claims no upstream identifier** |
| Tasker support | Broadcasts to start/stop the service, activate Dhizuku, query status, trigger downloads |
| API audit log | Records which app called which privileged API — review it any time in settings |
| Auto update | Checks for a new version **every time you open the app**, with an in-app download dialog; falls back to a working mirror when GitHub is blocked by your network |
| Crash logs | Crashes are written to `Download/Shizako-crash-*.txt` |

---

## 🚀 Getting started

### 1. Adopt her

Download the latest APK from [Releases](https://github.com/cr1437/Shizako/releases) and install it.

### 2. Wake her up

| Method | Requirements | Best for |
|--------|--------------|----------|
| Wireless debugging | Android 11+, enable wireless debugging and pair | No cable at hand |
| Computer ADB | Run the `adb` command shown in the app once | You have a computer nearby |
| Root | An existing root environment | Root users who want more |
| Dhizuku (Device Owner) | Run `adb shell dpm set-device-owner com.churan.shizako/moe.shizuku.manager.dhizuku.DhizukuAdminReceiver` once on a computer (no accounts may be on the device); to leave the mode, hit "Remove Device Owner" on the Dhizuku apps page | Set-and-forget |

The flow matches upstream Shizuku — see the [upstream setup guide](https://shizuku.rikka.app/guide/setup/), replacing Shizuku with Shizako.

### 3. Issue passports

Open an app you want to authorize; Shizako shows a permission dialog. Approve to grant, deny to decline. Every passport is recorded and can be revoked in settings.

---

## 🔌 Ecosystem compatibility

### Built on the Shizuku API

The `api/` directory contains the full [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) sources (MIT License). Protocol, interfaces and call patterns are identical to upstream — see [api/SHIZAKO-CHANGES.md](api/SHIZAKO-CHANGES.md) for what changed.

### Official-ecosystem apps connect directly

New name, same temper. Apps built on the official `dev.rikka.shizuku:api` (MT Manager, Ice Box, SystemUI Tuner, …) connect **without code changes or recompiling**:

| App built against | Connects to Shizuku | Connects to Shizako |
|-------------------|:---:|:---:|
| Official `dev.rikka.shizuku:api` | ✅ | ✅ |
| This repository's `api/` | ✅ | ✅ |

The server only checks *that* an app requested the permission, not *which* permission name it requested; the first connection still requires your approval.

### Dhizuku compatibility: she is her own Device Owner

- Once set as Device Owner, Dhizuku-API apps treat Shizako as Dhizuku — **no code changes**
- All privilege forwarding happens inside the Shizako process; the system sees Shizako itself as the caller — no binder backdoor for third parties
- Every Dhizuku-API app needs approval on first use; grants are stored in a local allowlist and revocable under "Dhizuku authorized apps"
- The compatibility layer is written independently (wire protocol aligned with Dhizuku-API) and does not depend on the GPL-licensed Dhizuku-API library, so Shizako stays Apache-2.0

### 🔏 Coexisting with the official Shizuku, and verifying an official build

Shizako uses **its own** package name (`com.churan.shizako`) and permission names, and its provider
declares only its own authority, `com.churan.shizako.shizuku`. It does not use the upstream
`moe.shizuku.privileged.api` package name, the `Shizuku` app name, `moe.shizuku.manager.permission.*`
permissions, the upstream provider authority or upstream icons. It therefore **can be installed
alongside the official Shizuku**; whichever one is running is the one your apps talk to.
(Historically the GitHub build shipped a "compatibility placeholder package" that squatted the
upstream package name to please clients with the old name hard-coded. That approach has been removed
entirely: it violated the upstream Apache-2.0 §6 terms verbatim, and from the outside it was
indistinguishable from a repackaged, renamed counterfeit.)

#### Signing fingerprint of official builds

Every official build is signed with the same release certificate:

| Item | Value |
| --- | --- |
| Certificate subject | `CN=Shizako, OU=Churan, O=Churan, L=Beijing, ST=Beijing, C=CN` |
| Certificate SHA-256 | `515BAF0C727F2AFBABA8A22379A05F8F2CC98682E5BEB24FB166565ACC9F9C8A` |

To verify, either:

1. Run `apksigner verify --print-certs shizako-*.apk` on a computer and compare the line above, or
2. Open **Settings → About** inside the app: it shows the fingerprint of **the APK you actually
   installed**. Compare that with the table. On launch, a mismatch also triggers a warning that
   points you to the official release page (warn-only, never blocks; F-Droid builds don't warn).

A mismatch means the APK was repackaged and re-signed. **Do not install it and do not grant it
anything.** F-Droid builds are re-signed with F-Droid's own key, so their fingerprint differs by
design; trust the one shown on the F-Droid listing.

---

## 🎨 Meet the mascot

<div align="center">
<img src="manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="Shizako logo" />
</div>

Shizako: white hair, cat ears, crescent hairpin — a catgirl system assistant hugging a terminal. The project icon, in-app copy and artwork all revolve around her.

> Officially approved for use as a sticker (ˊᗜˋ*) — as long as you don't do anything nasty.

---

## 🛠️ For developers

### Option 1: Connect directly (recommended)

The official `dev.rikka.shizuku:api` already works with both managers, **no changes needed**. See [docs/API.md](docs/API.md) for dependency setup, permission requests, binder lifecycle and AIDL user services.

### Option 2: Vendor the sources

Copy the `api/` directory into your project:

```gradle
// settings.gradle
include ':aidl', ':shared', ':api', ':provider'
project(':aidl').projectDir = file('api/aidl')
project(':shared').projectDir = file('api/shared')
project(':api').projectDir = file('api/api')
project(':provider').projectDir = file('api/provider')

// app/build.gradle
implementation project(':api')
implementation project(':provider')
```

Usage is identical to the official API: `Shizuku.bindUserService(...)`, `Shizuku.requestPermission(...)`.

---

## 📦 Building from source

```bash
git clone https://github.com/cr1437/Shizako.git
cd Shizako
./gradlew :manager:assembleRelease
```

| Requirement | Version |
|-------------|---------|
| JDK | **21** (root `build.gradle` uses `VERSION_21` and `manager` sets `jvmTarget = "21"`; no toolchain is declared, so `JAVA_HOME` is used as-is — **17 will not build**) |
| Android SDK | compileSdk 36 |
| NDK | 29.0.14206865 |

- Aliyun Maven mirrors are preconfigured (no extra proxy needed in mainland China)
- Output: `manager/build/outputs/apk/release/`
- Without a `signing.properties` the build falls back to the debug signature — fine for local use, but **official releases must use the project signature**, otherwise users cannot upgrade in place

### Tasker / MacroDroid broadcasts

| Action | Effect |
|--------|--------|
| `com.churan.shizako.action.START` | Start the service using the last launch method |
| `com.churan.shizako.action.STOP` | Stop the service |
| `com.churan.shizako.action.START_ROOT` | Start via root |
| `com.churan.shizako.action.START_ADB` | Start via wireless debugging |
| `com.churan.shizako.action.ACTIVATE_DHIZUKU` | Activate Device Owner mode (Shizuku mode must be running) |
| `com.churan.shizako.action.QUERY_STATUS` | Query status (ordered broadcast returns `running`, `dhizuku`) |
| `com.churan.shizako.action.DOWNLOAD_UPDATE` | Download the APK at the `url` extra using the built-in downloader |
| `com.churan.shizako.action.NOTIFY_INSTALL` | Post a tappable notification that installs the newest APK in Download |

---

## 🗂️ Project layout

**10 Gradle modules** (per `settings.gradle`):

```
Shizako/
├── manager/          Android app (Kotlin, ~32k lines — ~82% of the repo)
├── server/           Privileged server process (Java, runs as shell / root)
├── starter/          Service starter (launches the server process)
├── shell/            Builds rish_shizuku.dex (`ShizukuShellLoader`), bundled into the APK
├── common/           Shared utilities (BuildUtils / OsUtils)
├── api/              Vendored Shizuku-API (MIT) — contains 6 submodules:
│   ├── aidl/           AIDL interface definitions
│   ├── shared/         Constants shared by client and server
│   ├── api/            Client library (rikka.shizuku.Shizuku)
│   ├── provider/       Client-side ShizukuProvider
│   ├── server-shared/  Shared server-side base classes
│   └── rish/           Native part of the rish CLI tool
├── hidden-api-stub/  Compile-time hidden-API stubs
└── docs/             Documentation and artwork (the website is generated from here)
```

> The 6 modules inside `api/` have their `projectDir` rewritten to `api/<name>` in
> `settings.gradle`, which is why their directory names don't appear at the top level.

---

## ❓ FAQ

**Do I need root?**
No. Wireless debugging or USB is enough; root simply adds more options.

**How does this relate to Shizuku?**
It is compatible with the Shizuku-API ecosystem — apps built on the official SDK connect without changes. Per the upstream license it ships its own package and permission names and claims no upstream authority, so it can coexist with the official Shizuku; on top of that it adds Dhizuku compatibility, one-tap injection and more.

**Can it coexist with the official Shizuku?**
Yes. Shizako's provider declares only its own authority, so it does not clash with the official Shizuku. Install both and authorise each separately.

**Is granting privileges to apps safe?**
Every app requires your explicit approval on first connect; the allowlist is local and revocable, and Dhizuku privilege forwarding stays inside the Shizako process.

**Which Android versions are supported?**
Android 7.0+; wireless debugging activation requires Android 11+.

**The pairing dialog is stuck on "Searching for pairing service" and I cannot enter a port?**
Automatic discovery relies on mDNS multicast. With a VPN enabled on GrapheneOS the multicast never reaches the app, and some Huawei / HarmonyOS devices block it as well — discovery simply cannot succeed there. Since zako3.14 the port field in the pairing dialog is **always visible and editable**: read the **pairing port** from "Developer options → Wireless debugging → Pair device with pairing code" and type it in together with the pairing code. If adbd already listens on a fixed port (5555, for example), you can also use "Connect to a port manually" on the activation page and skip pairing entirely.

**Play Protect says "This app may be harmful" when I install or update it. Is it malware?**
No. Shizako is a sideloaded app that can download and install APKs, launch a privileged process via `app_process`, and act as Device Owner — exactly the shape Play Protect's heuristics are trained to distrust in Shizuku-class tools. The verdict is made on Google's side and cannot be turned off from code. To verify your copy: ① download only from [Releases](https://github.com/cr1437/Shizako/releases) and check the certificate SHA-256 with `apksigner verify --print-certs <apk>` (it must be `515baf0c…9f9c8a`); ② if you installed an Actions artefact (debug-signed) or a build of unknown origin, switch to the official signed release; ③ if the checks pass, tap "More details → Keep anyway" in the Play Protect prompt, or turn off "Scan apps" under Settings → Google → Security → Play Protect (this weakens device-wide protection and is not recommended long-term). The verdict belongs to Google — a false-positive appeal can be filed following their [developer guidance](https://developers.google.com/android/play-protect/warning-dev-guidance), but the process is one-way with no guaranteed outcome or timeline.

**Why does the APK request so many "dangerous" permissions?**
All are required by the features: `QUERY_ALL_PACKAGES` renders the name and icon of authorised apps, `WRITE_SECURE_SETTINGS` toggles wireless debugging and manages accessibility services for you, and DeviceAdmin is Dhizuku mode itself. Nothing is used silently in the background, and the allowlist is local and revocable.

**Why is she so cute?**
Because the author raises her like a daughter (confirmed).

---

## 💗 Credits

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) and its contributors — Shizako's core capabilities come from here
- [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) — client communication library
- Everyone who stars Shizako

## 📬 Contact

| Channel | Link |
|---------|------|
| GitHub Issues | [Issues](https://github.com/cr1437/Shizako/issues) |
| QQ (feedback) | [891276089](https://qm.qq.com/cgi-bin/qm/qr?k=891276089) |
| QQ group | [1104445003](https://qm.qq.com/cgi-bin/qm/qr?k=1104445003) |

## 📄 License

Licensed under the upstream [Apache License 2.0](LICENSE); see also [NOTICE](NOTICE).

- Original copyright belongs to RikkaApps; modifications copyright 初然
- Does not use the upstream `Shizuku` name, the `moe.shizuku.privileged.api` package name, `moe.shizuku.manager.permission.*` permissions, the upstream provider authority, or upstream icons
- The `api/` directory keeps its own MIT License

### Name and artwork (brand)

Following the same form as the upstream addendum to Apache-2.0 §6, this project states:

- You are **FORBIDDEN** to use `Shizako` (including `Shizako+`, `Shizako Plus`, `Shizako Pro` or any
  other confusingly similar variant) as the name of a third-party application, or as part of its
  package name / application id / permission names;
- You are **FORBIDDEN** to use this project's mascot artwork or launcher icons
  (`manager/src/main/res/mipmap*/ic_launcher*.png` and all related assets) for anything other
  than displaying Shizako itself;
- You are **FORBIDDEN** to distribute any non-official build under the application id
  `com.churan.shizako`.

In short: fork the code all you like (you are welcome to) — **change the name, the icons and the
package name**. That keeps you compliant with Apache-2.0 and lets users tell official builds apart,
which is the single most effective anti-counterfeiting measure there is.

---

<div align="center">

> ⭐ **Enjoying Shizako? Leave a star** — every star is cat food for her `(ฅ'ω'ฅ)`
>
> [Feed her a star →](https://github.com/cr1437/Shizako)

</div>
