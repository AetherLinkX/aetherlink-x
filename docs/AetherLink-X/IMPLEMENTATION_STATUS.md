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

## Артефакт

```text
Для протокола/dist/windows-amd64/xray-aetherlinkx.exe
size: 47,567,360 bytes
SHA-256: DF64291E04B58185FCE7ADBA9FC71D0A6A06DF83CBB70891146EAF9E606B51C3
```

## Интеграция с Remnawave 3.3.2

Подготовлена воспроизводимая интеграция, состоящая из двух согласованных образов:

- custom Remnawave Node со сборкой Xray `v26.7.28`, в которую применён ALX patch;
- custom Remnawave Backend с разрешённым статическим `aetherlinkx` inbound, ALX-aware JSON Schema и браузерным WASM-валидатором.

Patch ядра проверен на точном Xray commit `5ca6f4b7d4dc20a881d4330e498892697627ec0c`. Backend patch проверен на Remnawave Backend `3.3.2` commit `347e6de129f0289a3831dfbb7452d36b49528f3c`; TypeScript/Rspack build завершился успешно. Браузерный WASM-валидатор также собран успешно. Multi-arch Docker-сборку и публикацию `linux/amd64`/`linux/arm64` выполняет GitHub Actions.

Текущий режим — статический ALX inbound: его accounts сохраняются внутри Config Profile и не изменяются обычными событиями пользователей Remnawave. Динамическое создание ALX accounts и генерация ALX-ссылок подписки не заявлены как готовые: для них необходимо расширить схему БД пользователя, `xtls-sdk`, backend, frontend и клиенты подписки.

## Рекомендации перед production

1. Провести независимый криптографический и protocol review.
2. Добавить QUIC/unreliable datagram transport: это важнее «агрессивного Mux» для игр, потому что убирает TCP head-of-line.
3. Сделать отдельные realtime/bulk connection pools и измеряемый fair-queue/AQM.
4. Добавить session byte/time rekey для inner AEAD.
5. Проверить Linux race tests, soak tests и loss/jitter/netem матрицу.
6. Сравнить direct, WARP и MPTCP по median/p95/p99 RTT — не включать их без измерений.
7. Зафиксировать wire 1.x только после interoperability tests между версиями клиента и сервера.
