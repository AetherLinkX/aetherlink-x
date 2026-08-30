package com.palazik.vpn.data.codec

import android.net.Uri
import android.util.Base64
import com.palazik.vpn.data.model.*
import org.json.JSONObject
import java.util.UUID

/**
 * Encodes/decodes share-links for all supported protocols.
 *
 * Supported import schemes:
 *   aetherlinkx://  vmess://  vless://  ss://  trojan://  hysteria2://  wireguard://  socks5://
 *   tuic://   xhttp://  palazikvpn://  (palazikVPN proprietary share)
 *
 * Export always produces a palazikvpn:// URI plus the native URI.
 *
 * NOTE: xhttp:// is treated as VLESS + Transport.XHTTP on import.
 *       XHTTP is a transport layer, not a standalone protocol.
 */
object ProfileCodec {

    // ─────────────────────────────────────────────────────────────────────────
    // IMPORT
    // ─────────────────────────────────────────────────────────────────────────

    fun decode(raw: String): VpnProfile? = runCatching {
        val trimmed = raw.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        when {
            scheme == "palazikvpn" || scheme == "alxclient" -> decodePalazik(trimmed)
            trimmed.startsWith("aetherlinkx://", true) -> decodeAetherLinkX(trimmed)
            trimmed.startsWith("vmess://")      -> decodeVmess(trimmed)
            trimmed.startsWith("vless://")      -> decodeVless(trimmed)
            trimmed.startsWith("ss://")         -> decodeShadowsocks(trimmed)
            trimmed.startsWith("trojan://")     -> decodeTrojan(trimmed)
            trimmed.startsWith("hysteria2://")  -> decodeHysteria2(trimmed)
            trimmed.startsWith("wireguard://")  -> decodeWireguard(trimmed)
            trimmed.startsWith("socks5://")     -> decodeSocks5(trimmed)
            // BUG FIX: plain http:// is ambiguous (it's how subscription URLs look), so an
            // HTTP-proxy profile uses the explicit httpproxy:// scheme on import/export.
            trimmed.startsWith("httpproxy://")  -> decodeHttp(trimmed.replaceFirst("httpproxy://", "http://"))
            trimmed.startsWith("tuic://")       -> decodeTuic(trimmed)
            trimmed.startsWith("anytls://")     -> decodeAnyTls(trimmed)
            // xhttp:// share links are VLESS profiles with Transport.XHTTP
            trimmed.startsWith("xhttp://")      -> decodeXhttp(trimmed)
            else -> null
        }
    }.getOrNull()

    /** Decode a subscription body (newline-separated or base64-encoded links) */
    fun decodeSubscriptionBody(body: String): List<VpnProfile> {
        val trimmed = body.trim()

        // Remnawave can return a complete Xray JSON document instead of share links.
        // Keep this path before Base64 decoding so a valid JSON document is never
        // accidentally interpreted as garbage Base64.
        decodeXrayJsonSubscription(trimmed).takeIf { it.isNotEmpty() }?.let { return it }

        // Try base64 first (many providers serve a base64 blob of newline-separated links).
        val fromBase64 = listOf(Base64.DEFAULT, Base64.URL_SAFE).firstNotNullOfOrNull { flags ->
            runCatching { String(Base64.decode(trimmed, flags)) }.getOrNull()
                ?.lines()
                ?.mapNotNull { decode(it.trim()) }
                ?.takeIf { it.isNotEmpty() }
        }
        if (fromBase64 != null) return fromBase64

        // BUG FIX: a plain-text body can still be "Base64-decodable" into garbage, which
        // previously yielded zero profiles. Fall back to parsing the original lines.
        return trimmed.lines().mapNotNull { decode(it.trim()) }
    }

