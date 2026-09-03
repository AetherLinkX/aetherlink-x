#!/usr/bin/env bash
set -euo pipefail

# Build the Android TUN -> SOCKS bridge from a reviewed, pinned upstream commit.
# Output layout is consumed directly by app/srcSets.main.jniLibs.
HEV_REPOSITORY="${HEV_REPOSITORY:-https://github.com/heiher/hev-socks5-tunnel.git}"
HEV_COMMIT="${HEV_COMMIT:-64cc609}"
NDK_ROOT="${ANDROID_NDK_HOME:-${NDK_HOME:-}}"

if [[ -z "$NDK_ROOT" || ! -x "$NDK_ROOT/ndk-build" ]]; then
  echo "ANDROID_NDK_HOME (or NDK_HOME) must point to an Android NDK" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="$ANDROID_DIR/app/libs"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

git clone --recursive "$HEV_REPOSITORY" "$BUILD_DIR/jni"
git -C "$BUILD_DIR/jni" checkout --detach "$HEV_COMMIT"
git -C "$BUILD_DIR/jni" submodule update --init --recursive

cat > "$BUILD_DIR/Android.mk" <<'EOF'
include $(call all-subdir-makefiles)
EOF

"$NDK_ROOT/ndk-build" \
  NDK_PROJECT_PATH="$BUILD_DIR" \
  APP_BUILD_SCRIPT="$BUILD_DIR/Android.mk" \
  "APP_ABI=arm64-v8a armeabi-v7a" \
  APP_PLATFORM=android-26 \
  NDK_LIBS_OUT="$BUILD_DIR/libs" \
  NDK_OUT="$BUILD_DIR/obj" \
  "APP_CFLAGS=-O3 -DPKGNAME=com/palazik/vpn/service -DCLSNAME=TProxyService" \
  "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"

for abi in arm64-v8a armeabi-v7a; do
  install -d "$OUTPUT_DIR/$abi"
  install -m 0755 "$BUILD_DIR/libs/$abi/libhev-socks5-tunnel.so" \
    "$OUTPUT_DIR/$abi/libhev-socks5-tunnel.so"
done
