package com.palazik.vpn.service

import com.palazik.vpn.data.model.Protocol
import com.palazik.vpn.data.model.VpnProfile
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Resolves the ALX transport endpoint before Android establishes the TUN.
 *
 * The protocol server is itself the path used to carry later DNS requests.
 * Leaving its address as a hostname makes every new REALITY connection depend
 * on a resolver while the VPN is already capturing traffic. On affected
 * devices this produced a cancellation cascade: Process() calls increased,
 * but almost none reached dial_ok. The original hostname is retained as SNI,
 * so REALITY authentication and camouflage are unchanged.
 */
internal object AetherLinkXEndpointBootstrap {
    fun resolve(
        profile: VpnProfile,
        enableIpv6: Boolean,
        lookup: (String) -> Array<InetAddress> = InetAddress::getAllByName,
    ): VpnProfile {
        if (profile.protocol != Protocol.AETHERLINK_X || isIpLiteral(profile.address)) {
            return profile
        }

        val candidates = lookup(profile.address).filterNot { it.isAnyLocalAddress }
        val selected = if (enableIpv6) {
            candidates.firstOrNull { it is Inet6Address }
                ?: candidates.firstOrNull { it is Inet4Address }
        } else {
            candidates.firstOrNull { it is Inet4Address }
                ?: candidates.firstOrNull { it is Inet6Address }
        } ?: throw IllegalStateException("DNS не вернул адрес сервера ${profile.address}")

        return profile.copy(
            address = selected.hostAddress.substringBefore('%'),
            sni = profile.sni.ifBlank { profile.address },
        )
    }

    internal fun isIpLiteral(value: String): Boolean {
        val host = value.trim().removePrefix("[").removeSuffix("]")
        if (host.contains(':')) return host.isNotEmpty()
        val parts = host.split('.')
        return parts.size == 4 && parts.all { part ->
            val number = part.toIntOrNull()
            part.isNotEmpty() && part.length <= 3 && number != null && number in 0..255
        }
    }
}
