# AetherLink X native integration for Remnawave

This directory contains the version-pinned integration that makes
`aetherlink` a first-class Remnawave protocol. It does not translate ALX to
VLESS and it does not run ALX through Xray.

The integration currently targets:

- Remnawave Backend and Frontend `2.7.3`
- Remnawave Node `2.8.0`

The workflow builds patched images from the corresponding upstream tags and
publishes them to this project's GHCR namespace. The patches are kept separate
from the ALX core so upstream updates can be reviewed and rebased explicitly.

Upstream Remnawave components are licensed under AGPL-3.0-only. The patched
images and patch files retain that license and their upstream attribution.

## Runtime flow

1. A config profile contains a native `aetherlink` inbound.
2. The backend validates it, generates `aetherlink://` subscriptions, and sends
   a private runtime block to the selected node.
3. The patched node atomically writes the ALX runtime file and removes the
   private block before passing the remaining configuration to Xray.
4. `alx-server` reloads the token set from that runtime file without exposing
   the token in process arguments or replacing it with a VLESS credential.

The exact upstream versions are intentional. A new Remnawave release must pass
the build and integration checks before its version is added here.
