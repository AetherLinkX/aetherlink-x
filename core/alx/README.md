# AetherLink Native ALX/1

ALX/1 is an experimental, independent transport that runs beside the known-good
VLESS/REALITY baseline. It does not modify Xray or reuse the VLESS wire format.

Current data path:

```text
Android TUN -> hev-socks5-tunnel -> ALX local SOCKS5
  -> QUIC/TLS 1.3 (preferred)
  -> pinned TLS 1.3 over TCP (automatic fallback)
  -> alx-server
```

Properties of Preview 1:

- one authenticated QUIC connection multiplexes independent TCP streams;
- small UDP packets use QUIC DATAGRAM, large UDP packets use a reliable stream;
- the TLS server key is pinned with SHA-256 SPKI;
- every connection uses a fresh HMAC-authenticated nonce and short replay window;
- the server rejects loopback, link-local, multicast and private destinations;
- 0-RTT is disabled until request replay rules are formally specified;
- blocked UDP is detected during a short probe and transparently falls back to
  TLS/TCP without changing the profile;
- QUIC flow-control windows grow adaptively up to 8 MiB per stream and 32 MiB
  per connection; TLS fallback reuses session tickets across connections;
- long-lived SOCKS UDP associations reconnect in place after a transient QUIC
  or server restart, so Android DNS does not require a manual VPN restart;
- an optional ALPN gateway lets ALX and an existing Xray/REALITY service share
  TCP/443. ALX terminates only ClientHello messages advertising `alx/1`; every
  other byte stream is passed unchanged to the configured Xray backend.

This preview uses standard TLS 1.3 and QUIC cryptography. "Own protocol" refers
to ALX authentication, framing, multiplexing and routing, not to inventing
untested cryptographic primitives.

## Server build

```bash
go test ./...
go build -trimpath -ldflags='-s -w' -o alx-server ./cmd/alx-server
```

Required environment variables:

```text
ALX_LISTEN=:443
ALX_TCP_LISTEN=:443
# Optional when TCP/443 is shared with an existing local Xray listener:
ALX_TCP_DEFAULT_BACKEND=127.0.0.1:8444
ALX_CERT_FILE=/etc/aetherlink-x/server.crt
ALX_KEY_FILE=/etc/aetherlink-x/server.key
ALX_TOKEN=<at least 32 random bytes encoded as Base64URL>
```

The Android library is built together with AndroidLibXrayLite in the project CI
workflow. ALX is exported through the existing `Libv2ray` package so the APK
contains exactly one Go runtime.

## Client config

```json
{
  "listen": "127.0.0.1:10808",
  "server": "SERVER_IP:443",
  "fallbackServer": "SERVER_IP:443",
  "token": "BASE64URL_32_BYTE_TOKEN",
  "certificatePin": "BASE64URL_SHA256_SPKI",
  "serverName": "COVER_NAME",
  "handshakeTimeoutMs": 8000,
  "quicProbeTimeoutMs": 1500
}
```

The share URI uses the same fields:

```text
aetherlink://TOKEN@SERVER_IP:443?pin=SPKI_PIN&sni=COVER_NAME&fallback=443#NAME
```

Never commit real tokens, private keys, generated client configs, or complete
share links. Preview 1 deliberately uses standard audited TLS/QUIC primitives;
traffic camouflage beyond a normal TLS ClientHello remains a separate hardening
phase and must not be confused with transport correctness.
