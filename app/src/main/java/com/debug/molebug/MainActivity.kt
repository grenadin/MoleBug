package com.debug.molebug

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import com.debug.molebug.capture.CaptureManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/** -------- Data model -------- */
data class CheckedApp(
    val label: String,
    val pkg: String,
    val installed: Boolean,
    val versionName: String?,
    val versionCode: Long?,
    val installer: String?
)

data class CpuCoreFreq(
    val coreIndex: Int,
    val maxFreqMHz: String,
    val curFreqMHz: String
)

data class DeviceInfo(
    val deviceName: String,
    val model: String,
    val buildNumber: String,
    val softwareVersion: String,
    val emuiVersion: String,
    val cpuAbi: String,
    val cpuCores: Int,
    val cpuMaxFreqMHz: String,
    val gpuRenderer: String,
    val ramTotal: String,
    val ramType: String,
    val ramUsed: String,
    val cpuVendor: String,
    val cpuCurFreqMHz: String,
    val cpuCoreFreqs: List<CpuCoreFreq>,
    val cpuTempC: String,
    val gpuFreqMHz: String,
    val gpuTempC: String,
    val batteryPercent: String,
    val batteryHealth: String,
    val batteryStatus: String
)

/** Live values that change second-to-second — polled on a timer by the UI, separate from the
 *  rest of DeviceInfo which is read once when the screen loads. */
data class LiveDeviceStats(
    val cpuCoreFreqs: List<CpuCoreFreq>,
    val cpuTempC: String,
    val gpuFreqMHz: String,
    val gpuTempC: String,
    val ramUsed: String
)

data class TargetAppInfo(
    val versionName: String?,
    val versionCode: Long?,
    val installer: String,
    val installedAt: String,
    val requestedPermissions: List<Pair<String, Boolean>>, // name -> granted
    val dataUsageSinceInstall: String,
    val notificationStatus: String,
    val apkMd5: String,
    val apkSha1: String,
    val apkSha256: String
)

/** -------- Helpers -------- */
object DeviceInspector {

    fun getDeviceInfo(context: Context): DeviceInfo {
        return DeviceInfo(
            deviceName = "${Build.MANUFACTURER} ${Build.DEVICE}",
            model = Build.MODEL,
            buildNumber = Build.DISPLAY,
            softwareVersion = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            emuiVersion = getProp("ro.build.version.emui").ifEmpty {
                getProp("ro.build.hw_emui_api_level").ifEmpty { context.getString(R.string.emui_not_found) }
            },
            cpuAbi = Build.SUPPORTED_ABIS.joinToString(", "),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuMaxFreqMHz = readCpuMaxFreqMHz(),
            gpuRenderer = readGpuRenderer(),
            ramTotal = readRamTotal(context),
            ramType = readRamType(),
            ramUsed = readRamUsed(context),
            cpuVendor = readCpuVendor(),
            cpuCurFreqMHz = readCpuCurFreqMHz(),
            cpuCoreFreqs = readCpuCoreFreqs(),
            cpuTempC = readCpuTempC(),
            gpuFreqMHz = readGpuFreqMHz(),
            gpuTempC = readGpuTempC(),
            batteryPercent = readBatteryPercent(context),
            batteryHealth = readBatteryHealth(context),
            batteryStatus = readBatteryStatus(context)
        )
    }

    /** Re-reads only the values that change second-to-second, for the UI to poll on a timer
     *  without re-running the rest of getDeviceInfo()'s heavier one-time checks. */
    fun readLiveDeviceStats(context: Context): LiveDeviceStats = LiveDeviceStats(
        cpuCoreFreqs = readCpuCoreFreqs(),
        cpuTempC = readCpuTempC(),
        gpuFreqMHz = readGpuFreqMHz(),
        gpuTempC = readGpuTempC(),
        ramUsed = readRamUsed(context)
    )

    /** Total physical RAM via ActivityManager.MemoryInfo — the only RAM size source that
     *  doesn't need root or parsing /proc/meminfo directly. */
    private fun readRamTotal(context: Context): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            "${info.totalMem / (1024 * 1024)} MB"
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    /** System-wide RAM currently in use (total - available), same ActivityManager.MemoryInfo
     *  source as readRamTotal — this is a live value (changes constantly) unlike the total. */
    private fun readRamUsed(context: Context): String {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            "${(info.totalMem - info.availMem) / (1024 * 1024)} MB"
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    /** RAM chip type (e.g. LPDDR4X) isn't exposed by any public Android API; only some vendors
     *  leak it through a boot system property. Best-effort across the property names seen on
     *  real devices, otherwise honestly report it as unavailable. */
    private fun readRamType(): String {
        val candidates = listOf(
            "ro.boot.ddr_type", "ro.boot.hardware.ddr", "ro.boot.ddr_vendor",
            "ro.boot.ram_type", "ro.product.ram_type"
        )
        for (key in candidates) {
            val v = getProp(key)
            if (v.isNotEmpty()) return v
        }
        return "Unknown (not exposed by Android API on this device)"
    }

    /** CPU manufacturer. Build.SOC_MANUFACTURER (API 31+) is the official field; older devices
     *  fall back to the "Hardware"/"vendor_id" line in /proc/cpuinfo, then Build.HARDWARE. */
    private fun readCpuVendor(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MANUFACTURER
            if (soc.isNotEmpty() && soc != Build.UNKNOWN) return soc
        }
        return try {
            val line = File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Hardware") || it.startsWith("vendor_id") }
            line?.substringAfter(":")?.trim()?.ifEmpty { null } ?: Build.HARDWARE
        } catch (e: Exception) {
            Build.HARDWARE
        }
    }

