# AetherLink X

AetherLink X combines a production VLESS/REALITY compatibility path with the
experimental independent ALX transport. The Android application keeps its
existing design and subscription support; native ALX profiles are additional
profiles and do not replace ordinary Xray locations.

## Preview 8: Turbo

Turbo is the only new mode in Preview 8. It is designed for maximum practical
speed without artificial padding, random delays, CDN routing, or custom
cryptography:

- standard TLS 1.3 encryption;
- a current Chrome-like ClientHello on TCP;
- an authenticated HTTP Upgrade that resembles a normal web connection;
- session-bound HMAC authentication with replay protection;
- parallel TCP/QUIC probing and automatic selection of the first working path;
- large adaptive buffers and connection reuse for high-throughput links;
- an ordinary cover page for unauthenticated requests;
- full compatibility with Preview 7 ALX profiles and existing VLESS profiles.

`ALX` means the authentication, framing, UDP relay, path selection and routing
implemented in this repository. It does not claim to invent a new cipher:
security-critical encryption remains the responsibility of standard TLS 1.3.

## Repository layout

- `client/android` — Android application;
- `core/alx` — ALX client, server, tests and diagnostic benchmark;
- `.github/workflows` — reproducible Android and Linux release builds;
- `test-server` — preserved VLESS baseline configuration.

Build and configuration details: [core/alx/README.md](core/alx/README.md).
Android installation details: [client/README.md](client/README.md).

Never commit tokens, private keys or complete share links.
