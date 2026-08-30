package com.palazik.vpn.service

import com.palazik.vpn.AppDirs
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Locates the xray / tun2socks binaries and the geo data files.
 *
 * Lookup order for binaries:
 *  1. resources bundled into the packaged app by CI (<dist>/app/resources)
 *  2. ~/.local/share/palazikVPN/bin (user-provided)
 *  3. $PATH (e.g. `pacman -S xray`)
 *
 * Geo files are copied from the bundle into the assets dir on first run, or
 * downloaded from the default URLs as a fallback (same sources the CI uses).
 */
object XrayAssets {

    const val GEOIP_URL = "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
    const val GEOSITE_URL = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"

    fun findXray(): File? = findBinary("xray")

    fun findTun2socks(): File? = findBinary("tun2socks")

    private fun findBinary(name: String): File? {
        AppDirs.bundledResourcesDir?.let { dir ->
            val f = File(dir, name)
            if (f.isFile) {
                // jpackage strips exec bits from bundled resources, and the install
                // lives in root-owned /opt where we can't chmod. Self-heal by copying
                // into the user's data dir and making that executable.
                if (f.canExecute() || f.setExecutable(true)) return f
                val local = File(AppDirs.binDir, name)
                runCatching {
                    if (!local.isFile || local.length() != f.length()) f.copyTo(local, overwrite = true)
                    local.setExecutable(true)
                }
                if (local.canExecute()) return local
            }
        }
        File(AppDirs.binDir, name).takeIf { it.isFile }?.let { return it.also { f -> f.setExecutable(true) } }
        val path = System.getenv("PATH") ?: return null
        for (entry in path.split(":")) {
            val f = File(entry, name)
            if (f.isFile && f.canExecute()) return f
        }
        return null
    }

    /**
     * Make sure geoip.dat / geosite.dat exist in [AppDirs.assetsDir].
     * Copies the bundled files when packaged, otherwise downloads them once.
     * @throws Exception when a file can neither be copied nor downloaded.
     */
    fun ensureGeoFiles(client: OkHttpClient, log: (String) -> Unit) {
        listOf("geoip.dat" to GEOIP_URL, "geosite.dat" to GEOSITE_URL).forEach { (name, url) ->
            val dest = File(AppDirs.assetsDir, name)
            if (dest.exists() && dest.length() > 0L) return@forEach

            val bundled = AppDirs.bundledResourcesDir?.let { File(it, name) }
            if (bundled?.isFile == true && bundled.length() > 0L) {
                bundled.copyTo(dest, overwrite = true)
                log("Copied bundled $name (${dest.length()} bytes)")
                return@forEach
            }

            log("Downloading $name…")
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw Exception("$name: HTTP ${resp.code}")
                val bytes = resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                    ?: throw Exception("$name: empty response")
                dest.writeBytes(bytes)
            }
            log("Downloaded $name (${dest.length()} bytes)")
        }
    }
}
