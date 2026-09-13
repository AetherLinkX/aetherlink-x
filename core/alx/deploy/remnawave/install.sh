#!/usr/bin/env bash
set -Eeuo pipefail

readonly INSTALLER_VERSION="2.0.0-native"
readonly DEFAULT_RELEASE_TAG="server-v0.2.2-alx-preview.8-rw.2"
readonly DEFAULT_REPOSITORY="AetherLinkX/aetherlink-x"

DOMAIN=""
EMAIL=""
PANEL_IP=""
NODE_PORT="2222"
ALX_PORT="8443"
ALX_PORT_EXPLICIT="false"
REMNAWAVE_IMAGE="auto"
COMPAT_MODE="auto"
RELEASE_TAG="$DEFAULT_RELEASE_TAG"
REPOSITORY="$DEFAULT_REPOSITORY"
REMNAWAVE_SECRET_KEY="${REMNAWAVE_SECRET_KEY:-}"
REMNAWAVE_PANEL_URL="${REMNAWAVE_PANEL_URL:-}"
REMNAWAVE_API_TOKEN="${REMNAWAVE_API_TOKEN:-}"
ALX_TOKEN="${ALX_TOKEN:-}"
ALX_RUNTIME_GID="${ALX_RUNTIME_CONFIG_GID:-}"
GH_TOKEN="${GH_TOKEN:-}"
EXISTING_CERT=""
EXISTING_KEY=""
DRY_RUN="false"
DRY_ROOT=""
SKIP_DNS_CHECK="false"
ROTATE_ALX_TOKEN="false"
ROTATE_REMNAWAVE_KEY="false"
EXISTING_ALX_PROFILE_FILE=""
PANEL_PROFILE="AetherLink X"
PANEL_PROFILE_EXPLICIT="false"
PANEL_NODE_NAME="AetherLink X"
PANEL_SQUAD_NAME="AetherLink X"
PANEL_COUNTRY_CODE="FI"

log() { printf '[AetherLink X] %s\n' "$*"; }
die() { printf '[AetherLink X] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
AetherLink X + Remnawave Node installer

Usage:
  sudo bash install.sh --domain alx.example.com --email admin@example.com \
    --panel-ip 198.51.100.10 [options]

The installer can obtain the Remnawave Node secret and register the native
ALX profile, host, node and Internal Squad automatically when REMNAWAVE_PANEL_URL and
REMNAWAVE_API_TOKEN are set. Otherwise REMNAWAVE_SECRET_KEY is requested.

Required:
  --domain NAME              DNS name whose A/AAAA record points to this VPS
  --email ADDRESS            Let's Encrypt account email
  --panel-ip ADDRESS         Panel IP allowed to reach the Node API

Options:
  --node-port PORT           Remnawave Node API port (default: 2222)
  --alx-port PORT            ALX TCP+UDP port (default: 8443)
  --remnawave-image IMAGE    Node image/tag (default: auto by panel major version)
  --compat MODE              auto, modern, or legacy (default: auto)
  --panel-profile VALUE      Native ALX profile UUID/name (default: AetherLink X)
  --panel-node-name NAME     Node name in Remnawave (default: AetherLink X)
  --panel-squad-name NAME    Internal Squad receiving ALX (default: AetherLink X)
  --panel-country CODE       Two-letter node country code (default: FI)
  --release-tag TAG          ALX server release tag
  --repository OWNER/REPO    GitHub repository used for the server binary
  --existing-cert FILE       Use an existing PEM full chain
  --existing-key FILE        Use its existing PEM private key
  --existing-alx-profile-file FILE
                             Attach an already working ALX profile without
                             replacing its binary, certificate or service
  --rotate-alx-token         Generate a new ALX token on an existing install
  --rotate-node-key          Replace the existing Remnawave Node key
  --skip-dns-check           Let certbot report DNS errors itself
  --dry-run DIR              Render files under DIR without changing the VPS
  --help                     Show this message

Compatibility modes:
  auto    Exports both modern NODE_PORT/SECRET_KEY and legacy APP_PORT/SSL_CERT.
  modern  Remnawave Node 2.2.2+ and 3.x.
  legacy  Older Node releases that use APP_PORT/SSL_CERT.
EOF
}

