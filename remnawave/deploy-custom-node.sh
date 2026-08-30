#!/usr/bin/env sh
set -eu

if [ "$(id -u)" -ne 0 ]; then
  echo "Run as root." >&2
  exit 1
fi

REMNANODE_DIR=${REMNANODE_DIR:-/opt/remnanode}
ALX_NODE_IMAGE=${ALX_NODE_IMAGE:-}
ALX_MODE=${ALX_MODE:-static}
ALX_COMPOSE_STRATEGY=${ALX_COMPOSE_STRATEGY:-in-place}
REMNAWAVE_NODE_VERSION=${REMNAWAVE_NODE_VERSION:-}

case "$ALX_MODE" in
  static|managed) ;;
  *)
    echo "Unsupported ALX_MODE: $ALX_MODE (expected static or managed)." >&2
    exit 1
    ;;
esac

case "$ALX_COMPOSE_STRATEGY" in
  in-place|override) ;;
  *)
    echo "Unsupported ALX_COMPOSE_STRATEGY: $ALX_COMPOSE_STRATEGY (expected in-place or override)." >&2
    exit 1
    ;;
esac

base="$REMNANODE_DIR/docker-compose.yml"
override="$REMNANODE_DIR/docker-compose.alx.yml"
if [ ! -f "$base" ]; then
  echo "Missing $base" >&2
  exit 1
fi

cd "$REMNANODE_DIR"

if [ -z "$REMNAWAVE_NODE_VERSION" ]; then
  current_image=$(docker compose -f "$base" config --images | grep -E '(^|/)(remnawave/)?node:' | head -n 1 || true)
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
    managed:3.2.2) ALX_NODE_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-node-managed-3.2.2:sha-eb469f9 ;;
    *)
      echo "Unsupported Remnawave Node/mode pair: $REMNAWAVE_NODE_VERSION/$ALX_MODE" >&2
      echo "No files or containers were changed." >&2
      exit 1
      ;;
  esac
fi

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$REMNANODE_DIR/docker-compose.yml.alx-backup.$timestamp"
override_backup="$REMNANODE_DIR/docker-compose.alx.yml.alx-backup.$timestamp"
tmp="$REMNANODE_DIR/.docker-compose.alx.$$.tmp"
cp -p "$base" "$backup"

restore_node() {
  if [ "$ALX_COMPOSE_STRATEGY" = "in-place" ]; then
    cp -p "$backup" "$base"
    docker compose -f "$base" up -d remnanode || true
  elif [ -f "$override_backup" ]; then
    cp -p "$override_backup" "$override"
    docker compose -f "$base" -f "$override" up -d remnanode || true
  else
    rm -f "$override"
    docker compose -f "$base" up -d remnanode || true
  fi
}

if [ "$ALX_COMPOSE_STRATEGY" = "in-place" ]; then
  if ! awk -v image="$ALX_NODE_IMAGE" '
    /^  remnanode:[[:space:]]*$/ { in_service = 1; print; next }
    in_service && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { in_service = 0 }
    in_service && /^    image:[[:space:]]*/ {
      print "    image: " image
      changed++
      next
    }
    { print }
    END { if (changed != 1) exit 42 }
  ' "$base" > "$tmp"; then
    rm -f "$tmp"
    echo "Expected exactly one services.remnanode.image field; no change was applied." >&2
    exit 1
  fi

  if ! docker compose -f "$tmp" config >/dev/null; then
    rm -f "$tmp"
    echo "Generated Compose file is invalid; no change was applied." >&2
    exit 1
  fi
  mv "$tmp" "$base"
  compose_args="-f $base"
else
  if [ -f "$override" ]; then
    cp -p "$override" "$override_backup"
  fi
  cat > "$override" <<EOF
services:
  remnanode:
    image: ${ALX_NODE_IMAGE}
    pull_policy: always
EOF
  docker compose -f "$base" -f "$override" config >/dev/null
  compose_args="-f $base -f $override"
fi

# shellcheck disable=SC2086
docker compose $compose_args pull remnanode
# shellcheck disable=SC2086
if ! docker compose $compose_args up -d remnanode; then
  echo "Node start failed; restoring the previous Compose configuration." >&2
  restore_node
  exit 1
fi

# shellcheck disable=SC2086
if ! docker compose $compose_args exec -T remnanode /usr/local/bin/xray version; then
  echo "ALX Xray verification failed; restoring the previous Compose configuration." >&2
  restore_node
  exit 1
fi

container_state=$(docker inspect --format '{{.State.Status}}' remnanode 2>/dev/null || true)
if [ "$container_state" != "running" ]; then
  echo "Node container is not running; restoring the previous Compose configuration." >&2
  restore_node
  exit 1
fi

echo "Custom AetherLink X node image is active for Remnawave Node $REMNAWAVE_NODE_VERSION in $ALX_MODE mode."
echo "Compose strategy: $ALX_COMPOSE_STRATEGY"
echo "Backup: $backup"