    /** Current (not max) clock speed, read live from sysfs — same permission-free path as
     *  cpuinfo_max_freq, just the "scaling_cur_freq" sibling file. */
    private fun readCpuCurFreqMHz(): String {
        return try {
            val freqsKHz = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { core ->
                File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
                    .takeIf { it.exists() }
                    ?.readText()?.trim()?.toLongOrNull()
            }
            if (freqsKHz.isEmpty()) return "unavailable (not readable on this device)"
            freqsKHz.joinToString(", ") { "${it / 1000} MHz" }
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    /** Scans every thermal zone under sysfs for one whose "type" label mentions any of the
     *  given keywords — there's no fixed zone index or naming scheme across vendors (Kirin/
     *  HiSilicon SoCs label CPU zones "soc_thermal"/"cluster0"/"tsens_tz_sensor0" instead of
     *  anything containing "cpu"), so this is the only portable, non-root way to find the
     *  right sensor. Most zones report in millidegrees C; a handful of ROMs report whole
     *  degrees, so values implausibly high (>200) are treated as millidegrees and divided
     *  down. On a total miss, the fallback message lists every zone type actually found on
     *  the device, so a future keyword can be added precisely instead of guessing blind. */
    private fun readThermalZoneTempC(typeKeywords: List<String>): String {
        val zonesDir = File("/sys/class/thermal")
        val zones = try {
            zonesDir.listFiles { f -> f.name.startsWith("thermal_zone") } ?: return "unavailable"
        } catch (e: Exception) {
            return "unavailable: ${e.message}"
        }
        val seenTypes = mutableListOf<String>()
        // Some ROMs grant read access to most thermal zones but SELinux-deny a handful of
        // individual ones (varies by zone, not a blanket policy) — a single EACCES must not
        // abort the whole scan, so each zone is read in its own try/catch and simply skipped
        // on failure instead of failing the entire lookup.
        for (zone in zones) {
            try {
                val type = File(zone, "type").takeIf { it.exists() }?.readText()?.trim()?.lowercase()
                    ?: continue
                seenTypes += type
                if (typeKeywords.none { type.contains(it) }) continue
                val raw = File(zone, "temp").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
                    ?: continue
                val celsius = if (raw > 200) raw / 1000.0 else raw.toDouble()
                return "%.1f°C".format(celsius)
            } catch (e: Exception) {
                continue
            }
        }
        return if (seenTypes.isEmpty()) {
            "unavailable (no thermal zone readable on this device)"
        } else {
            "unavailable (zones on this device: ${seenTypes.distinct().joinToString(", ")})"
        }
    }

    private fun readCpuTempC(): String =
        readThermalZoneTempC(listOf("cpu", "soc", "cluster", "tsens", "apss", "core"))
    private fun readGpuTempC(): String =
        readThermalZoneTempC(listOf("gpu", "kgsl", "mali", "adreno", "gpuss"))

    /** GPU current clock. Tries known vendor-specific paths first, then falls back to scanning
     *  every generic devfreq node under /sys/class/devfreq (present on most modern SoCs) for
     *  one whose device-tree-derived name mentions the GPU — broader and more portable than
     *  hardcoding every vendor's exact path, since devfreq node names vary by SoC but
     *  consistently contain the GPU driver name somewhere. */
    private fun readGpuFreqMHz(): String {
        val candidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", // Adreno (Qualcomm)
            "/sys/class/kgsl/kgsl-3d0/gpuclk",
            "/sys/kernel/gpu/gpu_clock", // some Mali ROMs
            "/sys/kernel/ged/hal/current_freqency" // MediaTek (typo is intentional upstream)
        )
        for (path in candidates) {
            try {
                val hz = File(path).takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: continue
                // kgsl reports Hz, not KHz like CPU cpufreq — normalize down to MHz either way.
                return "${hz / 1_000_000} MHz"
            } catch (e: Exception) {
                continue
            }
        }
        val gpuKeywords = listOf("gpu", "kgsl", "mali", "adreno")
        try {
            val devfreqDir = File("/sys/class/devfreq")
            val nodes = devfreqDir.listFiles() ?: emptyArray()
            for (node in nodes) {
                try {
                    val name = (File(node, "name").takeIf { it.exists() }?.readText()?.trim() ?: node.name)
                        .lowercase()
                    if (gpuKeywords.none { name.contains(it) }) continue
                    val hz = File(node, "cur_freq").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
                        ?: continue
                    return "${hz / 1_000_000} MHz"
                } catch (e: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            // /sys/class/devfreq itself unreadable — fall through to the generic message below
        }
        return "unavailable (not exposed on this device)"
    }

    /** Per-core max/current frequency, one entry per CPU core index, so the UI can show each
     *  core in its own mini card instead of one flattened "core0, core1, ..." string. Public
     *  so the UI can re-poll just this (cheap sysfs reads) on a timer for a live "Now" value,
     *  without re-running the rest of getDeviceInfo()'s more expensive checks every tick. */
    fun readCpuCoreFreqs(): List<CpuCoreFreq> {
        return (0 until Runtime.getRuntime().availableProcessors()).map { core ->
            val maxKHz = File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                .takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            val curKHz = File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq")
                .takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
            CpuCoreFreq(
                coreIndex = core,
                maxFreqMHz = if (maxKHz != null) "${maxKHz / 1000} MHz" else "unavailable",
                curFreqMHz = if (curKHz != null) "${curKHz / 1000} MHz" else "unavailable"
            )
        }
    }

    private fun batteryIntent(context: Context) =
        context.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun readBatteryPercent(context: Context): String {
        return try {
            val intent = batteryIntent(context) ?: return "unavailable"
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) "unavailable" else "${(level * 100 / scale)}%"
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    private fun readBatteryHealth(context: Context): String {
        return try {
            val intent = batteryIntent(context) ?: return "unavailable"
            when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified failure"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    private fun readBatteryStatus(context: Context): String {
        return try {
            val intent = batteryIntent(context) ?: return "unavailable"
            when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_FULL -> "Charging (full)"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Not charging (discharging)"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    /** Highest of each core's cpuinfo_max_freq under sysfs — plain file reads, no special
     *  permission needed, readable on stock ROMs without root. */
    private fun readCpuMaxFreqMHz(): String {
        return try {
            val freqsKHz = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { core ->
                File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
                    .takeIf { it.exists() }
                    ?.readText()?.trim()?.toLongOrNull()
            }
            val maxKHz = freqsKHz.maxOrNull()
            if (maxKHz != null) "${maxKHz / 1000} MHz" else "unavailable (not readable on this device)"
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    /** Queries GL_RENDERER from a throwaway off-screen EGL context — the only way to get the
     *  actual GPU model string (e.g. "Adreno (TM) 619") on Android; there's no Build.* field
     *  for it. Context is created and torn down immediately, never touches the UI. */
    private fun readGpuRenderer(): String {
        return try {
            val egl = javax.microedition.khronos.egl.EGLContext.getEGL() as javax.microedition.khronos.egl.EGL10
            val display = egl.eglGetDisplay(javax.microedition.khronos.egl.EGL10.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            egl.eglInitialize(display, version)

            val configAttribs = intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                javax.microedition.khronos.egl.EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            egl.eglChooseConfig(display, configAttribs, configs, 1, numConfigs)
            val config = configs[0] ?: return "unavailable (no matching EGL config)"

            val contextAttribs = intArrayOf(0x3098, 2, javax.microedition.khronos.egl.EGL10.EGL_NONE) // EGL_CONTEXT_CLIENT_VERSION=2
            val eglContext = egl.eglCreateContext(
                display, config, javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT, contextAttribs
            )
            val surfaceAttribs = intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_WIDTH, 1,
                javax.microedition.khronos.egl.EGL10.EGL_HEIGHT, 1,
                javax.microedition.khronos.egl.EGL10.EGL_NONE
            )
            val surface = egl.eglCreatePbufferSurface(display, config, surfaceAttribs)
            egl.eglMakeCurrent(display, surface, surface, eglContext)

            val renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER) ?: "unknown"

            egl.eglMakeCurrent(
                display,
                javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE,
                javax.microedition.khronos.egl.EGL10.EGL_NO_SURFACE,
                javax.microedition.khronos.egl.EGL10.EGL_NO_CONTEXT
            )
            egl.eglDestroySurface(display, surface)
            egl.eglDestroyContext(display, eglContext)
            egl.eglTerminate(display)

            renderer
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    /** Reads a system property via reflection on android.os.SystemProperties (no root needed for readable props) */
    private fun getProp(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            (method.invoke(null, key) as? String).orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    fun checkApp(context: Context, label: String, pkg: String): CheckedApp {
        val pm = context.packageManager
        return try {
            val info: PackageInfo = pm.getPackageInfo(pkg, 0)
            val installer = getInstallerName(context, pm, pkg)
            CheckedApp(
                label = label,
                pkg = pkg,
                installed = true,
                versionName = info.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    info.longVersionCode else info.versionCode.toLong(),
                installer = installer
            )
        } catch (e: PackageManager.NameNotFoundException) {
            CheckedApp(label, pkg, installed = false, versionName = null, versionCode = null, installer = null)
        }
    }

    private fun getInstallerName(context: Context, pm: PackageManager, pkg: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val src = pm.getInstallSourceInfo(pkg)
                src.installingPackageName ?: src.initiatingPackageName
                    ?: context.getString(R.string.installer_unknown)
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg) ?: context.getString(R.string.installer_unknown)
            }
        } catch (e: Exception) {
            context.getString(R.string.installer_read_failed, e.message)
        }
    }

    /** Gathers everything we can learn about a target app non-root: install source, version,
     *  declared permissions (with grant state), how much network data it has used since it
     *  was installed, and whether the user has notifications enabled for it. */
    fun getTargetAppInfo(context: Context, pkg: String): TargetAppInfo {
        val pm = context.packageManager
        val info: PackageInfo = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
        val apkChecksums = readApkChecksums(info)
        return TargetAppInfo(
            versionName = info.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                info.longVersionCode else info.versionCode.toLong(),
            installer = getInstallerName(context, pm, pkg),
            installedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(info.firstInstallTime)),
            requestedPermissions = readRequestedPermissions(info),
            dataUsageSinceInstall = readDataUsageSinceInstall(context, info),
            notificationStatus = readNotificationStatus(context, info),
            apkMd5 = apkChecksums[0],
            apkSha1 = apkChecksums[1],
            apkSha256 = apkChecksums[2]
        )
    }

    /** MD5/SHA-1/SHA-256 of the target app's installed base APK file — lets a capture session
     *  be tied to the exact binary that was running, e.g. to confirm two devices are on
     *  byte-identical builds. Reads the APK off disk (world-readable by design on Android,
     *  same way `pm` itself accesses it), no root needed. */
    private fun readApkChecksums(info: PackageInfo): List<String> {
        val sourceDir = info.applicationInfo?.sourceDir
            ?: return listOf("unavailable", "unavailable", "unavailable")
        return try {
            val md5 = java.security.MessageDigest.getInstance("MD5")
            val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            val sha256 = java.security.MessageDigest.getInstance("SHA-256")
            File(sourceDir).inputStream().use { stream ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    md5.update(buffer, 0, read)
                    sha1.update(buffer, 0, read)
                    sha256.update(buffer, 0, read)
                }
            }
            listOf(md5, sha1, sha256).map { digest ->
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            val msg = "unavailable: ${e.message}"
            listOf(msg, msg, msg)
        }
    }

    private fun readRequestedPermissions(info: PackageInfo): List<Pair<String, Boolean>> {
        val names = info.requestedPermissions ?: return emptyList()
        val flags = info.requestedPermissionsFlags
        return names.mapIndexed { i, name ->
            val granted = flags != null && i < flags.size &&
                (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            name to granted
        }
    }

    /** Total mobile + Wi-Fi bytes (rx+tx) attributed to the target app's uid since it was
     *  first installed, via NetworkStatsManager — the same usage-access grant the app already
     *  requires for Tier 1 foreground/background timing also unlocks this query, no extra
     *  permission needed. */
    private fun readDataUsageSinceInstall(context: Context, info: PackageInfo): String {
        return try {
            val nsm = context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
                ?: return "unavailable"
            val uid = info.applicationInfo?.uid ?: return "unavailable"
            val start = info.firstInstallTime
            val end = System.currentTimeMillis()
            var totalBytes = 0L
            for (networkType in listOf(ConnectivityManager.TYPE_MOBILE, ConnectivityManager.TYPE_WIFI)) {
                try {
                    val bucket = android.app.usage.NetworkStats.Bucket()
                    val subscriberId: String? = null
                    val stats = nsm.queryDetailsForUid(networkType, subscriberId, start, end, uid)
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(bucket)
                        totalBytes += bucket.rxBytes + bucket.txBytes
                    }
                    stats.close()
                } catch (_: Exception) {
                    // Network type unsupported/unreadable on this device — skip, keep summing the rest.
                }
            }
            "${totalBytes / (1024 * 1024)} MB (since install)"
        } catch (e: Exception) {
            "unavailable: ${e.message}"
        }
    }

    /** Whether the user has notifications enabled for the target app. There is no public,
     *  documented API for querying another app's notification state, so this falls back to
     *  the same INotificationManager binder call the Settings app itself uses — best-effort,
     *  reports "Unknown" rather than guessing if the ROM blocks it. */
    private fun readNotificationStatus(context: Context, info: PackageInfo): String {
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val getService = nm.javaClass.getMethod("getService")
            val service = getService.invoke(nm)
            val uid = info.applicationInfo?.uid ?: -1
            val method = service.javaClass.getMethod(
                "areNotificationsEnabledForPackage", String::class.java, Int::class.javaPrimitiveType
            )
            val enabled = method.invoke(service, info.packageName, uid) as Boolean
            if (enabled) "Enabled" else "Disabled"
        } catch (e: Exception) {
            "Unknown (unsupported on this device/ROM)"
        }
    }

    fun listInstalledApps(context: Context): List<CheckedApp> {
        val pm = context.packageManager
        val apps = pm.getInstalledPackages(0)
        return apps.map {
            CheckedApp(
                label = it.applicationInfo?.loadLabel(pm)?.toString() ?: it.packageName,
                pkg = it.packageName,
                installed = true,
                versionName = it.versionName,
                versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    it.longVersionCode else it.versionCode.toLong(),
                installer = getInstallerName(context, pm, it.packageName)
            )
        }.sortedBy { it.pkg }
    }

    fun exportLog(context: Context, deviceInfo: DeviceInfo, checks: List<CheckedApp>, allApps: List<CheckedApp>): File {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(dir, "mole_log_$ts.txt")

        val sb = StringBuilder()
        sb.appendLine("===== MoleBug Debug Log =====")
        sb.appendLine("Timestamp: $ts")
        sb.appendLine()
        sb.appendLine("---- Device Info ----")
        sb.appendLine("[Device]")
        sb.appendLine("Device name: ${deviceInfo.deviceName}")
        sb.appendLine("Model: ${deviceInfo.model}")
        sb.appendLine("Build number: ${deviceInfo.buildNumber}")
        sb.appendLine("Software version: ${deviceInfo.softwareVersion}")
        sb.appendLine("EMUI version: ${deviceInfo.emuiVersion}")
        sb.appendLine("[CPU]")
        sb.appendLine("CPU ABI: ${deviceInfo.cpuAbi}")
        sb.appendLine("CPU cores: ${deviceInfo.cpuCores}")
        sb.appendLine("CPU vendor: ${deviceInfo.cpuVendor}")
        sb.appendLine("CPU max frequency: ${deviceInfo.cpuMaxFreqMHz}")
        sb.appendLine("CPU realtime frequency: ${deviceInfo.cpuCurFreqMHz}")
        sb.appendLine("CPU temperature: ${deviceInfo.cpuTempC}")
        deviceInfo.cpuCoreFreqs.forEach { core ->
            sb.appendLine("  Core ${core.coreIndex}: max ${core.maxFreqMHz}, now ${core.curFreqMHz}")
        }
        sb.appendLine("[RAM]")
        sb.appendLine("RAM total: ${deviceInfo.ramTotal}")
        sb.appendLine("RAM used: ${deviceInfo.ramUsed}")
        sb.appendLine("RAM type: ${deviceInfo.ramType}")
        sb.appendLine("[GPU]")
        sb.appendLine("GPU renderer: ${deviceInfo.gpuRenderer}")
        sb.appendLine("GPU frequency: ${deviceInfo.gpuFreqMHz}")
        sb.appendLine("GPU temperature: ${deviceInfo.gpuTempC}")
        sb.appendLine("[Battery]")
        sb.appendLine("Battery percent: ${deviceInfo.batteryPercent}")
        sb.appendLine("Battery health: ${deviceInfo.batteryHealth}")
        sb.appendLine("Battery status: ${deviceInfo.batteryStatus}")
        sb.appendLine()
        sb.appendLine("---- Required Components ----")
        checks.forEach {
            sb.appendLine("[${if (it.installed) "OK" else "MISSING"}] ${it.label} (${it.pkg})")
            if (it.installed) {
                sb.appendLine("    versionName: ${it.versionName}")
                sb.appendLine("    versionCode: ${it.versionCode}")
                sb.appendLine("    installer:   ${it.installer}")
            }
        }
        sb.appendLine()
        sb.appendLine("---- All Installed Packages (${allApps.size}) ----")
        allApps.forEach {
            sb.appendLine("${it.pkg} | ${it.versionName} | code=${it.versionCode} | installer=${it.installer}")
        }

        file.writeText(sb.toString())
        return file
    }
}

/** -------- Components to check -------- */
val REQUIRED_COMPONENTS = listOf(
    Triple("microG Services", "com.google.android.gms", "เวอร์ชันของ microG Services"),
    Triple("microG Services Framework Proxy", "com.google.android.gsf", "เวอร์ชันของ Framework Proxy"),
    Triple("microG Companion", "com.android.vending", "เวอร์ชันของ microG companion"),
    Triple("Aurora Store", "com.aurora.store", "เวอร์ชันของ Aurora")
)

/** ReVanced/Vanced's own bundled GmsCore implementations declare the same package name and
 *  signature space microG occupies, so having one of these installed alongside microG is a
 *  real package conflict (sign-in loops, silent GMS failures) — flagged red. The Manager apps
 *  themselves (the patching tool, not a GmsCore build) don't collide with microG's package at
 *  all — they're flagged yellow purely as an FYI that ReVanced patching is in play, not a
 *  warning that anything is actually broken. */
val CONFLICTING_GMS_PACKAGES = listOf(
    Triple("Vanced microG", "com.mgoogle.android.gms", true),
    Triple("Vanced Manager", "com.vanced.manager", false),
    Triple("ReVanced microG / GmsCore", "app.revanced.android.gms", true),
    Triple("ReVanced Manager", "app.revanced.manager.flutter", false)
)

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_SHOW_LOG_VIEWER = "com.debug.molebug.ACTION_SHOW_LOG_VIEWER"
    }

    private var screenState: MutableState<Screen>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state = remember { mutableStateOf(screenForIntent(intent)) }
            screenState = state
            MoleBugRoot(state)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenState?.value = screenForIntent(intent)
    }

    private fun screenForIntent(intent: Intent?): Screen =
        if (intent?.action == ACTION_SHOW_LOG_VIEWER) Screen.LOG_VIEWER else Screen.HOME
}

@Composable
fun MoleBugApp(onOpenCapture: () -> Unit = {}, onOpenLogViewer: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceInfo = remember { DeviceInspector.getDeviceInfo(context) }
    // CPU/GPU frequency+temp and RAM used are live values, not one-time snapshots — re-read
    // them on a timer while the Device Info card is open so they track the device in real
    // time instead of freezing at whatever they were when the screen first loaded.
    var liveStats by remember {
        mutableStateOf(
            LiveDeviceStats(
                cpuCoreFreqs = deviceInfo.cpuCoreFreqs,
                cpuTempC = deviceInfo.cpuTempC,
                gpuFreqMHz = deviceInfo.gpuFreqMHz,
                gpuTempC = deviceInfo.gpuTempC,
                ramUsed = deviceInfo.ramUsed
            )
        )
    }
    val checks = remember {
        REQUIRED_COMPONENTS.map { (label, pkg, _) -> DeviceInspector.checkApp(context, label, pkg) }
    }
    val allInstalled = checks.all { it.installed }
    val conflictingGmsApps = remember {
        CONFLICTING_GMS_PACKAGES
            .map { (label, pkg, isRealConflict) -> DeviceInspector.checkApp(context, label, pkg) to isRealConflict }
            .filter { (app, _) -> app.installed }
    }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var exportedPath by remember { mutableStateOf<String?>(null) }
    var exportedContent by remember { mutableStateOf<String?>(null) }

    // The Device Info card auto-collapses to just its title once the page is scrolled past
    // the card's own height, then expands again on scroll-back-to-top — same pattern as the
    // Target Picker's Permissions/Capture Options sections, so the card doesn't eat the whole
    // screen while the user is trying to get to Check Basic Apps / Export below it. Gating on
    // the card's measured height (rather than any scroll > 0) lets the user scroll through the
    // card's own content — e.g. down to GPU/Battery below the CPU core cards — without it
    // collapsing out from under them mid-scroll.
    var deviceInfoExpanded by remember { mutableStateOf(false) }
    // Each category inside the card (Device/CPU/RAM/GPU/Battery) collapses independently,
    // since showing all five at once was taking up a lot of the card's space.
    var deviceSubExpanded by remember { mutableStateOf(true) }
    var cpuSubExpanded by remember { mutableStateOf(true) }
    var ramSubExpanded by remember { mutableStateOf(true) }
    var gpuSubExpanded by remember { mutableStateOf(true) }
    var batterySubExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(deviceInfoExpanded) {
        while (deviceInfoExpanded) {
            liveStats = DeviceInspector.readLiveDeviceStats(context)
            kotlinx.coroutines.delay(1000)
        }
    }
    var deviceCardHeightPx by remember { mutableStateOf(0) }
    val pageScrollState = rememberScrollState()
    var hasScrolledAway by remember { mutableStateOf(false) }
    LaunchedEffect(pageScrollState) {
        snapshotFlow { pageScrollState.value }.collect { value ->
            if (deviceInfoExpanded && value > deviceCardHeightPx) {
                hasScrolledAway = true
                deviceInfoExpanded = false
            } else if (!deviceInfoExpanded && hasScrolledAway && value == 0) {
                deviceInfoExpanded = true
                hasScrolledAway = false
            }
        }
    }

    // Permissions live on the Home screen now, as a modal-style card pinned to the bottom of
    // the screen instead of taking up space inline in the Target Picker — that screen's job is
    // picking a target app, not granting permissions. While the three core (Tier 1) perms
    // aren't all granted, the card is forced open since capture can't work at all without
    // them; once granted it auto-collapses into a small pastel pill the user can still tap to
    // come back and grant the optional Tier 2 (READ_LOGS/DUMP) perms later.
    var overlayOk by remember { mutableStateOf(hasOverlayPermission(context)) }
    var usageOk by remember { mutableStateOf(hasUsageAccess(context)) }
    var a11yOk by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var batteryOptOk by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var readLogsOk by remember { mutableStateOf(CaptureManager.hasReadLogsPermission(context)) }
    var dumpOk by remember { mutableStateOf(CaptureManager.hasDumpPermission(context)) }
    val tier1Complete = overlayOk && usageOk && a11yOk
    // Not forced open — the user can close it any time, even with zero permissions granted.
    // It only auto-collapses to the pill once Tier 1 is actually reached (a courtesy, not a
    // requirement), and the only place that re-summons it is the "Go to Target App Log
    // Capture" button, which can't be used at all until Tier 1 is granted.
    var permissionsExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(tier1Complete) {
        if (tier1Complete) permissionsExpanded = false
    }

    // The permissions widget pops in over the screen a few seconds after launch, instead of
    // being there on the very first frame — so the user sees the normal Device Info / Basic
    // Apps content first, then the modal animates in on top of it.
    var permissionsWidgetVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2000)
        permissionsWidgetVisible = true
        permissionsExpanded = !tier1Complete
    }

    // Once every permission (Tier 2 included) is granted and the widget is collapsed, there's
    // nothing left the user needs to act on — so it stops floating over the content and just
    // becomes a normal item in the scrolling page instead, freeing up the pinned-bottom space
    // entirely. It only goes back to floating if re-expanded (tap to review) or if a Tier 2
    // perm gets revoked later.
    val isFullyGranted = tier1Complete && readLogsOk && dumpOk
    val permissionsFloating = permissionsExpanded || !isFullyGranted

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                overlayOk = hasOverlayPermission(context)
                usageOk = hasUsageAccess(context)
                a11yOk = isAccessibilityServiceEnabled(context)
                batteryOptOk = isIgnoringBatteryOptimizations(context)
                readLogsOk = CaptureManager.hasReadLogsPermission(context)
                dumpOk = CaptureManager.hasDumpPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(pageScrollState)
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.app_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Image(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.mole_badge),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))

                GlassyCard(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { deviceCardHeightPx = it.size.height }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { deviceInfoExpanded = !deviceInfoExpanded }
                        ) {
                            Text(
                                stringResource(R.string.section_device_info),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (deviceInfoExpanded) "▾" else "▸",
                                fontSize = 28.sp
                            )
                        }

                        if (deviceInfoExpanded) {
                            CollapsibleSubsectionLabel(
                                stringResource(R.string.subsection_device),
                                expanded = deviceSubExpanded,
                                onToggle = { deviceSubExpanded = !deviceSubExpanded }
                            )
                            if (deviceSubExpanded) {
                                InfoRow(stringResource(R.string.info_device_name), deviceInfo.deviceName)
                                InfoRow(stringResource(R.string.info_model), deviceInfo.model)
                                InfoRow(stringResource(R.string.info_build_number), deviceInfo.buildNumber)
                                InfoRow(stringResource(R.string.info_software_version), deviceInfo.softwareVersion)
                                InfoRow(stringResource(R.string.info_emui_version), deviceInfo.emuiVersion)
                            }

                            Divider(modifier = Modifier.padding(vertical = 6.dp))
                            CollapsibleSubsectionLabel(
                                stringResource(R.string.subsection_cpu),
                                expanded = cpuSubExpanded,
                                onToggle = { cpuSubExpanded = !cpuSubExpanded }
                            )
                            if (cpuSubExpanded) {
                                InfoRow(stringResource(R.string.info_cpu_abi), deviceInfo.cpuAbi)
                                InfoRow(stringResource(R.string.info_cpu_cores), deviceInfo.cpuCores.toString())
                                InfoRow(stringResource(R.string.info_cpu_vendor), deviceInfo.cpuVendor)
                                InfoRow(stringResource(R.string.info_cpu_temp), liveStats.cpuTempC)
                                Spacer(Modifier.height(4.dp))
                                liveStats.cpuCoreFreqs.chunked(2).forEach { rowCores ->
                                    Row(modifier = Modifier.fillMaxWidth()) {
                                        rowCores.forEach { core ->
                                            CpuCoreCard(core, modifier = Modifier.weight(1f).padding(2.dp))
                                        }
                                        if (rowCores.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }

                            Divider(modifier = Modifier.padding(vertical = 6.dp))
                            CollapsibleSubsectionLabel(
                                stringResource(R.string.subsection_ram),
                                expanded = ramSubExpanded,
                                onToggle = { ramSubExpanded = !ramSubExpanded }
                            )
                            if (ramSubExpanded) {
                                InfoRow(stringResource(R.string.info_ram_total), deviceInfo.ramTotal)
                                InfoRow(stringResource(R.string.info_ram_used), liveStats.ramUsed)
                                InfoRow(stringResource(R.string.info_ram_type), deviceInfo.ramType)
                            }

                            Divider(modifier = Modifier.padding(vertical = 6.dp))
                            CollapsibleSubsectionLabel(
                                stringResource(R.string.subsection_gpu),
                                expanded = gpuSubExpanded,
                                onToggle = { gpuSubExpanded = !gpuSubExpanded }
                            )
                            if (gpuSubExpanded) {
                                InfoRow(stringResource(R.string.info_gpu_renderer), deviceInfo.gpuRenderer)
                                InfoRow(stringResource(R.string.info_gpu_freq), liveStats.gpuFreqMHz)
                                InfoRow(stringResource(R.string.info_gpu_temp), liveStats.gpuTempC)
                            }

                            Divider(modifier = Modifier.padding(vertical = 6.dp))
                            CollapsibleSubsectionLabel(
                                stringResource(R.string.subsection_battery),
                                expanded = batterySubExpanded,
                                onToggle = { batterySubExpanded = !batterySubExpanded }
                            )
                            if (batterySubExpanded) {
                                InfoRow(stringResource(R.string.info_battery_percent), deviceInfo.batteryPercent)
                                InfoRow(stringResource(R.string.info_battery_health), deviceInfo.batteryHealth)
                                InfoRow(stringResource(R.string.info_battery_status), deviceInfo.batteryStatus)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionTitle(stringResource(R.string.section_check_components))
                checks.forEach { c ->
                    ComponentRow(c)
                }

                conflictingGmsApps.forEach { (app, isRealConflict) ->
                    Spacer(Modifier.height(8.dp))
                    GlassyCard(
                        color = if (isRealConflict) Color(0xFFE53935) else Color(0xFFFFF3B0), // red vs pastel yellow
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                if (isRealConflict) R.string.gms_conflict_warning else R.string.gms_manager_notice,
                                app.label
                            ),
                            color = Color.Black,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (allInstalled) {
                    OutlinedButton(onClick = {
                        val allApps = DeviceInspector.listInstalledApps(context)
                        val file = DeviceInspector.exportLog(context, deviceInfo, checks, allApps)
                        exportedPath = file.absolutePath
                        exportedContent = file.readText()
                        statusMsg = context.getString(R.string.status_log_saved, file.name)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.button_export_log))
                    }
                } else {
                    Text(
                        stringResource(R.string.status_missing_components),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                statusMsg?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                exportedPath?.let {
                    Text(
                        stringResource(R.string.status_file_path, it),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                exportedContent?.let { content ->
                    var exportContentExpanded by remember { mutableStateOf(true) }
                    var exportSearchQuery by remember { mutableStateOf("") }
                    var exportMatchPointer by remember { mutableStateOf(0) }
                    val exportLines = remember(content) { content.lines() }
                    val exportListState = rememberLazyListState()
                    val exportCoroutineScope = rememberCoroutineScope()
                    val exportMatchIndices = remember(exportLines, exportSearchQuery) {
                        if (exportSearchQuery.isBlank()) emptyList()
                        else exportLines.indices.filter { exportLines[it].contains(exportSearchQuery, ignoreCase = true) }
                    }
                    fun jumpToExportMatch(pointer: Int) {
                        if (exportMatchIndices.isEmpty()) return
                        val clamped = ((pointer % exportMatchIndices.size) + exportMatchIndices.size) % exportMatchIndices.size
                        exportMatchPointer = clamped
                        exportCoroutineScope.launch { exportListState.scrollToItem(exportMatchIndices[clamped]) }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { exportContentExpanded = !exportContentExpanded }
                    ) {
                        Text(
                            stringResource(R.string.exported_log_title),
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            if (exportContentExpanded) "▾" else "▸",
                            fontSize = 28.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }

                    if (exportContentExpanded) {
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = exportSearchQuery,
                            onValueChange = {
                                exportSearchQuery = it
                                exportMatchPointer = 0
                                jumpToExportMatch(0)
                            },
                            label = { Text(stringResource(R.string.log_search_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (exportSearchQuery.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (exportMatchIndices.isEmpty()) stringResource(R.string.log_search_no_matches)
                                    else stringResource(R.string.log_search_match_count, exportMatchPointer + 1, exportMatchIndices.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { jumpToExportMatch(exportMatchPointer - 1) }, enabled = exportMatchIndices.isNotEmpty()) {
                                    Text(stringResource(R.string.log_search_previous))
                                }
                                TextButton(onClick = { jumpToExportMatch(exportMatchPointer + 1) }, enabled = exportMatchIndices.isNotEmpty()) {
                                    Text(stringResource(R.string.log_search_next))
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Card(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                LazyColumn(state = exportListState, modifier = Modifier.fillMaxSize().padding(12.dp)) {
                                    itemsIndexed(exportLines) { index, line ->
                                        val isCurrentMatch = exportMatchIndices.isNotEmpty() && exportMatchIndices[exportMatchPointer] == index
                                        val isOtherMatch = !isCurrentMatch && exportSearchQuery.isNotBlank() &&
                                                line.contains(exportSearchQuery, ignoreCase = true)
                                        Text(
                                            line,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isCurrentMatch) MaterialTheme.colorScheme.onPrimaryContainer else Color.Unspecified,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    when {
                                                        isCurrentMatch -> MaterialTheme.colorScheme.primaryContainer
                                                        isOtherMatch -> MaterialTheme.colorScheme.secondaryContainer
                                                        else -> Color.Transparent
                                                    }
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button3D(
                        onClick = {
                            val path = exportedPath ?: return@Button3D
                            val file = File(path)
                            if (!file.exists()) return@Button3D
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                // "text/plain" here was getting misread by some share targets
                                // (confirmed via logcat: Messenger's ShareIntentHandler calls
                                // finish() on itself immediately, with no UI, the instant it
                                // sees text/plain + EXTRA_STREAM together — it reads that mime
                                // as "this is text for the compose box", not "this is a file",
                                // and bails when a stream shows up instead). "*/*" reads
                                // unambiguously as "generic file attachment" everywhere,
                                // matching the capture-log Share button (application/zip) that
                                // never had this problem.
                                type = "*/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                // Pre-fills a prompt alongside the file for share targets that
                                // read EXTRA_TEXT even with a stream attached (most chat-style
                                // AI apps do) — not guaranteed for every app, but costs nothing
                                // for the ones that ignore it.
                                putExtra(Intent.EXTRA_TEXT, context.getString(R.string.ai_prompt_export_log))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser_title)))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.button_share_file)) }
                }

                Spacer(Modifier.height(24.dp))
                Divider()
                Spacer(Modifier.height(16.dp))
                SectionTitle(stringResource(R.string.section_capture_other_app))
                Text(
                    stringResource(R.string.capture_intro),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Button3D(
                    onClick = {
                        if (tier1Complete) {
                            onOpenCapture()
                        } else {
                            // Tier 1 not granted yet — re-summon the permissions card instead
                            // of letting the user into the Target Picker, where capture
                            // couldn't work anyway.
                            permissionsWidgetVisible = true
                            permissionsExpanded = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.button_go_capture))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenLogViewer, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.button_view_log))
                }
                if (permissionsFloating) {
                    // Bottom padding so the permissions card/pill (pinned via the outer Box)
                    // never sits on top of the last bit of scrollable content.
                    Spacer(Modifier.height(96.dp))
                } else {
                    // Fully granted and collapsed — the pill now flows inline with the page
                    // instead of floating, so it scrolls away with everything else.
                    Spacer(Modifier.height(16.dp))
                    PermissionsPill(
                        isTier2 = true,
                        onClick = { permissionsExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            val density = androidx.compose.ui.platform.LocalDensity.current
            // The pill/card's own height is only ~50-300dp, so sliding by just `fullHeight`
            // barely travels at all and the spring's overshoot ends up looking like an
            // in-place pulse rather than real upward motion. Sliding by a fixed, generous
            // pixel distance (well taller than any phone screen) guarantees it travels up
            // from below the visible screen every time, regardless of the content's size.
            val slideDistancePx = with(density) { 1000.dp.roundToPx() }
            androidx.compose.animation.AnimatedVisibility(
                visible = permissionsWidgetVisible && permissionsFloating,
                // Slides up from below the screen edge along the Y axis — low stiffness +
                // high bounciness makes the spring overshoot past its resting position and
                // bounce back down a few times before settling, like a jelly popping up from
                // the bottom.
                enter = androidx.compose.animation.slideInVertically(
                    initialOffsetY = { slideDistancePx },
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioHighBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessVeryLow
                    )
                ) + androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(150)
                ),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                PermissionsWidget(
                    expanded = permissionsExpanded,
                    onToggle = { permissionsExpanded = !permissionsExpanded },
                    overlayOk = overlayOk,
                    usageOk = usageOk,
                    a11yOk = a11yOk,
                    batteryOptOk = batteryOptOk,
                    readLogsOk = readLogsOk,
                    dumpOk = dumpOk,
                    onRecheck = {
                        overlayOk = hasOverlayPermission(context)
                        usageOk = hasUsageAccess(context)
                        a11yOk = isAccessibilityServiceEnabled(context)
                        batteryOptOk = isIgnoringBatteryOptimizations(context)
                        readLogsOk = CaptureManager.hasReadLogsPermission(context)
                        dumpOk = CaptureManager.hasDumpPermission(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                )
            }
            }
        }
    }
}

/** Modal-style permissions card pinned to the bottom of the Home screen. Forced open while
 *  any of the three core (Tier 1) permissions are missing — capture can't work at all
 *  without them — then collapses into a small pastel pill once granted, which the user can
 *  still tap to come back and grant the optional Tier 2 (READ_LOGS/DUMP) perms whenever they
 *  like. Keeping this off the Target Picker screen means picking a target app and reading
 *  capture results never gets visually crowded out by permission setup. */
/** The collapsed pastel pill, shared between the floating overlay (while still incomplete or
 *  re-expanded for review) and the inline, scrolls-with-the-page placement once everything is
 *  fully granted. */
@Composable
private fun PermissionsPill(isTier2: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = if (isTier2) Color(0xFFFFF3B0) else Color(0xFFFFD8A8), // pastel yellow vs pastel orange
        shape = MaterialTheme.shapes.large,
        modifier = modifier.clickable { onClick() }
    ) {
        Text(
            if (isTier2) stringResource(R.string.permissions_pill_label_tier2)
            else stringResource(R.string.permissions_pill_label_tier1),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun PermissionsWidget(
    expanded: Boolean,
    onToggle: () -> Unit,
    overlayOk: Boolean,
    usageOk: Boolean,
    a11yOk: Boolean,
    batteryOptOk: Boolean,
    readLogsOk: Boolean,
    dumpOk: Boolean,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    if (!expanded) {
        // Tier 2 (READ_LOGS + DUMP also granted) gets the green check and turns pastel
        // yellow — fully done. Tier 1 only gets a yellow warning triangle on a pastel
        // orange pill instead, since the pill alone is the only Tier signal visible once
        // this is collapsed back down.
        PermissionsPill(isTier2 = readLogsOk && dumpOk, onClick = onToggle, modifier = modifier)
        return
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3B0)) // pastel yellow, same as the collapsed pill — keeps it visually distinct from the Basic Apps cards below
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Title + Close stay fixed at the top of the card; only the permission list below
            // scrolls. On a normal (non-foldable, non-tablet) screen the card is pinned to the
            // bottom of the page, so its full content — 4 permission rows, the Huawei hint,
            // two ADB terminal blocks, and the recheck button — doesn't all fit without
            // scrolling, and was getting cut off at the top edge before this.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.section_permissions_needed),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF3A3000), // explicit dark color — readable on the pastel
                                                // yellow card regardless of dynamic theming
                    modifier = Modifier.weight(1f)
                )
                // Always closeable, even with zero permissions granted — this card is a
                // reminder, not a blocker. The actual gate on starting a capture session is
                // the "Go to Target App Log Capture" button on Home, which re-summons this
                // card if Tier 1 still isn't satisfied instead of letting the user through.
                TextButton(
                    onClick = onToggle,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF3A3000))
                ) { Text(stringResource(R.string.permissions_close_button)) }
            }
            Spacer(Modifier.height(8.dp))

            val scrollState = rememberScrollState()
            val coroutineScope = rememberCoroutineScope()
            val maxListHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.45f).dp

            Box(modifier = Modifier.heightIn(max = maxListHeight)) {
                Column(
                    modifier = Modifier
                        .verticalScroll(scrollState)
                        .padding(end = 12.dp)
                ) {
                    Text(
                        stringResource(R.string.permissions_tier1_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF3A3000),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    PermissionRow(
                        label = stringResource(R.string.perm_overlay), granted = overlayOk,
                        onClick = {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    )
                    PermissionRow(
                        label = stringResource(R.string.perm_usage_access), granted = usageOk,
                        onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    )
                    PermissionRow(
                        label = stringResource(R.string.perm_accessibility), granted = a11yOk,
                        onClick = { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )
                    PermissionRow(
                        label = stringResource(R.string.perm_battery_opt), granted = batteryOptOk,
                        onClick = {
                            context.startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    android.net.Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    )
                    if (!batteryOptOk) {
                        Text(
                            stringResource(R.string.perm_battery_opt_huawei_hint),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                        )
                    }

                    Divider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.permissions_tier2_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF3A3000),
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    OptionalAdbPermissionRow(
                        granted = readLogsOk,
                        label = stringResource(R.string.read_logs_label),
                        hint = stringResource(R.string.read_logs_hint),
                        adbCmd = "adb shell pm grant ${context.packageName} android.permission.READ_LOGS"
                    )
                    Spacer(Modifier.height(8.dp))
                    OptionalAdbPermissionRow(
                        granted = dumpOk,
                        label = stringResource(R.string.dump_permission_label),
                        hint = stringResource(R.string.dump_permission_hint),
                        adbCmd = "adb shell pm grant ${context.packageName} android.permission.DUMP"
                    )
                    Button3D(onClick = onRecheck, modifier = Modifier.padding(top = 8.dp)) {
                        Text(stringResource(R.string.recheck_permissions))
                    }
                }

                PermissionsCardScrollbar(
                    scrollState = scrollState,
                    coroutineScope = coroutineScope,
                    modifier = Modifier
                        .fillMaxHeight()
                        .align(Alignment.CenterEnd)
                )
            }
        }
    }
}

/** ScrollState-driven equivalent of the log viewer's LazyListState scrollbar — Compose's
 *  plain Column + verticalScroll has no visible scrollbar by default either, and the
 *  permissions card's content is a plain Column (checkboxes/rows/terminal blocks), not a
 *  LazyColumn, so it needs its own pixel-based (rather than item-index-based) version. */
@Composable
private fun PermissionsCardScrollbar(
    scrollState: androidx.compose.foundation.ScrollState,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier
) {
    val maxScrollPx = scrollState.maxValue.toFloat()
    if (maxScrollPx <= 0f) return // content fits without scrolling — no thumb needed

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val totalContentPx = trackHeightPx + maxScrollPx
        val thumbFraction = (trackHeightPx / totalContentPx).coerceIn(0.08f, 1f)
        val thumbHeightPx = trackHeightPx * thumbFraction
        val scrollFraction = (scrollState.value / maxScrollPx).coerceIn(0f, 1f)
        val thumbOffsetPx = (trackHeightPx - thumbHeightPx) * scrollFraction
        val trackRange = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .background(Color(0xFF3A3000).copy(alpha = 0.2f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { androidx.compose.ui.unit.IntOffset(0, thumbOffsetPx.roundToInt()) }
                .width(6.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .background(
                    Color(0xFF3A3000).copy(alpha = 0.6f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(3.dp)
                )
                .pointerInput(maxScrollPx) {
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val newOffsetPx = (thumbOffsetPx + dragAmount.y).coerceIn(0f, trackRange)
                            coroutineScope.launch {
                                scrollState.scrollTo((newOffsetPx / trackRange * maxScrollPx).roundToInt())
                            }
                        }
                    )
                }
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun SubsectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
    )
}

/** Tappable version of [SubsectionLabel] with a chevron, so each category inside Device
 *  Information (Device/CPU/RAM/GPU/Battery) can collapse independently — the full card was
 *  otherwise always showing all five at once and eating a lot of screen space. */
@Composable
fun CollapsibleSubsectionLabel(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(top = 8.dp, bottom = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Text(
            if (expanded) "▾" else "▸",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** A translucent, glassy-looking card: the base color shows through at reduced alpha, with a
 *  soft white diagonal highlight near the top-left to read as glass/glossy rather than flat
 *  matte color, plus a thin light border to catch the edge like glass would. Used for every
 *  colored card in the app (Device Info, Permissions widget, GMS conflict warnings) so they
 *  all share one consistent "glassy" look instead of flat pastel fills. */
@Composable
fun GlassyCard(
    color: Color,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.medium,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.55f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.45f), shape)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(300f, 300f)
                    ),
                    shape
                )
        )
        content()
    }
}

/** A filled button with real depth instead of Material3's flat default: shadow elevation
 *  underneath, plus a top-to-bottom gradient (light highlight up top, dark shade at the
 *  bottom edge) so it reads as a convex, pressable 3D button rather than a flat color block.
 *  Drops shadow/highlight and dims the color when disabled, like a button that's sunk back
 *  into the surface. */
@Composable
fun Button3D(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    content: @Composable RowScope.() -> Unit
) {
    val shape = MaterialTheme.shapes.medium
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = if (enabled) color else color.copy(alpha = 0.4f),
        contentColor = contentColor,
        shadowElevation = if (enabled) 8.dp else 0.dp,
        tonalElevation = 4.dp,
        modifier = modifier
    ) {
        Box {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.30f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.18f)
                            )
                        ),
                        shape
                    )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )
        }
    }
}

@Composable
fun CpuCoreCard(core: CpuCoreFreq, modifier: Modifier = Modifier) {
    GlassyCard(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                stringResource(R.string.cpu_core_label, core.coreIndex),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                stringResource(R.string.cpu_core_max, core.maxFreqMHz),
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                stringResource(R.string.cpu_core_cur, core.curFreqMHz),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ComponentRow(c: CheckedApp) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (c.installed) "✅" else "❌",
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(c.label, style = MaterialTheme.typography.bodyLarge)
            }
            Text(c.pkg, style = MaterialTheme.typography.bodySmall)
            if (c.installed) {
                Text(
                    stringResource(R.string.component_version, c.versionName ?: "-", c.versionCode ?: 0L),
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    stringResource(R.string.component_installer, c.installer ?: "-"),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    stringResource(R.string.component_not_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
