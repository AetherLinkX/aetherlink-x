# Исследование архитектуры протоколов Xray-core

Дата исследования: 2026-08-28  
Локальная документация: `Xray-docs-next-main`  
Исходники для сверки: официальный `XTLS/Xray-core`, ветка `main`, архив загружен в `Xray-core-source/Xray-core-main`  
Статус: архитектурный анализ завершён; после него создана экспериментальная реализация AetherLink X wire 1.1 в `Xray-core-source/Xray-core-main/proxy/aetherlinkx`. Этот отчёт сохраняет результаты этапа исследования, а актуальная спецификация реализации находится в `proxy/aetherlinkx/README.md`.

## 1. Краткий итог

Xray разделяет обработку трафика на три уровня:

1. **Application layer** — dispatcher, routing, DNS, policy, stats, inbound/outbound managers.
2. **Proxy layer** — конкретные протоколы: VLESS, VMess, Trojan, Shadowsocks и другие.
3. **Transport layer** — RAW/TCP, WebSocket, gRPC, XHTTP, mKCP, Hysteria; отдельно накладываются TLS/REALITY и FinalMask/Sockopt.

Протокол прокси в нормальном случае не знает, передаётся ли его байтовый поток через RAW, WebSocket, gRPC или XHTTP. Transport создаёт объект, совместимый с `net.Conn`/`stat.Connection`, а затем inbound/outbound протокола читает и пишет в него свой handshake и payload. Это основная точка расширения: новый прокси-протокол следует делать в `proxy/<name>`, не в `transport/internet/<name>`, если он не вводит принципиально новый способ доставки байтов.

Минимальная реализация нового двустороннего протокола требует:

- wire format запроса, ответа и тела;
- inbound, реализующий `proxy.Inbound`;
- outbound, реализующий `proxy.Outbound`;
- protobuf-конфигурации и преобразования JSON → protobuf;
- регистрации конструкторов через `common.RegisterConfig`;
- статического импорта пакетов в сборку `main/distro/all`;
- тестов кодека, ошибок/границ и сквозных сценариев.

Если протокол имеет пользователей, дополнительно нужны тип account, преобразование в memory account, validator и, желательно, `proxy.UserManager` для динамического API управления пользователями.

**Отдельного стабильного plugin API/ABI для загрузки протокола во время выполнения нет.** Реестры Xray работают через Go `init()`, следовательно пакет должен быть импортирован в бинарник и ядро необходимо пересобрать.

Для будущего протокола наиболее подходящая база для изучения — **VLESS**, потому что его код лучше всего демонстрирует разделение простого протокольного заголовка, аутентификации пользователя, transport security, optional encryption, XTLS Vision, UDP framing и Mux/XUDP. Криптографию VLESS Encryption копировать механически не следует: она сложна, нова и требует отдельного threat model и криптографического review.

## 2. Источники и важные расхождения

Основные локальные документы:

- `Xray-docs-next-main/docs/development/intro/design.md` — трёхслойная архитектура.
- `Xray-docs-next-main/docs/development/intro/guide.md` — структура репозитория, генерация protobuf, тестирование.
- `Xray-docs-next-main/docs/development/protocols/vless.md` — историческое описание VLESS wire format.
- `Xray-docs-next-main/docs/development/protocols/vmess.md` — в основном исторический VMess wire format.
- `Xray-docs-next-main/docs/config/inbounds/*.md` и `outbounds/*.md` — пользовательские конфигурации.
- `Xray-docs-next-main/docs/config/transport.md` и `transports/*.md` — transport/security/masking.

Официальные исходники использованы как источник истины:

