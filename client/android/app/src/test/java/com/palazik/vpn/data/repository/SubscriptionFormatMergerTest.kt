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
        assertEquals(listOf("FI", "FI bypass", "ALX"), result.map { it.name })
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

    @Test fun `JSON only locations stay between their provider neighbours`() {
        val nl = ordinary.copy(name = "NL")
        val nlBypass = bypass.copy(name = "NL bypass")
        val result = SubscriptionFormatMerger.merge(
            listOf(ordinary, native, nl), listOf(ordinary, bypass, nlBypass, nl),
        )
        assertEquals(listOf("FI", "FI bypass", "NL bypass", "ALX", "NL"), result.map { it.name })
        assertEquals(listOf("FI", "FI bypass", "NL bypass", "NL"),
            result.filter { it.protocol != Protocol.AETHERLINK_NATIVE }.map { it.name })
    }

    @Test fun `JSON provider reorder supersedes the previous raw order`() {
        val nl = ordinary.copy(name = "NL")
        val result = SubscriptionFormatMerger.merge(listOf(ordinary, nl), listOf(nl, bypass, ordinary))
        assertEquals(listOf("NL", "FI bypass", "FI"), result.map { it.name })
    }

    @Test fun `interleaved provider duplicates keep their JSON positions`() {
        val nl = ordinary.copy(name = "NL")
        val json = listOf(ordinary.copy(id = "first"), nl, ordinary.copy(id = "second"), bypass)
        val result = SubscriptionFormatMerger.merge(listOf(ordinary, ordinary, nl), json)
        assertEquals(json, result)
    }

    @Test fun `native profiles carried in headers do not reorder the merged list`() {
        val nl = ordinary.copy(name = "NL")
        val ordered = listOf(ordinary, bypass, native, nl)
        assertEquals(ordered, SubscriptionFormatMerger.merge(ordered, listOf(native)))
    }
}
