# CLAUDE.md

Guidance for Claude Code working on this repository.

## Mission in one line

GeckoView-based Android browser whose only "killer feature" is **uBlock Origin running silently under the hood**. Treat the extensions system as an implementation detail, not a user-facing feature.

## Stack snapshot

- **Engine**: GeckoView (Firefox), pulled transitively via `org.mozilla.components:browser-engine-gecko`
- **Frameworks**: Mozilla android-components 150.0.2 — pinned in [app/build.gradle.kts](app/build.gradle.kts) via `androidComponentsVersion`. **Every `org.mozilla.components:*` artifact must use the same version**, mismatches give cryptic linker errors at runtime.
- **AdBlock**: uBlock Origin (Mozilla-signed XPI from AMO, fetched at first launch)
- **Language**: Kotlin 2.0, Java 17 toolchain, AGP 8.7
- **min/target SDK**: 26 / 35
- **UI**: View system + ViewBinding (Compose later)

## Repo map

```
app/src/main/
├── AndroidManifest.xml             ← intent-filters for VIEW http(s), default browser
├── assets/extensions/upgrid_fullscreen/  ← built-in player WebExtension (manifest + background.js + player.js + observer.js)
├── java/com/upgrid/browser/
│   ├── BrowserApplication.kt       ← components; restore session; uBO bootstrap; autosave
│   ├── BrowserComponents.kt        ← single source of truth for runtime/engine/store/tabs
│   ├── MainActivity.kt             ← the single top bar + GeckoEngineView + Session/Toolbar
│   │                                 features. No bottom bar — see below.
│   ├── AdblockController.kt        ← thin façade for the AdBlock on/off toggle
│   ├── addons/AdblockBootstrap.kt  ← silent uBO install + version pin
│   ├── bookmarks/
│   │   ├── BookmarkStore.kt        ← SQLite, one row per URL, no folders
│   │   ├── BookmarkAdapter.kt
│   │   └── BookmarksActivity.kt    ← full screen, search, undo on delete
│   ├── fullscreen/
│   │   ├── VideoPlayerBridge.kt    ← native ⇆ extension port; takeover trigger
│   │   └── PlayerOverlayController.kt ← overlay buttons, seek bar, gestures
│   ├── history/
│   │   ├── HistoryStore.kt         ← SQLite visits table (one row per URL)
│   │   ├── HistoryAdapter.kt       ← day chips + rows
│   │   └── HistoryActivity.kt      ← full screen, search, clear-all
│   ├── home/                       ← speed-dial start page (bookmarks, topped up from SEED)
│   ├── menu/AppMenuPopup.kt        ← 236dp drop-down menu (PopupWindow, not BottomSheet)
│   ├── prefs/BrowserPreferences.kt ← typed SharedPreferences façade (all settings)
│   ├── search/                     ← SearchEngine, SearchHistory, omnibar Suggestions
│   ├── settings/SettingsBottomSheet.kt ← account, adblock, search, player, data, about
│   ├── sync/
│   │   ├── AccountSync.kt          ← GoogleAccounts (sign-in) + SyncEngine (merge loop)
│   │   ├── DriveAppData.kt         ← the four Drive v3 calls, over HttpURLConnection
│   │   └── SyncPayload.kt          ← the versioned JSON document
│   ├── tabs/
│   │   ├── TabsActivity.kt         ← 2-column preview grid, store-driven
│   │   ├── TabThumbnails.kt        ← in-memory LRU of page captures
│   │   └── TabViewHolder.kt
│   └── ui/
│       ├── HostTile.kt             ← per-host letter + color, shared by every list
│       └── ExpandedBottomSheetFragment.kt ← sheets open full height
└── res/
    ├── layout/                     ← activity_{main,history,bookmarks,tabs} + app_menu_popup +
    │                                 fragment_settings + view_page_{header,search} +
    │                                 view_fullscreen_controls + …
    ├── values/styles.xml           ← row/tile styles for the menu and the sheets
    ├── values-ru/                  ← Russian translation (device-locale driven)
    └── values, drawable, mipmap…
```

## Cardinal rules

1. **Never bypass `BrowserComponents`.** `GeckoRuntime` *must* be created exactly once per process; making a second one will silently break things or crash. If you need engine state from anywhere, route it through `(application as BrowserApplication).components`.

