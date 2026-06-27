package com.debug.molebug.analysis

/**
 * One recognizable protection/anti-tamper SDK signature found in a captured log.
 *
 * To add a new SDK once you've captured real evidence of it in a log: append one
 * [SdkSignature] entry below. [lineMatcher] decides whether a log line belongs to
 * this SDK; [detailMatcher] (optional) pulls a sub-classifier out of the line —
 * e.g. a syscall ID — so the Diagnostic Summary screen has something to drill into
 * below the SDK level. Entries are checked in order; the first match wins.
 */
data class SdkSignature(
    val id: String,
    val category: String,
    val company: String,
    val sdk: String,
    val lineMatcher: Regex,
    val detailMatcher: Regex? = null
)

object SdkSignatureDb {
    val signatures: List<SdkSignature> = listOf(
        SdkSignature(
            id = "vkey-vos",
            category = "Protection",
            company = "V-Key",
            sdk = "V-OS",
            lineMatcher = Regex("""V-OS\.debug"""),
            detailMatcher = Regex("""Unknown syscall ID (0x[0-9a-fA-F]+)""")
        ),
        SdkSignature(
            id = "google-play-integrity",
            category = "Integrity",
            company = "Google",
            sdk = "Play Integrity API",
            lineMatcher = Regex("""IntegrityService|finsky\.integrityservice""")
        ),
        // MoleBug's own synthetic diagnostic tag (not a third-party SDK) — added here so it
        // gets its own bar/category in Diagnostic Summary instead of disappearing into the
        // generic "Unrecognized" bucket inside "Other". LogAnalyzer.findIssues() gives this
        // category special handling (root-cause analysis) instead of the generic per-SDK line.
        SdkSignature(
            id = "molebug-touch-watchdog",
            category = "Responsiveness",
            company = "MoleBug",
            sdk = "Touch Watchdog",
            lineMatcher = Regex("""\[UNRESPONSIVE-TOUCH\]"""),
            detailMatcher = Regex("""detected (\d+ms) ago""")
        )
    )
}
