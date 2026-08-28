# AetherLink X for Xray-core

Status: working experimental implementation, wire `1.1`. It is suitable for controlled testing, not an independently audited production security protocol.

## What is implemented

- Xray inbound/outbound handlers for TCP and destination-aware UDP-over-stream;
- canonical UUID accounts with a separate random 32-byte Base64URL secret;
- authenticated `ClientInit`/`ServerAccept`, fresh client/server nonces, session ID, a two-minute timestamp window and bounded replay cache;
- hybrid post-quantum X-Wing KEM (X25519 + ML-KEM-768) with `off`, `prefer` and `required` policy;
- optional directional ChaCha20-Poly1305 inner records with authenticated length, monotonic nonces and tamper rejection;
- AetherLink Turbo UDP sequence/deadline metadata, negotiated payload limits and a bounded per-direction destination dictionary;
- Stealth record shaping: random encrypted-record chunks and bounded random padding, without latency-producing sleeps;
- automatic Xray Mux/XUDP settings when explicitly enabled, plus TCP keepalive, user-timeout, congestion and MPTCP socket tuning;
- transport independence through Xray's existing RAW, XHTTP, gRPC and WebSocket implementations;
- mandatory TLS/REALITY for normal JSON configurations; plaintext requires the explicit `allowInsecureTransport` override;
- a WARP composition example using Xray's WireGuard outbound and `sockopt.dialerProxy`;
- unit, tamper, replay, fuzz, benchmark and TLS/UDP end-to-end coverage;
- `xray aetherlinkx-keygen` for UUID, account secret and X-Wing key generation.

REALITY, TLS, WebSocket, gRPC and WARP remain existing Xray layers. ALX does not duplicate or fork them.

## Security model

TLS or REALITY remains mandatory on an untrusted network and supplies transport confidentiality, endpoint authentication and traffic persona. The ALX binder authenticates the user and all request metadata inside that transport. `innerAead` adds defense in depth but costs extra copies, allocations and bytes, and prevents a true Vision/splice fast path.

`pqMode: "required"` performs a second, inner X-Wing encapsulation. Its shared secret is mixed with the account secret before request/response authentication and record-key derivation. This implementation deliberately uses standardized X-Wing rather than the obsolete `X25519Kyber768Draft00` name. Outer REALITY/TLS post-quantum negotiation and inner ALX X-Wing are independent layers.

The server private X-Wing seed must never be placed in client configuration. The JSON builder rejects wrong-side key fields, malformed lengths and an all-zero private seed. The server rejects an inner-AEAD policy mismatch and an unavailable requested Turbo mode before `ServerAccept`; the client also rejects PQ, AEAD and Turbo downgrade/mismatch after authenticating a response.

Clock synchronization is required because an authenticated `ClientInit` is accepted only within ±2 minutes. Replay entries are bounded; saturation fails closed for new initializations.

## Wire 1.1

Every handshake message starts with a two-byte big-endian body length, limited to 4096 bytes.

`ClientInit` fixed prefix:

| Field | Bytes |
|---|---:|
| magic `ALX1` | 4 |
| major, minor, command, flags | 4 |
| HMAC-derived key ID | 16 |
| client nonce | 16 |
| session ID | 8 |
| Unix timestamp | 8 |
| Turbo age/cache/payload | 6 |
| X-Wing ciphertext length | 2 |

The optional 1120-byte X-Wing ciphertext, destination and 32-byte HMAC-SHA256 binder follow. The binder key is HKDF-SHA256 over the account secret and, when enabled, the X-Wing shared secret.

`ServerAccept` contains `ALXR`, version, status, authenticated feature flags, 16-byte server nonce, echoed session ID, six negotiated Turbo bytes and a 32-byte binder. Separate HKDF contexts are used for client authentication, server authentication, client-to-server records and server-to-client records.

Inner AEAD records are:

```text
uint16 plaintext_length
ChaCha20-Poly1305(
  uint16 content_length || uint16 padding_length || content || random_padding,
  associated_data = plaintext_length
)
```

The encrypted length is bounded by the Xray buffer size before allocation. Each direction starts with counter zero under a different key; counter exhaustion terminates the stream.

