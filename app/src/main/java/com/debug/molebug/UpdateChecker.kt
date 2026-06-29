package com.debug.molebug

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** On-demand (never automatic) lookups against public release APIs, used by the Basic Apps
 *  section's "Check for updates" button. No extra HTTP/JSON library — just HttpURLConnection
 *  and the SDK-bundled org.json, matching this project's zero-extra-dependency style. */
object UpdateChecker {
    private const val TIMEOUT_MS = 8000

    /** Returns the GitHub release tag (e.g. "v0.3.15.250932") for the repo's latest release,
     *  plus whether an asset matching [requireAssetContains] (e.g. "-hw.apk", case-insensitive)
     *  AND starting with [assetPrefix] (e.g. "com.android.vending") was found in it. A single
     *  GmsCore release bundles "-hw" assets for *multiple* packages at once (one for
     *  com.google.android.gms, a separate one for com.android.vending) — matching on
     *  [requireAssetContains] alone would happily match either one for either package, since
     *  both filenames contain "-hw.apk". [assetPrefix] pins the match to the right package's
     *  own asset specifically. Pass null for repos with no per-device variant at all (e.g.
     *  GsfProxy, which only ever publishes a single universal apk). Returns null on any
     *  network/parse failure — callers treat null as "couldn't check right now". */
    suspend fun fetchLatestGithubRelease(
        owner: String,
        repo: String,
        requireAssetContains: String? = null,
        assetPrefix: String? = null
    ): GithubReleaseInfo? = withContext(Dispatchers.IO) {
        val connection = URL("https://api.github.com/repos/$owner/$repo/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            if (connection.responseCode != 200) return@withContext null
            val body = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tag = json.optString("tag_name").takeIf { it.isNotBlank() } ?: return@withContext null
            val assets = json.optJSONArray("assets") ?: JSONArray()
            val matchedAssetName = requireAssetContains?.let { needle ->
                (0 until assets.length())
                    .map { assets.getJSONObject(it).optString("name") }
                    .firstOrNull {
                        it.contains(needle, ignoreCase = true) &&
                            (assetPrefix == null || it.startsWith(assetPrefix, ignoreCase = true))
                    }
            }
            val hasRequiredAsset = requireAssetContains == null || matchedAssetName != null
            // The asset filename itself carries the exact versionCode for that specific flavor
            // (e.g. "com.google.android.gms-250932030-hw.apk") — read straight out of the
            // GitHub API's JSON response (a plain HTTP/JSON call, no DOM/JS scraping needed),
            // so comparing against the installed versionCode is exact instead of guessing at
            // string suffixes.
            val matchedAssetVersionCode = matchedAssetName
                ?.let { Regex("""-(\d+)-hw\.apk$""", RegexOption.IGNORE_CASE).find(it) }
                ?.groupValues?.getOrNull(1)?.toLongOrNull()
            GithubReleaseInfo(tag, hasRequiredAsset, matchedAssetVersionCode)
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    /** Returns the newest release's tag name from a public GitLab project's Releases API
     *  (the list is already newest-first), or null on any failure. */
    suspend fun fetchLatestGitlabTag(projectId: String): String? = withContext(Dispatchers.IO) {
        val connection = URL("https://gitlab.com/api/v4/projects/$projectId/releases")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = "GET"
            if (connection.responseCode != 200) return@withContext null
            val body = connection.inputStream.bufferedReader().readText()
            val releases = JSONArray(body)
            if (releases.length() == 0) return@withContext null
            releases.getJSONObject(0).optString("tag_name").takeIf { tag -> tag.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

data class GithubReleaseInfo(
    val tagName: String,
    val hasRequiredAsset: Boolean,
    val matchedAssetVersionCode: Long? = null
)

/** GmsCore's "huawei" product flavor appends versionNameSuffix "-hw" at compile time
 *  (confirmed in play-services-core/build.gradle's productFlavors block) — the exact same
 *  suffix the release filenames are derived from. So the installed versionName itself already
 *  tells us which flavor is installed; no network call or file-size comparison needed. Only
 *  meaningful for packages that actually ship a Huawei flavor (gms/vending) — see
 *  [HUAWEI_BUILD_REQUIRED_PACKAGES]. */
fun isHuaweiFlavor(versionName: String?): Boolean = versionName?.contains("-hw", ignoreCase = true) == true

val HUAWEI_BUILD_REQUIRED_PACKAGES: Set<String> = setOf("com.google.android.gms", "com.android.vending")

/** Where to check a package's latest community release from. [requireAssetContains] (GitHub
 *  only) confirms the Huawei-specific build is actually present in that release — GmsCore's
 *  releases bundle both "-hw" (Huawei, no Google Play Services) and plain asset variants, and
 *  MoleBug only ever points the user at the "-hw" ones, so the check needs to confirm that
 *  exact variant exists rather than just reporting whatever version the release as a whole is. */
sealed class UpdateCheckTarget {
    data class GitHub(
        val owner: String,
        val repo: String,
        val requireAssetContains: String? = null,
        val assetPrefix: String? = null
    ) : UpdateCheckTarget()
    data class GitLab(val projectId: String) : UpdateCheckTarget()
}

val UPDATE_CHECK_TARGETS: Map<String, UpdateCheckTarget> = mapOf(
    "com.google.android.gms" to UpdateCheckTarget.GitHub(
        "microg", "GmsCore", requireAssetContains = "-hw.apk", assetPrefix = "com.google.android.gms"
    ),
    "com.android.vending" to UpdateCheckTarget.GitHub(
        "microg", "GmsCore", requireAssetContains = "-hw.apk", assetPrefix = "com.android.vending"
    ),
    // GsfProxy only ever ships one universal apk — no per-device variant to confirm.
    "com.google.android.gsf" to UpdateCheckTarget.GitHub("microg", "GsfProxy"),
    // auroraoss.com/files publishes a "-hw" build for every GitLab-tagged release 1:1 (confirmed
    // against the GitLab release history), so the tag itself is a reliable stand-in here.
    "com.aurora.store" to UpdateCheckTarget.GitLab("6922885") // gitlab.com/AuroraOSS/AuroraStore
)

sealed class UpdateCheckResult {
    object Loading : UpdateCheckResult()
    /** [updateAvailable] is decided once at fetch time by whichever comparison actually fits
     *  the target (exact versionCode-vs-asset-filename for gms/vending, tag-vs-versionName
     *  string compare otherwise) — the UI just reads it, instead of re-deriving it from
     *  [installed]/[latest] display strings whose format differs per target. */
    data class Checked(val installed: String?, val latest: String?, val updateAvailable: Boolean) : UpdateCheckResult()
    object Failed : UpdateCheckResult()
    /** Latest release fetched fine, but it doesn't contain the Huawei-specific asset MoleBug
     *  requires — so reporting its version would imply a Huawei build that isn't actually there. */
    object NoHuaweiBuild : UpdateCheckResult()
}

/** Exact comparison for packages where the GitHub release asset's own filename carries the
 *  real versionCode for that specific flavor (see [GithubReleaseInfo.matchedAssetVersionCode])
 *  — no string-suffix guessing needed at all. */
fun hasUpdateAvailable(installedVersionCode: Long?, latestVersionCode: Long?): Boolean {
    if (installedVersionCode == null || latestVersionCode == null) return false
    return installedVersionCode < latestVersionCode
}

/** Fallback for targets with no per-flavor asset filename to parse a versionCode out of
 *  (GsfProxy's single universal apk, Aurora Store's GitLab tag) — best-effort string compare,
 *  only stripping a leading "v". False positives are possible; callers show both raw values
 *  alongside the badge rather than asserting a confident yes/no. */
fun hasUpdateAvailable(installed: String?, latest: String?): Boolean {
    if (installed == null || latest == null) return false
    fun normalize(v: String) = v.removePrefix("v").removePrefix("V").trim()
    return normalize(installed) != normalize(latest)
}
