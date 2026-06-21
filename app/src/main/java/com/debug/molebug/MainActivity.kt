package com.debug.molebug

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/** -------- Data model -------- */
data class CheckedApp(
    val label: String,
    val pkg: String,
    val installed: Boolean,
    val versionName: String?,
    val versionCode: Long?,
    val installer: String?
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
    val cpuVendor: String,
    val cpuCurFreqMHz: String,
    val batteryPercent: String,
    val batteryHealth: String,
    val batteryStatus: String
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
            cpuVendor = readCpuVendor(),
            cpuCurFreqMHz = readCpuCurFreqMHz(),
            batteryPercent = readBatteryPercent(context),
            batteryHealth = readBatteryHealth(context),
            batteryStatus = readBatteryStatus(context)
        )
    }

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
        sb.appendLine("[RAM]")
        sb.appendLine("RAM total: ${deviceInfo.ramTotal}")
        sb.appendLine("RAM type: ${deviceInfo.ramType}")
        sb.appendLine("[GPU]")
        sb.appendLine("GPU renderer: ${deviceInfo.gpuRenderer}")
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
    val checks = remember {
        REQUIRED_COMPONENTS.map { (label, pkg, _) -> DeviceInspector.checkApp(context, label, pkg) }
    }
    val allInstalled = checks.all { it.installed }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var exportedPath by remember { mutableStateOf<String?>(null) }
    var exportedContent by remember { mutableStateOf<String?>(null) }

    // The Device Info card auto-collapses to just its title once the page is scrolled away
    // from the top, then expands again on scroll-back-to-top — same pattern as the Target
    // Picker's Permissions/Capture Options sections, so the card doesn't eat the whole
    // screen while the user is trying to get to Check Basic Apps / Export below it.
    var deviceInfoExpanded by remember { mutableStateOf(true) }
    val pageScrollState = rememberScrollState()
    var hasScrolledAway by remember { mutableStateOf(false) }
    LaunchedEffect(pageScrollState) {
        snapshotFlow { pageScrollState.value }.collect { value ->
            if (value > 0) {
                hasScrolledAway = true
                deviceInfoExpanded = false
            } else if (hasScrolledAway) {
                deviceInfoExpanded = true
                hasScrolledAway = false
            }
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
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

                Card(modifier = Modifier.fillMaxWidth()) {
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
                            SubsectionLabel(stringResource(R.string.subsection_device))
                            InfoRow(stringResource(R.string.info_device_name), deviceInfo.deviceName)
                            InfoRow(stringResource(R.string.info_model), deviceInfo.model)
                            InfoRow(stringResource(R.string.info_build_number), deviceInfo.buildNumber)
                            InfoRow(stringResource(R.string.info_software_version), deviceInfo.softwareVersion)
                            InfoRow(stringResource(R.string.info_emui_version), deviceInfo.emuiVersion)

                            SubsectionLabel(stringResource(R.string.subsection_cpu))
                            InfoRow(stringResource(R.string.info_cpu_abi), deviceInfo.cpuAbi)
                            InfoRow(stringResource(R.string.info_cpu_cores), deviceInfo.cpuCores.toString())
                            InfoRow(stringResource(R.string.info_cpu_vendor), deviceInfo.cpuVendor)
                            InfoRow(stringResource(R.string.info_cpu_freq), deviceInfo.cpuMaxFreqMHz)
                            InfoRow(stringResource(R.string.info_cpu_cur_freq), deviceInfo.cpuCurFreqMHz)

                            SubsectionLabel(stringResource(R.string.subsection_ram))
                            InfoRow(stringResource(R.string.info_ram_total), deviceInfo.ramTotal)
                            InfoRow(stringResource(R.string.info_ram_type), deviceInfo.ramType)

                            SubsectionLabel(stringResource(R.string.subsection_gpu))
                            InfoRow(stringResource(R.string.info_gpu_renderer), deviceInfo.gpuRenderer)

                            SubsectionLabel(stringResource(R.string.subsection_battery))
                            InfoRow(stringResource(R.string.info_battery_percent), deviceInfo.batteryPercent)
                            InfoRow(stringResource(R.string.info_battery_health), deviceInfo.batteryHealth)
                            InfoRow(stringResource(R.string.info_battery_status), deviceInfo.batteryStatus)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                SectionTitle(stringResource(R.string.section_check_components))
                checks.forEach { c ->
                    ComponentRow(c)
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
                    Button(
                        onClick = {
                            val path = exportedPath ?: return@Button
                            val file = File(path)
                            if (!file.exists()) return@Button
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", file
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
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
                Button(onClick = onOpenCapture, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.button_go_capture))
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onOpenLogViewer, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.button_view_log))
                }
            }
        }
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
