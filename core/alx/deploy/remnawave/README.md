# Native Remnawave integration

This integration makes AetherLink X a first-class Remnawave protocol. It does
not create a VLESS control inbound and it does not distribute ALX through a
custom response header.

The patched panel stores an inbound with `"protocol": "aetherlink"`, creates a
normal Host linked to that inbound, and emits a native `aetherlink://` entry in
the user's subscription. The patched Node removes the private `aetherlink`
runtime block before Xray starts and atomically applies it to the independent
ALX core. Xray never parses or serves ALX traffic.

## Native profile format

```json
{
  "inbounds": [
    {
      "tag": "AETHERLINK_NATIVE",
      "port": 443,
      "protocol": "aetherlink",
      "settings": {
        "token": "generated-secret",
        "previousTokens": [],
        "pin": "64-character-certificate-SPKI-SHA256",
        "sni": "alx.example.com",
        "mode": "turbo",
        "fallback": "auto"
      }
    }
  ],
  "outbounds": [
    { "tag": "DIRECT", "protocol": "freedom" },
    { "tag": "BLOCK", "protocol": "blackhole" }
  ]
}
```

Only one native ALX inbound may be active on a node. The protocol has its own
TCP and QUIC listener, certificate pin, authentication token and runtime. The
ALX token can be rotated with up to two previous tokens for a graceful client
migration.

## Panel installation

On the server that already runs Remnawave Panel:

```bash
curl -fsSL https://raw.githubusercontent.com/AetherLinkX/aetherlink-x/alx/native-preview/core/alx/deploy/remnawave/install-panel.sh \
  | sudo bash
```

The panel installer changes only the effective `remnawave` backend image,
automatically detects active Compose override files, backs up the complete
Compose set and restores it without pulling on rollback if the replacement is
not healthy. PostgreSQL, Valkey, volumes, environment variables and existing
Xray profiles are not changed.

## One-command node installation

After deploying the patched panel backend, create a panel API token and run on
the node VPS:

```bash
export REMNAWAVE_PANEL_URL='https://panel.example.com'
export REMNAWAVE_API_TOKEN='panel API token'
curl -fsSL https://raw.githubusercontent.com/AetherLinkX/aetherlink-x/alx/native-preview/core/alx/deploy/remnawave/install.sh \
  | sudo -E bash -s -- \
      --domain alx.example.com \
      --email admin@example.com \
      --panel-ip 198.51.100.10
```

The installer:

- backs up the existing node and ALX configuration;
- installs the ALX core and certificate;
- starts the compatible Remnawave Node;
- creates or updates the native `AetherLink X` Config Profile;
- creates the native Host and `AetherLink X` Internal Squad;
- assigns and enables the node on the native inbound without changing existing
  profiles;
- reuses the live ALX token when attaching an existing runtime and keeps up to
  two previous tokens for a graceful client migration.

Assign users to the generated Internal Squad to include ALX in their normal
subscription. Private tokens and profile links are written only to root-owned
files on the node.

## Compatibility

The integration is version-pinned because Remnawave's backend and node APIs
change between major releases. The current tested baseline is Panel 2.7.4 with
Node 2.8.0. New releases use dedicated overlays and build tests instead of
silently falling back to VLESS.
