#!/usr/bin/env sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root." >&2
  exit 1
fi

PANEL_DIR=${PANEL_DIR:-/opt/remnawave}
ALX_BACKEND_IMAGE=${ALX_BACKEND_IMAGE:-}
ALX_MODE=${ALX_MODE:-static}
REMNAWAVE_PANEL_VERSION=${REMNAWAVE_PANEL_VERSION:-}
case "$ALX_MODE" in
  static|managed) ;;
  *)
    echo "Unsupported ALX_MODE: $ALX_MODE (expected static or managed)." >&2
    exit 1
    ;;
esac
if [ ! -f "$PANEL_DIR/docker-compose.yml" ]; then
  echo "Missing $PANEL_DIR/docker-compose.yml" >&2
  exit 1
fi

override="$PANEL_DIR/docker-compose.alx.yml"
backup="$PANEL_DIR/docker-compose.yml.alx-backup"
override_backup="$PANEL_DIR/docker-compose.alx.yml.previous"

restore_panel() {
  if [ -f "$override_backup" ]; then
    cp -p "$override_backup" "$override"
    docker compose -f docker-compose.yml -f docker-compose.alx.yml up -d remnawave || true
  else
    rm -f "$override"
    docker compose -f docker-compose.yml up -d remnawave || true
  fi
}

cd "$PANEL_DIR"

if [ -z "$REMNAWAVE_PANEL_VERSION" ]; then
  current_image=$(docker compose -f docker-compose.yml config --images | grep -E '(^|/)(remnawave/)?backend:|aetherlink-x-remnawave-backend' | head -n 1 || true)
  case "$current_image" in
    *aetherlink-x-remnawave-backend-managed-2.7.4*|*aetherlink-x-remnawave-backend-2.7.4*|*:2.7.4*) REMNAWAVE_PANEL_VERSION=2.7.4 ;;
    *aetherlink-x-remnawave-backend:sha-*|*aetherlink-x-remnawave-backend:latest|*:3.3.2*) REMNAWAVE_PANEL_VERSION=3.3.2 ;;
    *)
      echo "Cannot safely detect the Remnawave Panel version from: ${current_image:-<empty>}." >&2
      echo "Set REMNAWAVE_PANEL_VERSION to an explicitly supported version." >&2
      exit 1
      ;;
  esac
fi

if [ -z "$ALX_BACKEND_IMAGE" ]; then
  case "$ALX_MODE:$REMNAWAVE_PANEL_VERSION" in
    static:2.7.4) ALX_BACKEND_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-backend-2.7.4:sha-6a091c7 ;;
    static:3.3.2) ALX_BACKEND_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-backend:sha-6c536a9 ;;
    managed:2.7.4) ALX_BACKEND_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-backend-managed-2.7.4:sha-eb469f9 ;;
    *)
      echo "Unsupported Remnawave Panel/mode pair: $REMNAWAVE_PANEL_VERSION/$ALX_MODE" >&2
      echo "No files or containers were changed." >&2
      exit 1
      ;;
  esac
fi

cp -p "$PANEL_DIR/docker-compose.yml" "$backup"
if [ -f "$override" ]; then
  cp -p "$override" "$override_backup"
fi
cat > "$override" <<EOF
services:
  remnawave:
    image: ${ALX_BACKEND_IMAGE}
    pull_policy: always
EOF

docker compose -f docker-compose.yml -f docker-compose.alx.yml config >/dev/null
docker compose -f docker-compose.yml -f docker-compose.alx.yml pull remnawave
if ! docker compose -f docker-compose.yml -f docker-compose.alx.yml up -d remnawave; then
  echo "Backend start failed; restoring the previous override." >&2
  restore_panel
  exit 1
fi
docker compose -f docker-compose.yml -f docker-compose.alx.yml ps remnawave

echo "Custom AetherLink X panel backend is active for Remnawave Panel $REMNAWAVE_PANEL_VERSION in $ALX_MODE mode."
echo "Rollback: restore $override_backup when present (or remove $override), then run docker compose up -d remnawave."
