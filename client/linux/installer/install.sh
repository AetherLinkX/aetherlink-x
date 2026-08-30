#!/bin/bash
# palazikVPN Linux installer — packaged into a self-extracting .run by CI (makeself).
# Installs to /opt/palazikVPN, adds the `palazikvpn` command, a desktop entry with
# proxy-scheme deep links (like Android), and an uninstaller.
set -e

if [ "$(id -u)" -ne 0 ]; then
  echo "→ Root is required to install into /opt — re-running with sudo…"
  exec sudo bash "$0" "$@"
fi

APP_DIR=/opt/palazikVPN
BIN_LINK=/usr/local/bin/palazikvpn
DESKTOP=/usr/share/applications/palazikvpn.desktop

# Root's umask can be 077 — everything we install must stay world-readable,
# otherwise app launchers get "Permission denied" on the .desktop file.
umask 022

echo "→ Installing palazikVPN to $APP_DIR"
rm -rf "$APP_DIR"
mkdir -p /opt
cp -r ./palazikVPN "$APP_DIR"
# The CI staging dir is created by mktemp (mode 700) — without this, /opt/palazikVPN
# ends up unreadable for normal users and the launcher "is not an executable file".
chmod -R a+rX "$APP_DIR"
chmod +x "$APP_DIR/bin/palazikVPN"
# jpackage strips exec bits from bundled app resources — restore them for the cores.
find "$APP_DIR" -type f \( -name xray -o -name tun2socks \) -exec chmod 755 {} +

echo "→ Creating command: palazikvpn"
rm -f "$BIN_LINK"
cat > "$BIN_LINK" <<EOF
#!/bin/bash
# GUI apps can't run as root (the X/Wayland server refuses the connection).
# TUN mode requests root by itself via pkexec — no sudo needed.
if [ "\$(id -u)" -eq 0 ]; then
  echo "Do not run palazikvpn with sudo — start it as your normal user."
  echo "TUN mode will ask for authorization on its own (pkexec)."
  exit 1
fi
# NVIDIA's GLX usually cannot create a context under XWayland (hybrid laptops),
# which drops the app to slow software rendering. Prefer Mesa there — override
# by exporting __GLX_VENDOR_LIBRARY_NAME yourself if needed.
if [ -n "\$WAYLAND_DISPLAY" ] && [ -z "\${__GLX_VENDOR_LIBRARY_NAME:-}" ]; then
  export __GLX_VENDOR_LIBRARY_NAME=mesa
fi
exec "$APP_DIR/bin/palazikVPN" "\$@"
EOF
chmod 755 "$BIN_LINK"

ICON="$APP_DIR/lib/palazikVPN.png"
echo "→ Creating desktop entry"
cat > "$DESKTOP" <<EOF
[Desktop Entry]
Type=Application
Name=palazikVPN
Comment=Open-source Xray proxy client
Exec=$BIN_LINK %u
Icon=$ICON
Terminal=false
Categories=Network;
MimeType=x-scheme-handler/palazikvpn;x-scheme-handler/vmess;x-scheme-handler/vless;x-scheme-handler/ss;x-scheme-handler/trojan;x-scheme-handler/hysteria2;x-scheme-handler/wireguard;x-scheme-handler/socks5;x-scheme-handler/tuic;x-scheme-handler/anytls;x-scheme-handler/xhttp;x-scheme-handler/httpproxy;
EOF
chmod 644 "$DESKTOP"
update-desktop-database /usr/share/applications 2>/dev/null || true

echo "→ Writing uninstaller: $APP_DIR/uninstall.sh"
cat > "$APP_DIR/uninstall.sh" <<EOF
#!/bin/bash
set -e
if [ "\$(id -u)" -ne 0 ]; then exec sudo bash "\$0" "\$@"; fi
rm -f "$BIN_LINK" "$DESKTOP"
rm -f "\$HOME/.config/autostart/palazikvpn.desktop" 2>/dev/null || true
rm -rf "$APP_DIR"
update-desktop-database /usr/share/applications 2>/dev/null || true
echo "palazikVPN uninstalled. Your profiles/settings in ~/.config/palazikVPN were kept."
EOF
chmod +x "$APP_DIR/uninstall.sh"

echo ""
echo "✓ palazikVPN installed."
echo "  Run it with:    palazikvpn   (or from your app launcher)"
echo "  Uninstall with: sudo $APP_DIR/uninstall.sh"
