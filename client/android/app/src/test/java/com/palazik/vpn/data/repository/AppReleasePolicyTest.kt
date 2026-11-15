package com.palazik.vpn.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppReleasePolicyTest {
    private val tag = "client-v0.8.7-alx-preview.8-update.1"
    private val name = "aetherlink-x-client-android-v0.8.7-alx-preview.8-update.1.apk"
    private val url = "https://github.com/AetherLinkX/aetherlink-x/releases/download/$tag/$name"

    private fun release() = JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject().put("name", name).put("size", 100_000_000)
            .put("state", "uploaded").put("browser_download_url", url)))

    @Test fun `new release supplies readable version and direct APK`() {
        assertEquals(UpdateInfo("0.8.7-alx-preview.8-update.1", url),
            AppReleasePolicy.findUpdate(release(), "0.8.6-alx-preview.8-update.1"))
    }

    @Test fun `current version never locks itself`() {
        assertNull(AppReleasePolicy.findUpdate(release(), tag.removePrefix("client-v")))
    }

    @Test fun `older release and rollback do not block current app`() {
        assertNull(AppReleasePolicy.findUpdate(release(), "0.8.8-alx-preview.8-update.1"))
    }

    @Test fun `draft and preview publication cannot force everyone to update`() {
        assertNull(AppReleasePolicy.findUpdate(release().put("draft", true), "0.8.6"))
        assertNull(AppReleasePolicy.findUpdate(release().put("prerelease", true), "0.8.6"))
    }

    @Test fun `release without a finished APK does not block app`() {
        assertNull(AppReleasePolicy.findUpdate(release().put("assets", JSONArray()), "0.8.6"))
        val uploading = release()
        uploading.getJSONArray("assets").getJSONObject(0).put("state", "new")
        assertNull(AppReleasePolicy.findUpdate(uploading, "0.8.6"))
        val empty = release()
        empty.getJSONArray("assets").getJSONObject(0).put("size", 0)
        assertNull(AppReleasePolicy.findUpdate(empty, "0.8.6"))
    }

    @Test fun `unrelated server release cannot become a mandatory Android update`() {
        assertNull(AppReleasePolicy.findUpdate(release().put("tag_name", "native-v99.0.0"), "0.8.6"))
    }

    @Test fun `download must belong to the same repository and release`() {
        for (badUrl in listOf(
            url.replace("https:", "http:"),
            url.replace("github.com", "github.com.evil.example"),
            url.replace("AetherLinkX/", "SomeoneElse/"),
            url.replace("/download/$tag/", "/download/client-v99/"),
            url + "?redirect=https://evil.example",
            url.replace("github.com/", "attacker@github.com/"),
        )) {
            val value = release()
            value.getJSONArray("assets").getJSONObject(0).put("browser_download_url", badUrl)
            assertNull(badUrl, AppReleasePolicy.findUpdate(value, "0.8.6"))
        }
    }

    @Test fun `architecture specific APK is skipped instead of forcing wrong installation`() {
        val value = release()
        value.getJSONArray("assets").getJSONObject(0).put("name", "aetherlink-x-client-android-arm64.apk")
        assertNull(AppReleasePolicy.findUpdate(value, "0.8.6"))
    }

    @Test fun `blank or malformed current version never locks app`() {
        assertNull(AppReleasePolicy.findUpdate(release(), ""))
        assertNull(AppReleasePolicy.findUpdate(release(), "unknown"))
    }

    @Test fun `numeric version comparison handles prefixes and revision numbers`() {
        assertTrue(AppReleasePolicy.isNewerVersion("client-v0.8.10", "0.8.9"))
        assertFalse(AppReleasePolicy.isNewerVersion("client-v0.8.6-update.1", "0.8.6-update.2"))
        assertFalse(AppReleasePolicy.isNewerVersion("client-v0.8.6", "0.8.6.0"))
    }
}