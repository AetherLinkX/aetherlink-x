# AetherLink X — автономный комплект

Эта папка содержит всё, что относится к новому протоколу: исходники, точки интеграции с Xray-core, переносимый patch, документацию, конфигурации, тесты, готовые Windows/Linux-бинарники и интеграцию с Remnawave Node.

Готовый архив для переноса: `dist/AetherLink-X-0.4.0-experimental.zip`; его SHA-256 находится в одноимённом `.sha256` файле.

## Структура

```text
Для протокола/
  source/             автономная копия proxy/aetherlinkx
  integration/        копии изменённых точек Xray-core
  patches/            patch для чистого Xray-core
  docs/               архитектурный анализ и проектирование
  tools/              установка, сборка, проверка и генерация конфигов
  remnawave/          custom Node image, deploy script и Config Profile
  .github/workflows/  сборка multi-arch Remnawave Backend и Node в GHCR
  dist/               готовые Windows/Linux-бинарники и архив
  manifest.json       версия, совместимость и результаты проверок
  SHA256SUMS          контрольные суммы комплекта
```

Рабочая копия исходников также остаётся в `Xray-core-source/Xray-core-main/proxy/aetherlinkx`, поскольку Go/Xray требует пакет внутри дерева модуля. Удаление этой копии сломало бы текущую сборку. Канонический переносимый комплект — эта папка.

## Быстрый запуск готового бинарника

```powershell
.\dist\windows-amd64\xray-aetherlinkx.exe version
.\dist\windows-amd64\xray-aetherlinkx.exe aetherlinkx-keygen
```

Проверка встроенной регистрации протокола:

```powershell
.\dist\windows-amd64\xray-aetherlinkx.exe run -test -config .\dist\windows-amd64\smoke-config.json
```

## Установка в чистый Xray-core

```powershell
.\tools\install.ps1 -XrayRoot C:\src\Xray-core
.\tools\build.ps1 -XrayRoot C:\src\Xray-core
```

Installer сначала выполняет `git apply --check`. Patch применяется атомарно; уже установленная точная версия распознаётся через reverse-check. При несовместимом или частично применённом дереве скрипт останавливается и ничего не переписывает.

## Генерация пары REALITY-конфигов

Сначала получите отдельную пару REALITY-ключей штатной командой Xray. Затем:

```powershell
.\tools\generate-configs.ps1 `
  -ServerAddress alx.example.com `
  -RealityServerName www.example.com `
  -RealityPublicKey PUBLIC_KEY `
  -RealityPrivateKey PRIVATE_KEY
```

Скрипт самостоятельно создаст UUID, account secret и X-Wing key pair и запишет согласованные `client.json`, `server.json` и готовый для вставки в Remnawave `remnawave-profile.json`. Секреты выводятся только в созданные файлы; каталог следует защищать как credentials.

## Документация

- `docs/XRAY_PROTOCOL_ARCHITECTURE_REPORT.md` — исследование Xray;
- `docs/AetherLink-X/README.md` — архитектура и roadmap;
- `docs/AetherLink-X/IMPLEMENTATION_STATUS.md` — точный итог реализации;
- `source/proxy/aetherlinkx/README.md` — wire 1.1 и JSON-поля;
- `source/proxy/aetherlinkx/SECURITY.md` — threat model и checklist;
- `source/proxy/aetherlinkx/BENCHMARKS.md` — baseline производительности.

## Remnawave

В `remnawave/` находятся воспроизводимые сборки Backend/Node, ALX-aware
WASM-валидатор, static Config Profile и managed-адаптеры для Internal Squads.
Managed-режим выдаёт отдельные ALX credentials каждому пользователю, передаёт
add/remove события Node и генерирует `aetherlinkx://` и Xray JSON подписки.
Точная матрица Panel/Node/Xray и fail-closed политика для неизвестных версий
описаны в `remnawave/compatibility-matrix.json`.

«Поддержка разных версий» реализована через точные адаптеры и неизменяемые
SHA-теги образов. Универсальная бинарная совместимость со всеми будущими
версиями Panel/Node/Xray технически невозможна; неизвестная комбинация
останавливается до изменения Compose, а новый адаптер добавляется после CI и
runtime smoke-test.

ALX остаётся экспериментальным протоколом без независимого криптографического аудита. Перед публичным production-развёртыванием обязательны review, Linux race/soak tests и сетевые испытания с loss/jitter/netem.
