# AetherLink X

Статус: **архитектурный проект + рабочая экспериментальная реализация wire 1.1**  
Назначение: проектирование нового proxy-протокола для Xray-core  
Реализация находится в `Xray-core-source/Xray-core-main/proxy/aetherlinkx`. Работают аутентифицированный handshake и replay-защита, TCP/UDP, Turbo deadline/destination framing, Xray Mux/XUDP tuning, X-Wing (X25519 + ML-KEM-768), внутренний ChaCha20-Poly1305, Stealth record shaping, MPTCP/socket tuning, REALITY/TLS enforcement и WARP composition через WireGuard outbound. Точный реализованный wire format, ограничения и проверяемые JSON-поля описаны в `proxy/aetherlinkx/README.md`. Более широкие идеи Vision, native multipath lanes, fair-queue/AQM и unreliable datagram transport ниже являются roadmap, а не заявлением о готовой функции.

## 1. Введение

**AetherLink X (ALX)** — проект низколатентного proxy-протокола для Xray-core, ориентированного на интерактивный UDP/TCP-трафик, игровые сессии, устойчивость при смене сети и совместимость с существующими transport/security слоями Xray.

Основная идея — не собирать ещё один монолит наподобие исторического VMess, а разделить функции:

- **AetherLink X core**: аутентификация пользователя, команды, потоки, datagram framing и приоритеты;
- **AetherLink Turbo**: очереди, pacing, deadline-aware UDP, изоляция игровых потоков и выбор congestion policy;
- **REALITY/TLS 1.3**: внешний handshake, server authentication, forward secrecy и post-quantum hybrid key exchange;
- **XTLS/Vision-compatible flow**: устранение лишнего TLS-in-TLS копирования там, где это безопасно и поддерживается transport;
- **Stealth**: согласованный TLS/HTTP persona и ограниченное shaping после шифрования;
- **WARP/WireGuard**: опциональный underlay/egress до ALX-сервера, а не часть wire format;
- **Multipath**: либо системный MPTCP для RAW TCP, либо несколько независимых ALX lanes.

Целевая метрика ALX — не максимальная пропускная способность любой ценой, а минимизация:

- median и p95/p99 RTT;
- jitter;
- queueing delay под параллельной загрузкой;
- потерь полезных realtime datagrams;
- времени восстановления после смены Wi‑Fi/LTE;
- дополнительных RTT при установлении сессии.

### 1.1. Что ALX не обещает

ALX не может гарантировать «невидимость» для любого DPI. Транспортный fingerprint, размеры, направления и тайминги остаются наблюдаемыми даже при сильном шифровании. Цель Stealth — уменьшить стабильные сигнатуры, не создавая заведомо фальшивый и ещё более уникальный fingerprint.

ALX также не может сделать плохой маршрут физически быстрее. WARP, MPTCP и BBR иногда улучшают маршрут или поведение очередей, но иногда увеличивают RTT. Все такие функции должны быть измеряемыми, отключаемыми и иметь автоматический rollback.

## 2. Краткий анализ VLESS/Xray

### 2.1. VLESS

VLESS — лёгкий proxy layer: version, UUID, optional protobuf addons, command, destination и payload. Он хорошо отделён от transport layer и потому работает поверх RAW, XHTTP, gRPC, WebSocket и других transports.

Сильная сторона VLESS — малый protocol overhead. Слабая сторона classic VLESS — отсутствие самостоятельной защиты payload: UUID и metadata нельзя безопасно передавать через публичную сеть без TLS/REALITY или VLESS Encryption.

### 2.2. REALITY

REALITY модифицирует TLS-поведение так, чтобы внешний handshake и сертификатный контекст соответствовали выбранному реальному target. В актуальном Xray REALITY совместим прежде всего с RAW, XHTTP и gRPC.

REALITY — security/masquerade layer снаружи proxy protocol. ALX не должен повторять его handshake внутри каждого transport.

