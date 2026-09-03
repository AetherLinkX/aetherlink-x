package com.palazik.vpn.service

import java.io.EOFException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.random.Random

/**
 * An end-to-end probe for the exact SOCKS inbound used by HEV.
 *
 * Unlike CoreController.measureDelay, this exercises the running Android libv2ray
 * instance, its selected outbound (including AetherLink X + REALITY), TCP payloads,
 * and SOCKS UDP ASSOCIATE.  It deliberately contains no credentials and never logs
 * generated Xray configuration.
 */
internal object TunnelDataPlaneProbe {
    data class Result(val tcpStatus: Int, val tcpBytes: Int, val dnsAnswers: Int)
    private data class SocksCommand(val socket: Socket, val bound: InetSocketAddress)

    fun run(socksPort: Int, timeoutMs: Int = 12_000): Result {
        val (tcpStatus, tcpBytes) = probeTcp(socksPort, timeoutMs)
        val dnsAnswers = probeUdpDns(socksPort, timeoutMs)
        return Result(tcpStatus, tcpBytes, dnsAnswers)
    }

    private fun probeTcp(socksPort: Int, timeoutMs: Int): Pair<Int, Int> {
        val host = "speed.cloudflare.com"
        val command = openSocksCommand(socksPort, command = 0x01, host = host, port = 443, timeoutMs)
        command.socket.use { plainSocket ->
            val tlsFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val tls = tlsFactory.createSocket(plainSocket, host, 443, false) as SSLSocket
            tls.use { socket ->
                socket.soTimeout = timeoutMs
                socket.useClientMode = true
                socket.sslParameters = socket.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                socket.startHandshake()
                val request = (
                    "GET /__down?bytes=32768 HTTP/1.1\r\n" +
                        "Host: $host\r\n" +
                        "Accept: application/octet-stream\r\n" +
                        "Connection: close\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII)
                socket.getOutputStream().apply { write(request); flush() }
                val input = socket.getInputStream()
                val statusLine = readAsciiLine(input, 256)
                val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                    ?: error("invalid HTTPS status through SOCKS")
                check(status in 200..299) { "HTTPS payload probe returned $status" }
                while (readAsciiLine(input, 8_192).isNotEmpty()) Unit
                val buffer = ByteArray(8_192)
                var received = 0
                while (received < 4_096) {
                    val count = input.read(buffer, 0, minOf(buffer.size, 4_096 - received))
                    if (count < 0) break
                    received += count
                }
                check(received >= 4_096) { "HTTPS payload probe received only $received bytes" }
                return status to received
            }
        }
    }

    private fun probeUdpDns(socksPort: Int, timeoutMs: Int): Int {
        val command = openSocksCommand(socksPort, command = 0x03, host = "0.0.0.0", port = 0, timeoutMs)
        command.socket.use {
            val relay = command.bound
            val relayAddress = if (relay.address.isAnyLocalAddress) {
                InetAddress.getLoopbackAddress()
            } else relay.address

            DatagramSocket().use { udp ->
                udp.soTimeout = timeoutMs
                val id = Random.nextInt(0, 65_536)
                val dns = buildDnsQuery(id, "cloudflare.com")
                val frame = ByteArray(10 + dns.size)
                // RSV(2), FRAG(1), ATYP(1), IPv4(4), PORT(2), DATA
                frame[3] = 0x01
                frame[4] = 1
                frame[5] = 1
                frame[6] = 1
                frame[7] = 1
                frame[8] = 0
                frame[9] = 53
                dns.copyInto(frame, 10)
                udp.send(DatagramPacket(frame, frame.size, relayAddress, relay.port))

                val response = ByteArray(8_192)
                val packet = DatagramPacket(response, response.size)
                udp.receive(packet)
                val payloadOffset = socksUdpPayloadOffset(response, packet.length)
                check(packet.length - payloadOffset >= 12) { "short DNS response through SOCKS" }
                val responseId = u16(response, payloadOffset)
                val flags = u16(response, payloadOffset + 2)
                val answers = u16(response, payloadOffset + 6)
                check(responseId == id) { "DNS transaction mismatch through SOCKS" }
                check(flags and 0x000f == 0) { "DNS rcode=${flags and 0x000f} through SOCKS" }
                return answers
            }
        }
    }

    private fun openSocksCommand(
        socksPort: Int,
        command: Int,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): SocksCommand {
        val socket = Socket()
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), socksPort), timeoutMs)
            val output = socket.getOutputStream()
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = readExact(socket.getInputStream(), 2)
            check(greeting.contentEquals(byteArrayOf(0x05, 0x00))) { "SOCKS authentication rejected" }

            val hostBytes = host.toByteArray(StandardCharsets.US_ASCII)
            val request = if (host == "0.0.0.0") {
                byteArrayOf(0x05, command.toByte(), 0x00, 0x01, 0, 0, 0, 0,
                    (port ushr 8).toByte(), port.toByte())
            } else {
                check(hostBytes.size <= 255)
                byteArrayOf(0x05, command.toByte(), 0x00, 0x03, hostBytes.size.toByte()) +
                    hostBytes + byteArrayOf((port ushr 8).toByte(), port.toByte())
            }
            output.write(request)
            output.flush()
            val head = readExact(socket.getInputStream(), 4)
            check(head[0] == 0x05.toByte() && head[1] == 0x00.toByte()) {
                "SOCKS command failed, reply=${head[1].toInt() and 0xff}"
            }
            val bound = readBoundAddress(socket.getInputStream(), head[3].toInt() and 0xff)
            return SocksCommand(socket, bound)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun readBoundAddress(input: InputStream, type: Int): InetSocketAddress {
        val address = when (type) {
            0x01 -> InetAddress.getByAddress(readExact(input, 4))
            0x04 -> InetAddress.getByAddress(readExact(input, 16))
            0x03 -> InetAddress.getByName(String(readExact(input, readExact(input, 1)[0].toInt() and 0xff)))
            else -> error("invalid SOCKS address type")
        }
        val port = u16(readExact(input, 2), 0)
        check(port in 1..65_535) { "invalid SOCKS UDP relay port" }
        return InetSocketAddress(address, port)
    }

    private fun socksUdpPayloadOffset(packet: ByteArray, size: Int): Int {
        check(size >= 4 && packet[0] == 0.toByte() && packet[1] == 0.toByte() && packet[2] == 0.toByte()) {
            "invalid SOCKS UDP response"
        }
        return when (packet[3].toInt() and 0xff) {
            0x01 -> 10
            0x04 -> 22
            0x03 -> 7 + (packet[4].toInt() and 0xff)
            else -> error("invalid SOCKS UDP address type")
        }.also { check(it <= size) { "short SOCKS UDP response" } }
    }

    private fun buildDnsQuery(id: Int, host: String): ByteArray {
        val labels = host.split('.').flatMap { label ->
            val bytes = label.toByteArray(StandardCharsets.US_ASCII)
            listOf(bytes.size.toByte()) + bytes.toList()
        }
        return byteArrayOf(
            (id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0x00, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        ) + labels.toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
    }

    private fun readAsciiLine(input: InputStream, max: Int): String {
        val bytes = ArrayList<Byte>()
        while (bytes.size < max) {
            val value = input.read()
            if (value < 0) throw EOFException("HTTP response ended before status")
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) throw EOFException("unexpected end of SOCKS response")
            offset += count
        }
        return result
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

}
