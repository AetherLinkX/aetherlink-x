# AetherLink X — итог реализации

## Результат

В отдельной папке Xray-core `proxy/aetherlinkx` создан экспериментальный proxy-протокол wire 1.1. Он зарегистрирован как inbound/outbound под именами `aetherlinkx` и `alx`, включён в статическую distro-сборку и работает поверх стандартных transport/security слоёв Xray.

Это полноценный исследовательский прототип, но не прошедший независимый криптографический аудит production-релиз.

## Соответствие требованиям

| Требование | Реализация | Ограничение |
|---|---|---|
| REALITY | используется штатный `streamSettings.security: reality`; plaintext по умолчанию запрещён | ALX не форкает REALITY и не управляет его TLS fingerprint |
| WARP | готовый клиентский пример через WireGuard outbound + `sockopt.dialerProxy` | WARP credentials выдаются внешним сервисом; маршрут может увеличить RTT |
| Turbo / Game Mode | negotiated deadline, sequence, destination cache, payload limit, keepalive/user-timeout, congestion sockopt, Mux/XUDP tuning | UDP пока идёт по ordered stream, поэтому TCP HOL полностью не устранён |
| Bufferbloat | stale-delivery drop и короткие OS timeouts; bulk batching не добавляется | собственного fair queue/CoDel в этой версии нет |
| Mux | автоматическая настройка штатного Xray Mux/XUDP | агрессивный Mux может ухудшить p99 latency при loss |
| BBR/Cubic | явный `congestion` передаётся в socket config; `auto` сохраняет OS default | автоматического runtime-переключения нет; BBR зависит от ядра ОС |
| XTLS Vision | архитектурно совместим с внешним Xray transport | собственного Vision/direct-copy adapter нет; inner AEAD исключает splice |
| динамический fingerprint | persona задаётся штатным REALITY/uTLS на transport session | смена полей внутри сессии намеренно не реализована как аномальная |
| Stealth | случайный размер зашифрованных records и bounded random padding без sleep | это shaping, не доказанная имитация видеозвонка/стриминга |
| post-quantum | внутренний X-Wing: X25519 + ML-KEM-768, режимы off/prefer/required | это отдельный слой от PQ-группы внешнего REALITY/TLS |
| усиленное шифрование | directional ChaCha20-Poly1305, authenticated length/counters | добавляет копии и overhead; rekey внутри одной сессии пока нет |
| WS/gRPC/HTTP2/RAW | работает через существующий Xray stream dialer/listener | fake HTTP chunked после WebSocket Upgrade не применяется, так как это некорректно |
| MPTCP | `tcpMptcp` socket opt-in | требует поддержки ОС; native ALX multipath lanes отсутствуют |
| «усилитель скорости» | UDP destination-header dictionary + отказ от artificial delay/batching + socket/Mux tuning | TCP ACK управляет ядро ОС; прокси не подделывает ACK и не ломает congestion control |

## Криптографическая схема

У каждой учётной записи есть публичный UUID и независимый случайный 32-byte secret. На проводе передаётся HMAC-derived 128-bit key ID. `ClientInit` и `ServerAccept` имеют разные HKDF/HMAC контексты, свежие nonces и session ID. Timestamp ±2 минуты и bounded replay cache защищают от повторной отправки.

В `pqMode: required` клиент encapsulates X-Wing shared secret публичным ключом сервера. Этот secret смешивается с account secret до проверки binder и до вывода AEAD-ключей. Клиент аутентифицированно проверяет, что сервер не выключил PQ, AEAD или Turbo.

В managed-профиле серверный JSON может хранить совпадающий публичный X-Wing ключ рядом с приватным seed, чтобы Backend формировал клиентские ссылки. Ядро проверяет соответствие пары в constant-time, отбрасывает публичный ключ из серверных runtime-настроек и fail-closed отклоняет несовпадающие ключи.

Внутренние records используют разные ключи для uplink/downlink и ChaCha20-Poly1305. Length входит в associated data; malformed tag/length/padding закрывает соединение.

## Созданные файлы протокола

```text
proxy/aetherlinkx/
  account.go
  validator.go
  protocol.go
  turbo.go
  security.go
  record.go
  stealth.go
  client.go
  server.go
  config.proto
  config.pb.go
  protocol_test.go
  benchmark_test.go
  README.md
  SECURITY.md
  BENCHMARKS.md
  examples/client-reality.json
  examples/client-warp-reality.json
  examples/server-reality.json
```

Дополнительно созданы или изменены:

```text
infra/conf/aetherlinkx.go
infra/conf/aetherlinkx_test.go
infra/conf/xray.go
main/distro/all/all.go
main/commands/all/aetherlinkx.go
main/commands/all/commands.go
testing/scenarios/aetherlinkx_test.go
```

## Проверки

- unit/security tests: успешно;
- TLS TCP end-to-end: успешно;
- Turbo UDP + required X-Wing + inner AEAD + Stealth end-to-end: успешно;
- handshake fuzz: 122 228 executions / 10 s, успешно;
- AEAD record fuzz: 65 403 executions / 10 s, успешно;
- `go vet ./proxy/aetherlinkx`: успешно;
- финальная Windows/amd64 distro-сборка: успешно;
- keygen smoke-test: UUID, 32-byte secret, 32-byte private seed и 1216-byte public key проверены.

