# AetherLink X: live diagnosis (2026-09-03)

This report intentionally omits subscription tokens, account identifiers, secrets,
private keys, public keys, and full configuration bodies.

## Proven observations

- The Remnawave panel returned HTTP 200 and 65 profiles for both tested active
  subscriptions: 64 VLESS locations and one AetherLink X location.
- Both tested users were active and belonged to the AetherLink X internal squad.
- Redacted fingerprints of the account ID, account secret, REALITY public key,
  short ID, and X-Wing public key matched the running FI node configuration.
- The FI node container remained healthy with restart count zero. Its patched Xray
  26.7.28 process listened on TCP 8443.
- A separate patched Xray client using the exact live AetherLink X configuration
  completed all of the following through its local SOCKS inbound:
  - HTTPS response and a complete 10,000,000-byte payload;
  - 16 concurrent HTTPS transfers of 1,048,576 bytes each;
  - SOCKS UDP ASSOCIATE followed by a real DNS response;
  - strict RFC 1928 and pipelined SOCKS negotiation tests.
- The node produced no AetherLink X, REALITY, X-Wing, authentication, framing, or
  transport errors during these tests.
- The distributed Android 0.6.11 APK contained both native ABIs, the HEV bridge,
  and the patched `aetherlinkx` module inside `libgojni.so`.

## Conclusion

The panel, subscription generation, credentials, FI listener, REALITY handshake,
AetherLink X framing, server return path, TCP, and UDP were all exercised and are
not the failing layer. The remaining failure boundary is the Android data plane:

```text
Android application -> VpnService TUN -> HEV -> local Xray SOCKS -> AetherLink X
```

The earlier client could report a successful Xray timing probe while not proving
that Android packets reached HEV or that HEV reached the private SOCKS listener.
Its HEV configuration also overrode the current upstream production buffer sizes
with substantially smaller values.

## Corrective instrumentation in 0.6.12

- Verify the actual SOCKS listener with an RFC 1928 greeting before starting HEV.
- Require the asynchronous native HEV worker to remain running for a stable startup
  interval; include its redacted native log tail in startup failures.
- Use the upstream HEV production stack, TCP buffer, UDP receive buffer, and UDP
  splice-buffer defaults pinned by the current v2rayNG integration.
- Run a real 32 KiB HTTPS payload transfer and UDP/DNS query through the selected
  outbound after connection.
- Record HEV packet/byte counters next to Xray proxy counters. These counters make
  the next device report deterministic:
  - SOCKS self-test passes, HEV stays at zero: Android routing/app filter boundary;
  - HEV increases, Xray stays at zero: HEV-to-SOCKS boundary;
  - Xray uplink increases without downlink: remote return path.

The server-side diagnostic process was independent of the production process. It
was stopped after the tests, its temporary files were deleted, and the production
node remained running with restart count zero.

## Android startup regression and 0.6.13 hotfix

Version 0.6.12 introduced an Android-only false negative in the new SOCKS
readiness check. Xray binds its private inbound to `127.0.0.1`, while
`InetAddress.getLoopbackAddress()` may select IPv6 `::1` on a device. The check
then waited five seconds, reported that the system TUN bridge could not start,
and stopped Xray before HEV was launched. This matches the device timestamps and
affected every protocol because they share the same local data plane.

Version 0.6.13 pins allocation, readiness checks, HTTPS probes, and UDP relay
fallback to the numeric IPv4 loopback address. A regression test asserts both
the address value and its four-byte address family. Startup errors now retain
the nested failure message instead of reporting only the generic bridge error.

## Reconnect isolation and 0.6.14 fix

The later device log proved that the shared Android route works: VLESS produced
bidirectional HEV and Xray counters immediately. ALX then accepted one session,
but subsequent sessions sometimes left Xray counters at zero. On the FI host,
the same ALX credentials and REALITY parameters completed repeated 32 KiB HTTP
transfers, including five requests matching the Android self-test. This excludes
the panel, FI listener, authentication data, REALITY, X-Wing and server egress.

ALX was the only Android outbound that unconditionally requested MPTCP and a
10-second TCP user timeout. Both are kernel/carrier-sensitive mobile socket
features; neither is part of ALX authentication or encryption. Version 0.6.14
retains Turbo framing, REALITY, X-Wing, inner AEAD and Stealth, but disables MPTCP
on Android and uses a 60-second user timeout. It also exports credential-free
stage counters for `dial`, `ClientInit`, `ServerAccept`, TCP/UDP readiness and the
last bounded error. CI now verifies five client-core restarts against one
persistent ALX+REALITY server process.

## Android cancellation cascade and 0.6.15 fix

The device trace from 2026-09-04 proved that REALITY, X-Wing, ALX
authentication and both UDP/TCP payload setup completed. At the same time,
`connections` rose from 5 to 42 while `dial_ok` rose only from 1 to 4, followed
by `lookup saf.sinfor.fun: operation was canceled` and payload
`context canceled`.

Version 0.6.15 addresses both demonstrated cancellation paths:

- Android resolves an ALX domain before establishing the TUN and uses the
  selected numeric endpoint for that VPN session. The original domain remains
  the REALITY SNI, so certificate authentication and camouflage do not change.
- The ALX outbound now handles Xray timeout-only contexts exactly like VLESS,
  VMess and Trojan: a completed stream owns a separately cancellable payload
  context instead of inheriting a short-lived setup context.

No server credential, cryptographic primitive or wire-format field changed.
