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
├── assets/extensions/upgrid_fullscreen/  ← built-in player WebExtension (manifest + background.js + player.js)
├── java/com/upgrid/browser/
│   ├── BrowserApplication.kt       ← components; restore session; uBO bootstrap; autosave
│   ├── BrowserComponents.kt        ← single source of truth for runtime/engine/store/tabs
│   ├── MainActivity.kt             ← topbar (home + URL chip) + GeckoEngineView +
│   │                                 6-slot Banana-style bottom bar + Session/Toolbar features
│   ├── AdblockController.kt        ← thin façade for the AdBlock on/off menu toggle
│   ├── addons/AdblockBootstrap.kt  ← silent uBO install + version pin
│   ├── fullscreen/
│   │   ├── VideoPlayerBridge.kt    ← native ⇆ extension port; takeover trigger
│   │   └── PlayerOverlayController.kt ← overlay buttons, seek bar, gestures
│   ├── history/
│   │   ├── HistoryStore.kt         ← SQLite visits table (one row per URL)
│   │   ├── HistoryAdapter.kt       ← day headers + rows
│   │   └── HistoryFragment.kt      ← bottom-sheet history browser
│   ├── home/                       ← speed-dial start page
│   ├── menu/AppMenuPopup.kt        ← Banana-style drop-down menu (PopupWindow, not BottomSheet)
│   ├── prefs/BrowserPreferences.kt ← typed SharedPreferences façade (all settings)
│   ├── search/                     ← SearchEngine enum + SearchHistory
│   ├── settings/SettingsBottomSheet.kt ← settings sheet (search engine, seek step, history)
│   └── tabs/
│       ├── TabsTrayFragment.kt     ← BottomSheet tabs tray (RecyclerView, store-driven)
│       └── TabViewHolder.kt
└── res/
    ├── layout/                     ← activity_main + app_menu_popup + fragment_tabs_tray +
    │                                 view_fullscreen_controls (player overlay) + …
    ├── menu/app_menu.xml           ← New tab / Close tab / AdBlock toggle
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
- **Exit is multi-path and must stay idempotent.** System back / fsExit button / page exiting fullscreen all converge: content script's `fullscreenchange` listener auto-releases → `"released"` event → MainActivity hides overlay + restores chrome. `exitPlayer()` also restores chrome optimistically without waiting for the round-trip.
- Seek step for double-tap/skip buttons is `BrowserPreferences.playerSeekSeconds` (5/10/15/30 s, settings sheet).

### Two invariants that cost real debugging time

**Never let a fullscreen transition we caused reach the auto-release watcher.**
`player.js` releases the video whenever `fullscreenchange` reports no
fullscreen element — that's how system back and page-driven exits are caught.
But a re-entrant takeover exits the old fullscreen before requesting a new one,
and PiP exits it on purpose. Both used to trip the watcher, which handed the
video back mid-transition and left the overlay on a page that no longer had it.
Every deliberate transition arms `ignoreFsFor(ms)` first; `exitFullscreenQuietly()`
does it for you. The internal `release({keepFullscreen: true})` at takeover
entry exists for the same reason.

**Android PiP and DOM fullscreen cannot coexist.** The system resizes the
activity, Gecko drops fullscreen, and (before `pipMode`) the watcher fired.
`MainActivity.enterPipMode()` therefore sends `{cmd:"pip", on:true}` *before*
calling `enterPictureInPictureMode`, so the content script leaves fullscreen on
its own terms and keeps the takeover. The video still fills the PiP window
because the window is the viewport. Coming back from PiP we do **not** try to
re-enter DOM fullscreen — there's no gesture to do it with, and the nuke style
plus hidden chrome already looks identical.

Related: `MainActivity` distinguishes `playerActive` (took over, not yet
released) from `playerOverlay.isVisible` (false in PiP while the player runs).
`setVideoFocus` owns the `inVideoFocus` state; `applyVideoFocus` re-pushes it
onto the window without touching the state, which is what PiP transitions need.

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
- **Phase 3 — History & bookmarks.** History **done**, but *not* on `browser-storage-sync` as originally planned — see below. Bookmarks + downloads (`feature-downloads`) still open.
- **Phase 4 — Settings.** Theme picker, search engine, default-browser prompt, "use system dark mode", about-page. Move the AdBlock toggle from popup menu into the settings screen.
- **Phase 5 — Optional extensions.** Surface a curated list (Dark Reader, Bitwarden, Tampermonkey) via `feature-addons`'s `AddonManager`. Behind a "Power user" setting.

## Architecture notes (phase 2)

- **`BrowserStore` is the single source of truth.** Tabs, selected tab id, URL, title, progress all live there. UI observes via `store.flow()` and dispatches actions; never mutate engine sessions directly.
- **Features are the glue.** `SessionFeature` renders the selected tab into `GeckoEngineView`; `ToolbarFeature` keeps `BrowserToolbar` synced. Both are bound through `ViewBoundFeatureWrapper` so they stop/start with the view lifecycle.
- **`BrowserApplication.restorePreviousSession`** restores tabs *before* the bootstrap installs uBO — this guarantees tabs are visible the moment the user sees the activity even if AMO is unreachable.
- **Tab close → empty state:** `MainActivity.wireBackPress` finishes the activity when the last tab is closed via the system back button. The tabs tray itself does *not* auto-dismiss when `tabs.isEmpty()` — it shows a Banana-style empty illustration. If the user swipes the tray away with zero tabs, `TabsTrayFragment.onDismiss` opens a fresh HOME tab so MainActivity isn't left empty-handed.
- **App menu is a `PopupWindow`, not a BottomSheet.** [AppMenuPopup](app/src/main/java/com/upgrid/browser/menu/AppMenuPopup.kt) is a 300dp drop-down anchored to `btnMenu` via `showAsDropDown(anchor, 0, 0, Gravity.END)`. The auto-flip-above behavior places it correctly even though the anchor is at the bottom of the screen. Construct a new instance per tap (cheap, avoids stale toggle state).

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

Two consequences to respect:

- **CI must check out with `fetch-depth: 0`.** A shallow clone counts 1 commit,
  so every build would be versionCode 1 and Android would refuse the upgrade.
- **Don't hand-edit `versionCode`.** It's derived; an edit is silently lost.

[.github/workflows/android.yml](.github/workflows/android.yml) builds on every
branch, publishes the rolling `latest-debug` pre-release only from `main`, and
posts the APK to Telegram (secrets `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID`).
The Bot API caps uploads at **50 MB** and GeckoView debug APKs sit near that
line — over the limit the job sends a download link instead of the file, so
don't "simplify" that branch away.

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