Turbo UDP frames contain marker `0xa1`, flags, sequence, send-time delta, destination-cache ID, optional destination, payload length and payload. Cache references are scoped to one direction and connection. The receiver can discard a stale packet after reading it. Because ALX UDP currently rides an ordered stream, this prevents stale delivery but cannot remove transport-level head-of-line blocking already caused by TCP retransmission.

## Configuration

Generate credentials with:

```text
xray aetherlinkx-keygen
```

Client protocol settings:

```json
{
  "address": "alx.example.com",
  "port": 443,
  "id": "REPLACE_WITH_GENERATED_UUID",
  "secret": "REPLACE_WITH_GENERATED_ACCOUNT_SECRET",
  "turbo": {
    "enabled": true,
    "maxDatagramAgeMs": 35,
    "destinationCacheSize": 64,
    "maxUdpPayload": 8192,
    "tcpKeepAliveIdle": 15,
    "tcpKeepAliveInterval": 5,
    "tcpUserTimeout": 10000,
    "congestion": "auto",
    "multipathTcp": true,
    "muxConcurrency": 8,
    "xudpConcurrency": 4,
    "xudpProxyUdp443": "allow"
  },
  "security": {
    "pqMode": "required",
    "xwingPublicKey": "REPLACE_WITH_GENERATED_XWING_PUBLIC_KEY",
    "innerAead": true
  },
  "stealth": {
    "enabled": true,
    "minChunkSize": 256,
    "maxChunkSize": 1200,
    "maxPaddingBytes": 128,
    "paddingProbabilityPercent": 25
  }
}
```

Server protocol settings use the same `id` and `secret`, but `security.xwingPrivateKey` instead of the public key. Complete templates are in [`examples`](examples). Placeholder credentials are intentionally invalid until replaced.

Turbo socket fields are defaults only: explicit `streamSettings.sockopt` values win. `congestion: "auto"` preserves the operating system default; names such as `bbr` request that OS algorithm and fail or have no benefit if the host/kernel does not support it. MPTCP requires operating-system support and only affects eligible RAW TCP sockets. It is disabled in the WARP example because WireGuard already supplies the underlay tunnel.

`muxConcurrency` creates Xray Mux/XUDP settings only when the outbound has no explicit top-level `mux`. Aggressive Mux can reduce connection setup cost but may worsen gaming tail latency through head-of-line blocking. Measure p95/p99 RTT and disable it when loss is non-trivial.

## Stealth and transport behavior

ALX Stealth changes only encrypted inner record sizing. It does not claim to turn arbitrary traffic into a statistically faithful video call. Randomly changing TLS extensions per packet would itself be anomalous; TLS/uTLS/REALITY fingerprints should be selected by the outer Xray transport and remain coherent for the lifetime of that session.

WebSocket `Transfer-Encoding: chunked` is not valid after an Upgrade. For WebSocket, ALX record boundaries may be split across normal binary frames by the existing transport, but ALX does not emit fake HTTP chunks. gRPC and HTTP/2 framing are likewise owned by their existing Xray transports.

WARP is not an ALX wire capability. The WARP example dials the REALITY endpoint through a tagged WireGuard outbound. This hides the client's origin from the ALX endpoint but can add RTT; compare it against direct routing.

## Known limits and next hardening work

- no independent cryptographic audit or formal protocol analysis;
- no native unreliable/QUIC datagram transport, so UDP-over-TCP retains head-of-line behavior;
- no ALX-native multipath lane aggregation or migration; current support is OS MPTCP socket opt-in;
- no Vision direct-copy adapter; inner AEAD and Stealth necessarily process every record;
- no per-flow fair queue/CoDel scheduler inside ALX; Turbo currently supplies deadline metadata, destination compression and Xray/OS tuning;
- no automatic congestion-algorithm switching based on live measurements;
- no automatic WARP enrollment or credential acquisition;
- wire `1.1` is experimental and may change incompatibly before a stable release.

## Verification

From the Xray source root:

```text
go test ./proxy/aetherlinkx -count=1
go test ./infra/conf -run AetherLinkX -count=1
go test ./testing/scenarios -run '^TestAetherLinkX(TLS|TurboUDP)$' -count=1
go test ./proxy/aetherlinkx -run '^$' -fuzz FuzzDecodeRequestHeader -fuzztime 10s
go test ./proxy/aetherlinkx -run '^$' -bench '^BenchmarkAetherLinkX' -benchtime 100x
```
