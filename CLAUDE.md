# CLAUDE.md

Guidance for Claude Code working on this repository.

## Mission in one line

GeckoView-based Android browser whose only "killer feature" is **uBlock Origin running silently under the hood**. Treat the extensions system as an implementation detail, not a user-facing feature.

## Stack snapshot

- **Engine**: GeckoView (Firefox), pulled transitively via `org.mozilla.components:browser-engine-gecko`
- **Frameworks**: Mozilla android-components 150.0.2 — pinned in [app/build.gradle.kts](app/build.gradle.kts) via `androidComponentsVersion`. **Every `org.mozilla.components:*` artifact must use the same version**, mismatches give cryptic linker errors at runtime.
- **AdBlock**: uBlock Origin (Mozilla-signed XPI from AMO, fetched at first launch)
- **Language**: Kotlin 2.0, Java 17 toolchain, AGP 8.7
- **min/target SDK**: 26 / 36
- **UI**: View system + ViewBinding (Compose later)

## Repo map

```
app/src/main/
├── AndroidManifest.xml             ← intent-filters for VIEW http(s), default browser
├── assets/extensions/upgrid_fullscreen/  ← the one bundled WebExtension: background.js relay +
│                                     player.js/observer.js (video) + find.js + translate.js + logins.js
├── java/com/upgrid/browser/
│   ├── BrowserApplication.kt       ← components; restore session; uBO bootstrap; autosave
│   ├── BrowserComponents.kt        ← single source of truth for runtime/engine/store/tabs
│   ├── MainActivity.kt             ← the single top bar + GeckoEngineView + the a-c
│   │                                 features. No bottom bar — see below.
│   ├── AdblockController.kt        ← thin façade for the AdBlock on/off toggle
│   ├── addons/AdblockBootstrap.kt  ← silent uBO install + version pin
│   ├── bookmarks/
│   │   ├── BookmarkStore.kt        ← SQLite, one row per URL, no folders
│   │   ├── BookmarkAdapter.kt
│   │   └── BookmarksActivity.kt    ← full screen, search, undo on delete
│   ├── download/
│   │   ├── DownloadManager.kt      ← copies the engine's response to disk; no a-c feature-downloads
│   │   ├── DownloadRecords.kt      ← the list, as JSON in prefs, exposed as a StateFlow
│   │   ├── DownloadsActivity.kt    ← full screen, live progress, open/delete
│   │   └── FileNames.kt            ← the engine's DownloadDelegate (Content-Disposition)
│   ├── fullscreen/
│   │   ├── VideoPlayerBridge.kt    ← native ⇆ extension port; takeover trigger
│   │   └── PlayerOverlayController.kt ← overlay buttons, seek bar, gestures, the lock
│   ├── history/
│   │   ├── HistoryStore.kt         ← SQLite visits table (one row per URL)
│   │   ├── HistoryAdapter.kt       ← day chips + rows
│   │   └── HistoryActivity.kt      ← full screen, search, clear-all
│   ├── home/                       ← speed-dial start page (bookmarks, topped up from SEED)
│   ├── logins/
│   │   ├── LoginStore.kt           ← AES-GCM under an Android-keystore key
│   │   └── LoginsActivity.kt       ← the list; passwords only behind a tap
│   ├── menu/AppMenuPopup.kt        ← 236dp drop-down menu (PopupWindow, not BottomSheet)
│   ├── prefs/BrowserPreferences.kt ← typed SharedPreferences façade (all settings)
│   ├── privacy/
│   │   ├── BrowsingDataCleaner.kt  ← periods, data types, cache size
│   │   ├── ClearDataDialog.kt      ← the picker, generated from the enums
│   │   └── SiteDataActivity.kt     ← which sites have data, per-site clear
│   ├── search/                     ← SearchEngine, SearchHistory, omnibar Suggestions
│   ├── settings/SettingsBottomSheet.kt ← account, adblock, search, staying open, player,
│   │                                     data, about
│   ├── sync/
│   │   ├── AccountSync.kt          ← GoogleAccounts (sign-in) + SyncEngine (merge loop)
│   │   ├── DriveAppData.kt         ← the four Drive v3 calls, over HttpURLConnection
│   │   └── SyncPayload.kt          ← the versioned JSON document
│   ├── tabs/
│   │   ├── TabsActivity.kt         ← list of open tabs, store-driven, swipe to close
│   │   ├── TabViewHolder.kt
│   │   └── WindowRequests.kt       ← target=_blank / window.open → a tab (or not)
│   ├── vpn/
│   │   ├── VpnController.kt        ← com.wireguard.android:tunnel, one per process
│   │   ├── VpnSettings.kt          ← the profile as fields; wg-quick in and out
│   │   └── VpnActivity.kt          ← the form, the switch, the key pair
│   └── ui/
│       ├── HostTile.kt             ← per-host letter + color, shared by every list
│       ├── SiteIconView.kt         ← that letter with the real favicon over it
│       ├── PullToRefreshLayout.kt  ← takes the pull back from GeckoView at scroll 0
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

## The player's lock owns the navigation bar; video focus owns the status bar

The two system bars are **not one decision**, and `refreshSystemBars()` exists
to keep them apart:

- **The status bar goes as soon as a video takes over.** A clock and a battery
  meter over a film is the single thing that makes the player read as a web page
  with its chrome hidden rather than as a player, and there is nothing up there
  to navigate with.
- **The navigation bar stays until the 🔒.** Losing back and home the instant a
  video opens is disorienting and there is no visible way to ask for them back.
  The lock is a real lock — control bars away, every gesture swallowed, one
  floating unlock button left, back unlocks rather than exits — and it is also
  the moment the user has said they won't be touching anything.

PiP overrides both: the system shrinks us to a thumbnail where a navigation bar
would be most of the window.

The state is derived rather than passed: `hideAll = inVideoFocus && (locked ||
isInPipMode())`, `hideStatus = inVideoFocus`. Three independent things move it —
entering or leaving video focus, the lock, the PiP transition — and they arrive
in any order. Note the status-bar-only branch keeps `fitsSystemWindows` on, so
the navigation bar's inset still pads the root and the overlay's bottom controls
stay above it; only the full-hide branch clears the padding by hand (see the
MIUI note in the code).

`applyVideoFocus` also paints the root black. A video almost never matches the
shape of a phone, so there is always a band above and below it, and on a light
theme that band was the app's white surface — the brightest thing you can put
next to moving picture.

**The navigation bar has to be painted too, separately.** Painting the root is
not enough: the system draws the navigation strip itself, in the colour the
theme names (`android:navigationBarColor` → `?attr/colorSurface`), *over* our
black root. One shade off black under a player is exactly the seam the eye
locks onto, and it is the difference the user photographed next to another
browser. `applySystemBarColors` sets both bars to black in video focus and back
to `colorSurface` on the way out, and moves `isAppearanceLight*Bars` with them —
on the light theme the bars are told to draw dark icons, which on black means no
back button at all. The light flag is derived from the surface colour's
luminance rather than read back from the theme, so it cannot drift out of sync
with the colour set two lines above it. (On API 35+ both setters are no-ops
under enforced edge-to-edge, where the black root shows through anyway — the
code is correct either way, which is why there is no version branch.)

**Rotation is the player's, not the browser's.** Every activity is
`screenOrientation="portrait"` in the manifest. Turning the phone while reading
used to rotate the whole browser, and every screen in it is a portrait layout
stretched sideways; worse, rotating for a video left the *browser* sideways
after the video ended. The player's own rotate button calls
`toggleFsOrientation()`, and `applyVideoFocus(false)` puts
`SCREEN_ORIENTATION_PORTRAIT` back — so landscape exists exactly as long as the
video does. `configChanges` already lists `orientation`, so none of this
rebuilds the activity.

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
- **Features are the glue.** `SessionFeature` renders the selected tab into `GeckoEngineView`; `SwipeRefreshFeature`, `FullScreenFeature`, `ContextMenuFeature` and our own `WindowRequests` handle the rest. All are bound through `ViewBoundFeatureWrapper` so they stop/start with the view lifecycle. **`ToolbarFeature` is deliberately not among them** — see "The top bar does not belong to the page".
- **`BrowserApplication.restorePreviousSession`** restores tabs *before* the bootstrap installs uBO — this guarantees tabs are visible the moment the user sees the activity even if AMO is unreachable.
- **Tab close → empty state:** `MainActivity.wireBackPress` finishes the activity when the last tab is closed via the system back button. The tabs screen does *not* close itself when `tabs.isEmpty()` — it shows an empty illustration. `TabsActivity.finish()` is overridden to open a fresh HOME tab when the list is empty, because leaving with zero tabs would drop MainActivity onto an unrendered engine view. It's on `finish()` rather than in a click handler since three paths reach it: the back arrow, the back gesture, and picking a tab.
- **App menu is a `PopupWindow`, not a BottomSheet.** [AppMenuPopup](app/src/main/java/com/upgrid/browser/menu/AppMenuPopup.kt) is a 236dp drop-down anchored to `btnTopMenu` via `showAsDropDown(anchor, 0, 0, Gravity.END)`. Construct a new instance per tap (cheap, avoids stale toggle state) — but note `PopupWindow` keeps its content view between shows, so anything derived from browser state is re-read in `showFrom`, not at construction.
- **History, bookmarks and tabs are Activities, not sheets.** A sheet gave each list whatever height was left over and put a drag handle where a back arrow belongs. They dispatch to the shared `BrowserStore` and finish — no results to hand back, nothing for MainActivity to keep in sync. They share [view_page_header.xml](app/src/main/res/layout/view_page_header.xml) and [view_page_search.xml](app/src/main/res/layout/view_page_search.xml) via `<include>` so the three can't drift apart. Settings is still a sheet ([ExpandedBottomSheetFragment](app/src/main/java/com/upgrid/browser/ui/ExpandedBottomSheetFragment.kt)) — it's a flat list of switches with no navigation inside it.
- **Tabs are cards *or* a list, and the header switches them.** Both are right some of the time — cards are how you pick one tab out of six, a list is how you get through thirty without every title cut to three words — so this is a preference the user sets by using it (`BrowserPreferences.tabsGrid`, cards by default) rather than one buried in Settings. One RecyclerView either way; only the layout manager and the item view type change. Two consequences: swipe-to-close is list-only (`getSwipeDirs` returns 0 in the grid, where a sideways swipe reads as scrolling between columns), and the recycled view pool is cleared on every switch because it is keyed by view type and would otherwise sit on inflated cards while the list wants rows. `MainActivity.captureCurrentThumbnail` — a full-window bitmap on every `onPause` — is gated on the same preference, so choosing the list turns the cost off entirely.
- **The bookmark star is a toolbar page action**, not a sixth button. Four 44dp targets plus the video button already leave the URL under half a phone screen. `Toolbar.ActionToggleButton` owns its own selected state and only repaints on `invalidateActions()`, so `renderBookmarkAction` drives it and memoises the last URL it looked up — the store observer fires many times per load and each miss is a database round-trip.
- **One site looks the same everywhere.** [SiteIconView](app/src/main/java/com/upgrid/browser/ui/SiteIconView.kt) is the leading square in every list — history, bookmarks, tabs, downloads, saved passwords, site data, the omnibar drop-down. It stacks the real favicon **on top of** [HostTile](app/src/main/java/com/upgrid/browser/ui/HostTile.kt)'s letter rather than swapping it in: the letter is there the instant the row binds, the icon covers it when it arrives, and a site with no icon still looks like something. The hash behind the colour is computed by hand rather than via `String.hashCode()` so the colours can't reshuffle between releases. `BrowserIcons.loadIntoView` is not used — in a-c 150 it nulls the ImageView before its fetch starts, which flashes an empty square for every row on the first scroll. Icons whose `source` is `GENERATOR` are dropped: that is BrowserIcons drawing its own letter tile, and we already have one.

## Chrome: one bar, and where the bottom bar went

There is **one** bar, at the top: home · URL chip · player button · new tab ·
tab counter · menu. The five-slot bottom bar is gone. It cost ~60dp of page height on every
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
The omnibar drop-down is capped (336dp) for the same reason: rows below the
keyboard line can't be tapped. New chrome that takes focus goes under the
toolbar, not above the nav bar.

**`findBar` and `translateBar` share one slot** under the divider, and both push
the page down when visible. Opening either closes the other — stacking them
would take 100dp off the page and neither is a mode you're in twice.

**All of it lives in one `chrome` container, and the page is not under it.**
The bar, the progress line, the hairline and the three page bars are children of
a single vertical LinearLayout; `swipeRefresh` (and `pageCover`) are constrained
to the *whole* window and slid down by `chrome.height` — see
`MainActivity.applyChromeOffset`. That inversion is what makes hiding the bar on
scroll cheap: the engine view is never re-measured, so GeckoView is never
resized, so the page never reflows and never jumps. Anything added to the chrome
goes inside that container, and its height is accounted for automatically by the
layout listener.

Two calls make Gecko agree with the geometry:
`setDynamicToolbarMaxHeight(toolbarHeight)`, so `100vh` is computed for the
bar-hidden case once and stays put, and `setVerticalClipping(offset)`, so a
site's own bottom-fixed bar sits above the screen edge rather than below it —
the page is `chrome.height` taller than the window and hangs off the bottom.

**The bar hides on the way down and comes back on the way up.** Driven by
`EngineSession.Observer.onScrollChange` (the only reliable source: a touch on
the engine view tells us nothing about whether the page moved), through
`onPageScrolled`, which accumulates a *directional run* rather than summing
deltas — a single scroll event is a few pixels and the sum of a scroll down and
back up is zero. Thresholds are asymmetric (24dp to hide, 8dp to show) and the
top of the document always shows it. It never hides while the address bar is
being typed into or while find/translate is open; see `canHideToolbar`.

There is **no slide animation**, deliberately. Moving the engine view every
frame means moving a SurfaceView every frame, and its surface follows the view
by about a frame — the page would tear away from the bar for the length of the
animation. One frame of that is invisible, twelve are not.

**A `wrap_content` view constrained top *and* bottom is centred.** That's
ConstraintLayout doing what it's told, and it parked the whole suggestion list
halfway down the screen. The bottom constraint has to stay — it's what
`layout_constrainedHeight` measures against — so the fix is
`layout_constraintVertical_bias="0"`, not removing the constraint.

Two consequences worth knowing before you "restore" something:

- **`applyVideoFocus` hides the whole `chrome` container.** That is now one line
  rather than a list of views, and the page moves to the top of the window by
  itself because the offset is derived from the container's height. It also
  un-hides the top bar on the way out: a bar scrolled away before the video
  started would otherwise still be away after it ends, with nothing to scroll
  back up with.
- **`BrowserToolbar` must be given 56dp. Never shrink it.** Its layouts are
  built for exactly that height and nothing in them re-centres:
  `mozac_browser_toolbar_displaytoolbar.xml` top-anchors every child with a
  hard-coded margin (8dp for the 40dp indicators, 4dp for the 48dp action
  containers — both land on the 28dp centre line of a 56dp bar and nowhere
  else), and the edit layout is a flat `layout_height="56dp"`. `onMeasure`
  honours an EXACTLY spec, so a short container silently drags the URL text
  below the middle of the chip and clips the bottom of the star. The visible
  pill is 40dp because `bg_toolbar.xml` is an `<inset>`, not because the view is
  short — same 40dp slot the library reserves for its own `..._background`
  ImageView.
- **An `<inset>` drawable used as a background silently pads the view.**
  `InsetDrawable.getPadding()` reports its insets as padding, and `View`'s
  constructor adopts the background's padding for every edge the layout doesn't
  name (`internalSetPadding(..., topPadding >= 0 ? topPadding : mPaddingTop, ...)`).
  So `toolbarPill` — a 56dp box with `bg_toolbar` behind it — was handing
  `BrowserToolbar` only 40dp, positioned 8dp down. The URL text stayed centred
  (its `OriginView` is constrained top *and* bottom) while the site-info icon
  and the bookmark star, both anchored to the parent's top, sat exactly 8dp low.
  That is what "иконки кривые" was, twice. `toolbarPill` now names
  `paddingTop="0dp"` and `paddingBottom="0dp"`; do not remove them. Insets still
  control where the drawable *paints*, so the pill still looks 40dp.
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

## Page features live in the page (find, translate)

Find-in-page and translation are **content scripts**, not engine calls. Both
ship in the player extension (`assets/extensions/upgrid_fullscreen/`) and both
talk over the same native-messaging port the player uses — one pipe, three
streams, separated by the `t`/`cmd` verb.

| file | runs in | verbs it answers |
|---|---|---|
| `observer.js` | every frame | `pauseAll` |
| `find.js` | top frame | `find`, `findNext`, `findPrev`, `findClear` |
| `translate.js` | top frame | `translate`, `untranslate`, `translateState` |

Why not the engine's own finder: it paints matches with an internal selection
colour **no CSS can reach** (so "orange like Chrome" is not a setting), and it
skips subtrees the page never laid out — feeds that mark off-screen items
`content-visibility: auto` (Reddit, Habr) returned "not found" for words plainly
visible further down. A DOM walk sees them; `checkVisibility()`'s defaults
deliberately do *not* treat `content-visibility: auto` as hidden.

The highlight CSS is declared in the manifest's `content_scripts.css`, not
injected as a `<style>` element: extension stylesheets bypass the page's CSP,
and appended `<style>` tags don't — GitHub and Reddit block them, and those are
exactly the sites people search in.

Why not `*.translate.goog` for translation: the proxy rate-limits by IP (a phone
on mobile data gets a captcha) and pins Google's own banner over the page. The
text now goes to `translate.googleapis.com/translate_a/t?client=dict-chrome-ex`
— the endpoint Chrome's own translation uses, no key, one
`[translation, detectedLang]` pair per `q` in order — and is written back into
the nodes it came from. The **background page** makes that call: a content
script's `fetch` carries the page's origin and CORS refuses it.

**Routing needs `markActiveForWebExtensions`.** `browser.tabs.query({active:true})`
is how background.js aims these at the foreground tab, and GeckoView answers
"none" unless the embedder marks sessions active — `WebExtensionSupport` would,
but it brings a whole tab-sync layer we don't run.
`MainActivity.markSelectedTabActive()` does it directly on selection and on
navigation (the engine session is created lazily, so at selection time there may
be nothing to mark yet). background.js falls back to broadcasting when the query
comes back empty.

**`VideoPlayerBridge.sendCommand` queues while the port is down.** background.js
connects on its first outbound message, which is observer.js's opening media
report — about a second after load. Opening find-in-page faster than that used
to be silence.

## Downloads

No `feature-downloads`. GeckoView has **already made the request** by the time it
decides it can't render a response: `onExternalResponse` hands a-c a `WebResponse`
that becomes `DownloadState.response`, an open stream with the page's cookies
behind it. So `DownloadManager` copies that stream and nothing else is needed —
a-c's version wraps the same copy in a foreground service, a notification
channel and three prompt dialogs we would then have to restyle.

Three things to keep in mind:

- **Consume last.** `ContentAction.ConsumeDownloadAction` *closes*
  `DownloadState.response`. Dispatch it after the copy, never before, and keep a
  set of ids already started — the store keeps `content.download` set until it is
  consumed, so every state tick in between would start the file again.
- **Where the bytes go.** MediaStore's Downloads collection on Android 10+ (no
  permission, shows up in the system's own list); the app's external files dir
  below that, because shared storage there needs `WRITE_EXTERNAL_STORAGE` and a
  runtime prompt on top of a download the user already asked for. The
  `FileProvider` (`${applicationId}.files`) exists only for that second case.
- **The filename lives in `Content-Disposition`,** which never reaches
  `DownloadState`. `DefaultSettings.downloadDelegate` is asked for it at exactly
  the right moment; ours is `download/FileNames.kt`. With no delegate the engine
  hands back null and every file is named after its URL's last segment.

## Passwords

`logins.js` in the same content script bundle as find and translate: capture on
`submit` **and** on a click that looks like one (frameworks that navigate by
script never fire submit), fill on load after announcing that the page has a
password field. Values are written through the prototype's own `value` setter —
React installs its own and ignores plain assignment, so the field would look
filled and submit empty.

Storage is AES-256-GCM under a key generated in the Android keystore, one
encrypted JSON blob in app-private prefs (`logins/LoginStore.kt`). If the key
ever becomes unusable — restoring a device copies the file but not the keystore —
the store resets itself rather than refusing to start. `androidx.security:security-crypto`
does the same job with a dependency and more machinery.

One switch (`BrowserPreferences.savePasswords`) gates both halves. A browser that
keeps filling passwords after you turned saving off is not honouring the switch.

## Clearing data, and what the period really means

`Engine.clearData(data, host)` is everything the engine offers: all of it, or all
of one host's. **There is no time range** — Gecko's sanitizer has one, GeckoView
does not expose it. So `BrowsingDataCleaner`:

- deletes our own tables (history, searches) by timestamp, exactly;
- for "all time", clears the engine's data outright;
- for a bounded period, clears it **per host, for the hosts visited in that
  period** — which is the same answer for anyone whose question was "forget where
  I've been since Monday".

The dialog says so in as many words. The site list is built from history for the
same reason: the engine can delete a host's data but cannot enumerate hosts that
have any.

## The browser's own account

Separate from the Google account, and not a replacement for it: Google syncs
bookmarks and history, this one says who you are to *this* browser and what it
is allowed to set up for you. Today that means the VPN profile — sign in and the
tunnel is configured, which is the whole reason the account exists.

Sign-in has two halves and either can carry it
([AccountController](app/src/main/java/com/upgrid/browser/account/AccountController.kt)):

- **The account server**, when it answers. HTTP basic auth over TLS against a
  static JSON per account. It is the authority on **profiles** — the VPN config
  only ever comes from there — but not on whether you may open your own browser.
- **The device**, otherwise. A PBKDF2-SHA256 hash stored in the same
  keystore-backed AES-GCM box as saved website passwords, seeded with the
  account the owner asked for. The browser is never locked out by a network, and
  a profile fetched earlier stays in place.

Two rules that took a round of "I can't sign in" to get right:

- **A 401 is not a verdict.** The server answers exactly the same way to a login
  it has never heard of, to a path that belongs to some other site on the same
  host, and to a setup nobody has run yet — so a rejection falls through to the
  device's copy instead of ending the attempt. Believing it meant a browser its
  owner could not sign in to with the correct password.
- **The local path is a success, not a warning.** Signing in closes the screen
  and says "welcome" either way. It used to stay open with *"server unreachable
  — signed in on this phone"* under the button, which is a true sentence about
  our plumbing and, to the person reading it, indistinguishable from a refusal.
  Whether a VPN profile arrived is a different question and it is answered on
  the VPN screen, which has room to say what to do about it.

Seeding is on the entry (`accounts.has(DEFAULT_LOGIN)`), not on a first-run flag,
and it happens inside `verifyLocally` rather than in an `init` block: PBKDF2 at
120 000 iterations is a few hundred milliseconds of CPU by design, and in `init`
that landed on whichever thread first touched `components.accounts` — the main
one, from the app menu. `AccountController.signIn` wraps the whole local half in
`Dispatchers.IO` for the same reason.

The server side is four things in one script
([tools/account-server-setup.sh](tools/account-server-setup.sh)): a WireGuard key
pair, a peer on the running interface, the profile as JSON, and an nginx server
block serving it behind `auth_basic`. No application to deploy — the "API" is a
file, because the only question being asked is "here are my credentials, what is
my profile?".

**Nothing secret is in this repository.** It is public. Keys live on the server
and reach the phone through the account, never through the build; a bundle
committed here would be a private key on GitHub whatever it was wrapped in.

**The seeded password is not a secret either, and that has a consequence.**
`AccountStore.DEFAULT_PASSWORD` ships in every APK and is readable in this repo,
and the same credentials the user types are what fetch the VPN profile — so an
account left on its out-of-the-box password is an account whose profile anyone
who read the source can download, and a peer on the owner's WireGuard server is
what they get. There is no way around it that keeps "install and sign in" with
zero input: anything baked into a public build is public. The setup script
therefore refuses to default the *server* password, and the owner is told in as
many words that the pair is only as private as the weakest half. Changing it is
one run of the script plus typing the new password once.

## VPN

`com.wireguard.android:tunnel` — WireGuard's own embeddable backend (wireguard-go
plus the config parser). It declares `GoBackend$VpnService` in its own manifest,
so **do not declare a second one** in ours; the merger folds it in and a
duplicate fails the build.

- `VpnController` is one per process. Two backends would fight over one
  VpnService, and the tunnel outlives every screen.
- `setState` is blocking (DNS resolution with retries) — always off the main
  thread.
- **`VpnService.prepare` needs an Activity.** It returns an Intent the user has to
  accept once per install, so connecting lives in `VpnActivity` and in
  `MainActivity.toggleVpn` (the app menu is a PopupWindow with no lifecycle to
  register a result contract against). `VpnController` never asks.
- The profile is stored as fields and rendered to `wg-quick` text on connect.
  Three ways in, all landing in `applyConfig`: the account fills it, a config is
  pasted, or a `.conf` file is picked. The file picker uses a wildcard filter —
  `.conf` has no registered mime type, so Android calls it
  `application/octet-stream` on one device and `text/plain` on the next, and
  filtering on either hides the file the user came for. `Config.parse` validates
  at connect time, where a bad value can be reported as one.
- **The status notification is posted from `BrowserApplication`, not a screen.**
  The tunnel outlives every Activity, and the notification has to still be
  correct — and its Disconnect button still reachable — after the browser is
  swiped out of recents. `POST_NOTIFICATIONS` is asked for at the moment the
  user connects, never on first launch; refusing it changes nothing about the
  tunnel, so `VpnNotifications` treats "not allowed" as a normal state and
  posts nothing.
- **"Connected" is not the interesting half — the speed is.** A tunnel that is
  up and carrying nothing looks identical to a healthy one until you can see
  numbers move. `VpnStatus` samples `backend.getStatistics` every two seconds
  while the tunnel is up and turns the totals into a rate; the notification, the
  app menu's VPN row and the VPN screen all render the same `Snapshot`, so they
  cannot disagree. The sampling loop lives inside a `collectLatest` on the
  tunnel state, so a disconnect cancels it rather than leaving a timer polling a
  backend with nothing to say. `elapsedRealtime`, not wall clock: a rate divided
  by a wall-clock delta goes wrong the one time the phone's clock is corrected.
  The first sample has no rate to report, only the absence of one — hence
  `Snapshot.sampled`, and no row of zeroes under a shield that just went green.
- **The notification channel id changed, and it had to.** A channel's importance
  is fixed at creation; an app cannot raise it later. The first version shipped
  `IMPORTANCE_LOW`, which can never produce the pop-up the user asked for, so
  `upgrid_vpn` was replaced by `upgrid_vpn_status` at `IMPORTANCE_DEFAULT` and
  the old id is deleted on first render. `setOnlyAlertOnce(true)` is what keeps
  that from meaning a heads-up card every two seconds: it announces itself when
  the tunnel comes up and is silent for every update after.
- `VpnFormat` exists so the three places that print bytes print them the same
  way, through the platform formatter, in the phone's locale.

## Surviving the background

"I switched away for a second and the page had to load again" is a memory
problem, not a lifecycle one. It is also the single most-reported bug in this
project, so the answer is spread across four places and it is worth knowing all
four before touching any of them.

**Say which tab matters.** GeckoView renders each page in its own content
process, and Android kills the cheapest-looking process first — a large, idle
one belonging to an app that isn't on screen looks very cheap indeed. a-c ships
`SessionPrioritizationMiddleware`, which sets the selected tab's session to
`PRIORITY_HIGH` (`GeckoSession.setPriorityHint`) and everything else to default;
Gecko then keeps that child process at foreground importance instead of letting
it fall to "empty" the moment the app is backgrounded. **It is not part of
`EngineMiddleware.create()`** — Fenix adds it by hand and so do we, in
`BrowserComponents.store`. For six rounds we simply were not asking for it, and
every "the page reloaded" report traces back through a tab that came back
`crashed`. It also keeps a tab holding half-filled form data at high priority
for three minutes after you leave, which needs `AppLifecycleAction` to be
dispatched — `BrowserApplication.watchAppLifecycle` does that off
`ProcessLifecycleOwner`.

**Give memory back before the system takes the process.**
`BrowserApplication.onTrimMemory` dispatches `SystemAction.LowMemoryAction` and
calls `BrowserIcons.onTrimMemory`. `EngineMiddleware.create` already installs
`TrimMemoryMiddleware`, which suspends the engine sessions of tabs nobody is
looking at — it had simply never been told there was any pressure, because
nothing dispatched the action it listens for. Tab thumbnails (up to 6 MB of
decoded bitmaps of pages that are still open) are dropped at
`TRIM_MEMORY_BACKGROUND` and up. The `isMainProcess` guard matters more here
than in `onCreate`: GeckoView's content processes get this callback too,
`components` is lazy, and touching it there would build a second BrowserStore
and a second GeckoEngine inside a child process — triggered by low memory, of
all things.

**Bring back what the system took anyway.** When Android reclaims a content
process, GeckoView reports it, a-c marks the tab crashed and suspends its
session, and there it stops — correct for Firefox, which then shows a "restore
this tab?" page, and wrong for a browser without one: the tab came back blank.
`MainActivity.restoreCrashedTabs` collects the set of crashed tab ids off the
store and dispatches `CrashAction.RestoreCrashedSessionAction`. That is not a
reload: the session's saved state went into the store on the way down and
`CreateEngineSessionMiddleware` calls `restoreState` with it, so history, scroll
position and form state come back and the engine re-fetches only what it must.
Bounded per tab (`MAX_CRASH_RESTORES`), because a page that takes the content
process down on sight would otherwise be restored, crash and be restored again
forever. The collector lives inside `repeatOnLifecycle(STARTED)`, so nothing
spawns a content process while the browser is in the background.

**And past that it is not ours to fix.** Some phones — MIUI above all — kill
backgrounded apps far more eagerly than stock Android, and no amount of
priority hinting survives a manufacturer's task killer. The only switch that
does is the battery exemption, and it belongs to the user, so Settings has one
row that reports what the system is currently doing and opens the system's own
dialog (`Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, which is why
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is in the manifest). **The browser never
asks by itself** — no prompt on launch, no banner. The row is there for the
moment somebody goes looking for why, and a browser that nags for a permission
on startup is exactly the kind of app this one is trying not to be.

