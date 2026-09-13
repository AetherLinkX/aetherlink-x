#!/usr/bin/env bash
set -Eeuo pipefail

readonly INSTALLER_VERSION="1.0.0"
readonly DEFAULT_RELEASE_TAG="server-v0.2.1-alx-preview.8-rw.1"
readonly DEFAULT_REPOSITORY="AetherLinkX/aetherlink-x"

DOMAIN=""
EMAIL=""
PANEL_IP=""
NODE_PORT="2222"
ALX_PORT="8443"
REMNAWAVE_IMAGE="remnawave/node:latest"
COMPAT_MODE="auto"
RELEASE_TAG="$DEFAULT_RELEASE_TAG"
REPOSITORY="$DEFAULT_REPOSITORY"
REMNAWAVE_SECRET_KEY="${REMNAWAVE_SECRET_KEY:-}"
ALX_TOKEN="${ALX_TOKEN:-}"
GH_TOKEN="${GH_TOKEN:-}"
EXISTING_CERT=""
EXISTING_KEY=""
DRY_RUN="false"
DRY_ROOT=""
SKIP_DNS_CHECK="false"
ROTATE_ALX_TOKEN="false"

log() { printf '[AetherLink X] %s\n' "$*"; }
die() { printf '[AetherLink X] ERROR: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
AetherLink X + Remnawave Node installer

Usage:
  sudo bash install.sh --domain alx.example.com --email admin@example.com \
    --panel-ip 198.51.100.10 [options]

The Remnawave secret is read from REMNAWAVE_SECRET_KEY or requested securely.

Required:
  --domain NAME              DNS name whose A/AAAA record points to this VPS
  --email ADDRESS            Let's Encrypt account email
  --panel-ip ADDRESS         Panel IP allowed to reach the Node API

Options:
  --node-port PORT           Remnawave Node API port (default: 2222)
  --alx-port PORT            ALX TCP+UDP port (default: 8443)
  --remnawave-image IMAGE    Node image/tag (default: remnawave/node:latest)
  --compat MODE              auto, modern, or legacy (default: auto)
  --release-tag TAG          ALX server release tag
  --repository OWNER/REPO    GitHub repository used for the server binary
  --existing-cert FILE       Use an existing PEM full chain
  --existing-key FILE        Use its existing PEM private key
  --rotate-alx-token         Generate a new ALX token on an existing install
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
    --alx-port) ALX_PORT="${2:-}"; shift 2 ;;
    --remnawave-image) REMNAWAVE_IMAGE="${2:-}"; shift 2 ;;
    --compat) COMPAT_MODE="${2:-}"; shift 2 ;;
    --release-tag) RELEASE_TAG="${2:-}"; shift 2 ;;
    --repository) REPOSITORY="${2:-}"; shift 2 ;;
    --existing-cert) EXISTING_CERT="${2:-}"; shift 2 ;;
    --existing-key) EXISTING_KEY="${2:-}"; shift 2 ;;
    --rotate-alx-token) ROTATE_ALX_TOKEN="true"; shift ;;
    --skip-dns-check) SKIP_DNS_CHECK="true"; shift ;;
    --dry-run) DRY_RUN="true"; DRY_ROOT="${2:-}"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown argument: $1" ;;
  esac
done

