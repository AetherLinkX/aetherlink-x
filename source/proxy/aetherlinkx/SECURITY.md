# AetherLink X security notes

## Trust boundaries

ALX assumes the outer Xray TLS or REALITY transport is correctly configured and that the client has an authentic server transport key. `allowInsecureTransport` is only a test/private-network escape hatch. WARP changes the route and visible client IP; it is not server authentication and does not replace REALITY/TLS.

The long-term ALX account secret authenticates a user. The server X-Wing private seed provides the hybrid KEM component. Compromise of either server-side value requires rotation. Logs must never include either secret.

## Handshake properties

- `key_id` is a 128-bit truncated HMAC derived from the UUID and account secret, not a plaintext UUID lookup.
- `ClientInit` authenticates version, command, flags, timestamp, Turbo parameters, X-Wing ciphertext and destination.
- `ServerAccept` authenticates the selected features, server nonce and echoed session ID.
- client and server binder keys use different HKDF labels;
- a ±2 minute authenticated timestamp window and bounded `(key_id, client_nonce)` cache reject delayed and immediate replay; an entry is inserted only after the complete authenticated request and server feature policy pass validation;
- requested inner AEAD must match server policy, and requested Turbo fails closed when unavailable;
- the client rejects authenticated PQ, inner-AEAD and Turbo downgrade/mismatch;
- parser lengths and destination-cache sizes are bounded before allocation/use;
- framing handles legal short writes and treats a zero-byte write as `io.ErrShortWrite`.

X-Wing ciphertext decapsulation occurs before the request binder can be checked. This is unavoidable for a binder keyed by the hybrid shared secret and creates a CPU-amplification surface for peers that have reached the outer transport. Deploy connection/rate limits at the inbound and keep REALITY/TLS authentication in front of ALX.

## Payload protection

With `innerAead: true`, ALX derives separate ChaCha20-Poly1305 keys for each direction from the account secret, optional X-Wing secret, both nonces and session ID. Record length is authenticated as associated data. Counters never repeat under a session key and overflow fails closed. Malformed lengths, padding and tags terminate the stream.

Without inner AEAD, payload confidentiality and integrity come only from TLS/REALITY. This lower-overhead mode is not safe over plaintext public transport.

Stealth padding is encrypted and bounded. It is traffic shaping, not a cryptographic primitive, not a proof of indistinguishability and not a faithful video-call emulator.

## Residual risks

- the protocol and X-Wing composition have not received an independent cryptographic audit;
- Go-managed secret byte slices are not guaranteed to be wiped from memory;
- traffic timing, direction and approximate sizes remain observable;
- inner AEAD has no mid-session rekey; session lifetime and byte limits are inherited from connection policy;
- unknown-user lookup may be distinguishable by timing after the outer connection is established;
- TCP-carried UDP is subject to head-of-line blocking even when stale packets are discarded at the receiver;
- replay defense depends on synchronized clocks and in-process cache state; it is not shared across clustered servers;
- `pqMode: "prefer"` is opportunistic on the server, but a client configured with a public key sends X-Wing and does not silently retry without PQ after a failed connection.

## Deployment checklist

1. Generate credentials with `xray aetherlinkx-keygen`; do not invent passwords or reuse REALITY/WireGuard keys.
2. Keep the X-Wing private seed only on the server and the account secret on both endpoints.
3. Use REALITY or TLS; never enable `allowInsecureTransport` on the Internet.
4. Synchronize system clocks and rate-limit new inbound handshakes.
5. Start with `pqMode: "required"` only after confirming both peers run the same experimental wire version.
6. Benchmark inner AEAD and Stealth on the deployment CPU before enabling them for latency-sensitive traffic.
7. Treat MPTCP, BBR, Mux and WARP as measured routing/OS policies, not unconditional accelerators.
8. Rotate account and X-Wing keys after suspected compromise; restarting servers also clears replay state.

## Validation performed

- unit tests cover handshake round-trip, tampering, replay, stale timestamp, downgrade, X-Wing/AEAD, short writes, key-side validation and UDP/Turbo framing;
- end-to-end Xray scenarios cover TLS TCP and Turbo UDP with required X-Wing, inner AEAD and Stealth;
- fuzz targets cover `ClientInit` decoding and encrypted record decoding;
- `go vet ./proxy/aetherlinkx` passes;
- a Go race build was not available in the portable Windows toolchain because no CGO C compiler is installed.

This is an engineering review, not a third-party security certification.
