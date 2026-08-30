# Исследование Android-клиентов

## Проверенные репозитории

- [INCY-DEV/incy-platforms](https://github.com/INCY-DEV/incy-platforms) содержит описание и метаданные релиза, но не исходники Android-приложения.
- [LieutenantColonnade/v2raytun](https://github.com/LieutenantColonnade/v2raytun) публикует roadmap и релизы; полного кода клиента нет.
- [Happ-proxy/happ-android](https://github.com/Happ-proxy/happ-android) содержит README и релизные файлы, но не полные исходники.
- [2dust/v2rayNG](https://github.com/2dust/v2rayNG) — открытый GPLv3 Android-клиент Xray. Его подходы к жизненному циклу `VpnService`, `libv2ray`, TUN-to-SOCKS, подпискам и геоданным взяты как проверяемый ориентир.
- [heiher/hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — MIT-мост между Android TUN и локальным SOCKS5 Xray.

Копировать закрытые клиенты Incy/Happ/v2RayTun из APK нельзя: нет публичной лицензии на их исходники. Поэтому AetherLink X использует собственный Kotlin/Compose-код и совместимые паттерны из открытого v2rayNG.

## Текущая схема AetherLink X

1. `MainActivity` получает deep link или текст из буфера/QR.
2. `MainViewModel.importFromText` различает HTTP(S)-подписку, один профиль, Base64-список и JSON.
3. `ProfileRepository` проверяет и хранит профили и подписки.
4. `palazikVpnService` строит full-device TUN, запускает пропатченный Xray через `libv2ray` и передаёт TUN-дескриптор в HEV.
5. Xray слушает приватный динамический SOCKS-порт. Фиксированный `10808` не используется в боевом VPN-сеансе.
6. DNS IPv4/IPv6 захватываются TUN-интерфейсом и передаются в Xray; приложение и адрес сервера исключаются из петли.

Android сам гарантирует, что одновременно активен только один `VpnService`: диалог `VpnService.prepare` передаёт разрешение AetherLink X и отзывает его у прежнего VPN. Принудительно закрывать чужое приложение без root/device-owner нельзя и не нужно.
