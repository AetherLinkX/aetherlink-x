package com.palazik.vpn.service

import com.palazik.vpn.data.model.*
import org.json.JSONArray
import org.json.JSONObject

object XrayConfigBuilder {

    fun build(profile: VpnProfile, settings: AppSettings = AppSettings()): String =
        JSONObject().apply {
            put("log",       buildLog())
            put("dns",       buildDns(settings))
            // FakeDNS pool — speeds up routing and avoids real DNS leaks (#6)
            if (settings.enableFakeDns) {
                put("fakedns", JSONArray().apply {
                    put(JSONObject().apply {
                        put("ipPool",   "198.18.0.0/15")
                        put("poolSize", 65535)
                    })
                })
            }
            put("inbounds",  buildInbounds(settings))
            put("outbounds", buildOutbounds(profile, settings))
            put("routing",   buildRouting(settings))
            put("stats",     JSONObject())
            put("policy",    buildPolicy())
        }.toString(2)

    // ── Log ───────────────────────────────────────────────────────────────────

    private fun buildLog() = JSONObject().apply {
        put("loglevel", "warning")
    }

    // ── Inbounds ──────────────────────────────────────────────────────────────

    // Mirrors v2ray_config_with_tun.json from v2rayNG assets.
    // TUN inbound must be present for xray to process packets from the tunFd
    // passed to startLoop(). Without it xray ignores the fd entirely.
    private fun buildInbounds(settings: AppSettings) = JSONArray().apply {
        // FakeDNS must be the first destOverride entry so sniffed connections map back
        // to their real domain before http/tls override (v2rayNG ordering).
        fun sniffing() = JSONObject().apply {
            put("enabled", true)
            put("destOverride", JSONArray().apply {
                if (settings.enableFakeDns) put("fakedns")
                put("http"); put("tls")
            })
        }
        put(JSONObject().apply {
            put("tag", "tun")
            put("protocol", "tun")
            put("settings", JSONObject().apply {
                put("name", "xray0")
                put("mtu", 1500)
                put("userLevel", 8)
            })
            put("sniffing", sniffing())
        })
        put(JSONObject().apply {
            put("tag", "socks")
            put("port", 10808)
            put("listen", "127.0.0.1")
            put("protocol", "socks")
            put("settings", JSONObject().apply {
                put("auth", "noauth")
                put("udp", true)
                put("userLevel", 8)
            })
            put("sniffing", sniffing())
        })
        put(JSONObject().apply {
            put("tag", "http")
            put("port", 10809)
            put("listen", "127.0.0.1")
            put("protocol", "http")
            put("settings", JSONObject().apply { put("userLevel", 8) })
        })
    }

    // ── Outbounds ─────────────────────────────────────────────────────────────

