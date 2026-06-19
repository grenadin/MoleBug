package com.debug.molebug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.debug.molebug.capture.CaptureManager
import kotlinx.coroutines.launch
import java.io.File

enum class Screen { HOME, TARGET_PICKER, LOG_VIEWER }

@Composable
fun MoleBugRoot(screenState: MutableState<Screen> = remember { mutableStateOf(Screen.HOME) }) {
    var screen by screenState

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (screen) {
                Screen.HOME -> MoleBugApp(
                    onOpenCapture = { screen = Screen.TARGET_PICKER },
                    onOpenLogViewer = { screen = Screen.LOG_VIEWER }
                )
                Screen.TARGET_PICKER -> TargetPickerScreen(
                    onBack = { screen = Screen.HOME },
                    onArmed = { screen = Screen.HOME } // user goes home then taps target app manually
                )
                Screen.LOG_VIEWER -> LogViewerScreen(onBack = { screen = Screen.HOME })
            }
        }
    }
}

/** ---------- Permission helpers ---------- */

private fun hasOverlayPermission(context: Context): Boolean =
    Settings.canDrawOverlays(context)

private fun hasUsageAccess(context: Context): Boolean {
    return try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), context.packageName
        )
        mode == android.app.AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }
}

private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${context.packageName}.capture.MoleAccessibilityService"
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

/** ---------- Screen: pick a target app + grant permissions + arm capture ---------- */

