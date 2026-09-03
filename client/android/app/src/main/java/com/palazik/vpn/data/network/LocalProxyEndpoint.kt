package com.palazik.vpn.data.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/** Runtime address of Xray's private SOCKS listener. */
object LocalProxyEndpoint {
    private val activePort = AtomicInteger(0)
    /** Numeric IPv4 loopback used by Xray's IPv4-only private inbounds. */
    val ipv4Loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    val port: Int get() = activePort.get()

    fun publish(port: Int) {
        require(port in 1..65535)
        activePort.set(port)
    }

    fun clear(port: Int) {
        activePort.compareAndSet(port, 0)
    }

    fun proxyOrNull(): Proxy? = port.takeIf { it > 0 }?.let {
        Proxy(Proxy.Type.SOCKS, InetSocketAddress(ipv4Loopback, it))
    }

    fun allocate(): Int = ServerSocket(0, 1, ipv4Loopback).use { it.localPort }
}
