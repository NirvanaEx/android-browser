# Upgrid Browser

Android-браузер на движке **GeckoView** (Firefox) с встроенной блокировкой рекламы уровня **uBlock Origin** — без UI расширений и настройки. Запустил — и реклама ушла.

## Зачем ещё один браузер?

Большинство Android-браузеров с «встроенным AdBlock» блокируют только по списку доменов. Это убирает запросы к рекламе, но не прячет пустые контейнеры, не справляется с anti-adblock-обходами и не работает на сайтах вроде YouTube. uBlock Origin делает все 5 уровней фильтрации (network, cosmetic, scriptlet injection, HTML filtering, обновление списков). Этот проект просто берёт настоящий uBO и прячет его «под капот» в браузере.

## Архитектура (1 минута)

```
BrowserApplication (process-scope)
   └── BrowserComponents
         ├── GeckoRuntime    ← движок Firefox, один на процесс
         ├── Engine          ← обёртка GeckoEngine из android-components
         ├── BrowserStore    ← Redux-стор для будущих вкладок
         └── HTTP client (OkHttp) — нужен feature-addons
   └── AdblockBootstrap → silent install uBO XPI с AMO
MainActivity
   ├── omnibar (URL + back/forward/reload)
   └── GeckoEngineView ← рендерит активную EngineSession
```

`MainActivity` для MVP работает напрямую с одной `EngineSession` без вкладок, чтобы быстрее запуститься. Перейдём на store-driven session management в фазе 2.

## Сборка

**Требования:**
- Android Studio Ladybug+ (или Hedgehog), JDK 17
- Android SDK с platform-35
- Gradle 8.7+ (Android Studio подтянет сам через wrapper)

**Первый запуск в Android Studio:**
1. `File → Open` → указать корень проекта
2. Studio предложит «Generate Gradle wrapper» — согласиться (или выполнить `gradle wrapper --gradle-version 8.10` вручную)
3. Sync Gradle. Первая загрузка GeckoView ~80 МБ — будет долго.
4. Run на устройстве/эмуляторе с Android 8.0+ (API 26)

**Командная строка (после генерации wrapper):**
```powershell
.\gradlew :app:assembleDebug
.\gradlew :app:installDebug
```

## Что работает

- ✅ Загрузка любой https-страницы
- ✅ Адресная строка с авто-определением URL vs поискового запроса
- ✅ Silent install uBlock Origin при первом запуске + быстрый тумблер в нижней панели
- ✅ Tracking Protection (Mozilla, recommended-уровень) — даже до uBO
- ✅ Вкладки: шторка со списком, восстановление сессии после перезапуска
- ✅ Стартовая страница со ссылками быстрого доступа
- ✅ Встроенный видеоплеер: перехват `<video>`, жесты (перемотка, громкость,
      яркость), Picture-in-Picture с кнопками управления в плавающем окне
- ✅ История посещений: группировка по дням, поиск, удаление
- ✅ Поиск по странице, версия сборки в настройках
- ✅ Открытие http(s)-ссылок из других приложений (можно поставить дефолтным браузером)

## Чего пока нет

- ❌ Закладки, загрузки
- ❌ Reader view
- ❌ Выбор темы (приложение следует системной)
- ❌ Управление расширениями (Dark Reader, Bitwarden — по плану позже)

## Обновление uBlock Origin

URL XPI зашит в `AdblockBootstrap.UBO_XPI_URL`. Когда выйдет новая версия uBO:
1. Открыть https://addons.mozilla.org/firefox/addon/ublock-origin/
2. Кликнуть «Add to Firefox» в браузере, в DevTools посмотреть финальный URL
3. Обновить константу

GeckoView потом сам обновляет уже установленные расширения по AMO update-manifest, но первая установка всегда тянется по фиксированному URL.

## Лицензия

Код проекта — MIT. GeckoView под MPL 2.0, uBlock Origin под GPLv3 — устанавливается во время выполнения, не входит в APK, поэтому лицензионных конфликтов нет.