    private fun buildOutbounds(profile: VpnProfile, settings: AppSettings) = JSONArray().apply {
        put(buildProxyOutbound(profile, settings))
        // Anti-DPI fragment dialer (#23) — the proxy outbound chains through this via
        // sockopt.dialerProxy when the profile opts in. Only added when needed.
        if (profile.fragmentEnabled) {
            put(JSONObject().apply {
                put("tag", "fragment")
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("domainStrategy", "AsIs")
                    put("fragment", JSONObject().apply {
                        put("packets",  settings.fragmentPackets.ifBlank { "tlshello" })
                        put("length",   settings.fragmentLength.ifBlank { "100-200" })
                        put("interval", settings.fragmentInterval.ifBlank { "10-20" })
                    })
                })
                put("streamSettings", JSONObject().apply {
                    put("sockopt", JSONObject().apply { put("tcpNoDelay", true) })
                })
            })
        }
        put(JSONObject().apply {
            put("tag", "direct")
            put("protocol", "freedom")
            // UseIP allows IPv6 dialling when the user opted into IPv6; otherwise force IPv4.
            put("settings", JSONObject().apply {
                put("domainStrategy", if (settings.enableIpv6) "UseIP" else "UseIPv4")
            })
        })
        put(JSONObject().apply {
            put("tag", "block")
            put("protocol", "blackhole")
            put("settings", JSONObject().apply {
                put("response", JSONObject().apply { put("type", "http") })
            })
        })
        // Required for TUN mode: intercepts DNS queries from TUN and resolves them
        put(JSONObject().apply {
            put("tag", "dns-out")
            put("protocol", "dns")
        })
    }

    private fun buildProxyOutbound(profile: VpnProfile, settings: AppSettings) = JSONObject().apply {
        put("tag", "proxy")

        when (profile.protocol) {
            Protocol.AETHERLINK_X -> buildAetherLinkX(this, profile)
            Protocol.VLESS       -> buildVless(this, profile)
            Protocol.VMESS       -> buildVmess(this, profile)
            Protocol.SHADOWSOCKS -> buildShadowsocks(this, profile)
            Protocol.TROJAN      -> buildTrojan(this, profile)
            Protocol.HYSTERIA2   -> buildHysteria2(this, profile)
            Protocol.WIREGUARD   -> buildWireguard(this, profile)
            Protocol.SOCKS5      -> buildSocksOut(this, profile)
            Protocol.HTTP        -> buildHttpOut(this, profile)
            Protocol.TUIC        -> buildTuic(this, profile)
            Protocol.ANYTLS      -> buildAnyTls(this, profile)
        }

        // SS handles its own framing; streamSettings causes TLS handshake against plain SS servers
        val needsStream = profile.protocol !in listOf(
            Protocol.HYSTERIA2, Protocol.WIREGUARD, Protocol.SHADOWSOCKS, Protocol.HTTP
        )
        if (needsStream) {
            val stream = buildStreamSettings(profile)
            // Chain through the fragment dialer for anti-DPI (#22/#23). Only meaningful
            // for TCP-based transports; QUIC/UDP transports ignore TCP fragmentation.
            if (profile.fragmentEnabled && profile.transport != Transport.QUIC) {
                stream.put("sockopt", JSONObject().apply { put("dialerProxy", "fragment") })
            }
            put("streamSettings", stream)
        }

        // v2rayNG: mux disabled for SS, Trojan, WireGuard, Hysteria2, TUIC, SOCKS5, AnyTLS
        // Shadowsocks + mux is broken — xray-core does not support it
        // REALITY/XTLS use xtls-rprx-vision flow which is incompatible with mux
        // AnyTLS has its own session multiplexing, so xray mux must stay off.
        // Per-profile override (#22): the user can force mux off via profile.muxEnabled.
        val useMux = profile.muxEnabled && profile.protocol !in listOf(
            Protocol.AETHERLINK_X, Protocol.SHADOWSOCKS, Protocol.TROJAN, Protocol.ANYTLS,
            Protocol.HYSTERIA2, Protocol.WIREGUARD, Protocol.TUIC, Protocol.SOCKS5, Protocol.HTTP
        ) && profile.transport !in listOf(Transport.XHTTP, Transport.QUIC)
          && profile.security !in listOf(Security.REALITY, Security.XTLS)
        put("mux", JSONObject().apply {
            put("enabled", useMux)
            if (useMux) put("concurrency", 8)
        })
    }

    // ── Protocol builders ─────────────────────────────────────────────────────

    private fun buildAetherLinkX(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "aetherlinkx")
        obj.put("settings", JSONObject().apply {
            put("address", p.address)
            put("port", p.port)
            put("id", p.uuid)
            put("secret", p.alxSecret)
            put("allowInsecureTransport", p.alxAllowInsecureTransport)
            optionsObject(p.alxTurboJson)?.let { put("turbo", it) }
            optionsObject(p.alxSecurityJson)?.let { put("security", it) }
            optionsObject(p.alxStealthJson)?.let { put("stealth", it) }
        })
    }

    private fun optionsObject(raw: String): JSONObject? = runCatching {
        JSONObject(raw.ifBlank { "{}" }).takeIf { it.length() > 0 }
    }.getOrNull()

    private fun buildVless(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "vless")
        obj.put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", p.address)
                    put("port", p.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", p.uuid)
                            put("encryption", "none")
                            // BUG FIX: xtls-rprx-vision flow is only valid on raw/TCP transport.
                            // Setting it on WS/gRPC/XHTTP/H2/QUIC makes xray reject the outbound,
                            // so only apply flow for REALITY/XTLS over TCP.
                            val flow = when {
                                p.transport == Transport.TCP &&
                                    (p.security == Security.REALITY || p.security == Security.XTLS) ->
                                    "xtls-rprx-vision"
                                else -> ""
                            }
                            if (flow.isNotEmpty()) put("flow", flow)
                        })
                    })
                })
            })
        })
    }

    private fun buildVmess(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "vmess")
        obj.put("settings", JSONObject().apply {
            put("vnext", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", p.address)
                    put("port", p.port)
                    put("users", JSONArray().apply {
                        put(JSONObject().apply {
                            put("id", p.uuid)
                            put("alterId", 0)
                            put("security", p.vmessSecurity.ifBlank { "auto" })
                        })
                    })
                })
            })
        })
    }

    private fun buildShadowsocks(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "shadowsocks")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address",  p.address)
                    put("port",     p.port)
                    put("method",   p.ssMethod)
                    put("password", p.ssPassword)
                })
            })
        })
    }

    private fun buildTrojan(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "trojan")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address",  p.address)
                    put("port",     p.port)
                    put("password", p.uuid)
                })
            })
        })
    }

    private fun buildHysteria2(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "hysteria2")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address",  p.address)
                    put("port",     p.port)
                    put("password", p.hystPassword)
                    if (p.sni.isNotEmpty()) {
                        put("tlsSettings", JSONObject().apply {
                            put("serverName", p.sni)
                            put("allowInsecure", p.allowInsecure)
                        })
                    }
                    if (p.hystObfs.isNotEmpty()) {
                        put("obfs", JSONObject().apply {
                            put("type",     p.hystObfs)
                            put("password", p.hystObfsPassword)
                        })
                    }
                })
            })
        })
    }

    private fun buildWireguard(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "wireguard")
        obj.put("settings", JSONObject().apply {
            put("secretKey", p.wgPrivateKey)
            put("address", JSONArray().apply { put(p.address.ifEmpty { "10.0.0.2/32" }) })
            put("peers", JSONArray().apply {
                put(JSONObject().apply {
                    put("publicKey",  p.wgPeerPublicKey)
                    put("allowedIPs", JSONArray().apply { put("0.0.0.0/0"); put("::/0") })
                    // The endpoint is the peer's public host:port. p.address is the LOCAL
                    // tunnel address (often a CIDR like 10.0.0.2/32), so strip the CIDR in
                    // the fallback to avoid an invalid endpoint like "10.0.0.2/32:51820".
                    put("endpoint",   p.wgEndpoint.ifEmpty { "${p.address.substringBefore("/")}:${p.port}" })
                    if (p.wgPreSharedKey.isNotEmpty()) put("preSharedKey", p.wgPreSharedKey)
                })
            })
            put("mtu", p.wgMtu)
            // Cloudflare WARP "reserved" — 3 bytes prepended to each WG packet.
            parseReserved(p.wgReserved)?.let { bytes ->
                put("reserved", JSONArray().apply { bytes.forEach { put(it) } })
            }
            // WireGuard outbound only accepts Force* strategies (NOT UseIP/UseIPv4 like
            // freedom/routing) — "UseIPv4" makes xray reject the whole config.
            if (p.wgDns.isNotEmpty()) put("domainStrategy", "ForceIP")
        })
    }

    /** Parse a WARP "reserved" value ("a,b,c") into exactly three ints, or null. */
    private fun parseReserved(raw: String): List<Int>? {
        if (raw.isBlank()) return null
        val parts = raw.split(",", " ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 3) return null
        val ints = parts.mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        return ints.takeIf { it.size == 3 }
    }

    private fun buildSocksOut(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "socks")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", p.address)
                    put("port",    p.port)
                    if (p.uuid.isNotEmpty() && p.uuid.contains(":")) {
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("user", p.uuid.substringBefore(":"))
                                put("pass", p.uuid.substringAfter(":"))
                            })
                        })
                    }
                })
            })
        })
    }

    private fun buildHttpOut(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "http")
        obj.put("settings", JSONObject().apply {
            put("servers", JSONArray().apply {
                put(JSONObject().apply {
                    put("address", p.address)
                    put("port", p.port)
                    if (p.uuid.isNotEmpty() && p.uuid.contains(":")) {
                        put("users", JSONArray().apply {
                            put(JSONObject().apply {
                                put("user", p.uuid.substringBefore(":"))
                                put("pass", p.uuid.substringAfter(":"))
                            })
                        })
                    }
                })
            })
        })
    }

    private fun buildTuic(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "tuic")
        obj.put("settings", JSONObject().apply {
            put("server",            p.address)
            put("port",              p.port)
            put("uuid",              p.uuid)
            put("password",          p.ssPassword)
            put("congestionControl", "bbr")
            put("udpRelayMode",      "native")
            put("zeroRttHandshake",  false)
        })
    }

    private fun buildAnyTls(obj: JSONObject, p: VpnProfile) {
        obj.put("protocol", "anytls")
        obj.put("settings", JSONObject().apply {
            put("address",  p.address)
            put("port",     p.port)
            put("password", p.uuid)
        })
    }

    // ── Stream settings ───────────────────────────────────────────────────────

    private fun buildStreamSettings(p: VpnProfile) = JSONObject().apply {
        val network = when (p.transport) {
            Transport.TCP   -> "tcp"
            Transport.WS    -> "ws"
            Transport.GRPC  -> "grpc"
            Transport.XHTTP -> "xhttp"
            Transport.H2    -> "http"
            Transport.QUIC  -> "quic"
        }
        put("network", network)

        when (p.transport) {
            Transport.XHTTP -> put("xhttpSettings", JSONObject().apply {
                put("path", p.path.ifEmpty { "/" })
                put("host", p.host.ifEmpty { p.sni.ifEmpty { p.address } })
                put("mode", "stream-one")
            })
            Transport.WS -> put("wsSettings", JSONObject().apply {
                put("path", p.path.ifEmpty { "/" })
                if (p.host.isNotEmpty()) put("headers", JSONObject().apply { put("Host", p.host) })
            })
            Transport.GRPC -> put("grpcSettings", JSONObject().apply {
                put("serviceName", p.path.ifEmpty { "" })
                put("multiMode", false)
            })
            Transport.H2 -> put("httpSettings", JSONObject().apply {
                put("path", p.path.ifEmpty { "/" })
                if (p.host.isNotEmpty()) put("host", JSONArray().apply { put(p.host) })
            })
            Transport.TCP -> {
                if (p.host.isNotEmpty()) {
                    put("tcpSettings", JSONObject().apply {
                        put("header", JSONObject().apply {
                            put("type", "http")
                            put("request", JSONObject().apply {
                                put("version", "1.1")
                                put("method",  "GET")
                                put("path",    JSONArray().apply { put(p.path.ifEmpty { "/" }) })
                                put("headers", JSONObject().apply {
                                    put("Host",       JSONArray().apply { put(p.host) })
                                    put("User-Agent", JSONArray().apply { put("Mozilla/5.0") })
                                })
                            })
                        })
                    })
                }
            }
            else -> {}
        }

        when (p.security) {
            Security.TLS -> {
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("serverName",    p.sni.ifEmpty { p.address })
                    put("allowInsecure", p.allowInsecure)
                    if (p.fingerprint.isNotEmpty()) put("fingerprint", p.fingerprint)
                    put("alpn", JSONArray().apply {
                        if (p.transport in listOf(Transport.GRPC, Transport.H2)) put("h2")
                        put("http/1.1")
                    })
                })
            }
            Security.REALITY -> {
                put("security", "reality")
                put("realitySettings", JSONObject().apply {
                    put("serverName",  p.sni.ifEmpty { p.address })
                    put("fingerprint", p.fingerprint.ifEmpty { "chrome" })
                    put("shortId",     p.shortId)
                    put("publicKey",   p.publicKey)
                    put("spiderX",     "")
                })
            }
            Security.XTLS -> {
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("serverName",    p.sni.ifEmpty { p.address })
                    put("allowInsecure", p.allowInsecure)
                    if (p.fingerprint.isNotEmpty()) put("fingerprint", p.fingerprint)
                })
            }
            Security.NONE -> put("security", "none")
        }
    }

    // ── DNS ───────────────────────────────────────────────────────────────────

    // v2rayNG CoreConfigManager.getDns():
    // - hosts map pre-resolves known DoH provider domains to their IPs
    //   to prevent DNS loop (xray intercepts DNS → tries to resolve DoH domain
    //   → DNS intercept again → infinite loop)
    // - remote DNS (1.1.1.1 DoH) for proxied traffic
    // - direct DNS (223.5.5.5) for local/cn traffic
    private fun buildDns(settings: AppSettings) = JSONObject().apply {
        put("tag", "dns-in")
        put("hosts", JSONObject().apply {
            // Hardcoded per v2rayNG to fix DNS loop for popular DoH providers
            put("dns.google",         JSONArray().apply { put("8.8.8.8"); put("8.8.4.4") })
            put("one.one.one.one",    JSONArray().apply { put("1.1.1.1"); put("1.0.0.1") })
            put("cloudflare-dns.com", JSONArray().apply { put("1.1.1.1"); put("1.0.0.1") })
            put("dns.cloudflare.com", JSONArray().apply { put("1.1.1.1"); put("1.0.0.1") })
            put("dns.alidns.com",     JSONArray().apply { put("223.5.5.5"); put("223.6.6.6") })
            // v2rayNG: fix Play Store problems
            put("clients4.google.com", "clients.google.com")
        })
        put("servers", JSONArray().apply {
            // FakeDNS server must come first so A/AAAA queries get a fake IP from the pool (#6).
            if (settings.enableFakeDns) put("fakedns")
            put(JSONObject().apply {
                put("address", settings.remoteDns)
                put("domains", JSONArray().apply { put("geosite:geolocation-!cn") })
            })
            put(settings.directDns)
            put("localhost")
        })
    }

    // ── Policy ────────────────────────────────────────────────────────────────

    // v2rayNG v2ray_config_with_tun.json policy section
    private fun buildPolicy() = JSONObject().apply {
        put("levels", JSONObject().apply {
            put("8", JSONObject().apply {
                put("handshake",    4)
                put("connIdle",     300)
                put("uplinkOnly",   1)
                put("downlinkOnly", 1)
            })
        })
        put("system", JSONObject().apply {
            put("statsOutboundUplink",   true)
            put("statsOutboundDownlink", true)
        })
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    // v2rayNG CoreConfigManager.getCustomLocalDns() adds a DNS intercept rule:
    //   inboundTag=["tun"], port="53" → outboundTag="dns-out"
    // This is critical — without it DNS packets from TUN go into proxy,
    // proxy resolves them, returns through TUN, xray intercepts again → loop.
    private fun buildRouting(settings: AppSettings) = JSONObject().apply {
        put("domainStrategy", settings.domainStrategy.name)   // #7 user-selectable
        put("domainMatcher",  "hybrid")
        // GLOBAL forces everything (except LAN/DNS) through the proxy; BYPASS_LAN ignores
        // China/custom-direct bypass. Only RULE_BASED applies the full direct/bypass rules. (#2)
        val applyDirectRules = settings.routingMode == RoutingMode.RULE_BASED
        val applyChina       = settings.routingMode == RoutingMode.RULE_BASED && settings.bypassChina
        put("rules", JSONArray().apply {

            // Rule 1: DNS from TUN → dns-out (MUST be first)
            put(JSONObject().apply {
                put("type", "field")
                put("inboundTag", JSONArray().apply { put("tun") })
                put("outboundTag", "dns-out")
                put("port", "53")
            })

            // User-defined blocked domains (always honoured — blocking is not a "direct" rule)
            if (settings.customBlockedDomains.isNotEmpty()) {
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "block")
                    put("domain", JSONArray().apply { settings.customBlockedDomains.forEach { put(it) } })
                })
            }

            // Block ads (optional, always honoured)
            if (settings.blockAds) {
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "block")
                    put("domain", JSONArray().apply { put("geosite:category-ads-all") })
                })
            }

            // User-defined direct domains
            if (applyDirectRules && settings.customDirectDomains.isNotEmpty()) {
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", JSONArray().apply { settings.customDirectDomains.forEach { put(it) } })
                })
            }

            // Bypass China (optional) — route mainland domains & IPs direct
            if (applyChina) {
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("domain", JSONArray().apply { put("geosite:cn") })
                })
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply { put("geoip:cn") })
                })
            }

            // Private/LAN IPs → direct. GLOBAL forces everything (LAN included) through the
            // proxy, so the bypass only applies to RULE_BASED and BYPASS_LAN.
            if (settings.routingMode != RoutingMode.GLOBAL) {
                put(JSONObject().apply {
                    put("type", "field")
                    put("outboundTag", "direct")
                    put("ip", JSONArray().apply {
                        put("geoip:private")
                        put("127.0.0.0/8")
                        put("10.0.0.0/8")
                        put("172.16.0.0/12")
                        put("192.168.0.0/16")
                        put("169.254.0.0/16")
                        put("224.0.0.0/4")
                    })
                })
            }

            // All other traffic → proxy
            put(JSONObject().apply {
                put("type", "field")
                put("outboundTag", "proxy")
                put("network", "tcp,udp")
            })
        })
    }
}