Если REALITY target поддерживает `X25519MLKEM768`, актуальный Xray способен использовать этот hybrid post-quantum key exchange. Устаревшее имя `X25519Kyber768Draft00` в ALX не используется: draft Kyber заменён стандартизованным ML-KEM naming и актуальными hybrid groups.

### 2.3. XTLS Vision

Vision анализирует внутренний TLS-поток, добавляет padding к ранним данным и при подходящих условиях переключается на direct copy/splice, избегая лишних копирований и повторной обработки уже зашифрованного payload.

Для ALX нужен не форк Vision wire format, а общий flow adapter — условный `aether-vision`. Он должен переиспользовать общие Vision reader/writer primitives, но не зависеть от VLESS account type.

### 2.4. Mux/XUDP

Mux.Cool сокращает число handshake для множества коротких соединений, однако один общий надёжный TCP stream создаёт head-of-line blocking. Для игр агрессивное смешивание realtime UDP, web и загрузок в одном mux ухудшает tail latency при потерях.

ALX использует **изоляцию lanes**, а не один «максимально агрессивный» mux:

- realtime lane — без batching и без bulk traffic;
- interactive lane — DNS, control, короткие TCP flows;
- bulk lane — загрузки и фоновые потоки;
- optional datagram lane — ненадёжная доставка без retransmission устаревших пакетов.

### 2.5. uTLS/fingerprints

Имитация ClientHello полезна только как согласованный persona: набор extensions, их порядок, ALPN, key shares, record sizing и HTTP-поведение должны соответствовать друг другу.

Случайная смена отдельных TLS-полей на каждом пакете создаёт аномалию. ALX выбирает persona на новую transport session, удерживает его до закрытия соединения и меняет только между сессиями по bounded policy.

## 3. Ядро протокола

### 3.1. Положение в Xray

ALX реализуется как proxy protocol:

```text
proxy/aetherlinkx/
  account + validator
  encoding
  inbound
  outbound
  turbo
  multipath
```

Он должен реализовать стандартные Xray interfaces:

```text
proxy.Inbound:
  Network() []net.Network
  Process(ctx, network, connection, dispatcher) error

proxy.Outbound:
  Process(ctx, link, dialer) error
```

ALX не добавляет собственный transport dialer для RAW/XHTTP/gRPC/WebSocket. Outbound получает готовый `stat.Connection` через существующий `internet.Dialer`.

### 3.2. Режимы security

#### `transport-bound` — режим по умолчанию

- REALITY или TLS 1.3 обязательны для public destination.
- ALX ClientInit передаётся только после завершения внешнего handshake.
- User authentication выполняется binder-тегом, а не открытым UUID.
- Payload повторно не шифруется, чтобы сохранить минимальный overhead и возможность Vision/direct copy.
- Post-quantum protection обеспечивается `X25519MLKEM768` во внешнем REALITY/TLS handshake, если группа согласована.

#### `exporter-aead` — усиленная защита

- Требует transport, предоставляющий TLS exporter/channel binding.
- ALX выводит отдельные uplink/downlink keys из exporter material, user secret и nonces.
- Frames дополнительно защищаются AES-256-GCM либо ChaCha20-Poly1305.
- Rekey выполняется по лимиту bytes/time.
- Режим несовместим с полным Vision splice, потому что ALX обязан видеть и переупаковывать каждый frame.

Этот профиль предназначен для defense-in-depth, но не является Turbo default.

#### `none`

Разрешён только для loopback/private trusted network и тестов. JSON builder должен запрещать public ALX outbound с `securityProfile: "none"` и без transport security — по аналогии с текущими ограничениями VLESS/Trojan.

### 3.3. User identity

У пользователя есть два независимых значения:

- `id`: 16-byte случайный идентификатор/управляющий ключ для логов и API;
- `secret`: 32 random bytes, закодированные Base64URL; это authentication key material.

Пароль человека не должен напрямую использоваться как `secret`. Если UI принимает пароль, он обязан применить memory-hard password KDF и сохранить результат, но рекомендуемый путь — криптографически случайный secret.

На проводе передаётся `key_id`, а не secret:

```text
key_id = Trunc128(HMAC-SHA256(user_secret, "ALX key-id v1" || server_context))
```

