package com.palazik.vpn.data.codec

import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.ProfileValidator
import com.palazik.vpn.data.model.Protocol
import com.palazik.vpn.service.XrayConfigBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StandardProtocolsCodecTest {
    private val uuid = "018f3f89-01be-7b44-8a7f-23e54f92ea00"

    @Test
    fun standardShareLinksStillParseAndBuildAfterAlxIntegration() {
        val vmessJson = """{"v":"2","ps":"VMess","add":"vm.example.com","port":"443","id":"$uuid","net":"ws","path":"/ws","host":"cdn.example.com","tls":"tls"}"""
        val vmess = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val ssUser = java.util.Base64.getEncoder()
            .encodeToString("chacha20-ietf-poly1305:test-password".toByteArray())
        val links = listOf(
            "vless://$uuid@vl.example.com:443?type=ws&security=tls&path=%2Fws&host=cdn.example.com#VLESS",
            "vmess://$vmess",
            "ss://$ssUser@ss.example.com:8388#SS",
            "trojan://test-password@tr.example.com:443?security=tls&sni=tr.example.com#Trojan",
            "hysteria2://test-password@hy.example.com:443?sni=hy.example.com#Hysteria2",
            "tuic://$uuid:test-password@tuic.example.com:443?sni=tuic.example.com#TUIC",
            "anytls://test-password@any.example.com:443?security=tls&sni=any.example.com#AnyTLS",
            "socks5://user:password@socks.example.com:1080#SOCKS5",
            "httpproxy://user:password@http.example.com:8080#HTTP",
            "wireguard://wg.example.com:51820?address=10.0.0.2%2F32&privatekey=private&publickey=public&endpoint=wg.example.com%3A51820#WireGuard",
        )

        val profiles = links.map { link -> requireNotNull(ProfileCodec.decode(link)) }
        assertEquals(
            setOf(Protocol.VLESS, Protocol.VMESS, Protocol.SHADOWSOCKS, Protocol.TROJAN,
                Protocol.HYSTERIA2, Protocol.TUIC, Protocol.ANYTLS, Protocol.SOCKS5,
                Protocol.HTTP, Protocol.WIREGUARD),
            profiles.map { it.protocol }.toSet(),
        )
        profiles.forEach { profile ->
            assertTrue(ProfileValidator.validate(profile).isEmpty(), "${profile.protocol}: ${ProfileValidator.validate(profile)}")
            val config = XrayConfigBuilder.build(profile, AppSettings())
            assertTrue(config.contains("\"tag\": \"proxy\""), profile.protocol.name)
            assertEquals(profile.protocol, requireNotNull(ProfileCodec.decode(ProfileCodec.encodeNative(profile))).protocol)
        }
    }
}
