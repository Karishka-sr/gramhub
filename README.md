# GramHub

Лаунчер для мессенджеров Telegram: находит все клиенты Telegram, установленные на
телефоне, показывает их одним списком и запускает в один тап. Плюс каталог — можно
найти и установить клиенты, которых на телефоне ещё нет.

Интерфейс — Material Design 3 (Material You, динамические цвета на Android 12+).
Локализация: русский (по умолчанию) и английский.

## Возможности

**Установленные клиенты**

- Находит клиенты Telegram по списку известных пакетов и по слову «telegram» в названии
- Запуск в один тап, долгое нажатие — долгий тап по иконке GramHub запускает клиент по умолчанию
- Избранное (закрепляются вверху), скрытие ненужных клиентов, ручная сортировка
- Сортировка по названию, по дате установки или вручную
- Поиск по списку, три уровня плотности списка

**Каталог**

- Сервер собирает клиенты с GitHub: Nekogram, Telegram-FOSS, Telegram-X и другие
- Безопасность: сервер скачивает APK и проверяет его, приложение перед установкой
  сверяет SHA-256 — файл ставится, только если совпал с тем, что проверял сервер
- Вердикт по разрешениям: «нормально», «стоит посмотреть», «опасно»

**Оформление**

- Три темы: системная, светлая, тёмная, плюс AMOLED-режим
- Динамические цвета из обоев (Android 12+, Material You)

## Скачать

Готовые APK — в разделе [Releases](../../releases/latest):

- `GramHub-<версия>-release.apk` — рекомендуется
- `GramHub-<версия>-debug.apk` — для отладки, ставится рядом с release (отдельный
  идентификатор `dev.gramhub.debug`)

Требуется Android 7.0 (API 24) или новее.

## Сборка

Нужны Android SDK (build-tools 35), JDK 21 и `ANDROID_HOME`.

```bash
./gradlew :app:assembleRelease     # подписанный release APK
./gradlew :app:assembleDebug       # debug APK
./gradlew :app:testDebugUnitTest   # юнит-тесты приложения
```

Release-сборка подписывается ключом из `keystore.properties` в корне проекта:

```properties
storeFile=keystore/gramhub-release.jks
storePassword=...
keyAlias=gramhub
keyPassword=...
```

Файл ключа и `keystore.properties` в репозиторий **не входят** — ключ подписи
хранится отдельно. Без `keystore.properties` сборка проходит, но APK остаётся
неподписанным.

## Сервер каталога

`server/` — FastAPI-приложение: находит клиенты Telegram на GitHub, скачивает их
release-APK и сканирует разрешения. Запуск:

```bash
cd server
pip install -r requirements.txt
PYTHONPATH=. GRAMHUB_DATA_DIR=/tmp/gh_live \
  python3 -m uvicorn gramhub_server.api:create_app --factory --host 0.0.0.0 --port 8080
PYTHONPATH=. pytest tests -q        # 83 теста
```

### Адрес каталога

По умолчанию приложение читает статический снимок каталога:

```
https://karishka-sr.github.io/gramhub/clients.json
```

Этот файл лежит в репозитории (`docs/clients.json`) и раздаётся через GitHub Pages,
поэтому работает без запущенного сервера и без хостинга. Раз в сутки
workflow `.github/workflows/refresh-catalog.yml` заново ищет клиентов и обновляет
снимок; обновить вручную можно так:

```bash
GITHUB_TOKEN=... python3 tools/refresh_catalog.py -o docs/clients.json
```

Можно указать и свой сервер: Настройки → Каталог клиентов → Адрес сервера. Адрес
может быть как живым сервером (`https://catalog.example.com`, приложение
обратится к `/clients`), так и прямой ссылкой на `.json`-снимок.

## Структура

```
app/                    Android-приложение (Kotlin, Jetpack Compose)
  src/main/java/dev/gramhub/
    data/               репозитории, настройки, сопоставление клиентов
    remote/             клиент каталога, загрузка и проверка APK
    ui/                 экраны: список, каталог, настройки, запуск
  src/main/res/values/      русские строки (по умолчанию)
  src/main/res/values-en/   английские строки
  src/test/             юнит-тесты (JUnit + Robolectric + Compose)
server/                 FastAPI-сервер каталога
  gramhub_server/       API, конфиг, сканер разрешений, работа с GitHub
  tests/                тесты сервера (pytest)
```

## Тесты

- Приложение: 45 юнит-тестов
- Сервер: 83 теста

## Лицензия

Не задана. Добавьте файл LICENSE, если планируете распространять проект.

---

# GramHub (English)

A launcher for Telegram messengers: it lists every Telegram client installed on the
phone and opens one in a single tap. A built-in catalog lets you find and install
clients you do not have yet.

Material Design 3 UI (Material You dynamic colour on Android 12+). Russian and
English localisation. Requires Android 7.0 (API 24) or newer.

Download the latest APKs from [Releases](../../releases/latest). To build, run
`./gradlew :app:assembleRelease` (JDK 21 and Android SDK 35 required). The release
key is read from `keystore.properties`, which is not committed.
