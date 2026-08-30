package com.palazik.vpn.data.model

object ProfileValidator {
    private val uuidRegex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private val shadowsocksMethods = setOf(
        "aes-128-gcm",
        "aes-256-gcm",
        "chacha20-poly1305",
        "chacha20-ietf-poly1305",
        "xchacha20-ietf-poly1305",
        "2022-blake3-aes-128-gcm",
        "2022-blake3-aes-256-gcm",
        "2022-blake3-chacha20-poly1305",
    )

    private fun isValidAetherLinkSecret(value: String): Boolean = runCatching {
        val raw = value.trim()
        val padded = raw.padEnd(raw.length + (4 - raw.length % 4) % 4, '=')
        java.util.Base64.getUrlDecoder().decode(padded)
    }.getOrNull()?.let { decoded -> decoded.size == 32 && decoded.any { it.toInt() != 0 } } == true

    fun validate(profile: VpnProfile): List<String> {
        val errors = mutableListOf<String>()

        if (profile.name.isBlank()) errors += "Profile name is required"
        if (profile.address.isBlank()) errors += "Server address is required"
        if (profile.port !in 1..65535) errors += "Port must be between 1 and 65535"

        when (profile.protocol) {
            Protocol.AETHERLINK_X -> {
                if (!uuidRegex.matches(profile.uuid)) errors += "AetherLink X requires a valid UUID"
                if (!isValidAetherLinkSecret(profile.alxSecret)) {
                    errors += "AetherLink X secret must be 32 non-zero bytes encoded as Base64URL"
                }
                if (!profile.alxAllowInsecureTransport && profile.security == Security.NONE) {
                    errors += "AetherLink X requires TLS/REALITY unless insecure transport is explicitly enabled"
                }
                if (profile.security == Security.REALITY && profile.publicKey.isBlank()) {
                    errors += "AetherLink X REALITY requires a public key"
                }
            }
            Protocol.VMESS, Protocol.VLESS -> {
                if (!uuidRegex.matches(profile.uuid)) errors += "${profile.protocol.name} requires a valid UUID"
                if (profile.security == Security.REALITY && profile.publicKey.isBlank()) {
                    errors += "Reality requires a public key"
                }
            }
            Protocol.TROJAN -> {
                if (profile.uuid.isBlank()) errors += "Trojan password is required"
            }
            Protocol.SHADOWSOCKS -> {
                if (profile.ssMethod.isBlank()) errors += "Shadowsocks cipher is required"
                if (profile.ssPassword.isBlank()) errors += "Shadowsocks password is required"
                if (profile.ssMethod.isNotBlank() && profile.ssMethod !in shadowsocksMethods) {
                    errors += "Unsupported Shadowsocks cipher: ${profile.ssMethod}"
                }
            }
            Protocol.HYSTERIA2 -> {
                if (profile.hystPassword.isBlank()) errors += "Hysteria2 password is required"
            }
            Protocol.WIREGUARD -> {
                if (profile.wgPrivateKey.isBlank()) errors += "WireGuard private key is required"
                if (profile.wgPeerPublicKey.isBlank()) errors += "WireGuard peer public key is required"
                if (profile.wgEndpoint.isBlank() && profile.address.isBlank()) {
                    errors += "WireGuard endpoint is required"
                }
            }
            Protocol.SOCKS5 -> Unit
            Protocol.TUIC -> {
                if (!uuidRegex.matches(profile.uuid)) errors += "TUIC requires a valid UUID"
                if (profile.ssPassword.isBlank()) errors += "TUIC password is required"
            }
            Protocol.HTTP -> Unit
            Protocol.ANYTLS -> {
                if (profile.uuid.isBlank()) errors += "AnyTLS password is required"
            }
        }

        return errors
    }
}
