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

AetherLink X implements that architecture from open sources:

- Xray-core through `AndroidLibXrayLite` (LGPL-3.0 wrapper and Xray's MPL-2.0 core);
- `heiher/hev-socks5-tunnel` at commit `64cc609f945253b0e9ebc56317d544268f3c68c1`
  (MIT), compiled reproducibly by the Android workflow;
- lifecycle and configuration concepts cross-checked against the GPL-3.0 v2rayNG implementation.

## Fixed traffic path

The previous client passed Android's TUN file descriptor directly to Xray's native `tun` inbound.
That path could report a running core while vendor-specific TUN handling passed no application
traffic. Version 0.2 uses HEV as the sole owner of the descriptor and starts Xray with a loopback
SOCKS inbound. The VPN becomes `RUNNING` only after HEV confirms it is alive. A periodic health
check turns an unexpected native stop into an explicit error instead of a false connected state.

HEV handles TCP and UDP. IPv4 is always captured. IPv6 is either forwarded when enabled or claimed
by the Android VPN and dropped inside the tunnel when disabled, preventing fallback to the physical
network.

## DNS leak prevention

Android DNS servers point into the captured VPN path. HEV forwards their UDP/TCP packets to the
SOCKS inbound. Xray's first routing rule redirects SOCKS destination port 53 to `dns-out`. The DNS
configuration has only the selected remote resolver; the former direct and `localhost` fallbacks
were removed. Resolver failure therefore fails closed instead of silently querying the ISP DNS.

The default routing preset for new installations is `GLOBAL`. A user can still explicitly enable
split tunnelling or direct routing; those options necessarily exempt the selected traffic.

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
