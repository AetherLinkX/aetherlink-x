package com.palazik.vpn.service

import com.palazik.vpn.data.model.Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

class XrayProtocolBaselineTest {
    @Test
    fun `aetherlink x uses unchanged vless core protocol`() {
        assertEquals("vless", XrayConfigBuilder.coreProtocolName(Protocol.AETHERLINK_X))
        assertEquals("vless", XrayConfigBuilder.coreProtocolName(Protocol.VLESS))
    }
}
