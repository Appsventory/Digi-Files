package com.appsventory.digiapps.nemo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleaseApiTest {

    private val sampleJson = """
        {
          "tag_name": "v2.1.0",
          "name": "DigiBrowser v2.1.0 — Navigation & UI Improvements",
          "body": "Release notes here.",
          "published_at": "2026-10-10T18:59:16Z",
          "html_url": "https://github.com/Appsventory/Digi-TvBrowser/releases/tag/v2.1.0",
          "prerelease": false,
          "draft": false,
          "assets": [
            {
              "name": "DigiBrowser-v2.1.0-universal-debug.apk",
              "browser_download_url": "https://example.com/debug.apk",
              "content_type": "application/vnd.android.package-archive",
              "size": 24676122,
              "label": null
            },
            {
              "name": "DigiBrowser-v2.1.0-universal-release.aab",
              "browser_download_url": "https://example.com/release.aab",
              "content_type": "application/octet-stream",
              "size": 6335209,
              "label": null
            },
            {
              "name": "DigiBrowser-v2.1.0-universal-release.apk",
              "browser_download_url": "https://example.com/release.apk",
              "content_type": "application/vnd.android.package-archive",
              "size": 2980752,
              "label": null
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parse_success() {
        val result = GitHubReleaseApi.parseRelease(sampleJson)
        assertTrue(result is GitHubReleaseApi.Result.Success)
        val release = (result as GitHubReleaseApi.Result.Success).release
        assertEquals("v2.1.0", release.tagName)
        assertEquals(3, release.assets.size)
        assertFalse(release.draft)
        assertFalse(release.prerelease)
    }

    @Test
    fun suitableAssets_listsAllApksReleaseFirst() {
        val result = GitHubReleaseApi.parseRelease(sampleJson) as GitHubReleaseApi.Result.Success
        val suitable = suitableDownloadAssets(result.release.assets)
        // debug + release APKs; AAB excluded. Release sorted first.
        assertEquals(2, suitable.size)
        assertEquals("DigiBrowser-v2.1.0-universal-release.apk", suitable.first().name)
        assertFalse(suitable.first().isDebugBuild)
        assertTrue(suitable[1].isDebugBuild)
    }

    @Test
    fun suitableAssets_fallsBackToDebugWhenOnlyDebug() {
        val onlyDebug = """
            {
              "tag_name": "v1.0.0",
              "name": "Test",
              "body": null,
              "published_at": "2026-01-01T00:00:00Z",
              "html_url": "https://example.com",
              "prerelease": false,
              "draft": false,
              "assets": [
                {
                  "name": "app-debug.apk",
                  "browser_download_url": "https://example.com/debug.apk",
                  "content_type": "application/vnd.android.package-archive",
                  "size": 1000,
                  "label": "debug"
                }
              ]
            }
        """.trimIndent()
        val release = (GitHubReleaseApi.parseRelease(onlyDebug) as GitHubReleaseApi.Result.Success).release
        val suitable = suitableDownloadAssets(release.assets)
        assertEquals(1, suitable.size)
        assertTrue(suitable.first().isDebugBuild)
    }

    @Test
    fun suitableAssets_emptyWhenNoApk() {
        val noApk = """
            {
              "tag_name": "v1.0.0",
              "name": "Test",
              "body": null,
              "published_at": "2026-01-01T00:00:00Z",
              "html_url": "https://example.com",
              "prerelease": false,
              "draft": false,
              "assets": [
                {
                  "name": "source.zip",
                  "browser_download_url": "https://example.com/source.zip",
                  "content_type": "application/zip",
                  "size": 100,
                  "label": null
                }
              ]
            }
        """.trimIndent()
        val release = (GitHubReleaseApi.parseRelease(noApk) as GitHubReleaseApi.Result.Success).release
        assertTrue(suitableDownloadAssets(release.assets).isEmpty())
    }

    @Test
    fun parse_malformed() {
        val result = GitHubReleaseApi.parseRelease("{not json")
        assertTrue(result is GitHubReleaseApi.Result.ParseError)
    }

    @Test
    fun parse_emptyAssets() {
        val empty = """
            {
              "tag_name": "v1.0.0",
              "name": "Empty",
              "body": "",
              "published_at": "2026-01-01T00:00:00Z",
              "html_url": "https://example.com",
              "prerelease": false,
              "draft": false,
              "assets": []
            }
        """.trimIndent()
        val result = GitHubReleaseApi.parseRelease(empty)
        assertTrue(result is GitHubReleaseApi.Result.Success)
        assertTrue((result as GitHubReleaseApi.Result.Success).release.assets.isEmpty())
    }
}