Server precomputes lookup map `key_id → MemoryUser`. `key_id` не считается секретом, но находится внутри защищённого transport.

### 3.4. Handshake state machine

```text
Client                         Server
  |                              |
  |--- REALITY/TLS handshake --->|
  |<-- secure transport ready ---|
  |                              |
  |--- ClientInit -------------->|
  |    version/features/nonces   |
  |    key_id/auth_binder        |
  |                              |
  |<-- ServerAccept/Reject -------|
  |    selected features/binder  |
  |                              |
  |=== Streams / Datagrams ======|
```

0-RTT application data отключена в v1. Она увеличивает replay surface, а выигрыш после REALITY resumption меньше риска повторной отправки игрового или управляющего payload.

Handshake limits:

- maximum ClientInit: 4096 bytes;
- maximum extensions block: 2048 bytes;
- handshake deadline: configurable, default 4 seconds;
- authentication выполняется до открытия target connection;
- unknown critical extension завершает handshake;
- unknown non-critical extension игнорируется;
- reject не содержит подробной причины для remote peer.

### 3.5. ClientInit

Все multi-byte integers используют network byte order; variable integers используют bounded QUIC-style varint.

| Поле | Размер | Назначение |
|---|---:|---|
| `version_major` | 1 | несовместимая версия |
| `version_minor` | 1 | совместимые расширения |
| `flags` | 2 | capabilities/critical flags |
| `header_len` | varint | длина оставшегося ClientInit |
| `key_id` | 16 | lookup пользователя |
| `client_nonce` | 16 | случайный nonce сессии |
| `session_id` | 8 | корреляция multipath lanes |
| `feature_bits` | varint | Turbo, datagram, multipath, vision, exporter-aead |
| `max_frame` | varint | предлагаемый maximum frame |
| `extensions_len` | varint | bounded TLV block |
| `extensions` | variable | параметры без secrets |
| `auth_binder` | 32 | HMAC-SHA256 transcript binder |

Binder:

```text
auth_key = HKDF-SHA256(user_secret,
                       salt = client_nonce,
                       info = "ALX client auth v1")

auth_binder = HMAC-SHA256(auth_key,
                          transcript_without_binder || channel_binding)
```

`channel_binding` обязателен для `exporter-aead` и желателен для `transport-bound`, если transport wrapper его предоставляет. При отсутствии exporter в `transport-bound` binder всё равно защищает от подмены со стороны другого пользователя, а transport обеспечивает конфиденциальность и целостность.

### 3.6. ServerAccept

| Поле | Размер | Назначение |
|---|---:|---|
| `version_major/minor` | 2 | выбранная версия |
| `status` | 1 | 0 = accepted; остальные коды не детализируются remote peer |
| `flags` | 1 | negotiated options |
| `server_nonce` | 16 | nonce сервера |
| `session_id` | 8 | подтверждённый session id |
| `selected_features` | varint | итоговые возможности |
| `max_frame` | varint | итоговый limit |
| `auth_binder` | 32 | server transcript authentication |

Server binder использует отдельный HKDF info `"ALX server auth v1"` и включает ClientInit целиком.

### 3.7. Общий frame header

После handshake multiplexed режим использует:

| Поле | Размер | Назначение |
|---|---:|---|
| `type` | 1 | OPEN, DATA, DATAGRAM, CLOSE, PING, PATH, ACK_META |
| `flags` | 1 | FIN, PRIORITY, DEADLINE, DUPLICATE |
| `stream_id` | varint | логический поток/lane |
| `payload_len` | varint | bounded payload |
| optional metadata | variable | зависит от flags/type |
| payload | variable | данные |

Limits применяются до allocation. `payload_len` больше negotiated `max_frame` — protocol error.

Для одиночного TCP CONNECT без Mux разрешён **raw stream fast path**: после OPEN_OK данные идут без frame headers до EOF/half-close. Это уменьшает overhead и сохраняет возможность Vision/direct copy.

### 3.8. Команды

