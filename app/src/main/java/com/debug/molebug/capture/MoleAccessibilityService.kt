package com.debug.molebug.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Watches window-state changes system-wide to:
 *  1. Detect when the armed target package comes to the foreground -> start capturing.
 *  2. Detect Android's "App has stopped" / ANR system dialog appearing while capturing -> log it.
 *  3. Detect the user tapping "Close app" / "ปิด" / "OK" inside that dialog -> stop capturing.
 *
 * NOTE: package names of the system crash dialog vary by OEM (e.g. "android",
 * "com.android.systemui", or a Huawei-specific package). We match generically by
 * looking for stop/ANR keywords in the dialog text instead of relying on a fixed package.
 */
class MoleAccessibilityService : AccessibilityService() {

    private val crashKeywords = listOf(
        "หยุดทำงาน", "ไม่ตอบสนอง", "has stopped", "isn't responding", "keeps stopping"
    )
    private val closeButtonKeywords = listOf(
        "ปิด", "close app", "close", "ตกลง", "ok"
    )

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val ctx = applicationContext
        val target = CaptureManager.targetPackage(ctx) ?: return
        val pkg = event.packageName?.toString() ?: return

        // 1. Target app came to foreground while armed -> start capture
        if (CaptureManager.isArmed(ctx) && pkg == target &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            CaptureManager.startCapturing(ctx)
            startCaptureService(target)
            return
        }

        if (!CaptureManager.isCapturing(ctx)) return

        // While capturing, log the target app's own window transitions
        if (pkg == target && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CaptureManager.appendLog(ctx, "[WINDOW] $target window changed: ${event.className}")
        }

        // 2. Detect crash/ANR dialog text from ANY package (system UI shows it, not the target)
        val text = collectNodeText(event)
        if (text.isNotBlank()) {
            val lower = text.lowercase()
            if (crashKeywords.any { lower.contains(it.lowercase()) }) {
                CaptureManager.appendLog(ctx, "[CRASH-DIALOG] Detected text: \"$text\" (from package=$pkg)")
            }
        }

        // 3. Detect tap on the dialog's close/ok button -> record a crash cycle (do NOT stop;
        //    the target may keep crash-looping, so capture continues until the user manually stops it)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val clickedText = event.text?.joinToString(" ")?.lowercase().orEmpty()
            if (closeButtonKeywords.any { clickedText.contains(it) }) {
                CaptureManager.appendLog(ctx, "[USER] User tapped the dialog close button (\"$clickedText\")")
                CaptureManager.recordCrashCycle(ctx)
            }
        }
    }

    private fun collectNodeText(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        event.text?.forEach { sb.append(it).append(" ") }
        val src: AccessibilityNodeInfo? = event.source
        src?.let { node ->
            collectFromNode(node, sb)
        }
        return sb.toString().trim()
    }

    private fun collectFromNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int = 0) {
        if (depth > 6) return
        node.text?.let { sb.append(it).append(" ") }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectFromNode(it, sb, depth + 1) }
        }
    }

    private fun startCaptureService(target: String) {
        val intent = Intent(this, CaptureService::class.java).apply {
            action = CaptureService.ACTION_START
            putExtra(CaptureService.EXTRA_TARGET_PKG, target)
        }
        startForegroundService(intent)
    }

    override fun onInterrupt() {}
}
