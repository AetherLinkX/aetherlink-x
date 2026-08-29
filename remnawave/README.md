# AetherLink X и Remnawave

## Что действительно требуется

GitHub сам по себе не добавляет протокол в панель. Remnawave Panel валидирует Xray JSON, отправляет его в Remnawave Node, а Node запускает встроенный `/usr/local/bin/xray`. Поэтому для ALX нужны два согласованных образа: custom Backend и custom Node.

`Dockerfile` собирает Xray-core из официального тега `v26.7.28`, commit `5ca6f4b7d4dc20a881d4330e498892697627ec0c`, применяет ALX patch и заменяет бинарник внутри выбранного образа Remnawave Node. `Dockerfile.backend` собирает вариант для Remnawave `3.3.2`. `Dockerfile.backend-2.7.4` и `backend-static-alx-2.7.4.patch` предназначены для установленной панели `2.7.4` и не требуют обновления её базы данных.

Оба patch проверены командой `git apply --check` на закреплённых commit. GitHub Actions публикует два multi-architecture image для `linux/amd64` и `linux/arm64`:

- `ghcr.io/OWNER/aetherlink-x-remnawave-backend`;
- `ghcr.io/OWNER/aetherlink-x-remnawave-node`.

Для legacy-пары, соответствующей фактически установленной панели:

- `ghcr.io/OWNER/aetherlink-x-remnawave-backend-2.7.4`;
- `ghcr.io/OWNER/aetherlink-x-remnawave-node-2.7.0`.

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

Для панели `2.7.4` используйте только согласованную пару:

```sh
export ALX_BACKEND_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-backend-2.7.4:sha-7ef48b3
export ALX_NODE_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-node-2.7.0:sha-7ef48b3
```

Проверенные multi-arch digest: Backend `sha256:64027ea7c88f4b656ec16e401734fddf437216035a9756fa0fb1b196aad5a796`, Node `sha256:5d2907bbd2cbcefd608eba3a8f05c2cf40d253c243bb67641f60a2a3d8f6fda4`. Сборка: [GitHub Actions #3](https://github.com/AetherLinkX/aetherlink-x/actions/runs/33248315033).

Не устанавливайте Backend `3.3.2` поверх панели `2.7.4` без отдельной штатной процедуры обновления Remnawave.

Сначала на сервере панели:

```sh
export ALX_BACKEND_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-backend-2.7.4:sha-7ef48b3
sudo -E sh deploy-custom-panel.sh
```

Затем на сервере ноды:

```sh
export ALX_NODE_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-node-2.7.0:sha-7ef48b3
sudo -E sh deploy-custom-node.sh
```

Если GitHub-репозиторий или GHCR package приватный, до запуска необходимо выполнить на сервере `docker login ghcr.io` с token, имеющим только право `read:packages`. Для сервера предпочтителен отдельный fine-grained/read-only token; не используйте основной пароль GitHub.

Оба скрипта сохраняют backup основного compose, проверяют объединённую конфигурацию и перезапускают только соответствующий сервис. Миграций базы данных нет. Команда отката печатается после успешного запуска.

После переключения создайте профиль `AetherLink X Static`, вставьте сгенерированный `server.json`, назначьте профиль тестовой Node и включите inbound `AETHERLINK_X_REALITY`. Не добавляйте этот inbound во внутренний squad: подключения выдаются ручным клиентским JSON, а не стандартной подпиской Remnawave.

Перед переключением production-ноды рекомендуется создать отдельную тестовую Node в Remnawave и назначить ей отдельный порт.
