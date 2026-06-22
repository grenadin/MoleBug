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

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun arm(context: Context, targetPackage: String) {
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
            appendLine("RAM type: ${deviceInfo.ramType}")
            appendLine("[GPU]")
            appendLine("GPU renderer: ${deviceInfo.gpuRenderer}")
            appendLine("GPU frequency: ${deviceInfo.gpuFreqMHz}")
            appendLine("GPU temperature: ${deviceInfo.gpuTempC}")
            appendLine("[Battery]")
            appendLine("Battery percent: ${deviceInfo.batteryPercent}")
            appendLine("Battery health: ${deviceInfo.batteryHealth}")
            appendLine("Battery status: ${deviceInfo.batteryStatus}")
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
    }

    fun cancelArmed(context: Context) {
        prefs(context).edit().putBoolean(KEY_ARMED, false).putBoolean(KEY_CAPTURING, false).apply()
    }

    fun appendLog(context: Context, line: String) {
        val path = logPath(context) ?: return
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        File(path).appendText("[$ts] $line\n")
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
}
