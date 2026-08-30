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

Для нод, которые уже работают на Remnawave Node `3.2.2`, workflow также
публикует совместимый образ без понижения версии Node:

- `ghcr.io/OWNER/aetherlink-x-remnawave-node-3.2.2`.

Для выборочной выдачи через Internal Squads собирается отдельная согласованная
пара managed-образов:

- `ghcr.io/OWNER/aetherlink-x-remnawave-backend-managed-2.7.4`;
- `ghcr.io/OWNER/aetherlink-x-remnawave-node-managed-3.2.2`.

Версия Backend и версия Node не обязаны совпадать. Однако базовую версию
production-ноды менять или понижать только ради ALX нельзя: выбирайте образ с
тем же суффиксом версии, который панель показывает для конкретной ноды.

## Режимы интеграции

**Статический режим** сохраняет ALX accounts непосредственно в Config Profile.
Он подходит для ручных клиентских JSON, но не предоставляет изоляцию по squad.

**Managed-режим** включается только для inbound, tag которого начинается с
`AETHERLINK_X_MANAGED_`. Backend очищает его `settings.users` и при каждой
сборке конфигурации добавляет только пользователей Internal Squad, которому
назначен этот inbound. События add/remove передаются Node без перезапуска Xray.
Пустой squad создаёт пустой список пользователей: inbound запускается, но
отклоняет любое рукопожатие.

Для каждого пользователя детерминированно формируются разные credentials:
`id = vlessUuid`, а `secret = base64url(SHA-256(context || ssPassword))`.
Backend и Node проверяются одним golden-вектором. При ротации стандартного
`ssPassword` меняется и ALX secret, отдельная миграция БД не требуется.

Raw/Xray-подписка получает URI `aetherlinkx://` и Xray JSON outbound.
Обычные сторонние клиенты не распознают новый URI без ALX-capable ядра или
импортера; поэтому для первого production-теста следует использовать
собственный Xray JSON.

Managed-реализация намеренно не меняет БД и официальный SDK package. Node
добавляет ALX protobuf account через тот же gRPC HandlerService, который
используется штатным SDK. Привязка к точным commit Remnawave 2.7.4/3.2.2
проверяется `git apply --check`, TypeScript build/typecheck и container build.

Неизвестные версии не патчатся «на удачу»: deploy-скрипты завершаются до
изменений. Новая версия добавляется в `compatibility-matrix.json` только после
отдельного адаптера и полного CI.

Node по умолчанию использует `ALX_COMPOSE_STRATEGY=in-place`: атомарно заменяет
только `services.remnanode.image` в существующем
`/opt/remnanode/docker-compose.yml` и сохраняет датированную резервную копию.
`SECRET_KEY`, network mode, порты, capabilities и остальные поля не
переписываются. Для прежней схемы с дополнительным Compose-файлом задайте
`ALX_COMPOSE_STRATEGY=override`.

## Развёртывание custom images

Для панели `2.7.4` используйте только согласованную пару:

```sh
export ALX_BACKEND_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-backend-2.7.4:sha-6a091c7
export ALX_NODE_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-node-2.7.0:sha-6a091c7
```

Точные проверенные digest фиксируются в `manifest.json`. Используйте только образ, для которого workflow завершился успешно и runtime healthcheck имеет статус `healthy`.

Не устанавливайте Backend `3.3.2` поверх панели `2.7.4` без отдельной штатной процедуры обновления Remnawave.

Если панель `2.7.4` уже управляет нодой `3.2.2`, используйте для этой ноды:

```sh
export ALX_NODE_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-node-3.2.2:sha-e1f9593
```

Для managed/squad-режима пары Panel 2.7.4 + Node 3.2.2:

```sh
export ALX_MODE=managed
sudo -E sh deploy-custom-panel.sh
# На отдельном сервере ноды:
export ALX_COMPOSE_STRATEGY=in-place
sudo -E sh deploy-custom-node.sh
```

Сначала на сервере панели:

```sh
export ALX_BACKEND_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-backend-2.7.4:sha-6a091c7
sudo -E sh deploy-custom-panel.sh
```

Затем на сервере ноды:

```sh
export ALX_NODE_IMAGE=ghcr.io/aetherlinkx/aetherlink-x-remnawave-node-2.7.0:sha-6a091c7
sudo -E sh deploy-custom-node.sh
```

Если GitHub-репозиторий или GHCR package приватный, до запуска необходимо выполнить на сервере `docker login ghcr.io` с token, имеющим только право `read:packages`. Для сервера предпочтителен отдельный fine-grained/read-only token; не используйте основной пароль GitHub.

Оба скрипта сохраняют backup основного compose, проверяют объединённую конфигурацию и перезапускают только соответствующий сервис. Миграций базы данных нет. Команда отката печатается после успешного запуска.

В статическом режиме создайте профиль `AetherLink X Static` и не добавляйте
его inbound во внутренний squad. В managed-режиме добавьте inbound с префиксом
`AETHERLINK_X_MANAGED_` в существующий профиль, затем включите его только в
отдельный Internal Squad: доступ получат исключительно пользователи этого
squad.

После добавления inbound откройте назначенную ноду, нажмите `Изменить` в
секции профиля, убедитесь, что новый порт отмечен, затем нажмите `Применить
изменения` и сохраните карточку ноды. Remnawave хранит выбранные inbound ноды
отдельно от JSON профиля; одного сохранения профиля недостаточно. До сохранения
карточки новый listener не запускается, а существующие listener продолжают
работать.

Managed inbound хранит одновременно приватный и соответствующий публичный
X-Wing ключ: приватный используется ядром, публичный нужен Backend для
клиентских ссылок. Xray проверяет совпадение пары в constant-time и не сохраняет
публичный ключ в runtime-настройках серверного handshake.

Перед переключением production-ноды рекомендуется создать отдельную тестовую Node в Remnawave и назначить ей отдельный порт.
