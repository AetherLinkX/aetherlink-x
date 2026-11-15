package com.palazik.vpn.service

import com.palazik.vpn.data.codec.ProfileCodec
import com.palazik.vpn.data.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubscriptionRoutingTest {
    private fun subscription() = JSONObject("""{
      "remarks":"FI bypass",
      "dns":{"tag":"dns_proxy","queryStrategy":"UseIPv4","servers":[
        {"address":"77.88.8.8","domains":["domain:ozon.ru"],"skipFallback":true},"1.1.1.1"]},
      "inbounds":[{"protocol":"socks","listen":"0.0.0.0","port":12345}],
      "outbounds":[
        {"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"fi.example","port":443,
          "users":[{"id":"b13dd989-61d7-498c-afcf-1d097836e13e","encryption":"none"}]}]}},
        {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
      "routing":{"domainStrategy":"IPIfNonMatch","rules":[
        {"type":"field","domain":["domain:ozon.ru","domain:wildberries.ru","domain:gosuslugi.ru"],"outboundTag":"direct"},
        {"type":"field","ip":["77.88.8.8"],"port":"53","outboundTag":"direct"},
        {"type":"field","inboundTag":["dns_proxy"],"outboundTag":"proxy"},
        {"type":"field","network":"tcp,udp","outboundTag":"proxy"}]}
    }""")

    @Test fun `JSON import retains policy and runtime keeps private Android listeners`() {
        val root = subscription()
        val profile = ProfileCodec.decodeSubscriptionBody(root.toString()).single()
        assertEquals(root.getJSONObject("routing").toString(), profile.subscriptionRoutingJson)
        assertEquals(root.getJSONObject("dns").toString(), profile.subscriptionDnsJson)
        val runtime = JSONObject(XrayConfigBuilder.build(profile, localSocksPort = 23456, includeHttpInbound = false))
        val inbound = runtime.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(23456, inbound.getInt("port"))
        assertEquals(1, runtime.getJSONArray("inbounds").length())
        val rules = runtime.getJSONObject("routing").getJSONArray("rules")
        assertEquals("dns-out", rules.getJSONObject(0).getString("outboundTag"))
        val direct = (0 until rules.length()).map { rules.getJSONObject(it) }
            .single { it.optJSONArray("domain")?.toString()?.contains("ozon.ru") == true }
        assertEquals("direct", direct.getString("outboundTag"))
        assertEquals("proxy", rules.getJSONObject(rules.length() - 1).getString("outboundTag"))
        assertEquals(root.getJSONObject("dns").toString(), runtime.getJSONObject("dns").toString())
    }

    @Test fun `switching to an ordinary profile does not reuse subscription bypass`() {
        val imported = ProfileCodec.decodeSubscriptionBody(subscription().toString()).single()
        val settings = AppSettings(routingMode = RoutingMode.GLOBAL)
        XrayConfigBuilder.build(imported, settings)
        val ordinary = imported.copy(subscriptionRoutingJson = "", subscriptionDnsJson = "")
        val rules = JSONObject(XrayConfigBuilder.build(ordinary, settings)).getJSONObject("routing").getJSONArray("rules")
        assertFalse(rules.toString().contains("ozon.ru"))
        assertFalse(rules.toString().contains("77.88.8.8"))
        assertEquals(RoutingMode.GLOBAL, settings.routingMode)
    }

    @Test fun `real provider DNS does not inherit the global FakeDNS pool`() {
        val profile = ProfileCodec.decodeSubscriptionBody(subscription().toString()).single()
        val runtime = JSONObject(XrayConfigBuilder.build(profile, AppSettings(enableFakeDns = true)))
        assertFalse(runtime.has("fakedns"))
        assertFalse(runtime.getJSONArray("inbounds").getJSONObject(0).getJSONObject("sniffing")
            .getJSONArray("destOverride").toString().contains("fakedns"))
    }

    @Test fun `legacy invalid panel DNS strategy is corrected before core startup`() {
        val root = subscription()
        root.getJSONObject("dns").put("queryStrategy", "IPIfNonMatch")
        val profile = ProfileCodec.decodeSubscriptionBody(root.toString()).single()
        val runtime = JSONObject(XrayConfigBuilder.build(profile))
        assertEquals("UseIPv4", runtime.getJSONObject("dns").getString("queryStrategy"))
    }

    @Test fun `multi proxy routing is not assigned to disconnected single outbounds`() {
        val root = subscription()
        val second = JSONObject(root.getJSONArray("outbounds").getJSONObject(0).toString()).put("tag", "proxy2")
        root.getJSONArray("outbounds").put(second)
        val profiles = ProfileCodec.decodeSubscriptionBody(root.toString())
        assertEquals(2, profiles.size)
        assertTrue(profiles.all { it.subscriptionRoutingJson.isBlank() })
    }
}
