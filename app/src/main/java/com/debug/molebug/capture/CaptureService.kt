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
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import com.debug.molebug.MainActivity
import com.debug.molebug.R

class CaptureService : Service() {

    companion object {
        const val ACTION_START = "com.debug.molebug.ACTION_START"
        const val ACTION_STOP = "com.debug.molebug.ACTION_STOP"
        const val EXTRA_TARGET_PKG = "target_pkg"
        private const val CHANNEL_ID = "molebug_capture"
        private const val NOTI_ID = 1001
        private const val POLL_INTERVAL_MS = 1000L
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var stopButtonView: View? = null
    private var blinking = false
    private val blinkHandler = Handler(Looper.getMainLooper())
    private val pollHandler = Handler(Looper.getMainLooper())
    private var targetPkg: String? = null
    private var lastEventTime = System.currentTimeMillis()

    private var logcatThread: Thread? = null
    @Volatile private var logcatRunning = false
    private var logcatProcess: Process? = null
    private var anrWatcherProcess: Process? = null
    private var lmkWatcherProcess: Process? = null
    private var currentPid = -1
    private val memoryHandler = Handler(Looper.getMainLooper())

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

    /** Continuously finds the target package's current PID, attaches `logcat --pid=PID`,
     *  and streams real log lines (including stack traces) into the capture file. If the
     *  target crashes and Android restarts its process with a new PID, this loop detects
     *  the PID change and re-attaches automatically — so crash loops are followed seamlessly. */
    private fun startLogcatCapture() {
        logcatRunning = true
        val pkg = targetPkg ?: return
        dumpLogcatBacklog(pkg)
        startAnrWatcher(pkg)
        startLmkWatcher(pkg)
        startMemoryPolling()
        logcatThread = Thread {
            var lastPid = -1
            while (logcatRunning) {
                val pid = findPid(pkg)
                if (pid != lastPid) {
                    // pid changed (new launch, crash-restart, or process died with nothing
                    // new yet) -> look up the official exit reason for whatever pid just left
                    if (lastPid != -1) checkExitReason(pkg, lastPid)
                    if (pid != null) {
                        logcatProcess?.destroy()
                        CaptureManager.appendLog(applicationContext, "[LOGCAT] attach pid=$pid ($pkg)")
                        attachLogcatForPid(pid)
                    } else {
                        CaptureManager.appendLog(applicationContext, "[LOGCAT] pid $lastPid disappeared (process died/restarted) — waiting for new pid")
                        logcatProcess?.destroy()
                        logcatProcess = null
                    }
                    currentPid = pid ?: -1
                    lastPid = currentPid
                }
                Thread.sleep(500)
            }
            logcatProcess?.destroy()
        }
        logcatThread?.isDaemon = true
        logcatThread?.start()
    }

    /** One-shot dump of whatever is already sitting in the log buffers (main/system/crash)
     *  before we ever attach to a pid, filtered down to lines mentioning the target package.
     *  Covers crashes that happen so fast the pid-watch loop never catches a live pid for them. */
    private fun dumpLogcatBacklog(pkg: String) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", "-b", "main", "-b", "system", "-b", "crash")
            )
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            val relevant = lines.filter { it.contains(pkg, ignoreCase = true) }
            if (relevant.isNotEmpty()) {
                CaptureManager.appendLog(applicationContext, "[LOGCAT-HISTORY] ${relevant.size} buffered line(s) already mentioning $pkg before capture started:")
                relevant.forEach { CaptureManager.appendLog(applicationContext, "[LOGCAT-HISTORY] $it") }
            }
        } catch (e: Exception) {
            CaptureManager.appendLog(applicationContext, "[LOGCAT-HISTORY] Failed to dump backlog: ${e.message}")
        }
    }

    private fun findPid(pkg: String): Int? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", "pidof $pkg"))
            val out = p.inputStream.bufferedReader().readLine()
            p.waitFor()
            out?.trim()?.split(" ")?.firstOrNull()?.toIntOrNull()
        } catch (e: Exception) {
            null
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
                        if (line.contains("FATAL EXCEPTION") || line.contains("AndroidRuntime")) {
                            CaptureManager.appendLog(applicationContext, "[LOGCAT-CRASH] Found a real crash stack trace, see lines above")
                            CaptureManager.appendLog(applicationContext, "[NETWORK] ${networkSnapshot()}")
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

    /** ANR entries are logged by system_server (ActivityManager), under system_server's own
     *  pid — never the target app's pid — so `--pid` filtering on the target's pid can never
     *  catch them. Instead tail the unfiltered `system` buffer for the whole capture session
     *  and match by package name + ANR keywords in the text itself. */
    private fun startAnrWatcher(pkg: String) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "threadtime", "-b", "system")
            )
            anrWatcherProcess = process
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        if (!logcatRunning) return@forEachLine
                        if (line.contains(pkg, ignoreCase = true) && anrKeywords.any { line.contains(it) }) {
                            CaptureManager.appendLog(applicationContext, "[LOGCAT-ANR] $line")
                            CaptureManager.appendLog(applicationContext, "[LOGCAT-ANR] Detected an ANR (app not responding) for $pkg")
                            CaptureManager.appendLog(applicationContext, "[NETWORK] ${networkSnapshot()}")
                        }
                    }
                } catch (e: Exception) {
                    // process destroyed/pipe closed — normal on stop
                }
            }.apply { isDaemon = true }.start()
        } catch (e: Exception) {
            CaptureManager.appendLog(applicationContext, "[LOGCAT-ANR] Failed to start ANR watcher: ${e.message}")
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
        anrWatcherProcess?.destroy()
        anrWatcherProcess = null
        lmkWatcherProcess?.destroy()
        lmkWatcherProcess = null
        memoryHandler.removeCallbacksAndMessages(null)
        currentPid = -1
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

    /** Solid red circle (recording dot, like a video-record indicator) that blinks while
     *  capturing. Non-touchable so it never blocks interaction with the app underneath. */
    private fun showOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val dotSize = dp(24)
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
            }
        }
        val dotParams = WindowManager.LayoutParams(
            dotSize, dotSize,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 60
        }
        overlayView = dot
        windowManager?.addView(dot, dotParams)

        showStopButton(belowY = dotParams.y + dotSize + dp(8))
    }

    /** Red floating "pause" button next to the record dot, so the user can stop capturing
     *  on demand at any point — not just after a crash — e.g. when they just wanted to
     *  capture a normal session with nothing wrong. */
    private fun showStopButton(belowY: Int) {
        val buttonSize = dp(40)
        val barWidth = dp(5)
        val barHeight = dp(16)
        val barGap = dp(6)

        val button = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
            }
            isClickable = true
            setOnClickListener { stopCaptureInternal("User tapped the stop button on the floating overlay") }
        }
        val barRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        repeat(2) { index ->
            val bar = View(this).apply { setBackgroundColor(Color.WHITE) }
            val barLp = android.widget.LinearLayout.LayoutParams(barWidth, barHeight)
            if (index == 0) barLp.marginEnd = barGap
            barRow.addView(bar, barLp)
        }
        button.addView(
            barRow,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
        )

        val params = WindowManager.LayoutParams(
            buttonSize, buttonSize,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = belowY
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