- `CONNECT_STREAM` — TCP/Unix-like ordered stream;
- `ASSOCIATE_DATAGRAM` — UDP association;
- `OPEN_MUX` — набор логических streams;
- `OPEN_LANE` — дополнительный transport path той же ALX session;
- `PING/PONG` — path liveness/RTT, не application keepalive;
- `CLOSE` — status + optional local diagnostic code, не отправляемый до auth.

Destination encoding:

- address type: IPv4, IPv6, domain, cached-address token;
- port: uint16;
- domain length: varint, maximum 255 bytes;
- canonical UTF-8/IDNA policy определяется JSON builder;
- destination token может повторно использовать ранее подтверждённый адрес в пределах одной session.

### 3.9. Datagram frame

```text
type = DATAGRAM
flags
flow_id: varint
sequence: varint
deadline_delta_ms: varint
destination_token or destination
payload_len: varint
payload
```

Правила:

- datagram не retransmit после deadline;
- late datagram отбрасывается до помещения в игровую очередь;
- duplicate sequence отбрасывается;
- reordered datagrams доставляются приложению, если deadline не истёк;
- maximum payload выводится из path MTU;
- ALX не фрагментирует UDP payload по умолчанию; слишком большой datagram возвращает локальную ошибку или ICMP-equivalent signal;
- congestion control обязателен для собственного UDP/QUIC transport.

## 4. Подсистема AetherLink Turbo

### 4.1. Цель

Turbo уменьшает queueing delay и jitter под нагрузкой. Он не увеличивает бесконечно socket buffers и не смешивает все потоки в одном mux.

### 4.2. Классы трафика

| Класс | Примеры | Политика |
|---|---|---|
| P0 realtime | игровые input/state UDP | самый короткий queue budget, stale drop, no batching |
| P1 control | DNS, handshake, keepalive, path validation | высокая гарантия доставки, маленький объём |
| P2 interactive | voice, chat, короткие TCP requests | fair queue, умеренный burst |
| P3 bulk | downloads, updates, video bulk | остаточная полоса, не блокирует P0/P1 |

Класс назначается явными routing rules, портами/процессом/TUN metadata или локальным приложением. Сервер не должен классифицировать encrypted application payload с помощью ненадёжного DPI.

### 4.3. Борьба с bufferbloat

Turbo использует per-flow fair queue и CoDel-подобный AQM:

- очередь измеряется во времени (`sojourn time`), не только в packets/bytes;
- default `targetQueueMs = 4` и `maxQueueMs = 12` являются стартовыми, а не универсальными значениями;
- packets P0 с истёкшим deadline удаляются вместо бессмысленной поздней передачи;
- bulk pacing ограничивается оценённой bottleneck rate;
- P0 имеет небольшой reserved budget, но также подчиняется congestion control;
- ECN используется при доступности;
- capacity estimator снижает sending rate при росте RTT без роста delivery rate;
- metrics отслеживают base RTT, queue RTT, loss, late drops и reorder.

Receiver обязан иметь bounded queue. Отсутствие flow control у datagrams не разрешает неограниченное накопление памяти.

### 4.4. TurboMux

Вместо одного агрессивного mux создаются раздельные pools:

```text
realtime pool: 1–2 lanes, no batching, no bulk
interactive pool: 1–2 lanes, short coalescing <= 1 ms
bulk pool: отдельное соединение, обычный batching
```

Настройки:

- `maxRealtimeStreams`: ограничение одновременных realtime flows;
- `laneCount`: максимум активных transport lanes;
- `maxBatchDelayUs = 0` для game profile;
- `keepAliveSeconds`: NAT/path liveness, не инструмент снижения active RTT;
- `idleLaneSeconds`: закрытие лишней lane;
- `streamIsolation`: запрет bulk/realtime на одной TCP lane;
- `redundantControl`: optional duplication только малых control packets.

Важно: Mux поверх TCP не устраняет TCP head-of-line blocking. Поэтому для realtime UDP при потерях предпочтительнее QUIC DATAGRAM/Hysteria-like datagram transport либо native UDP. WS/gRPC/HTTP/2/RAW TCP остаются compatibility paths.

