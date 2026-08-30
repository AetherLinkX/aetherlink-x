package com.palazik.vpn.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProxyEndpointTest {
    @Test
    fun allocatedPortCanBePublishedAndClearedByOwner() {
        val port = LocalProxyEndpoint.allocate()
        assertTrue(port in 1..65535)
        LocalProxyEndpoint.publish(port)
        assertEquals(port, LocalProxyEndpoint.port)
        assertNotNull(LocalProxyEndpoint.proxyOrNull())
        LocalProxyEndpoint.clear(port + 1)
        assertEquals(port, LocalProxyEndpoint.port)
        LocalProxyEndpoint.clear(port)
        assertEquals(0, LocalProxyEndpoint.port)
    }
}
