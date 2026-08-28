# AetherLink X benchmark snapshot

This snapshot is a development baseline, not a cross-platform performance guarantee.

Environment: Windows amd64, Intel Core i7-7700HQ at 2.80 GHz, Go 1.26.0, `-benchtime=100x`.

| Benchmark | Time/op | Bytes/op | Allocs/op | Throughput |
|---|---:|---:|---:|---:|
| classic authenticated handshake | 29.449 µs | 6,019 | 76 | — |
| X-Wing + AEAD negotiation handshake | 695.718 µs | 25,342 | 84 | — |
| 1 KiB ChaCha20-Poly1305 record including layer setup | 13.290 µs | 5,040 | 41 | 77.05 MB/s |
| cached 512-byte Turbo UDP frame | 6.005 µs | 961 | 8 | 85.26 MB/s |

The X-Wing cost occurs once per transport connection, so connection reuse matters. The record benchmark intentionally includes HKDF and AEAD construction each iteration and therefore represents connection setup plus one record, not steady-state bulk throughput.

Reproduce with:

```text
go test ./proxy/aetherlinkx -run '^$' -bench '^BenchmarkAetherLinkX' -benchtime=100x -benchmem
```
