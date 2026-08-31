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

## Транспортная совместимость

Генератор конфигурации сверяется с `XTLS/Xray-core@v26.7.28` и моделью
`StreamSettingsBean` из v2rayNG. Реализованы RAW TCP, WebSocket, gRPC, XHTTP,
HTTPUpgrade, mKCP и Hysteria transport. Для gRPC отдельно сохраняются
`serviceName` и `authority`, mux Xray выключается, а `h2` ставится первым и
единственным ALPN. Для WS/HTTPUpgrade используется `http/1.1`.

Legacy transport `h2/http` и `quic` в Xray v26.7.28 удалены. Импортированные
ссылки не отбрасываются: H2 преобразуется в XHTTP `stream-one` + ALPN h2, QUIC
— в XHTTP `stream-one` + ALPN h3. Это современная замена, но серверная сторона
тоже должна использовать XHTTP.

UDP здесь не является отдельным share-link transport. Пакеты UDP устройства
перехватываются TUN, HEV передаёт их в SOCKS/XUDP, а AetherLink X кодирует
назначение и datagram framing в собственном `commandUDP`. Поэтому исправление
UDP проверяется на всём маршруте, а не добавлением фиктивного `type=udp`.

## Переключение и измерения

При смене активного профиля работающий сервис последовательно останавливает
HEV, Xray loop и старый TUN, затем запускает новый профиль внутри того же
`VpnService`. Это исключает конфликт локальных listener и не требует нового
разрешения Android.

Проверки разделены по смыслу: TCP/ICMP измеряют конкретный endpoint, HTTP
GET/HEAD измеряют активный end-to-end туннель, а AetherLink Ping выполняет три
GET и берёт медиану. Массовое сравнение профилей намеренно использует TCP,
иначе все результаты прошли бы через один и тот же активный outbound.