2. **Don't downgrade tracking protection.** `BrowserComponents.engine` defaults to `TrackingProtectionPolicy.recommended()` — uBO sits *on top* of that. Don't disable it for "compatibility" without a measured reason.

3. **uBO is not optional, but is invisible.** No "extensions" UI in the app menu. The only knob the user gets is enable/disable AdBlock. If you add a settings screen, hide all WebExtension management behind a dev-mode flag for now.

4. **Pin `androidComponentsVersion` together.** Bumping a single dependency by hand will pull a different transitive `geckoview-stable` and you'll spend hours debugging native-code crashes. Update the `val` in `app/build.gradle.kts` and verify all `org.mozilla.components:*` artifacts at https://maven.mozilla.org/maven2/org/mozilla/components/.

5. **Don't bundle the XPI in `assets/`.** The current strategy is to install from the AMO URL at runtime so updates are automatic and the APK stays small. If you switch to a bundled XPI, document why and use `installBuiltInWebExtension` (privileged path) — but you lose auto-updates.

## Critical: uBO install requires a `WebExtensionDelegate`

**Never** call `engine.installWebExtension(uboUrl, …)` without first calling `engine.registerWebExtensionDelegate(…)`. GeckoView raises a permission prompt on install (uBO needs `<all_urls>`, `tabs`, `webNavigation`, etc.) — without a delegate to answer it, the prompt is `AbortError`'d via `Actor 'GeckoViewPermission' destroyed before query 'GeckoView:ContentPermission' was resolved`. The install **silently succeeds** but uBO comes up half-crippled: filters mostly work, but cosmetic + popup + anti-circumvention rules don't.

[AdblockBootstrap.registerAutoGrantDelegate()](app/src/main/java/com/upgrid/browser/addons/AdblockBootstrap.kt) registers the delegate. The signature in a-c 150.x is:

```kotlin
override fun onInstallPermissionRequest(
    extension: WebExtension,
    permissions: List<String>,
    origins: List<String>,
    dataCollectionPermissions: List<String>,
    onConfirm: (PermissionPromptResponse) -> Unit,
)
```

`PermissionPromptResponse(isPermissionsGranted, isPrivateModeGranted, isTechnicalAndInteractionDataGranted)` — grant **all three** for uBO; tech/interaction-data is needed for anti-circumvention rules and private-mode for filtering in incognito tabs.

We auto-grant **only for our pinned UBO_ID**; anything else falls through to the engine's default deny.

## Critical: `AdblockController.isEnabled()` is suspend, never call from observer

`Engine.listInstalledWebExtensions(…)` is a callback API. The callback **does not** always fire synchronously — on cold start the engine cache is cold and the callback lands on the next dispatch tick. A sync `isEnabled()` that reads `var result = false` before the callback fires returns `false` even when uBO is installed and active — symptom: bottom-bar shield permanently rendered OFF.

The fix is `suspend fun isEnabled(): Boolean = findUbo()?.isEnabled() == true`. **Don't** call it from `store.flow().collect { … renderAdblockShield() }` — store ticks fire dozens of times during page load, each launches a new lookup coroutine, the engine queue backs up, main thread stalls, **ANR**. Refresh shield only at: `wireBottomBar()` init, after the user's own shield tap, after `AppMenuPopup` toggles uBO (popup calls back via `activity.renderAdblockShield()`), and `onResume()`.

## Built-in video player (takeover architecture)

The topbar ▶ button hands the page's `<video>` to OUR overlay player. Key facts:

