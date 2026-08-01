# CLAUDE.md

Guidance for Claude Code working on this repository.

**Полное описание — [docs/architecture.md](docs/architecture.md), 47 разделов.**
Здесь только то, что нужно в каждой сессии: правила, которые легко нарушить, и
ловушки, которые стоили времени. За поведением конкретного экрана или фичи —
идите в architecture.md, там разделы названы по-человечески.

## Mission in one line

GeckoView-based Android browser whose only "killer feature" is **uBlock Origin running silently under the hood**. Treat the extensions system as an implementation detail, not a user-facing feature.

Product target is **Banana Browser** (Google Play) — minimal chrome, big tap targets, single-purpose menu sheet, no power-user clutter. Not Firefox-for-Android, not Chrome.

## Stack snapshot

- **Engine**: GeckoView (Firefox), via `org.mozilla.components:browser-engine-gecko`
- **Frameworks**: Mozilla android-components 150.0.2, pinned in [app/build.gradle.kts](app/build.gradle.kts) via `androidComponentsVersion`
- **AdBlock**: uBlock Origin (Mozilla-signed XPI from AMO, fetched at first launch)
- **Language**: Kotlin 2.0, Java 17, AGP 8.7 · **min/target SDK**: 26 / 36
- **UI**: View system + ViewBinding. `values-sw600dp` включает планшетный таб-стрип и вращение

## Repo map

Всё под `app/src/main/java/com/upgrid/browser/`:

| Каталог | Что там |
|---|---|
| корень | `BrowserApplication`, `BrowserComponents` (единственный источник runtime/engine/store), `MainActivity`, `AdblockController` |
| `addons/` | тихая установка uBO и пин версии |
| `fullscreen/` | видеоплеер: мост к расширению, оверлей, замок |
| `sync/` | Google-аккаунт, Drive v3, версионированный JSON |
| `tabs/` `home/` `bookmarks/` `history/` `download/` `logins/` | экраны и их хранилища |
| `search/` `menu/` `prefs/` `privacy/` `vpn/` `errors/` `ui/` | остальное |

Плюс `assets/extensions/upgrid_fullscreen/` — единственное встроенное расширение
(video, find, translate, logins) и `assets/error.{html,css,js}` — страница ошибки,
которых обязательно три файла.

Подробная карта с назначением каждого файла — в [architecture.md → Repo map](docs/architecture.md).

## Cardinal rules

1. **Never bypass `BrowserComponents`.** `GeckoRuntime` создаётся ровно один раз на процесс; второй — тихие поломки или краш.
2. **Don't downgrade tracking protection.** Движок по умолчанию на `TrackingProtectionPolicy.recommended()`, uBO работает *поверх*.
3. **uBO is not optional, but is invisible.** Никакого UI расширений. Пользователю доступен один тумблер AdBlock.
4. **Pin `androidComponentsVersion` together.** Каждый `org.mozilla.components:*` — одной версии, иначе крэши в нативном коде.
5. **Don't bundle the XPI in `assets/`.** Ставим с AMO в рантайме ради автообновлений.

## Две ловушки, стоившие дня

**uBO install требует `WebExtensionDelegate`.** Не вызывайте `engine.installWebExtension(...)` без `registerWebExtensionDelegate(...)`. GeckoView поднимает prompt на разрешения, без делегата он `AbortError`'ится, установка **молча проходит**, но uBO работает наполовину: фильтры есть, косметика и анти-обход — нет. В `PermissionPromptResponse` давайте все три флага. Детали и сигнатура — в architecture.md.

**`AdblockController.isEnabled()` — suspend, не зовите из observer.** Колбэк `listInstalledWebExtensions` не всегда синхронный: на холодном старте вернёт `false` при живом uBO. И не вызывайте из `store.flow().collect` — тики летят десятками за загрузку страницы, очередь движка забивается, main thread встаёт, ANR. Обновлять щит только в `wireBottomBar()`, по тапу, после переключения в меню и в `onResume()`.

## Build identity & delivery

`versionCode` — количество коммитов, `versionName` — `$baseVersion.$commitCount`, оба считаются в [app/build.gradle.kts](app/build.gradle.kts).

- **CI обязан чекаутить с `fetch-depth: 0`** — иначе versionCode всегда 1 и Android откажет в обновлении.
- **`versionCode` руками не правят**, он производный.
- **Debug-сборки подписаны закоммиченным ключом**, не сгенерированным. `signingConfigs` не «чистить»: на нём держится вход в Google-аккаунт.

**Верхняя `## `-секция [CHANGELOG.md](CHANGELOG.md) — пользовательский текст.** CI кладёт её в тело релиза, релей вырезает в подпись к APK в Telegram. По-русски, 3–5 пунктов, **только видимое в приложении**: без ключей, архитектуры и обоснований — это в коммит и в architecture.md. Секция ищется как «первая `## `», не по номеру версии.

Сборка — в GitHub Actions, не на VPS: Gradle с Gecko хочет больше памяти, чем там есть.

## Скриншоты в чат

**API отвергает мульти-картиночные чаты, если хоть одна сторона больше 2000 px**, а телефон отдаёт 1080×2400 — такой скриншот роняет всю переписку. Порядок: класть сырые в [screenshots/](screenshots/), прогонять [tools/Resize-Screenshots.ps1](tools/Resize-Screenshots.ps1), в чат тащить уменьшенные. Если пользователь присылает свежий скриншот с телефона — сначала напомнить про это.

## Шпаргалка по сборке

- `Could not find org.mozilla.components:...` → нет `maven("https://maven.mozilla.org/maven2/")` в `settings.gradle.kts`
- `UnsatisfiedLinkError: libxul.so` → ABI устройства не в `splits.abi.include`
- `GeckoRuntime already running` → кто-то создал runtime мимо `BrowserComponents`, ищите `GeckoRuntime.create`
- IDE краснит `mozilla.components.*` → invalidate caches, первый синк с maven Mozilla бывает неполным
- **`+ ' ' +` в Kotlin дважды сохранялся как NUL-байт**: файл становится бинарным, `grep` по нему молча ничего не находит. Писать `"${a} $b"`, проверять `git ls-files -z -- '*.kt' | xargs -0 grep -lP '\x00'`

## Тестирование

Реальное устройство лучше эмулятора. Проверка, что uBO жив: `https://d3ward.github.io/toolz/adblock`, ожидаемо 90+%. После смены версии a-c — youtube.com (косметика), facebook.com (анти-обход), крупный новостной сайт.
