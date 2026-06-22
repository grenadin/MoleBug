# Changelog

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
