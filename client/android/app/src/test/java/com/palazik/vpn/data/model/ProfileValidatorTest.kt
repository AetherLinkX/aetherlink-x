package com.palazik.vpn.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileValidatorTest {
    @Test
    fun remnawaveFallbackEndpointIsNotAcceptedAsServer() {
        val fallback = VpnProfile(
            name = "Приложение не поддерживается",
            protocol = Protocol.VLESS,
            address = "0.0.0.0",
            port = 1,
            uuid = "00000000-0000-0000-0000-000000000000",
        )

        assertTrue(ProfileValidator.isProviderPlaceholder(fallback))
        assertTrue(ProfileValidator.validate(fallback).isNotEmpty())
    }

    @Test
    fun ordinaryEndpointIsNotMistakenForFallback() {
        assertFalse(
            ProfileValidator.isProviderPlaceholder(
                VpnProfile(address = "vpn.example.com", port = 443),
            ),
        )
    }
}
