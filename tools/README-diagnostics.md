# AetherLink X diagnostics

`aetherlinkx_diagnose.py` inspects subscription responses and Xray JSON without
printing UUIDs, passwords, account secrets, REALITY keys, or X-Wing keys. Values
are represented by their length and the first 12 hexadecimal characters of a
SHA-256 digest. Equal fingerprints mean equal input values.

Examples:

```sh
python3 tools/aetherlinkx_diagnose.py tcp saf.sinfor.fun 8443
python3 tools/aetherlinkx_diagnose.py subscription 'https://example/sub/token'
python3 tools/aetherlinkx_diagnose.py body response.txt
python3 tools/aetherlinkx_diagnose.py config client.json
python3 tools/aetherlinkx_diagnose.py socks5-dns --proxy-port 10808
python3 tools/aetherlinkx_diagnose.py socks5-http --proxy-port 10808
python3 tools/aetherlinkx_diagnose.py socks5-http --proxy-port 10808 --pipeline
```

For Remnawave HWID-limited subscriptions, reuse an already registered device ID
instead of generating a new one:

```sh
python3 tools/aetherlinkx_diagnose.py subscription 'https://example/sub/token' \
  --hwid "$EXISTING_DEVICE_ID" --device-os Android --os-version 16
```

The direct `tcp` command only proves that an address is reachable. A full
protocol check must run the patched Xray binary with the inspected client
configuration, perform an HTTP request through its local SOCKS inbound, and use
`socks5-dns` to verify SOCKS UDP ASSOCIATE plus a real DNS response.

The Android client performs the same two data-plane checks automatically after
connection. Its redacted results and HEV native packet counters are available at
**Настройки → Диагностика**. A successful SOCKS HTTPS/UDP test together with zero
HEV counters means the protocol and server are healthy and the fault is before the
bridge (Android VPN routing or per-app filtering), not in REALITY.
