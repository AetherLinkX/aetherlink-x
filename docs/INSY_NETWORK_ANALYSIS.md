# Insy network-stack analysis and AetherLink X implementation

## Scope and source availability

The public `INCY-DEV/incy-platforms` repository contains release metadata and a README, but no
application source and no source-code license. Its APK was therefore used only for non-invasive
package metadata inspection. No decompiled or closed Insy code is included in AetherLink X.

The public APK contains both `libgojni.so` and `libhev-socks5-tunnel.so`. Together with Insy's
public statement that it uses Xray-core, this strongly indicates the following Android data path:

```text
Android apps → VpnService TUN → HEV tun2socks → loopback SOCKS → Xray-core → remote server
```

AetherLink X uses that architecture from open sources. Exact production TCP and UDP tests proved
the AetherLink X/REALITY core path independently; Android tests then isolated the built-in Xray TUN
as the layer where a core probe could pass while device packets stalled. The production data path
therefore uses the mature HEV bridge:

- Xray-core through `AndroidLibXrayLite` (LGPL-3.0 wrapper and Xray's MPL-2.0 core);
- lifecycle and configuration concepts cross-checked against the GPL-3.0 v2rayNG implementation.

## Fixed traffic path

Version 0.6.11 gives Android's TUN descriptor to pinned `hev-socks5-tunnel`; HEV forwards TCP and UDP
to a private random-port SOCKS inbound owned by the patched Xray core. The core is started first and
the packet bridge second, and both lifecycles are monitored. A slow or filtered synthetic probe is
diagnostic only and can no longer tear down a working tunnel.

HEV handles TCP and UDP and Xray applies the selected outbound identically to both. IPv6 is captured
only when enabled. With IPv6 disabled, Xray's DNS `queryStrategy` is `UseIPv4`, preventing
applications from receiving unreachable AAAA answers and stalling before IPv4 fallback.

## DNS leak prevention

Android DNS servers point into the captured VPN path. Xray's first routing rule redirects the
private SOCKS inbound's destination port 53 to `dns-out`. The DNS
configuration has only the selected remote resolver; the former direct and `localhost` fallbacks
were removed. Resolver failure therefore fails closed instead of silently querying the ISP DNS.

The default routing preset for new installations is `GLOBAL`. A user can still explicitly enable
split tunnelling or direct routing; those options necessarily exempt the selected traffic.

## Main-tab lifecycle

The three main Compose screens use a retained `HorizontalPager`: a page is created lazily on its
first visit and kept composed afterwards. Switching is immediate and does not rebuild a complete
navigation destination. Expensive package enumeration is deferred until the split-tunnelling page
is actually opened. This is the Jetpack Compose equivalent of Flutter's `IndexedStack`, lazy
`PageView`, and keep-alive approach; Flutter widgets are not part of this native Android client.

## Subscription and deep links

The client accepts regular Base64 subscription bodies, newline-separated VLESS/VMess/Trojan/
Shadowsocks/etc. links, and supported full JSON. The Android manifest also handles
`https://s.obsa.su/sub/...`. Opening such a link downloads it off the UI thread, validates every
profile, atomically replaces the subscription's previous profiles, and activates the first valid
profile. Reopening the same URL refreshes it rather than creating a duplicate.

## Android App Links deployment

The manifest requests verification for `s.obsa.su`. To make links open directly without a chooser,
serve `https://s.obsa.su/.well-known/assetlinks.json` with the production application ID
`io.aetherlinkx.client` and the SHA-256 fingerprint of the stable production signing certificate:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "io.aetherlinkx.client",
      "sha256_cert_fingerprints": ["REPLACE_WITH_PRODUCTION_CERT_SHA256"]
    }
  }
]
```

The response must use `Content-Type: application/json`, have no redirect, and be reachable over
valid HTTPS. A stable signing key is required; fingerprints from ephemeral debug keys must not be
published.

## Runtime verification still required

An APK build proves packaging and configuration generation, not packet flow on every Android
vendor kernel. Before calling the client production-ready, test on a physical device: web and UDP
traffic, DNS leak pages, IPv4/IPv6 networks, reconnect, network handover, screen-off, and Always-on
VPN with “Block connections without VPN”.
