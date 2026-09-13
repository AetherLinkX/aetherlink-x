# AetherLink X for Android

AetherLink X is an Android 8+ VPN client with an original Compose interface,
subscription management, profiles, connection diagnostics, split tunnelling
and light/dark themes.

## Connection engines

- **ALX Preview 8 Turbo** through the embedded native ALX client.
- **Xray compatibility** for VLESS, VMess, Trojan, Shadowsocks, Hysteria2,
  TUIC, AnyTLS, WireGuard, SOCKS5 and HTTP profiles supported by the bundled
  Xray build.
- RAW/TCP, WebSocket, gRPC, XHTTP and HTTP Upgrade transports where supported
  by the selected protocol and Xray version.

ALX profiles and ordinary Xray subscriptions coexist. Importing or selecting an
ALX location does not convert unrelated VLESS/REALITY locations.

## Current release

- Version: **0.8.2-alx-preview.8**
- Minimum Android: **8.0 (API 26)**
- [Download signed release APK](https://github.com/AetherLinkX/aetherlink-x/releases/tag/client-v0.8.2-alx-preview.8)

Verify the downloaded APK with the SHA-256 checksum attached to the release.
Installing a release over an existing signed installation preserves profiles
and settings.

## Build

The Android build requires JDK 21, Android SDK 37 and native dependencies
produced by the release workflow. From client/android:

~~~bash
./gradlew testDebugUnitTest assembleDebug
~~~

The GitHub Actions workflow pins the Xray-core, AndroidLibXrayLite and
hev-socks5-tunnel revisions used for release builds.

## Physical-device validation

The scripts in [tools](../tools/README.md) can import a private test profile,
check HTTPS services, exercise reconnects and verify the configured certificate
pin. They require ADB and intentionally keep secrets outside Git.

## Privacy

The application needs Android's VPN permission to create the system TUN
interface. Connection diagnostics may contain server addresses and profile
names; remove private information before sharing logs.
