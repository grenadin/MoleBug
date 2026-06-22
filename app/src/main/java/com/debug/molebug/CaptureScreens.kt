package com.debug.molebug

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.debug.molebug.capture.CaptureManager
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

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

fun hasOverlayPermission(context: Context): Boolean =
    Settings.canDrawOverlays(context)

fun hasUsageAccess(context: Context): Boolean {
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

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${context.packageName}.capture.MoleAccessibilityService"
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    return try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    } catch (e: Exception) {
        false
    }
}

/** Copies a file into the public Download/MoleBug folder via MediaStore — the only folder a
 *  third-party file manager can reliably browse into on Android 11+, since our own app's
 *  external-files directory (Android/data/<pkg>/...) is OS-blocked from SAF/file-manager
 *  access entirely, for every app including the owner. No storage permission needed; writing
 *  through MediaStore's own Downloads collection is always allowed for an app's own inserts. */
private fun copyFileToPublicDownloads(context: Context, source: File): Boolean {
    if (!source.exists()) return false
    // MediaStore.Downloads is API 29+; older devices fall back to just opening the Files app
    // with no folder target, same as before this change.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
    return try {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/MoleBug")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        }
        true
    } catch (e: Exception) {
        false
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
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
    var batteryOptOk by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

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

    var readLogsOk by remember { mutableStateOf(CaptureManager.hasReadLogsPermission(context)) }
    var dumpOk by remember { mutableStateOf(CaptureManager.hasDumpPermission(context)) }

    // Re-check permission state whenever the user comes back from the Settings screen —
    // permissions themselves are now granted from the Home screen's modal, but this screen
    // still needs the live state for the Tier badge and to gate the Start Capture button.
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

    var captureOptionsExpanded by remember { mutableStateOf(false) }

    // The page scrolls as one unit so an expanded Capture Options section never squeezes the
    // app list down to nothing. Scrolling all the way back to the top auto-collapses it again,
    // so the compact layout returns once you're done with it instead of staying expanded
    // forever.
    val pageScrollState = rememberScrollState()
    var hasScrolledAway by remember { mutableStateOf(false) }
    LaunchedEffect(pageScrollState) {
        snapshotFlow { pageScrollState.value }.collect { value ->
            if (value > 0) {
                hasScrolledAway = true
            } else if (hasScrolledAway) {
                captureOptionsExpanded = false
                hasScrolledAway = false
            }
        }
    }

    val allPermsOk = overlayOk && usageOk && a11yOk
    val tier = if (readLogsOk && dumpOk) 2 else if (allPermsOk) 1 else 0

    Column(modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(pageScrollState)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_button)) }
            Text(stringResource(R.string.target_picker_title), style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))

        // Title/tier/search packed tighter (no SectionTitle's usual vertical padding) to free
        // up vertical space so Capture Options further down becomes reachable without as
        // much scrolling.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.section_target_app),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                when (tier) {
                    2 -> stringResource(R.string.tier_badge_2)
                    1 -> stringResource(R.string.tier_badge_1)
                    else -> stringResource(R.string.tier_badge_none)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (tier == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }
        if (tier == 1) {
            Text(
                stringResource(R.string.tier_1_missing_hint),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
            )
        }
        Spacer(Modifier.height(4.dp))

        // Placeholder instead of a floating label, plus a smaller text style — a floating
        // label reserves extra vertical space above the text for its animated shrink/rise,
        // which is what was actually making this field tall (it was never about the width).
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.search_apps_label), style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().heightIn(min = 0.dp)
        )
        Spacer(Modifier.height(2.dp))
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
        Spacer(Modifier.height(2.dp))

        // Shortened by roughly one list row's height (~72dp) from the original 420dp so the
        // "Start Capturing Log" button below stays visible without scrolling — otherwise
        // users who never scroll the app list wouldn't realize it's scrollable at all.
        Row(modifier = Modifier.height(348.dp).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxHeight()) {
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

        Spacer(Modifier.height(8.dp))
        Button3D(
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

        Spacer(Modifier.height(16.dp))
        Divider()
        Spacer(Modifier.height(8.dp))

        var networkTimingEnabled by remember { mutableStateOf(CaptureManager.isNetworkTimingEnabled(context)) }
        var anrTraceEnabled by remember { mutableStateOf(CaptureManager.isAnrTraceEnabled(context)) }
        var eventsBufferEnabled by remember { mutableStateOf(CaptureManager.isEventsBufferEnabled(context)) }
        var stallWatchdogEnabled by remember { mutableStateOf(CaptureManager.isStallWatchdogEnabled(context)) }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { captureOptionsExpanded = !captureOptionsExpanded }
        ) {
            Text(
                stringResource(R.string.section_capture_options),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
            )
            Text(
                if (captureOptionsExpanded) "▾" else "▸",
                fontSize = 28.sp,
                modifier = Modifier.padding(8.dp)
            )
        }

        if (captureOptionsExpanded) {
            CaptureOptionRow(
                checked = networkTimingEnabled,
                label = stringResource(R.string.opt_network_timing_label),
                hint = stringResource(R.string.opt_network_timing_hint),
                onCheckedChange = {
                    networkTimingEnabled = it
                    CaptureManager.setNetworkTimingEnabled(context, it)
                }
            )
            CaptureOptionRow(
                checked = anrTraceEnabled,
                label = stringResource(R.string.opt_anr_trace_label),
                hint = stringResource(R.string.opt_anr_trace_hint),
                onCheckedChange = {
                    anrTraceEnabled = it
                    CaptureManager.setAnrTraceEnabled(context, it)
                }
            )
            CaptureOptionRow(
                checked = eventsBufferEnabled,
                label = stringResource(R.string.opt_events_buffer_label),
                hint = stringResource(R.string.opt_events_buffer_hint),
                onCheckedChange = {
                    eventsBufferEnabled = it
                    CaptureManager.setEventsBufferEnabled(context, it)
                }
            )
            CaptureOptionRow(
                checked = stallWatchdogEnabled,
                label = stringResource(R.string.opt_stall_watchdog_label),
                hint = stringResource(R.string.opt_stall_watchdog_hint),
                onCheckedChange = {
                    stallWatchdogEnabled = it
                    CaptureManager.setStallWatchdogEnabled(context, it)
                }
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

/** The command only ever runs from a PC's own terminal (no way to send it there from the
 *  phone that's actually useful — package name is fixed, so typing it straight into the PC
 *  is just as fast as copying), so this shows it as a mock terminal instead of a copy
 *  button: black screen, green monospace text, exactly like it'll look once typed for real. */
@Composable
fun OptionalAdbPermissionRow(
    granted: Boolean,
    label: String,
    hint: String,
    adbCmd: String
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
        Text(
            stringResource(R.string.read_logs_instructions),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF000000), shape = MaterialTheme.shapes.small)
                .padding(12.dp)
        ) {
            SelectionContainer {
                Text(
                    "$ $adbCmd",
                    color = Color(0xFF33FF33),
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
fun PermissionRow(label: String, granted: Boolean, onClick: () -> Unit) {
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

@Composable
private fun CaptureOptionRow(
    checked: Boolean,
    label: String,
    hint: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall)
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
    var logFileSizeBytes by remember { mutableStateOf(CaptureManager.logPath(context)?.let { File(it).length() } ?: 0L) }

    // Poll the log file + state every second so the screen stays live while capturing
    LaunchedEffect(Unit) {
        while (true) {
            logText = CaptureManager.readLog(context)
            capturing = CaptureManager.isCapturing(context)
            armed = CaptureManager.isArmed(context)
            logFileSizeBytes = CaptureManager.logPath(context)?.let { File(it).length() } ?: 0L
            kotlinx.coroutines.delay(1000)
        }
    }

    val logLines = remember(logText) { logText.lines() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var logSearchQuery by remember { mutableStateOf("") }
    var currentMatchPointer by remember { mutableStateOf(0) }
    val matchIndices = remember(logLines, logSearchQuery) {
        if (logSearchQuery.isBlank()) emptyList()
        else logLines.indices.filter { logLines[it].contains(logSearchQuery, ignoreCase = true) }
    }

    fun jumpToMatch(pointer: Int) {
        if (matchIndices.isEmpty()) return
        val clamped = ((pointer % matchIndices.size) + matchIndices.size) % matchIndices.size
        currentMatchPointer = clamped
        coroutineScope.launch { listState.scrollToItem(matchIndices[clamped]) }
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
        Text(
            stringResource(R.string.log_file_size, formatFileSize(logFileSizeBytes)),
            style = MaterialTheme.typography.bodySmall
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

        // Trying to point a file manager straight at a folder via intent/URI turned out to
        // crash this EMUI build's Files app outright (bounced to the home screen) even for a
        // public, SAF-browsable folder — not just our blocked Android/data path. Since we
        // can't catch or diagnose a crash happening inside another app's process, this no
        // longer attempts to auto-navigate at all: it copies the log to the public
        // Download/MoleBug folder (so it's somewhere findable), then just opens the Files
        // app's own home screen — the one action that can't crash regardless of EMUI quirks —
        // and tells the user exactly where to tap to find it.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .clickable {
                        val copied = CaptureManager.logPath(context)?.let { copyFileToPublicDownloads(context, File(it)) } ?: false
                        context.packageManager.getLaunchIntentForPackage("com.huawei.filemanager")
                            ?.let { context.startActivity(it) }
                        android.widget.Toast.makeText(
                            context,
                            if (copied) context.getString(R.string.copied_to_downloads_hint)
                            else context.getString(R.string.copy_to_downloads_failed),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
            ) {
                AppIcon(pkg = "com.huawei.filemanager")
            }
        }
        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = logSearchQuery,
            onValueChange = {
                logSearchQuery = it
                currentMatchPointer = 0
                jumpToMatch(0)
            },
            label = { Text(stringResource(R.string.log_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (logSearchQuery.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (matchIndices.isEmpty()) stringResource(R.string.log_search_no_matches)
                    else stringResource(R.string.log_search_match_count, currentMatchPointer + 1, matchIndices.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { jumpToMatch(currentMatchPointer - 1) }, enabled = matchIndices.isNotEmpty()) {
                    Text(stringResource(R.string.log_search_previous))
                }
                TextButton(onClick = { jumpToMatch(currentMatchPointer + 1) }, enabled = matchIndices.isNotEmpty()) {
                    Text(stringResource(R.string.log_search_next))
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        Card(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (logLines.size <= 1 && logText.isBlank()) {
                Text(
                    stringResource(R.string.log_empty),
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().padding(start = 12.dp, end = 20.dp)
                        ) {
                            itemsIndexed(logLines) { index, line ->
                                val isCurrentMatch = matchIndices.isNotEmpty() && matchIndices[currentMatchPointer] == index
                                val isOtherMatch = !isCurrentMatch && logSearchQuery.isNotBlank() &&
                                        line.contains(logSearchQuery, ignoreCase = true)
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
                                        .padding(vertical = 1.dp)
                                )
                            }
                        }
                    }
                    LogScrollbar(
                        listState = listState,
                        totalItems = logLines.size,
                        coroutineScope = coroutineScope,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(16.dp)
                    )
                }
            }
        }

        if (logLines.isNotEmpty()) {
            val firstVisible = listState.firstVisibleItemIndex
            val percent = if (logLines.size > 1) (firstVisible * 100 / (logLines.size - 1)).coerceIn(0, 100) else 100
            Text(
                stringResource(R.string.log_scroll_position, firstVisible + 1, logLines.size, percent),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button3D(
                onClick = {
                    val path = CaptureManager.logPath(context)
                    if (path != null) {
                        // file already saved continuously; just confirm
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.button_saved_automatically)) }
            Spacer(Modifier.width(8.dp))
            Button3D(
                onClick = {
                    val zipFile = CaptureManager.zipLogFile(context) ?: return@Button3D
                    val uri = FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", zipFile
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        // Pre-fills a prompt alongside the attached file for share targets
                        // that read EXTRA_TEXT (most chat-style AI apps do, even with a file
                        // attached) — saves typing the same framing every time you hand a log
                        // off for analysis. Apps that ignore EXTRA_TEXT when a stream is
                        // present just show the file with no prompt, same as before.
                        putExtra(
                            Intent.EXTRA_TEXT,
                            context.getString(R.string.ai_prompt_capture_log, target ?: "the target app")
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser_title)))
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.button_share_file)) }
        }
    }
}

/** Compose's LazyColumn has no built-in visible scrollbar, so this draws one: a thumb sized
 *  proportionally to (visible items / total items) and positioned from current scroll
 *  progress, draggable to jump anywhere in the log instead of only flinging/dragging the
 *  list itself — important once a log is thousands of lines long. */
@Composable
private fun LogScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    totalItems: Int,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    modifier: Modifier = Modifier
) {
    if (totalItems <= 0) return
    val density = androidx.compose.ui.platform.LocalDensity.current

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier) {
        val trackHeightPx = with(density) { maxHeight.toPx() }
        val visibleCount = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val thumbFraction = (visibleCount.toFloat() / totalItems).coerceIn(0.05f, 1f)
        val thumbHeightPx = trackHeightPx * thumbFraction
        val maxScrollableItems = (totalItems - visibleCount).coerceAtLeast(1)
        val scrollFraction = (listState.firstVisibleItemIndex.toFloat() / maxScrollableItems).coerceIn(0f, 1f)
        val thumbOffsetPx = (trackHeightPx - thumbHeightPx) * scrollFraction

        fun scrollToFraction(fraction: Float) {
            val targetIndex = (fraction.coerceIn(0f, 1f) * maxScrollableItems).roundToInt()
            coroutineScope.launch { listState.scrollToItem(targetIndex) }
        }

        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(4.dp)
                .align(Alignment.CenterEnd)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { androidx.compose.ui.unit.IntOffset(0, thumbOffsetPx.roundToInt()) }
                .width(8.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                )
                .pointerInput(totalItems, maxScrollableItems) {
                    var dragOffsetPx = thumbOffsetPx
                    val trackRange = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
                    detectDragGestures(
                        onDragStart = { dragOffsetPx = thumbOffsetPx },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetPx = (dragOffsetPx + dragAmount.y).coerceIn(0f, trackRange)
                            scrollToFraction(dragOffsetPx / trackRange)
                        }
                    )
                }
        )
    }
}