- **Gesture token path is sacred.** Gecko rejects `requestFullscreen()`/`play()` without a user gesture, and `loadUrl("javascript:…")` carries none. The only preserved path: Android click → `Action.onClick` (browser_action) → background.js `onClicked` → `tabs.executeScript` → page. Don't "simplify" this into direct JS injection — it half-works and the failure is silent.
- **Takeover = fullscreen the `<video>` element itself + `v.controls = false`.** The page's custom control DOM (YouTube/VK/video.js) lives *outside* the video element, so it simply never renders in fullscreen — no z-index fights. `player.js` remembers the original `controls` value and restores it on release.
- **State/commands flow over a native-messaging port** (`upgridPlayer`): content script streams `{t:"state", pos, dur, paused…}` every 500 ms; native sends `{cmd:"toggle"|"seekBy"|"seekTo"|"loop"|"release"}`. Native side: `VideoPlayerBridge.registerBackgroundMessageHandler`; manifest needs `nativeMessaging` + `geckoViewAddons` (privileged, OK for built-in extensions only).
- **Frame locking.** `player.js` is injected `allFrames:true` (videos live in embed iframes). background.js locks onto the first frame reporting takeover-ok and silently releases any later claimant; commands are routed with `{frameId}`.
- **`observer.js` is a *separate*, always-on content script**, declared in the manifest rather than injected. It watches `<video>`/`<audio>` and reports presence + playing to the port. Three features need that answer before the user has touched anything: the ▶ button (which shows only while something is playing), pausing on tab switch, and not claiming the player "is still starting" over a running video. Don't try to derive it from `tab.mediaSessionState` — that's only populated for sites that opt into the MediaSession API, and plenty of plain `<video>` pages never do. That was the original bug.
- **`browser_action` announcement races app start.** `onBrowserAction` may fire before `registerActionHandler` runs, and then the click handle is lost for the whole session — that's what produced "player is still starting" on a page that was visibly playing. `requestTakeover` now queues the request and re-arms the delegate; the queued tap still works because the user-gesture token Gecko needs comes from the browser-action click itself, not from the Android touch that asked for it.
- **`pauseAll` is a broadcast, not a routed command.** The native side has no way to name a Gecko tab (we don't run `WebExtensionSupport`, so tabs aren't even marked active for the extension APIs), and a tab that isn't on screen has no business making noise regardless of which one it is.
- **Exit is multi-path and must stay idempotent.** System back / fsExit button / page exiting fullscreen all converge: content script's `fullscreenchange` listener auto-releases → `"released"` event → MainActivity hides overlay + restores chrome. `exitPlayer()` also restores chrome optimistically without waiting for the round-trip.
- Seek step for double-tap/skip buttons is `BrowserPreferences.playerSeekSeconds` (5/10/15/30 s, settings sheet).

### The stage, and why there is no DOM fullscreen

**Takeover works by moving the `<video>`, not by restyling it in place.**
`player.js` builds a "stage": an opaque, viewport-filling `<div>` appended to
`documentElement`, and relocates the video into it.

Three details are load-bearing, each of them a bug that actually shipped:

- **Sizing lives in a `<style>` rule with `!important`, never inline.** YouTube
  rewrites the video element's inline `style` on every relayout, so a one-shot
  `video.style.cssText = …` is wiped within milliseconds and the page shows
  through — the site skin, the ambient-mode glow, the DOM subtitles, all of it.
  An author rule marked `!important` outranks the page's inline declarations.
- **The stage attaches to `<html>`, not `<body>`.** A `transform`/`filter` on an
  ancestor re-anchors `position: fixed` to that ancestor instead of the
  viewport, and pages do transform `<body>`. Nothing transforms `<html>`.
- **`appendChild` relocates atomically**, so the media element is still in a
  document when the spec's "await a stable state" check runs and playback isn't
  paused. MSE/blob sources survive untouched — which is the whole reason this
  works on YouTube, where the stream cannot be extracted at all (see below).

`reassert()` re-applies all of it on every 500 ms state tick and from a narrow
MutationObserver, because scripted players also re-parent their video back into
their own container. Don't widen that observer to a `subtree` watch on
`documentElement` — it fires thousands of times a second on YouTube.

**There is deliberately no `requestFullscreen`.** The stage already covers the
content area and the native side hides the chrome and system bars, so DOM
fullscreen bought nothing but races: entering it fought Android PiP, exiting it
was indistinguishable from the user leaving, and YouTube re-grabbed fullscreen
onto its own container anyway. The one remnant is that `reassert()` kicks any
*other* element out of fullscreen, since the top layer paints above every
z-index. `exitFullscreen()` needs no gesture and re-requesting one does, so
that can't ping-pong.

**Videos in iframes** are handled by promoting the frame chain: a frame that
takes over `postMessage`s its parent, which gives the containing `<iframe>` the
same stage treatment and asks *its* parent, up to the top document.
`event.source` identifies the child window even cross-origin, which is the only
reliable way to know which iframe spoke.

Related native state: `MainActivity` distinguishes `playerActive` (took over,
not yet released) from `playerOverlay.isVisible` (false in PiP while the player
runs). `setVideoFocus` owns `inVideoFocus`; `applyVideoFocus` re-pushes it onto
the window without touching the state, which is what a PiP resize needs.

