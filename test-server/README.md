# Чистый тестовый сервер

Этот каталог хранит шаблоны проверенного резервного VLESS. Рабочая точка отката
зафиксирована Git-тегом `baseline-vless-reality-0.7.2`; закрытые конфиги и ключи
исключены из Git. Экспериментальный ALX/1 развивается отдельно в `core/alx` и
ветке `alx/native-preview`.

Файл `config.example.json` содержит безопасный шаблон. Перед запуском замените
`REPLACE_WITH_UUID` и параметры REALITY, сгенерированные командой `xray x25519`.

## Установка на Ubuntu 22.04/24.04

1. Установите официальный Xray:

   ```bash
   bash -c "$(curl -L https://github.com/XTLS/Xray-install/raw/main/install-release.sh)" @ install
   ```

2. Создайте UUID и пару REALITY:

   ```bash
   xray uuid
   xray x25519
   openssl rand -hex 8
   ```

   В серверный `privateKey` помещается `PrivateKey`, а в клиентский `password`
   (или совместимый старый параметр `publicKey`) — значение `Password
   (PublicKey)`. Последняя команда создаёт `shortId`.

3. Скопируйте шаблон в `/usr/local/etc/xray/config.json`, замените три
   заполнителя и проверьте его до перезапуска:

   ```bash
   xray run -test -config /usr/local/etc/xray/config.json
   systemctl restart xray
   systemctl --no-pager --full status xray
   ```

4. Разрешите входящий TCP-порт 443 в firewall/VPS-панели. Не открывайте
   диагностические SOCKS-порты в интернет.

## Контрольная проверка

- `protocol` на обеих сторонах должен быть строго `vless`.
- `flow` — `xtls-rprx-vision`, транспорт — `raw`, защита — `reality`.
- UUID, REALITY password/public key и `shortId` клиента должны соответствовать
  серверу.
- Перед использованием другого REALITY-target проверьте его командой
  `xray tls ping DOMAIN`. Target — самостоятельная часть TLS-маскировки, а не
  реализация AetherLink X.
- Выполните минимум пять HTTPS-запросов через внешний адрес сервера, затем
  проверьте DNS/UDP и пять переподключений в Android-приложении.

На тестовом VPS резервный Xray v26.7.28 переведён на локальный
`127.0.0.1:8444`, а общий TCP/443 принимает L4-шлюз ALX. По ALPN `alx/1` он
направляет соединение в собственное ядро; остальные соединения побайтно передаёт
в Xray. Контрольные запросы через оба пути вернули HTTP 204. Для полного отката
на VPS сохранён снимок
`/root/aetherlink-x-backups/baseline-20260906/xray-config.before-alx-gateway.json`.
