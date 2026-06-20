package com.debug.molebug.capture

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.debug.molebug.MainActivity
import com.debug.molebug.R
import java.io.File

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.debug.molebug.ACTION_START"
        const val ACTION_STOP = "com.debug.molebug.ACTION_STOP"
        const val EXTRA_TARGET_PKG = "target_pkg"
        private const val CHANNEL_ID = "molebug_capture"
        private const val NOTI_ID = 1001
        private const val POLL_INTERVAL_MS = 1000L
        private const val STALL_THRESHOLD_MS = 15000L
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var stopButtonView: View? = null
    private var blinking = false
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val pollHandler = Handler(Looper.getMainLooper())
    private var targetPkg: String? = null
    private var lastEventTime = System.currentTimeMillis()

    @Volatile private var logcatRunning = false
    private var logcatProcess: Process? = null
    private var systemWatcherProcess: Process? = null
    private var lmkWatcherProcess: Process? = null
    private var eventsWatcherProcess: Process? = null
    private var currentPid = -1
    private val memoryHandler = Handler(Looper.getMainLooper())

    // Capture Options checklist, read once per session when capture starts
    private var networkTimingEnabled = true
    private var anrTraceEnabled = true
    private var eventsBufferEnabled = true
    private var stallWatchdogEnabled = true
    private var foregroundSinceMs = 0L
    private var lastAppLogLineMs = 0L
    private var stallWarned = false
    private val stallHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                targetPkg = intent.getStringExtra(EXTRA_TARGET_PKG)
                startForeground(NOTI_ID, buildNotification())
                showOverlay()
                startBlinking()
                startUsagePolling()
                if (CaptureManager.hasReadLogsPermission(applicationContext)) {
                    startLogcatCapture()
                } else {
                    CaptureManager.appendLog(
                        applicationContext,
                        "[SYSTEM] No READ_LOGS permission — only events (foreground/background/dialog) " +
                                "are captured, no real stack trace. Run adb grant first."
                    )
                }
            }
            ACTION_STOP -> {
                stopCaptureInternal("Stopped from the notification button")
            }
        }
        return START_STICKY
    }

    private fun startLogcatCapture() {
        logcatRunning = true
        val pkg = targetPkg ?: return
        networkTimingEnabled = CaptureManager.isNetworkTimingEnabled(applicationContext)
        anrTraceEnabled = CaptureManager.isAnrTraceEnabled(applicationContext)
        eventsBufferEnabled = CaptureManager.isEventsBufferEnabled(applicationContext)
        stallWatchdogEnabled = CaptureManager.isStallWatchdogEnabled(applicationContext)
        dumpLogcatBacklog(pkg)
        startSystemBufferWatcher(pkg)
        startLmkWatcher(pkg)
        startMemoryPolling()
        if (eventsBufferEnabled) startEventsWatcher(pkg)
        if (stallWatchdogEnabled) startStallWatchdog(pkg)
    }

    /** A log line is "bulk listing" noise (e.g. Huawei's loadRunningPackages/appLruQueue
     *  dumps) rather than something actually about the target app if it mentions five or
     *  more distinct dotted package-like identifiers — a real crash/event line about one
     *  app essentially never does. Filters this out wherever we match by package-name
     *  substring across unfiltered buffers, instead of pid-filtered ones. */
    private val packageLikeTokenRegex = Regex("[a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z][a-zA-Z0-9_]*){2,}")
    private fun looksLikeBulkListing(line: String): Boolean = packageLikeTokenRegex.findAll(line).count() >= 5

    /** One-shot dump of whatever is already sitting in the log buffers (main/system/crash)
     *  before we ever attach to a pid, filtered down to lines mentioning the target package
     *  (and not bulk package-listing noise). Covers crashes that happen so fast the pid
     *  watcher below never catches a live pid for them. */
    private fun dumpLogcatBacklog(pkg: String) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash")
            )
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            val relevant = lines.filter { it.contains(pkg, ignoreCase = true) && !looksLikeBulkListing(it) }
            if (relevant.isNotEmpty()) {
                CaptureManager.appendLog(applicationContext, "[LOGCAT-HISTORY] ${relevant.size} buffered line(s) already mentioning $pkg before capture started:")
                relevant.forEach { CaptureManager.appendLog(applicationContext, "[LOGCAT-HISTORY] $it") }
            }
        } catch (e: Exception) {
            CaptureManager.appendLog(applicationContext, "[LOGCAT-HISTORY] Failed to dump backlog: ${e.message}")
        }
    }

    private val anrKeywords = listOf("ANR in", "Input dispatching timed out", "Reason: Input dispatching timed out")

    /** Streams one `logcat --pid=PID` session on a background reader until it ends or is destroyed.
     *  Pulls from main + crash buffers — crash carries Android's own concise crash summary,
     *  not just whatever the app itself printed to the main buffer. */
    private fun attachLogcatForPid(pid: Int) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "--pid=$pid", "-v", "threadtime", "-b", "main", "-b", "crash")
            )
            logcatProcess = process
            // Reader runs on its own thread so the pid-watch loop above isn't blocked
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (!logcatRunning) return@forEachLine
                        CaptureManager.appendLog(applicationContext, "[LOGCAT] $line")
                        lastAppLogLineMs = System.currentTimeMillis()
                        stallWarned = false
                        if (line.contains("FATAL EXCEPTION") || line.contains("AndroidRuntime")) {
                            CaptureManager.appendLog(applicationContext, "[LOGCAT-CRASH] Found a real crash stack trace, see lines above")
                            CaptureManager.appendLog(applicationContext, "[NETWORK] ${networkSnapshot()}")
                            logNetworkSocketSnapshotIfPossible()
                            logPowerStateSnapshot()
                            logTimingIfEnabled()
                        }
                    }
                } catch (e: Exception) {
                    // process destroyed/pipe closed — normal when we re-attach to a new pid
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            CaptureManager.appendLog(applicationContext, "[LOGCAT] Failed to open logcat: ${e.message}")
        }
    }

    /** Matches ActivityManager's `ProcessRecord{<hash> <pid>:<package>/<uid>}` text, which the
     *  system buffer logs repeatedly on every app launch/foreground/process-death event. Lets
     *  us discover the target's live pid purely by reading log text we already have permission
     *  to read — no `pidof`/`ps`/`sh` process needs to be spawned at all. */
    private fun pidPatternFor(pkg: String) = Regex("(\\d+):${Regex.escape(pkg)}(?:/|\\})")

    /** Single tail of the unfiltered `system` buffer for the whole capture session, doing two
     *  jobs at once:
     *  1. ANR entries are logged by system_server (ActivityManager) under system_server's own
     *     pid, never the target app's — `--pid` filtering on the target's pid could never
     *     catch them, so this matches by package name + ANR keywords in the text itself.
     *  2. Discovers the target's current pid from ProcessRecord-style mentions and (re)attaches
     *     `logcat --pid=X` whenever it changes — replacing the old `pidof`/`ps`-based polling
     *     loop, which silently failed forever: apps run in the SELinux `untrusted_app` domain
     *     and can't exec `sh`/`pidof`/`ps` the way `adb shell` (in the `shell` domain) can. */
    private fun startSystemBufferWatcher(pkg: String) {
        val pidPattern = pidPatternFor(pkg)
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "threadtime", "-b", "system")
            )
            systemWatcherProcess = process
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (!logcatRunning) return@forEachLine

                        if (line.contains(pkg, ignoreCase = true) && anrKeywords.any { line.contains(it) }) {
                            CaptureManager.appendLog(applicationContext, "[LOGCAT-ANR] $line")
                            CaptureManager.appendLog(applicationContext, "[LOGCAT-ANR] Detected an ANR (app not responding) for $pkg")
                            CaptureManager.appendLog(applicationContext, "[NETWORK] ${networkSnapshot()}")
                            logNetworkSocketSnapshotIfPossible()
                            logPowerStateSnapshot()
                            logTimingIfEnabled()
                            if (anrTraceEnabled) logAnrTraceIfAvailable()
                        }

                        val newPid = pidPattern.find(line)?.groupValues?.get(1)?.toIntOrNull()
                        if (newPid != null && newPid != currentPid) {
                            val previousPid = currentPid
                            if (previousPid != -1) {
                                CaptureManager.appendLog(applicationContext, "[LOGCAT] pid $previousPid replaced by pid $newPid (new launch or crash-restart)")
                                checkExitReason(pkg, previousPid)
                            }
                            currentPid = newPid
                            lastAppLogLineMs = System.currentTimeMillis()
                            stallWarned = false
                            logcatProcess?.destroy()
                            CaptureManager.appendLog(applicationContext, "[LOGCAT] attach pid=$newPid ($pkg)")
                            attachLogcatForPid(newPid)
                        }
                    }
                } catch (e: Exception) {
                    // process destroyed/pipe closed — normal on stop
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            CaptureManager.appendLog(applicationContext, "[LOGCAT] Failed to start system buffer watcher: ${e.message}")
        }
    }

    private val lmkKeywords = listOf("Killed process", "lowmemorykiller", "Out of memory")

    /** Low-memory-killer terminations show up in the kernel log buffer, not main/system/crash,
     *  and (like ANR) are not attributed to the target app's own pid. Tail it unfiltered for
     *  the whole session so a background OOM-kill isn't mistaken for a crash. */
    private fun startLmkWatcher(pkg: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime", "-b", "kernel"))
            lmkWatcherProcess = process
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (!logcatRunning) return@forEachLine
                        if (line.contains(pkg, ignoreCase = true) && lmkKeywords.any { line.contains(it, ignoreCase = true) }) {
                            CaptureManager.appendLog(applicationContext, "[LMK] $line")
                            CaptureManager.appendLog(applicationContext, "[LMK] The system's low-memory killer terminated $pkg — likely an OOM kill, not a crash")
                        }
                    }
                } catch (e: Exception) {
                    // process destroyed/pipe closed — normal on stop
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            CaptureManager.appendLog(applicationContext, "[LMK] Failed to start low-memory-killer watcher: ${e.message}")
        }
    }

    /** Periodic PSS memory snapshot of the target's current pid, so a log full of crash text
     *  can also show whether the process was already under memory pressure beforehand. */
    private fun startMemoryPolling() {
        val r = object : Runnable {
            override fun run() {
                if (!logcatRunning) return
                val pid = currentPid
                if (pid != -1) {
                    CaptureManager.appendLog(applicationContext, "[MEMORY] pid=$pid ${memorySnapshot(pid)}")
                }
                memoryHandler.postDelayed(this, 5000)
            }
        }
        memoryHandler.postDelayed(r, 5000)
    }

    private fun memorySnapshot(pid: Int): String {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()
            if (info != null) "TotalPss=${info.totalPss}KB" else "unavailable (process may have already died)"
        } catch (e: Exception) {
            "failed to read: ${e.message}"
        }
    }

    private fun networkSnapshot(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return "no active network"
            val caps = cm.getNetworkCapabilities(network) ?: return "active network but no capabilities"
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "OTHER"
            }
            val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            "$transport, internet=$internet, validated=$validated"
        } catch (e: Exception) {
            "failed to read: ${e.message}"
        }
    }

    /** Counts the target's currently-open TCP sockets and any bytes still sitting in their
     *  send/receive queues, by reading /proc/net/tcp[6] directly — plain file I/O, no exec
     *  needed. Found while diagnosing a real Aurora Store hang: confirmed the app was NOT
     *  actually waiting on a pending network response at that moment (0 active sockets with
     *  queued bytes), which a logcat-only capture could never have shown either way. */
    private fun networkSocketSnapshot(pkg: String): String {
        return try {
            val uid = packageManager.getApplicationInfo(pkg, 0).uid
            var socketCount = 0
            var queuedBytes = 0L
            for (path in listOf("/proc/net/tcp", "/proc/net/tcp6")) {
                val file = File(path)
                if (!file.exists()) continue
                file.readLines().drop(1).forEach { line ->
                    val fields = line.trim().split(Regex("\\s+"))
                    if (fields.size > 7 && fields[7].toIntOrNull() == uid) {
                        socketCount++
                        val queues = fields[4].split(":")
                        val tx = queues.getOrNull(0)?.toLongOrNull(16) ?: 0L
                        val rx = queues.getOrNull(1)?.toLongOrNull(16) ?: 0L
                        queuedBytes += tx + rx
                    }
                }
            }
            "open_tcp_sockets=$socketCount queued_bytes=$queuedBytes"
        } catch (e: Exception) {
            "failed to read: ${e.message}"
        }
    }

    private fun logNetworkSocketSnapshotIfPossible() {
        val pkg = targetPkg ?: return
        CaptureManager.appendLog(applicationContext, "[NETWORK-SOCKET] ${networkSocketSnapshot(pkg)}")
    }

    /** Doze (device idle) and battery saver both throttle background network/jobs for apps not
     *  in the foreground, and battery saver can restrict even foreground apps on some OEM
     *  ROMs. Public PowerManager APIs, no permission needed. Useful for telling "this hang is
     *  the system throttling the app" apart from "the app's own bug". */
    private fun powerStateSnapshot(): String {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val idle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) pm.isDeviceIdleMode else false
            "deviceIdleMode=$idle powerSaveMode=${pm.isPowerSaveMode}"
        } catch (e: Exception) {
            "failed to read: ${e.message}"
        }
    }

    private fun logPowerStateSnapshot() {
        CaptureManager.appendLog(applicationContext, "[POWER] ${powerStateSnapshot()}")
    }

    /** Catches the case a real device test turned up: an app silently stuck (e.g. a coroutine
     *  awaiting something that never completes) with 0% CPU, no exception, no ANR, and no
     *  pending network socket - nothing else in this service would ever flag it, since there's
     *  no crash/ANR/LMK event to hook into. Polls every 5s once the target has been both in
     *  the foreground and pid-attached for a while with no new log line from it. Fires once
     *  per stall episode (resets on any new log line or pid change). */
    private fun startStallWatchdog(pkg: String) {
        val r = object : Runnable {
            override fun run() {
                if (!logcatRunning) return
                val now = System.currentTimeMillis()
                if (currentPid != -1 && foregroundSinceMs != 0L && !stallWarned) {
                    val sinceForeground = now - foregroundSinceMs
                    val sinceLastLine = if (lastAppLogLineMs != 0L) now - lastAppLogLineMs else sinceForeground
                    if (sinceForeground > STALL_THRESHOLD_MS && sinceLastLine > STALL_THRESHOLD_MS) {
                        stallWarned = true
                        CaptureManager.appendLog(
                            applicationContext,
                            "[STALL-SUSPECTED] pid=$currentPid has printed no new log line for ${sinceLastLine}ms " +
                                    "while in foreground for ${sinceForeground}ms — may be stuck (e.g. a blocked " +
                                    "coroutine) without crashing, ANR-ing, or logging anything"
                        )
                        CaptureManager.appendLog(applicationContext, "[MEMORY] pid=$currentPid ${memorySnapshot(currentPid)}")
                        CaptureManager.appendLog(applicationContext, "[NETWORK] ${networkSnapshot()}")
                        logNetworkSocketSnapshotIfPossible()
                        logPowerStateSnapshot()
                    }
                }
                stallHandler.postDelayed(this, 5000)
            }
        }
        stallHandler.postDelayed(r, 5000)
    }

    /** Logs how long the target app had been in the foreground before this crash/ANR — handy
     *  for spotting a pattern like "always dies ~10s after opening" (e.g. an SDK call that
     *  hangs and eventually times out). Falls back to a clear note if no foreground
     *  transition was ever observed yet for this session. */
    private fun logTimingIfEnabled() {
        if (!networkTimingEnabled) return
        val text = if (foregroundSinceMs != 0L) {
            "${System.currentTimeMillis() - foregroundSinceMs} ms since app came to foreground"
        } else {
            "foreground time unknown (no MOVED_TO_FOREGROUND usage event seen yet this session)"
        }
        CaptureManager.appendLog(applicationContext, "[TIMING] $text")
    }

    /** Android writes a full thread-dump-style stack trace per ANR under /data/anr — far more
     *  specific than the one-line "ANR in ..." summary, since it shows exactly which thread
     *  (and what it was doing) was stuck. Whether an app process can read another app's files
     *  under /data/anr is governed by plain Linux file permissions, not by READ_LOGS/DUMP, so
     *  this is genuinely untested territory — kept behind its own opt-in toggle and fails
     *  gracefully with a clear note if the device denies access. */
    private fun logAnrTraceIfAvailable() {
        val content = try {
            val dir = File("/data/anr")
            val files = dir.listFiles()
            when {
                files == null -> "Could not list /data/anr (no permission, or it doesn't exist on this device/ROM)"
                files.isEmpty() -> "No trace files found in /data/anr"
                else -> {
                    val latest = files.filter { it.isFile }.maxByOrNull { it.lastModified() }
                    val text = latest?.readText().orEmpty()
                    if (text.length > 8000) text.take(8000) + "\n...(truncated)" else text
                }
            }
        } catch (e: Exception) {
            "Failed to read ANR trace: ${e.message}"
        }
        CaptureManager.appendLog(applicationContext, "[ANR-TRACE] ----- begin /data/anr content -----")
        content.lineSequence().forEach { CaptureManager.appendLog(applicationContext, "[ANR-TRACE] $it") }
        CaptureManager.appendLog(applicationContext, "[ANR-TRACE] ----- end -----")
    }

    /** events buffer carries ActivityManager's structured am_anr/am_crash entries, which are
     *  sometimes more specific than the freeform text in main/system/crash. Runs as its own
     *  tail (separate logcat process) rather than merging into the system buffer watcher,
     *  since logcat's combined output across -b flags doesn't tag which buffer a line came
     *  from, and we want this whole feature cleanly removable via its own toggle. */
    private fun startEventsWatcher(pkg: String) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "threadtime", "-b", "events"))
            eventsWatcherProcess = process
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (!logcatRunning) return@forEachLine
                        if (line.contains(pkg, ignoreCase = true) && !looksLikeBulkListing(line)) {
                            CaptureManager.appendLog(applicationContext, "[LOGCAT-EVENTS] $line")
                        }
                    }
                } catch (e: Exception) {
                    // process destroyed/pipe closed — normal on stop
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            CaptureManager.appendLog(applicationContext, "[LOGCAT-EVENTS] Failed to start events buffer watcher: ${e.message}")
        }
    }

    /** Official OS-reported reason a process exited (crash/ANR/low-memory/signaled/etc.),
     *  straight from ActivityManager — distinguishes "crashed" from "was killed for memory"
     *  from "user swiped it away" far more reliably than guessing from logcat text alone.
     *  Querying another app's exit reasons requires android.permission.DUMP (one-time
     *  `adb shell pm grant` like READ_LOGS) — falls back to a hint if not granted. */
    private fun checkExitReason(pkg: String, pid: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getHistoricalProcessExitReasons(pkg, pid, 1).firstOrNull()
            if (info != null) {
                CaptureManager.appendLog(
                    applicationContext,
                    "[EXIT-REASON] pid=$pid reason=${exitReasonName(info.reason)} " +
                            "description=\"${info.description}\" importance=${info.importance}"
                )
            } else {
                CaptureManager.appendLog(applicationContext, "[EXIT-REASON] No exit reason record found yet for pid=$pid")
            }
        } catch (e: SecurityException) {
            CaptureManager.appendLog(
                applicationContext,
                "[EXIT-REASON] No DUMP permission — skipping official exit reason lookup. " +
                        "Grant it once via: adb shell pm grant ${applicationContext.packageName} android.permission.DUMP"
            )
        } catch (e: Exception) {
            CaptureManager.appendLog(applicationContext, "[EXIT-REASON] Failed to query exit reason: ${e.message}")
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        else -> "REASON_$reason"
    }

    private fun stopLogcatCapture() {
        logcatRunning = false
        logcatProcess?.destroy()
        logcatProcess = null
        systemWatcherProcess?.destroy()
        systemWatcherProcess = null
        lmkWatcherProcess?.destroy()
        lmkWatcherProcess = null
        eventsWatcherProcess?.destroy()
        eventsWatcherProcess = null
        memoryHandler.removeCallbacksAndMessages(null)
        stallHandler.removeCallbacksAndMessages(null)
        currentPid = -1
        foregroundSinceMs = 0L
        lastAppLogLineMs = 0L
        stallWarned = false
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title, targetPkg))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopPending)
            .build()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun overlayWindowType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    /** Red dot + "Record" text, both blinking together as one unit (blinking just toggles
     *  this whole pill's visibility). Non-touchable so it never blocks interaction with the
     *  app underneath. Dark translucent pill background keeps the red visible over any
     *  app content behind it. */
    private fun showOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dotSize = dp(14)
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
            }
        }
        val recordText = TextView(this).apply {
            text = "Record"
            setTextColor(Color.parseColor("#E53935"))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val recordRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC222222"))
                cornerRadius = dp(4).toFloat()
            }
            setPadding(dp(10), dp(6), dp(10), dp(6))
            addView(dot, android.widget.LinearLayout.LayoutParams(dotSize, dotSize).apply { marginEnd = dp(6) })
            addView(recordText)
        }

        val rowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 60
        }
        overlayView = recordRow
        windowManager?.addView(recordRow, rowParams)

        // Wait for the pill to be measured so the stop button can be placed right beside it
        // instead of guessing a fixed width for the "Record" text.
        recordRow.post {
            showStopButton(x = rowParams.x + recordRow.width + dp(8), y = rowParams.y)
        }
    }

    /** Plain white square stop button (a classic media "stop" square inside it) placed beside
     *  the Record pill, so the user can stop capturing on demand at any point — not just
     *  after a crash — e.g. when they just wanted to capture a normal session with nothing
     *  wrong. */
    private fun showStopButton(x: Int, y: Int) {
        val buttonSize = dp(40)

        val button = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setStroke(dp(1), Color.parseColor("#888888"))
            }
            isClickable = true
            setOnClickListener { stopCaptureInternal("User tapped the stop button on the floating overlay") }
        }
        val stopIcon = View(this).apply { setBackgroundColor(Color.parseColor("#444444")) }
        button.addView(
            stopIcon,
            FrameLayout.LayoutParams(dp(16), dp(16)).apply { gravity = Gravity.CENTER }
        )

        val params = WindowManager.LayoutParams(
            buttonSize, buttonSize,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        stopButtonView = button
        windowManager?.addView(button, params)
    }

    private fun startBlinking() {
        blinking = true
        val r = object : Runnable {
            override fun run() {
                overlayView?.let { it.visibility = if (it.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE }
                if (blinking) blinkHandler.postDelayed(this, 500)
            }
        }
        blinkHandler.post(r)
    }

    /** Polls UsageStatsManager for foreground/background transitions of the target package.
     *  This is the closest non-root substitute for "process state" since
     *  getRunningAppProcesses() no longer reports other apps' processes. */
    private fun startUsagePolling() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val r = object : Runnable {
            override fun run() {
                val pkg = targetPkg
                if (usm != null && pkg != null && CaptureManager.isCapturing(applicationContext)) {
                    val events: UsageEvents = usm.queryEvents(lastEventTime, System.currentTimeMillis())
                    val e = UsageEvents.Event()
                    while (events.hasNextEvent()) {
                        events.getNextEvent(e)
                        if (e.packageName == pkg) {
                            val label = usageEventLabel(e.eventType)
                            CaptureManager.appendLog(applicationContext, "[USAGE] $pkg -> $label")
                            if (label == "MOVED_TO_FOREGROUND") {
                                // Tracked unconditionally - both the timing log and the stall
                                // watchdog read this, and they're independently toggleable.
                                foregroundSinceMs = System.currentTimeMillis()
                            }
                            if (label == "MOVED_TO_BACKGROUND" || label == "ACTIVITY_STOPPED") {
                                CaptureManager.appendLog(
                                    applicationContext,
                                    "[USAGE] Target left the foreground — may have closed itself/crashed, waiting for dialog close or timeout"
                                )
                            }
                        }
                    }
                    lastEventTime = System.currentTimeMillis()
                    pollHandler.postDelayed(this, POLL_INTERVAL_MS)
                }
            }
        }
        pollHandler.postDelayed(r, POLL_INTERVAL_MS)
    }

    private fun usageEventLabel(type: Int): String = when (type) {
        UsageEvents.Event.MOVE_TO_FOREGROUND -> "MOVED_TO_FOREGROUND"
        UsageEvents.Event.MOVE_TO_BACKGROUND -> "MOVED_TO_BACKGROUND"
        UsageEvents.Event.ACTIVITY_STOPPED -> "ACTIVITY_STOPPED"
        else -> "EVENT_TYPE_$type"
    }

    private fun stopCaptureInternal(reason: String) {
        CaptureManager.stopCapturing(applicationContext, reason)
        blinking = false
        stopLogcatCapture()
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        stopButtonView?.let { windowManager?.removeView(it) }
        stopButtonView = null
        bringAppToLogViewer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Brings MoleBug back to the foreground straight to the Log Viewer screen so the user
     *  immediately sees what was captured, instead of having to find and reopen the app. */
    private fun bringAppToLogViewer() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_SHOW_LOG_VIEWER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        blinking = false
        stopLogcatCapture()
        overlayView?.let { runCatching { windowManager?.removeView(it) } }
        stopButtonView?.let { runCatching { windowManager?.removeView(it) } }
        super.onDestroy()
    }
}
