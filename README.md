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
- Device info, shown in a card that's manually collapsible (tap the highlighted title) and grouped by category — Device, CPU, RAM, GPU, Battery, each independently collapsible:
  - **Device**: manufacturer/model, EMUI version
  - **CPU**: ABI, cores, vendor, max/realtime frequency per core (live, polled every second) with mini-cards, temperature
  - **RAM**: total, used (live)
  - **GPU**: renderer string (queried via a throwaway EGL context), realtime frequency, temperature
  - **Battery**: percent, health, charging status
- Checks required components for a working microG setup: microG Services, Framework Proxy, microG Companion (Play Store substitute), Aurora Store (Optional), GBox (Optional), AppGallery — each with installed version, installer source, and whether it's a system or user app; the section is collapsible (collapsed by default)
  - When microG Services, microG Companion, microG Services Framework Proxy, or Aurora Store isn't installed, a hint links straight to the right community download (GitHub releases for microG/GmsCore/GsfProxy, a community-hosted Huawei build for Aurora Store)
  - An on-demand "Check for updates" button on those same four rows compares the installed version against the latest GitHub/GitLab release
- microG Companion, Aurora Store, GBox, and AppGallery rows have a 📦 scan button that lists every installed app whose installer source actually matches that store, sortable ascending/descending by app name
- Detects ReVanced/Vanced packages and flags them: red card for ones that actually conflict with microG's package (`com.mgoogle.android.gms`, `app.revanced.android.gms`), yellow card for patching-tool-only apps (`com.vanced.manager`, `app.revanced.manager.flutter`) that don't conflict by themselves
- Export a full snapshot (device info + component status + every installed app with version/installer) to a text file, with in-app search, collapsible preview, and a Share button — dispatched off the main thread so a device with a lot of installed apps can't trigger an ANR while it's building
- A small permissions pill always stays pinned to the bottom of the screen (even once fully granted and collapsed, instead of scrolling away with the page); it expands into the full Permission Required card when tapped, when "Go to Target App Log Capture" is pressed with Tier 1 incomplete, or when a permission is later revoked

