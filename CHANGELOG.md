# Changelog

## 1.5.1 (versionCode 7)

### Added
- AppGallery and GBox rows in Check Basic Apps now show a download link when not installed (official Huawei AppGallery page / gboxlab.com), matching the existing microG/Aurora Store hints.
- App version number now shown under the logo on the Home screen.
- AppGallery row moved above GBox in Check Basic Apps.

### Changed
- microG Services/Companion "wrong build" detection simplified: now reads the installed `-hw` flavor suffix directly off `versionName` (confirmed baked in at compile time by GmsCore's own `productFlavors` block) instead of comparing file sizes against a GitHub release asset over the network — no network call needed, and it's exact instead of a heuristic.
- Update-check version comparison for microG Services/Companion now parses the exact versionCode out of the matching GitHub release asset's own filename, scoped to the correct package's asset specifically (fixes a mismatch where `com.android.vending` could accidentally compare against `com.google.android.gms`'s asset in the same release).

### Fixed
- Home screen's Device Information could show a block of blank space after a Huawei Mate X6 folds/unfolds (switches between its two physical displays) — `pageScrollState`'s saved scroll offset no longer survives a screen-size change in a way that leaves it stranded relative to content that didn't grow to match.
- `InfoRow`'s label could be squeezed out entirely on a very narrow display (confirmed on the Mate X6's ~345dp-wide cover screen in portrait) when its value was long — label and value now split the row width evenly instead of value getting first claim.
- Reduced Home screen's side margins (16dp→8dp page padding, 12dp→4dp inside the Device Information card) so narrow displays have more room for label/value text before wrapping.

## 1.5 (versionCode 6)

### Added
- **Diagnostic Summary** screen, reachable from the Log Viewer: scans a captured log against a growing database of known protection/anti-tamper SDK signatures (currently V-Key V-OS and Google Play Integrity API) and renders the breakdown as a tappable 100%-stacked bar — tap a category to drill into its SDKs, tap an SDK to see its detail log lines (lazily loaded, with an internal scrollbar only when content actually overflows).
  - A quick plain-language assessment card up top says whether anything suspicious was found at all, and now includes root-cause analysis for the new touch-watchdog finding below.
  - Every captured line counts toward the 100% total, not just lines matching a known signature — unmatched lines land in an "Other" category, split out by logcat tag (or bracket marker like `[GRANTED]`/`[WINDOW]` when no tag is parseable) instead of fanning out into one card per distinct message.
- **Touch watchdog** (new Capture Option, on by default): flags a screen that keeps receiving touch/gesture activity but never confirms a UI response (scroll/click/window-change) for 3 seconds — `[UNRESPONSIVE-TOUCH]` in the log. Found via a real repro: Aurora Store handing an install off to the microG Installer can leave its Downloads page frozen for a few seconds afterward, with no ANR/crash/stall signal MoleBug previously had any way to catch.
- **Update checker** for the three microG/Aurora rows in Check Basic Apps: an on-demand "Check for updates" button compares the installed version against the latest GitHub (microG GmsCore/GsfProxy) or GitLab (Aurora Store) release.
- Download hints with direct links for microG Services, microG Companion, microG Services Framework Proxy, and Aurora Store (Huawei build) when not installed.
- Aurora Store and GBox rows labeled "(Optional)" in Check Basic Apps.

### Changed
- The Permission Required card/pill now stays pinned to the bottom of the Home screen at all times (including once fully granted and collapsed) instead of scrolling away with the page — it only expands back out when tapped or re-triggered by a permission change.
- The Permission Required card's pop-in animation no longer has a bouncy spring overshoot — plain slide-up + fade now.
- "Get Installed App List + Save Log" no longer runs on the main thread — it was capable of triggering an ANR on devices with a lot of installed apps (one IPC call per app). Now dispatched off-thread with a "⏳ กำลังดึงรายการแอป…" loading state on the button.

### Fixed
- `return` inside a `withContext` block (not legal for a `noinline` lambda parameter) in the export-log path.

## 1.4 (versionCode 5)

### Added
- **"Installed via [Store]" scan** (📦 button) on the microG Companion, Aurora Store, GBox, and AppGallery rows in Check Basic Apps: scans every installed app's installer source and lists which ones actually came from that store, with a friendly installer name mapping for this section.
- Each scan result list can be sorted ascending/descending by app name (digits before letters), with the sort toggle living on the triggering row itself so it stays visible while the result list scrolls.
- Only one store's scan result is shown at a time — opening a different store's scan automatically closes whatever was already open.
- Each app row now shows whether it's a system app or a user-installed app.
- Check Basic Apps section is now collapsible (collapsed by default), matching Device Information's collapse/expand pattern.
- Device Information and Check Basic Apps section titles are now highlighted with a colored background for easier scanning.
- Tap ripple effect across Home and Target Picker is now bolder (theme-colored instead of the faint default) and covers a larger tap area on small icon buttons.
- Target Picker's "what will actually run" comic-style scroll: its close button now floats just outside the scroll's own card instead of overlapping its top edge.
- Doze (device idle) and battery-saver transitions are now logged (`[POWER-CHANGE]`) for the whole capture session, not just at crash/ANR time.
- Render-stall events are now counted and summarized once at session stop (`[RENDER-STALL-SUMMARY]`).

### Changed
- The Permission Required card no longer pops up automatically a few seconds after opening the app. It now only reopens when "Go to Target App Log Capture" is pressed while Tier 1 permissions are still incomplete (a small pill still floats at the bottom in the meantime).
- Device Information no longer auto-collapses when scrolled past — it now only collapses/expands on manual tap.
- RAM Type removed from Device Information, export logs, and capture logs — Huawei/EMUI firmware on the test device doesn't expose this through any public or vendor system property, so it always read as "Unknown".

### Fixed
- Fixed a crash (`ClassCastException`) when tapping the 📦 scan button, caused by a Compose slot-table mismatch in the Check Basic Apps list.
- GPU Frequency now reads correctly on Kirin (HiSilicon) devices — added a direct sysfs path (`/sys/class/devfreq/gpufreq/cur_freq`) instead of relying solely on a directory scan that this SoC's SELinux policy blocks for the app's own process.

## 1.3 (versionCode 4)

### Added
- Share intents now pre-fill an AI-analysis prompt alongside the attached log file (both the Home-screen export log and the capture log), for share targets that read `EXTRA_TEXT` next to a file (most chat-style AI apps).
- Sensitive-data redaction on every captured log line before it's written to disk: email addresses, Authorization/Bearer header values, JWTs, long opaque tokens (API keys/session ids), and card-number-shaped digit runs are masked. Protects against the target app itself accidentally logging sensitive data that would otherwise be captured verbatim and potentially shared with a third party.

## 1.2 (versionCode 3)

### Added
- **Target App Info** captured in every capture-log header: install source, install date, requested permissions with grant state, network data used since install, notification status, and APK MD5/SHA-1/SHA-256 checksums.
- New crash-diagnostic coverage for previously-invisible failure modes:
  - Black-screen/render-stall detection (Choreographer, OpenGLRenderer, MediaCodec/NuPlayer/ExoPlayer)
  - GMS/microG API failure detection (sign-in loops, dead-object errors)
  - SELinux denial detection
  - Native crash signal detection (non-Java crashes)
  - Force-stopped state and free internal storage logged at arm time
- Device Information card: each category (Device/CPU/RAM/GPU/Battery) now collapses independently, per-core CPU frequency mini-cards with live 1s polling, CPU/GPU temperature, GPU frequency, RAM used — all live values, plus a glassy card visual style.
- GMS/microG conflict detection for ReVanced/Vanced packages, with red (real package conflict) vs yellow (patching tool only) warning cards.
- Permissions setup moved off the Target Picker screen onto a Home-screen modal that pops in shortly after launch, collapses to a small pastel pill once Tier 1 is granted, and gates the "Go to Target App Log Capture" button until Tier 1 is satisfied.
- Target Picker: Tier 1/2 badge next to "Target App" with a hint of what's missing at Tier 1, Capture Options moved below the Start Capture button, narrower search field, trimmed app-list height so the Start button stays visible without scrolling.
- Log Viewer: ADB grant commands now shown as a mock terminal (black background, green text) instead of a copy button; a Files-app icon copies the current log to the public Download/MoleBug folder and opens the Files app.
- 3D-styled primary buttons across Home, Target Picker, and Log Viewer.

## 1.1 (versionCode 2)

### Added
- Device Information now reports additional hardware/battery details:
  - RAM total and RAM type
  - CPU vendor and CPU realtime (current) frequency, alongside the existing max frequency
  - Battery percent, battery health, and charging status
- Device Information is now grouped into clear categories — Device, CPU, RAM, GPU, Battery — both on screen and in exported logs/capture headers.
- Device Information is now shown as a collapsible Card: it auto-collapses to just its title when the page is scrolled away from the top, and re-expands when scrolled back up.

### Fixed
- The floating Stop button on the capture overlay no longer towers over the Record pill — it's now sized and vertically aligned to match the Record pill's height.
