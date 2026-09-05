# AetherLink X для Android

## Текущий базовый режим

В версии `0.7.0-vless-baseline` пункт **AetherLink X** является именованным
профилем VLESS. При запуске приложение создаёт обычный Xray outbound
`protocol: "vless"`; отдельный ALX handshake и изменённое ядро отсутствуют.

Поддерживаются стандартные профили приложения: VLESS, VMess, Trojan,
Shadowsocks, Hysteria2, TUIC, AnyTLS, WireGuard, SOCKS5 и HTTP, а также
транспорты RAW/TCP, WebSocket, gRPC, XHTTP, HTTP Upgrade и поддерживаемые
актуальным Xray варианты.

## Импорт AetherLink X

Рекомендуемый формат для панели — обычная VLESS-ссылка с названием локации,
содержащим `AetherLink X`, например:

```text
vless://UUID@SERVER:PORT?type=tcp&security=reality&sni=SNI&fp=chrome&pbk=PUBLIC_KEY&sid=SHORT_ID#AetherLink%20X%20Test
```

Клиент покажет такую локацию как AetherLink X, но передаст Xray стандартный
VLESS-конфиг. Для ручного обмена также принимается схема `aetherlinkx://`;
она является только псевдонимом VLESS URI и не меняет данные на проводе.

## Сборка

GitHub Actions собирает `libv2ray.aar` из официального Xray-core `v26.7.28`
без патчей, затем запускает Android unit tests и создаёт APK.

Локально, при установленных Android SDK/JDK 21 и готовом `libv2ray.aar`:

```text
cd client/android
./gradlew testDebugUnitTest assembleDebug
```

## Проверка перед развитием протокола

1. Импортировать тестовую подписку.
2. Убедиться, что в JSON профиля outbound имеет `protocol: "vless"`.
3. Проверить сайты, загрузку файла и DNS.
4. Выполнить не менее пяти отключений и повторных подключений.
5. Переключиться VLESS → AetherLink X → VLESS при активном VPN.
6. Только после успешной серии начинать одно изолированное изменение.