### Why not a native player on the real stream

Asked often, so: extracting the media and handing it to ExoPlayer works only
where `video.currentSrc` is a fetchable http(s) URL. YouTube, VK and most large
sites deliver through Media Source Extensions — page JS pulls DASH segments and
appends them to a `SourceBuffer`, and `currentSrc` is a `blob:` handle that
exists only inside that page's JS context. It cannot be fetched, forwarded, or
handed to a native player. Real stream URLs would mean a NewPipe-class
extractor (InnerTube + signature deciphering): large, broken by Google on a
regular schedule, and against YouTube's ToS. The stage above is what actually
delivers "only the video" everywhere.

## API gotchas (these break between a-c versions)

The `Engine` callbacks have shifted between releases. Current expected signatures (a-c 150.x):

```kotlin
fun installWebExtension(
    url: String,
    onSuccess: ((WebExtension) -> Unit) = { },
    onError: ((Throwable) -> Unit) = { _ -> }
): CancellableOperation

fun listInstalledWebExtensions(
    onSuccess: ((List<WebExtension>) -> Unit) = { },
    onError: ((Throwable) -> Unit) = { _ -> }
)
```

If a build breaks at `AdblockBootstrap` with a callback-shape error, **first** check the Engine.kt in the matching a-c version on https://searchfox.org/mozilla-central/source/mobile/android/android-components/components/concept/engine/src/main/java/mozilla/components/concept/engine/Engine.kt — older versions used `(id, url, onSuccess, onError(String, Throwable))`.

## Bumping uBlock Origin

`AdblockBootstrap.UBO_XPI_URL` is pinned. To bump:

1. Open https://addons.mozilla.org/firefox/addon/ublock-origin/ in any Firefox.
2. DevTools → Network → click "Add to Firefox" → copy the final `.xpi` URL.
3. Replace `UBO_XPI_URL`. Bump `versionCode` of the app so existing installs re-trigger the bootstrap (which is a no-op if uBO is already installed at any version — GeckoView updates extensions on its own schedule).

The AMO file id changes per release; the version in the filename is cosmetic.

## Roadmap (don't merge work that violates phase ordering without discussion)

- **Phase 1 — MVP.** Single session, omnibar, silent uBO. **Done.**
- **Phase 2 — Tabs + persistence.** `BrowserStore`-driven; `BrowserToolbar`; `SessionFeature`/`ToolbarFeature`; bottom-sheet tabs tray; `SessionStorage` autosave/restore; AdBlock on/off in app menu. **Done.**
- **Phase 3 — History & bookmarks.** **Done**, but *not* on `browser-storage-sync` as originally planned — see below. Downloads (`feature-downloads`) still open, and is the one thing the menu no longer even hints at.
- **Phase 4 — Settings.** Search engine, AdBlock toggle, data management, about-page, Google account: **done**. Theme picker, default-browser prompt and "use system dark mode" still open.
- **Phase 5 — Optional extensions.** Surface a curated list (Dark Reader, Bitwarden, Tampermonkey) via `feature-addons`'s `AddonManager`. Behind a "Power user" setting.

## Architecture notes (phase 2)

