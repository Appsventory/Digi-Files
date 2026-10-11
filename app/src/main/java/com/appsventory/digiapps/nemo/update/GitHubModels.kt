package com.appsventory.digiapps.nemo.update

/**
 * Subset of the GitHub Releases API payload that the update flow needs.
 * Field names match the JSON keys from https://docs.github.com/en/rest/releases/releases
 */
data class GitHubRelease(
    val tagName: String,
    val name: String?,
    val body: String?,
    val publishedAt: String?,
    val htmlUrl: String?,
    val prerelease: Boolean,
    val draft: Boolean,
    val assets: List<GitHubAsset>
)

data class GitHubAsset(
    val name: String,
    val browserDownloadUrl: String,
    val contentType: String?,
    val size: Long,
    val label: String?
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase()

    val isApk: Boolean
        get() = extension == "apk" ||
            contentType?.contains("android.package-archive", ignoreCase = true) == true

    val isAab: Boolean
        get() = extension == "aab"

    val isDebugBuild: Boolean
        get() = name.contains("debug", ignoreCase = true) ||
            (label?.contains("debug", ignoreCase = true) == true)

    val isReleaseBuild: Boolean
        get() = !isDebugBuild && isApk
}

/**
 * All installable APK assets with a download URL.
 * Release builds are listed before debug builds so a single-asset auto-download
 * prefers a release APK, and a multi-asset picker still shows every choice.
 * AABs and source archives are excluded (not side-loadable as packages).
 */
fun suitableDownloadAssets(assets: List<GitHubAsset>): List<GitHubAsset> {
    val apks = assets.filter { it.isApk && it.browserDownloadUrl.isNotBlank() }
    if (apks.isEmpty()) return emptyList()
    return apks.sortedBy { if (it.isReleaseBuild) 0 else 1 }
}
