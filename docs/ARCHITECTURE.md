# Architecture

ALX Preview 8 is a client/server tunnel embedded in the AetherLink X Android
application. It exposes a local SOCKS5 endpoint to the Android TUN data plane
and relays TCP and UDP traffic to an ALX gateway.

```text
Android apps
    │
Android VpnService / TUN
    │
hev-socks5-tunnel
    │
ALX local SOCKS5
    ├── QUIC + TLS 1.3 ──────┐
    └── Chrome-like TLS/TCP ─┤
                             │
                         ALX server
                             │
                          Internet
```

## Connection sequence

1. The client opens QUIC and TCP probes for Turbo mode.
2. The server presents its certificate and the client verifies both the TLS
   identity and configured SHA-256 SPKI pin.
3. ALX derives a session binding with a TLS exporter.
4. The client sends a fixed-size HMAC authentication frame containing a fresh
   timestamp and random nonce.
5. The server validates the frame, rejects replays and accepts the first
   authenticated transport.
6. TCP streams and UDP associations are multiplexed through the selected path.
7. If QUIC is unavailable, the TLS/TCP HTTP Upgrade path remains available.

## Ownership boundary

| Layer | Implementation |
|---|---|
| Encryption and key exchange | Standard TLS 1.3 / QUIC libraries |
| ClientHello presentation | uTLS Chrome-compatible fingerprint |
| Access authentication | ALX session-bound HMAC frame |
| Replay protection | ALX timestamp and nonce cache |
| Stream framing and UDP relay | ALX |
| Path probing and selection | ALX Turbo |
| Android packet bridge | hev-socks5-tunnel |
| Standard profile compatibility | Xray-core |

ALX intentionally does not implement a new cipher. Designing cryptographic
primitives without extensive public review would reduce, not improve, security.

## Compatibility

The ALX runtime is additive. Standard Xray profiles use the Xray execution path;
native ALX profiles use the ALX execution path. Preview 7 ALX authentication
and profile compatibility remain available during migration.