[[ "$DOMAIN" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] || die "Invalid --domain"
[[ "$EMAIL" == *@*.* ]] || die "Invalid --email"
[[ -n "$PANEL_IP" ]] || die "--panel-ip is required"
[[ "$NODE_PORT" =~ ^[0-9]+$ ]] && ((NODE_PORT >= 1 && NODE_PORT <= 65535)) || die "Invalid --node-port"
[[ "$ALX_PORT" =~ ^[0-9]+$ ]] && ((ALX_PORT >= 1 && ALX_PORT <= 65535)) || die "Invalid --alx-port"
[[ "$NODE_PORT" != "$ALX_PORT" ]] || die "Node API and ALX ports must differ"
[[ "$COMPAT_MODE" =~ ^(auto|modern|legacy)$ ]] || die "--compat must be auto, modern, or legacy"
[[ "$REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "Invalid --repository"
if [[ -n "$EXISTING_CERT" || -n "$EXISTING_KEY" ]]; then
  [[ -n "$EXISTING_CERT" && -n "$EXISTING_KEY" ]] || die "Both --existing-cert and --existing-key are required"
fi

if [[ -z "$REMNAWAVE_SECRET_KEY" ]]; then
  if [[ -t 0 && "$DRY_RUN" == "false" ]]; then
    read -r -s -p 'Paste the SECRET_KEY/SSL_CERT copied from Remnawave Panel: ' REMNAWAVE_SECRET_KEY
    printf '\n'
  else
    die "Set REMNAWAVE_SECRET_KEY before running the installer"
  fi
fi
[[ -n "$REMNAWAVE_SECRET_KEY" ]] || die "Remnawave secret cannot be empty"

root_path() {
  if [[ "$DRY_RUN" == "true" ]]; then printf '%s%s' "$DRY_ROOT" "$1"; else printf '%s' "$1"; fi
}

readonly REMNA_DIR="$(root_path /opt/remnanode)"
readonly ALX_DIR="$(root_path /etc/aetherlink-x)"
readonly ALX_BIN="$(root_path /usr/local/bin/alx-server)"
readonly SERVICE_FILE="$(root_path /etc/systemd/system/aetherlink-native.service)"
readonly RENEW_HOOK="$(root_path /etc/letsencrypt/renewal-hooks/deploy/aetherlink-native)"
readonly SUMMARY_FILE="$(root_path /root/aetherlink-remnawave-summary.txt)"
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

write_remnawave_env() {
  local target="$REMNA_DIR/.env"
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
EOF
  chmod 0600 "$ALX_DIR/alx.env"

  cat >"$SERVICE_FILE" <<'EOF'
[Unit]
Description=AetherLink X Native Turbo Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
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
install -o root -g root -m 0644 "${LIVE_CERT_DIR}/fullchain.pem" "${INSTALLED_CERT}"
install -o root -g root -m 0600 "${LIVE_CERT_DIR}/privkey.pem" "${INSTALLED_KEY}"
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
    install -o root -g root -m 0644 "$EXISTING_CERT" "$ALX_DIR/fullchain.pem"
    install -o root -g root -m 0600 "$EXISTING_KEY" "$ALX_DIR/privkey.pem"
    return
  fi
  check_dns
  certbot certonly --standalone --non-interactive --agree-tos --keep-until-expiring \
    --email "$EMAIL" --preferred-challenges http -d "$DOMAIN"
  install -o root -g root -m 0644 "$LIVE_CERT_DIR/fullchain.pem" "$ALX_DIR/fullchain.pem"
  install -o root -g root -m 0600 "$LIVE_CERT_DIR/privkey.pem" "$ALX_DIR/privkey.pem"
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
  printf 'aetherlink://%s@%s:%s?pin=%s&sni=%s&fallback=%s&mode=turbo#AetherLink%%20X%%20Turbo' \
    "$ALX_TOKEN" "$DOMAIN" "$ALX_PORT" "$pin" "$DOMAIN" "$ALX_PORT"
}

write_summary() {
  local pin="$1" profile="$2" encoded
  encoded="$(printf '%s' "$profile" | openssl base64 -A)"
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

Remnawave subscription response header:
Name: X-AetherLink-Profile
Raw value: ${profile}
Base64 value: base64:${encoded}

Panel v2: put the header in Subscription Settings or External Squad responseHeaders.
Panel v3: put the header in responseHeadersAdd. The emitted HTTP header is identical.
EOF
  chmod 0600 "$SUMMARY_FILE"
}

render_dry_run() {
  mkdir -p "$REMNA_DIR" "$ALX_DIR" "$(dirname "$SERVICE_FILE")" "$(dirname "$SUMMARY_FILE")"
  [[ -n "$ALX_TOKEN" ]] || ALX_TOKEN="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
  write_remnawave_env
  write_compose
  write_alx_service
  write_summary "$(printf '0%.0s' {1..64})" "$(make_profile "$(printf '0%.0s' {1..64})")"
  log "Dry-run files rendered under $DRY_ROOT"
}

if [[ "$DRY_RUN" == "true" ]]; then
  [[ -n "$DRY_ROOT" && "$DRY_ROOT" != "/" ]] || die "--dry-run requires a safe output directory"
  render_dry_run
  exit 0
fi

[[ "$EUID" -eq 0 ]] || die "Run as root"
grep -qiE '^(ID|ID_LIKE)=.*(debian|ubuntu)' /etc/os-release || die "Supported operating systems: Debian and Ubuntu"

log "Installing required packages"
install_packages

mkdir -p "$REMNA_DIR" "$ALX_DIR" /var/log/remnanode /var/backups/aetherlink-remnawave
if [[ -d /opt/remnanode || -d /etc/aetherlink-x || -f /etc/systemd/system/aetherlink-native.service ]]; then
  backup="/var/backups/aetherlink-remnawave/$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
  existing=()
  [[ -d /opt/remnanode ]] && existing+=(/opt/remnanode)
  [[ -d /etc/aetherlink-x ]] && existing+=(/etc/aetherlink-x)
  [[ -f /etc/systemd/system/aetherlink-native.service ]] && existing+=(/etc/systemd/system/aetherlink-native.service)
  ((${#existing[@]} == 0)) || tar -czf "$backup" "${existing[@]}"
  log "Backup saved to $backup"
fi

if [[ "$ROTATE_ALX_TOKEN" == "false" && -f "$ALX_DIR/alx.env" ]]; then
  existing_token="$(sed -n 's/^ALX_TOKEN=//p' "$ALX_DIR/alx.env" | head -n1)"
  [[ -z "$existing_token" ]] || ALX_TOKEN="$existing_token"
fi
[[ -n "$ALX_TOKEN" ]] || ALX_TOKEN="$(openssl rand -base64 48 | tr -d '\n=' | tr '+/' '-_' | cut -c1-64)"

log "Writing Remnawave Node configuration ($COMPAT_MODE mode)"
write_remnawave_env
write_compose

log "Issuing or installing TLS certificate"
prepare_acme_firewall
issue_certificate

log "Downloading and verifying ALX server"
install_server_binary
write_alx_service

log "Starting Remnawave Node and ALX"
docker compose -f "$REMNA_DIR/docker-compose.yml" --env-file "$REMNA_DIR/.env" pull
docker compose -f "$REMNA_DIR/docker-compose.yml" --env-file "$REMNA_DIR/.env" up -d
systemctl daemon-reload
systemctl enable --now aetherlink-native.service

configure_firewall

sleep 2
systemctl is-active --quiet aetherlink-native.service || die "ALX service failed; run: journalctl -u aetherlink-native -n 100"
docker inspect -f '{{.State.Running}}' remnanode 2>/dev/null | grep -qx true || die "Remnawave Node container is not running"

pin="$(calculate_pin)"
[[ "$pin" =~ ^[0-9a-f]{64}$ ]] || die "Could not calculate certificate pin"
profile="$(make_profile "$pin")"
write_summary "$pin" "$profile"

log "Installation completed"
log "Private setup details: $SUMMARY_FILE"
printf '\nAdd the node in Remnawave as %s:%s, then configure X-AetherLink-Profile from:\n  %s\n' \
  "$DOMAIN" "$NODE_PORT" "$SUMMARY_FILE"
