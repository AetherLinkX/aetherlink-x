package com.palazik.vpn.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class RemnawaveCompatibilityTest {
    private val link = "aetherlink://token@example.com:8443?pin=abc&sni=example.com&mode=turbo#ALX"

    @Test
    fun `reads raw profile from headers regardless of case`() {
        assertEquals(
            listOf(link),
            RemnawaveCompatibility.nativeProfileLinks(mapOf("X-AetherLink-Profile" to listOf(link))),
        )
    }

    @Test
    fun `reads Remnawave base64 encoded response header`() {
        val encoded = Base64.getEncoder().encodeToString(link.toByteArray())
        assertEquals(
            listOf(link),
            RemnawaveCompatibility.nativeProfileLinks(mapOf("x-alx-profile" to listOf("base64:$encoded"))),
        )
    }

    @Test
    fun `accepts unpadded base64url and removes duplicate header aliases`() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(link.toByteArray())
        assertEquals(
            listOf(link),
            RemnawaveCompatibility.nativeProfileLinks(
                mapOf(
                    "X-AetherLink-Profile" to listOf("base64:$encoded"),
                    "X-ALX-Profile" to listOf(link),
                ),
            ),
        )
    }

    @Test
    fun `ignores malformed and unrelated values`() {
        assertEquals(
            emptyList<String>(),
            RemnawaveCompatibility.nativeProfileLinks(
                mapOf(
                    "X-AetherLink-Profile" to listOf("base64:not-valid-%"),
                    "Profile-Title" to listOf(link),
                ),
            ),
        )
    }
}