## Cards and rows: the shape of every settings-ish screen

Settings, the VPN screen and sign-in share one layout language, defined in
[values/styles.xml](app/src/main/res/values/styles.xml): a stack of cards, each
card a group of 60dp rows, each row an icon in a tinted circle, a title, a
subtitle and one control on the right. Screen background `colorSurface`, cards
`colorSurfaceContainerHigh` — that ordering is right in both light and dark,
which the obvious alternative (container background, surface cards) is not.

Two rules keep new rows from drifting:

- **A subtitle answers "what does this do / what is it set to now."** Never
  leave a row whose subtitle repeats its own title. The VPN row says whether the
  tunnel is up and through what; the data rows say how much there is. A row that
  only reads "Site data" makes you open it to find out whether there's anything
  to do.
- **Same icon in the menu and in Settings for the same thing.** The leading
  circle is the row's identity.

What this replaced was a single column of section captions with loose text and
buttons floating between them at four different indents. Nothing was wrong with
any individual row; the screen had no structure, so everything read as equally
important and none of it looked finished.

## The brand mark

One path, three uses. `ic_launcher_foreground` is a shield with an arrow cut
through it by `fillType="evenOdd"` — the arrow is a *hole*, not a second shape,
which is what lets the same geometry serve as the launcher foreground (over
`bg_launcher`'s gradient), as the Android 13 `monochrome` layer (over whatever
colour the system themes it) and as `ic_brand_mark` on the sign-in screen,
without three versions drifting apart. Everything sits inside the 66 × 66 safe
zone of the 108 × 108 adaptive-icon canvas; outside it the launcher's mask may
crop.

