# Changelog

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