### 4.5. Congestion control

`congestion: "auto"` выбирает механизм по transport и ОС:

- RAW TCP/Linux: BBR, если доступен; иначе системный CUBIC;
- MPTCP: kernel scheduler + congestion policy каждого subflow;
- WS/gRPC/XHTTP поверх TCP: алгоритм нижнего TCP socket, если transport позволяет применить sockopt;
- QUIC/datagram lane: BBR/CUBIC-подобный controller с pacing;
- WARP underlay: ALX не управляет внутренним controller Cloudflare и адаптирует только собственные queues.

BBR не гарантирует меньший ping на каждом маршруте. Auto controller сравнивает base RTT, p95 RTT и loss; при деградации возвращается к CUBIC/default policy.

### 4.6. «Усилитель скорости»

Модуль называется **AetherLink Accelerator** и включает только корректно реализуемые оптимизации:

1. **Header compression**
   - cached destination tokens;
   - delta/varint для stream ID, sequence и deadlines;
   - omission неизменившихся per-flow metadata;
   - bounded session dictionary;
   - никакого общего payload compression по умолчанию.

2. **Write coalescing по классам**
   - P0: disabled;
   - P1/P2: максимум 0–1 ms;
   - P3: adaptive batching.

3. **ACK optimization**
   - ALX не подавляет TCP ACK: это функция kernel/transport;
   - для RAW TCP используются существующие `TCP_NODELAY` defaults Go и доступные OS socket policies;
   - для custom datagram lane ACK ranges объединяются, но control ACK не задерживается дольше negotiated limit;
   - duplicate ACK metadata не отправляется, если состояние не изменилось.

4. **Buffer pooling/zero-copy**
   - reuse Xray `buf.MultiBuffer`;
   - raw fast path;
   - Linux splice только в совместимом transport-bound/Vision режиме.

Payload compression выключена, потому что игры, TLS и media обычно уже сжаты, а совместное сжатие secrets и attacker-controlled data создаёт compression side channels.

## 5. Стек шифрования

### 5.1. Рекомендуемый стек Turbo

```text
REALITY/TLS 1.3
  key exchange: X25519MLKEM768 preferred
  fallback: X25519 only if pqMode = prefer
  traffic AEAD: negotiated TLS 1.3 AES-GCM/ChaCha20-Poly1305
  server authentication: REALITY mechanism

ALX authentication
  user secret: random 256 bit
  KDF: HKDF-SHA256
  binder: HMAC-SHA256
  nonces: 128 bit random per side
  0-RTT: disabled

ALX body
  transport-bound: no duplicate encryption
  exporter-aead: AES-256-GCM or ChaCha20-Poly1305
```

### 5.2. Почему ML-KEM, а не Kyber draft

`X25519Kyber768Draft00` — историческое draft-название. ALX использует ML-KEM-768 terminology и hybrid `X25519MLKEM768`, присутствующий в актуальных TLS/Xray implementations.

Режимы:

- `pqMode: "prefer"` — использовать hybrid, если REALITY target/peer его согласовал, иначе X25519;
- `pqMode: "required"` — handshake завершается, если hybrid group не выбрана;
- `pqMode: "off"` — только для compatibility/диагностики.

`required` повышает security policy, но может ухудшить совместимость с middleboxes и увеличивает ClientHello. Его нельзя включать без проверки target и реальной сети.

### 5.3. Rekey

Для `exporter-aead`:

- отдельные keys на направление;
- отдельные nonce spaces для control/stream/datagram;
- rekey после `min(1 GiB, 30 минут, 2^32 frames)`;
- old key хранится только на короткое overlap window для reordered datagrams;
- key update аутентифицирован текущим key;
- повтор sequence/nonce является fatal protocol error.

### 5.4. Replay protection

- TLS/REALITY early data отключена;
- ClientInit nonce уникален;
- accepted `(key_id, client_nonce)` хранится bounded TTL;
- multipath lanes подтверждаются HMAC path token, связанным с `session_id` и transport endpoint;
- datagram sequence window bounded;
- session tickets, если появятся позже, single-use либо имеют строгий replay policy.

