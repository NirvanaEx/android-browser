# Уборка лишнего — android-browser — 2026-08-16

Прогон получился небольшим намеренно: код в проекте уже прошёл несколько чисток,
живых «лишних работ» в горячих путях я не нашёл (см. «Не делал»). Убрано только
то, что проверяемо мертво.

## Что сделано

### 1. Три объявления, которые никто не вызывает

- **Файл:** `app/src/main/java/com/upgrid/browser/download/DownloadRecords.kt:76`,
  `app/src/main/java/com/upgrid/browser/account/AccountStore.kt:65`,
  `app/src/main/java/com/upgrid/browser/fullscreen/VideoPlayerBridge.kt:176`
- **Зачем:** `DownloadRecords.byId()`, `AccountStore.isSignedIn` и
  `VideoPlayerBridge.isReady` объявлены и ни разу не прочитаны. Каждое имя
  встречается в `app/src/` ровно один раз — в своей же строке объявления.
  Динамического доступа быть не может: рефлексии над этими классами в проекте
  нет (`getMethod`/`getDeclaredField`/`Class.forName` не встречаются вовсе), а
  Kotlin-свойства недоступны из XML.
- **Что изменилось для кода:** убрано 11 строк мёртвого кода. Три публичных
  члена меньше в API трёх классов — читающий `VideoPlayerBridge` больше не
  тратит время на комментарий к геттеру, который ничего не питает.
- **Как проверить:**
  `grep -rn "byId\|isSignedIn\|isReady" app/src/` — пусто.

### 2. Четыре drawable без единой ссылки

- **Файл:** `app/src/main/res/drawable/ic_skip_next.xml`,
  `ic_skip_prev.xml`, `ic_play_dashed_circle.xml`, `bg_favicon.xml`
- **Зачем:** остатки двух переделок. Первые три пришли с коммитом
  «Built-in video player» (`677eba3`) под кнопки перемотки, которых в текущем
  оверлее нет; `bg_favicon.xml` — фон плитки со стартовой страницы, вытесненный
  `bg_host_tile` / `bg_site_icon_plate` в `SiteIconView`. Ссылок нет ни в
  Kotlin, ни в layout/anim/xml, ни в манифесте, ни в `assets/`;
  `Resources.getIdentifier` в проекте не используется, так что имя ресурса
  собраться в рантайме не может.
- **Что изменилось для кода:** 4 файла ресурсов меньше — они больше не
  компилируются в APK и не всплывают в автодополнении рядом с живыми иконками.
- **Как проверить:**
  `grep -rn "ic_skip_next\|ic_skip_prev\|ic_play_dashed_circle\|bg_favicon" app/src/` — пусто.

### 3. Тринадцать строк локализации, которых не показывает ни один экран

- **Файл:** `app/src/main/res/values/strings.xml`,
  `app/src/main/res/values-ru/strings.xml`
- **Зачем:** `start_page_tagline`, `tabs_tray_title`, `tabs_tray_title_count`,
  `settings_section_content`, `settings_section_bookmarks`,
  `settings_open_bookmarks`, `settings_section_browsing`,
  `settings_open_history`, `settings_clear_history`,
  `settings_section_downloads`, `settings_open_vpn`, `settings_clear_data`,
  `settings_open_downloads` — заголовки и пункты, переехавшие в другие экраны
  (меню-шит, отдельные Activity) вместе с переделками настроек, стартовой
  страницы и тайтла вкладок. Ни `R.string.<имя>`, ни `@string/<имя>` не
  встречаются нигде в `app/src/main`.
- **Что изменилось для кода:** удалено 26 строк (13 в базовой локали + 13 в
  русской). Тексты, которые пользователь не увидит, больше не переводятся при
  каждой правке ru-файла и не создают ложного впечатления, что такие пункты
  в настройках есть. Ни одна секция не осталась пустой — у каждого
  комментария-заголовка остались живые строки под ним.
- **Как проверить:**
  `grep -rn "tabs_tray_title\|settings_open_vpn\|start_page_tagline" app/src/` — пусто;
  `python3 -c "import xml.etree.ElementTree as E; [E.parse(f) for f in ['app/src/main/res/values/strings.xml','app/src/main/res/values-ru/strings.xml']]"` — оба файла разбираются.

## Не делал

- **`hostOf()` продублирован байт в байт** в `HistoryStore.kt:355` и
  `HostTile.kt:65` (`runCatching { Uri.parse(url).host.orEmpty().removePrefix("www.") }.getOrDefault("")`).
  Мест два, а не три, расхождения ещё нет, и любое объединение тянет зависимость
  между слоями не в ту сторону: либо хранилище истории начинает зависеть от
  `ui/`, либо UI-плитка от `history/`. Диффа на ровном месте больше, чем пользы.
- **`url.startsWith("http://") || url.startsWith("https://")`** в пяти местах
  (`MainActivity.kt:2767`, `BookmarkStore.kt:203`, `LinkContextMenu.kt:261`,
  `AppMenuPopup.kt:246` и рядом). Формально дублирование, но это однострочный
  предикат, все пять копий идентичны, и вынос ради него общего хелпера — ровно
  тот «рефакторинг ради красоты», который профиль запрещает.
- **Срезание схемы для показа** (`removePrefix("https://").removePrefix("http://")`)
  в `Suggestions.kt:221,251`, `HistoryAdapter.kt:104`, `LinkContextMenu.kt:136`.
  Три варианта чуть разные (где-то ещё `removeSuffix("/")`, где-то `www.`), и
  сведение их к одному — это уже изменение того, что видит пользователь в
  строках. Не уборка.
- **Лишней работы в горячих путях не нашёл.** Смотрел `observeStore()`
  (`MainActivity.kt:1936`), `recordVisit()` (2386), `DownloadRecords.persist()`,
  `BrowsingDataCleaner.clear()`, `SiteIconView`. Все дорогие места уже закрыты
  осознанно: три коллектора над `store.flow()` с `distinctUntilChanged` по своим
  полям, мемоизация последнего визита по `tab.id`, `durable=false` на тиках
  прогресса. Трогать нечего.
- **Неиспользуемых layout'ов нет.** Первый проход показал 28 «мёртвых», но это
  ложные срабатывания ViewBinding: `activity_vpn.xml` находится по имени класса
  `ActivityVpnBinding`. Повторная проверка по camelCase-именам биндингов даёт
  ноль.
- **Закомментированного кода, `TODO`, `FIXME`, `@Deprecated` в проекте нет** —
  проверено grep'ом по всем `*.kt`.

## Тесты

Тестов (`src/test`, `src/androidTest`) в проекте нет — искал, каталоги
отсутствуют.

Сборкой проверить тоже не вышло: в контейнере нет JDK, `./gradlew` падает на
`JAVA_HOME is not set and no 'java' command could be found in your PATH`. Это
согласуется с CLAUDE.md — сборка живёт в GitHub Actions, не локально.

Вместо сборки проверено то, что в данном случае её заменяет: все три правки —
удаление объявлений с нулём ссылок, и отсутствие ссылок подтверждено grep'ом по
всему `app/src/` (Kotlin, res, assets, манифест) для каждого удалённого имени, а
оба `strings.xml` разобраны XML-парсером. Компиляция сломаться не может: ничего
из удалённого никем не читается. Полноценная проверка — зелёный
`:app:assembleDebug` в CI на этой ветке.