minSdk is 26, so `mipmap-anydpi-v26` is the whole story — there are no PNG
densities to keep in step and nothing older to fall back for.

## Pull to refresh

`SwipeRefreshFeature` (feature-session) drives the reload and the spinner, but
the layout is ours:
[PullToRefreshLayout](app/src/main/java/com/upgrid/browser/ui/PullToRefreshLayout.kt).
The **`GeckoEngineView` has to be its direct child**: both the feature and our
layout identify the page with `child is EngineView`, and anything else answers
"it can still scroll", which disables the gesture outright. The start page is a
sibling *over* the refresh layout and `swipeRefresh.isEnabled` is false while
it's showing.

**Why a subclass at all.** Plain `SwipeRefreshLayout` under GeckoView only works
on one kind of page. `NestedGeckoView` claims every gesture on ACTION_DOWN —
`requestDisallowInterceptTouchEvent(true)` plus `startNestedScroll` — and
releases it only once Gecko has answered, and only when the answer is
`INPUT_HANDLED` with can-overscroll-top: a long, plain document already at its
top. Two ordinary answers never release it:

- `INPUT_UNHANDLED` — nothing on the page scrolls, i.e. any page shorter than
  the screen;
- `INPUT_HANDLED_CONTENT` — the site has its own touch listeners, i.e. most of
  the modern web.

