#!/usr/bin/env sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root." >&2
  exit 1
fi

REMNANODE_DIR=${REMNANODE_DIR:-/opt/remnanode}
ALX_NODE_IMAGE=${ALX_NODE_IMAGE:-}
ALX_MODE=${ALX_MODE:-static}
REMNAWAVE_NODE_VERSION=${REMNAWAVE_NODE_VERSION:-}
case "$ALX_MODE" in
  static|managed) ;;
  *)
    echo "Unsupported ALX_MODE: $ALX_MODE (expected static or managed)." >&2
    exit 1
    ;;
esac
if [ ! -f "$REMNANODE_DIR/docker-compose.yml" ]; then
  echo "Missing $REMNANODE_DIR/docker-compose.yml" >&2
  exit 1
fi

override="$REMNANODE_DIR/docker-compose.alx.yml"
backup="$REMNANODE_DIR/docker-compose.yml.alx-backup"
override_backup="$REMNANODE_DIR/docker-compose.alx.yml.previous"

restore_node() {
  if [ -f "$override_backup" ]; then
    cp -p "$override_backup" "$override"
    docker compose -f docker-compose.yml -f docker-compose.alx.yml up -d remnanode || true
  else
    rm -f "$override"
    docker compose -f docker-compose.yml up -d remnanode || true
  fi
}

cd "$REMNANODE_DIR"

if [ -z "$REMNAWAVE_NODE_VERSION" ]; then
  current_image=$(docker compose -f docker-compose.yml config --images | grep -E '(^|/)(remnawave/)?node:' | head -n 1 || true)
  case "$current_image" in
    *aetherlink-x-remnawave-node-2.7.0*|*:2.7.0*) REMNAWAVE_NODE_VERSION=2.7.0 ;;
    *aetherlink-x-remnawave-node-managed-3.2.2*|*aetherlink-x-remnawave-node-3.2.2*|*:3.2.2*) REMNAWAVE_NODE_VERSION=3.2.2 ;;
    *aetherlink-x-remnawave-node:sha-*|*aetherlink-x-remnawave-node:latest|*:3.3.2*) REMNAWAVE_NODE_VERSION=3.3.2 ;;
    *)
      echo "Cannot safely detect the Remnawave Node version from: ${current_image:-<empty>}." >&2
      echo "Set REMNAWAVE_NODE_VERSION to an explicitly supported version." >&2
      exit 1
      ;;
  esac
fi

if [ -z "$ALX_NODE_IMAGE" ]; then
  case "$ALX_MODE:$REMNAWAVE_NODE_VERSION" in
    static:2.7.0) ALX_NODE_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-node-2.7.0:sha-6a091c7 ;;
    static:3.2.2) ALX_NODE_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-node-3.2.2:sha-e1f9593 ;;
    static:3.3.2) ALX_NODE_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-node:sha-6c536a9 ;;
    managed:3.2.2) ALX_NODE_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-node-managed-3.2.2:sha-e1f9593 ;;
    *)
      echo "Unsupported Remnawave Node/mode pair: $REMNAWAVE_NODE_VERSION/$ALX_MODE" >&2
      echo "No files or containers were changed." >&2
      exit 1
      ;;
  esac
fi

cp -p "$REMNANODE_DIR/docker-compose.yml" "$backup"
if [ -f "$override" ]; then
  cp -p "$override" "$override_backup"
fi
cat > "$override" <<EOF
services:
  remnanode:
    image: ${ALX_NODE_IMAGE}
    pull_policy: always
EOF

docker compose -f docker-compose.yml -f docker-compose.alx.yml config >/dev/null
docker compose -f docker-compose.yml -f docker-compose.alx.yml pull remnanode
if ! docker compose -f docker-compose.yml -f docker-compose.alx.yml up -d remnanode; then
  echo "Node start failed; restoring the previous override." >&2
  restore_node
  exit 1
fi

if ! docker compose -f docker-compose.yml -f docker-compose.alx.yml exec -T remnanode /usr/local/bin/xray version; then
  echo "ALX Xray verification failed; restoring the previous override." >&2
  restore_node
  exit 1
fi

echo "Custom AetherLink X node image is active for Remnawave Node $REMNAWAVE_NODE_VERSION in $ALX_MODE mode."
echo "Rollback: restore $override_backup when present (or remove $override), then run docker compose up -d remnanode."
