# Remnawave Node + ALX installer

This installer deploys the official Remnawave Node and AetherLink X Turbo on
the same Debian/Ubuntu VPS. They are separate processes: Remnawave continues
to manage Xray while ALX listens on its own TCP+UDP port.

## Fully automatic installation

Create a Remnawave API token with permission to read the system/configuration
and manage nodes and External Squads. Point the ALX domain to the VPS, then run:

```bash
export REMNAWAVE_PANEL_URL='https://panel.example.com'
export REMNAWAVE_API_TOKEN='panel API token'
curl -fsSL https://raw.githubusercontent.com/AetherLinkX/aetherlink-x/main/core/alx/deploy/remnawave/install.sh \
  | sudo -E bash -s -- \
      --domain alx.example.com \
      --email admin@example.com \
      --panel-ip 198.51.100.10 \
      --panel-profile 'Default-Profile' \
      --panel-node-name 'AetherLink X' \
      --panel-squad-name 'AetherLink X'
```

The API token is consumed from the environment and is not saved by the
installer. In this mode the installer obtains the Node key, registers the Node,
creates or reuses the named External Squad, and adds the private
`X-AetherLink-Profile` subscription header. If the panel contains exactly one
config profile, `--panel-profile` can be omitted.

Finally, assign the users who should receive ALX to the created External Squad.
Their existing Internal Squads, VLESS locations, hosts and config profiles are
not changed.

## Manual installation

Create a Node in the panel first and copy its secret. Point the ALX domain to
the VPS, then run:

```bash
export REMNAWAVE_SECRET_KEY='value copied from the panel'
curl -fsSL https://raw.githubusercontent.com/AetherLinkX/aetherlink-x/main/core/alx/deploy/remnawave/install.sh \
  | sudo -E bash -s -- \
      --domain alx.example.com \
      --email admin@example.com \
      --panel-ip 198.51.100.10
```

The default ports are `2222/tcp` for the panel-to-node API and `8443/tcp+udp`
for ALX. Port 80 is used for ACME renewal. If UFW is active, the installer
allows the Node API only from `--panel-ip`.

After installation, read `/root/aetherlink-remnawave-summary.txt`. It contains
the node address, certificate pin, private ALX profile and the exact
`X-AetherLink-Profile` response header to add in Remnawave. Automatic mode also
writes non-secret panel object identifiers to
`/root/aetherlink-remnawave-panel.json`.

## Panel generations

| Mode | Environment passed to Remnawave Node |
|---|---|
| `auto` | Both `NODE_PORT`/`SECRET_KEY` and `APP_PORT`/`SSL_CERT` |
| `modern` | Remnawave Node 2.2.2+ and 3.x names only |
| `legacy` | Older `APP_PORT`/`SSL_CERT` names only |

`auto` is the default. The Node image can be pinned independently with
`--remnawave-image remnawave/node:<version>`, so panel upgrades do not require
changing ALX.

Remnawave v2 stores custom headers in `responseHeaders`; v3 calls the field
`responseHeadersAdd`. AetherLink X reads the emitted HTTP header instead of
depending on either API representation, so the subscription format stays
stable across those releases. Existing Xray locations remain in the same
subscription.

The automatically registered Node intentionally has no active Xray inbound.
ALX is its own protocol and owns its TCP/UDP listener; assigning an Xray inbound
to the same port would create a collision. The official Node remains online for
panel compatibility while the External Squad distributes the ALX profile.

## Idempotence and recovery

Re-running the command updates both services, renews configuration and keeps
the current ALX token. Use `--rotate-alx-token` only for an intentional token
rollout. Before modifying an existing installation, the script archives the
three managed locations under `/var/backups/aetherlink-remnawave/`.

For a private GitHub repository, export a read-only `GH_TOKEN` so the installer
can download the release asset. The installer itself can be fetched through
GitHub's authenticated contents API:

```bash
curl -fsSL \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H 'Accept: application/vnd.github.raw+json' \
  'https://api.github.com/repos/AetherLinkX/aetherlink-x/contents/core/alx/deploy/remnawave/install.sh?ref=main' \
  | sudo -E bash -s -- --domain alx.example.com --email admin@example.com \
      --panel-ip 198.51.100.10
```

Public releases need no token.

## Limits

ALX is not an Xray inbound, so the panel cannot push ALX wire configuration
through Xray's API. The installer deliberately uses a sidecar and a subscription
response header instead of patching Remnawave or pretending ALX is VLESS. User
accounting for native ALX is shared-token based in Preview 8; per-user panel
accounting requires a future ALX authentication adapter.