## 6. Stealth Mode

### 6.1. Persona policy

Stealth выбирает один согласованный persona на transport session:

- `browser-streaming`: Chrome-like TLS + XHTTP HTTP/2/3 behavior + downstream burst profile;
- `rtc-interactive`: реальный QUIC/datagram transport предпочтителен; small bidirectional packets и bounded pacing;
- `generic-web`: обычный browser-like TLS и XHTTP;
- `off`: без дополнительного shaping.

Persona меняется только при новом соединении и не чаще заданного epoch. ALPN, transport, TLS fingerprint и HTTP behavior выбираются одной записью. Нельзя сочетать Chrome HTTP/2 persona с WebSocket-only HTTP/1.1 поведением и называть это тем же fingerprint.

### 6.2. Shaping

- padding применяется после encryption;
- padding budget задаётся как процент трафика и абсолютный bytes/sec;
- P0 packets не задерживаются ради shaping дольше `maxAddedLatencyMs`;
- dummy packets первыми отбрасываются при congestion;
- размеры и интервалы выбираются из bounded profile, а не полностью случайно;
- профиль должен проходить statistical tests против целевого реального трафика.

«Видеозвонок» и «стриминг» имеют разные формы. Один универсальный режим невозможен: streaming обычно асимметричен, а RTC — двунаправлен и чувствителен к jitter.

### 6.3. WebSocket frame morphing

HTTP `Transfer-Encoding: chunked` после WebSocket Upgrade не применяется. Корректная реализация может только:

- разбивать binary messages на стандартные fragmented WebSocket frames;
- случайно выбирать размер фрагментов из bounded distribution;
- сохранять порядок и FIN semantics;
- ограничивать frame overhead и не задерживать realtime payload.

Название функции в ALX: `wsFrameMorph`, а не `Chunked Transfer`.

WebSocket — compatibility transport. В текущей архитектуре Xray REALITY не комбинируется с WebSocket, поэтому WS профиль использует обычный TLS, а основной REALITY путь — RAW/XHTTP/gRPC.

## 7. Transports и совместимость

| Transport | REALITY | Turbo suitability | Stealth | Примечание |
|---|---|---|---|---|
| RAW TCP | да | высокая для TCP; плохая для lossy UDP-over-TCP | TLS/REALITY persona + padding | минимальный overhead, Vision/splice |
| XHTTP | да | средняя/высокая при корректном режиме | лучший web-like вариант | HTTP/1.1, 2 или 3; сложнее buffering |
| gRPC | да | средняя | настоящий HTTP/2/gRPC | собственный multiplexing; не смешивать с лишним Mux |
| WebSocket | TLS, не REALITY | средняя/низкая | HTTP/1.1 Upgrade | заметный ALPN; legacy compatibility |
| старый HTTP/2 | не отдельная цель | — | — | использовать XHTTP или gRPC |
| QUIC DATAGRAM, future | TLS 1.3 | высокая для gaming UDP | RTC-like возможен естественно | рекомендуемое расширение v1.1 |

### 7.1. WARP

WARP не является ALX transport. Это внешний маршрут:

```text
ALX client
  → Xray dialerProxy / OS routing
  → WARP WireGuard или MASQUE tunnel
  → Cloudflare egress
  → ALX server REALITY endpoint
```

В Xray интеграция возможна двумя способами:

1. ALX outbound использует `streamSettings.sockopt.dialerProxy: "warp"`, где `warp` — WireGuard outbound.
2. Системный WARP client маршрутизирует адрес ALX server через виртуальный интерфейс.

Первый способ проще контролировать из Xray. Второй может лучше интегрироваться с официальным WARP client, но требует системных split-tunnel rules.

WARP скрывает исходный IP от конечного ALX endpoint только в пределах фактической маршрутизации через Cloudflare egress. Он добавляет tunnel overhead и может увеличить ping; Game Mode должен сравнивать direct и WARP path, а не всегда принудительно выбирать WARP.