while (($#)); do
  case "$1" in
    --domain) DOMAIN="${2:-}"; shift 2 ;;
    --email) EMAIL="${2:-}"; shift 2 ;;
    --panel-ip) PANEL_IP="${2:-}"; shift 2 ;;
    --node-port) NODE_PORT="${2:-}"; shift 2 ;;
    --alx-port) ALX_PORT="${2:-}"; ALX_PORT_EXPLICIT="true"; shift 2 ;;
    --remnawave-image) REMNAWAVE_IMAGE="${2:-}"; shift 2 ;;
    --compat) COMPAT_MODE="${2:-}"; shift 2 ;;
    --panel-profile) PANEL_PROFILE="${2:-}"; PANEL_PROFILE_EXPLICIT="true"; shift 2 ;;
    --panel-node-name) PANEL_NODE_NAME="${2:-}"; shift 2 ;;
    --panel-squad-name) PANEL_SQUAD_NAME="${2:-}"; shift 2 ;;
    --panel-country) PANEL_COUNTRY_CODE="${2:-}"; shift 2 ;;
    --release-tag) RELEASE_TAG="${2:-}"; shift 2 ;;
    --repository) REPOSITORY="${2:-}"; shift 2 ;;
    --existing-cert) EXISTING_CERT="${2:-}"; shift 2 ;;
    --existing-key) EXISTING_KEY="${2:-}"; shift 2 ;;
    --existing-alx-profile-file) EXISTING_ALX_PROFILE_FILE="${2:-}"; shift 2 ;;
    --rotate-alx-token) ROTATE_ALX_TOKEN="true"; shift ;;
    --rotate-node-key) ROTATE_REMNAWAVE_KEY="true"; shift ;;
    --skip-dns-check) SKIP_DNS_CHECK="true"; shift ;;
    --dry-run) DRY_RUN="true"; DRY_ROOT="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

if [[ -n "$EXISTING_ALX_PROFILE_FILE" && "$ALX_PORT_EXPLICIT" != "true" ]]; then
  [[ -f "$EXISTING_ALX_PROFILE_FILE" ]] || die "Existing ALX profile file does not exist"
  profile_preview="$(grep -m1 '^aetherlink://' "$EXISTING_ALX_PROFILE_FILE" || true)"
  detected_alx_port="$(python3 - "$profile_preview" <<'PY'
import sys
from urllib.parse import urlsplit

try:
    print(urlsplit(sys.argv[1]).port or "")
except (TypeError, ValueError):
    print("")
PY
)"
  [[ "$detected_alx_port" =~ ^[0-9]+$ ]] || die "Could not detect the ALX port from the existing profile"
  ALX_PORT="$detected_alx_port"
  log "Detected existing ALX listener on port ${ALX_PORT}"
fi

