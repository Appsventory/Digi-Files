package com.appsventory.digiapps.nemo.update

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Fetches the latest published release from the GitHub REST API.
 * Uses the public unauthenticated endpoint; no token is required for public repos.
 * Draft releases are never returned by /releases/latest.
 */
object GitHubReleaseApi {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val USER_AGENT = "DigiFiles-UpdateChecker"

    sealed class Result {
        data class Success(val release: GitHubRelease) : Result()
        /** HTTP 404 from /releases/latest means the repository has no published releases. */
        object NoReleases : Result()
        data class HttpError(val code: Int, val message: String) : Result()
        data class NetworkError(val cause: Throwable) : Result()
        data class ParseError(val cause: Throwable) : Result()
    }

    /**
     * Blocking call — invoke from Dispatchers.IO.
     * [url] defaults to [ReleaseConfig.latestReleaseUrl] so production/debug switch
     * without any change to callers.
     */
    fun fetchLatest(url: String = ReleaseConfig.latestReleaseUrl()): Result {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", USER_AGENT)
                instanceFollowRedirects = true
            }
            try {
                val code = connection.responseCode
                when (code) {
                    HttpURLConnection.HTTP_OK -> {
                        val body = connection.inputStream.bufferedReader(StandardCharsets.UTF_8)
                            .use(BufferedReader::readText)
                        parseRelease(body)
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> Result.NoReleases
                    403, 429 -> Result.HttpError(
                        code,
                        if (code == 429) "Rate limited by GitHub. Try again later."
                        else "Access denied (HTTP $code)."
                    )
                    else -> {
                        val err = runCatching {
                            connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)
                                ?.use(BufferedReader::readText)
                        }.getOrNull().orEmpty()
                        Result.HttpError(code, err.ifBlank { "HTTP $code" })
                    }
                }
            } finally {
                connection.disconnect()
            }
        } catch (t: Throwable) {
            Result.NetworkError(t)
        }
    }

    internal fun parseRelease(json: String): Result {
        return try {
            val obj = JSONObject(json)
            if (obj.optBoolean("draft", false)) {
                // Defensive: /latest never returns drafts, but guard anyway.
                return Result.NoReleases
            }
            val assetsArr = obj.optJSONArray("assets") ?: JSONArray()
            val assets = buildList {
                for (i in 0 until assetsArr.length()) {
                    val a = assetsArr.getJSONObject(i)
                    add(
                        GitHubAsset(
                            name = a.optString("name", ""),
                            browserDownloadUrl = a.optString("browser_download_url", ""),
                            contentType = a.optString("content_type", null),
                            size = a.optLong("size", 0L),
                            label = a.optString("label", null)?.takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }
            Result.Success(
                GitHubRelease(
                    tagName = obj.optString("tag_name", ""),
                    name = obj.optString("name", null)?.takeIf { it.isNotBlank() && it != "null" },
                    body = obj.optString("body", null)?.takeIf { it.isNotBlank() && it != "null" },
                    publishedAt = obj.optString("published_at", null)
                        ?.takeIf { it.isNotBlank() && it != "null" },
                    htmlUrl = obj.optString("html_url", null)?.takeIf { it.isNotBlank() && it != "null" },
                    prerelease = obj.optBoolean("prerelease", false),
                    draft = false,
                    assets = assets
                )
            )
        } catch (t: Throwable) {
            Result.ParseError(t)
        }
    }
}
