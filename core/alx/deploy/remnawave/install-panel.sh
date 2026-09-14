#!/usr/bin/env bash
set -Eeuo pipefail

readonly DEFAULT_IMAGE="ghcr.io/aetherlinkx/remnawave-backend-alx:2.7.4-native.2"

IMAGE="${REMNAWAVE_ALX_BACKEND_IMAGE:-$DEFAULT_IMAGE}"
declare -a COMPOSE_FILES=()
DRY_RUN="false"

log() { printf '[AetherLink X Panel] %s\n' "$*"; }
die() { printf '[AetherLink X Panel] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Install the version-pinned native AetherLink extension into Remnawave Panel.

Usage:
  sudo bash install-panel.sh [--compose-file FILE ...] [--image IMAGE] [--dry-run]

The script changes only the effective backend image in the existing Compose
project. Repeat --compose-file when the installation uses override files. When
no file is supplied, the active Compose file list is read from the running
Remnawave container. A timestamped backup is restored automatically if the new
backend does not become healthy.
EOF
}

while (($#)); do
  case "$1" in
    --compose-file) COMPOSE_FILES+=("${2:-}"); shift 2 ;;
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

discover_active_compose_files() {
  command -v docker >/dev/null 2>&1 || return 1
  local config_files candidate
  local -a discovered=()
  config_files="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project.config_files" }}' remnawave 2>/dev/null || true)"
  [[ -n "$config_files" && "$config_files" != '<no value>' ]] || return 1
  IFS=',' read -ra discovered <<<"$config_files"
  for candidate in "${discovered[@]}"; do
    candidate="${candidate# }"
    candidate="${candidate% }"
    [[ -f "$candidate" ]] || die "Active Compose file no longer exists: $candidate"
    COMPOSE_FILES+=("$candidate")
  done
  ((${#COMPOSE_FILES[@]} > 0))
}

compose_has_backend_image() {
  python3 - "$1" <<'PY'
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
    if in_service and re.match(r"^\s*image:\s*[^#\s]+", line):
        raise SystemExit(0)
raise SystemExit(1)
PY
}

[[ "$IMAGE" == *:* ]] || die "The backend image must include an explicit tag"
if ((${#COMPOSE_FILES[@]} == 0)); then
  discover_active_compose_files || true
fi
if ((${#COMPOSE_FILES[@]} == 0)); then
  fallback_compose="$(find_compose_file || true)"
  [[ -z "$fallback_compose" ]] || COMPOSE_FILES+=("$fallback_compose")
fi
((${#COMPOSE_FILES[@]} > 0)) || die "Remnawave Compose file was not found; pass --compose-file"

declare -a RESOLVED_COMPOSE_FILES=()
declare -a COMPOSE_ARGS=()
for compose_file in "${COMPOSE_FILES[@]}"; do
  [[ -n "$compose_file" && -f "$compose_file" ]] || die "Compose file does not exist: $compose_file"
  resolved_compose="$(readlink -f "$compose_file")"
  if [[ "$DRY_RUN" != "true" ]]; then
    [[ "$resolved_compose" == /opt/remnawave/* || "$resolved_compose" == /root/remnawave/* ]] || \
      die "Refusing to modify a Compose file outside /opt/remnawave or /root/remnawave"
  fi
  RESOLVED_COMPOSE_FILES+=("$resolved_compose")
  COMPOSE_ARGS+=(-f "$resolved_compose")
done
COMPOSE_FILES=("${RESOLVED_COMPOSE_FILES[@]}")

TARGET_COMPOSE_FILE=""
for ((index=${#COMPOSE_FILES[@]} - 1; index >= 0; index--)); do
  if compose_has_backend_image "${COMPOSE_FILES[$index]}"; then
    TARGET_COMPOSE_FILE="${COMPOSE_FILES[$index]}"
    break
  fi
done
[[ -n "$TARGET_COMPOSE_FILE" ]] || die "Could not find the remnawave backend image in the Compose project"

current_image="$(python3 - "$TARGET_COMPOSE_FILE" <<'PY'
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
)" || die "Could not find the remnawave backend image in $TARGET_COMPOSE_FILE"

log "Compose files: ${COMPOSE_FILES[*]}"
log "Image override: $TARGET_COMPOSE_FILE"
log "Backend image: $current_image -> $IMAGE"
[[ "$DRY_RUN" != "true" ]] || exit 0
[[ "$EUID" -eq 0 ]] || die "Run as root"
command -v docker >/dev/null 2>&1 || die "Docker is required"
command -v python3 >/dev/null 2>&1 || die "Python 3 is required"

backup_dir="/var/backups/remnawave-alx/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$backup_dir"
for index in "${!COMPOSE_FILES[@]}"; do
  cp -a "${COMPOSE_FILES[$index]}" "$backup_dir/compose-$index.yml"
done
printf '%s\n' "${COMPOSE_FILES[@]}" >"$backup_dir/compose-files.txt"
compose_dir="$(dirname "${COMPOSE_FILES[0]}")"
[[ ! -f "$compose_dir/.env" ]] || cp -a "$compose_dir/.env" "$backup_dir/.env"
log "Backup saved to $backup_dir"

python3 - "$TARGET_COMPOSE_FILE" "$IMAGE" <<'PY'
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
  for index in "${!COMPOSE_FILES[@]}"; do
    cp -a "$backup_dir/compose-$index.yml" "${COMPOSE_FILES[$index]}"
  done
  docker compose "${COMPOSE_ARGS[@]}" --project-directory "$compose_dir" up -d --pull never remnawave || true
}
trap rollback ERR

docker compose "${COMPOSE_ARGS[@]}" --project-directory "$compose_dir" config --quiet
docker compose "${COMPOSE_ARGS[@]}" --project-directory "$compose_dir" pull remnawave
docker compose "${COMPOSE_ARGS[@]}" --project-directory "$compose_dir" up -d remnawave

container_id="$(docker compose "${COMPOSE_ARGS[@]}" --project-directory "$compose_dir" ps -q remnawave)"
[[ -n "$container_id" ]] || die "The remnawave container was not created"

for _ in $(seq 1 30); do
  state="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id" 2>/dev/null || true)"
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