### Target App Capture
- Pick any installed app, arm capture, and it launches automatically — no manual app-switching
- A Tier 1/2 badge next to "Target App" shows what capability level is currently active, with a hint of what's missing at Tier 1 only
- A floating "● Record" pill (blinking red) plus a white square stop button, so you can stop a session on demand at any point, not just after a crash
- Real-time pid discovery by parsing ActivityManager's own log output — no `pidof`/`ps`/`sh` exec involved (which is blocked by SELinux for normal apps anyway)
- **Target App Info** captured in every session's log header: install source, install date, requested permissions with grant state, network data used since install, notification status, and APK MD5/SHA-1/SHA-256 checksums
- Crash stack traces, ANR detection (system buffer, not pid-filtered), low-memory-killer detection (kernel buffer), official process exit reason (`ActivityManager#getHistoricalProcessExitReasons`)
- Additional diagnostic detectors for previously-invisible failure modes:
  - Black-screen/render-stall detection (Choreographer, OpenGLRenderer, MediaCodec/NuPlayer/ExoPlayer signals on the target's own pid), summarized with a total count at session stop
  - GMS/microG API failure detection (sign-in loops, dead-object errors)
  - SELinux denial detection on the target's uid
  - Native crash signal detection (non-Java crashes)
  - Doze (device idle) and battery-saver transitions logged for the whole session, not just at crash/ANR time
  - Force-stopped state and free internal storage logged at arm time
- **Capture Options checklist** (all optional, persisted, default on):
  - Network timing — elapsed time from foreground to crash/ANR
  - ANR trace file content — reads `/data/anr` for the full stuck-thread stack trace (not guaranteed readable on every device/ROM)
  - Events buffer — tails `logcat -b events` for `am_anr`/`am_crash` entries
  - Stall watchdog — flags an app that's silently stuck (0% CPU, no crash, no ANR, no log output at all) with a memory/network/socket snapshot
  - Touch watchdog — flags a screen that keeps receiving touch/gesture activity but never confirms a UI response (scroll/click/window-change) for 3 seconds (`[UNRESPONSIVE-TOUCH]`); built from a real repro where handing an install off to the microG Installer left the calling app's screen frozen for a few seconds afterward with no crash/ANR/stall signal
- Network socket snapshot (`/proc/net/tcp[6]`) at crash/ANR/stall moments — settles whether the app was actually waiting on a pending network response
- Log Viewer: search with highlight + jump, a draggable scrollbar, a live scroll-position indicator, and live file-size readout — built for logs running into the thousands of lines; ADB grant commands are shown as a mock terminal instead of a copy button, and a Files-app icon copies the current log to the public `Download/MoleBug` folder and opens the Files app
- **Diagnostic Summary** (from the Log Viewer): scans the captured log against a growing database of known protection/anti-tamper SDK signatures (currently V-Key V-OS, Google Play Integrity API) plus the touch watchdog above, shown as a tappable 100%-stacked bar you drill into (category → SDK → detail log lines, lazily loaded). A quick assessment card up top gives a plain-language read of whether anything suspicious was found, including a best-effort root-cause guess for any `[UNRESPONSIVE-TOUCH]` hits based on what's actually in the log nearby (not a fixed assumption).

## Permissions

| Permission | Why | How to grant |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | Floating record/stop overlay | In-app button → Settings |
| `PACKAGE_USAGE_STATS` | Foreground/background timing (Tier 1) | In-app button → Settings |
| Accessibility Service | Detect target app foreground + system crash dialogs | In-app button → Settings |
| `QUERY_ALL_PACKAGES` | See every installed app in the target picker | Declared in manifest (this app is sideloaded only, never distributed on Play Store, so the store policy restriction on this permission doesn't apply) |
| `READ_LOGS` (optional, Tier 2) | Real `logcat` capture | `adb shell pm grant com.debug.molebug android.permission.READ_LOGS` — once |
| `DUMP` (optional, Tier 2) | Official process exit reason | `adb shell pm grant com.debug.molebug android.permission.DUMP` — once |
| `INTERNET` | On-demand "Check for updates" against GitHub/GitLab release APIs — only on a button tap, never automatic/background | Declared in manifest (normal permission, no prompt) |

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

## Privacy

- Logs never leave the device on their own — there's no analytics, no telemetry, no background upload. The only way a log leaves the device is the explicit Share button.
- Captured lines come straight from logcat/system buffers written by *the target app itself* — MoleBug only filters by package name, it doesn't understand the content. Before every line is written to disk, it's run through a redaction pass that masks email addresses, `Authorization`/`Bearer` header values, JWTs, long opaque tokens (API keys/session ids), and card-number-shaped digit runs — a safety net for the case where the target app's own logging accidentally includes something sensitive.
- Capture/export log files live in the app's own external-files directory (`Android/data/com.debug.molebug/...`), which other apps can't read directly on Android 11+ — only reachable through MoleBug's own `FileProvider` grants (Share button, Files-app copy).

## Known limitations

- `pidof`/`ps`/`sh` cannot be exec'd from the app's own process (SELinux `untrusted_app` domain) — pid discovery is done by parsing `ProcessRecord` text out of the `system` log buffer instead, which `logcat` itself can read.
- Forcing a live thread dump of another app's process (`kill -3`) requires root — confirmed this fails even from a real `adb shell` session on a non-rooted device, so it's not a gap specific to this app.
- `/data/anr` readability for the ANR-trace-content option depends on the device/ROM's file permissions, not on the `READ_LOGS`/`DUMP` grants — it fails gracefully with a clear log message if unreadable.
- Tier 3 (Shizuku) and Tier 4 (root) are intentionally not implemented.