### 7.2. Multipath

#### System MPTCP

- применяется к RAW TCP;
- Linux kernel 5.6+;
- в Xray уже существует `sockopt.tcpMptcp`;
- kernel выбирает subflows/scheduler;
- при отсутствии поддержки происходит fallback на обычный TCP;
- WARP virtual interface может скрыть от MPTCP реальные Wi‑Fi/LTE paths.

#### ALX bonded lanes

Transport-agnostic вариант:

- несколько независимых secure connections имеют общий `session_id`;
- server подтверждает каждую lane path token;
- scheduler выбирает lowest-RTT path для P0;
- один UDP flow закрепляется за path, чтобы уменьшить reorder;
- при path failure новые datagrams немедленно переходят на другую lane;
- reliable stream имеет connection-level sequence/reassembly и потому может получить HOL;
- duplication разрешена только для небольших P1 control frames в пределах бюджета.

## 8. Пример клиентской конфигурации

Полный файл: [`examples/client.json`](examples/client.json).

Ключевая схема:

```json
{
  "protocol": "aetherlinkx",
  "settings": {
    "address": "alx.example.com",
    "port": 443,
    "id": "018f3f89-01be-7b44-8a7f-23e54f92ea00",
    "secret": "REPLACE_WITH_32_BYTE_BASE64URL_SECRET",
    "securityProfile": "transport-bound",
    "pqMode": "prefer",
    "flow": "aether-vision",
    "turbo": {
      "enabled": true,
      "profile": "game",
      "targetQueueMs": 4,
      "maxQueueMs": 12,
      "maxDatagramAgeMs": 35,
      "laneCount": 2,
      "maxBatchDelayUs": 0,
      "congestion": "auto"
    },
    "stealth": {
      "mode": "adaptive",
      "persona": "generic-web",
      "rotateOnNewSessionMinutes": 60,
      "maxAddedLatencyMs": 1,
      "paddingBudgetPercent": 2
    }
  },
  "streamSettings": {
    "method": "raw",
    "security": "reality",
    "realitySettings": {
      "serverName": "www.example.com",
      "fingerprint": "chrome",
      "password": "REPLACE_WITH_REALITY_CLIENT_KEY",
      "shortId": "0123456789abcdef",
      "spiderX": "/"
    },
    "sockopt": {
      "dialerProxy": "warp",
      "tcpKeepAliveIdle": 30,
      "tcpKeepAliveInterval": 10
    }
  }
}
```

Если WARP отключён, для Linux RAW TCP можно экспериментально использовать:

```json
{
  "tcpMptcp": true,
  "tcpcongestion": "bbr"
}
```

Одновременное включение WARP chaining и MPTCP не гарантирует два физических path и по умолчанию не рекомендуется.

## 9. Пример серверной конфигурации

Полный файл: [`examples/server.json`](examples/server.json).

Ключевая схема:

```json
{
  "listen": "0.0.0.0",
  "port": 443,
  "protocol": "aetherlinkx",
  "settings": {
    "users": [
      {
        "id": "018f3f89-01be-7b44-8a7f-23e54f92ea00",
        "secret": "REPLACE_WITH_SAME_32_BYTE_BASE64URL_SECRET",
        "email": "player@example.com",
        "level": 0,
        "policy": "turbo"
      }
    ],
    "securityProfile": "transport-bound",
    "handshakeTimeoutSeconds": 4,
    "maxClientInitBytes": 4096,
    "turbo": {
      "enabled": true,
      "targetQueueMs": 4,
      "maxQueueMs": 12,
      "maxRealtimeStreamsPerUser": 32
    }
  },
  "streamSettings": {
    "method": "raw",
    "security": "reality",
    "realitySettings": {
      "target": "www.example.com:443",
      "serverNames": [
        "www.example.com"
      ],
      "privateKey": "REPLACE_WITH_REALITY_PRIVATE_KEY",
      "shortIds": [
        "0123456789abcdef"
      ]
    }
  }
}
```

## 10. Предложения и замечания