With the gesture claimed, both routes into `SwipeRefreshLayout` are shut:
`onInterceptTouchEvent` is skipped because interception was disallowed, and the
nested-scroll path is dead because `NestedGeckoView` only forwards scrolls it is
itself performing (`allowScroll` requires `isTouchHandledByBrowser()`). The pull
did nothing, silently, and read as a missing feature.

So: **at the top of the page the pull is ours.** `pageAtTop` is sampled once per
gesture at ACTION_DOWN from `EngineView.canScrollVerticallyUp()` — the engine's
own scroll position, not the async input result — and while it holds we refuse
the nested-scroll handshake and ignore the disallow. The child-scroll-up
callback is replaced for the same reason and **must be set after** constructing
`SwipeRefreshFeature`, whose `init` installs its own.

The trade: on a page scrolled to the top that wants to handle a downward drag
itself (a canvas, a map), refresh wins. Same trade Chrome makes.

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
done — **bookmarks first**, then visited pages, then past search queries — plus
what the search engine thinks they're typing
([Suggestions.kt](app/src/main/java/com/upgrid/browser/search/Suggestions.kt)).

The order is fixed rather than scored. A bookmark is a page the user chose to
keep, so it is always the better guess than one they merely passed through;
ranking all three sources by a relevance score lets a page that got refreshed
twenty times outrank something deliberately saved. Duplicates are dropped by URL
as the list is built, so a bookmarked page that's also in history appears once,
as a bookmark.