- **`BrowserStore` is the single source of truth.** Tabs, selected tab id, URL, title, progress all live there. UI observes via `store.flow()` and dispatches actions; never mutate engine sessions directly.
- **Never render chrome straight off `store.flow()`.** The store ticks dozens of times per page load — every progress update is a new state, and almost none of them change anything visible. `MainActivity.observeStore` maps to a `ChromeState` of just the fields the bar renders and passes it through `distinctUntilChanged`, which turns a hundred redundant view writes per load into three or four. History collects separately and unfiltered, because it needs the title, which lands on a tick where nothing else moved.
- **The data stores live in `BrowserComponents`, one per process.** `BookmarkStore`, `HistoryStore` and `SearchHistory` used to be constructed per screen, so every activity showing a list opened its own `SQLiteOpenHelper` — a second connection and a second page cache against a file the activity already had open. Go through `components`.
- **Features are the glue.** `SessionFeature` renders the selected tab into `GeckoEngineView`; `ToolbarFeature` keeps `BrowserToolbar` synced. Both are bound through `ViewBoundFeatureWrapper` so they stop/start with the view lifecycle.
- **`BrowserApplication.restorePreviousSession`** restores tabs *before* the bootstrap installs uBO — this guarantees tabs are visible the moment the user sees the activity even if AMO is unreachable.
- **Tab close → empty state:** `MainActivity.wireBackPress` finishes the activity when the last tab is closed via the system back button. The tabs screen does *not* close itself when `tabs.isEmpty()` — it shows an empty illustration. `TabsActivity.finish()` is overridden to open a fresh HOME tab when the list is empty, because leaving with zero tabs would drop MainActivity onto an unrendered engine view. It's on `finish()` rather than in a click handler since three paths reach it: the back arrow, the back gesture, and picking a tab.
- **App menu is a `PopupWindow`, not a BottomSheet.** [AppMenuPopup](app/src/main/java/com/upgrid/browser/menu/AppMenuPopup.kt) is a 236dp drop-down anchored to `btnTopMenu` via `showAsDropDown(anchor, 0, 0, Gravity.END)`. Construct a new instance per tap (cheap, avoids stale toggle state) — but note `PopupWindow` keeps its content view between shows, so anything derived from browser state is re-read in `showFrom`, not at construction.
- **History, bookmarks and tabs are Activities, not sheets.** A sheet gave each list whatever height was left over and put a drag handle where a back arrow belongs. They dispatch to the shared `BrowserStore` and finish — no results to hand back, nothing for MainActivity to keep in sync. They share [view_page_header.xml](app/src/main/res/layout/view_page_header.xml) and [view_page_search.xml](app/src/main/res/layout/view_page_search.xml) via `<include>` so the three can't drift apart. Settings is still a sheet ([ExpandedBottomSheetFragment](app/src/main/java/com/upgrid/browser/ui/ExpandedBottomSheetFragment.kt)) — it's a flat list of switches with no navigation inside it.
- **Tab previews are memory-only** ([TabThumbnails](app/src/main/java/com/upgrid/browser/tabs/TabThumbnails.kt)), scaled to 360px on the way in, capped at 6 MB. `EngineView.captureThumbnail` can only see the tab currently rendered, so captures happen at the two moments where what's on screen is unambiguous: `onPause`, and the tap that opens the grid. **Don't capture on a selection change** — by the time the store reports one, the engine is already drawing the new tab, so the shot is the old page's pixels filed under the new tab's id.
- **The bookmark star is a toolbar page action**, not a sixth button. Four 44dp targets plus the video button already leave the URL under half a phone screen. `Toolbar.ActionToggleButton` owns its own selected state and only repaints on `invalidateActions()`, so `renderBookmarkAction` drives it and memoises the last URL it looked up — the store observer fires many times per load and each miss is a database round-trip.
- **One site looks the same everywhere.** [HostTile](app/src/main/java/com/upgrid/browser/ui/HostTile.kt) derives a letter and a color from the host, and history rows, bookmark rows, tab cards and speed-dial tiles all use it. The hash is computed by hand rather than via `String.hashCode()` so the colors can't reshuffle between releases. Favicons are used *on top of* the tile in the tabs grid, never instead of it — they arrive over the network and popping in mid-scroll reads as flicker.

## Chrome: one bar, and where the bottom bar went

There is **one** bar, at the top: home · URL chip · player button · tab counter ·
menu. The five-slot bottom bar is gone. It cost ~60dp of page height on every
screen, sat where the system gesture bar and most sites' sticky footers are, and
two of its five slots only ever produced a "coming soon" toast.

Its contents were redistributed, not deleted — if you're looking for one:

| was | now |
|---|---|
| forward / reload | quick-action strip at the top of the app menu |
| adblock shield | app menu row (ON/OFF pill) **and** a switch in Settings |
| bookmarks | app menu row + the star in that same quick strip |
| reader view | dropped — it was never implemented |
| tabs + counter | top bar (`btnTopTabs`, same `tabCount` TextView) |

**The window is `adjustNothing`, so anything you have to type into lives at the
TOP.** GeckoView does its own keyboard handling and reflowing the page under the
IME is worse than covering it — but that means nothing moves out of the
keyboard's way, ever. Find-in-page spent a release pinned to the bottom of the
window where the IME covered it completely, which read as "not implemented".
The omnibar drop-down is capped at 300dp for the same reason: rows below the
keyboard line can't be tapped. New chrome that takes focus goes under the
toolbar, not above the nav bar.

