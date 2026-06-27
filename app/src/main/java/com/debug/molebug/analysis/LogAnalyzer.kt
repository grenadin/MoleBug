package com.debug.molebug.analysis

// The Diagnostic Summary screen now renders every matching line inside a scrollable area
// (virtualized, so it stays cheap to render), so this only needs to bound memory for a
// pathologically noisy bucket — not UI cost. Effectively "keep everything" for real-world logs.
private const val MAX_SAMPLE_LINES = 5000
private const val OTHER_CATEGORY = "Other"

data class DetailBucket(val label: String, val count: Int, val sampleLines: List<String>)
data class SdkBucket(
    val id: String,
    val category: String,
    val company: String,
    val sdk: String,
    val count: Int,
    val details: List<DetailBucket>
)
data class CategoryBucket(val category: String, val count: Int, val sdks: List<SdkBucket>)

/** One plain-language finding for the screen's quick-assessment card — either a recognized
 *  protection/anti-tamper SDK that was active (a likely cause of the target app blocking
 *  itself) or a crash/ANR signal, each with how many times it showed up. */
data class IssueFinding(val label: String, val count: Int)

/** Pulls the logcat tag out of a captured line (format: "... MM-DD HH:MM:SS.mmm PID TID LEVEL TAG: message"),
 *  used to classify lines that don't match any known [SdkSignatureDb] entry so nothing captured gets
 *  silently dropped from the breakdown — it just lands in the "Other" category, split out by tag. */
private val logcatTagRegex = Regex("""\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+\s+\d+\s+\d+\s+[VDIWEF]\s+([^:]+):""")

/** Many captured lines — regardless of SDK/tag — carry a bracketed marker (e.g. "[GRANTED]",
 *  "[WINDOW]", "[USAGE]") that identifies what kind of event it is. Grouping detail buckets by
 *  that marker instead of by raw line text is what keeps every noisy SDK/tag (not just
 *  "Unrecognized") from fanning out into one Card per distinct message. */
private val bracketMarkerRegex = Regex("""\[([A-Z]{3,})\]""")
private const val UNRECOGNIZED_SDK = "Unrecognized"
private const val GENERIC_DETAIL_LABEL = "Other"

/** detailMatcher hits (e.g. a syscall ID) win when present; otherwise group by bracket marker;
 *  otherwise everything with no identifiable marker lands in one generic bucket per SDK instead
 *  of fanning out into a Card per distinct message. */
private fun fallbackDetailLabel(line: String): String =
    bracketMarkerRegex.find(line)?.value ?: GENERIC_DETAIL_LABEL

/** Crashes/ANRs don't carry a curated SDK signature, so they're detected directly against the
 *  raw line instead of going through [SdkSignatureDb] — these are always worth flagging in the
 *  quick-assessment card regardless of which category/tag they ended up bucketed under. */
private val crashRegex = Regex("""FATAL EXCEPTION|ANR in|Process .+ has died|AndroidRuntime: FATAL""")

private const val RESPONSIVENESS_CATEGORY = "Responsiveness"
private val unresponsiveTouchMarker = Regex("""\[UNRESPONSIVE-TOUCH\].*detected""")

/** Keyword -> plain-language cause, checked against a window of lines just before each
 *  [UNRESPONSIVE-TOUCH] hit. Each entry must be a genuinely install/install-adjacent signal —
 *  NOT a generic activity-lifecycle line (e.g. plain "wm_resume_activity"/"wm_destroy_activity"
 *  on their own happen on basically every screen change, in every app, regardless of whether
 *  anything is actually wrong — including those here would make the analysis falsely lean
 *  toward "an activity switched" for every target app, every time, which isn't a real cause).
 *  This list is intentionally generic across apps (not Aurora-specific) so a different target
 *  app's freeze gets judged on what's actually in *its* log, not assumed to be the same cause
 *  as a previous, unrelated app's. If none of these match, the finding says so plainly instead
 *  of guessing. */
private val unresponsiveTouchCauseHints = listOf(
    "MicroGInstallerActivity" to "ติดตั้ง/อัปเดตแอปผ่าน microG Installer",
    "PackageInstaller" to "กระบวนการติดตั้งแอป (Package Installer)",
    "PACKAGE_ADDED" to "มีการติดตั้ง/อัปเดตแอป (PACKAGE_ADDED)",
    "INSTALL_PACKAGE" to "กระบวนการติดตั้งแอป (INSTALL_PACKAGE)",
    "PackageInstallerSession" to "กระบวนการติดตั้งแอปแบบ session-based"
)
private const val UNRESPONSIVE_TOUCH_LOOKBACK_LINES = 60

/**
 * Classifies every captured log line — known protection/SDK signatures from [SdkSignatureDb]
 * land in their own category/company/SDK; anything else still counts towards the total, grouped
 * into an "Other" category by logcat tag — so the top-level bar always reflects 100% of what was
 * actually captured, not just the lines that matched a curated signature.
 */
