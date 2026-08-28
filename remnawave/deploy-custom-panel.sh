#!/usr/bin/env sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root." >&2
  exit 1
fi

PANEL_DIR=${PANEL_DIR:-/opt/remnawave}
ALX_BACKEND_IMAGE=${ALX_BACKEND_IMAGE:-}
if [ -z "$ALX_BACKEND_IMAGE" ]; then
  echo "Set ALX_BACKEND_IMAGE to the published GHCR image." >&2
  exit 1
fi
if [ ! -f "$PANEL_DIR/docker-compose.yml" ]; then
  echo "Missing $PANEL_DIR/docker-compose.yml" >&2
  exit 1
fi

override="$PANEL_DIR/docker-compose.alx.yml"
backup="$PANEL_DIR/docker-compose.yml.alx-backup"
cp -p "$PANEL_DIR/docker-compose.yml" "$backup"
cat > "$override" <<EOF
services:
  remnawave:
    image: ${ALX_BACKEND_IMAGE}
    pull_policy: always
EOF

cd "$PANEL_DIR"
docker compose -f docker-compose.yml -f docker-compose.alx.yml config >/dev/null
docker compose -f docker-compose.yml -f docker-compose.alx.yml pull remnawave
docker compose -f docker-compose.yml -f docker-compose.alx.yml up -d remnawave
docker compose -f docker-compose.yml -f docker-compose.alx.yml ps remnawave

echo "Custom AetherLink X panel backend is active."
echo "Rollback: cd $PANEL_DIR && rm docker-compose.alx.yml && docker compose up -d remnawave"