### 10.1. Добавить datagram transport в следующую версию

Главное техническое противоречие исходных требований: gaming UDP и «агрессивный Mux» поверх WS/gRPC/HTTP/2/RAW TCP. Все они обычно опираются на надёжный ordered stream и наследуют head-of-line blocking.

Для настоящего Game Mode наиболее полезное расширение — QUIC DATAGRAM по RFC 9221 или интеграция с существующим Hysteria transport. DATAGRAM frames не retransmit и сохраняют congestion control, что лучше соответствует deadline-sensitive игровым пакетам.

### 10.2. Не делать полностью случайные fingerprints

Нужен каталог проверенных personas, привязанных к версии TLS/HTTP stack. Смена — только на новой сессии. Полностью случайные extensions, ALPN и frame sizes делают клиента уникальным.

### 10.3. Не включать payload compression

Сжимать стоит только собственные headers. Payload игр/media/TLS уже сжат, а дополнительное сжатие тратит CPU, увеличивает jitter и создаёт side channels.

### 10.4. Разделить Turbo и Stealth budgets

У режимов конфликтующие цели. Turbo не должен ждать ради shaping. Предлагаемый приоритет:

```text
deadline/security > congestion safety > latency > stealth shaping > throughput
```

### 10.5. Не изобретать новый cryptographic handshake в v1

REALITY/TLS 1.3 с `X25519MLKEM768` уже даёт современную hybrid key exchange. ALX v1 должен добавить user binder и channel binding, но не создавать собственную непроверенную KEM/AKE конструкцию.

### 10.6. Сделать результаты проверяемыми

До production должны существовать benchmarks:

- RTT idle и под upload/download saturation;
- p50/p95/p99 jitter;
- datagram late-drop rate;
- recovery Wi‑Fi → LTE;
- direct vs WARP;
- Cubic vs BBR;
- RAW vs XHTTP vs gRPC;
- Stealth overhead по bytes, CPU и added latency.

### 10.7. Security review

Обязательны:

- отдельная нормативная wire specification;
- golden vectors;
- fuzzing всех parsers;
- replay/nonce tests;
- bounds на allocations и queues;
- проверка downgrade `pqMode`;
- независимый cryptographic review;
- анализ active probing и отличимости reject/fallback paths.

## 11. План интеграции в Xray-core

После утверждения draft предполагается новая папка:

```text
Xray-core/proxy/aetherlinkx/
  account.proto
  account.go
  validator.go
  encoding/
    handshake.go
    frame.go
    datagram.go
  turbo/
    scheduler.go
    aqm.go
    metrics.go
  multipath/
    session.go
    path.go
  inbound/
    config.proto
    inbound.go
  outbound/
    config.proto
    outbound.go
```

Также потребуются:

- `infra/conf/aetherlinkx.go`;
- записи `aetherlinkx` в `infra/conf/xray.go`;
- imports в `main/distro/all/all.go`;
- optional CLI API user extraction;
- unit, fuzz и scenario tests;
- отдельная документация inbound/outbound/wire format.

Кодирование начинается только после согласования threat model, transport matrix и обязательности datagram lane.

## 12. Источники

- [Xray proxy interfaces](https://github.com/XTLS/Xray-core/blob/main/proxy/proxy.go)
- [Xray VLESS implementation](https://github.com/XTLS/Xray-core/tree/main/proxy/vless)
- [Xray REALITY documentation](https://github.com/XTLS/Xray-docs-next/blob/main/docs/en/config/transports/reality.md)
- [XTLS REALITY: X25519MLKEM768 implementation](https://github.com/XTLS/REALITY/blob/main/common.go)
- [RFC 9221: QUIC DATAGRAM](https://www.rfc-editor.org/rfc/rfc9221.html)
- [RFC 8684: Multipath TCP](https://www.rfc-editor.org/rfc/rfc8684.html)
- [Linux kernel MPTCP documentation](https://docs.kernel.org/networking/mptcp.html)
- [Cloudflare WARP client architecture](https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/configure/route-traffic/client-architecture/)
