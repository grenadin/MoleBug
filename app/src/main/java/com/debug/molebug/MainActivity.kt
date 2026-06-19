package com.debug.molebug

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
    val emuiVersion: String
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
            }
        )
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
        sb.appendLine("Device name: ${deviceInfo.deviceName}")
        sb.appendLine("Model: ${deviceInfo.model}")
        sb.appendLine("Build number: ${deviceInfo.buildNumber}")
        sb.appendLine("Software version: ${deviceInfo.softwareVersion}")
        sb.appendLine("EMUI version: ${deviceInfo.emuiVersion}")
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

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
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

                SectionTitle(stringResource(R.string.section_device_info))
                InfoRow(stringResource(R.string.info_device_name), deviceInfo.deviceName)
                InfoRow(stringResource(R.string.info_model), deviceInfo.model)
                InfoRow(stringResource(R.string.info_build_number), deviceInfo.buildNumber)
                InfoRow(stringResource(R.string.info_software_version), deviceInfo.softwareVersion)
                InfoRow(stringResource(R.string.info_emui_version), deviceInfo.emuiVersion)

                Spacer(Modifier.height(16.dp))
                SectionTitle(stringResource(R.string.section_check_components))
                checks.forEach { c ->
                    ComponentRow(c)
                }

                Spacer(Modifier.height(16.dp))

                if (allInstalled) {
                    Button(onClick = {
                        val allApps = DeviceInspector.listInstalledApps(context)
                        val file = DeviceInspector.exportLog(context, deviceInfo, checks, allApps)
                        exportedPath = file.absolutePath
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

                Spacer(Modifier.height(24.dp))
                Divider()
                Spacer(Modifier.height(16.dp))
                SectionTitle(stringResource(R.string.section_capture_other_app))
                Text(
                    stringResource(R.string.capture_intro),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onOpenCapture, modifier = Modifier.fillMaxWidth()) {
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
