# MoleBug

A personal Android debug tool for two things:

1. **System Check** — verify that a Huawei/EMUI device running [microG](https://microg.org/) (instead of full Google Mobile Services) has all the basic components installed correctly, and inspect the full list of installed apps with their version/installer source.
2. **Target App Capture** — capture crash/ANR/stall events for *another* installed app, without root, by combining `logcat`, `UsageStatsManager`, `AccessibilityService`, and a handful of other non-root-friendly Android APIs.

Built for a Huawei nova 9 SE (EMUI 12 / Android 12) running microG, but works on any non-rooted Android 8.0+ (API 26+) device.

## Why this exists

Diagnosing why an app keeps crashing or hanging is usually a job for `adb logcat`. But sometimes you don't have a PC handy, or the issue only happens during normal daily use away from a desk. MoleBug runs the closest non-root approximation of `adb logcat` directly on the device, with extra diagnostics layered on top that plain `adb logcat` doesn't give you for free.

## Capability tiers

| Tier | What it needs | What you get |
|---|---|---|
| **Tier 1** | Nothing beyond in-app permission prompts | Foreground/background timing, system "app has stopped" dialog detection — no PC ever required |
| **Tier 2** (default target) | One-time `adb shell pm grant` for `READ_LOGS` and `DUMP` | Real `logcat` stack traces, ANR detection, official OS exit-reason, memory/network snapshots |
| Tier 3 (Shizuku) / Tier 4 (root) | Not implemented | Deliberately out of scope — see [Permissions](#permissions) |

The `READ_LOGS`/`DUMP` grant only needs to happen **once** per install (survives until uninstall), and the app works without it — it just falls back to Tier 1.

## Features

### System Check (Home screen)
- Device info, shown in a collapsible card (auto-collapses to just the title on scroll, re-expands at the top) and grouped by category — Device, CPU, RAM, GPU, Battery, each independently collapsible:
  - **Device**: manufacturer/model, EMUI version
  - **CPU**: ABI, cores, vendor, max/realtime frequency per core (live, polled every second) with mini-cards, temperature
  - **RAM**: total, used (live), type (best-effort vendor property, since Android exposes no public API for it)
  - **GPU**: renderer string (queried via a throwaway EGL context), realtime frequency, temperature
  - **Battery**: percent, health, charging status
- Checks 4 required components for a working microG setup: microG Services, Framework Proxy, microG Companion (Play Store substitute), Aurora Store — each with installed version and installer source
- Detects ReVanced/Vanced packages and flags them: red card for ones that actually conflict with microG's package (`com.mgoogle.android.gms`, `app.revanced.android.gms`), yellow card for patching-tool-only apps (`com.vanced.manager`, `app.revanced.manager.flutter`) that don't conflict by themselves
- Export a full snapshot (device info + component status + every installed app with version/installer) to a text file, with in-app search, collapsible preview, and a Share button
- A permissions modal pops in a couple seconds after launch, collapses to a small pastel pill once Tier 1 is granted (tap to reopen for Tier 2), and gates the "Go to Target App Log Capture" button until Tier 1 is satisfied

### Target App Capture
- Pick any installed app, arm capture, and it launches automatically — no manual app-switching
- A Tier 1/2 badge next to "Target App" shows what capability level is currently active, with a hint of what's missing at Tier 1 only
- A floating "● Record" pill (blinking red) plus a white square stop button, so you can stop a session on demand at any point, not just after a crash
- Real-time pid discovery by parsing ActivityManager's own log output — no `pidof`/`ps`/`sh` exec involved (which is blocked by SELinux for normal apps anyway)
- **Target App Info** captured in every session's log header: install source, install date, requested permissions with grant state, network data used since install, notification status, and APK MD5/SHA-1/SHA-256 checksums
- Crash stack traces, ANR detection (system buffer, not pid-filtered), low-memory-killer detection (kernel buffer), official process exit reason (`ActivityManager#getHistoricalProcessExitReasons`)
- Additional diagnostic detectors for previously-invisible failure modes:
  - Black-screen/render-stall detection (Choreographer, OpenGLRenderer, MediaCodec/NuPlayer/ExoPlayer signals on the target's own pid)
  - GMS/microG API failure detection (sign-in loops, dead-object errors)
  - SELinux denial detection on the target's uid
  - Native crash signal detection (non-Java crashes)
  - Force-stopped state and free internal storage logged at arm time
- **Capture Options checklist** (all optional, persisted, default on):
  - Network timing — elapsed time from foreground to crash/ANR
  - ANR trace file content — reads `/data/anr` for the full stuck-thread stack trace (not guaranteed readable on every device/ROM)
  - Events buffer — tails `logcat -b events` for `am_anr`/`am_crash` entries
  - Stall watchdog — flags an app that's silently stuck (0% CPU, no crash, no ANR, no log output at all) with a memory/network/socket snapshot
- Network socket snapshot (`/proc/net/tcp[6]`) at crash/ANR/stall moments — settles whether the app was actually waiting on a pending network response
- Log Viewer: search with highlight + jump, a draggable scrollbar, a live scroll-position indicator, and live file-size readout — built for logs running into the thousands of lines; ADB grant commands are shown as a mock terminal instead of a copy button, and a Files-app icon copies the current log to the public `Download/MoleBug` folder and opens the Files app

## Permissions

| Permission | Why | How to grant |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | Floating record/stop overlay | In-app button → Settings |
| `PACKAGE_USAGE_STATS` | Foreground/background timing (Tier 1) | In-app button → Settings |
| Accessibility Service | Detect target app foreground + system crash dialogs | In-app button → Settings |
| `QUERY_ALL_PACKAGES` | See every installed app in the target picker | Declared in manifest (this app is sideloaded only, never distributed on Play Store, so the store policy restriction on this permission doesn't apply) |
| `READ_LOGS` (optional, Tier 2) | Real `logcat` capture | `adb shell pm grant com.debug.molebug android.permission.READ_LOGS` — once |
| `DUMP` (optional, Tier 2) | Official process exit reason | `adb shell pm grant com.debug.molebug android.permission.DUMP` — once |

If you have zero access to any PC with `adb`, even once, the app still works fully at Tier 1 — see the in-app hints on the target picker screen.

## Building

```bash
git clone https://github.com/grenadin/MoleBug.git
cd MoleBug
./gradlew assembleDebug
```

Open in Android Studio and run normally also works — `gradlew`/wrapper files are committed, no extra setup needed.

- `compileSdk` / `targetSdk`: 34
- `minSdk`: 26
- Kotlin + Jetpack Compose (Material3)

## Localization

UI text follows the device's system language: Thai (`values-th`) when set to Thai, English (default `values`) otherwise. Diagnostic log content written to capture files (`[LOGCAT]`, `[SYSTEM]`, etc.) is always English, regardless of device locale, so logs stay greppable/searchable consistently.

## Known limitations

- `pidof`/`ps`/`sh` cannot be exec'd from the app's own process (SELinux `untrusted_app` domain) — pid discovery is done by parsing `ProcessRecord` text out of the `system` log buffer instead, which `logcat` itself can read.
- Forcing a live thread dump of another app's process (`kill -3`) requires root — confirmed this fails even from a real `adb shell` session on a non-rooted device, so it's not a gap specific to this app.
- `/data/anr` readability for the ANR-trace-content option depends on the device/ROM's file permissions, not on the `READ_LOGS`/`DUMP` grants — it fails gracefully with a clear log message if unreadable.
- Tier 3 (Shizuku) and Tier 4 (root) are intentionally not implemented.
