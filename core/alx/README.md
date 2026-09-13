<div align="center">

# ALX Preview 8 Turbo

**An independent, authenticated tunnel for AetherLink X.**

[Overview](../../README.md) · [Architecture](../../docs/ARCHITECTURE.md) ·
[Server release](https://github.com/AetherLinkX/aetherlink-x/releases/tag/server-v0.2.0-alx-preview.8) ·
[Русский](README.ru.md)

</div>

## What ALX is

ALX is the transport layer implemented in this directory: access
authentication, stream framing, UDP relay, connection management and adaptive
path selection. Preview 8's single mode is **Turbo**.

~~~text
Android TUN -> hev-socks5-tunnel -> ALX local SOCKS5
  -> Turbo: QUIC/TLS 1.3 or Chrome-like TLS 1.3/TCP
  -> alx-server -> Internet
~~~

ALX does **not** invent a cipher. TLS 1.3 and QUIC libraries handle encryption
and key exchange. ALX adds authentication and transport logic above that
cryptographic foundation.

## Turbo capabilities

| Area | Preview 8 behavior |
|---|---|
| Path selection | QUIC and TCP are probed; the first authenticated path wins |
| TCP presentation | Current Chrome-like ClientHello and HTTP/1.1 Upgrade |
| Authentication | HMAC-SHA256 bound to the TLS session exporter |
| Freshness | Timestamp, random nonce and replay cache |
| Server identity | Normal certificate verification plus SHA-256 SPKI pin |
| UDP | QUIC datagrams with reliable stream fallback |
| Operations | Up to two previous tokens during zero-downtime rotation |
| Probe handling | Cover response and throttling for invalid authentication |
| Throughput | Adaptive buffers, connection reuse and BBR-controlled QUIC |

Turbo deliberately adds no artificial padding, fake traffic or random delay.
It does not require a CDN.

## Build

Go 1.26 or the version configured by the release workflow is required.

~~~bash
go mod download
go mod verify
go test -race ./...
go build -trimpath -ldflags='-s -w' -o alx-server ./cmd/alx-server
go build -trimpath -ldflags='-s -w' -o alx-client ./cmd/alx-client
go build -trimpath -ldflags='-s -w' -o alx-check ./cmd/alx-check
~~~

## Server configuration

~~~text
ALX_LISTEN=:443
ALX_TCP_LISTEN=:443
ALX_TCP_DEFAULT_BACKEND=127.0.0.1:8444
ALX_CERT_FILE=/etc/aetherlink-x/server.crt
ALX_KEY_FILE=/etc/aetherlink-x/server.key
ALX_TOKEN=<at least 32 random Base64URL bytes>

# Optional only during a key rollout:
ALX_PREVIOUS_TOKENS=<old token 1>,<old token 2>

# Public identity used by Turbo:
ALX_TURBO_SERVER_NAME=turbo.example.com
ALX_TURBO_CERT_FILE=/etc/aetherlink-x/turbo-fullchain.pem
ALX_TURBO_KEY_FILE=/etc/aetherlink-x/turbo-privkey.pem
~~~

The TCP backend is optional. When configured, non-ALX TLS traffic can be passed
through to an existing listener. Requests for the Turbo SNI are terminated by
ALX.

Never store production tokens directly in a tracked service file. Load them
from a root-readable environment file or a secret manager.

## Client configuration

~~~json
{
  "listen": "127.0.0.1:10808",
  "server": "203.0.113.10:443",
  "fallbackServer": "203.0.113.10:443",
  "serverName": "turbo.example.com",
  "transportMode": "turbo",
  "certificatePin": "<SHA-256 SPKI pin>",
  "token": "<Base64URL token>"
}
~~~

The addresses and values above are placeholders. Do not publish a completed
configuration.

## Diagnostic benchmark

~~~bash
./alx-check \
  -config client.json \
  -url 'https://speed.cloudflare.com/__down?bytes=10485760' \
  -attempts 3
~~~

The command reports the selected transport, successful attempts, time to first
byte, downloaded bytes, throughput and the last tunnel error. Benchmark ALX and
another transport on the same device, server, route and time window before
drawing conclusions.

## Zero-downtime token rotation

1. Put the new secret in ALX_TOKEN and the deployed secret in
   ALX_PREVIOUS_TOKENS; restart the server.
2. Roll out clients containing the new token.
3. Confirm both generations connect.
4. Clear ALX_PREVIOUS_TOKENS after the migration window and restart.

At most two previous tokens are accepted.

## Security status

The protocol has automated unit and integration tests, including TLS routing,
session binding, replay-related behavior, invalid-auth throttling and previous
token acceptance. It has **not** received an independent security audit. See
[SECURITY.md](../../SECURITY.md) before deployment.
