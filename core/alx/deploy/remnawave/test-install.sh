#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEMP_DIR"' EXIT

REMNAWAVE_SECRET_KEY='test-secret-key' ALX_TOKEN='abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG' \
  bash "$SCRIPT_DIR/install.sh" \
    --domain alx.example.com \
    --email admin@example.com \
    --panel-ip 192.0.2.10 \
    --dry-run "$TEMP_DIR/auto"

grep -q '^NODE_PORT=2222$' "$TEMP_DIR/auto/opt/remnanode/.env"
grep -q '^APP_PORT=2222$' "$TEMP_DIR/auto/opt/remnanode/.env"
grep -q '^SECRET_KEY=' "$TEMP_DIR/auto/opt/remnanode/.env"
grep -q '^SSL_CERT=' "$TEMP_DIR/auto/opt/remnanode/.env"
grep -q '^ALX_RUNTIME_CONFIG_GID=991$' "$TEMP_DIR/auto/opt/remnanode/.env"
grep -q 'image: ghcr.io/aetherlinkx/remnawave-node-alx:2.8.0-native.2' "$TEMP_DIR/auto/opt/remnanode/docker-compose.yml"
grep -q '/etc/aetherlink-x:/etc/aetherlink-x' "$TEMP_DIR/auto/opt/remnanode/docker-compose.yml"
grep -q 'ALX_LISTEN=:8443' "$TEMP_DIR/auto/etc/aetherlink-x/alx.env"
grep -q 'ALX_RUNTIME_CONFIG_FILE=/etc/aetherlink-x/panel-runtime.json' "$TEMP_DIR/auto/etc/aetherlink-x/alx.env"
grep -q '^User=aetherlink$' "$TEMP_DIR/auto/etc/systemd/system/aetherlink-native.service"
grep -q 'aetherlink://.*@alx.example.com:8443' "$TEMP_DIR/auto/root/aetherlink-remnawave-summary.txt"
grep -q 'fallback=8443' "$TEMP_DIR/auto/root/aetherlink-remnawave-summary.txt"
grep -q '"activeInbounds": \[selected_inbound\["uuid"\]\]' "$SCRIPT_DIR/install.sh"
grep -q 'REMNAWAVE_IMAGE="ghcr.io/aetherlinkx/remnawave-node-alx:2.8.0-native.2"' "$SCRIPT_DIR/install.sh"
grep -q 'Reusing the existing Remnawave Node key' "$SCRIPT_DIR/install.sh"
grep -q -- '--rotate-node-key' "$SCRIPT_DIR/install.sh"
grep -q 'PANEL_PROFILE="AetherLink X"' "$SCRIPT_DIR/install.sh"
grep -q '"tag": "AETHERLINK_NATIVE"' "$SCRIPT_DIR/install.sh"
grep -q '"protocol": "aetherlink"' "$SCRIPT_DIR/install.sh"
grep -q 'request("POST", "config-profiles/"' "$SCRIPT_DIR/install.sh"
grep -q 'nodes/{node\["uuid"\]}/actions/enable' "$SCRIPT_DIR/install.sh"
grep -q 'ALX_PREVIOUS_TOKENS_PANEL' "$SCRIPT_DIR/install.sh"
grep -q 'urlsafe_b64decode' "$SCRIPT_DIR/install.sh"
grep -q 'native_fallback_value in {"auto", "quic", "tcp"}' "$SCRIPT_DIR/install.sh"
grep -q 'fallback: String(host.port)' "$SCRIPT_DIR/../../../../integrations/remnawave-native/patches/backend-2.7.3.patch"
grep -q 'merged_config.setdefault("inbounds", \[\])' "$SCRIPT_DIR/install.sh"
grep -q 'desired_inbound_ids = list(dict.fromkeys' "$SCRIPT_DIR/install.sh"
grep -q '"address": node.get("address") or domain' "$SCRIPT_DIR/install.sh"
grep -q 'if ! command -v docker >/dev/null 2>&1; then' "$SCRIPT_DIR/install.sh"
grep -q 'native_tag += "_" + selected\["uuid"\].replace' "$SCRIPT_DIR/install.sh"
grep -q 'profile_explicit != "true"' "$SCRIPT_DIR/install.sh"
! grep -q 'AETHERLINK_X_CONTROL' "$SCRIPT_DIR/install.sh"
! grep -q 'X-AetherLink-Profile' "$SCRIPT_DIR/install.sh"
! grep -q 'responseHeadersAdd' "$SCRIPT_DIR/install.sh"