- [proxy/proxy.go](https://github.com/XTLS/Xray-core/blob/main/proxy/proxy.go)
- [proxy/vless](https://github.com/XTLS/Xray-core/tree/main/proxy/vless)
- [proxy/vmess](https://github.com/XTLS/Xray-core/tree/main/proxy/vmess)
- [proxy/trojan](https://github.com/XTLS/Xray-core/tree/main/proxy/trojan)
- [proxy/shadowsocks](https://github.com/XTLS/Xray-core/tree/main/proxy/shadowsocks)
- [proxy/shadowsocks_2022](https://github.com/XTLS/Xray-core/tree/main/proxy/shadowsocks_2022)
- [app/proxyman](https://github.com/XTLS/Xray-core/tree/main/app/proxyman)
- [transport/internet](https://github.com/XTLS/Xray-core/tree/main/transport/internet)
- [infra/conf](https://github.com/XTLS/Xray-core/tree/main/infra/conf)

Обнаружены два существенных расхождения документации и кода:

1. Исторический документ VLESS рассуждает о переходе к версии `1`, но актуальный код задаёт `encoding.Version = 0`, а decoder принимает только `case 0`. Для совместимой реализации ориентироваться нужно на код и тесты, не на исторический комментарий.
2. Документ VMess подробно описывает старый HMAC-MD5/AES-CFB request header. Актуальный Xray использует **VMess AEAD**: AuthID с timestamp и replay filter, AEAD-шифрование длины и payload заголовка, а также AEAD response header. Историческая схема полезна для понимания полей, но не является достаточной спецификацией текущего wire format.

## 3. Архитектура и путь данных

### 3.1. Основные слои

```text
локальный клиент / удалённый peer
             │
             ▼
Transport listener/dialer
RAW | WebSocket | gRPC | XHTTP | mKCP | Hysteria
             │
      TLS / REALITY (optional)
             │
       FinalMask / Sockopt
             │
             ▼
Proxy inbound/outbound
VLESS | VMess | Trojan | Shadowsocks | новый протокол
             │
             ▼
Dispatcher → Router → выбранный outbound
```

`streamSettings` не является частью `settings` конкретного прокси-протокола. Это отдельная конфигурация proxyman/transport, поэтому один и тот же VLESS/VMess/Trojan byte stream можно поместить в разные transports без изменения кодека самого протокола.

### 3.2. Загрузка конфигурации

Путь конфигурации выглядит так:

```text
JSON/YAML/TOML
  → infra/conf: выбор структуры по полю protocol
  → Build(): JSON-структура преобразуется в protobuf Config
  → serial.TypedMessage в core.InboundHandlerConfig/OutboundHandlerConfig
  → common.CreateObject(...)
  → зарегистрированный через common.RegisterConfig конструктор
  → proxy.Inbound или proxy.Outbound
```

Списки допустимых строк `protocol` захардкожены в `infra/conf/xray.go` в `inboundConfigLoader` и `outboundConfigLoader`. Одной реализации интерфейсов недостаточно: имя нового протокола нужно добавить в эти maps.

`common.RegisterConfig` сопоставляет Go-тип protobuf config с функцией-конструктором. Регистрация обычно выполняется в `init()` пакета inbound/outbound. Чтобы `init()` вообще выполнился, пакет импортируется через blank import в `main/distro/all/all.go`.

### 3.3. Inbound lifecycle

1. `app/proxyman/inbound` строит `MemoryStreamConfig` из `streamSettings`.
2. Proxyman создаёт protocol handler через `common.CreateObject`.
3. По результату `Inbound.Network()` запускается TCP/UDP/Unix worker.
4. `internet.ListenTCP` выбирает listener из transport registry по `MemoryStreamConfig.ProtocolName`.
5. Transport listener принимает соединение, выполняет нужный HTTP/WebSocket/gRPC/TLS/REALITY handshake и возвращает Xray поток как `stat.Connection`.
6. Worker вызывает `Inbound.Process(ctx, network, conn, dispatcher)`.
7. Protocol inbound применяет handshake timeout, читает и валидирует request header, связывает пользователя с session context и создаёт `transport.Link` из декодирующих reader/writer.
8. `dispatcher.DispatchLink` передаёт link в routing и выбранный outbound.

### 3.4. Outbound lifecycle

1. Dispatcher передаёт в outbound `transport.Link`, содержащий внутренний трафик приложения.
2. Proxyman вызывает `Outbound.Process(ctx, link, dialer)`.
3. Protocol outbound выбирает сервер и вызывает переданный `internet.Dialer`.
4. `internet.Dial` выбирает transport dialer по `streamSettings.method`/внутреннему `ProtocolName`.
5. Transport dialer устанавливает RAW/WebSocket/gRPC/XHTTP соединение и, если настроено, TLS/REALITY.
6. Protocol outbound отправляет свой request header, оборачивает body в framing/encryption writer и копирует данные из `link.Reader`.
7. В обратном направлении он читает response header, создаёт decoder reader и пишет в `link.Writer`.

## 4. Интерфейсы, которые нужно реализовать

### 4.1. Обязательные proxy interfaces

Из `proxy/proxy.go`:

```go
type Inbound interface {
    Network() []net.Network
    Process(context.Context, net.Network, stat.Connection, routing.Dispatcher) error
}

type Outbound interface {
    Process(context.Context, *transport.Link, internet.Dialer) error
}
```

Это единственные обязательные публичные proxy interfaces. Специального единого `Protocol` interface нет.

`transport.Link` — простой объект с двумя половинами потока:

```go
type Link struct {
    Reader buf.Reader
    Writer buf.Writer
}
```

### 4.2. Account interfaces

Если есть пользователи/аутентификация:

```go
type Account interface {
    Equals(Account) bool
    ToProto() proto.Message
}

type AsAccount interface {
    AsAccount() (Account, error)
}
```

Обычный паттерн:

- protobuf `Account` хранит сериализуемые поля;
- `Account.AsAccount()` валидирует/парсит их и возвращает `MemoryAccount`;
- `MemoryAccount` содержит уже разобранные ключи, UUID, cipher state или иные значения;
- validator хранит `*protocol.MemoryUser`, индексируя по безопасному идентификатору.

### 4.3. Опциональный UserManager

Для gRPC API добавления/удаления пользователей handler реализует:

```go
type UserManager interface {
    AddUser(context.Context, *protocol.MemoryUser) error
    RemoveUser(context.Context, string) error
    GetUser(context.Context, string) *protocol.MemoryUser
    GetUsers(context.Context) []*protocol.MemoryUser
    GetUsersCount(context.Context) int64
}
```

Это не требуется для протокола с одной общей конфигурацией или без пользователей, но рекомендуется для server inbound с пользовательскими аккаунтами. Email в существующих реализациях служит управляющим ключом API/статистики и должен быть уникальным либо пустым согласно выбранной политике.

Для поддержки CLI `xray api adu` одного `UserManager` недостаточно: helper `main/commands/all/api/inbound_user_add.go` содержит type switch по конкретным inbound config и его также потребуется расширить.

### 4.4. Registry interfaces transport layer

Они нужны только при создании **нового транспорта**, а не обычного proxy protocol:

- `internet.RegisterProtocolConfigCreator(name, creator)`;
- `internet.RegisterTransportDialer(name, Dial)`;
- `internet.RegisterTransportListener(name, Listen)`.

Если новый протокол должен работать поверх существующих RAW/WebSocket/gRPC/XHTTP, регистрировать transport dialer/listener не требуется.

## 5. Общие паттерны существующих протоколов

### 5.1. Разделение control plane и data plane

В каждом протоколе есть две логические части:

- **control plane**: версия, credential/authentication, команда, адрес, порт, параметры потока;
- **data plane**: byte stream TCP либо framed datagrams UDP, иногда с chunk AEAD.

Сервер сначала ограниченно читает заголовок под handshake deadline, валидирует пользователя и только затем выделяет долгоживущие структуры/открывает target connection.

### 5.2. Общий объект назначения

Все протоколы в итоге формируют `protocol.RequestHeader` с:

- `Version`;
- `User`;
- `Command` (`TCP`, `UDP`, `Mux`, иногда protocol-specific reverse);
- `Address`;
- `Port`.

Адреса кодируются общим `protocol.AddressParser`, но конкретные байтовые значения address type и порядок port/address задаются протоколом.

### 5.3. Reader/writer wrappers

Кодек не обязан вручную копировать каждый пакет. Он возвращает обёртки:

- header encoder/decoder;
- chunk/framing `buf.Reader` и `buf.Writer`;
- AEAD reader/writer;
- UDP packet reader/writer;
- Vision reader/writer.

После этого handler использует `buf.Copy`, `task.Run` или `dispatcher.DispatchLink` для двух направлений.

### 5.4. Deadlines, policy, stats, session metadata

Типовой inbound/outbound:

- получает policy по user level;
- ставит handshake timeout;
- после handshake очищает read deadline;
- создаёт inactivity timer;
- записывает `session.Inbound.User`, protocol name и destination;
- использует access log и stats;
- запускает uplink/downlink параллельно и корректно закрывает writer при EOF.

### 5.5. TCP, UDP, Mux/XUDP

TCP обычно становится непрерывным body stream. UDP требует сохранения границ datagram:

- VLESS: 2-byte big-endian length перед каждым UDP payload;
- Trojan: destination + 2-byte length + CRLF + payload;
- Shadowsocks: отдельный encrypted UDP packet с destination внутри;
- Mux/XUDP: UDP может быть упакован в multiplexed TCP-like stream, минуя native UDP path.

Новый протокол должен заранее определить:

- поддерживает ли он TCP;
- поддерживает ли native UDP;
- поддерживает ли UDP-over-stream;
- как кодируется destination каждого datagram;
- каковы максимальный размер, поведение при fragmentation и пустом datagram;
- совместим ли он с Mux/XUDP.

## 6. Сравнение VLESS, VMess, Trojan и Shadowsocks

| Протокол | Аутентификация/handshake | Защита payload на protocol layer | UDP | Зависимость от transport security | Особенности/риски |
|---|---|---|---|---|---|
| VLESS classic | Версия + 16-byte UUID + addons + command/destination | Нет по умолчанию | Length-prefixed UDP или XUDP | Для public network требуется TLS/REALITY либо VLESS Encryption | Очень простой wire format; static UUID нельзя посылать открыто |
| VLESS Encryption | Внешний key exchange до внутреннего VLESS; затем UUID внутри защищённого канала | AEAD records | Через внутренний VLESS/XUDP | Может работать без TLS, но не получает нормальный HTTPS fingerprint автоматически | Сложный новый handshake; hybrid ML-KEM-768 + X25519, tickets/0-RTT, padding |
| VMess AEAD | Time-based AuthID, user lookup, replay filter, AEAD header | AES-GCM или ChaCha20-Poly1305 chunks | Chunked stream/packet semantics | Payload защищён, но docs предупреждают об отсутствии TLS 1.3-style forward secrecy и нормального HTTPS внешнего вида | Требует синхронизации времени; текущий код отличается от legacy документа |
| Trojan | `hex(SHA-224(password))` (56 ASCII bytes), CRLF, command, destination | Нет | Destination + length + CRLF + payload | По смыслу обязательно TLS/REALITY; код запрещает public outbound без TLS | TLS несёт конфиденциальность и server auth; fallback скрывает invalid probes, но не заменяет криптографию |
| Shadowsocks legacy AEAD | Password-derived key + salt/IV; server пробует user keys | AEAD stream/chunks и AEAD UDP packet | Native encrypted UDP packet | Payload защищён, но внешний вид классифицируем; нет TLS 1.3-style PFS | Нужны salt/IV uniqueness, replay filter и безопасное multi-user распознавание |
| Shadowsocks 2022 | PSK, BLAKE3-based 2022 design, отдельная реализация | AEAD | Native UDP с replay protection | Всё ещё не имитирует HTTPS | Улучшенная replay protection; Xray использует библиотеку `sing-shadowsocks/shadowaead_2022` |

### 6.1. VLESS classic

Фактический request format версии 0:

| Размер | Поле |
|---:|---|
| 1 | version (`0`) |
| 16 | user ID/UUID |
| 1 | длина protobuf addons `M` |
| M | serialized `Addons` |
| 1 | command |
| variable | port + address type + address для TCP/UDP |
| rest | request payload |

Response:

| Размер | Поле |
|---:|---|
| 1 | та же version |
| 1 | длина response addons `N` |
| N | serialized addons |
| rest | response payload |

Текущий `Addons` содержит `Flow` и `Seed`. На практике encoder сериализует addons для `xtls-rprx-vision`; при отсутствии addons записывается нулевая длина.

Важная деталь актуального validator: перед lookup `ProcessUUID` обнуляет байты ID с индексами 6 и 7. Исходные два байта сохраняются в `session.Inbound.VlessRoute` как route/seed. Следовательно, текущая идентичность VLESS в lookup фактически не зависит от этих 16 бит. Если новый протокол заимствует seed/route в credential, это должно быть явно включено в threat model и спецификацию, а не возникать как скрытая нормализация.

### 6.2. VMess AEAD

Текущий VMess client создаёт случайные request body key/IV и response-auth byte. Request header защищается VMess AEAD:

- AuthID содержит timestamp и случайные данные, шифруется ключом пользователя;
- server принимает timestamp в окне примерно ±120 секунд;
- AuthID проходит replay filter;
- длина header и header payload защищаются AES-GCM ключами/nonce, полученными через VMess KDF;
- body разбивается на chunks и защищается AES-GCM или ChaCha20-Poly1305;
- response header также AEAD-защищён ключами, производными от request body key/IV.

Следствия для нового дизайна:

- timestamp облегчает stateless user matching, но создаёт операционную зависимость от синхронизации времени;
- anti-replay обязателен отдельно от AEAD;
- нельзя повторно использовать nonce с тем же key;
- длины chunks тоже могут быть side channel и, если влияют на allocation/parsing, должны быть аутентифицированы до доверия им.

### 6.3. Trojan

Request header:

```text
56-byte lowercase hex SHA-224(password)
CRLF
1-byte command
address type + address + port
CRLF
payload
```

Trojan не шифрует payload сам. Его security model опирается на внешний TLS/REALITY. Invalid credential может быть направлен в fallback service, чтобы ответ выглядел как обычный сервис. Это защитная обработка probing/failure path, а не самостоятельная аутентификация peer или шифрование.

UDP frame:

```text
destination | uint16 payload_length | CRLF | payload
```

### 6.4. Shadowsocks

Legacy AEAD TCP:

- клиент создаёт уникальный IV/salt;
- из password-derived key и IV/salt создаётся cipher state;
- destination идёт первым внутри encrypted stream;
- body идёт через authentication reader/writer с chunk lengths;
- inbound validator пытается определить пользователя и проверяет уникальность IV/salt;
- invalid input проходит controlled drain, уменьшающий полезность active probing.

UDP — самостоятельный encrypted packet, внутри которого находятся destination и payload. Это принципиально отличается от VLESS/Trojan UDP-over-stream.

SS2022 в текущем Xray находится в отдельном `proxy/shadowsocks_2022` и выбирается JSON builder по имени cipher. Поэтому пользовательское имя `shadowsocks` может построить protobuf config другого Go-пакета.

## 7. Подробный пример: реализация VLESS в Xray

### 7.1. Структура файлов

```text
proxy/vless/
  account.proto, account.pb.go, account.go
  validator.go
  vless.go
  encoding/
    addons.proto, addons.pb.go, addons.go
    encoding.go, encoding_test.go
  encryption/
    client.go, server.go, common.go, xor.go
  inbound/
    config.proto, config.pb.go, config.go, inbound.go
  outbound/
    config.proto, config.pb.go, config.go, outbound.go
```

`config.go` в inbound/outbound сейчас фактически пуст, потому что protobuf типы генерируются в `config.pb.go`, а регистрация и конструктор находятся в handler-файле.

### 7.2. Account и validator

`account.proto` определяет сериализуемые поля пользователя: UUID, flow, encryption parameters и reverse. `Account.AsAccount()` парсит UUID и создаёт `MemoryAccount`. `MemoryValidator` использует concurrent maps для lookup по нормализованному UUID и по lowercase email.

Это хороший паттерн для нового протокола: дорогое декодирование ключей и строгая validation выполняются один раз при загрузке/добавлении пользователя, а per-connection lookup работает по готовому бинарному ключу.

### 7.3. Outbound

`proxy/vless/outbound/outbound.go`:

1. Регистрирует конструктор `*outbound.Config`.
2. Преобразует protobuf server endpoint в `protocol.ServerSpec`.
3. При включённой VLESS Encryption заранее парсит public-key configuration.
4. В `Process` получает destination из session context.
5. Вызывает transport-aware `dialer.Dial` до VLESS server endpoint.
6. При необходимости выполняет внешний encryption handshake.
7. Строит `protocol.RequestHeader` и addons.
8. `EncodeRequestHeader` пишет VLESS header.
9. `EncodeBodyAddons` выбирает raw writer, UDP length writer или Vision writer.
10. Uplink и downlink копируются параллельно с policy timer.

### 7.4. Inbound

`proxy/vless/inbound/inbound.go`:

1. Регистрирует конструктор `*inbound.Config`.
2. Строит validator из protobuf users.
3. В `Process` при необходимости выполняет VLESS Encryption handshake.
4. Устанавливает handshake deadline и читает ограниченный first buffer.
5. `DecodeRequestHeader` проверяет version, lookup UUID, addons, command и destination.
6. При invalid request может выполнить настроенный fallback; иначе логирует reject без открытия target.
7. Проверяет, разрешён ли account flow и совместим ли он с command/transport.
8. Создаёт body reader/writer и response header.
9. Передаёт `transport.Link` в `dispatcher.DispatchLink`.

### 7.5. VLESS Encryption как отдельная внешняя обёртка

В актуальном коде VLESS Encryption выполняется **до** парсинга классического VLESS header и возвращает `CommonConn`. Внутренний формат VLESS при этом сохраняется.

Наблюдаемые свойства текущей реализации:

- non-forward-secret server authentication строится на заранее настроенных X25519 public keys или ML-KEM-768 encapsulation keys;
- полный 1-RTT exchange дополнительно комбинирует ML-KEM-768 и ephemeral X25519 для PFS;
- session ticket позволяет 0-RTT/resumption;
- server хранит ticket sessions и per-session использованные NFS keys для replay detection;
- record payload защищается AES-GCM при наличии аппаратного AES либо ChaCha20-Poly1305;
- BLAKE3 `DeriveKey` используется как KDF/context separation;
- records имеют 5-byte TLS-application-data-like header, а handshake использует randomized padding и delays;
- `xorMode` меняет внешний вид отдельных полей/record headers, но XOR не должен рассматриваться как самостоятельная криптографическая защита — целостность и конфиденциальность даёт AEAD.

Security caveat: наличие TLS-похожего record header не означает полного TLS fingerprint. Документация прямо отделяет payload protection от нормального HTTPS camouflage. Для устойчивого внешнего вида по-прежнему важны REALITY/TLS/XHTTP либо специально спроектированная masking layer.

## 8. Как протокол взаимодействует с transports

### 8.1. RAW/TCP

Transport dialer создаёт системный TCP connection, затем оборачивает его TLS или REALITY, если настроено. Protocol outbound получает уже готовый connection и пишет в него protocol handshake. На inbound transport listener выполняет обратную последовательность и передаёт расшифрованный stream в `Inbound.Process`.

RAW имеет минимум собственного framing и лучше всего подходит для protocol features, которым нужен доступ к реальному TCP connection или zero-copy/splice. XTLS Vision может перейти к direct copy/splice только при совместимых raw/security wrappers.

### 8.2. WebSocket

WebSocket transport выполняет HTTP/1.1 Upgrade и даёт protocol layer stream-like `net.Conn`. VLESS/VMess/Trojan не меняют wire format. Early Data может перенести начало protocol header в `Sec-WebSocket-Protocol`, но это реализует WebSocket transport, а не protocol encoder.

WebSocket сам по себе не шифрует и имеет узнаваемые признаки, включая HTTP/1.1 ALPN при TLS. Локальная документация рекомендует XHTTP вместо WebSocket для менее заметного внешнего поведения.

### 8.3. gRPC/HTTP/2

gRPC transport упаковывает byte stream в bidirectional gRPC stream, регистрирует service/path и возвращает connection wrapper. Protocol layer видит обычный поток. gRPC уже имеет собственное multiplexing, поэтому дополнительный Mux.Cool часто не нужен и может ухудшить характеристики.

Service endpoint может быть подвержен active probing, если не защищён front proxy/path routing. gRPC framing не является криптографией; TLS/REALITY настраивается отдельно.

Старый отдельный HTTP/2 transport в текущей документации фактически заменён рекомендацией XHTTP.

### 8.4. XHTTP

XHTTP может разделять upload/download, использовать HTTP/1.1, HTTP/2 или HTTP/3 и собирать их обратно в connection abstraction. Для protocol layer это всё ещё ordered byte stream, но transport может иметь более сложные latency, buffering и half-close semantics.

Поэтому новый протокол не должен предполагать, что один `Write` соответствует одному network packet или одному peer `Read`. Любое поле нужно читать через `io.ReadFull`/buffered parser, а framing должен переживать произвольное дробление и склеивание записей.

### 8.5. TLS и REALITY

TLS/REALITY находятся снаружи proxy protocol:

```text
IP/TCP → optional FinalMask wrapper → TLS/REALITY → WS/gRPC/XHTTP specifics → proxy bytes
```

Фактический порядок wrappers зависит от transport, но принцип один: protocol не должен дублировать transport security без явного threat model. Если protocol encryption существует, нужно определить, зачем нужны обе защиты:

- TLS/REALITY: server authentication, PFS, стандартный/правдоподобный внешний handshake;
- protocol encryption: защита end-to-end независимо от transport, возможность trusted/private transports, protocol-specific resumption;
- masking: изменение наблюдаемого размера/тайминга/сигнатуры, но не замена AEAD.

### 8.6. FinalMask

FinalMask применяется после transport security и меняет самый внешний TCP/UDP вид: custom header, fragmentation, Sudoku/noise/другие masks и QUIC parameters. Это отдельный concern. Новому proxy protocol не нужно реализовывать такие механизмы внутри, если их можно корректно оставить FinalMask.

## 9. Обязательные и опциональные компоненты нового протокола

### Обязательные

1. **Спецификация wire format**
   - versioning;
   - request/response handshake;
   - command and destination encoding;
   - TCP body semantics;
   - UDP framing, если поддерживается;
   - limits и invalid-input behavior.

2. **Inbound handler**
   - `Network()`;
   - `Process(...)`;
   - handshake deadline;
   - authentication/validation;
   - session metadata;
   - dispatch link.

3. **Outbound handler**
   - `Process(...)`;
   - server selection;
   - `dialer.Dial`;
   - request encoding/response decoding;
   - bidirectional copy and lifecycle.

4. **Config/protobuf layer**
   - protobuf account/config messages;
   - generated `.pb.go`;
   - JSON config structs и `Build()`;
   - registry entries.

5. **Tests**
   - golden wire vectors;
   - split-read/split-write;
   - malformed/truncated/oversized inputs;
   - authentication failure and replay;
   - TCP and UDP end-to-end;
   - transports at least RAW+TLS и один framed transport (например XHTTP или gRPC).

### Опциональные

- multi-user validator;
- `proxy.UserManager`;
- fallback;
- native UDP listener;
- Mux/XUDP;
- protocol encryption;
- resumption/0-RTT;
- padding/traffic shaping;
- XTLS Vision integration;
- reverse proxy support;
- CLI helpers/share links.

## 10. Файлы для добавления нового протокола

Ниже — рекомендуемая структура для условного имени `myproto`. Точный набор зависит от требований этапа 2.

### 10.1. Создать в Xray-core

| Файл | Назначение |
|---|---|
| `proxy/myproto/myproto.go` | package docs, constants/version |
| `proxy/myproto/account.proto` | сериализуемая учётная запись |
| `proxy/myproto/account.pb.go` | генерируется, вручную не править |
| `proxy/myproto/account.go` | `AsAccount`, `MemoryAccount`, equality/serialization |
| `proxy/myproto/validator.go` | lookup пользователей, uniqueness, replay-related state |
| `proxy/myproto/protocol.go` или `encoding/encoding.go` | request/response wire codec |
| `proxy/myproto/protocol_test.go` | golden vectors, malformed input, fragmentation |
| `proxy/myproto/inbound/config.proto` | server protobuf config |
| `proxy/myproto/inbound/config.pb.go` | генерируется |
| `proxy/myproto/inbound/inbound.go` | `proxy.Inbound`, registration, dispatch |
| `proxy/myproto/outbound/config.proto` | client protobuf config |
| `proxy/myproto/outbound/config.pb.go` | генерируется |
| `proxy/myproto/outbound/outbound.go` | `proxy.Outbound`, registration, dial/copy |
| `infra/conf/myproto.go` | JSON structs, validation и `Build()` |
| `infra/conf/myproto_test.go` | JSON/protobuf build tests |
| `testing/scenarios/myproto_test.go` | end-to-end TCP/UDP/transport tests |

Если account одинаков для client/server, он остаётся в `proxy/myproto`. Если модели принципиально различаются, допустимы отдельные account types, но это усложнит API.

### 10.2. Изменить в Xray-core

| Файл | Изменение |
|---|---|
| `infra/conf/xray.go` | добавить `myproto` в inbound/outbound config loader maps |
| `main/distro/all/all.go` | blank imports inbound/outbound packages |
| `main/commands/all/api/inbound_user_add.go` | optional: извлечение users для CLI `api adu` |
| `go.mod` / `go.sum` | только если появляется обоснованная новая dependency |

Возможны дополнительные изменения в shared helpers, но их следует избегать до подтверждения, что функциональность действительно общая. Протокол не должен протаскивать свои детали в `proxy/proxy.go` без необходимости.

### 10.3. Документация

Создать:

- `Xray-docs-next-main/docs/config/inbounds/myproto.md`;
- `Xray-docs-next-main/docs/config/outbounds/myproto.md`;
- `Xray-docs-next-main/docs/development/protocols/myproto.md` — нормативный wire format;
- при необходимости зеркала `docs/en/...` и `docs/ru/...`.

Изменить:

- `docs/config/inbounds/index.md`;
- `docs/config/outbounds/index.md`;
- `docs/development/index.md`;
- соответствующие языковые индексы.

### 10.4. Генерация и проверка

После изменения `.proto`:

```text
go generate core/proto.go
```

Форматирование проекта:

```text
go generate core/format.go
```

Основная проверка:

```text
go test ./...
```

Для разработки разумно сначала запускать unit tests нового пакета, затем scenario tests и только потом полный suite.

## 11. Security requirements для этапа проектирования

До написания кода необходимо зафиксировать threat model. Минимальный перечень решений:

### 11.1. Аутентификация

- Что аутентифицируется: только client, только server или оба?
- Credential — случайный token, PSK, password-derived key, public key или certificate?
- Если используется password, обязателен memory-hard password KDF; нельзя считать UUID/password полноценным encryption key без KDF/domain separation.
- Static credential нельзя передавать открыто на public network.
- User lookup не должен давать различимые timing/error responses.

### 11.2. Key exchange и forward secrecy

- Для самостоятельного защищённого протокола предпочтителен известный handshake construction (TLS 1.3, Noise pattern или тщательно выбранный стандарт), а не собственная комбинация primitives.
- Для PFS нужны ephemeral keys; один static PSK/UUID PFS не даёт.
- Key schedule должен иметь domain separation по направлению, назначению и версии.
- Client→server и server→client должны иметь разные keys/nonces.

### 11.3. AEAD и framing

- Использовать AEAD, а не encryption без authentication.
- Nonce никогда не повторяется под одним key, включая reconnect/resumption.
- Аутентифицировать длину, тип, sequence number и важные header fields как plaintext или AAD.
- Проверять длину до allocation: protocol maximum должен быть мал и явен.
- Overflow, zero length, truncated tag, extra bytes и unknown command должны иметь определённое поведение.

### 11.4. Replay и 0-RTT

- AEAD сам по себе не предотвращает replay.
- Time window требует синхронизации часов и replay cache.
- Ticket/0-RTT требует single-use либо bounded replay protection.
- Произвольный proxy request в early data не является идемпотентным; replay может повторно открыть соединение/послать payload. До ясного требования безопаснее начать с 1-RTT и без 0-RTT.

### 11.5. Active probing и failure path

- Handshake timeout и max bytes должны ограничивать slowloris/resource exhaustion.
- Invalid authentication не должна открывать target и не должна давать подробный ответ peer.
- Fallback допустим только при однозначном отделении valid handshake от fallback traffic.
- Разные ошибки не должны создавать стабильный fingerprint по размеру/таймингу.
- Random drain/padding может снизить probing signal, но усложняет DoS accounting.

### 11.6. Traffic analysis и маскировка

- Encryption скрывает содержимое, но не размеры, направления, timing, ALPN, SNI и HTTP/gRPC paths.
- Padding должен иметь конкретную цель и лимиты; бесконтрольный padding создаёт amplification/DoS.
- «Похожий на TLS» header без полного TLS state machine может стать более сильной сигнатурой.
- Для нормального web appearance предпочтительнее использовать TLS/REALITY/XHTTP, чем повторять их внутри proxy protocol.

### 11.7. UDP

- Datagram boundaries должны быть authenticated.
- Нужны anti-replay и sequence/nonce policy для native UDP.
- Определить NAT mapping, destination-per-packet, max payload и fragmentation.
- Не доверять заявленной длине больше установленного лимита.

### 11.8. Versioning и совместимость

- Версия должна аутентифицироваться вместе с handshake.
- Unknown versions должны завершаться безопасно, без downgrade.
- Extensions лучше кодировать bounded TLV/CBOR/protobuf block с явной максимальной длиной.
- Сервер может поддерживать несколько версий через switch, но negotiation не должна позволять attacker downgrade.

### 11.9. Тестирование безопасности

- fuzz parser/decoder;
- race tests validator/replay cache;
- golden vectors между независимыми реализациями;
- replay, nonce reuse, corrupted tag, bit flips, truncation;
- memory/CPU limits на random input;
- differential behavior valid/invalid/fallback;
- тесты через fragmented transports и half-close;
- внешнее криптографическое review до production use.

## 12. Рекомендация по стартовой архитектуре

Пока требования неизвестны, безопасная базовая форма выглядит так:

- отдельный `proxy/<name>`;
- минимальный versioned binary header;
- `Account` + `MemoryAccount` + validator;
- request/response codec, не связанный с transport;
- 1-RTT handshake без resumption на первой версии;
- TLS 1.3/REALITY как обязательная transport security, если нет строгой причины делать собственный key exchange;
- raw TCP payload после authenticated header;
- отдельный bounded framing для UDP;
- XHTTP/RAW/gRPC compatibility через существующий `internet.Dialer`;
- отсутствие XTLS/fallback/Mux в первой версии, если требования их прямо не требуют.

Это не окончательный дизайн протокола, а минимизация количества независимых security mechanisms до появления требований.

## 13. Вопросы, которые нужно решить на этапе 2

1. Какова цель нового протокола по сравнению с VLESS: производительность, censorship resistance, privacy, authentication model, UDP или иное?
2. Он должен работать только Xray↔Xray или нужна независимая спецификация для сторонних клиентов?
3. Нужна ли собственная encryption, или обязательны TLS/REALITY?
4. Нужна ли server authentication без PKI?
5. Нужны ли multi-user, dynamic user API и per-user stats?
6. Требуется ли native UDP, UDP-over-stream, XUDP/Mux?
7. Нужны ли fallback, reverse, XTLS Vision/splice?
8. Какие transports обязательны: RAW, XHTTP, gRPC, WebSocket, Hysteria?
9. Допустима ли синхронизация времени?
10. Нужны ли 0-RTT/resumption и приемлем ли replay risk?
11. Какие наблюдаемые traffic properties требуется скрывать?
12. Какие ограничения по latency, CPU, memory и handshake size?

Ответы определят, достаточно ли VLESS-подобного простого header поверх REALITY/TLS или нужен самостоятельный криптографический handshake.

## 14. Финальный вывод

Xray уже предоставляет почти всю инфраструктуру вокруг нового протокола: listening/dialing, transport abstraction, TLS/REALITY, routing, policy, stats, buffers, bidirectional links, Mux/XUDP и dynamic user API. Реализация нового протокола должна сосредоточиться на небольшом, строго специфицированном proxy layer и по возможности не дублировать transport layer.

Главная инженерная граница:

- **proxy protocol** определяет identity, request metadata, commands и payload framing;
- **transport** определяет доставку потока;
- **transport security** определяет TLS/REALITY protection и peer authentication;
- **masking** определяет внешний наблюдаемый вид.

Смешивание этих задач, как показывает сложность VMess и нового VLESS Encryption, резко увеличивает поверхность ошибок. Поэтому перед кодом нужен формальный protocol specification и security model.
