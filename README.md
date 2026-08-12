# NEXTERM

A real terminal emulator for Android. Local shell, SSH, and full Linux distributions
running under proot — no root required.

Kotlin · Jetpack Compose · Material 3 · MVVM · Hilt · Room

[![Download](https://img.shields.io/badge/download-NEXTERM.apk-2ea44f?style=for-the-badge&logo=android&logoColor=white)](https://github.com/Dreamucxe/NEXTERM/releases/latest/download/NEXTERM.apk)
[![Release](https://img.shields.io/github/v/release/Dreamucxe/NEXTERM?style=for-the-badge)](https://github.com/Dreamucxe/NEXTERM/releases/latest)
[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-3ddc84?style=for-the-badge&logo=android&logoColor=white)](#download)

---

## Download

**[⬇ Download NEXTERM.apk](https://github.com/Dreamucxe/NEXTERM/releases/latest/download/NEXTERM.apk)** — signed release build, install directly.

Or browse [all releases](https://github.com/Dreamucxe/NEXTERM/releases).

**Requirements**

| | |
|---|---|
| Android | 7.0 or newer (API 24+) |
| CPU | 64-bit ARM (arm64-v8a) for Linux environments |
| Root | Not required |
| Size | 13.3 MB |

To install, open the APK and allow installation from your browser or file manager when
prompted. The build is signed but not distributed through Play, so Android will ask.

Verify what you downloaded, if you like — the current release is

```
sha256  84af4ffc4bc085d18666ec2c5eb4a455dc323244a4b5f51ccef5df67f8ab5d02
```

signed by `CN=NEXTERM, OU=Terminal, O=NEXTERM`, certificate SHA-256
`2b81bc12ae276f4d2a2bd78783a5d4db5efc32dce5b35a55752e78b0bac091bc`. Every release is
signed with the same key, which is what lets Android accept a later one as an update.
Per-release checksums are on each [release page](https://github.com/Dreamucxe/NEXTERM/releases).

---

## What it does

**Local shell** — a real PTY on Android's own shell, with a full VT/xterm-256color
emulator, scrollback, text selection, and a programmable key row.

**Linux environments** — installs Alpine, Debian, Ubuntu, Arch and others from the
proot-distro catalogue: downloaded, checksum-verified, and extracted on device, then
entered through proot. `apt install` works. So does `gcc`.

**SSH** — key or password auth, with credentials held in the Android Keystore.

**Files** — a browser over app storage, shared storage via SAF, and the guest
filesystems, with privilege reported honestly rather than assumed.

**Sessions** — multiple tabs, groups, split view, snippets with placeholders, and quick
commands.

Nothing here is mocked. There is no fake terminal output, no simulated filesystem, and
no button that opens a placeholder dialog. When something cannot work on a device — no
proot for the CPU, no root, no permission — the app says so and explains why.

## Privilege, stated accurately

The app never assumes root. Every filesystem operation checks the privilege it actually
has, and the environments screen reports the result of a live probe: an `su` that really
ran, a Shizuku binder that really answered, a proot binary really found on disk.

Shizuku runs commands as the shell user (uid 2000) — not as root. It reaches places an
app cannot, such as `/data/local/tmp`, but it is not unrestricted root access, and
NEXTERM does not claim otherwise.

## The interesting constraint

Android has mounted app-writable storage W^X since API 29, so an app may not execute a
binary it downloaded. The only app-owned directory that is executable is the native
library directory, which the installer fills at install time — so proot has to ship
inside the APK under `lib/<abi>/` named `lib*.so`, whatever it actually is.

That applies to proot's loader too. proot normally writes its loader out to a temp file
and executes that, which is exactly what the sandbox refuses, so NEXTERM ships the
loader as a native library and points `PROOT_LOADER` at it.

Two proot builds are bundled, each with the loader it was compiled against. The Termux
fork is tried first — it is the build maintained against Android — with upstream's
static build as a fallback. Since the fork is dynamically linked, its libraries ship
beside it and `LD_LIBRARY_PATH` names the directory explicitly: bionic does not search
the native library directory for an `exec`'d process.

Which build and which syscall-interception mode a device tolerates is not assumed. On
the first session per environment the app runs `sh -c 'exit 0'` through the real command
line in each combination and keeps whichever exits 0. If none does, it reports what
proot printed, the signal that killed it, and the ELF details of every binary involved —
rather than an exit code with no explanation.

## Building

Requires JDK 17 and the Android SDK (compileSdk 34).

```bash
git clone https://github.com/Dreamucxe/NEXTERM
cd NEXTERM
chmod +x gradlew
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

Release signing is optional: create `keystore.properties` in the project root with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword`, and `assembleRelease` will
produce a signed, directly installable APK. Without it the build is unsigned. That file
and the keystore are gitignored and must never be committed.

## Architecture

```
UI (Compose) → ViewModel → Domain → Repository → Data / Terminal engine / FileSystem
```

Composables arrange and observe; they hold no business logic. No composable starts a
process, writes to a PTY, or touches the database. Room schema changes ship as
migrations — the database is never wiped. Destructive actions confirm first. Passwords
and keys are never logged, and never stored in plaintext.

## Third-party software

NEXTERM bundles proot (GPL-2.0) and talloc (LGPL-3.0-or-later) and executes them as
separate processes rather than linking against them. Full notices, per-file checksums,
the two ELF modification notices, and the written offers of source code are in
[`app/src/main/assets/licenses/NOTICE.txt`](app/src/main/assets/licenses/NOTICE.txt) —
summarised for this repository in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md), and readable in the app under
Settings → Licences.