[[ "$DOMAIN" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "Invalid --domain"
[[ "$EMAIL" == *@*.* ]] || die "Invalid --email"
[[ -n "$PANEL_IP" ]] || die "--panel-ip is required"
[[ "$NODE_PORT" =~ ^[0-9]+$ ]] && ((NODE_PORT >= 1 && NODE_PORT <= 65535)) || die "Invalid --node-port"
[[ "$ALX_PORT" =~ ^[0-9]+$ ]] && ((ALX_PORT >= 1 && ALX_PORT <= 65535)) || die "Invalid --alx-port"
[[ "$NODE_PORT" != "$ALX_PORT" ]] || die "Node API and ALX ports must differ"
[[ "$COMPAT_MODE" =~ ^(auto|modern|legacy)$ ]] || die "--compat must be auto, modern, or legacy"
[[ "$PANEL_COUNTRY_CODE" =~ ^[A-Za-z]{2}$ ]] || die "--panel-country must contain two letters"
PANEL_COUNTRY_CODE="${PANEL_COUNTRY_CODE^^}"
[[ "$REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "Invalid --repository"
if [[ -n "$EXISTING_CERT" || -n "$EXISTING_KEY" ]]; then
  [[ -n "$EXISTING_CERT" && -n "$EXISTING_KEY" ]] || die "Both --existing-cert and --existing-key are required"
fi
if [[ -n "$REMNAWAVE_PANEL_URL" || -n "$REMNAWAVE_API_TOKEN" ]]; then
  [[ -n "$REMNAWAVE_PANEL_URL" && -n "$REMNAWAVE_API_TOKEN" ]] || \
    die "Set both REMNAWAVE_PANEL_URL and REMNAWAVE_API_TOKEN"
fi

root_path() {
  if [[ "$DRY_RUN" == "true" ]]; then printf '%s%s' "$DRY_ROOT" "$1"; else printf '%s' "$1"; fi
}

readonly REMNA_DIR="$(root_path /opt/remnanode)"
readonly ALX_DIR="$(root_path /etc/aetherlink-x)"
readonly ALX_BIN="$(root_path /usr/local/bin/alx-server)"
readonly SERVICE_FILE="$(root_path /etc/systemd/system/aetherlink-native.service)"
readonly RENEW_HOOK="$(root_path /etc/letsencrypt/renewal-hooks/deploy/aetherlink-native)"
readonly SUMMARY_FILE="$(root_path /root/aetherlink-remnawave-summary.txt)"
readonly PANEL_RESULT_FILE="$(root_path /root/aetherlink-remnawave-panel.json)"
readonly LIVE_CERT_DIR="/etc/letsencrypt/live/$DOMAIN"
readonly INSTALLED_CERT="/etc/aetherlink-x/fullchain.pem"
readonly INSTALLED_KEY="/etc/aetherlink-x/privkey.pem"

dotenv_quote() {
  local value="$1"
  value=${value//\\/\\\\}
  value=${value//$'\r'/}
  value=${value//$'\n'/\\n}
  value=${value//\"/\\\"}
  printf '"%s"' "$value"
}

# Version-adaptive Remnawave REST helper. Secrets are accepted only through
# environment variables and are never placed in argv, logs, or result JSON.
panel_tool() {
  python3 - "$@" <<'PY'
import json
import os
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

base = os.environ.get("REMNAWAVE_PANEL_URL", "").rstrip("/")
token = os.environ.get("REMNAWAVE_API_TOKEN", "")
if not base or not token:
    raise SystemExit("REMNAWAVE_PANEL_URL and REMNAWAVE_API_TOKEN are required")
if not base.startswith(("https://", "http://")):
    raise SystemExit("REMNAWAVE_PANEL_URL must start with https:// or http://")

def request(method, path, payload=None):
    data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
    req = urllib.request.Request(
        base + "/api/" + path.lstrip("/"),
        data=data,
        method=method,
        headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=25, context=ssl.create_default_context()) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        raise SystemExit(f"Remnawave API {method} {path} failed with HTTP {exc.code}")
    except urllib.error.URLError as exc:
        raise SystemExit(f"Remnawave API is unreachable: {exc.reason}")

command = sys.argv[1]
if command == "keygen":
    print(request("GET", "keygen/")["response"]["pubKey"])
    raise SystemExit(0)
if command == "version":
    print(str(request("GET", "system/metadata").get("response", {}).get("version", "2.0.0")))
    raise SystemExit(0)
if command != "configure":
    raise SystemExit("unknown panel helper command")

domain, node_port, alx_port, profile_selector, profile_explicit, node_name, squad_name, country = sys.argv[2:10]
profile_link = os.environ.get("ALX_PROFILE", "")
if not profile_link.startswith("aetherlink://"):
    raise SystemExit("ALX_PROFILE is missing or invalid")

parsed_profile = urllib.parse.urlsplit(profile_link)
profile_query = urllib.parse.parse_qs(parsed_profile.query)
native_token = urllib.parse.unquote(parsed_profile.username or "")
native_pin = (profile_query.get("pin") or [""])[0]
native_sni = (profile_query.get("sni") or [domain])[0]
native_mode = (profile_query.get("mode") or ["turbo"])[0]
native_fallback = (profile_query.get("fallback") or ["auto"])[0]
if len(native_token) < 32 or len(native_pin) != 64 or native_mode != "turbo":
    raise SystemExit("ALX_PROFILE does not contain a valid native token, pin and Turbo mode")

metadata = request("GET", "system/metadata").get("response", {})
version = str(metadata.get("version", "2.0.0")).lstrip("v")
try:
    major = int(version.split(".", 1)[0])
except ValueError:
    major = 2

profiles = request("GET", "config-profiles/").get("response", {}).get("configProfiles", [])
selected = None
if profile_selector:
    selected = next((item for item in profiles if item.get("uuid") == profile_selector or item.get("name") == profile_selector), None)
elif len(profiles) == 1:
    selected = profiles[0]

native_config = {
        "log": {"loglevel": "warning"},
        "inbounds": [{
            "tag": "AETHERLINK_NATIVE",
            "port": int(alx_port),
            "protocol": "aetherlink",
            "settings": {
                "token": native_token,
                "previousTokens": [],
                "pin": native_pin,
                "sni": native_sni,
                "mode": "turbo",
                "fallback": native_fallback,
            },
        }],
        "outbounds": [
            {"tag": "DIRECT", "protocol": "freedom"},
            {"tag": "BLOCK", "protocol": "blackhole"},
        ],
}

if selected is None and profile_explicit != "true" and profile_selector == "AetherLink X":
    created_response = request("POST", "config-profiles/", {
        "name": "AetherLink X",
        "config": native_config,
    }).get("response", {})
    created_uuid = created_response.get("uuid")
    profiles = request("GET", "config-profiles/").get("response", {}).get("configProfiles", [])
    selected = next(
        (item for item in profiles if (created_uuid and item.get("uuid") == created_uuid) or item.get("name") == "AetherLink X"),
        None,
    )
if selected is None:
    names = ", ".join(str(item.get("name", "unnamed")) for item in profiles)
    raise SystemExit("Select a profile with --panel-profile. Available: " + (names or "none"))

if selected.get("name") == "AetherLink X":
    request("PATCH", "config-profiles/", {
        "uuid": selected["uuid"],
        "name": "AetherLink X",
        "config": native_config,
    })
    profiles = request("GET", "config-profiles/").get("response", {}).get("configProfiles", [])
    selected = next((item for item in profiles if item.get("uuid") == selected["uuid"]), None)

inbounds = selected.get("inbounds") or []
selected_inbound = next((item for item in inbounds if item.get("tag") == "AETHERLINK_NATIVE"), None)
if selected_inbound is None or selected_inbound.get("type") != "aetherlink":
    raise SystemExit("Selected profile does not contain the native AETHERLINK_NATIVE inbound")

desired_profile = {
    "activeConfigProfileUuid": selected["uuid"],
    "activeInbounds": [selected_inbound["uuid"]],
}

nodes_response = request("GET", "nodes/").get("response", [])
nodes = nodes_response if isinstance(nodes_response, list) else nodes_response.get("nodes", [])
node = next((item for item in nodes if item.get("name") == node_name), None)
if node is not None and (node.get("address") != domain or int(node.get("port") or 0) != int(node_port)):
    raise SystemExit(
        f'Remnawave node "{node_name}" already exists with another address or port; '
        "choose another --panel-node-name"
    )
if node is None:
    node = next(
        (item for item in nodes if item.get("address") == domain and int(item.get("port") or 0) == int(node_port)),
        None,
    )
if node is None:
    node_payload = {
        "name": node_name,
        "address": domain,
        "port": int(node_port),
        "countryCode": country,
        "isTrafficTrackingActive": True,
        "configProfile": desired_profile,
        "tags": ["AETHERLINK_X"],
    }
    node = request("POST", "nodes/", node_payload)["response"]
else:
    current_profile = node.get("configProfile") or {}
    current_inbounds = current_profile.get("activeInbounds") or []
    current_inbound_ids = [item.get("uuid") if isinstance(item, dict) else item for item in current_inbounds]
    if (
        current_profile.get("activeConfigProfileUuid") != selected["uuid"]
        or current_inbound_ids != desired_profile["activeInbounds"]
    ):
        update_payload = {
            "uuid": node["uuid"],
            "name": node.get("name", node_name),
            "address": domain,
            "port": int(node_port),
            "countryCode": node.get("countryCode") or country,
            "isTrafficTrackingActive": node.get("isTrafficTrackingActive", True),
            "configProfile": desired_profile,
            "tags": list(dict.fromkeys((node.get("tags") or []) + ["AETHERLINK_X"])),
        }
        node = request("PATCH", "nodes/", update_payload)["response"]

hosts_response = request("GET", "hosts/").get("response", [])
hosts = hosts_response if isinstance(hosts_response, list) else hosts_response.get("hosts", [])
host = next(
    (item for item in hosts if item.get("configProfileInboundUuid") == selected_inbound["uuid"]),
    None,
)
host_payload = {
    "inbound": {
        "configProfileUuid": selected["uuid"],
        "configProfileInboundUuid": selected_inbound["uuid"],
    },
    "remark": "AetherLink Native Turbo",
    "address": domain,
    "port": int(alx_port),
    "sni": native_sni,
    "isDisabled": False,
    "nodes": [node["uuid"]],
    "excludeFromSubscriptionTypes": ["XRAY_JSON"],
}
if host is None:
    host = request("POST", "hosts/", host_payload)["response"]
else:
    host_payload["uuid"] = host["uuid"]
    host = request("PATCH", "hosts/", host_payload)["response"]

squads_response = request("GET", "internal-squads/").get("response", {})
squads = squads_response.get("internalSquads", []) if isinstance(squads_response, dict) else squads_response
squad = next((item for item in squads if item.get("name") == squad_name), None)
if squad is None:
    squad = request("POST", "internal-squads/", {
        "name": squad_name,
        "inbounds": [selected_inbound["uuid"]],
    })["response"]
else:
    current_inbounds = squad.get("inbounds") or []
    inbound_ids = [item.get("uuid") if isinstance(item, dict) else item for item in current_inbounds]
    if selected_inbound["uuid"] not in inbound_ids:
        inbound_ids.append(selected_inbound["uuid"])
        squad = request("PATCH", "internal-squads/", {
            "uuid": squad["uuid"],
            "name": squad.get("name", squad_name),
            "inbounds": inbound_ids,
        })["response"]

print(json.dumps({
    "panelVersion": version,
    "nodeUuid": node.get("uuid"),
    "nodeName": node.get("name", node_name),
    "configProfileUuid": selected.get("uuid"),
    "configProfileName": selected.get("name"),
    "activeInboundUuid": selected_inbound.get("uuid"),
    "activeInboundTag": selected_inbound.get("tag"),
    "activeInboundPort": selected_inbound.get("port"),
    "hostUuid": host.get("uuid"),
    "hostRemark": host.get("remark", "AetherLink Native Turbo"),
    "internalSquadUuid": squad.get("uuid"),
    "internalSquadName": squad.get("name", squad_name),
}, separators=(",", ":")))
PY
}

resolve_remnawave_image() {
  [[ "$REMNAWAVE_IMAGE" == "auto" ]] || return
  if [[ -n "$REMNAWAVE_PANEL_URL" ]]; then
    local panel_version panel_major
    panel_version="$(panel_tool version)"
    panel_major="${panel_version#v}"
    panel_major="${panel_major%%.*}"
    REMNAWAVE_IMAGE="ghcr.io/aetherlinkx/remnawave-node-alx:2.8.0-native.1"
    log "Panel ${panel_version}: selected ${REMNAWAVE_IMAGE}"
  else
    REMNAWAVE_IMAGE="ghcr.io/aetherlinkx/remnawave-node-alx:2.8.0-native.1"
    log "Panel version is unavailable: selected conservative ${REMNAWAVE_IMAGE}"
  fi
}

read_existing_remnawave_key() {
  local env_file="$REMNA_DIR/.env"
  [[ -f "$env_file" ]] || return 1
  python3 - "$env_file" <<'PY'
import json
import sys

for raw in open(sys.argv[1], encoding="utf-8"):
    line = raw.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    if key not in {"SECRET_KEY", "SSL_CERT"} or not value:
        continue
    try:
        value = json.loads(value)
    except Exception:
        value = value.strip("'\"")
    if value:
        print(value)
        raise SystemExit(0)
raise SystemExit(1)
PY
}

write_remnawave_env() {
  local target="$REMNA_DIR/.env"
  local runtime_gid="${ALX_RUNTIME_GID:-991}"
  : >"$target"
  case "$COMPAT_MODE" in
    auto)
      printf 'NODE_PORT=%s\nAPP_PORT=%s\n' "$NODE_PORT" "$NODE_PORT" >>"$target"
      printf 'SECRET_KEY=%s\nSSL_CERT=%s\n' "$(dotenv_quote "$REMNAWAVE_SECRET_KEY")" "$(dotenv_quote "$REMNAWAVE_SECRET_KEY")" >>"$target"
      ;;
    modern)
      printf 'NODE_PORT=%s\nSECRET_KEY=%s\n' "$NODE_PORT" "$(dotenv_quote "$REMNAWAVE_SECRET_KEY")" >>"$target"
      ;;
    legacy)
      printf 'APP_PORT=%s\nSSL_CERT=%s\n' "$NODE_PORT" "$(dotenv_quote "$REMNAWAVE_SECRET_KEY")" >>"$target"
      ;;
  esac
  printf 'XTLS_API_PORT=61000\n' >>"$target"
  printf 'ALX_RUNTIME_CONFIG_GID=%s\n' "$runtime_gid" >>"$target"
  chmod 0600 "$target"
}

write_compose() {
  cat >"$REMNA_DIR/docker-compose.yml" <<EOF
services:
  remnanode:
    image: ${REMNAWAVE_IMAGE}
    container_name: remnanode
    hostname: remnanode
    network_mode: host
    restart: always
    cap_add:
      - NET_ADMIN
    ulimits:
      nofile:
        soft: 1048576
        hard: 1048576
    env_file:
      - .env
    volumes:
      - /var/log/remnanode:/var/log/remnanode
      - /etc/aetherlink-x:/etc/aetherlink-x
EOF
}

write_alx_service() {
  cat >"$ALX_DIR/alx.env" <<EOF
ALX_LISTEN=:${ALX_PORT}
ALX_TCP_LISTEN=:${ALX_PORT}
ALX_CERT_FILE=${INSTALLED_CERT}
ALX_KEY_FILE=${INSTALLED_KEY}
ALX_TURBO_SERVER_NAME=${DOMAIN}
ALX_TURBO_CERT_FILE=${INSTALLED_CERT}
ALX_TURBO_KEY_FILE=${INSTALLED_KEY}
ALX_TOKEN=${ALX_TOKEN}
ALX_RUNTIME_CONFIG_FILE=/etc/aetherlink-x/panel-runtime.json
EOF
  chmod 0640 "$ALX_DIR/alx.env"
  if [[ "$DRY_RUN" != "true" ]]; then
    chown root:aetherlink "$ALX_DIR/alx.env"
  fi

  cat >"$SERVICE_FILE" <<'EOF'
[Unit]
Description=AetherLink X Native Turbo Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=aetherlink
Group=aetherlink
EnvironmentFile=/etc/aetherlink-x/alx.env
ExecStart=/usr/local/bin/alx-server
Restart=on-failure
RestartSec=2s
LimitNOFILE=1048576
NoNewPrivileges=true
PrivateTmp=true
ProtectHome=true
ProtectSystem=strict
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
AmbientCapabilities=CAP_NET_BIND_SERVICE

[Install]
WantedBy=multi-user.target
EOF
}

write_renew_hook() {
  mkdir -p "$(dirname "$RENEW_HOOK")"
  cat >"$RENEW_HOOK" <<EOF
#!/bin/sh
set -eu
install -o root -g aetherlink -m 0640 "${LIVE_CERT_DIR}/fullchain.pem" "${INSTALLED_CERT}"
install -o root -g aetherlink -m 0640 "${LIVE_CERT_DIR}/privkey.pem" "${INSTALLED_KEY}"
systemctl try-restart aetherlink-native.service
EOF
  chmod 0755 "$RENEW_HOOK"
}

detect_arch() {
  case "$(uname -m)" in
    x86_64|amd64) printf 'amd64' ;;
    aarch64|arm64) printf 'arm64' ;;
    *) die "Unsupported CPU architecture: $(uname -m)" ;;
  esac
}

install_packages() {
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y ca-certificates curl openssl python3 certbot docker.io
  if ! docker compose version >/dev/null 2>&1; then
    if apt-cache show docker-compose-v2 >/dev/null 2>&1; then
      apt-get install -y docker-compose-v2
    elif apt-cache show docker-compose-plugin >/dev/null 2>&1; then
      apt-get install -y docker-compose-plugin
    else
      die "Docker Compose v2 package is unavailable on this OS"
    fi
  fi
  systemctl enable --now docker
}

ensure_alx_user() {
  if ! getent group aetherlink >/dev/null 2>&1; then
    groupadd --system aetherlink
  fi
  if ! id -u aetherlink >/dev/null 2>&1; then
    useradd --system --gid aetherlink --home-dir /nonexistent --shell /usr/sbin/nologin aetherlink
  fi
  ALX_RUNTIME_GID="$(id -g aetherlink)"
}

check_dns() {
  [[ "$SKIP_DNS_CHECK" == "true" ]] && return
  local public_ip resolved
  public_ip="$(curl -4fsS --max-time 10 https://api.ipify.org || true)"
  resolved="$(getent ahostsv4 "$DOMAIN" | awk 'NR==1 {print $1}')"
  [[ -n "$resolved" ]] || die "DNS for $DOMAIN does not resolve"
  if [[ -n "$public_ip" && "$resolved" != "$public_ip" ]]; then
    die "$DOMAIN resolves to $resolved, but this VPS public IPv4 is $public_ip"
  fi
}

issue_certificate() {
  mkdir -p "$ALX_DIR"
  if [[ -n "$EXISTING_CERT" ]]; then
    openssl x509 -in "$EXISTING_CERT" -noout -checkend 86400 >/dev/null || die "Existing certificate is invalid or expires within 24 hours"
    install -o root -g aetherlink -m 0640 "$EXISTING_CERT" "$ALX_DIR/fullchain.pem"
    install -o root -g aetherlink -m 0640 "$EXISTING_KEY" "$ALX_DIR/privkey.pem"
    return
  fi
  check_dns
  certbot certonly --standalone --non-interactive --agree-tos --keep-until-expiring \
    --email "$EMAIL" --preferred-challenges http -d "$DOMAIN"
  install -o root -g aetherlink -m 0640 "$LIVE_CERT_DIR/fullchain.pem" "$ALX_DIR/fullchain.pem"
  install -o root -g aetherlink -m 0640 "$LIVE_CERT_DIR/privkey.pem" "$ALX_DIR/privkey.pem"
  write_renew_hook
}

install_server_binary() {
  local arch asset base temp actual expected auth=()
  arch="$(detect_arch)"
  asset="aetherlink-native-server-linux-${arch}"
  base="https://github.com/${REPOSITORY}/releases/download/${RELEASE_TAG}"
  temp="$(mktemp -d)"
  [[ -n "$GH_TOKEN" ]] && auth=(-H "Authorization: Bearer $GH_TOKEN")
  curl -fL --retry 3 "${auth[@]}" "$base/$asset" -o "$temp/$asset"
  curl -fL --retry 3 "${auth[@]}" "$base/$asset.sha256" -o "$temp/$asset.sha256"
  expected="$(awk 'NR==1 {print $1}' "$temp/$asset.sha256")"
  actual="$(sha256sum "$temp/$asset" | awk '{print $1}')"
  [[ "$expected" =~ ^[0-9a-fA-F]{64}$ && "$actual" == "$expected" ]] || die "ALX server checksum mismatch"
  install -o root -g root -m 0755 "$temp/$asset" "$ALX_BIN"
  rm -rf -- "$temp"
}

configure_firewall() {
  command -v ufw >/dev/null 2>&1 || { log "UFW is not installed; firewall rules were not changed"; return; }
  ufw status | grep -q '^Status: active' || { log "UFW is inactive; firewall rules were not changed"; return; }
  ufw allow from "$PANEL_IP" to any port "$NODE_PORT" proto tcp comment 'Remnawave Node API'
  ufw allow "$ALX_PORT/tcp" comment 'AetherLink X Turbo TCP'
  ufw allow "$ALX_PORT/udp" comment 'AetherLink X Turbo QUIC'
  [[ -n "$EXISTING_CERT" ]] || ufw allow '80/tcp' comment 'ACME HTTP-01 renewal'
}

configure_node_firewall() {
  command -v ufw >/dev/null 2>&1 || { log "UFW is not installed; firewall rules were not changed"; return; }
  ufw status | grep -q '^Status: active' || { log "UFW is inactive; firewall rules were not changed"; return; }
  ufw allow from "$PANEL_IP" to any port "$NODE_PORT" proto tcp comment 'Remnawave Node API'
}

prepare_acme_firewall() {
  [[ -n "$EXISTING_CERT" ]] && return
  command -v ufw >/dev/null 2>&1 || return
  ufw status | grep -q '^Status: active' || return
  ufw allow '80/tcp' comment 'ACME HTTP-01 renewal'
}

calculate_pin() {
  openssl x509 -in "$ALX_DIR/fullchain.pem" -pubkey -noout \
    | openssl pkey -pubin -outform DER 2>/dev/null \
    | openssl dgst -sha256 -hex \
    | awk '{print $NF}'
}

make_profile() {
  local pin="$1"
  printf 'aetherlink://%s@%s:%s?pin=%s&sni=%s&fallback=auto&mode=turbo#AetherLink%%20X%%20Turbo' \
    "$ALX_TOKEN" "$DOMAIN" "$ALX_PORT" "$pin" "$DOMAIN"
}

write_summary() {
  local pin="$1" profile="$2"
  cat >"$SUMMARY_FILE" <<EOF
AetherLink X + Remnawave Node
Installer: ${INSTALLER_VERSION}

Remnawave node address: ${DOMAIN}:${NODE_PORT}
Remnawave image: ${REMNAWAVE_IMAGE}
Compatibility mode: ${COMPAT_MODE}

ALX address: ${DOMAIN}:${ALX_PORT} (TCP + UDP)
ALX certificate SPKI SHA-256: ${pin}
ALX profile:
${profile}
EOF
  chmod 0600 "$SUMMARY_FILE"
}

write_existing_summary() {
  local profile="$1"
  cat >"$SUMMARY_FILE" <<EOF
AetherLink X + Remnawave Node
Installer: ${INSTALLER_VERSION}
Mode: attach existing ALX

Remnawave node address: ${DOMAIN}:${NODE_PORT}
Remnawave image: ${REMNAWAVE_IMAGE}
Compatibility mode: ${COMPAT_MODE}

Existing ALX profile (service was not modified):
${profile}
EOF
  chmod 0600 "$SUMMARY_FILE"
}

append_panel_summary() {
  local panel_result="$1" node_name squad_name node_uuid squad_uuid profile_name host_uuid
  node_name="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("nodeName", ""))' <<<"$panel_result")"
  squad_name="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("internalSquadName", ""))' <<<"$panel_result")"
  node_uuid="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("nodeUuid", ""))' <<<"$panel_result")"
  squad_uuid="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("internalSquadUuid", ""))' <<<"$panel_result")"
  host_uuid="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("hostUuid", ""))' <<<"$panel_result")"
  profile_name="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("configProfileName", ""))' <<<"$panel_result")"
  cat >>"$SUMMARY_FILE" <<EOF

Automatic panel integration completed:
Node: ${node_name} (${node_uuid})
Native config profile: ${profile_name}
Native host: ${host_uuid}
Internal Squad: ${squad_name} (${squad_uuid})

Assign users who should receive ALX to the Internal Squad "${squad_name}".
Their existing squads, Xray profiles and hosts remain unchanged.
EOF
}

render_dry_run() {
  mkdir -p "$REMNA_DIR" "$ALX_DIR" "$(dirname "$SERVICE_FILE")" "$(dirname "$SUMMARY_FILE")"
  write_remnawave_env
  write_compose
  if [[ -n "$EXISTING_ALX_PROFILE_FILE" ]]; then
    [[ -f "$EXISTING_ALX_PROFILE_FILE" ]] || die "Existing ALX profile file does not exist"
    profile="$(grep -m1 '^aetherlink://' "$EXISTING_ALX_PROFILE_FILE" || true)"
    [[ "$profile" == aetherlink://* ]] || die "Existing ALX profile file does not contain an aetherlink:// profile"
    write_existing_summary "$profile"
  else
    [[ -n "$ALX_TOKEN" ]] || ALX_TOKEN="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    write_alx_service
    write_summary "$(printf '0%.0s' {1..64})" "$(make_profile "$(printf '0%.0s' {1..64})")"
  fi
  log "Dry-run files rendered under $DRY_ROOT"
}

if [[ "$DRY_RUN" == "true" ]]; then
  [[ -n "$DRY_ROOT" && "$DRY_ROOT" != "/" ]] || die "--dry-run requires a safe output directory"
  [[ "$REMNAWAVE_IMAGE" != "auto" ]] || REMNAWAVE_IMAGE="ghcr.io/aetherlinkx/remnawave-node-alx:2.8.0-native.1"
  render_dry_run
  exit 0
fi

[[ "$EUID" -eq 0 ]] || die "Run as root"
grep -qiE '^(ID|ID_LIKE)=.*(debian|ubuntu)' /etc/os-release || die "Supported operating systems: Debian and Ubuntu"

log "Installing required packages"
install_packages
ensure_alx_user
resolve_remnawave_image

if [[ -z "$REMNAWAVE_SECRET_KEY" && "$ROTATE_REMNAWAVE_KEY" != "true" ]]; then
  if REMNAWAVE_SECRET_KEY="$(read_existing_remnawave_key)"; then
    log "Reusing the existing Remnawave Node key"
  else
    REMNAWAVE_SECRET_KEY=""
  fi
fi
if [[ -z "$REMNAWAVE_SECRET_KEY" && -n "$REMNAWAVE_PANEL_URL" ]]; then
  log "Obtaining Remnawave Node key from the panel"
  REMNAWAVE_SECRET_KEY="$(panel_tool keygen)"
fi
if [[ -z "$REMNAWAVE_SECRET_KEY" ]]; then
  if [[ -t 0 ]]; then
    read -r -s -p 'Paste the SECRET_KEY/SSL_CERT copied from Remnawave Panel: ' REMNAWAVE_SECRET_KEY
    printf '\n'
  else
    die "Set REMNAWAVE_SECRET_KEY, or set REMNAWAVE_PANEL_URL and REMNAWAVE_API_TOKEN"
  fi
fi
[[ -n "$REMNAWAVE_SECRET_KEY" ]] || die "Remnawave secret cannot be empty"

mkdir -p "$REMNA_DIR" "$ALX_DIR" /var/log/remnanode /var/backups/aetherlink-remnawave
chown root:aetherlink "$ALX_DIR"
chmod 0750 "$ALX_DIR"
chown root:aetherlink "$ALX_DIR"
chmod 0750 "$ALX_DIR"
if [[ -d /opt/remnanode || -d /etc/aetherlink-x || -f /etc/systemd/system/aetherlink-native.service ]]; then
  backup="/var/backups/aetherlink-remnawave/$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
  existing=()
  [[ -d /opt/remnanode ]] && existing+=(/opt/remnanode)
  [[ -d /etc/aetherlink-x ]] && existing+=(/etc/aetherlink-x)
  [[ -f /etc/systemd/system/aetherlink-native.service ]] && existing+=(/etc/systemd/system/aetherlink-native.service)
  ((${#existing[@]} == 0)) || tar -czf "$backup" "${existing[@]}"
  log "Backup saved to $backup"
fi

if [[ -n "$EXISTING_ALX_PROFILE_FILE" ]]; then
  [[ -f "$EXISTING_ALX_PROFILE_FILE" ]] || die "Existing ALX profile file does not exist"
  profile="$(grep -m1 '^aetherlink://' "$EXISTING_ALX_PROFILE_FILE" || true)"
  [[ "$profile" == aetherlink://* ]] || die "Existing ALX profile file does not contain an aetherlink:// profile"
elif [[ "$ROTATE_ALX_TOKEN" == "false" && -f "$ALX_DIR/alx.env" ]]; then
  existing_token="$(sed -n 's/^ALX_TOKEN=//p' "$ALX_DIR/alx.env" | head -n1)"
  [[ -z "$existing_token" ]] || ALX_TOKEN="$existing_token"
fi
if [[ -z "$EXISTING_ALX_PROFILE_FILE" ]]; then
  [[ -n "$ALX_TOKEN" ]] || ALX_TOKEN="$(openssl rand -base64 48 | tr -d '\n=' | tr '+/' '-_' | cut -c1-64)"
fi

log "Writing Remnawave Node configuration ($COMPAT_MODE mode)"
write_remnawave_env
write_compose

if [[ -z "$EXISTING_ALX_PROFILE_FILE" ]]; then
  log "Issuing or installing TLS certificate"
  prepare_acme_firewall
  issue_certificate

  log "Downloading and verifying ALX server"
  install_server_binary
  write_alx_service
else
  log "Attach mode: preserving the existing ALX binary, service, certificate and token"
fi

log "Starting Remnawave Node"
docker compose -f "$REMNA_DIR/docker-compose.yml" --env-file "$REMNA_DIR/.env" pull
docker compose -f "$REMNA_DIR/docker-compose.yml" --env-file "$REMNA_DIR/.env" up -d
if [[ -z "$EXISTING_ALX_PROFILE_FILE" ]]; then
  log "Starting ALX"
  systemctl daemon-reload
  systemctl enable --now aetherlink-native.service
  configure_firewall
else
  configure_node_firewall
fi

sleep 2
if [[ -z "$EXISTING_ALX_PROFILE_FILE" ]]; then
  systemctl is-active --quiet aetherlink-native.service || die "ALX service failed; run: journalctl -u aetherlink-native -n 100"
fi
docker inspect -f '{{.State.Running}}' remnanode 2>/dev/null | grep -qx true || die "Remnawave Node container is not running"

if [[ -n "$EXISTING_ALX_PROFILE_FILE" ]]; then
  write_existing_summary "$profile"
else
  pin="$(calculate_pin)"
  [[ "$pin" =~ ^[0-9a-f]{64}$ ]] || die "Could not calculate certificate pin"
  profile="$(make_profile "$pin")"
  write_summary "$pin" "$profile"
fi

if [[ -n "$REMNAWAVE_PANEL_URL" ]]; then
  log "Registering the native ALX profile, host, node and Internal Squad in Remnawave"
  panel_result="$(ALX_PROFILE="$profile" panel_tool configure \
    "$DOMAIN" "$NODE_PORT" "$ALX_PORT" "$PANEL_PROFILE" "$PANEL_PROFILE_EXPLICIT" "$PANEL_NODE_NAME" \
    "$PANEL_SQUAD_NAME" "$PANEL_COUNTRY_CODE")"
  printf '%s\n' "$panel_result" >"$PANEL_RESULT_FILE"
  chmod 0600 "$PANEL_RESULT_FILE"
  append_panel_summary "$panel_result"
fi

log "Installation completed"
log "Private setup details: $SUMMARY_FILE"
if [[ -n "$REMNAWAVE_PANEL_URL" ]]; then
  printf '\nThe native ALX profile, host and node are configured automatically.\nAssign intended users to Internal Squad "%s".\nDetails: %s\n' \
    "$PANEL_SQUAD_NAME" "$SUMMARY_FILE"
else
  printf '\nAdd the node in the patched Remnawave panel as %s:%s. Native profile details:\n  %s\n' \
    "$DOMAIN" "$NODE_PORT" "$SUMMARY_FILE"
fi