**The two halves run in parallel, and that is load-bearing.** `local()` is three
SQLite reads and `completions()` is a network round-trip; `MainActivity` starts
the second with `async` *before* awaiting the first, renders the local half as
soon as it lands, and merges the engine's when it arrives. Chained the other way
round — which is how it shipped, and why "поисковик не показывает варианты" was
a real complaint — the request wasn't even sent until the local reads came back,
so on anything but a deliberate pause the next keystroke cancelled the job
before the engine had been asked at all. The debounce is 180 ms for the same
reason: the whole round-trip has to fit between two keystrokes.

`local()` runs on `Dispatchers.IO`. It used to be a suspend function that never
left the caller's dispatcher, so three database reads happened on the main
thread on every keystroke, while the engine was laying out a page.

**The "search for what I typed" row is produced by the local half**, not the
remote one, so the drop-down offers the search engine instantly and can never
come back empty. It goes *first* when the input reads as words and last when it
reads as a hostname — same `looksLikeQuery` rule the address bar applies on
commit, and it has to stay the same one, or the list offers to search for
something the bar would have loaded.

`SearchSuggestionClient` sends a browser User-Agent rather than the fetch
layer's default `MozacFetch/<version>`, keeps a small LRU of recent answers (a
backspace walks back over queries just asked), and waits 5 s — the first
completion of a session pays DNS plus a TLS handshake, and that is exactly the
one that decides whether the user believes the feature exists.