**A `wrap_content` view constrained top *and* bottom is centred.** That's
ConstraintLayout doing what it's told, and it parked the whole suggestion list
halfway down the screen. The bottom constraint has to stay — it's what
`layout_constrainedHeight` measures against — so the fix is
`layout_constraintVertical_bias="0"`, not removing the constraint.

Two consequences worth knowing before you "restore" something:

- **`applyVideoFocus` only hides `toolbarWrapper` + `toolbarDivider` now.** Any
  new chrome that should vanish in video focus has to be added there explicitly.
- **`BrowserToolbar` must be given 56dp. Never shrink it.** Its layouts are
  built for exactly that height and nothing in them re-centres:
  `mozac_browser_toolbar_displaytoolbar.xml` top-anchors every child with a
  hard-coded margin (8dp for the 40dp indicators, 4dp for the 48dp action
  containers — both land on the 28dp centre line of a 56dp bar and nowhere
  else), and the edit layout is a flat `layout_height="56dp"`. `onMeasure`
  honours an EXACTLY spec, so a 44dp container silently drags the URL text 6dp
  below the middle of the chip and clips the bottom of the star. That is what
  the "text sits low in the search field" bug was. The visible pill is 40dp
  because `bg_toolbar.xml` is an `<inset>`, not because the view is short —
  same 40dp slot the library reserves for its own `..._background` ImageView.
- **Nothing on a store tick may read uBO's state.** That lookup is async through
  the engine and firing one per tick ANRs the UI (see the AdblockController
  section above). The switch renders when the menu or Settings opens — that's
  the whole trigger list.

The app menu is a 236dp `PopupWindow` (down from 300dp) of 42dp rows. Row sizing
lives in `values/styles.xml`, not inline: it's eight visually identical rows and
the previous copy-paste version had drifted out of alignment with itself.

**The division of labour is: menu = acts on the page in front of you; Settings =
things you set once.** New per-page action → menu. New preference → Settings.
Desktop-site stayed in the menu despite being a toggle because it's per-tab.

## Bookmarks

Flat SQLite table, one row per URL, **no folders** — a tree needs a tree UI, a
move gesture and breadcrumbs, none of which fit this app. Star a saved page
again and it un-saves; `toggle()` returns the resulting state so the caller can
flip its icon without a second query.

The speed dial is backed by bookmarks, topped up from `QuickLink.SEED` to fill
the grid, deduped by URL. Saving one page must not blank the other seven tiles
on a fresh install — that's what the top-up is for. Anything that changes
bookmarks has to call `MainActivity.refreshStartPage()`; the star, the bookmarks
screen and a completed sync all do.

**A speed-dial tile IS a bookmark.** The "+" tile saves one; long-press removes
one. A separate "shortcuts" store would be the same rows under a second name and
the two would drift the first time either was edited. Removing a *default* tile
is the one asymmetry: there's no row to delete, so its URL goes into
`BrowserPreferences.hiddenQuickLinks` instead — and re-adding it has to clear
that tombstone or the tile silently never comes back.

Tile icons are real favicons through `BrowserIcons` (memory + disk cached, so
the network is touched once per site). The coloured letter stays underneath
rather than being swapped out, so a tile is never blank while a fetch is in
flight and never blank for a site with no icon at all.

## Omnibar suggestions

Typing in the URL bar drops down a list built from what the user has already
done: **bookmarks first**, then visited pages, then past search queries
([Suggestions.kt](app/src/main/java/com/upgrid/browser/search/Suggestions.kt)).

The order is fixed rather than scored. A bookmark is a page the user chose to
keep, so it is always the better guess than one they merely passed through;
ranking all three sources by a relevance score lets a page that got refreshed
twenty times outrank something deliberately saved. Duplicates are dropped by URL
as the list is built, so a bookmarked page that's also in history appears once,
as a bookmark.

Deliberately **not** `feature-awesomebar`: its suggestion providers are written
against `concept-storage`'s `HistoryStorage`, which is the Places-backed API this
project decided against (see below). A `RecyclerView` and two queries need no
new dependency.

Wired through `Toolbar.OnEditListener`, set in `onStart()` alongside the commit
listener — `ToolbarFeature.start()` runs on ON_START and installs its own, and
both are single-slot.

## Google account & sync

