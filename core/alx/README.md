# AetherLink Native ALX/1

ALX/1 is an experimental, independent transport that runs beside the known-good
VLESS/REALITY baseline. It does not modify Xray or reuse the VLESS wire format.

Current data path:

```text
Android TUN -> hev-socks5-tunnel -> ALX local SOCKS5 -> QUIC/TLS 1.3 -> alx-server
```

Properties of Preview 1:

- one authenticated QUIC connection multiplexes independent TCP streams;
- small UDP packets use QUIC DATAGRAM, large UDP packets use a reliable stream;
- the TLS server key is pinned with SHA-256 SPKI;
- every connection uses a fresh HMAC-authenticated nonce and short replay window;
- the server rejects loopback, link-local, multicast and private destinations;
- 0-RTT is disabled until request replay rules are formally specified;
- the existing Xray service on TCP/443 is untouched; ALX listens on UDP/443.

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
ALX_CERT_FILE=/etc/aetherlink-x/server.crt
ALX_KEY_FILE=/etc/aetherlink-x/server.key
ALX_TOKEN=<at least 32 random bytes encoded as Base64URL>
```

The Android library is built together with AndroidLibXrayLite in the project CI
workflow. Binding both packages into one AAR prevents duplicate Go runtimes.
