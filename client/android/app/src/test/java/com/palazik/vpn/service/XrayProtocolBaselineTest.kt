package com.palazik.vpn.service

import com.palazik.vpn.data.model.Protocol
import com.palazik.vpn.data.model.Security
import com.palazik.vpn.data.model.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayProtocolBaselineTest {
    @Test
    fun `aetherlink x uses unchanged vless core protocol`() {
        assertEquals("vless", XrayConfigBuilder.coreProtocolName(Protocol.AETHERLINK_X))
        assertEquals("vless", XrayConfigBuilder.coreProtocolName(Protocol.VLESS))
    }

    @Test
    fun `aetherlink x replaces only broken chrome reality preset`() {
        val profile = VpnProfile(
            protocol = Protocol.AETHERLINK_X,
            security = Security.REALITY,
            fingerprint = "chrome",
        )

        assertEquals("safari", XrayConfigBuilder.effectiveRealityFingerprint(profile))
        assertEquals(
            "firefox",
            XrayConfigBuilder.effectiveRealityFingerprint(profile.copy(fingerprint = "firefox")),
        )
    }

    @Test
    fun `ordinary vless preserves subscription fingerprint`() {
        val profile = VpnProfile(
            protocol = Protocol.VLESS,
            security = Security.REALITY,
            fingerprint = "chrome",
        )

        assertEquals("chrome", XrayConfigBuilder.effectiveRealityFingerprint(profile))
    }
}
