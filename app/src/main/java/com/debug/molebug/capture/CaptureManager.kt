package com.debug.molebug.capture

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
        file.writeText("===== MoleBug Capture Log =====\nTarget package: $targetPackage\nArmed at: $ts\n\n")
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
}
