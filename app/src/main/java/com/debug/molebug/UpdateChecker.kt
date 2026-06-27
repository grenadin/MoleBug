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
     *  was found in it. GmsCore ships separate "-hw" (Huawei/no-Google-Play-Services devices)
     *  and plain asset variants per release — pass null for repos with no per-device variant
     *  (e.g. GsfProxy, which only ever publishes a single universal apk). Returns null on any
     *  network/parse failure — callers treat null as "couldn't check right now". */
    suspend fun fetchLatestGithubRelease(
        owner: String,
        repo: String,
        requireAssetContains: String? = null
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
            val hasRequiredAsset = requireAssetContains == null || (0 until assets.length()).any { i ->
                assets.getJSONObject(i).optString("name").contains(requireAssetContains, ignoreCase = true)
            }
            GithubReleaseInfo(tag, hasRequiredAsset)
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

data class GithubReleaseInfo(val tagName: String, val hasRequiredAsset: Boolean)

/** Where to check a package's latest community release from. [requireAssetContains] (GitHub
 *  only) confirms the Huawei-specific build is actually present in that release — GmsCore's
 *  releases bundle both "-hw" (Huawei, no Google Play Services) and plain asset variants, and
 *  MoleBug only ever points the user at the "-hw" ones, so the check needs to confirm that
 *  exact variant exists rather than just reporting whatever version the release as a whole is. */
sealed class UpdateCheckTarget {
    data class GitHub(val owner: String, val repo: String, val requireAssetContains: String? = null) : UpdateCheckTarget()
    data class GitLab(val projectId: String) : UpdateCheckTarget()
}

val UPDATE_CHECK_TARGETS: Map<String, UpdateCheckTarget> = mapOf(
    "com.google.android.gms" to UpdateCheckTarget.GitHub("microg", "GmsCore", requireAssetContains = "-hw.apk"),
    "com.android.vending" to UpdateCheckTarget.GitHub("microg", "GmsCore", requireAssetContains = "-hw.apk"),
    // GsfProxy only ever ships one universal apk — no per-device variant to confirm.
    "com.google.android.gsf" to UpdateCheckTarget.GitHub("microg", "GsfProxy"),
    // auroraoss.com/files publishes a "-hw" build for every GitLab-tagged release 1:1 (confirmed
    // against the GitLab release history), so the tag itself is a reliable stand-in here.
    "com.aurora.store" to UpdateCheckTarget.GitLab("6922885") // gitlab.com/AuroraOSS/AuroraStore
)

sealed class UpdateCheckResult {
    object Loading : UpdateCheckResult()
    data class Checked(val installed: String?, val latest: String?) : UpdateCheckResult()
    object Failed : UpdateCheckResult()
    /** Latest release fetched fine, but it doesn't contain the Huawei-specific asset MoleBug
     *  requires — so reporting its version would imply a Huawei build that isn't actually there. */
    object NoHuaweiBuild : UpdateCheckResult()
}

/** Best-effort "is this a different version" check — GitHub/GitLab tag formatting won't always
 *  match an installed app's versionName exactly (e.g. a leading "v"), so this only strips that
 *  and compares trimmed strings. False positives are possible; callers show both raw values
 *  alongside the badge rather than asserting a confident yes/no. */
fun hasUpdateAvailable(installed: String?, latest: String?): Boolean {
    if (installed == null || latest == null) return false
    fun normalize(v: String) = v.removePrefix("v").removePrefix("V").trim()
    return normalize(installed) != normalize(latest)
}
