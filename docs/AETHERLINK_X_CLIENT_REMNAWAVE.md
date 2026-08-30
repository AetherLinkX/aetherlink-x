# AetherLink X Client: подключение к Remnawave

## Android

1. Откройте релиз `client-dev` в GitHub и скачайте
   `aetherlink-x-client-android-universal-debug.apk` вместе с файлом контрольной суммы.
2. Сверьте SHA-256 и установите APK. Android может попросить разовое разрешение на
   установку из браузера или файлового менеджера.
3. В AetherLink X Client откройте **Subscriptions → Add subscription**.
4. Вставьте обычную ссылку подписки пользователя Remnawave. User-Agent
   `AetherLinkX/0.1` уже установлен по умолчанию.
5. Обновите подписку. Клиент принимает оба варианта, которые может вернуть панель:
   Base64-список ссылок и полный Xray JSON.
6. Выберите профиль **AETHERLINK X**, нажмите **Connect** и подтвердите системный
   запрос Android VPN.

Одну ноду также можно импортировать через QR-код, буфер обмена или прямую ссылку
`aetherlinkx://`. Поля `secret`, Turbo, PQ/security и Stealth переносятся автоматически.

## Если профиль не появился

- Убедитесь, что пользователь состоит во внутреннем squad, которому доступен ALX-host.
- Проверьте, что в профиле Remnawave есть ALX inbound и пользователю разрешён этот host.
- Обновите подписку внутри AetherLink X Client. Happ, v2RayTun и другие клиенты со
  стандартным Xray-core не обязаны понимать `aetherlinkx://`.
- Не публикуйте subscription URL, UUID, account secret, REALITY private key или X-Wing
  private key в тикетах и скриншотах.

## Совместимость

- Android 8+ (`arm64-v8a` и `armeabi-v7a`): собственное пропатченное ядро.
- Linux x64: собственный `xray-aetherlinkx` в portable-архиве.
- iOS: UI/импорт находятся в разработке; ALX-соединение не заявлено до выпуска
  пропатченного XCFramework и теста на физическом устройстве.

Протокол экспериментальный и пока не проходил независимый криптографический аудит.
