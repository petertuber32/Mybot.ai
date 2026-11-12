package com.mybotai.bacboanalyzer.update

import com.google.gson.Gson
import java.io.InputStreamReader
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

/**
 * Checks GitHub Releases for a newer app version.
 * Pure JVM (no Android imports) so version comparison is unit tested.
 */
object UpdateChecker {

    const val REPO_OWNER = "petertuber32"
    const val REPO_NAME = "Mybot.ai"
    const val REPO = "$REPO_OWNER/$REPO_NAME"
    const val RELEASES_URL = "https://github.com/$REPO/releases"

    data class LatestRelease(
        val tag_name: String? = null,
        val html_url: String? = null,
        val name: String? = null
    )

    /** Fetches the latest published release. Returns null on any failure. */
    fun fetchLatest(): LatestRelease? = runCatching {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest")
            .openConnection() as HttpsURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "BacBoAnalyzer-UpdateCheck")
        conn.inputStream.use { stream ->
            Gson().fromJson(InputStreamReader(stream, StandardCharsets.UTF_8), LatestRelease::class.java)
        }
    }.getOrNull()

    /**
     * True when [remoteTag] (e.g. "v1.2.3") represents a version strictly
     * newer than [currentVersion] (e.g. "1.2.2"). Numeric per-component
     * comparison; missing components are treated as 0.
     */
    fun isNewer(remoteTag: String, currentVersion: String): Boolean {
        val a = parseVersion(remoteTag)
        val b = parseVersion(currentVersion)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parseVersion(v: String): IntArray =
        v.trim().removePrefix("v").removePrefix("V")
            .split('.')
            .map { part -> part.filter { it.isDigit() }.ifEmpty { "0" }.toInt() }
            .toIntArray()
}