Sign in with Google, and bookmarks + history live in that account's Drive
**app-data folder** — hidden per-app storage under the `drive.appdata` scope. We
request that scope and nothing else: it cannot see, list or touch a single file
the user didn't create through this app. One JSON document, read-merge-write, no
server ([SyncPayload.kt](app/src/main/java/com/upgrid/browser/sync/SyncPayload.kt)).

**The merge is a union and never a subtraction.** Without a tombstone log,
"this URL isn't in the remote document" and "this URL was deleted on the other
device" are the same bytes. Guessing wrong destroys the user's bookmarks, so
deletions deliberately don't propagate. `visits` takes MAX of the two counts,
not the sum — summing re-adds the remote number every sync and a page visited
twice climbs into the hundreds inside a week.

### `GoogleSignIn` is deprecated, and that's deliberate

The build prints a wall of "GoogleSignIn is deprecated" warnings. Google's
replacement is Credential Manager, which handles *identity* — it hands back an
ID token and nothing else. Drive needs *authorization*: an OAuth scope and an
access token, which on that path means `AuthorizationClient` from the same
play-services-auth artifact, i.e. a second API on top rather than instead.

For one scope requested at one place in the app, `GoogleSignIn` +
`GoogleAuthUtil.getToken` is fewer moving parts and is not going anywhere soon
(it still ships in play-services-auth 21.x). Don't "modernise" this without also
wiring `AuthorizationClient` — swapping in Credential Manager alone gets you a
signed-in user and no way to reach Drive.

### Setting up the OAuth client (required, one-time)

Sign-in fails with `DEVELOPER_ERROR` (code 10) until an OAuth client in Google
Cloud Console matches this build's **application id + signing certificate**.
The settings sheet renders that specific code as "sign-in isn't set up yet"
rather than a bare number.

1. <https://console.cloud.google.com> → new project → **APIs & Services**.
2. Enable the **Google Drive API**.
3. OAuth consent screen: External, add the account as a **test user**.
   `drive.appdata` is a sensitive scope — testing mode is fine for a private
   build; a public release would need verification.
4. Credentials → OAuth client ID → **Android**:
   - package name `com.upgrid.browser.debug` (note the `.debug` suffix that
     `buildTypes.debug` appends — a release build is `com.upgrid.browser`)
   - SHA-1 `DA:81:89:45:70:EE:9B:5C:A1:27:04:E3:48:37:39:E0:7B:C1:3B:18`

That SHA-1 belongs to [keystore/upgrid-debug.p12](keystore/upgrid-debug.p12),
which is **committed on purpose**. Gradle otherwise generates a debug key per
machine, so the fingerprint would differ on every developer box and on any CI
runner that missed the cache — sign-in would work on one build and fail on the
rest. It signs debug builds only, its password is the conventional `android`,
and it grants nothing: Play uploads need the release key, which is not in this
repo. To regenerate (and then re-register the new SHA-1):

```bash
docker run --rm -v "$PWD/keystore:/ks" -w /ks eclipse-temurin:17-jre-alpine \
  keytool -genkeypair -keystore upgrid-debug.p12 -storetype PKCS12 \
    -alias upgrid -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass android -keypass android -dname "CN=Upgrid Browser Debug"
```

## History: why not `browser-storage-sync`

The phase-3 plan said Places. We shipped a plain SQLite table instead
([HistoryStore.kt](app/src/main/java/com/upgrid/browser/history/HistoryStore.kt)).
`browser-storage-sync` drags in the application-services megazord — tens of MB
of native code *per ABI*, stacked on GeckoView's — and pins yet another native
artifact to the a-c version we already bump by hand. We use none of what it
buys: no Firefox Sync account, no frecency ranking, no bookmark tree. Revisit
if Sync ever lands on the roadmap; until then a table is the honest tool.

Shape worth knowing: **one row per URL, not one per visit.** Re-visiting bumps
`visited_at` and increments `visits`. A visit log would bury everything else
the moment someone refreshes a page ten times. `record()` counts a visit;
`updateTitle()` exists so the late-arriving `<title>` (and SPA title rewrites)
don't each count as new visits. Everything suspends onto `Dispatchers.IO` —
`MainActivity.recordVisit` is called from the store observer, which ticks dozens
of times per page load.

## Build identity & delivery

