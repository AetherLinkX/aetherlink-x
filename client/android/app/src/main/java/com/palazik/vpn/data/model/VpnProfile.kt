package com.palazik.vpn.data.model

import java.util.UUID

enum class Protocol {
    AETHERLINK_X, VMESS, VLESS, SHADOWSOCKS, TROJAN,
    HYSTERIA2, WIREGUARD, SOCKS5, HTTP,
    TUIC, ANYTLS
    // XHTTP removed — it is a Transport, not a Protocol (use VLESS + Transport.XHTTP)
    // REALITY removed — it is a Security layer, not a Protocol (use Security.REALITY)
}

/**
 * Wire transports understood by the upstream Xray core.
 *
 * H2 and QUIC are retained for old subscription links. Modern Xray removed the
 * legacy HTTP/2 and QUIC transports; the client migrates them to XHTTP over H2
 * and H3 respectively when it builds the runtime configuration.
 */
enum class Transport {
    TCP, WS, GRPC, XHTTP, HTTP_UPGRADE, KCP, HYSTERIA,
    H2, QUIC,
}
enum class Security  { NONE, TLS, REALITY, XTLS }

data class VpnProfile(
    val id: String        = UUID.randomUUID().toString(),
    val name: String      = "Unnamed",
    val protocol: Protocol= Protocol.VMESS,

    // ── common ───────────────────────────────────────────────────────────────
    val address: String   = "",
    val port: Int         = 443,
    val uuid: String      = "",       // vmess / vless / trojan password

    // ── transport ────────────────────────────────────────────────────────────
    val transport: Transport = Transport.TCP,
    val path: String      = "/",
    val host: String      = "",
    val transportMode: String = "",       // XHTTP: auto / packet-up / stream-up / stream-one
    val transportExtraJson: String = "{}", // XHTTP: padding, xmux and upload tuning
    val transportHeader: String = "none", // mKCP/legacy QUIC header type
    val transportSeed: String = "",       // mKCP seed
    val transportSecurity: String = "none", // legacy QUIC packet cipher
    val transportKey: String = "",        // legacy QUIC packet key

    // ── security ─────────────────────────────────────────────────────────────
    val security: Security   = Security.TLS,
    val sni: String       = "",
    val fingerprint: String = "chrome",
    val alpn: String       = "",       // comma-separated TLS ALPN values from the share link
    val publicKey: String = "",       // reality / wireguard
    val shortId: String   = "",       // reality
    val spiderX: String   = "",       // REALITY spider path
    val allowInsecure: Boolean = false, // skip TLS cert verification (self-signed servers)

    // ── VLESS ───────────────────────────────────────────────────────────────
    val flow: String      = "",       // preserve the server-provided flow; never invent Vision

    // ── VMess ────────────────────────────────────────────────────────────────
    val vmessSecurity: String = "auto", // vmess cipher: auto / aes-128-gcm / chacha20-poly1305 / none

    // ── Shadowsocks ──────────────────────────────────────────────────────────
    val ssMethod: String  = "chacha20-ietf-poly1305",
    val ssPassword: String= "",

    // ── WireGuard ────────────────────────────────────────────────────────────
    val wgPrivateKey: String = "",
    val wgPeerPublicKey: String = "",
    val wgPreSharedKey: String = "",
    val wgEndpoint: String = "",
    val wgDns: String = "1.1.1.1",
    val wgMtu: Int = 1280,
    val wgReserved: String = "",      // 3-byte "reserved" for Cloudflare WARP, e.g. "12,34,56"

    // ── Hysteria2 ────────────────────────────────────────────────────────────
    val hystPassword: String = "",
    val hystObfs: String = "",
    val hystObfsPassword: String = "",

    // ── per-profile overrides (#22) ───────────────────────────────────────────
    val muxEnabled: Boolean = true,        // force-disable mux when false (auto-off still applies)
    val fragmentEnabled: Boolean = false,  // route this profile through the TLS-fragment dialer (#23)

    // ── metadata ─────────────────────────────────────────────────────────────
    val subscriptionId: String? = null,
    val latencyMs: Long   = -1L,
    val lastTested: Long  = 0L,         // epoch millis of last latency test (0 = never)
    val isActive: Boolean = false,
    val addedAt: Long     = System.currentTimeMillis(),
)
