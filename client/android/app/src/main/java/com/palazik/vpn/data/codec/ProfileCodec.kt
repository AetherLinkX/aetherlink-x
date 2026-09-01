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

    private fun parseTransport(raw: String?): Transport = when (raw?.lowercase()) {
        "ws", "websocket" -> Transport.WS
        "grpc", "gun" -> Transport.GRPC
        "xhttp", "splithttp" -> Transport.XHTTP
        "httpupgrade", "http_upgrade" -> Transport.HTTP_UPGRADE
        "kcp", "mkcp" -> Transport.KCP
        "hysteria" -> Transport.HYSTERIA
        "h2", "http" -> Transport.H2
        "h3", "quic" -> Transport.QUIC
        else -> Transport.TCP
    }

    private fun encodeTransport(transport: Transport): String = when (transport) {
        Transport.TCP -> "tcp"
        Transport.WS -> "ws"
        Transport.GRPC -> "grpc"
        Transport.XHTTP -> "xhttp"
        Transport.HTTP_UPGRADE -> "httpupgrade"
        Transport.KCP -> "kcp"
        Transport.HYSTERIA -> "hysteria"
        Transport.H2 -> "h2"
        Transport.QUIC -> "quic"
    }

    private fun Uri.Builder.appendTransportParameters(p: VpnProfile): Uri.Builder = apply {
        appendQueryParameter("type", encodeTransport(p.transport))
        if (p.transport == Transport.GRPC) appendQueryParameter("serviceName", p.path)
        else appendQueryParameter("path", p.path)
        appendQueryParameter("host", p.host)
        if (p.transportMode.isNotBlank()) appendQueryParameter("mode", p.transportMode)
        if (p.transport == Transport.XHTTP && p.transportExtraJson.isNotBlank() && p.transportExtraJson != "{}") {
            appendQueryParameter("extra", normalizeTransportExtra(p.transportExtraJson))
        }
        if (p.transportHeader.isNotBlank() && p.transportHeader != "none") {
            appendQueryParameter("headerType", p.transportHeader)
        }
        if (p.transportSeed.isNotBlank()) appendQueryParameter("seed", p.transportSeed)
        if (p.transportSecurity.isNotBlank() && p.transportSecurity != "none") {
            appendQueryParameter("quicSecurity", p.transportSecurity)
        }
        if (p.transportKey.isNotBlank()) appendQueryParameter("key", p.transportKey)
    }

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
        val trimmed = body.trim().removePrefix("\uFEFF").trim()
        if (trimmed.isEmpty()) return emptyList()

        // Remnawave can return a complete Xray JSON document instead of share links.
        // Keep this path before Base64 decoding so a valid JSON document is never
        // accidentally interpreted as garbage Base64.
        decodeXrayJsonSubscription(trimmed).takeIf { it.isNotEmpty() }?.let { return it }
        decodeSingBoxJsonSubscription(trimmed).takeIf { it.isNotEmpty() }?.let { return it }
        decodeSip008Subscription(trimmed).takeIf { it.isNotEmpty() }?.let { return it }

        // Try base64 first (many providers serve a base64 blob of newline-separated links).
        val fromBase64 = listOf(Base64.DEFAULT, Base64.URL_SAFE).firstNotNullOfOrNull { flags ->
            runCatching { String(Base64.decode(trimmed, flags), Charsets.UTF_8) }.getOrNull()
                ?.trim()
                ?.removePrefix("\uFEFF")
                ?.let { decoded ->
                    decodeXrayJsonSubscription(decoded).takeIf { it.isNotEmpty() }
                        ?: decodeSingBoxJsonSubscription(decoded).takeIf { it.isNotEmpty() }
                        ?: decodeSip008Subscription(decoded).takeIf { it.isNotEmpty() }
                        ?: decodeSubscriptionLines(decoded).takeIf { it.isNotEmpty() }
                }
        }
        if (fromBase64 != null) return fromBase64

        // BUG FIX: a plain-text body can still be "Base64-decodable" into garbage, which
        // previously yielded zero profiles. Fall back to parsing the original lines.
        return decodeSubscriptionLines(trimmed)
    }

    private fun decodeSubscriptionLines(body: String): List<VpnProfile> =
        body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull(::decode)
            .toList()

    private fun decodeXrayJsonSubscription(body: String): List<VpnProfile> {
        if (!body.startsWith("{") && !body.startsWith("[")) return emptyList()
        return runCatching {
            val roots = if (body.startsWith("[")) org.json.JSONArray(body)
                else org.json.JSONArray().put(JSONObject(body))
            buildList {
                for (rootIndex in 0 until roots.length()) {
                    val outer = roots.optJSONObject(rootIndex) ?: continue
                    val root = outer.optJSONObject("config") ?: outer
                    val remark = root.optString("remarks").ifBlank { outer.optString("remarks") }
                    val outbounds = root.optJSONArray("outbounds")
                        ?: org.json.JSONArray().put(root.takeIf { it.has("protocol") })
                    for (i in 0 until outbounds.length()) {
                        val outbound = outbounds.optJSONObject(i) ?: continue
                        addAll(decodeXrayOutbound(outbound, remark))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Import the native JSON produced by sing-box based panels/clients. */
    private fun decodeSingBoxJsonSubscription(body: String): List<VpnProfile> {
        if (!body.startsWith("{") && !body.startsWith("[")) return emptyList()
        return runCatching {
            val roots = if (body.startsWith("[")) org.json.JSONArray(body)
                else org.json.JSONArray().put(JSONObject(body))
            buildList {
                for (rootIndex in 0 until roots.length()) {
                    val outer = roots.optJSONObject(rootIndex) ?: continue
                    val root = outer.optJSONObject("config") ?: outer
                    val outbounds = root.optJSONArray("outbounds") ?: continue
                    for (index in 0 until outbounds.length()) {
                        val outbound = outbounds.optJSONObject(index) ?: continue
                        decodeSingBoxOutbound(outbound)?.let(::add)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeSingBoxOutbound(outbound: JSONObject): VpnProfile? {
        val type = outbound.optString("type").lowercase()
        val protocol = when (type) {
            "vless" -> Protocol.VLESS
            "vmess" -> Protocol.VMESS
            "trojan" -> Protocol.TROJAN
            "shadowsocks" -> Protocol.SHADOWSOCKS
            "hysteria2", "hy2" -> Protocol.HYSTERIA2
            "tuic" -> Protocol.TUIC
            "anytls" -> Protocol.ANYTLS
            else -> return null
        }
        val tls = outbound.optJSONObject("tls") ?: JSONObject()
        val reality = tls.optJSONObject("reality") ?: JSONObject()
        val transportSettings = outbound.optJSONObject("transport") ?: JSONObject()
        val transport = parseTransport(transportSettings.optString("type", "tcp"))
        val security = when {
            reality.optBoolean("enabled", false) -> Security.REALITY
            tls.optBoolean("enabled", false) -> Security.TLS
            else -> Security.NONE
        }
        val credential = when (protocol) {
            Protocol.VLESS, Protocol.VMESS, Protocol.TUIC -> outbound.optString("uuid")
            else -> outbound.optString("password")
        }
        val headers = transportSettings.optJSONObject("headers")
        val host = when (transport) {
            Transport.GRPC -> transportSettings.optString("authority")
            else -> jsonText(headers, "Host").ifBlank { jsonText(transportSettings, "host") }
        }
        val path = when (transport) {
            Transport.GRPC -> transportSettings.optString("service_name")
            else -> transportSettings.optString("path", "/")
        }.ifBlank { "/" }
        val fingerprint = tls.optJSONObject("utls")?.optString("fingerprint")
            .orEmpty().ifBlank { "chrome" }
        return VpnProfile(
            name = outbound.optString("tag", protocol.name).ifBlank { protocol.name },
            protocol = protocol,
            address = outbound.optString("server"),
            port = outbound.optInt("server_port", 443),
            uuid = credential,
            transport = transport,
            path = path,
            host = host,
            security = security,
            sni = tls.optString("server_name"),
            fingerprint = fingerprint,
            alpn = tls.optJSONArray("alpn")?.let { values ->
                (0 until values.length()).mapNotNull { values.optString(it).takeIf(String::isNotBlank) }
                    .joinToString(",")
            }.orEmpty(),
            publicKey = reality.optString("public_key"),
            shortId = reality.optString("short_id"),
            flow = outbound.optString("flow"),
            allowInsecure = tls.optBoolean("insecure", false),
            vmessSecurity = outbound.optString("security", "auto").ifBlank { "auto" },
            ssMethod = outbound.optString("method", "chacha20-ietf-poly1305"),
            ssPassword = if (protocol == Protocol.SHADOWSOCKS) credential else
                if (protocol == Protocol.TUIC) outbound.optString("password") else "",
            hystPassword = if (protocol == Protocol.HYSTERIA2) credential else "",
            hystObfs = outbound.optJSONObject("obfs")?.optString("type").orEmpty(),
            hystObfsPassword = outbound.optJSONObject("obfs")?.optString("password").orEmpty(),
            transportMode = transportSettings.optString("mode"),
            transportExtraJson = normalizeTransportExtra(transportSettings.opt("extra")?.toString()),
        )
    }

    /** Import Shadowsocks SIP008 JSON without treating repeated server entries as duplicates. */
    private fun decodeSip008Subscription(body: String): List<VpnProfile> {
        if (!body.startsWith("{")) return emptyList()
        return runCatching {
            val root = JSONObject(body)
            val servers = root.optJSONArray("servers") ?: return@runCatching emptyList()
            buildList {
                for (index in 0 until servers.length()) {
                    val server = servers.optJSONObject(index) ?: continue
                    if (!server.has("server") || !server.has("server_port")) continue
                    add(VpnProfile(
                        name = server.optString("remarks")
                            .ifBlank { server.optString("name") }
                            .ifBlank { "Shadowsocks" },
                        protocol = Protocol.SHADOWSOCKS,
                        address = server.optString("server"),
                        port = server.optInt("server_port"),
                        security = Security.NONE,
                        ssMethod = server.optString("method"),
                        ssPassword = server.optString("password"),
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun decodeXrayOutbound(outbound: JSONObject, remark: String): List<VpnProfile> =
        when (outbound.optString("protocol").lowercase()) {
            "aetherlinkx", "alx" -> listOfNotNull(decodeAetherLinkXOutbound(outbound, remark))
            "vless", "vmess" -> decodeVnextXrayOutbounds(outbound, remark)
            "trojan" -> decodeServerXrayOutbounds(outbound, remark, Protocol.TROJAN)
            "shadowsocks" -> decodeServerXrayOutbounds(outbound, remark, Protocol.SHADOWSOCKS)
            else -> emptyList()
        }

    private fun decodeAetherLinkXOutbound(outbound: JSONObject, remark: String = ""): VpnProfile? {
        if (outbound.optString("protocol").lowercase() !in setOf("aetherlinkx", "alx")) return null
        val settings = outbound.optJSONObject("settings") ?: return null
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val network = stream.optString("network", "tcp").lowercase()
        val transport = parseTransport(network)
        val security = when (stream.optString("security").lowercase()) {
            "tls" -> Security.TLS
            "reality" -> Security.REALITY
            "xtls" -> Security.XTLS
            else -> Security.NONE
        }
        val reality = stream.optJSONObject("realitySettings")
        val tls = stream.optJSONObject("tlsSettings")
        val tlsLayer = reality ?: tls
        val transportSettings = when (transport) {
            Transport.WS -> stream.optJSONObject("wsSettings")
            Transport.GRPC -> stream.optJSONObject("grpcSettings")
            Transport.XHTTP -> stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings")
            Transport.HTTP_UPGRADE -> stream.optJSONObject("httpupgradeSettings")
            Transport.KCP -> stream.optJSONObject("kcpSettings")
            Transport.HYSTERIA -> stream.optJSONObject("hysteriaSettings")
            Transport.H2 -> stream.optJSONObject("httpSettings")
            Transport.QUIC -> stream.optJSONObject("quicSettings")
            Transport.TCP -> stream.optJSONObject("tcpSettings")
        }
        val headers = transportSettings?.optJSONObject("headers")
        val host = when {
            transport == Transport.GRPC -> transportSettings?.optString("authority").orEmpty()
            jsonText(headers, "Host").isNotBlank() -> jsonText(headers, "Host")
            else -> jsonText(transportSettings, "host")
        }
        val path = when (transport) {
            Transport.GRPC -> transportSettings?.optString("serviceName").orEmpty()
            else -> transportSettings?.optString("path", "/") ?: "/"
        }.ifBlank { "/" }
        return VpnProfile(
            name = remark.ifBlank { outbound.optString("tag", "AetherLink X") }.ifBlank { "AetherLink X" },
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
            publicKey = reality?.optString("publicKey")?.ifBlank { reality.optString("password") }.orEmpty(),
            shortId = reality?.optString("shortId").orEmpty(),
            allowInsecure = tls?.optBoolean("allowInsecure", false) ?: false,
            alxSecret = settings.optString("secret"),
            alxAllowInsecureTransport = settings.optBoolean("allowInsecureTransport", false),
            alxTurboJson = settings.optJSONObject("turbo")?.toString() ?: "{}",
            alxSecurityJson = settings.optJSONObject("security")?.toString() ?: "{}",
            alxStealthJson = settings.optJSONObject("stealth")?.toString() ?: "{}",
            transportMode = transportSettings?.optString("mode").orEmpty(),
            transportExtraJson = normalizeTransportExtra(transportSettings?.opt("extra")?.toString()),
            transportHeader = transportSettings?.optJSONObject("header")?.optString("type", "none") ?: "none",
            transportSeed = transportSettings?.optString("seed").orEmpty(),
            transportSecurity = transportSettings?.optString("security", "none") ?: "none",
            transportKey = transportSettings?.optString("key").orEmpty(),
            alpn = tlsLayer?.optJSONArray("alpn")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { value -> value.isNotBlank() } }.joinToString(",")
            }.orEmpty(),
            spiderX = reality?.optString("spiderX").orEmpty(),
        )
    }

    private fun decodeVnextXrayOutbounds(outbound: JSONObject, remark: String): List<VpnProfile> {
        val protocol = when (outbound.optString("protocol").lowercase()) {
            "vless" -> Protocol.VLESS
            "vmess" -> Protocol.VMESS
            else -> return emptyList()
        }
        val servers = outbound.optJSONObject("settings")?.optJSONArray("vnext") ?: return emptyList()
        return buildList {
            for (index in 0 until servers.length()) {
                val server = servers.optJSONObject(index) ?: continue
                val user = server.optJSONArray("users")?.optJSONObject(0) ?: continue
                add(profileFromXrayOutbound(
                    outbound = outbound,
                    name = remark,
                    protocol = protocol,
                    address = server.optString("address"),
                    port = server.optInt("port", 443),
                    credential = user.optString("id"),
                ).copy(
                    flow = user.optString("flow"),
                    vmessSecurity = user.optString("security", "auto").ifBlank { "auto" },
                ))
            }
        }
    }

    private fun decodeServerXrayOutbounds(
        outbound: JSONObject,
        remark: String,
        protocol: Protocol,
    ): List<VpnProfile> {
        val servers = outbound.optJSONObject("settings")?.optJSONArray("servers") ?: return emptyList()
        return buildList {
            for (index in 0 until servers.length()) {
                val server = servers.optJSONObject(index) ?: continue
                val credential = server.optString("password")
                add(profileFromXrayOutbound(
                    outbound = outbound,
                    name = remark,
                    protocol = protocol,
                    address = server.optString("address"),
                    port = server.optInt("port", 443),
                    credential = credential,
                ).copy(
                    ssMethod = server.optString("method", "chacha20-ietf-poly1305"),
                    ssPassword = if (protocol == Protocol.SHADOWSOCKS) credential else "",
                ))
            }
        }
    }

    private fun profileFromXrayOutbound(
        outbound: JSONObject,
        name: String,
        protocol: Protocol,
        address: String,
        port: Int,
        credential: String,
    ): VpnProfile {
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val transport = parseTransport(stream.optString("network", "tcp"))
        val security = when (stream.optString("security").lowercase()) {
            "tls" -> Security.TLS
            "reality" -> Security.REALITY
            "xtls" -> Security.XTLS
            else -> Security.NONE
        }
        val reality = stream.optJSONObject("realitySettings")
        val tls = stream.optJSONObject("tlsSettings")
        val tlsLayer = reality ?: tls
        val transportSettings = when (transport) {
            Transport.WS -> stream.optJSONObject("wsSettings")
            Transport.GRPC -> stream.optJSONObject("grpcSettings")
            Transport.XHTTP -> stream.optJSONObject("xhttpSettings") ?: stream.optJSONObject("splithttpSettings")
            Transport.HTTP_UPGRADE -> stream.optJSONObject("httpupgradeSettings")
            Transport.KCP -> stream.optJSONObject("kcpSettings")
            Transport.HYSTERIA -> stream.optJSONObject("hysteriaSettings")
            Transport.H2 -> stream.optJSONObject("httpSettings")
            Transport.QUIC -> stream.optJSONObject("quicSettings")
            Transport.TCP -> stream.optJSONObject("tcpSettings")
        }
        val headers = transportSettings?.optJSONObject("headers")
        val host = when {
            transport == Transport.GRPC -> transportSettings?.optString("authority").orEmpty()
            jsonText(headers, "Host").isNotBlank() -> jsonText(headers, "Host")
            else -> jsonText(transportSettings, "host")
        }
        val path = when (transport) {
            Transport.GRPC -> transportSettings?.optString("serviceName").orEmpty()
            else -> transportSettings?.optString("path", "/") ?: "/"
        }.ifBlank { "/" }
        return VpnProfile(
            name = name.ifBlank { outbound.optString("tag", protocol.name) }.ifBlank { protocol.name },
            protocol = protocol,
            address = address,
            port = port,
            uuid = credential,
            transport = transport,
            path = path,
            host = host,
            security = security,
            sni = reality?.optString("serverName")?.ifBlank { null }
                ?: tls?.optString("serverName").orEmpty(),
            fingerprint = reality?.optString("fingerprint")?.ifBlank { null }
                ?: tls?.optString("fingerprint")?.ifBlank { null }
                ?: "chrome",
            publicKey = reality?.optString("publicKey")?.ifBlank { reality.optString("password") }.orEmpty(),
            shortId = reality?.optString("shortId").orEmpty(),
            allowInsecure = tls?.optBoolean("allowInsecure", false) ?: false,
            transportMode = transportSettings?.optString("mode").orEmpty(),
            transportExtraJson = normalizeTransportExtra(transportSettings?.opt("extra")?.toString()),
            transportHeader = transportSettings?.optJSONObject("header")?.optString("type", "none") ?: "none",
            transportSeed = transportSettings?.optString("seed").orEmpty(),
            transportSecurity = transportSettings?.optString("security", "none") ?: "none",
            transportKey = transportSettings?.optString("key").orEmpty(),
            alpn = tlsLayer?.optJSONArray("alpn")?.let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.joinToString(",")
            }.orEmpty(),
            spiderX = reality?.optString("spiderX").orEmpty(),
        )
    }

    private fun jsonText(source: JSONObject?, key: String): String {
        val value = source?.opt(key) ?: return ""
        return when (value) {
            is org.json.JSONArray -> value.optString(0)
            is String -> value
            else -> value.toString().takeUnless { it == "null" }.orEmpty()
        }
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
            put("transportMode", p.transportMode)
            put("transportExtraJson", p.transportExtraJson)
            put("transportHeader", p.transportHeader)
            put("transportSeed", p.transportSeed)
            put("transportSecurity", p.transportSecurity)
            put("transportKey", p.transportKey)
            put("security", p.security.name)
            put("sni", p.sni)
            put("fp", p.fingerprint)
            put("alpn", p.alpn)
            put("pubkey", p.publicKey)
            put("shortId", p.shortId)
            put("spiderX", p.spiderX)
            put("flow", p.flow)
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
            transportMode = json.optString("transportMode"),
            transportExtraJson = normalizeTransportExtra(json.optString("transportExtraJson", "{}")),
            transportHeader = json.optString("transportHeader", "none"),
            transportSeed = json.optString("transportSeed"),
            transportSecurity = json.optString("transportSecurity", "none"),
            transportKey = json.optString("transportKey"),
            security    = runCatching {
                Security.valueOf(json.optString("security", "TLS"))
            }.getOrDefault(Security.TLS),
            sni         = json.optString("sni"),
            fingerprint = json.optString("fp", "chrome"),
            alpn        = json.optString("alpn"),
            publicKey   = json.optString("pubkey"),
            shortId     = json.optString("shortId"),
            spiderX     = json.optString("spiderX"),
            flow        = json.optString("flow"),
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
        val transport = parseTransport(json.optString("net"))
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
            transportMode = json.optString("mode"),
            transportExtraJson = normalizeTransportExtra(json.opt("extra")?.toString()),
            transportHeader = json.optString("type", "none"),
            transportSeed = json.optString("seed"),
            transportSecurity = json.optString("quicSecurity", "none"),
            transportKey = json.optString("key"),
            security  = security,
            sni       = json.optString("sni"),
            alpn      = json.optString("alpn"),
            // BUG FIX: honour the link's cipher (scy) instead of always "auto"
            vmessSecurity = json.optString("scy", "auto").ifBlank { "auto" },
            allowInsecure = json.optString("allowInsecure") == "1" ||
                json.optString("allowInsecure").equals("true", true),
        )
    }

    private fun decodeVless(raw: String): VpnProfile {
        val uri  = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        val transport = parseTransport(params["type"])
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
            path        = when (transport) {
                Transport.GRPC -> params["serviceName"] ?: params["path"] ?: ""
                else -> params["path"] ?: "/"
            },
            host        = params["host"] ?: "",
            transportMode = params["mode"] ?: "",
            transportExtraJson = normalizeTransportExtra(params["extra"]),
            transportHeader = params["headerType"] ?: params["header"] ?: "none",
            transportSeed = params["seed"] ?: "",
            transportSecurity = params["quicSecurity"] ?: "none",
            transportKey = params["key"] ?: "",
            security    = security,
            sni         = params["sni"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            alpn        = params["alpn"] ?: "",
            publicKey   = params["pbk"] ?: "",
            shortId     = params["sid"] ?: "",
            spiderX     = params["spx"] ?: "",
            flow        = params["flow"] ?: "",
            allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"].equals("true", true),
        )
    }

    private fun decodeAetherLinkX(raw: String): VpnProfile {
        val uri = Uri.parse(raw)
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }
        val transport = parseTransport(params["type"])
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
            path = when (transport) {
                Transport.GRPC -> params["serviceName"] ?: params["path"] ?: ""
                else -> params["path"] ?: "/"
            },
            host = params["host"] ?: "",
            transportMode = params["mode"] ?: "",
            transportExtraJson = normalizeTransportExtra(params["extra"]),
            transportHeader = params["headerType"] ?: params["header"] ?: "none",
            transportSeed = params["seed"] ?: "",
            transportSecurity = params["quicSecurity"] ?: "none",
            transportKey = params["key"] ?: "",
            security = security,
            sni = params["sni"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            alpn = params["alpn"] ?: "",
            publicKey = params["pbk"] ?: params["password"] ?: "",
            shortId = params["sid"] ?: "",
            spiderX = params["spx"] ?: "",
            flow = params["flow"] ?: "",
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
        val transport = parseTransport(params["type"])
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
            path        = when (transport) {
                Transport.GRPC -> params["serviceName"] ?: params["path"] ?: ""
                else -> params["path"] ?: "/"
            },
            host        = params["host"] ?: "",
            transportMode = params["mode"] ?: "",
            transportExtraJson = normalizeTransportExtra(params["extra"]),
            transportHeader = params["headerType"] ?: params["header"] ?: "none",
            transportSeed = params["seed"] ?: "",
            transportSecurity = params["quicSecurity"] ?: "none",
            transportKey = params["key"] ?: "",
            security    = security,
            sni         = params["sni"] ?: "",
            fingerprint = params["fp"]?.ifBlank { "chrome" } ?: "chrome",
            alpn        = params["alpn"] ?: "",
            publicKey   = params["pbk"] ?: "",
            shortId     = params["sid"] ?: "",
            spiderX     = params["spx"] ?: "",
            flow        = params["flow"] ?: "",
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
            transportMode = params["mode"] ?: "",
            transportExtraJson = normalizeTransportExtra(params["extra"]),
            security  = when {
                params["security"].equals("tls", true) || params["tls"].equals("tls", true) -> Security.TLS
                params["security"].equals("reality", true) -> Security.REALITY
                params["security"].equals("xtls", true) -> Security.XTLS
                else -> Security.NONE
            },
            sni       = params["sni"] ?: "",
            fingerprint = params["fp"] ?: "chrome",
            alpn = params["alpn"] ?: "",
            publicKey = params["pbk"] ?: "",
            shortId = params["sid"] ?: "",
            spiderX = params["spx"] ?: "",
            flow = params["flow"] ?: "",
            allowInsecure = params["allowInsecure"] == "1" || params["allowInsecure"].equals("true", true),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private encoders
    // ─────────────────────────────────────────────────────────────────────────

    private fun encodeVmess(p: VpnProfile): String {
        val net = encodeTransport(p.transport)
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
            put("mode", p.transportMode); put("type", p.transportHeader)
            put("seed", p.transportSeed); put("quicSecurity", p.transportSecurity)
            put("key", p.transportKey)
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
            .appendTransportParameters(p)
            .appendQueryParameter("security", p.security.name.lowercase())
            .appendQueryParameter("sni", p.sni)
            .appendQueryParameter("fp", p.fingerprint)
            .appendQueryParameter("turbo", encodedOptions(p.alxTurboJson))
            .appendQueryParameter("alxSecurity", encodedOptions(p.alxSecurityJson))
            .appendQueryParameter("stealth", encodedOptions(p.alxStealthJson))
        if (p.security == Security.REALITY) {
            b.appendQueryParameter("pbk", p.publicKey)
                .appendQueryParameter("sid", p.shortId)
                .appendQueryParameter("spx", p.spiderX)
        }
        if (p.alpn.isNotBlank()) b.appendQueryParameter("alpn", p.alpn)
        return b.fragment(p.name).build().toString()
    }

    private fun normalizeOptionsJson(raw: String): String = runCatching {
        JSONObject(raw.ifBlank { "{}" }).toString()
    }.getOrDefault("{}")

    /**
     * XHTTP `extra` is transported as URI-escaped raw JSON by v2rayNG/Remnawave.
     * Some generators instead use Base64URL. Preserve a valid object exactly and
     * fail open to Xray defaults when a provider sends malformed optional tuning.
     */
    private fun normalizeTransportExtra(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank() || value == "null") return "{}"
        fun parse(candidate: String): String? = runCatching { JSONObject(candidate).toString() }.getOrNull()
        return parse(value)
            ?: decodeBase64OrNull(value)?.let(::parse)
            ?: "{}"
    }

    private fun encodeVless(p: VpnProfile): String {
        val b = Uri.Builder().scheme("vless")
            .encodedAuthority(buildEncodedAuthority(p.address, p.port, p.uuid))
            .appendTransportParameters(p)
            .appendQueryParameter("security", p.security.name.lowercase())
            .appendQueryParameter("sni", p.sni)
            .appendQueryParameter("fp", p.fingerprint)
        if (p.flow.isNotBlank()) b.appendQueryParameter("flow", p.flow)
        if (p.alpn.isNotBlank()) b.appendQueryParameter("alpn", p.alpn)
        if (p.security == Security.REALITY) {
            b.appendQueryParameter("pbk", p.publicKey)
                .appendQueryParameter("sid", p.shortId)
                .appendQueryParameter("spx", p.spiderX)
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
            .appendTransportParameters(p)
            .appendQueryParameter("security", p.security.name.lowercase())
            .appendQueryParameter("sni", p.sni)
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
