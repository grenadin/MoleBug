package com.debug.molebug.capture

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.debug.molebug.DeviceInspector
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Holds the state of an event-capture session and writes log lines to disk
 * immediately so nothing is lost if the app/service is killed.
 */
object CaptureManager {
    private const val PREFS = "molebug_capture"
    private const val KEY_TARGET_PKG = "target_pkg"
    private const val KEY_CAPTURING = "capturing"
    private const val KEY_ARMED = "armed" // true after user taps "start" but before target app is foregrounded
    private const val KEY_LOG_PATH = "log_path"
    private const val KEY_CRASH_COUNT = "crash_count"
    private const val KEY_OPT_NETWORK_TIMING = "opt_network_timing"
    private const val KEY_OPT_ANR_TRACE = "opt_anr_trace"
    private const val KEY_OPT_EVENTS_BUFFER = "opt_events_buffer"
    private const val KEY_OPT_STALL_WATCHDOG = "opt_stall_watchdog"
    private const val KEY_OPT_TOUCH_WATCHDOG = "opt_touch_watchdog"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Ephemeral, in-memory only (unlike the SharedPreferences-backed state above) — these are
    // just timestamps a live capture session compares against itself, shared between
    // CaptureService (touch-dispatch logcat lines) and MoleAccessibilityService (scroll/click/
    // window-change events) so the unresponsive-touch watchdog can tell "a touch happened" apart
    // from "the app's own UI actually responded to it".
    @Volatile private var lastTouchSignalMs = 0L
    @Volatile private var lastUiResponseMs = 0L

    fun recordTouchSignal() { lastTouchSignalMs = System.currentTimeMillis() }
    fun recordUiResponseSignal() { lastUiResponseMs = System.currentTimeMillis() }
    fun lastTouchSignalAt(): Long = lastTouchSignalMs
    fun lastUiResponseAt(): Long = lastUiResponseMs
    private fun resetTouchSignals() {
        lastTouchSignalMs = 0L
        lastUiResponseMs = 0L
    }

