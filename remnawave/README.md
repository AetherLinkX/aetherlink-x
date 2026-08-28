# AetherLink X и Remnawave

## Что действительно требуется

GitHub сам по себе не добавляет протокол в панель. Remnawave Panel валидирует Xray JSON, отправляет его в Remnawave Node, а Node запускает встроенный `/usr/local/bin/xray`. Поэтому для ALX нужны два согласованных образа: custom Backend и custom Node.

`Dockerfile` собирает Xray-core из официального тега `v26.7.28`, commit `5ca6f4b7d4dc20a881d4330e498892697627ec0c`, применяет ALX patch и заменяет бинарник внутри `remnawave/node:3.3.2`. `Dockerfile.backend` собирает `remnawave/backend:3.3.2` из commit `347e6de129f0289a3831dfbb7452d36b49528f3c`, разрешает ALX как статический inbound, добавляет его в список профиля и включает ALX-aware браузерный WASM-валидатор.

Оба patch проверены командой `git apply --check` на закреплённых commit. GitHub Actions публикует два multi-architecture image для `linux/amd64` и `linux/arm64`:

- `ghcr.io/OWNER/aetherlink-x-remnawave-backend`;
- `ghcr.io/OWNER/aetherlink-x-remnawave-node`.

## Текущий поддерживаемый режим

Поддерживаемый режим интеграции — **статический ALX inbound**:

1. custom Backend принимает и показывает ALX в Config Profiles;
2. custom Remnawave Node запускает ALX-capable Xray;
3. `config-profile-static.json` добавляется как Config Profile;
4. UUID, ALX secret, X-Wing и REALITY keys заменяются значениями из `aetherlinkx-keygen`/`x25519`;
5. клиент получает ручной JSON из `tools/generate-configs.ps1`.

Панель может сохранять профиль, включать inbound на ноде, запускать ядро и учитывать суммарный трафик inbound. Штатное динамическое управление пользователями и генератор подписок пока не понимают ALX; ALX-учётные данные внутри профиля намеренно не очищаются и не изменяются событиями обычных пользователей.

## Почему динамические пользователи пока недоступны

Официальный `@remnawave/xtls-sdk` умеет добавлять только VLESS, Trojan, Shadowsocks, Shadowsocks 2022, SOCKS и HTTP accounts. Backend также очищает/инжектирует пользователей только для известных протоколов. ALX требует UUID и отдельный secret, а стандартная модель пользователя Remnawave такого поля не содержит.

Для полноценного production-подключения нужны согласованные изменения ещё в трёх проектах:

- `remnawave/xtls-sdk`: protobuf account и `addAetherLinkXUser`;
- `remnawave/node`: routing команды add/remove user к новому SDK method;
- `remnawave/backend/panel`: protocol schemas, profile validation, subscription output и UI.

Без этих изменений нельзя честно обещать, что обычные пользователи панели автоматически появятся в ALX inbound или получат рабочую подписку.

## Развёртывание custom images

Сначала на сервере панели:

```sh
export ALX_BACKEND_IMAGE=ghcr.io/OWNER/aetherlink-x-remnawave-backend:latest
sudo -E sh deploy-custom-panel.sh
```

Затем на сервере ноды:

```sh
export ALX_NODE_IMAGE=ghcr.io/OWNER/aetherlink-x-remnawave-node:latest
sudo -E sh deploy-custom-node.sh
```

Если GitHub-репозиторий или GHCR package приватный, до запуска необходимо выполнить на сервере `docker login ghcr.io` с token, имеющим только право `read:packages`. Для сервера предпочтителен отдельный fine-grained/read-only token; не используйте основной пароль GitHub.

Оба скрипта сохраняют backup основного compose, проверяют объединённую конфигурацию и перезапускают только соответствующий сервис. Миграций базы данных нет. Команда отката печатается после успешного запуска.

После переключения создайте профиль `AetherLink X Static`, вставьте сгенерированный `server.json`, назначьте профиль тестовой Node и включите inbound `AETHERLINK_X_REALITY`. Не добавляйте этот inbound во внутренний squad: подключения выдаются ручным клиентским JSON, а не стандартной подпиской Remnawave.

Перед переключением production-ноды рекомендуется создать отдельную тестовую Node в Remnawave и назначить ей отдельный порт.