    private fun decodeXrayJsonSubscription(body: String): List<VpnProfile> {
        if (!body.startsWith("{") && !body.startsWith("[")) return emptyList()
        return runCatching {
            val outbounds = if (body.startsWith("[")) {
                org.json.JSONArray(body)
            } else {
                val root = JSONObject(body)
                root.optJSONArray("outbounds")
                    ?: root.optJSONObject("config")?.optJSONArray("outbounds")
                    ?: org.json.JSONArray().put(root.takeIf { it.has("protocol") })
            }
            buildList {
                for (i in 0 until outbounds.length()) {
                    val outbound = outbounds.optJSONObject(i) ?: continue
                    decodeAetherLinkXOutbound(outbound)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeAetherLinkXOutbound(outbound: JSONObject): VpnProfile? {
        if (!outbound.optString("protocol").equals("aetherlinkx", true)) return null
        val settings = outbound.optJSONObject("settings") ?: return null
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val network = stream.optString("network", "tcp").lowercase()
        val transport = when (network) {
            "ws" -> Transport.WS
            "grpc" -> Transport.GRPC
            "xhttp", "splithttp" -> Transport.XHTTP
            "h2", "http" -> Transport.H2
            "quic" -> Transport.QUIC
            else -> Transport.TCP
        }
        val security = when (stream.optString("security").lowercase()) {
            "tls" -> Security.TLS
            "reality" -> Security.REALITY
            "xtls" -> Security.XTLS
            else -> Security.NONE
        }
        val reality = stream.optJSONObject("realitySettings")
        val tls = stream.optJSONObject("tlsSettings")
        val transportSettings = when (transport) {
            Transport.WS -> stream.optJSONObject("wsSettings")
            Transport.GRPC -> stream.optJSONObject("grpcSettings")
            Transport.XHTTP -> stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings")
            Transport.H2 -> stream.optJSONObject("httpSettings")
            Transport.QUIC -> stream.optJSONObject("quicSettings")
            Transport.TCP -> stream.optJSONObject("tcpSettings")
        }
        val headers = transportSettings?.optJSONObject("headers")
        val host = when {
            transport == Transport.GRPC -> ""
            headers?.optString("Host")?.isNotBlank() == true -> headers.optString("Host")
            else -> transportSettings?.optString("host").orEmpty()
        }
        val path = when (transport) {
            Transport.GRPC -> transportSettings?.optString("serviceName").orEmpty()
            else -> transportSettings?.optString("path", "/") ?: "/"
        }.ifBlank { "/" }
        return VpnProfile(
            name = outbound.optString("tag", "AetherLink X").ifBlank { "AetherLink X" },
            protocol = Protocol.AETHERLINK_X,
            address = settings.optString("address"),
            port = settings.optInt("port", 443),
            uuid = settings.optString("id"),
            transport = transport,
            path = path,
            host = host,
            security = security,
            sni = reality?.optString("serverName")?.ifBlank { null }
                ?: tls?.optString("serverName").orEmpty(),
            fingerprint = reality?.optString("fingerprint")?.ifBlank { null }
                ?: tls?.optString("fingerprint")?.ifBlank { null }
                ?: "chrome",
            publicKey = reality?.optString("publicKey").orEmpty(),
            shortId = reality?.optString("shortId").orEmpty(),
            allowInsecure = tls?.optBoolean("allowInsecure", false) ?: false,
            alxSecret = settings.optString("secret"),
            alxAllowInsecureTransport = settings.optBoolean("allowInsecureTransport", false),
            alxTurboJson = settings.optJSONObject("turbo")?.toString() ?: "{}",
            alxSecurityJson = settings.optJSONObject("security")?.toString() ?: "{}",
            alxStealthJson = settings.optJSONObject("stealth")?.toString() ?: "{}",
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // EXPORT — native + palazikvpn:// wrapper
    // ─────────────────────────────────────────────────────────────────────────

    fun encodeNative(p: VpnProfile): String = when (p.protocol) {
        Protocol.AETHERLINK_X -> encodeAetherLinkX(p)
        Protocol.VMESS       -> encodeVmess(p)
        Protocol.VLESS       -> encodeVless(p)      // covers XHTTP transport too
        Protocol.SHADOWSOCKS -> encodeShadowsocks(p)
        Protocol.TROJAN      -> encodeTrojan(p)
        Protocol.HYSTERIA2   -> encodeHysteria2(p)
        Protocol.WIREGUARD   -> encodeWireguard(p)
        Protocol.SOCKS5      -> encodeSocks5(p)
        Protocol.HTTP        -> encodeHttp(p)
        Protocol.TUIC        -> encodeTuic(p)
        Protocol.ANYTLS      -> encodeAnyTls(p)
    }

    /** Returns palazikvpn://<base64(json)>#name */
    fun encodePalazik(p: VpnProfile): String {
        val json = JSONObject().apply {
            put("v", 1)
            put("id", p.id)   // FIX: persist id so active-profile state survives restart
            put("proto", p.protocol.name)
            put("addr", p.address)
            put("port", p.port)
            put("uuid", p.uuid)
            put("transport", p.transport.name)
            put("path", p.path)
            put("host", p.host)
            put("security", p.security.name)
            put("sni", p.sni)
            put("fp", p.fingerprint)
            put("pubkey", p.publicKey)
            put("shortId", p.shortId)
            put("allowInsecure", p.allowInsecure)
            put("vmessScy", p.vmessSecurity)
            put("ssMethod", p.ssMethod)
            put("ssPwd", p.ssPassword)
            put("wgPriv", p.wgPrivateKey)
            put("wgPub", p.wgPeerPublicKey)
            put("wgPsk", p.wgPreSharedKey)
            put("wgEndp", p.wgEndpoint)
            put("wgDns", p.wgDns)
            put("wgMtu", p.wgMtu)
            put("wgReserved", p.wgReserved)
            put("hystPwd", p.hystPassword)
            put("hystObfs", p.hystObfs)
            put("hystObfsPwd", p.hystObfsPassword)
            put("alxSecret", p.alxSecret)
            put("alxAllowInsecureTransport", p.alxAllowInsecureTransport)
            put("alxTurbo", p.alxTurboJson)
            put("alxSecurity", p.alxSecurityJson)
            put("alxStealth", p.alxStealthJson)
            put("muxEnabled", p.muxEnabled)
            put("fragmentEnabled", p.fragmentEnabled)
            put("name", p.name)
        }.toString()
        val b64 = Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "alxclient://$b64#${Uri.encode(p.name)}"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private decoders
    // ─────────────────────────────────────────────────────────────────────────

    private fun decodePalazik(raw: String): VpnProfile {
        val b64 = raw.substringAfter("://").substringBefore("#")
        val json = JSONObject(String(Base64.decode(b64, Base64.URL_SAFE)))
        // Guard: old profiles saved with "XHTTP" or "REALITY" as protocol — migrate them
        val protoStr = json.optString("proto", "VLESS")
        val protocol = runCatching { Protocol.valueOf(protoStr) }.getOrDefault(Protocol.VLESS)
        val transportStr = json.optString("transport", "TCP")
        val transport = runCatching { Transport.valueOf(transportStr) }.getOrDefault(Transport.TCP)
        val savedId = json.optString("id").takeIf { it.isNotEmpty() }
        return VpnProfile(
            id          = savedId ?: UUID.randomUUID().toString(),  // FIX: restore id
            protocol    = protocol,
            address     = json.optString("addr"),
            port        = json.optInt("port", 443),
            uuid        = json.optString("uuid"),
            transport   = transport,
            path        = json.optString("path", "/"),
            host        = json.optString("host"),
            security    = runCatching {
                Security.valueOf(json.optString("security", "TLS"))
            }.getOrDefault(Security.TLS),
            sni         = json.optString("sni"),
            fingerprint = json.optString("fp", "chrome"),
            publicKey   = json.optString("pubkey"),
            shortId     = json.optString("shortId"),
            allowInsecure = json.optBoolean("allowInsecure", false),
            vmessSecurity = json.optString("vmessScy", "auto").ifBlank { "auto" },
            ssMethod    = json.optString("ssMethod", "chacha20-ietf-poly1305"),
            ssPassword  = json.optString("ssPwd"),
            wgPrivateKey    = json.optString("wgPriv"),
            wgPeerPublicKey = json.optString("wgPub"),
            wgPreSharedKey  = json.optString("wgPsk"),
            wgEndpoint      = json.optString("wgEndp"),
            wgDns           = json.optString("wgDns", "1.1.1.1"),
            wgMtu           = json.optInt("wgMtu", 1280),
            wgReserved      = json.optString("wgReserved"),
            hystPassword    = json.optString("hystPwd"),
            hystObfs        = json.optString("hystObfs"),
            hystObfsPassword = json.optString("hystObfsPwd"),
            alxSecret = json.optString("alxSecret"),
            alxAllowInsecureTransport = json.optBoolean("alxAllowInsecureTransport", false),
            alxTurboJson = normalizeOptionsJson(json.optString("alxTurbo", "{}")),
            alxSecurityJson = normalizeOptionsJson(json.optString("alxSecurity", "{}")),
            alxStealthJson = normalizeOptionsJson(json.optString("alxStealth", "{}")),
            muxEnabled  = json.optBoolean("muxEnabled", true),
            fragmentEnabled = json.optBoolean("fragmentEnabled", false),
            name        = json.optString("name", "Imported"),
        )
    }

    private fun decodeVmess(raw: String): VpnProfile {
        val b64  = raw.removePrefix("vmess://")
        val json = JSONObject(String(Base64.decode(b64, Base64.DEFAULT)))
        val transport = when (json.optString("net")) {
            "ws"   -> Transport.WS
            "grpc" -> Transport.GRPC
            "h2"   -> Transport.H2
            "quic" -> Transport.QUIC
            "xhttp"-> Transport.XHTTP
            else   -> Transport.TCP
        }
        val security = when (json.optString("tls")) {
            "tls"    -> Security.TLS
            "xtls"   -> Security.XTLS
            "reality"-> Security.REALITY
            else     -> Security.NONE
        }
        return VpnProfile(
            name      = json.optString("ps", "VMess"),
            protocol  = Protocol.VMESS,
            address   = json.optString("add"),
            port      = json.optString("port").toIntOrNull() ?: 443,
            uuid      = json.optString("id"),
            transport = transport,
            // BUG FIX: always read path and host regardless of transport type
            path      = json.optString("path", "/"),
            host      = json.optString("host"),
            security  = security,
            sni       = json.optString("sni"),
            // BUG FIX: honour the link's cipher (scy) instead of always "auto"
            vmessSecurity = json.optString("scy", "auto").ifBlank { "auto" },
            allowInsecure = json.optString("allowInsecure") == "1" ||
                json.optString("allowInsecure").equals("true", true),
        )
    }

    private fun decodeVless(raw: String): VpnProfile {
        val uri  = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        val transport = Transport.values().firstOrNull {
            it.name.equals(params["type"], true)
        } ?: Transport.TCP
        val security = when (params["security"]) {
            "tls"    -> Security.TLS
            "reality"-> Security.REALITY
            "xtls"   -> Security.XTLS
            else     -> Security.NONE
        }
        return VpnProfile(
            name        = Uri.decode(uri.fragment ?: "VLESS"),
            protocol    = Protocol.VLESS,
            address     = uri.host ?: "",
            port        = uri.port.takeIf { it > 0 } ?: 443,
            uuid        = uri.userInfo ?: "",
            transport   = transport,
            // BUG FIX: always read path and host regardless of transport type
            path        = params["path"] ?: "/",
            host        = params["host"] ?: "",
            security    = security,
            sni         = params["sni"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            publicKey   = params["pbk"] ?: "",
            shortId     = params["sid"] ?: "",
            allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"].equals("true", true),
        )
    }

    private fun decodeAetherLinkX(raw: String): VpnProfile {
        val uri = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        val transport = when (params["type"]?.lowercase()) {
            "ws" -> Transport.WS
            "grpc" -> Transport.GRPC
            "xhttp", "splithttp" -> Transport.XHTTP
            "h2", "http" -> Transport.H2
            "quic" -> Transport.QUIC
            else -> Transport.TCP
        }
        val security = when (params["security"]?.lowercase()) {
            "tls" -> Security.TLS
            "reality" -> Security.REALITY
            "xtls" -> Security.XTLS
            else -> Security.NONE
        }
        fun options(name: String): String = params[name]
            ?.takeIf { it.isNotBlank() }
            ?.let(::decodeBase64OrNull)
            ?.let(::normalizeOptionsJson)
            ?: "{}"

        return VpnProfile(
            name = Uri.decode(uri.fragment ?: "AetherLink X"),
            protocol = Protocol.AETHERLINK_X,
            address = uri.host ?: "",
            port = uri.port.takeIf { it > 0 } ?: 443,
            uuid = uri.userInfo ?: "",
            transport = transport,
            path = params["path"] ?: "/",
            host = params["host"] ?: "",
            security = security,
            sni = params["sni"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            publicKey = params["pbk"] ?: params["password"] ?: "",
            shortId = params["sid"] ?: "",
            alxSecret = params["secret"] ?: "",
            alxAllowInsecureTransport = params["allowInsecureTransport"] == "1" ||
                params["allowInsecureTransport"].equals("true", true),
            alxTurboJson = options("turbo"),
            alxSecurityJson = options("alxSecurity"),
            alxStealthJson = options("stealth"),
        )
    }

    private fun decodeShadowsocks(raw: String): VpnProfile {
        val fragment = raw.substringAfter("#", "Shadowsocks")
        val body     = raw.removePrefix("ss://").substringBefore("#")
        return try {
            val decodedWhole = if ("@" !in body) {
                decodeBase64OrNull(body.substringBefore("?"))
            } else {
                null
            }
            val uri = Uri.parse("ss://${decodedWhole ?: body}")
            val userInfo = uri.userInfo ?: decodedWhole?.substringBeforeLast("@").orEmpty()
            val methodPwd = if (userInfo.contains(":")) userInfo else decodeBase64(userInfo)
            val method  = methodPwd.substringBefore(":")
            val pwd     = methodPwd.substringAfter(":")
            VpnProfile(
                name       = Uri.decode(fragment),
                protocol   = Protocol.SHADOWSOCKS,
                address    = uri.host ?: "",
                port       = uri.port.takeIf { it > 0 } ?: 8388,
                ssMethod   = method,
                ssPassword = pwd,
                // SS does not use TLS — must override the default Security.TLS
                // otherwise XrayConfigBuilder adds tlsSettings and xray fails to connect
                security   = Security.NONE,
                transport  = Transport.TCP,
            )
        } catch (_: Exception) {
            VpnProfile(name = Uri.decode(fragment), protocol = Protocol.SHADOWSOCKS)
        }
    }

    private fun decodeBase64(value: String): String =
        decodeBase64OrNull(value) ?: String(Base64.decode(value, Base64.DEFAULT))

    private fun decodeBase64OrNull(value: String): String? {
        val trimmed = value.trim()
        val normalized = trimmed.padEnd(trimmed.length + (4 - trimmed.length % 4) % 4, '=')
        return listOf(Base64.DEFAULT, Base64.URL_SAFE).firstNotNullOfOrNull { flags ->
            runCatching { String(Base64.decode(normalized, flags)) }.getOrNull()
        }
    }

    private fun decodeTrojan(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        // BUG FIX: Trojan can run over WS/gRPC/H2 — read type/path/host so non-TCP
        // Trojan links import correctly instead of silently falling back to plain TCP.
        val transport = Transport.values().firstOrNull { it.name.equals(params["type"], true) } ?: Transport.TCP
        val security = when (params["security"]?.lowercase()) {
            "none"    -> Security.NONE
            "reality" -> Security.REALITY
            "xtls"    -> Security.XTLS
            else      -> Security.TLS   // Trojan defaults to TLS
        }
        return VpnProfile(
            name        = Uri.decode(uri.fragment ?: "Trojan"),
            protocol    = Protocol.TROJAN,
            address     = uri.host ?: "",
            port        = uri.port.takeIf { it > 0 } ?: 443,
            uuid        = uri.userInfo ?: "",
            transport   = transport,
            path        = params["path"] ?: "/",
            host        = params["host"] ?: "",
            security    = security,
            sni         = params["sni"] ?: "",
            fingerprint = params["fp"]?.ifBlank { "chrome" } ?: "chrome",
            publicKey   = params["pbk"] ?: "",
            shortId     = params["sid"] ?: "",
            allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"].equals("true", true),
        )
    }

    private fun decodeHysteria2(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        return VpnProfile(
            name         = Uri.decode(uri.fragment ?: "Hysteria2"),
            protocol     = Protocol.HYSTERIA2,
            address      = uri.host ?: "",
            port         = uri.port.takeIf { it > 0 } ?: 443,
            hystPassword = uri.userInfo ?: "",
            sni          = params["sni"] ?: "",
            hystObfs     = params["obfs"] ?: "",
            hystObfsPassword = params["obfs-password"] ?: "",
        )
    }

    private fun decodeWireguard(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        return VpnProfile(
            name            = Uri.decode(uri.fragment ?: "WireGuard"),
            protocol        = Protocol.WIREGUARD,
            address         = params["address"]
                ?: params["localaddress"]
                ?: params["localAddress"]
                ?: "10.0.0.2/32",
            port            = uri.port.takeIf { it > 0 } ?: 51820,
            wgPrivateKey    = params["privatekey"] ?: "",
            wgPeerPublicKey = params["publickey"] ?: "",
            wgPreSharedKey  = params["presharedkey"] ?: "",
            wgEndpoint      = params["endpoint"]
                ?: params["peer"]
                ?: uri.host?.let { host -> "$host:${uri.port.takeIf { it > 0 } ?: 51820}" }
                ?: "",
            wgDns           = params["dns"] ?: "1.1.1.1",
            wgMtu           = params["mtu"]?.toIntOrNull() ?: 1280,
            wgReserved      = normalizeReserved(params["reserved"] ?: ""),
        )
    }

    /**
     * Normalize a WARP "reserved" value to "a,b,c". Accepts comma/space-separated ints
     * or a base64 of exactly three bytes (some WARP clients use that form).
     */
    private fun normalizeReserved(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if ("," in trimmed || " " in trimmed) {
            val ints = trimmed.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
                .mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
            return if (ints.size == 3) ints.joinToString(",") else ""
        }
        // Base64 of exactly three bytes — decode to the raw bytes (not via a String).
        val bytes = listOf(Base64.DEFAULT, Base64.URL_SAFE).firstNotNullOfOrNull { flags ->
            runCatching { Base64.decode(trimmed, flags) }.getOrNull()?.takeIf { it.size == 3 }
        }
        return bytes?.joinToString(",") { (it.toInt() and 0xFF).toString() } ?: ""
    }

    private fun decodeSocks5(raw: String): VpnProfile {
        val uri = Uri.parse(raw)
        return VpnProfile(
            name     = Uri.decode(uri.fragment ?: "SOCKS5"),
            protocol = Protocol.SOCKS5,
            address  = uri.host ?: "",
            port     = uri.port.takeIf { it > 0 } ?: 1080,
            uuid     = uri.userInfo ?: "",
        )
    }

    private fun decodeHttp(raw: String): VpnProfile {
        val uri = Uri.parse(raw)
        return VpnProfile(
            name      = Uri.decode(uri.fragment ?: "HTTP"),
            protocol  = Protocol.HTTP,
            address   = uri.host ?: "",
            port      = uri.port.takeIf { it > 0 } ?: 8080,
            uuid      = uri.userInfo ?: "",
            security  = Security.NONE,
            transport = Transport.TCP,
        )
    }

    private fun decodeTuic(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        val userInfo = uri.userInfo ?: ""
        val uuid     = userInfo.substringBefore(":")
        val password = userInfo.substringAfter(":", "")
        return VpnProfile(
            name       = Uri.decode(uri.fragment ?: "TUIC"),
            protocol   = Protocol.TUIC,
            address    = uri.host ?: "",
            port       = uri.port.takeIf { it > 0 } ?: 443,
            uuid       = uuid,
            ssPassword = password.ifEmpty { params["password"] ?: "" },
            security   = Security.TLS,
            sni        = params["sni"] ?: "",
        )
    }

    private fun decodeAnyTls(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        val security = when (params["security"]?.lowercase()) {
            "none" -> Security.NONE
            else   -> Security.TLS   // AnyTLS is TLS-based by default
        }
        return VpnProfile(
            name        = Uri.decode(uri.fragment ?: "AnyTLS"),
            protocol    = Protocol.ANYTLS,
            address     = uri.host ?: "",
            port        = uri.port.takeIf { it > 0 } ?: 443,
            uuid        = uri.userInfo ?: "",   // password
            transport   = Transport.TCP,
            security    = security,
            sni         = params["sni"] ?: "",
            fingerprint = params["fp"]?.ifBlank { "chrome" } ?: "chrome",
            allowInsecure = params["allowInsecure"] == "1" || params["insecure"] == "1" ||
                params["allowInsecure"].equals("true", true),
        )
    }

    private fun encodeAnyTls(p: VpnProfile): String {
        val b = Uri.Builder().scheme("anytls")
            .encodedAuthority(buildEncodedAuthority(p.address, p.port, p.uuid))
            .appendQueryParameter("security", p.security.name.lowercase())
            .appendQueryParameter("sni", p.sni)
            .appendQueryParameter("fp", p.fingerprint)
        if (p.allowInsecure) b.appendQueryParameter("allowInsecure", "1")
        return b.fragment(p.name).build().toString()
    }

    /**
     * xhttp:// share links are VLESS profiles using Transport.XHTTP.
     * XHTTP is NOT a protocol — it is a transport layer.
     * BUG FIX: was Protocol.XHTTP, now correctly Protocol.VLESS + Transport.XHTTP.
     */
    private fun decodeXhttp(raw: String): VpnProfile {
        val uri    = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        return VpnProfile(
            name      = Uri.decode(uri.fragment ?: "XHTTP"),
            protocol  = Protocol.VLESS,          // FIX: was Protocol.XHTTP
            address   = uri.host ?: "",
            port      = uri.port.takeIf { it > 0 } ?: 443,
            uuid      = uri.userInfo ?: "",
            transport = Transport.XHTTP,
            // BUG FIX: always read path and host
            path      = params["path"] ?: "/",
            host      = params["host"] ?: "",
            security  = when {
                params["security"].equals("tls", true) || params["tls"].equals("tls", true) -> Security.TLS
                params["security"].equals("reality", true) -> Security.REALITY
                params["security"].equals("xtls", true) -> Security.XTLS
                else -> Security.NONE
            },
            sni       = params["sni"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            publicKey = params["pbk"] ?: "",
            shortId = params["sid"] ?: "",
            allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"].equals("true", true),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private encoders
    // ─────────────────────────────────────────────────────────────────────────

    private fun encodeVmess(p: VpnProfile): String {
        val net = when (p.transport) {
            Transport.WS    -> "ws"
            Transport.GRPC  -> "grpc"
            Transport.H2    -> "h2"
            Transport.QUIC  -> "quic"
            Transport.XHTTP -> "xhttp"
            else            -> "tcp"
        }
        val tls = when (p.security) {
            Security.TLS    -> "tls"
            Security.XTLS   -> "xtls"
            Security.REALITY-> "reality"
            else            -> ""
        }
        val json = JSONObject().apply {
            put("v","2"); put("ps",p.name); put("add",p.address)
            put("port",p.port); put("id",p.uuid); put("aid",0)
            put("scy", p.vmessSecurity.ifBlank { "auto" })
            put("net",net); put("path",p.path); put("host",p.host)
            put("tls",tls); put("sni",p.sni)
            if (p.allowInsecure) put("allowInsecure", "1")
        }.toString()
        return "vmess://${Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)}"
    }

    private fun encodeAetherLinkX(p: VpnProfile): String {
        fun encodedOptions(raw: String): String = Base64.encodeToString(
            normalizeOptionsJson(raw).toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val b = Uri.Builder().scheme("aetherlinkx")
            .encodedAuthority(buildEncodedAuthority(p.address, p.port, p.uuid))
            .appendQueryParameter("secret", p.alxSecret)
            .appendQueryParameter("allowInsecureTransport", if (p.alxAllowInsecureTransport) "1" else "0")
            .appendQueryParameter("type", p.transport.name.lowercase())
            .appendQueryParameter("security", p.security.name.lowercase())
            .appendQueryParameter("path", p.path)
            .appendQueryParameter("host", p.host)
            .appendQueryParameter("sni", p.sni)
            .appendQueryParameter("fp", p.fingerprint)
            .appendQueryParameter("turbo", encodedOptions(p.alxTurboJson))
            .appendQueryParameter("alxSecurity", encodedOptions(p.alxSecurityJson))
            .appendQueryParameter("stealth", encodedOptions(p.alxStealthJson))
        if (p.security == Security.REALITY) {
            b.appendQueryParameter("pbk", p.publicKey)
                .appendQueryParameter("sid", p.shortId)
        }
        return b.fragment(p.name).build().toString()
    }

    private fun normalizeOptionsJson(raw: String): String = runCatching {
        JSONObject(raw.ifBlank { "{}" }).toString()
    }.getOrDefault("{}")

    private fun encodeVless(p: VpnProfile): String {
        val b = Uri.Builder().scheme("vless")
            .encodedAuthority(buildEncodedAuthority(p.address, p.port, p.uuid))
            .appendQueryParameter("type", p.transport.name.lowercase())
            .appendQueryParameter("security", p.security.name.lowercase())
            // FIX: always encode path and host, not just for certain transports
            .appendQueryParameter("path", p.path)
            .appendQueryParameter("host", p.host)
            .appendQueryParameter("sni", p.sni)
            .appendQueryParameter("fp", p.fingerprint)
        if (p.security == Security.REALITY) {
            b.appendQueryParameter("pbk", p.publicKey)
                .appendQueryParameter("sid", p.shortId)
        }
        if (p.allowInsecure) b.appendQueryParameter("allowInsecure", "1")
        b.fragment(p.name)
        return b.build().toString()
    }

    private fun encodeShadowsocks(p: VpnProfile): String {
        val userInfo = Base64.encodeToString(
            "${p.ssMethod}:${p.ssPassword}".toByteArray(), Base64.NO_WRAP)
        return "ss://$userInfo@${p.address}:${p.port}#${Uri.encode(p.name)}"
    }

    private fun encodeTrojan(p: VpnProfile): String {
        val b = Uri.Builder().scheme("trojan")
            .encodedAuthority(buildEncodedAuthority(p.address, p.port, p.uuid))
            .appendQueryParameter("type", p.transport.name.lowercase())
            .appendQueryParameter("security", p.security.name.lowercase())
            .appendQueryParameter("sni", p.sni)
        if (p.path.isNotBlank() && p.path != "/") b.appendQueryParameter("path", p.path)
        if (p.host.isNotBlank()) b.appendQueryParameter("host", p.host)
        if (p.allowInsecure) b.appendQueryParameter("allowInsecure", "1")
        return b.fragment(p.name).build().toString()
    }

    private fun encodeHysteria2(p: VpnProfile): String {
        val b = Uri.Builder().scheme("hysteria2")
            .encodedAuthority(buildEncodedAuthority(p.address, p.port, p.hystPassword))
            .appendQueryParameter("sni", p.sni)
        if (p.hystObfs.isNotEmpty()) {
            b.appendQueryParameter("obfs", p.hystObfs)
                .appendQueryParameter("obfs-password", p.hystObfsPassword)
        }
        return b.fragment(p.name).build().toString()
    }

    private fun encodeWireguard(p: VpnProfile): String {
        // The URI authority must be the peer ENDPOINT (host:port), not the local tunnel
        // address (which is usually a CIDR like 10.0.0.2/32 and would corrupt the link).
        // The local address is carried as the `address` query parameter.
        val endpoint = p.wgEndpoint.ifBlank { "${stripCidr(p.address)}:${p.port}" }
        val b = Uri.Builder().scheme("wireguard")
            .encodedAuthority(endpoint)
            .appendQueryParameter("address", p.address)
            .appendQueryParameter("publickey", p.wgPeerPublicKey)
            .appendQueryParameter("privatekey", p.wgPrivateKey)
        if (p.wgPreSharedKey.isNotBlank()) b.appendQueryParameter("presharedkey", p.wgPreSharedKey)
        b.appendQueryParameter("endpoint", endpoint)
            .appendQueryParameter("dns", p.wgDns)
            .appendQueryParameter("mtu", p.wgMtu.toString())
        if (p.wgReserved.isNotBlank()) b.appendQueryParameter("reserved", p.wgReserved)
        return b.fragment(p.name).build().toString()
    }

    private fun encodeSocks5(p: VpnProfile): String =
        Uri.Builder().scheme("socks5")
            .encodedAuthority(buildEncodedAuthority(p.address, p.port, p.uuid, keepColon = true))
            .fragment(p.name)
            .build()
            .toString()

    private fun encodeHttp(p: VpnProfile): String {
        // Uses the explicit httpproxy:// scheme so it round-trips without colliding
        // with plain http:// subscription URLs on import.
        return Uri.Builder().scheme("httpproxy")
            .encodedAuthority(buildEncodedAuthority(p.address, p.port, p.uuid, keepColon = true))
            .fragment(p.name)
            .build()
            .toString()
    }

    private fun encodeTuic(p: VpnProfile) =
        Uri.Builder().scheme("tuic")
            .encodedAuthority(buildEncodedAuthority(p.address, p.port, "${p.uuid}:${p.ssPassword}", keepColon = true))
            .appendQueryParameter("sni", p.sni)
            .fragment(p.name).build().toString()

    /**
     * Build an `[userinfo@]host:port` authority with the userinfo percent-encoded so
     * credentials containing URI-reserved characters (@ : / # ? etc.) don't corrupt the
     * link or change the authority.
     *
     * @param keepColon keep ":" unescaped — only for composite `user:pass` userinfo
     *   (socks5/http/tuic). For single-secret userinfo (trojan/hysteria password, vless
     *   uuid) leave it false so a literal ":" in the secret is escaped.
     */
    private fun buildEncodedAuthority(
        address: String,
        port: Int,
        userInfo: String = "",
        keepColon: Boolean = false,
    ): String {
        val host = if (":" in address && !address.startsWith("[")) "[$address]" else address
        val encodedUserInfo = userInfo.takeIf { it.isNotBlank() }?.let {
            val enc = if (keepColon) Uri.encode(it, ":") else Uri.encode(it)
            "$enc@"
        }.orEmpty()
        return "$encodedUserInfo$host:$port"
    }

    /** Strip a CIDR suffix (e.g. "10.0.0.2/32" → "10.0.0.2"). */
    private fun stripCidr(address: String): String = address.substringBefore("/")
}
