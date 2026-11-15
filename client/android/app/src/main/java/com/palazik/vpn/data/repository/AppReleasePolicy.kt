package com.palazik.vpn.data.repository

import org.json.JSONObject
import java.net.URI

data class UpdateInfo(val version: String, val url: String)

/** Only a published Android release with a download from our repository can require an update. */
internal object AppReleasePolicy {
    fun findUpdate(release: JSONObject, currentVersion: String): UpdateInfo? {
        if (currentVersion.isBlank() || release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name")
        if (!tag.startsWith("client-v") || !isNewerVersion(tag, currentVersion)) return null
        val assets = release.optJSONArray("assets") ?: return null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            // Our CI publishes a universal APK. Never send an arbitrary ABI-specific
            // asset, source archive or external URL to a mandatory download button.
            if (!name.startsWith("aetherlink-x-client-android-") || !name.endsWith(".apk") ||
                name.contains("arm64") || name.contains("armeabi") || name.contains("x86")) continue
            if (asset.optLong("size") <= 0 || asset.optString("state") != "uploaded") continue
            val url = asset.optString("browser_download_url")
            val uri = runCatching { URI(url) }.getOrNull() ?: continue
            if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null ||
                uri.port != -1 || uri.query != null || uri.fragment != null ||
                uri.path != "/AetherLinkX/aetherlink-x/releases/download/$tag/$name") continue
            return UpdateInfo(tag.removePrefix("client-v"), url)
        }
        return null
    }

    internal fun isNewerVersion(latest: String, current: String): Boolean {
        fun parts(value: String) = value.dropWhile { !it.isDigit() }
            .split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }
        val a = parts(latest)
        val b = parts(current)
        if (a.isEmpty() || b.isEmpty()) return false
        for (index in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(index) { 0 }
            val y = b.getOrElse(index) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}