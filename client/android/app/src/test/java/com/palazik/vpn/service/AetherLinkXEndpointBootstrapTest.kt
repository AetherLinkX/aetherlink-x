package com.palazik.vpn.service

import com.palazik.vpn.data.model.Protocol
import com.palazik.vpn.data.model.Security
import com.palazik.vpn.data.model.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class AetherLinkXEndpointBootstrapTest {
    @Test
    fun resolvesAlxDomainToIpv4AndPreservesDomainAsSni() {
        val profile = VpnProfile(
            protocol = Protocol.AETHERLINK_X,
            address = "edge.example.com",
            security = Security.REALITY,
            sni = "",
        )

        val resolved = AetherLinkXEndpointBootstrap.resolve(profile, enableIpv6 = false) {
            arrayOf(
                InetAddress.getByName("2001:db8::10"),
                InetAddress.getByName("203.0.113.10"),
            )
        }

        assertEquals("203.0.113.10", resolved.address)
        assertEquals("edge.example.com", resolved.sni)
    }

    @Test
    fun preservesExplicitRealityServerName() {
        val profile = VpnProfile(
            protocol = Protocol.AETHERLINK_X,
            address = "edge.example.com",
            security = Security.REALITY,
            sni = "cover.example.net",
        )

        val resolved = AetherLinkXEndpointBootstrap.resolve(profile, enableIpv6 = false) {
            arrayOf(InetAddress.getByName("203.0.113.20"))
        }

        assertEquals("203.0.113.20", resolved.address)
        assertEquals("cover.example.net", resolved.sni)
    }

    @Test
    fun leavesIpAndNonAlxProfilesUntouchedWithoutLookup() {
        val ipProfile = VpnProfile(protocol = Protocol.AETHERLINK_X, address = "198.51.100.7")
        val vless = VpnProfile(protocol = Protocol.VLESS, address = "edge.example.com")
        val forbiddenLookup: (String) -> Array<InetAddress> = { error("lookup must not run") }

        assertSame(ipProfile, AetherLinkXEndpointBootstrap.resolve(ipProfile, false, forbiddenLookup))
        assertSame(vless, AetherLinkXEndpointBootstrap.resolve(vless, false, forbiddenLookup))
    }

    @Test
    fun detectsNumericAddressesOnly() {
        assertTrue(AetherLinkXEndpointBootstrap.isIpLiteral("192.0.2.1"))
        assertTrue(AetherLinkXEndpointBootstrap.isIpLiteral("[2001:db8::1]"))
        assertFalse(AetherLinkXEndpointBootstrap.isIpLiteral("999.0.0.1"))
        assertFalse(AetherLinkXEndpointBootstrap.isIpLiteral("edge.example.com"))
    }
}