**Two rows are the same row in two ways**, and `Suggestions.Dedup` checks both.
Same destination: `https://ya.ru`, `http://www.ya.ru/` and `ya.ru/#top` are one
page, and bookmarks store whatever the address bar held while history stores what
the page settled on — they differ by a trailing slash more often than not, which
is how the same site used to appear twice. Same row: even when the URLs genuinely
differ, two entries from one host with the same title read as one thing repeated
(a feed and its front page are both "Хабр"), and nine slots shouldn't hold three
of them.

**The field says which engine will answer.** `EditToolbar.setIcon` puts a
magnifier in the engine's `brandColor` at the left of the edit field, the hint
reads "Поиск в Yandex или адрес", and tapping the icon opens the picker that
writes the same preference Settings does. Re-rendered on `onStartEditing`, not
on `onResume`: the Settings sheet is a `DialogFragment` and can change the
engine without this activity ever pausing.

Deliberately **not** `feature-awesomebar`: its suggestion providers are written
against `concept-storage`'s `HistoryStorage`, which is the Places-backed API this
project decided against (see below). A `RecyclerView` and two queries need no
new dependency.

Wired through `Toolbar.OnEditListener`, set once in `onCreate` — see below for
why that used to have to be `onStart`.

## The top bar does not belong to the page

