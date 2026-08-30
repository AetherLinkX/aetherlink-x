#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <hev-socks5-tunnel-source> <jni-libs-output>" >&2
  exit 2
fi

source_dir="$(cd "$1" && pwd)"
output_dir="$2"
ndk_home="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$ndk_home" || ! -x "$ndk_home/ndk-build" ]]; then
  echo "ANDROID_NDK_HOME must point to an Android NDK containing ndk-build" >&2
  exit 2
fi

build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
mkdir -p "$output_dir"

"$ndk_home/ndk-build" \
  NDK_PROJECT_PATH="$build_dir" \
  APP_BUILD_SCRIPT="$source_dir/Android.mk" \
  APP_MODULES=hev-socks5-tunnel \
  APP_ABI="arm64-v8a armeabi-v7a" \
  APP_PLATFORM=android-26 \
  NDK_LIBS_OUT="$output_dir" \
  NDK_OUT="$build_dir/obj" \
  "APP_CFLAGS=-O3 -DPKGNAME=com/palazik/vpn/service -DCLSNAME=HevTunBridge" \
  "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"

for abi in arm64-v8a armeabi-v7a; do
  test -s "$output_dir/$abi/libhev-socks5-tunnel.so"
done
