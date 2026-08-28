#!/usr/bin/env sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root." >&2
  exit 1
fi

REMNANODE_DIR=${REMNANODE_DIR:-/opt/remnanode}
ALX_NODE_IMAGE=${ALX_NODE_IMAGE:-}
if [ -z "$ALX_NODE_IMAGE" ]; then
  echo "Set ALX_NODE_IMAGE to the published GHCR image." >&2
  exit 1
fi
if [ ! -f "$REMNANODE_DIR/docker-compose.yml" ]; then
  echo "Missing $REMNANODE_DIR/docker-compose.yml" >&2
  exit 1
fi

override="$REMNANODE_DIR/docker-compose.alx.yml"
backup="$REMNANODE_DIR/docker-compose.yml.alx-backup"
cp -p "$REMNANODE_DIR/docker-compose.yml" "$backup"
cat > "$override" <<EOF
services:
  remnanode:
    image: ${ALX_NODE_IMAGE}
    pull_policy: always
EOF

cd "$REMNANODE_DIR"
docker compose -f docker-compose.yml -f docker-compose.alx.yml config >/dev/null
docker compose -f docker-compose.yml -f docker-compose.alx.yml pull remnanode
docker compose -f docker-compose.yml -f docker-compose.alx.yml up -d remnanode
docker compose -f docker-compose.yml -f docker-compose.alx.yml exec -T remnanode /usr/local/bin/xray version

echo "Custom AetherLink X node image is active."
echo "Rollback: cd $REMNANODE_DIR && docker compose -f docker-compose.yml -f docker-compose.alx.yml down && rm docker-compose.alx.yml && docker compose up -d"
