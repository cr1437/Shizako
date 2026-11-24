<div align="center">

<img src="docs/banner.jpg" alt="Banner illustration of Shizako hugging a terminal" width="820" />

# Shizako

## Borrow privileged Android APIs through your catgirl assistant — no root, no flashing

*让猫耳看板娘替你「借」系统权限 —— 不 Root、不刷机*

[简体中文](README.md) | **English**

Shizuku-API ecosystem compatible · by [Churan](https://github.com/cr1437)

<!-- All badges are self-hosted under docs/ and referenced with relative paths so they load
     reliably even where shields.io is blocked. The three changing metrics
     (version / downloads / star) are regenerated automatically by
     .github/workflows/badges.yml on a schedule and after every release. -->
[![Version](docs/badge-version.svg)](https://github.com/cr1437/Shizako/releases)
[![Downloads](docs/badge-downloads.svg)](https://github.com/cr1437/Shizako/releases)
[![Star](docs/badge-star.svg)](https://github.com/cr1437/Shizako)
[![Platform](docs/badge-platform.svg)](https://github.com/cr1437/Shizako/releases)
[![Based on](docs/badge-based-on.svg)](https://github.com/RikkaApps/Shizuku)
[![License](docs/badge-license.svg)](LICENSE)

[<img src="docs/assets/icons/readme/book-open.svg" width="15" height="15" alt="" /> Website](https://cr1437.github.io/Shizako/) · [<img src="docs/assets/icons/readme/download.svg" width="15" height="15" alt="" /> Download latest](https://github.com/cr1437/Shizako/releases) · [<img src="docs/assets/icons/readme/message-circle.svg" width="15" height="15" alt="" /> Report an issue](https://github.com/cr1437/Shizako/issues) · <img src="docs/assets/icons/readme/star.svg" width="15" height="15" alt="" /> Star her

</div>

---

> "Hi, I'm **Shizako** `(ฅ'ω'ฅ)`
>
> Your phone is full of 'system-only' goodies — silent installs, freezing apps, tweaking
> permissions, reading system settings — that only the system itself can touch. Root gets
> you the key, sure, but at what cost: brick warnings, warranty gone, banking apps turning
> their back on you.
>
> So I took another road: I run a small privileged process for you (wake me up over wireless
> debugging, USB or root), then **lend** that privilege to the apps you trust. Lend, not give —
> every app needs your explicit nod for a pass, and you can revoke it any time."

---

## <img src="docs/assets/icons/readme/zap.svg" width="20" height="20" alt="" /> What she can do

| Feature | Description |
|------|------|
| Root-free privilege | Near-root powers through ADB / wireless debugging — no disassembly, no flashing, no cold sweat |
| Permission passes | Every app needs your explicit approval on first connect; revoke any time |
| Official ecosystem | Apps written against the official Shizuku-API connect **without changing a single line** |
| Dhizuku mode | One tap makes her the Device Owner — persistent privilege, no root, no wireless debugging; revocable in-app |
| Onboarding wizard | Step-by-step first-run guide (language / look / start method) that completes activation right inside; 10-second forced disclaimer |
| Four activation methods | Root / wireless debugging / PC ADB / Dhizuku on one page; one-tap start once paired |
| App data backup / restore | External data (`/sdcard/Android/data`, `obb`) packed into the SAF folder you pick, replace-style restore, batch queue, optional OBB / APK |
| Privileged storage proxy | The server exposes a restricted file API as shell/root: four-tier access fallback + path allowlist + full audit; third-party apps need a separate grant |
| Install trust list + batch queue | Trusted apps install without confirmation, external requests get a confirm dialog, XAPK / APKS / apk.1 supported, one failure doesn't stop the batch |
| EXEC_SHELL automation | Broadcast entry with token + signature verification for Tasker / MacroDroid to run privileged commands and get results by package name |
| Batch grants / templates | Multi-select grant and revoke with impact confirmation, built-in + custom templates, SAF export / import |
| One-click injection | Hand out permissions to common tools (Brevent, Island, Ice Box, Hail); download them in-app if missing |
| Compatibility bridge | Built-in provider relay, zero-change connection for Shizuku-API clients; only its own authority — **no upstream identity is taken** |
| Tasker support | Broadcasts to start / stop the service, activate Dhizuku, query status, trigger downloads |
| API audit | Full record of which app called which privileged API (including storage proxy / installer / automation details), viewable in settings |
| Auto update | Checks for new versions on every launch and shows an in-app downloadable dialog; switches to a working mirror when GitHub is blocked |
| Crash logs | Crashes land in `Download/Shizako-crash-*.txt` automatically |

---

## <img src="docs/assets/icons/readme/download.svg" width="20" height="20" alt="" /> App data backup / restore

Entry: **App management → top-right "App data backup / restore"**.

- **Backup**: pick one or more apps → choose a SAF folder → go. By default it only packs external data
  (`/sdcard/Android/data/<package>`, `/sdcard/Android/obb/<package>`); you can opt to include OBB / APK.
  Data streams straight into the folder you chose — **no temporary archive on the device** — along with
  a `<package>.meta.json` (package, version, time, Android version, whether OBB/APK is included).
- **Restore**: pick the backup folder → it recognises `<package>.meta.json` → **confirm overwrite** → restore.
  Restore is **replace-style**: it extracts to staging first and swaps at the end, so a failed extraction
  never touches your existing data; an OBB failure is reported separately; if the target app isn't
  installed it asks you to install it first (a backup containing the APK can go straight to the installer).
- **Batch**: backups run through a queue — sequential, one failure doesn't stop the rest, cancellable.
- **Scope**: a full `/data/data` backup is out of scope (external data only).

---

## <img src="docs/assets/icons/readme/package.svg" width="20" height="20" alt="" /> Installer: trust list + batch queue

- **Trusted-app direct install**: requests from apps that pass the signature + uid checks install without
  a confirmation dialog; the decision order is fixed (caller first, then policy, installation last) and the
  trust list is maintained in "Settings → Installer".
- **External requests**: anything not on the trust list gets a confirmation dialog (deny by default) and is
  written to history and the audit log.
- **Batch queue**: submit several packages at once — sequential, one failure doesn't stop the batch,
  pausable / cancellable / retryable; supports plain APK, WeChat-style `.apk.1`, XAPK (with splits and OBB),
  and APKS (bundletool output).
- **History**: every install records source, format, duration, result and failure reason, keeping up to 200 entries.

---

## <img src="docs/assets/icons/readme/terminal.svg" width="20" height="20" alt="" /> Automation: EXEC_SHELL

- Broadcast action `com.churan.shizako.action.EXEC_SHELL`, requiring an explicit component + token +
  sender signature verification;
- results come back via `com.churan.shizako.action.EXEC_SHELL_RESULT` **addressed to the caller's package
  name**, so a leaked token never leaks output to someone else;
- tokens are generated and rotated in "Settings → Automation"; commands run inside a whitelist with
  timeout and length limits.

---

## <img src="docs/assets/icons/readme/menu.svg" width="20" height="20" alt="" /> Batch grants and permission templates

- Long-press in the app list (or the top-right "Select" menu) for multi-select, batch grant / revoke with
  impact confirmation, progress and a result report;
- 3 built-in templates + custom templates, with SAF export / import (rename / skip / overwrite on conflict);
- every batch operation is written to the audit log (with a batch ID), reviewable in
  "Permission templates → Batch history".

---

## <img src="docs/assets/icons/readme/smartphone.svg" width="20" height="20" alt="" /> Get her home

### Step 1: Adopt

Download the latest APK from [Releases](https://github.com/cr1437/Shizako/releases) and install it.

### Step 2: Wake her up

| Method | Requirement | For |
|----------|------|---------|
| Wireless debugging | Android 11+, enable wireless debugging and scan / pair | You who can't be bothered with a cable |
| PC ADB | Run the in-app `adb` command on a computer once; no version limit | You with a computer nearby |
| Root | Wake her straight from an existing root environment | The hardcore rooted player |
| Dhizuku (Device Owner) | Run `adb shell dpm set-device-owner com.churan.shizako/moe.shizuku.manager.dhizuku.DhizukuAdminReceiver` on a computer once (no accounts allowed on the device); afterwards no root and no wireless debugging is needed; revoke in-app via "Dhizuku apps → Remove device owner" | The set-and-forget type |

A step-by-step illustrated guide lives on the [website activation page](https://cr1437.github.io/Shizako/act/);
the flow matches upstream Shizuku, so the [upstream setup guide](https://shizuku.rikka.app/guide/setup/)
also applies with Shizuku swapped for Shizako.

### Step 3: Hand out passes

Open the app you want to authorise and Shizako shows the permission dialog — agree, and the power is theirs;
decline, and it's a polite goodbye. Every pass is recorded and revocable from settings at any time.

---

## <img src="docs/assets/icons/readme/plug.svg" width="20" height="20" alt="" /> Ecosystem compatibility

### Built on the Shizuku API

The `api/` directory vendors the full [Shizuku-API](https://github.com/RikkaApps/Shizuku-API) source
(MIT License). The wire protocol, interfaces and calling conventions are the same as upstream — the exact
changes are documented in [api/SHIZAKO-CHANGES.md](api/SHIZAKO-CHANGES.md).

### Official apps connect directly

New name, same temper. Apps written against the official `dev.rikka.shizuku:api` (MT Manager, Ice Box,
SystemUI Tuner, …) connect **with no code changes and no recompilation**:

| App written against | Official Shizuku | Shizako |
|----------|:---:|:---:|
| official `dev.rikka.shizuku:api` | <img src="docs/assets/icons/readme/shield-check.svg" width="16" height="16" alt="" /> | <img src="docs/assets/icons/readme/shield-check.svg" width="16" height="16" alt="" /> |
| this repo's `api/` | <img src="docs/assets/icons/readme/shield-check.svg" width="16" height="16" alt="" /> | <img src="docs/assets/icons/readme/shield-check.svg" width="16" height="16" alt="" /> |

The server only checks "did it request permission", not "which permission name"; first connection still
shows the authorisation dialog — your nod is what counts.

### Dhizuku compatibility: she is her own Device Owner

- Once set as Device Owner, Dhizuku-API apps use Shizako as Dhizuku, **without code changes**
- Privilege forwarding happens entirely inside the Shizako process; the system sees Shizako as the caller —
  no system-binder backdoor is opened for third parties
- Every Dhizuku-API app gets an authorisation dialog on first use, lands in the local whitelist, and can be
  revoked on the "Dhizuku-authorized apps" page
- The compatibility layer is written independently (wire protocol aligned with Dhizuku-API), does not depend
  on the GPL Dhizuku-API library, and Shizako stays Apache-2.0

### <img src="docs/assets/icons/readme/shield-check.svg" width="19" height="19" alt="" /> Coexistence with official Shizuku, and verifying official builds

Shizako uses its **own** package name (`com.churan.shizako`) and permission names, and only declares its own
provider authority `com.churan.shizako.shizuku`. It does **not** use the upstream package name
`moe.shizuku.privileged.api`, the `Shizuku` app name, `moe.shizuku.manager.permission.*` permissions, the
upstream provider authority or the upstream icons, so it can be installed alongside official Shizuku.
(Historically the GitHub build bundled a "compatibility stub" occupying the upstream package name to please
clients that hard-code the old name; that scheme is removed entirely — it verbatim violated the upstream
Apache-2.0 §6 additional terms and was indistinguishable from a renamed repack from the outside.)

#### Official build signing certificate

All official builds are signed with the same release certificate:

| Item | Value |
| --- | --- |
| Subject | `CN=Shizako, OU=Churan, O=Churan, L=Beijing, ST=Beijing, C=CN` |
| SHA-256 | `515BAF0C727F2AFBABA8A22379A05F8F2CC98682E5BEB24FB166565ACC9F9C8A` |

Verify with `apksigner verify --print-certs shizako-*.apk` or the in-app "Settings → About" fingerprint.
A mismatch means the APK was re-signed — do not install or authorise it. F-Droid builds are re-signed by
F-Droid and differ by design.

---

## <img src="docs/assets/icons/readme/palette.svg" width="20" height="20" alt="" /> The mascot

<div align="center">
<img src="manager/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" alt="Shizako logo" />
</div>

Shizako-chan: white hair, cat ears, a crescent hairpin — a "catgirl system assistant hugging a terminal".
The project icon, in-app copy and promo materials are all built around her.

> Official blessing: feel free to use her as a reaction image (ˊᗜˋ*), just don't use her for evil.

---

## <img src="docs/assets/icons/readme/key-round.svg" width="20" height="20" alt="" /> For developers

### Option 1: Connect directly (recommended)

The official `dev.rikka.shizuku:api` already talks to both managers — **you don't have to change anything**.
Full walkthrough in [docs/API.md](docs/API.md): dependency setup, permission requests, binder lifecycle,
AIDL user-service examples.

### Option 2: Source integration

Vendor the `api/` directory into your project:

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

The code reads exactly like the official API: `Shizuku.bindUserService(...)`, `Shizuku.requestPermission(...)`
work as-is.

### Storage proxy (for trusted third parties)

The server exposes an `IStorageProxy` interface (list / open / tar pack / safe extract / copy / delete /
mkdir / exists). A third-party app needs **both**:

1. a normal Shizuku grant in Shizako;
2. its "Storage proxy" switch turned on separately in "Data backup / restore → Third-party calls".

Every call writes an audit line (caller uid, operation, path digest, result, duration). Paths pass an
allowlist and injection checks first — escalation, `../` and shell metacharacters are rejected.

---

## <img src="docs/assets/icons/readme/cpu.svg" width="20" height="20" alt="" /> Build it yourself

```bash
git clone https://github.com/cr1437/Shizako.git
cd Shizako
./gradlew :manager:assembleRelease
```

| Dependency | Version |
|------|------|
| JDK | **21** (root `build.gradle` sets `VERSION_21`, `manager` uses `jvmTarget = "21"`; no toolchain is declared — it uses `JAVA_HOME`, and **17 won't compile**) |
| Android SDK | compileSdk 36 |
| NDK | 29.0.14206865 |

- Aliyun Maven mirrors are preconfigured, no extra proxy needed
- Output lands in `manager/build/outputs/apk/release/`; release builds ship the Baseline Profile under `assets/dexopt/`
- Without `signing.properties` it falls back to the debug key (fine for personal use; **releases must use the project key** or users can't update over it)
- Deeper build / test notes in [docs/BUILD.md](docs/BUILD.md)

### Tasker / MacroDroid broadcasts

| Action | Effect |
|--------|------|
| `com.churan.shizako.action.START` | Start the service with the last-used method |
| `com.churan.shizako.action.STOP` | Stop the service |
| `com.churan.shizako.action.START_ROOT` | Start with root |
| `com.churan.shizako.action.START_ADB` | Start with wireless debugging |
| `com.churan.shizako.action.ACTIVATE_DHIZUKU` | Activate Device Owner mode in one tap (requires a running Shizuku mode) |
| `com.churan.shizako.action.QUERY_STATUS` | Query status (ordered broadcast returns `running`, `dhizuku`) |
| `com.churan.shizako.action.DOWNLOAD_UPDATE` | Download the APK pointed to by the `url` extra with the built-in downloader |
| `com.churan.shizako.action.NOTIFY_INSTALL` | Show a tappable notification to install the latest APK in Download |
| `com.churan.shizako.action.EXEC_SHELL` | Privileged command execution (token + signature verification, see the automation section) |

---

## <img src="docs/assets/icons/readme/book-open.svg" width="20" height="20" alt="" /> Source layout

**10 Gradle modules** (as listed in `settings.gradle`):

```
Shizako/
├── manager/          Android app itself (Kotlin, ~50k lines, ~85% of the repo)
│   └── storage/      App data backup / restore
├── server/           Privileged service process (Java, runs as shell / root)
│   └── storage/      Privileged storage proxy implementation
├── starter/          Service starter (spawns the server process)
├── shell/            Builds rish_shizuku.dex (`ShizukuShellLoader`), packaged into the APK
├── common/           Shared utilities (BuildUtils / OsUtils)
├── api/              Vendored Shizuku-API (MIT) — 6 submodules:
│   ├── aidl/           AIDL interface definitions
│   ├── shared/         Constants shared by client and server
│   ├── api/            Client library (rikka.shizuku.Shizuku)
│   ├── provider/       Client-side ShizukuProvider
│   ├── server-shared/  Shared server base classes
│   └── rish/           Native part of the rish CLI
├── hidden-api-stub/  Compile-time hidden-API stubs
└── docs/             Docs and assets (the website is generated from here by tools/md2html.py)
```

> The 6 modules inside `api/` have their `projectDir` rewritten to `api/<name>` in `settings.gradle`,
> which is why their directories are invisible at the top level.

---

## <img src="docs/assets/icons/readme/message-circle.svg" width="20" height="20" alt="" /> FAQ

<details>
<summary><b>Q: Does it need root?</b></summary>

A: No. Wireless debugging / USB is enough; root also works and gives more options, but it's never required.

</details>

<details>
<summary><b>Q: What's the relationship with Shizuku?</b></summary>

A: It's Shizuku-API ecosystem compatible — apps built with the official SDK connect with zero changes.
Per upstream licensing it uses its own package and permission names and takes no upstream authority, so it
coexists with official Shizuku on the same device; on top of that it adds Dhizuku compatibility,
one-click injection, data backup and other features of its own.

</details>

<details>
<summary><b>Q: Does backup touch `/data/data`?</b></summary>

A: No. It handles external data only (`/sdcard/Android/data`, `/sdcard/Android/obb`); full `/data/data`
backups are out of scope.

</details>

<details>
<summary><b>Q: How does a third-party backup tool use the storage proxy?</b></summary>

A: Give it a normal Shizuku grant first, then turn on its "Storage proxy" switch separately in
"Data backup / restore → Third-party calls". With a Shizuku grant but without the switch, the API refuses
explicitly and records the audit line.

</details>

<details>
<summary><b>Q: Can it coexist with official Shizuku?</b></summary>

A: Yes. Shizako's provider only declares its own authority and never collides with official Shizuku.
Install both and grant each separately.

</details>

<details>
<summary><b>Q: Is it safe to authorise apps?</b></summary>

A: Every app needs your explicit nod for a pass; the whitelist is local and revocable any time. Dhizuku
privilege forwarding stays inside the Shizako process, and the storage proxy needs a separate grant plus
per-call audit.

</details>

<details>
<summary><b>Q: Which Android versions are supported?</b></summary>

A: Android 7.0+ runs; wireless-debugging activation requires Android 11+.

</details>

<details>
<summary><b>Q: Pairing is stuck at "Searching for pairing service" and the port field is unusable?</b></summary>

A: Automatic discovery relies on mDNS multicast. On GrapheneOS with VPN on, multicast never reaches the
app; some Huawei / HarmonyOS devices block it too — auto-discovery will fail there by design. The port
field in the pairing dialog is always visible and typeable: read the **pairing port** from
"Developer options → Wireless debugging → Pair device with pairing code" and enter it together with the
code. If adbd is already listening on a fixed port (say 5555), you can also skip pairing entirely with
"Manual connect port" on the activation page.

</details>

<details>
<summary><b>Q: Play Protect warns "This app might be harmful" during install — is it a virus?</b></summary>

A: No. Shizako is sideloaded and has abilities like "download and install", "spawn a privileged process via
app_process" and "Device Owner", so Play Protect's heuristics easily flag Shizuku-ecosystem tools — the
decision happens on Google's side and cannot be disabled from code. Check in this order: ① download only
from [Releases](https://github.com/cr1437/Shizako/releases) and verify the certificate SHA-256
`515baf0c…9f9c8a` with `apksigner verify --print-certs <apk>`; ② if you have an Actions artifact (debug
key) or a package from an unknown source, switch to a properly signed build; ③ once verified, tap
"More details → Install anyway" in the Play Protect prompt, or turn off "Scan apps" in
"Settings → Google → Security → Play Protect" (this weakens device protection and isn't recommended long
term). The verdict belongs to Google; you can submit a false-positive appeal following the
[official guidance](https://developers.google.com/android/play-protect/warning-dev-guidance), but the
process is one-way with no promised outcome or timeline.

</details>

<details>
<summary><b>Q: Why does the package request so many "dangerous permissions"?</b></summary>

A: All are needed for features: `QUERY_ALL_PACKAGES` shows names and icons of authorised apps;
`WRITE_SECURE_SETTINGS` enables one-tap wireless debugging and accessibility management; DeviceAdmin is
Dhizuku mode itself; `STORAGE_PROXY` is the storage-proxy capability marker (the real switch is in-app).
The app never uses them silently in the background, and the permission whitelist is local and revocable.

</details>

<details>
<summary><b>Q: Why is she so cute?</b></summary>

A: Because the author raised her as a daughter (affirmed).

</details>

---

## <img src="docs/assets/icons/readme/star.svg" width="20" height="20" alt="" /> Credits

- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) and its contributors — Shizako's core comes from here
- [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API) — the client communication library
- Everyone who starred Shizako-chan

## <img src="docs/assets/icons/readme/github.svg" width="20" height="20" alt="" /> Feedback

| Channel | Link |
|------|------|
| GitHub Issues | [Issues](https://github.com/cr1437/Shizako/issues) |
| QQ feedback | [891276089](https://qm.qq.com/cgi-bin/qm/qr?k=891276089) |
| QQ community | [1104445003](https://qm.qq.com/cgi-bin/qm/qr?k=1104445003) |

## <img src="docs/assets/icons/readme/shield-check.svg" width="20" height="20" alt="" /> License

This project inherits the upstream [Apache License 2.0](LICENSE); see also [NOTICE](NOTICE).

- Original project copyright: RikkaApps; Shizako modifications copyright: Churan
- No use of the upstream `Shizuku` name, the `moe.shizuku.privileged.api` package, the
  `moe.shizuku.manager.permission.*` permissions, the upstream provider authority or upstream icons
- The `api/` directory follows its own MIT License

### Name and artwork (brand)

Following the same form as the upstream Apache-2.0 §6 addendum, this project declares:

- **Forbidden** to use `Shizako` (including `Shizako+`, `Shizako Plus`, `Shizako Pro` or any confusingly
  similar variant) as the name of a third-party app, or as part of its package name, application id or
  permission names;
- **Forbidden** to use this project's mascot artwork or launcher icons
  (`manager/src/main/res/mipmap*/ic_launcher*.png` and all related assets) for anything other than
  displaying Shizako itself;
- **Forbidden** to distribute any non-official build under the application id `com.churan.shizako`.

In other words: the code is (and is welcome to be) forked — **change the name, icons and package name**.
That satisfies Apache-2.0 and lets users tell official builds apart at a glance, which is the most
effective anti-impersonation step of all.

---

<div align="center">

> <img src="docs/assets/icons/readme/star.svg" width="18" height="18" alt="" /> **Like Shizako-chan? Star before you go** — every star is cat food `(ฅ'ω'ฅ)`
>
> [Feed her a Star →](https://github.com/cr1437/Shizako)

</div>
