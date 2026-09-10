#!/bin/sh
set -eu

SOURCE=/etc/letsencrypt/live/turbo.sinfor.fun
TARGET=/etc/aetherlink-x

install -o root -g aetherlink -m 0644 "$SOURCE/fullchain.pem" "$TARGET/turbo-fullchain.pem"
install -o root -g aetherlink -m 0640 "$SOURCE/privkey.pem" "$TARGET/turbo-privkey.pem"

if systemctl is-active --quiet aetherlink-native.service; then
    systemctl restart aetherlink-native.service
fi