REMNAWAVE_SECRET_KEY='legacy-value' ALX_TOKEN='abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG' \
  bash "$SCRIPT_DIR/install.sh" \
    --domain legacy.example.com \
    --email admin@example.com \
    --panel-ip 192.0.2.10 \
    --compat legacy \
    --dry-run "$TEMP_DIR/legacy"

grep -q '^APP_PORT=2222$' "$TEMP_DIR/legacy/opt/remnanode/.env"
grep -q '^SSL_CERT=' "$TEMP_DIR/legacy/opt/remnanode/.env"
! grep -q '^NODE_PORT=' "$TEMP_DIR/legacy/opt/remnanode/.env"
! grep -q '^SECRET_KEY=' "$TEMP_DIR/legacy/opt/remnanode/.env"

printf '%s\n' 'aetherlink://existing-token@alx.example.com:443?pin=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&sni=alx.example.com&mode=turbo#Existing' \
  >"$TEMP_DIR/existing-profile.txt"
REMNAWAVE_SECRET_KEY='attach-value' \
  bash "$SCRIPT_DIR/install.sh" \
    --domain alx.example.com \
    --email admin@example.com \
    --panel-ip 192.0.2.10 \
    --existing-alx-profile-file "$TEMP_DIR/existing-profile.txt" \
    --dry-run "$TEMP_DIR/attach"

grep -q '^NODE_PORT=2222$' "$TEMP_DIR/attach/opt/remnanode/.env"
grep -q 'aetherlink://existing-token@alx.example.com:443' "$TEMP_DIR/attach/root/aetherlink-remnawave-summary.txt"
grep -q 'Detected existing ALX listener on port' "$SCRIPT_DIR/install.sh"
! test -f "$TEMP_DIR/attach/etc/systemd/system/aetherlink-native.service"

cat >"$TEMP_DIR/panel-compose.yml" <<'EOF'
services:
  remnawave:
    image: remnawave/backend:2.7.3
  remnawave-db:
    image: postgres:17
EOF

panel_dry_run="$(bash "$SCRIPT_DIR/install-panel.sh" \
  --compose-file "$TEMP_DIR/panel-compose.yml" --dry-run)"
grep -q 'remnawave/backend:2.7.3 -> ghcr.io/aetherlinkx/remnawave-backend-alx:2.7.4-native.3' \
  <<<"$panel_dry_run"
grep -q 'image: remnawave/backend:2.7.3' "$TEMP_DIR/panel-compose.yml"

cat >"$TEMP_DIR/panel-override.yml" <<'EOF'
services:
  remnawave:
    image: remnawave/backend:custom
    environment:
      REMNAWAVE_BRANCH: main
EOF

panel_override_dry_run="$(bash "$SCRIPT_DIR/install-panel.sh" \
  --compose-file "$TEMP_DIR/panel-compose.yml" \
  --compose-file "$TEMP_DIR/panel-override.yml" \
  --dry-run)"
grep -q "Image override: .*panel-override.yml" <<<"$panel_override_dry_run"
grep -q 'remnawave/backend:custom -> ghcr.io/aetherlinkx/remnawave-backend-alx:2.7.4-native.3' \
  <<<"$panel_override_dry_run"
grep -q 'image: remnawave/backend:custom' "$TEMP_DIR/panel-override.yml"

if REMNAWAVE_PANEL_URL='https://panel.example.com' REMNAWAVE_SECRET_KEY='test' \
  bash "$SCRIPT_DIR/install.sh" \
    --domain invalid.example.com \
    --email admin@example.com \
    --panel-ip 192.0.2.10 \
    --dry-run "$TEMP_DIR/incomplete-panel" 2>/dev/null; then
  printf 'Installer accepted a panel URL without an API token\n' >&2
  exit 1
fi

printf 'Remnawave installer render tests passed\n'