`versionCode` is the git commit count and `versionName` is
`$baseVersion.$commitCount`, both resolved in
[app/build.gradle.kts](app/build.gradle.kts); `BuildConfig.GIT_SHA` carries the
short sha. Settings → About shows the pair, which is the first thing to ask for
in a bug report. Bump `baseVersion` there — CI greps that same line, so there's
nothing to keep in sync.

Three consequences to respect:

- **CI must check out with `fetch-depth: 0`.** A shallow clone counts 1 commit,
  so every build would be versionCode 1 and Android would refuse the upgrade.
- **Don't hand-edit `versionCode`.** It's derived; an edit is silently lost.
- **Debug builds are signed with the committed key**, not a generated one — see
  "Google account & sync" above. Don't "clean up" `signingConfigs`; it is load-
  bearing for sign-in, and swapping the key means re-registering its SHA-1.

[.github/workflows/android.yml](.github/workflows/android.yml) builds on every
branch and publishes the rolling `latest-debug` pre-release only from `main`.
Its Telegram step is a fallback for forks: this project's own bot has been
migrated to a self-hosted Bot API server, so the cloud API rejects its token and
the step is `continue-on-error`. Real delivery is a relay on the VPS that polls
the release and uploads the file — the public Bot API caps uploads at 50 MB and
these APKs are ~122 MB.

**[CHANGELOG.md](CHANGELOG.md)'s top `## ` section is user-facing text.** CI
copies it into the release body between `<!-- notes:start -->` markers, and the
relay slices it back out for the Telegram caption — so it's what the user reads
next to the APK they're about to install. Russian, 3–5 bullets, **only what is
visible in the app**: no signing keys, no architecture, no rationale. That
material belongs in the commit message and here. Commit subjects don't serve the
user-facing purpose either: they're English and they describe the code.
The section is matched by "first `## `", never by version number — the version
is the commit count, so it changes with the very commit that would add a heading
naming it.

Builds run in GitHub Actions, not on the VPS: the repo is public (free
unlimited minutes) and a Gecko-dependent Gradle build wants more RAM than that
box has spare.

## Testing notes

- **Real device > emulator** for GeckoView. The emulator x86_64 image works but is slow; many ad-blocker quirks only repro on real Chromium-vs-Firefox sites.
- Verify uBO is alive: load `https://d3ward.github.io/toolz/adblock` — it scores blocked items. Expected: 90+%.
- After bumping a-c version, smoke-test by loading youtube.com (cosmetic filtering), facebook.com (anti-circumvention), and a major news site.

## Sharing screenshots with Claude

UI work on this project goes screenshot ↔ feedback. **Anthropic's API rejects multi-image chats when any image exceeds 2000 px on any side**, and Android phones produce 1080×2400 screenshots — they trip the limit and corrupt the whole conversation.

**Workflow:**
1. Drop raw screenshots into [screenshots/](screenshots/) (descriptive names: `home-dark.png`, `tabs-tray-empty.png`).
2. Run [tools/Resize-Screenshots.ps1](tools/Resize-Screenshots.ps1) — downsizes anything >1900 px to fit, in-place, preserving aspect ratio.
3. Drag the resized files into the chat.

If Claude is asked to review UI and the user pastes a fresh phone screenshot, **first** point them at this workflow before the chat is at risk. Don't dump screenshots into the project root — they end up unowned junk and pollute Glob results.

## Design direction

The product target is **Banana Browser** (Google Play) — minimal chrome, big tap targets, single-purpose menu sheet, no power-user clutter. Not Firefox-for-Android, not Chrome. The "uBO is invisible" rule above is part of the same philosophy: hide complexity, surface one toggle. Don't add Firefox-style settings depth.

## Build issues cheat sheet

- "Could not find org.mozilla.components:..." → confirm `maven("https://maven.mozilla.org/maven2/")` in `settings.gradle.kts` `dependencyResolutionManagement`.
- "Caused by: java.lang.UnsatisfiedLinkError: ... libxul.so" → ABI filter mismatch; ensure your test device's ABI is in `splits.abi.include` in `app/build.gradle.kts`.
- "GeckoRuntime already running" / second-runtime crash → something created a `GeckoRuntime` outside `BrowserComponents`. Search the codebase for `GeckoRuntime.create`.
- IDE shows red on `mozilla.components.*` → invalidate caches; the Mozilla maven host is occasionally slow on first sync, which leaves an incomplete index.