    fun arm(context: Context, targetPackage: String) {
        resetTouchSignals()
        val dir = File(context.getExternalFilesDir(null), "capture_logs")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "molebug_${targetPackage}_$ts.txt")
        prefs(context).edit()
            .putString(KEY_TARGET_PKG, targetPackage)
            .putString(KEY_LOG_PATH, file.absolutePath)
            .putBoolean(KEY_ARMED, true)
            .putBoolean(KEY_CAPTURING, false)
            .putInt(KEY_CRASH_COUNT, 0)
            .apply()
        val deviceInfo = DeviceInspector.getDeviceInfo(context)
        val targetAppInfo = try {
            DeviceInspector.getTargetAppInfo(context, targetPackage)
        } catch (e: Exception) {
            null
        }
        // "Can't launch at all" is often the app sitting in the system's force-stopped state
        // (user/system force-stopped it, or a fresh install never opened yet) rather than a
        // crash — surfaced here since it's only knowable right before launch, not mid-capture.
        val isForceStopped = try {
            (context.packageManager.getApplicationInfo(targetPackage, 0).flags and
                android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
        } catch (e: Exception) {
            null
        }
        // Low internal storage is a common silent cause of "won't launch"/"won't install
        // update" failures that look identical to a crash from the user's side.
        val freeStorageMb = try {
            android.os.StatFs(context.filesDir.path).availableBytes / (1024 * 1024)
        } catch (e: Exception) {
            null
        }
        val header = buildString {
            appendLine("===== MoleBug Capture Log =====")
            appendLine("Target package: $targetPackage")
            appendLine("Armed at: $ts")
            appendLine()
            appendLine("---- Target App Info ----")
            if (targetAppInfo != null) {
                appendLine("Version: ${targetAppInfo.versionName} (code ${targetAppInfo.versionCode})")
                appendLine("Installed from: ${targetAppInfo.installer}")
                appendLine("Installed at: ${targetAppInfo.installedAt}")
                appendLine("Data used since install: ${targetAppInfo.dataUsageSinceInstall}")
                appendLine("Notifications: ${targetAppInfo.notificationStatus}")
                appendLine("APK MD5: ${targetAppInfo.apkMd5}")
                appendLine("APK SHA-1: ${targetAppInfo.apkSha1}")
                appendLine("APK SHA-256: ${targetAppInfo.apkSha256}")
                appendLine("Requested permissions (${targetAppInfo.requestedPermissions.size}):")
                targetAppInfo.requestedPermissions.forEach { (name, granted) ->
                    appendLine("  [${if (granted) "GRANTED" else "DENIED"}] $name")
                }
            } else {
                appendLine("unavailable: could not read target app info")
            }
            appendLine("Force-stopped state: ${isForceStopped?.let { if (it) "STOPPED (won't auto-launch from background until user opens it manually)" else "Not stopped" } ?: "unavailable"}")
            appendLine("Free internal storage at arm time: ${freeStorageMb?.let { "$it MB" } ?: "unavailable"}")
            appendLine()
            appendLine("---- Device Info ----")
            appendLine("[Device]")
            appendLine("Device name: ${deviceInfo.deviceName}")
            appendLine("Model: ${deviceInfo.model}")
            appendLine("Build number: ${deviceInfo.buildNumber}")
            appendLine("Software version: ${deviceInfo.softwareVersion}")
            appendLine("EMUI version: ${deviceInfo.emuiVersion}")
            appendLine("[CPU]")
            appendLine("CPU ABI: ${deviceInfo.cpuAbi}")
            appendLine("CPU cores: ${deviceInfo.cpuCores}")
            appendLine("CPU vendor: ${deviceInfo.cpuVendor}")
            appendLine("CPU max frequency: ${deviceInfo.cpuMaxFreqMHz}")
            appendLine("CPU realtime frequency: ${deviceInfo.cpuCurFreqMHz}")
            appendLine("CPU temperature: ${deviceInfo.cpuTempC}")
            deviceInfo.cpuCoreFreqs.forEach { core ->
                appendLine("  Core ${core.coreIndex}: max ${core.maxFreqMHz}, now ${core.curFreqMHz}")
            }
            appendLine("[RAM]")
            appendLine("RAM total: ${deviceInfo.ramTotal}")
            appendLine("RAM used: ${deviceInfo.ramUsed}")
            appendLine("[GPU]")
            appendLine("GPU renderer: ${deviceInfo.gpuRenderer}")
            appendLine("GPU frequency: ${deviceInfo.gpuFreqMHz}")
            appendLine("GPU temperature: ${deviceInfo.gpuTempC}")
            appendLine("GPU OpenGL ES version: ${deviceInfo.gpuGlEsVersion}")
            appendLine("GPU Vulkan version: ${deviceInfo.gpuVulkanVersion}")
            appendLine("[Battery]")
            appendLine("Battery percent: ${deviceInfo.batteryPercent}")
            appendLine("Battery health: ${deviceInfo.batteryHealth}")
            appendLine("Battery status: ${deviceInfo.batteryStatus}")
            appendLine("[Display]")
            appendLine("Resolution: ${deviceInfo.displayResolution}")
            appendLine("Current refresh rate: ${deviceInfo.displayRefreshRateCurrent}")
            appendLine("Supported refresh rates: ${deviceInfo.displayRefreshRatesSupported}")
            appendLine("[Storage]")
            appendLine("Total storage: ${deviceInfo.storageTotal}")
            appendLine("Used storage: ${deviceInfo.storageUsed}")
            appendLine("Free storage: ${deviceInfo.storageFree}")
            appendLine()
        }
        file.writeText(header)
    }

    fun isArmed(context: Context) = prefs(context).getBoolean(KEY_ARMED, false)
    fun isCapturing(context: Context) = prefs(context).getBoolean(KEY_CAPTURING, false)
    fun targetPackage(context: Context): String? = prefs(context).getString(KEY_TARGET_PKG, null)
    fun logPath(context: Context): String? = prefs(context).getString(KEY_LOG_PATH, null)

    fun startCapturing(context: Context) {
        prefs(context).edit().putBoolean(KEY_CAPTURING, true).putBoolean(KEY_ARMED, false).apply()
        appendLog(context, "[SYSTEM] Started capturing log — detected target app coming to foreground")
    }

    fun crashCount(context: Context): Int = prefs(context).getInt(KEY_CRASH_COUNT, 0)

    /** Call each time a crash/ANR dialog's close button is detected. Increments the
     *  counter but does NOT stop capturing — capture only stops via the manual button,
     *  since the target may keep crash-looping repeatedly. */
    fun recordCrashCycle(context: Context) {
        val n = crashCount(context) + 1
        prefs(context).edit().putInt(KEY_CRASH_COUNT, n).apply()
        appendLog(context, "[CRASH-CYCLE] Cycle #$n — user closed the dialog, still capturing (waiting for manual stop)")
    }

    fun stopCapturing(context: Context, reason: String) {
        val total = crashCount(context)
        appendLog(context, "[SYSTEM] Stopped capturing log — $reason (total $total crash(es))")
        prefs(context).edit().putBoolean(KEY_CAPTURING, false).putBoolean(KEY_ARMED, false).apply()
        resetTouchSignals()
    }

    fun cancelArmed(context: Context) {
        prefs(context).edit().putBoolean(KEY_ARMED, false).putBoolean(KEY_CAPTURING, false).apply()
    }

    // Captured lines come straight from logcat/system buffers written by whatever app we're
    // watching — we don't control or understand their content, only filter by package name.
    // If the target app itself logs something sensitive (a bug on its end), we'd otherwise
    // copy it verbatim into a file the user might later hand to a third party. These patterns
    // catch the common shapes of sensitive data without trying to parse semantics: email
    // addresses, Authorization/Bearer header values, JWTs, long opaque tokens (API keys,
    // session ids), and card-number-shaped digit runs.
    private val emailRegex = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val authHeaderRegex = Regex("(?i)(Authorization|Bearer)(\\s*[:=]?\\s*)\\S+")
    private val jwtRegex = Regex("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}")
    private val longTokenRegex = Regex("\\b[A-Za-z0-9+/_-]{32,}={0,2}\\b")
    // A bare 13-digit run is almost always an epoch-millisecond timestamp (extremely common in
    // logs), so this only fires on a clearly card-shaped layout: groups of 4 separated by a
    // space/dash, or an unseparated 15-16 digit run (Amex/Visa-Mastercard length) — deliberately
    // not matching plain 13/14/17-19 digit blobs to avoid redacting timestamps and other
    // ordinary numeric IDs.
    private val cardNumberRegex = Regex("\\b(?:\\d{4}[ -]\\d{4}[ -]\\d{4}[ -]\\d{1,4}|\\d{15,16})\\b")

    /** Redacts the common shapes of sensitive data (see field docs above) before a line ever
     *  touches disk — applied once, here, since every captured line funnels through this one
     *  function regardless of which watcher (logcat, ANR trace, events buffer, etc.) found it. */
    private fun redactSensitive(line: String): String {
        var redacted = line
        redacted = authHeaderRegex.replace(redacted) { m -> "${m.groupValues[1]}${m.groupValues[2]}[REDACTED-AUTH]" }
        redacted = jwtRegex.replace(redacted, "[REDACTED-TOKEN]")
        redacted = emailRegex.replace(redacted, "[REDACTED-EMAIL]")
        redacted = longTokenRegex.replace(redacted, "[REDACTED-TOKEN]")
        redacted = cardNumberRegex.replace(redacted, "[REDACTED-NUMBER]")
        return redacted
    }

    fun appendLog(context: Context, line: String) {
        val path = logPath(context) ?: return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        File(path).appendText("[$ts] ${redactSensitive(line)}\n")
    }

    fun readLog(context: Context): String {
        val path = logPath(context) ?: return ""
        val f = File(path)
        return if (f.exists()) f.readText() else ""
    }

    /** Zips the current capture log .txt (already includes device info in its header) into a
     *  same-named .zip alongside it, so a large session log (can run into the MBs) compresses
     *  down for easier sharing. Overwrites any previous zip for this session on each call. */
    fun zipLogFile(context: Context): File? {
        val path = logPath(context) ?: return null
        val source = File(path)
        if (!source.exists()) return null
        val zipFile = File(source.parentFile, source.nameWithoutExtension + ".zip")
        return try {
            ZipOutputStream(zipFile.outputStream()).use { zos ->
                zos.putNextEntry(ZipEntry(source.name))
                source.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            zipFile
        } catch (e: Exception) {
            null
        }
    }

    /** True only if `adb shell pm grant <pkg> android.permission.READ_LOGS` was run once.
     *  Without this, real logcat capture is impossible — Android blocks it for normal apps. */
    fun hasReadLogsPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_LOGS) ==
                PackageManager.PERMISSION_GRANTED

    /** True only if `adb shell pm grant <pkg> android.permission.DUMP` was run once.
     *  Needed to read another app's official process-exit reason (crash/ANR/low-memory/etc.)
     *  via ActivityManager#getHistoricalProcessExitReasons — optional, capture works without it. */
    fun hasDumpPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.DUMP) ==
                PackageManager.PERMISSION_GRANTED

    /** Optional capture features, toggled from the Capture Options checklist on the target
     *  picker screen and persisted across sessions. All default to enabled. */
    fun isNetworkTimingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OPT_NETWORK_TIMING, true)
    fun setNetworkTimingEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_OPT_NETWORK_TIMING, enabled).apply()

    fun isAnrTraceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OPT_ANR_TRACE, true)
    fun setAnrTraceEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_OPT_ANR_TRACE, enabled).apply()

    fun isEventsBufferEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OPT_EVENTS_BUFFER, true)
    fun setEventsBufferEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_OPT_EVENTS_BUFFER, enabled).apply()

    /** Flags a "silent stall" — app stayed in the foreground with an attached pid but printed
     *  no new log line for a while, e.g. a stuck coroutine that never crashes, never ANRs,
     *  and never logs anything. Found via real device testing (Aurora Store's spinner hang
     *  showed 0% CPU, no logcat output, and no pending network sockets — nothing else here
     *  would have ever flagged it). */
    fun isStallWatchdogEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OPT_STALL_WATCHDOG, true)
    fun setStallWatchdogEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_OPT_STALL_WATCHDOG, enabled).apply()

    /** Flags "touch happened but the app's UI never confirmed a response" — found via a real
     *  repro: Aurora Store handing an install off to the microG Installer, which returns to
     *  Aurora's Downloads page but leaves it unresponsive to scroll/touch for a while. EMUI's
     *  own touch-dispatch instrumentation (HiTouch_PressGestureDetector/HwDragEnhancementImpl)
     *  keeps logging during that freeze, which would otherwise suppress the stall watchdog
     *  above — this is a separate, tighter (3s) check specifically for that gap between "a
     *  touch was dispatched" and "the app actually responded" (scroll/click/window-change).
     *  Best-effort: EMUI-specific log lines aren't guaranteed on every OEM/ROM, and "no
     *  response" is inferred from absence of signals rather than a guaranteed proof of a
     *  frozen main thread. */
    fun isTouchWatchdogEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OPT_TOUCH_WATCHDOG, true)
    fun setTouchWatchdogEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_OPT_TOUCH_WATCHDOG, enabled).apply()
}