Race-detector не запущен: portable Windows Go toolchain не имеет CGO C compiler. Полный `go vet ./infra/conf` также видит уже существующий upstream unreachable block legacy reverse в `infra/conf/xray.go:701`; это не ALX-код.

## Артефакты

Готовые бинарники не хранятся в Git. Они воспроизводимо собираются из patch и
исходников, а APK/образы публикуются GitHub Actions вместе с контрольными суммами.

## Интеграция с Remnawave

Подготовлена воспроизводимая интеграция, состоящая из двух согласованных образов:

- custom Remnawave Node со сборкой Xray `v26.7.28`, в которую применён ALX patch;
- custom Remnawave Backend с разрешённым статическим `aetherlinkx` inbound, ALX-aware JSON Schema и браузерным WASM-валидатором.

Patch ядра проверен на точном Xray commit `5ca6f4b7d4dc20a881d4330e498892697627ec0c`. Backend patch проверен на Remnawave Backend `3.3.2` commit `347e6de129f0289a3831dfbb7452d36b49528f3c`; TypeScript/Rspack build завершился успешно. Браузерный WASM-валидатор также собран успешно.

GitHub Actions run [`33241158963`](https://github.com/AetherLinkX/aetherlink-x/actions/runs/33241158963) завершился со статусом `Success` и опубликовал multi-arch образы `linux/amd64` + `linux/arm64`; пакеты GHCR впоследствии переведены в публичный режим:

- `ghcr.io/aetherlinkx/aetherlink-x-remnawave-node:sha-6c536a9` — `sha256:ad434d56d469288d752c18b2da4496117bfcbd6249402c60a9ffbc4fbb7b6a82`;
- `ghcr.io/aetherlinkx/aetherlink-x-remnawave-backend:sha-6c536a9` — `sha256:580229d362c70eec72b0dcf26b54e857ce4831ad927f0afc65e8f78883ed27ae`.

Тег `latest` указывает на те же версии, но для воспроизводимого развёртывания следует использовать неизменяемый `sha-6c536a9` либо полный digest.

Для 3.3.2 сохранён static-режим. Для фактической пары Panel 2.7.4 +
Node 3.2.2 реализован отдельный managed-режим:

- Backend инжектирует пользователей только в inbound с tag-префиксом
  `AETHERLINK_X_MANAGED_`;
- доступ определяется членством в Internal Squad;
- `id` берётся из `vlessUuid`, уникальный ALX secret выводится из
  `ssPassword` через фиксированный SHA-256 KDF context;
- Node выполняет add/remove через Xray HandlerService;
- генератор отдаёт raw `aetherlinkx://` и полноценный Xray JSON outbound;
- пустой squad запускает inbound с нулём accounts и отклоняет все handshakes;
- static ALX inbound исключён из managed-событий и не изменяется.

Схема базы данных и официальный SDK package не изменяются. Клиент должен
использовать ALX-capable Xray JSON либо отдельный ALX importer: обычные
сторонние клиенты неизвестный URI не распознают.

### Совместимость с установленной панелью 2.7.4

Фактическая панель `ad.obsa.su` работает на Remnawave `2.7.4`; её ноды используют Node `2.7.0`–`3.2.2` и штатный Xray `26.3.27`–`26.7.28`. Для неё добавлены отдельные воспроизводимые артефакты:

- `remnawave/backend-static-alx-2.7.4.patch`, привязанный к Backend commit `8032a39eae7a83d2a503ee5eab1f6545168178a5`;
- `remnawave/Dockerfile.backend-2.7.4` с Frontend `2.7.4` commit `180d24607660305b1d44e0861c83698b7904bb08`;
- GitHub Actions jobs для `aetherlink-x-remnawave-backend-2.7.4` и `aetherlink-x-remnawave-node-2.7.0`.
- managed Backend patch/image для точного Backend 2.7.4 commit;
- managed Node patch/image для точного Node 3.2.2 commit
  `2c532c4e33bf5864e9867a7bdc36245cc1057eb1`.

Оба managed patch прошли `git apply --check`; Backend прошёл Prisma 6.19.0
generation и `nest build`, Node — `npm run typecheck`. Общий golden-вектор
подтверждает одинаковое выведение ALX secret. Оба managed-образа прошли
multi-arch сборку `linux/amd64` + `linux/arm64` в GitHub Actions run
[`33270878989`](https://github.com/AetherLinkX/aetherlink-x/actions/runs/33270878989).
Production handshake/revoke-test на FI Node остаётся отдельным gate и не может
быть заменён успешной сборкой контейнера.

Совместимость не заявляется как «один patch для любой будущей версии»:
Panel, Node и Xray меняют внутренние TypeScript/Protobuf/Go интерфейсы. Каждая
точная версия сначала проходит `git apply --check`, сборку, smoke-test и только
после этого добавляется в fail-closed матрицу
`remnawave/compatibility-matrix.json`.

## Рекомендации перед production

1. Провести независимый криптографический и protocol review.
2. Добавить QUIC/unreliable datagram transport: это важнее «агрессивного Mux» для игр, потому что убирает TCP head-of-line.
3. Сделать отдельные realtime/bulk connection pools и измеряемый fair-queue/AQM.
4. Добавить session byte/time rekey для inner AEAD.
5. Проверить Linux race tests, soak tests и loss/jitter/netem матрицу.
6. Сравнить direct, WARP и MPTCP по median/p95/p99 RTT — не включать их без измерений.
7. Зафиксировать wire 1.x только после interoperability tests между версиями клиента и сервера.
