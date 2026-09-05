package com.palazik.vpn.data.repository

import com.palazik.vpn.data.model.Protocol
import com.palazik.vpn.data.model.Security
import com.palazik.vpn.data.model.VpnProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionProfileMergerTest {
    @Test
    fun `rotated reality credentials replace values but retain active profile id`() {
        val old = profile(id = "stable-id", publicKey = "old-key", shortId = "old-sid", active = true)
        val fresh = profile(id = "random-download-id", publicKey = "fresh-key", shortId = "fresh-sid")

        val merged = SubscriptionProfileMerger.merge(listOf(fresh), listOf(old)).single()

        assertEquals("stable-id", merged.id)
        assertEquals("fresh-key", merged.publicKey)
        assertEquals("fresh-sid", merged.shortId)
        assertTrue(merged.isActive)
    }

    @Test
    fun `duplicate locations are retained and reconciled one for one`() {
        val previous = listOf(
            profile(id = "first", publicKey = "old-1"),
            profile(id = "second", publicKey = "old-2", active = true),
        )
        val fresh = listOf(
            profile(id = "new-1", publicKey = "fresh-1"),
            profile(id = "new-2", publicKey = "fresh-2"),
        )

        val merged = SubscriptionProfileMerger.merge(fresh, previous)

        assertEquals(listOf("first", "second"), merged.map { it.id })
        assertEquals(listOf("fresh-1", "fresh-2"), merged.map { it.publicKey })
        assertEquals(listOf(false, true), merged.map { it.isActive })
    }

    private fun profile(
        id: String,
        publicKey: String,
        shortId: String = "sid",
        active: Boolean = false,
    ) = VpnProfile(
        id = id,
        name = "AetherLink X FI",
        protocol = Protocol.AETHERLINK_X,
        address = "saf.sinfor.fun",
        port = 8443,
        security = Security.REALITY,
        publicKey = publicKey,
        shortId = shortId,
        isActive = active,
    )
}
