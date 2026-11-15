package com.palazik.vpn.data.repository

import com.palazik.vpn.data.model.Protocol
import com.palazik.vpn.data.model.VpnProfile
import org.junit.Assert.*
import org.junit.Test

class SubscriptionFormatMergerTest {
    private val ordinary = VpnProfile(name = "FI", protocol = Protocol.VLESS, address = "fi.example", port = 443)
    private val native = ordinary.copy(name = "ALX", protocol = Protocol.AETHERLINK_NATIVE)
    private val bypass = ordinary.copy(name = "FI bypass", subscriptionRoutingJson = "{\"rules\":[]}")

    @Test fun `equal size JSON adds bypass while retaining native ALX`() {
        val result = SubscriptionFormatMerger.merge(listOf(ordinary, native), listOf(ordinary, bypass))
        assertEquals(listOf("FI", "ALX", "FI bypass"), result.map { it.name })
        assertEquals(1, result.count { it.protocol == Protocol.AETHERLINK_NATIVE })
    }

    @Test fun `smaller JSON still supplies routing and locations`() {
        val policy = ordinary.copy(subscriptionRoutingJson = "{\"rules\":[]}", uuid = "rotated")
        val result = SubscriptionFormatMerger.merge(listOf(ordinary, native, native.copy(name = "ALX 2")), listOf(policy))
        assertEquals(3, result.size)
        assertEquals("rotated", result.first().uuid)
        assertEquals(policy.subscriptionRoutingJson, result.first().subscriptionRoutingJson)
    }

    @Test fun `provider duplicates are matched by occurrence rather than removed`() {
        val raw = listOf(ordinary, ordinary.copy(id = "second"))
        val json = listOf(ordinary.copy(id = "json-1"), ordinary.copy(id = "json-2"), ordinary.copy(id = "json-3"))
        assertEquals(listOf("json-1", "json-2", "json-3"), SubscriptionFormatMerger.merge(raw, json).map { it.id })
        assertEquals(2, SubscriptionFormatMerger.merge(raw, json.take(1)).size)
    }

    @Test fun `failed JSON fetch cannot erase the raw subscription`() {
        assertEquals(listOf(ordinary, native), SubscriptionFormatMerger.merge(listOf(ordinary, native), emptyList()))
    }
}