**There is no `ToolbarFeature`, and adding one back will break typing.**

`ToolbarPresenter.render()` runs on every store tick — every progress update,
title change, favicon, security-info arrival — and one of the things it does is
`toolbar.setSearchTerms(tab.content.searchTerms)`. `BrowserToolbar.setSearchTerms`
is not a display-only setter:

```kotlin
override fun setSearchTerms(searchTerms: String) {
    this.searchTerms = searchTerms.trimmed()
    if (state == State.EDIT) {
        edit.editSuggestion(this.searchTerms)   // → views.url.setText(...)
    }
}
```

A tab that wasn't opened by a search has **no** search terms, so that is
`setText("")` into the field the user is typing in, followed by
`editListener.onTextChanged("")`, which empties the drop-down too. On a fresh
`about:blank` tab the store is quiet and typing works; on any loaded page the
text deleted itself mid-word. That is the whole of "поиск не работает на
открытой странице" and "текст автоудаляется", and it was two separate bug
reports before the cause was found.

`BrowserToolbar` is not `open`, so it can't be subclassed to neuter that one
method. Instead `MainActivity.renderChrome` renders the three things the
presenter gave us — `toolbar.url`, `toolbar.siteInfo`, and our own progress bar
— comparing before assigning, because neither setter checks and this runs on
every tick. `ToolbarFeature.onBackPressed()` was just `toolbar.onBackPressed()`,
and `ToolbarInteractor`'s commit listener was already being overwritten by
`wireUrlCommit()`. Nothing else was lost: `displayProgress` feeds a progress bar
we hide (`mozac_browser_toolbar_progress_bar_height` is 0dp), and the
tracking-protection and permission indicators are not in `display.indicators`.

The general rule this is an instance of: **anything that writes to the toolbar
must be able to say why it can't reach `edit`.** `url`, `siteInfo` and
`invalidateActions` are safe; `setSearchTerms` and `editMode` are not.

## Links that ask for a window of their own

`target="_blank"` and `window.open()` reach a-c as a `WindowRequest` parked on
the tab, and **something has to consume it**. Gecko has already built the
session by then (`onNewSession` returns one immediately), so with no consumer
the page loads into a session nobody renders and the link appears to do nothing
at all — not "opens in the wrong place", *nothing*. That is what "некоторые
страницы не кликаются, как будто ссылки нет" was: not the adblock, not the
engine, an unhandled store action.

[WindowRequests](app/src/main/java/com/upgrid/browser/tabs/WindowRequests.kt) is
a-c's `WindowFeature` (feature-tabs) plus the `openLinksInNewTab` preference.
With it off the URL is loaded in the current tab and the prepared session is
`close()`d — leaving it open costs a content process for a page nobody will see.
A request with a blank URL always gets its own tab: the page is going to write
into that window from script and there is nothing to load anywhere else.

## Defaults, and the difference between a default and a rule

Three things ship in a particular position, and each has been the wrong kind of
"default" at least once:

- **AdBlock on.** `BrowserPreferences.adblockEnabled` starts true, so a fresh
  install blocks ads without being asked. It used to be *forced*:
  `AdblockBootstrap` re-enabled uBO on every launch as a safety net against a
  permission-prompt abort, which also silently undid the user turning it off.
  The preference is what tells those two cases apart.
- **VPN off.** Signing in fills the WireGuard profile in and stops there.
  It used to switch `autoConnect` on as well — signing in to a browser account
  is not asking for every byte to be routed through someone's server, and the
  tunnel's only visible sign is a notification. **Deleting the line that wrote
  the preference does not delete the preference**, which is the whole lesson
  here: every phone that had ever signed in kept coming up with a tunnel, and
  the report arrived as "installing the update turns the VPN on". So
  `VpnSettings.forgetAutoConnectSetBySignIn` clears it once and records that it
  did. The switch on the VPN screen still works and is still honoured — it just
  has to be the user who moves it. Any future preference written on the user's
  behalf needs the same treatment when it is taken back.
