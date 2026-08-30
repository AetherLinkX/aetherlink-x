package com.palazik.vpn.data.warp

import com.palazik.vpn.compat.Base64
import com.palazik.vpn.data.model.Protocol
import com.palazik.vpn.data.model.VpnProfile
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Registers a free Cloudflare WARP account and turns it into a ready-to-use WireGuard
 * profile, so a first-time user with no servers can still connect.
 *
 * The WireGuard keypair is generated with the JDK X25519 provider (available since
 * Java 11, always present in our bundled runtime).
 */
object WarpProvisioner {

    private const val REG_URL = "https://api.cloudflareclient.com/v0a2158/reg"
    private const val CLIENT_VERSION = "a-6.30-3596"

    fun register(client: OkHttpClient): Result<VpnProfile> = runCatching {
        val (privateKey, publicKey) = generateKeyPair()

        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        val payload = JSONObject().apply {
            put("key", publicKey)
            put("install_id", "")
            put("fcm_token", "")
            put("tos", tos)
            put("model", "PC")
            put("serial_number", "")
            put("locale", "en_US")
        }

        val request = Request.Builder()
            .url(REG_URL)
            .header("User-Agent", "okhttp/4.12.0")
            .header("CF-Client-Version", CLIENT_VERSION)
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val body = client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("Cloudflare returned HTTP ${resp.code}")
            resp.body?.string()?.takeIf { it.isNotBlank() } ?: throw Exception("Empty response")
        }

        parseProfile(body, privateKey)
    }

    /** @return (privateKeyBase64, publicKeyBase64), both raw 32-byte X25519 keys. */
    private fun generateKeyPair(): Pair<String, String> {
        val generator = KeyPairGenerator.getInstance("X25519")
        val pair = generator.generateKeyPair()
        // For X25519 the raw 32-byte key is the trailing 32 bytes of the DER encoding
        // (PKCS8 for the private key, SubjectPublicKeyInfo for the public key).
        val priv = pair.`private`.encoded.takeLast(32).toByteArray()
        val pub  = pair.`public`.encoded.takeLast(32).toByteArray()
        return base64(priv) to base64(pub)
    }

    private fun parseProfile(body: String, privateKey: String): VpnProfile {
        // Depending on the API version, the device config is either at the top level or
        // wrapped in a "result" object — handle both.
        val root = JSONObject(body)
        val config = (root.optJSONObject("result") ?: root).getJSONObject("config")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val endpointHost = peer.getJSONObject("endpoint").optString("host", "engage.cloudflareclient.com:2408")
        val addresses = config.getJSONObject("interface").getJSONObject("addresses")
        val v4 = addresses.optString("v4")

        // client_id is base64 of three bytes — WARP's WireGuard "reserved" field.
        val reserved = runCatching {
            Base64.decode(config.optString("client_id"), Base64.DEFAULT)
                .take(3).joinToString(",") { (it.toInt() and 0xFF).toString() }
        }.getOrDefault("")

        return VpnProfile(
            name            = "Cloudflare WARP",
            protocol        = Protocol.WIREGUARD,
            address         = if (v4.isNotBlank()) "$v4/32" else "172.16.0.2/32",
            port            = endpointHost.substringAfterLast(':').toIntOrNull() ?: 2408,
            wgPrivateKey    = privateKey,
            wgPeerPublicKey = peer.getString("public_key"),
            wgEndpoint      = endpointHost,
            wgDns           = "1.1.1.1",
            wgMtu           = 1280,
            wgReserved      = reserved,
        )
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
