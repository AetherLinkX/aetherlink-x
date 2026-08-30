package com.palazik.vpn.data.codec

import com.palazik.vpn.data.model.AppSettings
import com.palazik.vpn.data.model.ProfileValidator
import com.palazik.vpn.data.model.Protocol
import com.palazik.vpn.data.model.Security
import com.palazik.vpn.service.XrayConfigBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AetherLinkXCodecTest {

    @Test
    fun remnawaveLinkRoundTripsAndBuildsOutbound() {
        fun option(value: String) = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray())

        val link = "aetherlinkx://018f3f89-01be-7b44-8a7f-23e54f92ea00@alx.example.com:8443" +
            "?secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "&allowInsecureTransport=0&type=raw&security=reality" +
            "&sni=www.example.com&fp=chrome&pbk=public-key&sid=0123456789abcdef" +
            "&turbo=${option("""{"enabled":true,"maxDatagramAgeMs":35}""")}" +
            "&alxSecurity=${option("""{"pqMode":"prefer","innerAead":true}""")}" +
            "&stealth=${option("""{"enabled":true,"maxPaddingBytes":32}""")}" +
            "#AetherLink%20X%20FI"

        val profile = requireNotNull(ProfileCodec.decode(link))
        assertEquals(Protocol.AETHERLINK_X, profile.protocol)
        assertEquals(Security.REALITY, profile.security)
        assertEquals("AetherLink X FI", profile.name)
        assertTrue(ProfileValidator.validate(profile).isEmpty())

        val roundTrip = requireNotNull(ProfileCodec.decode(ProfileCodec.encodeNative(profile)))
        assertEquals(profile.alxSecret, roundTrip.alxSecret)
        assertEquals(profile.alxTurboJson, roundTrip.alxTurboJson)
        assertEquals(profile.alxSecurityJson, roundTrip.alxSecurityJson)

        val config = XrayConfigBuilder.build(profile, AppSettings())
        assertTrue(config.contains("\"protocol\": \"aetherlinkx\""))
        assertTrue(config.contains("\"pqMode\": \"prefer\""))
        assertTrue(config.contains("\"maxDatagramAgeMs\": 35"))
    }
}
