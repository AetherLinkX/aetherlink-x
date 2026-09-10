# AetherLink Native — Preview 8 Turbo

ALX is an independent authenticated tunnel that runs beside the known-good
VLESS/REALITY compatibility path. Turbo keeps the cryptographic layer simple
and audited while changing the transport and application framing.

```text
Android TUN -> hev-socks5-tunnel -> ALX local SOCKS5
  -> Turbo race: Chrome-like TLS 1.3/TCP or QUIC/TLS 1.3
  -> alx-server -> Internet
```

## Turbo properties

- the TCP ClientHello follows a current Chrome fingerprint;
- TCP uses a normal HTTP/1.1 WebSocket-style Upgrade before switching to the
  low-overhead ALX byte stream;
- invalid or unauthenticated web requests receive a harmless cover page;
- the 56-byte authentication frame has no ALX magic and is bound to the exact
  TLS session with a TLS exporter, timestamp, random nonce and HMAC-SHA256;
- replayed authentication nonces are rejected;
- TCP and QUIC probes run together and the first authenticated path wins;
- UDP uses QUIC datagrams when possible and a reliable stream for larger data;
- certificate identity is enforced with a SHA-256 SPKI pin;
- TLS 0-RTT is disabled because authentication is intentionally non-replayable;
- Preview 7 ALPN/authentication remains available for existing profiles.

There is no padding, fake traffic or artificial delay in Turbo. The mode uses
standard TLS 1.3 and QUIC encryption; ALX owns the authentication, framing,
multiplexing, UDP relay, routing and adaptive path selection.

## Build and test

```bash
go test ./...
go build -trimpath -ldflags='-s -w' -o alx-server ./cmd/alx-server
go build -trimpath -ldflags='-s -w' -o alx-check ./cmd/alx-check
```

The diagnostic tool starts a local ALX SOCKS endpoint and measures a real HTTPS
download through it:

```bash
./alx-check -config client.json -url 'https://speed.cloudflare.com/__down?bytes=10485760' -attempts 3
```

It emits JSON with selected transport, successful attempts, first-byte time,
downloaded bytes, throughput and the last tunnel error.

## Server configuration

```text
ALX_LISTEN=:443
ALX_TCP_LISTEN=:443
ALX_TCP_DEFAULT_BACKEND=127.0.0.1:8444
ALX_CERT_FILE=/etc/aetherlink-x/server.crt
ALX_KEY_FILE=/etc/aetherlink-x/server.key
ALX_TOKEN=<at least 32 random Base64URL bytes>

# Preview 8 public web identity:
ALX_TURBO_SERVER_NAME=turbo.example.com
ALX_TURBO_CERT_FILE=/etc/aetherlink-x/turbo-fullchain.pem
ALX_TURBO_KEY_FILE=/etc/aetherlink-x/turbo-privkey.pem
```

The default TCP backend is optional. When present, ordinary non-ALX TLS traffic
is forwarded unchanged to the existing Xray listener. Requests for the Turbo
SNI are terminated by ALX, while the legacy `alx/1` ALPN continues to use the
Preview 7 path.

## Client configuration

```json
{
  "listen": "127.0.0.1:10808",
  "server": "SERVER_IP:443",
  "fallbackServer": "SERVER_IP:443",
  "transportMode": "turbo",
  "token": "BASE64URL_32_BYTE_TOKEN",
  "certificatePin": "BASE64URL_SHA256_SPKI",
  "serverName": "turbo.example.com",
  "handshakeTimeoutMs": 8000,
  "quicProbeTimeoutMs": 1500
}
```

Share URI:

```text
aetherlink://TOKEN@SERVER_IP:443?pin=SPKI_PIN&sni=turbo.example.com&fallback=443&mode=turbo#AetherLink%20Turbo
```

Never commit tokens, certificate private keys, generated client configs or
complete share links.