@Composable
fun TargetPickerScreen(onBack: () -> Unit, onArmed: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<CheckedApp>>(emptyList()) }
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    var overlayOk by remember { mutableStateOf(hasOverlayPermission(context)) }
    var usageOk by remember { mutableStateOf(hasUsageAccess(context)) }
    var a11yOk by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    var searchQuery by remember { mutableStateOf("") }
    var searchByPackage by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    val displayedApps = remember(apps, searchQuery, searchByPackage) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            apps.filter { app ->
                val haystack = if (searchByPackage) app.pkg else app.label
                haystack.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // Maps each first letter present in the (unfiltered, alphabetically-sorted) app list to
    // its index, so the A-Z strip can jump straight to where that letter's apps begin.
    val letterIndex = remember(apps) {
        val map = linkedMapOf<Char, Int>()
        apps.forEachIndexed { index, app ->
            val letter = app.label.firstOrNull()?.uppercaseChar()
            if (letter != null && letter in 'A'..'Z' && letter !in map) {
                map[letter] = index
            }
        }
        map
    }

    LaunchedEffect(Unit) {
        apps = DeviceInspector.listInstalledApps(context)
            .filter { it.pkg != context.packageName }
            .sortedBy { it.label.lowercase() }
    }

    // Re-check permission state whenever the user comes back from the Settings screen
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                overlayOk = hasOverlayPermission(context)
                usageOk = hasUsageAccess(context)
                a11yOk = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
            Text(stringResource(R.string.target_picker_title), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))

        var permissionsExpanded by remember { mutableStateOf(true) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { permissionsExpanded = !permissionsExpanded }
        ) {
            Text(
                stringResource(R.string.section_permissions_needed),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
            )
            Text(if (permissionsExpanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
        }

        if (permissionsExpanded) {
            PermissionRow(
                label = stringResource(R.string.perm_overlay), granted = overlayOk,
                onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                }
            )
            PermissionRow(
                label = stringResource(R.string.perm_usage_access), granted = usageOk,
                onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            )
            PermissionRow(
                label = stringResource(R.string.perm_accessibility), granted = a11yOk,
                onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            )

            val readLogsOk = remember { CaptureManager.hasReadLogsPermission(context) }
            val dumpOk = remember { CaptureManager.hasDumpPermission(context) }
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

            OptionalAdbPermissionRow(
                granted = readLogsOk,
                label = stringResource(R.string.read_logs_label),
                hint = stringResource(R.string.read_logs_hint),
                adbCmd = "adb shell pm grant ${context.packageName} android.permission.READ_LOGS",
                clipboard = clipboard
            )
            Spacer(Modifier.height(8.dp))
            OptionalAdbPermissionRow(
                granted = dumpOk,
                label = stringResource(R.string.dump_permission_label),
                hint = stringResource(R.string.dump_permission_hint),
                adbCmd = "adb shell pm grant ${context.packageName} android.permission.DUMP",
                clipboard = clipboard
            )
            Button(
                onClick = {
                    overlayOk = hasOverlayPermission(context)
                    usageOk = hasUsageAccess(context)
                    a11yOk = isAccessibilityServiceEnabled(context)
                },
                modifier = Modifier.padding(top = 8.dp)
            ) { Text(stringResource(R.string.recheck_permissions)) }
        }

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        SectionTitle(stringResource(R.string.section_target_app))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text(stringResource(R.string.search_apps_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.search_mode_label), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = !searchByPackage,
                onClick = { searchByPackage = false },
                label = { Text(stringResource(R.string.search_by_name)) }
            )
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected = searchByPackage,
                onClick = { searchByPackage = true },
                label = { Text(stringResource(R.string.search_by_package)) }
            )
        }
        Spacer(Modifier.height(4.dp))

        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                items(displayedApps) { app ->
                    ListItem(
                        leadingContent = { AppIcon(pkg = app.pkg) },
                        headlineContent = { Text(app.label) },
                        supportingContent = { Text(app.pkg, style = MaterialTheme.typography.bodySmall) },
                        trailingContent = {
                            RadioButton(
                                selected = selectedPkg == app.pkg,
                                onClick = { selectedPkg = app.pkg }
                            )
                        },
                        modifier = Modifier.clickable { selectedPkg = app.pkg }
                    )
                    Divider()
                }
            }

            // A-Z fast-scroll index, shown only while the search box is empty so it can jump
            // around the full unfiltered list instead of a filtered subset.
            if (searchQuery.isBlank() && letterIndex.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ('A'..'Z').forEach { letter ->
                        val available = letterIndex.containsKey(letter)
                        Text(
                            letter.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (available)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier
                                .padding(vertical = 1.dp, horizontal = 4.dp)
                                .clickable {
                                    val keys = letterIndex.keys.sorted()
                                    val targetIndex = letterIndex[letter]
                                        ?: keys.firstOrNull { it >= letter }?.let { letterIndex[it] }
                                        ?: keys.lastOrNull()?.let { letterIndex[it] }
                                    targetIndex?.let { idx ->
                                        coroutineScope.launch { listState.scrollToItem(idx) }
                                    }
                                }
                        )
                    }
                }
            }
        }

        val allPermsOk = overlayOk && usageOk && a11yOk
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                selectedPkg?.let {
                    CaptureManager.arm(context, it)
                    onArmed()
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(it)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launchIntent)
                    } else {
                        // No launchable activity found for this package -> fall back to the
                        // system home screen so the user can open it manually themselves.
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(homeIntent)
                    }
                }
            },
            enabled = allPermsOk && selectedPkg != null,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (!allPermsOk) stringResource(R.string.button_grant_perms_first)
                else stringResource(R.string.button_start_capture)
            )
        }
        if (allPermsOk && selectedPkg != null) {
            Text(
                stringResource(R.string.start_capture_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** Loads the installed app's own launcher icon for the picker row. Loaded once per
 *  package (not per recomposition) and silently falls back to a placeholder square if the
 *  icon can't be read (e.g. for protected system packages). */
@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val bitmap = remember(pkg) {
        runCatching {
            context.packageManager.getApplicationIcon(pkg).toBitmap().asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
private fun OptionalAdbPermissionRow(
    granted: Boolean,
    label: String,
    hint: String,
    adbCmd: String,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(if (granted) "✅" else "⚠️", modifier = Modifier.padding(end = 8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (!granted) {
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (!granted) {
        Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(stringResource(R.string.read_logs_instructions), style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(adbCmd, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(adbCmd)) }) {
                    Text(stringResource(R.string.copy_command))
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(if (granted) "✅" else "❌", modifier = Modifier.padding(end = 8.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!granted) {
            TextButton(onClick = onClick) { Text(stringResource(R.string.perm_open_button)) }
        }
    }
}

/** ---------- Screen: log viewer ---------- */

@Composable
fun LogViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(CaptureManager.readLog(context)) }
    val target = CaptureManager.targetPackage(context)
    var capturing by remember { mutableStateOf(CaptureManager.isCapturing(context)) }
    var armed by remember { mutableStateOf(CaptureManager.isArmed(context)) }

    // Poll the log file + state every second so the screen stays live while capturing
    LaunchedEffect(Unit) {
        while (true) {
            logText = CaptureManager.readLog(context)
            capturing = CaptureManager.isCapturing(context)
            armed = CaptureManager.isArmed(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
            Text(stringResource(R.string.log_viewer_title, target ?: "-"), style = MaterialTheme.typography.titleLarge)
        }

        Text(
            when {
                capturing -> stringResource(R.string.status_capturing, CaptureManager.crashCount(context))
                armed -> stringResource(R.string.status_armed)
                else -> stringResource(R.string.status_idle, CaptureManager.crashCount(context))
            },
            style = MaterialTheme.typography.bodyMedium
        )

        if (capturing) {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val intent = Intent(context, com.debug.molebug.capture.CaptureService::class.java).apply {
                        action = com.debug.molebug.capture.CaptureService.ACTION_STOP
                    }
                    context.startService(intent)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.button_stop_capture)) }
        }

        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer {
                Text(
                    logText.ifBlank { stringResource(R.string.log_empty) },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val path = CaptureManager.logPath(context)
                    if (path != null) {
                        // file already saved continuously; just confirm
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.button_saved_automatically)) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val path = CaptureManager.logPath(context) ?: return@Button
                    val file = File(path)
                    if (!file.exists()) return@Button
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser_title)))
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.button_share_file)) }
        }
    }
}
