package com.appsventory.digiapps.nemo.update

import com.appsventory.digiapps.nemo.BuildConfig

/**
 * Single source of truth for the GitHub repository used by the update checker.
 * Values come from BuildConfig so debug builds hit Digi-TvBrowser for testing while
 * release builds always point at Digi-Files. UI and business logic never hard-code URLs.
 */
object ReleaseConfig {
    val owner: String get() = BuildConfig.UPDATE_GITHUB_OWNER
    val repo: String get() = BuildConfig.UPDATE_GITHUB_REPO

    /** Official GitHub REST API endpoint for the latest published (non-draft) release. */
    fun latestReleaseUrl(): String =
        "https://api.github.com/repos/$owner/$repo/releases/latest"
}
