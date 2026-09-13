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
grep -q 'image: remnawave/node:latest' "$TEMP_DIR/auto/opt/remnanode/docker-compose.yml"
grep -q 'ALX_LISTEN=:8443' "$TEMP_DIR/auto/etc/aetherlink-x/alx.env"
grep -q 'X-AetherLink-Profile' "$TEMP_DIR/auto/root/aetherlink-remnawave-summary.txt"
grep -q 'aetherlink://.*@alx.example.com:8443' "$TEMP_DIR/auto/root/aetherlink-remnawave-summary.txt"

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

printf 'Remnawave installer render tests passed\n'
