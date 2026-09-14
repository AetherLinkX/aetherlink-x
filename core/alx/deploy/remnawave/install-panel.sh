#!/usr/bin/env bash
set -Eeuo pipefail

readonly DEFAULT_IMAGE="ghcr.io/aetherlinkx/remnawave-backend-alx:2.7.4-native.1"

IMAGE="${REMNAWAVE_ALX_BACKEND_IMAGE:-$DEFAULT_IMAGE}"
COMPOSE_FILE=""
DRY_RUN="false"

log() { printf '[AetherLink X Panel] %s\n' "$*"; }
die() { printf '[AetherLink X Panel] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Install the version-pinned native AetherLink extension into Remnawave Panel.

Usage:
  sudo bash install-panel.sh [--compose-file FILE] [--image IMAGE] [--dry-run]

The script changes only the backend image in the existing Compose file. It
creates a timestamped backup and automatically restores it if the new backend
does not become healthy.
EOF
}

while (($#)); do
  case "$1" in
    --compose-file) COMPOSE_FILE="${2:-}"; shift 2 ;;
    --image) IMAGE="${2:-}"; shift 2 ;;
    --dry-run) DRY_RUN="true"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

find_compose_file() {
  local candidate
  for candidate in \
    /opt/remnawave/docker-compose-prod.yml \
    /opt/remnawave/docker-compose.yml \
    /opt/remnawave/compose.yml \
    /root/remnawave/docker-compose-prod.yml \
    /root/remnawave/docker-compose.yml; do
    if [[ -f "$candidate" ]] && grep -qE '^[[:space:]]*remnawave:[[:space:]]*$' "$candidate"; then
      printf '%s' "$candidate"
      return
    fi
  done
  return 1
}

[[ "$IMAGE" == *:* ]] || die "The backend image must include an explicit tag"
if [[ -z "$COMPOSE_FILE" ]]; then
  COMPOSE_FILE="$(find_compose_file || true)"
fi
[[ -n "$COMPOSE_FILE" && -f "$COMPOSE_FILE" ]] || die "Remnawave Compose file was not found; pass --compose-file"

resolved_compose="$(readlink -f "$COMPOSE_FILE")"
if [[ "$DRY_RUN" != "true" ]]; then
  [[ "$resolved_compose" == /opt/remnawave/* || "$resolved_compose" == /root/remnawave/* ]] || \
    die "Refusing to modify a Compose file outside /opt/remnawave or /root/remnawave"
fi

current_image="$(python3 - "$COMPOSE_FILE" <<'PY'
import re
import sys

lines = open(sys.argv[1], encoding="utf-8").read().splitlines()
in_service = False
service_indent = None
for line in lines:
    stripped = line.strip()
    indent = len(line) - len(line.lstrip())
    if re.match(r"^\s*remnawave:\s*$", line):
        in_service = True
        service_indent = indent
        continue
    if in_service and stripped and indent <= service_indent:
        break
    if in_service:
        match = re.match(r"^(\s*)image:\s*([^#\s]+)", line)
        if match:
            print(match.group(2))
            raise SystemExit(0)
raise SystemExit(1)
PY
)" || die "Could not find the remnawave backend image in $COMPOSE_FILE"

log "Compose: $COMPOSE_FILE"
log "Backend image: $current_image -> $IMAGE"
[[ "$DRY_RUN" != "true" ]] || exit 0
[[ "$EUID" -eq 0 ]] || die "Run as root"
command -v docker >/dev/null 2>&1 || die "Docker is required"
command -v python3 >/dev/null 2>&1 || die "Python 3 is required"

backup_dir="/var/backups/remnawave-alx/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup_dir"
cp -a "$COMPOSE_FILE" "$backup_dir/compose.yml"
compose_dir="$(dirname "$COMPOSE_FILE")"
[[ ! -f "$compose_dir/.env" ]] || cp -a "$compose_dir/.env" "$backup_dir/.env"
log "Backup saved to $backup_dir"

python3 - "$COMPOSE_FILE" "$IMAGE" <<'PY'
import os
import re
import sys
import tempfile

path, image = sys.argv[1:]
with open(path, encoding="utf-8") as source:
    lines = source.readlines()

in_service = False
service_indent = None
changed = False
for index, line in enumerate(lines):
    stripped = line.strip()
    indent = len(line) - len(line.lstrip())
    if re.match(r"^\s*remnawave:\s*$", line):
        in_service = True
        service_indent = indent
        continue
    if in_service and stripped and indent <= service_indent:
        break
    if in_service and re.match(r"^\s*image:\s*", line):
        prefix = re.match(r"^(\s*)", line).group(1)
        lines[index] = f"{prefix}image: {image}\n"
        changed = True
        break

if not changed:
    raise SystemExit("remnawave backend image entry was not found")

directory = os.path.dirname(path)
fd, temporary = tempfile.mkstemp(prefix=".compose-alx-", dir=directory, text=True)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as target:
        target.writelines(lines)
        target.flush()
        os.fsync(target.fileno())
    os.chmod(temporary, os.stat(path).st_mode)
    os.replace(temporary, path)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
PY

rollback() {
  log "New backend failed health checks; restoring $current_image"
  cp -a "$backup_dir/compose.yml" "$COMPOSE_FILE"
  docker compose -f "$COMPOSE_FILE" --project-directory "$compose_dir" up -d remnawave || true
}
trap rollback ERR

docker compose -f "$COMPOSE_FILE" --project-directory "$compose_dir" pull remnawave
docker compose -f "$COMPOSE_FILE" --project-directory "$compose_dir" up -d remnawave

for _ in $(seq 1 30); do
  state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' remnawave 2>/dev/null || true)"
  if [[ "$state" == "healthy" || "$state" == "running" ]]; then
    trap - ERR
    log "Native ALX panel backend is $state"
    log "Rollback backup: $backup_dir"
    exit 0
  fi
  [[ "$state" != "exited" && "$state" != "dead" ]] || break
  sleep 2
done

die "The native ALX backend did not become healthy"
