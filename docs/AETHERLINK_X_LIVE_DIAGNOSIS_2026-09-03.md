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