- **Desktop mode off, per tab.** Nothing to enforce: `requestDesktopSite`
  dispatches `EngineAction.ToggleDesktopModeAction(tabId, …)`, which is per-tab,
  and the browser-wide `BrowserState.desktopMode` is only moved by
  `DefaultDesktopModeAction`, which this app never dispatches. A tab put into
  desktop mode stays there across a restart (it's serialised with the tab); a
  new tab is never born in it.

## Surviving being minimised, and the back button

Two complaints that turned out to be one area.

**Back must never close the browser without asking.** It is the most-pressed
button on Android and it is pressed reflexively; ending the session, closing
every tab and landing on the launcher is not something one reflex should be
able to do. `MainActivity.leaveTab` is the ladder below page history: a tab
with a `parentId` (i.e. one a link opened) goes back to the tab it came from
via `removeTab(id, selectParentIfExists = true)`, any other extra tab just
closes, and the last one asks.

**State is saved four ways**, and each catches a different loss —
`whenSessionsChange` (tabs opened/closed/navigated), `periodicallyInForeground`
(a page being read and scrolled, which fires nothing else — without it the
saved copy can be an hour older than the screen), `whenGoingToBackground`, and
one of our own, in `BrowserApplication.saveSessionOnceGeckoHasAnswered`.

That fourth one exists because of an ordering problem in the third. Releasing
the engine view at `onStop` calls `GeckoSession.setActive(false)`, which asks
the session to flush its state — scroll position, back/forward history, what
was typed into the page. Gecko answers on its own thread, a beat later, through
`UpdateEngineSessionStateAction`. a-c's `whenGoingToBackground` writes the
snapshot the moment `ProcessLifecycleOwner` stops, i.e. possibly before that
answer lands, and `whenSessionsChange` does not watch `engineSessionState` at
all (it watches the tab list, the selection and the loading flag). So the file
on disk could be one flush behind, and after a kill the browser came back one
step older than it should have. We write again after
`SESSION_FLUSH_GRACE_MS`.

`android:alwaysRetainTaskState="true"` on MainActivity stops the system
trimming the task after ~30 minutes away, which it otherwise does on the theory
that after a break you want to start over.

**And when the page really is old, say so.** `view_stale_bar.xml` appears under
the toolbar past [BrowserPreferences.STALE_PAGE_AFTER_MS], phrased by
`DateUtils.getRelativeTimeSpanString` so the wording and the plurals come from
the phone. `pageLoadedAt` is recorded from the loading→idle transition rather
than from the URL changing, so a plain reload counts. It is deliberately
in-memory: the question is "how old is what I'm looking at", and if the process
was killed and the page refetched, the missing entry is the right answer.

## The hole GeckoView leaves in this window

GeckoView does not draw the page into our window. It draws into a `SurfaceView`
— a separate layer *behind* the window — and punches a transparent hole through
the window where that layer shows through (`SurfaceView.draw` clears its own
bounds with `PorterDuff.CLEAR` before anything else gets to paint there). Live
on screen this is invisible and it is why scrolling is fast. Everywhere the
system takes a *picture* of the window instead, it gets the hole: the card in
the recents list showed the address bar floating over the wallpaper with
nothing underneath it, which reads as "the app is broken", not as "the app is
in the background".

The fix is a plain `ImageView` (`pageCover` in `activity_main.xml`) laid over
that hole while the activity is not resumed, holding the last capture from
`TabThumbnails`. The window draws ordinary views into its own surface, so
whatever the system photographs now has a page in it. Shown in `onPause`, hidden
in `onResume`; the pixels are the ones already on screen, so the swap is never
visible as a change. Two things it must not do:

- **Not over the player.** Picture-in-picture is entered through `onPause` like
  everything else, and there the window *is* the video and it is still playing.
  `showPageCover` checks `inVideoFocus`, `playerActive` and `isInPipMode`.
- **Not on the start page.** That one is an ordinary view; the window draws it
  perfectly well, and there is no page to stand in for.

`captureCurrentThumbnail` used to return early when the tabs tray was set to the
list view — a preference about a different screen, which has nothing to say
about whether the recents card should be empty. One `capturePixels` per pause is
not a cost worth reasoning about. The capture is a compositor round trip, so it
can land after the cover is already up with an older frame; the callback swaps
the fresher one in if the tab hasn't changed underneath it.

The other way to fix this is `GeckoView.setViewBackend(BACKEND_TEXTURE_VIEW)`,
which makes the engine a normal composited view. It is one line and it works,
and it was not taken: Mozilla documents it as the slower backend, video is this
browser's headline feature, and paying for every frame forever to make one
static picture correct is the wrong trade.

## The tabs screen, laid out like Chrome's

Asked for by name, with a screenshot. The arrangement is: ⊞ filled, on the left;
the view switch in the middle as one trough with two positions; ⋮ on the right;
the filter underneath, full width.

Each position earns its place. **⊞ is the only action here that isn't about a
tab you already have**, so it is the only one that is filled. **The switch shows
where it is, not where it could go** — the old single icon showed the *other*
view, which is a coin-toss to read, and the left half now carries the tab count
because that is the number you came to check. **⋮ holds close-everything**: it
was a text button in the header, i.e. an irreversible action sitting under a
resting thumb. **The filter is about the list**, so it is under the row of
controls rather than in it.

There is **no back arrow**, also Chrome's. Every way out of this screen is a
tab, the system back button, or the gesture; an arrow would be a fourth, and it
was the only reason this screen used the shared `view_page_header`.

The filter matches title *or* address, because half the time you remember the
site and half the time you remember the headline — and a tab that hasn't loaded
yet has an address and nothing else. "You have no tabs" and "nothing matches
what you typed" are different situations and get different empty states; one
message that says the first while the second is true reads as the browser having
lost them. The count on the switch stays the real one either way: a filter is a
way of looking, not a change to what is there.

## The long-press menu

[LinkContextMenu](app/src/main/java/com/upgrid/browser/menu/LinkContextMenu.kt),
not `feature-contextmenu`. **Do not try to add that artifact back**: it depends
on `feature-search` → `support-remotesettings` → appservices → Glean, and
Glean's native library is already inside `geckoview-omni`, so Gradle fails the
build with

```
Cannot select module with conflict on capability 'org.mozilla.telemetry:glean-native'
```

Resolvable with `resolutionStrategy.capabilitiesResolution`, but the answer is
megabytes of telemetry machinery in a browser whose pitch is that it doesn't
phone home — for a menu with six rows.

Gecko parks a `HitResult` on the tab; the feature shows a dialog and **consumes
it either way**, because an unconsumed result means the next long press on the
same element changes nothing in the store and is silently ignored.
`HitResult.IMAGE_SRC` is the one to get right — an image inside a link, where
`src` is the picture and `uri` is the destination, and confusing them is how
"open in new tab" ends up loading a JPEG.

Saving goes through `ContentAction.UpdateDownloadAction`, which is what
`DownloadManager` already watches for; it re-fetches with the page as referrer,
which is what makes an image behind a hotlink check actually arrive.

## The one buzz, and where it belongs

There is exactly one haptic in this browser, and it fires when the long-press
menu is about to open (`LinkContextMenu.buzz`). That gesture is the only one
with no other feedback: the finger is already down, nothing on screen moves, and
the only way to learn whether you have held it long enough is to wait and find
out.

It used to fire on an ordinary tap on a link instead, reported by a content
script (`taps.js`, now deleted). That was the wrong gesture — a tap answers
itself, because the page starts loading — and it cost a click listener in every
frame of every page to say so.

`HapticFeedbackConstants.LONG_PRESS`, because Android reserves it for exactly
this and a phone that buzzes for the launcher's long press should buzz the same
way here. `View.performHapticFeedback` is a no-op when the user has turned
haptics off system-wide, so the phone's own setting is honoured without the app
asking about it; the switch in Settings sits on top of that, not instead of it.

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

## A char literal that isn't the character you typed

Twice now a `+ ' ' +` in Kotlin has landed on disk as a **literal NUL byte**
rather than a space (`Suggestions.kt`'s dedup key, `SearchSuggestionClient`'s
cache key). The code still compiles and still works, and then:

- `file` reports the source as `data`, `git diff` shows `Bin`, and **every grep
  over that file silently returns nothing** — which is how a whole feature once
  looked like it had never been wired up at all.

Write separators as string templates with an explicit escape
(`"${a} $b"`), never as a bare char literal, and check before committing:

```bash
git ls-files -z -- '*.kt' | xargs -0 grep -lP '\x00'
```

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
