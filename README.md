# AetherLink X — автономный комплект

Эта папка содержит всё, что относится к новому протоколу: исходники, точки интеграции с Xray-core, переносимый patch, документацию, конфигурации, тесты, интеграцию с Remnawave Node и Android-клиент. Бинарные сборочные артефакты в Git не хранятся: APK, образы и ядро создаются воспроизводимыми GitHub Actions.

## Структура

```text
Для протокола/
  source/             автономная копия proxy/aetherlinkx
  integration/        копии изменённых точек Xray-core
  patches/            patch для чистого Xray-core
  docs/               архитектурный анализ и проектирование
  tools/              установка, сборка, проверка и генерация конфигов
  remnawave/          custom Node image, deploy script и Config Profile
  client/             AetherLink X Client для Android (GPLv3)
  .github/workflows/  сборка multi-arch Remnawave Backend и Node в GHCR
  dist/               только небольшие smoke-конфиги; бинарники публикуются в Releases
  manifest.json       версия, совместимость и результаты проверок
```

Рабочая копия исходников также остаётся в `Xray-core-source/Xray-core-main/proxy/aetherlinkx`, поскольку Go/Xray требует пакет внутри дерева модуля. Удаление этой копии сломало бы текущую сборку. Канонический переносимый комплект — эта папка.

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
- `docs/AETHERLINK_X_CLIENT_REMNAWAVE.md` — установка клиента и импорт подписки.
- `docs/ANDROID_INSTALLATION_RU.md` — полная сборка, установка, импорты, транспорты и диагностика Android-клиента.
- `docs/AETHERLINK_X_LIVE_DIAGNOSIS_2026-09-03.md` — проверенный отчёт о панели, FI-ноде, REALITY, TCP/UDP и границе Android-сбоя без публикации секретов.
- `tools/aetherlinkx_diagnose.py` и `tools/README-diagnostics.md` — безопасная диагностика подписки, согласованности ключей и реального TCP/UDP-трафика без вывода секретов.

## AetherLink X Client

Клиент находится в `client/` и поддерживает AetherLink X, VLESS, VMess,
Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS, WireGuard, SOCKS5 и HTTP.
ALX-ссылки `aetherlinkx://` импортируют account secret и полные Turbo,
PQ/security и Stealth параметры из Remnawave. Также поддерживается прямой
импорт ALX-outbound из полного Xray JSON, если панель выбрала этот формат.

Workflow `build-aetherlinkx-client-android.yml` собирает собственный
`libv2ray.aar` из Xray-core 26.7.28 с ALX patch, а затем APK. Активно
разрабатывается и публикуется только Android-клиент. Linux-клиент и его CI удалены.

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