object LogAnalyzer {
    fun analyze(lines: List<String>): List<CategoryBucket> {
        data class DetailAccum(var count: Int = 0, val sampleLines: MutableList<String> = mutableListOf())
        data class BucketAccum(
            val category: String,
            val company: String,
            val sdk: String,
            var count: Int = 0,
            val details: LinkedHashMap<String, DetailAccum> = LinkedHashMap()
        )

        val byKey = LinkedHashMap<String, BucketAccum>()

        for (line in lines) {
            if (line.isBlank()) continue

            val signature = SdkSignatureDb.signatures.firstOrNull { it.lineMatcher.containsMatchIn(line) }
            val category: String
            val company: String
            val sdk: String
            val detailLabel: String

            if (signature != null) {
                category = signature.category
                company = signature.company
                sdk = signature.sdk
                detailLabel = signature.detailMatcher?.find(line)?.groupValues?.getOrNull(1)
                    ?: fallbackDetailLabel(line)
            } else {
                category = OTHER_CATEGORY
                company = ""
                val tag = logcatTagRegex.find(line)?.groupValues?.getOrNull(1)?.trim()
                sdk = tag ?: UNRECOGNIZED_SDK
                detailLabel = fallbackDetailLabel(line)
            }

            val key = "$category|$company|$sdk"
            val bucketAccum = byKey.getOrPut(key) { BucketAccum(category, company, sdk) }
            bucketAccum.count++

            val detailAccum = bucketAccum.details.getOrPut(detailLabel) { DetailAccum() }
            detailAccum.count++
            if (detailAccum.sampleLines.size < MAX_SAMPLE_LINES) detailAccum.sampleLines.add(line)
        }

        val sdkBuckets = byKey.map { (key, accum) ->
            SdkBucket(
                id = key,
                category = accum.category,
                company = accum.company,
                sdk = accum.sdk,
                count = accum.count,
                details = accum.details.map { (label, d) -> DetailBucket(label, d.count, d.sampleLines) }
                    .sortedByDescending { it.count }
            )
        }

        return sdkBuckets.groupBy { it.category }
            .map { (category, sdks) ->
                CategoryBucket(category, sdks.sumOf { it.count }, sdks.sortedByDescending { it.count })
            }
            .sortedByDescending { it.count }
    }

    /** Quick plain-language read of [categories]/[lines] for the assessment card: any category
     *  other than "Other" is, by construction, a recognized protection/anti-tamper SDK — exactly
     *  the kind of thing known to make a target app block itself — so every one of those is
     *  reported as a finding, plus a direct crash/ANR scan that isn't tied to SDK detection at all. */
    fun findIssues(categories: List<CategoryBucket>, lines: List<String>): List<IssueFinding> {
        val findings = mutableListOf<IssueFinding>()
        // Responsiveness gets its own richer finding (with root-cause analysis) below instead
        // of the generic per-SDK line every other non-"Other" category gets.
        categories.filter { it.category != OTHER_CATEGORY && it.category != RESPONSIVENESS_CATEGORY }.forEach { cat ->
            cat.sdks.forEach { sdk ->
                val label = if (sdk.company.isBlank()) sdk.sdk else "${sdk.company} ${sdk.sdk}"
                findings.add(IssueFinding("$label (${cat.category})", sdk.count))
            }
        }
        val crashCount = lines.count { crashRegex.containsMatchIn(it) }
        if (crashCount > 0) findings.add(IssueFinding("App crash / ANR", crashCount))

        // Each occurrence gets judged on its own preceding window — not unioned into one set
        // across every hit — so a freeze with no install activity nearby doesn't get lumped in
        // with (and misattributed to) a different occurrence that genuinely had one.
        val unresponsiveIndices = lines.indices.filter { unresponsiveTouchMarker.containsMatchIn(lines[it]) }
        if (unresponsiveIndices.isNotEmpty()) {
            val causePerOccurrence = unresponsiveIndices.map { idx ->
                val window = lines.subList((idx - UNRESPONSIVE_TOUCH_LOOKBACK_LINES).coerceAtLeast(0), idx)
                val matched = unresponsiveTouchCauseHints.filter { (keyword, _) -> window.any { it.contains(keyword) } }
                if (matched.isEmpty()) {
                    "ไม่สามารถระบุสาเหตุที่แน่ชัดจาก log ได้"
                } else {
                    matched.joinToString("; ") { it.second }
                }
            }
            causePerOccurrence.groupingBy { it }.eachCount().forEach { (cause, count) ->
                findings.add(IssueFinding("พบอาการไม่ตอบสนองของการสัมผัสหน้าจอ — สาเหตุที่เป็นไปได้: $cause", count))
            }
        }

        return findings.sortedByDescending { it.count }
    }
}
