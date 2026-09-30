# Chromium feature migration — 30 September 2026

The user requested Google page translation, ad blocking, Tampermonkey, new
branding, Telegram publication and an update over the installed Upgrid Next.
This is work in progress, not a distribution receipt.

## Build profiles

Latest resource authorization (30 September 2026): the user explicitly requested
8 GiB WSL RAM and 6 logical processors to accelerate the Chromium build. After
stopping the owned build, the idle e-lib-db container was shut down gracefully,
the old `.wslconfig` was backed up, and WSL was restarted. Verified effective
limits: 7.76 GiB reported RAM, 6 CPUs, 8 GiB swap; the database was restarted and
confirmed ready. Resume extensions-dev with `--jobs 6` (without `--low-memory`).
The one-worker constrained mode described below is historical and remains
available, but is not the user's latest selected Chromium build mode. Keep
the existing object files and do not raise these new limits automatically.

After the user's stop/resume, defer the optimized build. Resume the existing
two-object probe, then use `build.py --profile extensions-dev --low-memory`
for the actual development APK. This profile keeps the original debug flags
and reuses `out/Upgrid` from the completed player build. Its only effective
configuration changes are Desktop Android extensions and a new APK version.
Review and back up the old args before replacing them; do not delete object
files, Ninja dependency logs or the old verified APK. Ninja decides which
objects need rebuilding. Save separate `upgrid-extensions-dev` receipts.
Never run player and extensions-dev concurrently because they share output.
The optimized profile below remains available for a later release pass, but
must not be queued automatically by the resumed development build.

The existing `args.gn` preserves the tested phone-player configuration.
`args-extensions.gn` enables Chromium's experimental Desktop Android extension
platform. Build it explicitly with `build.py --profile extensions`, using a
separate `out/UpgridExtensions` directory and separate receipts. Continue to use
one Ninja worker and the existing disk reserve checks. Never increase WSL limits
automatically.

The currently running WSL reports 5.79 GiB RAM and 8 GiB swap. Use the explicit
`build.py --profile extensions --low-memory --jobs 1` mode for this agreed
resource budget. It checks both RAM and swap and rejects parallel workers;
the default 8 GiB preflight remains unchanged. No global limits are raised.
This is a constrained build attempt, not a claim that every compilation/link
step has been measured to fit; memory failures must stop, not increase limits.

The extensions profile disables native/Java debug builds and enables the usual
Java shrinking path. ThinLTO and PGO remain disabled to limit build memory.
The existing player test APK is 590,746,293 bytes; its stored native library
alone is approximately 385.5 MB. A smaller optimized APK is expected, but neither
the final size nor a reduction percentage has been measured yet.

An isolated GN probe of `enable_desktop_android_extensions=true` on the mobile
configuration failed at `chrome/browser/media/router/discovery/BUILD.gn` with
`Not supported on mobile`. `is_desktop_android=true` generated successfully
(61783 targets). This proves build-graph generation only. The upstream Android
permission dialog, extension manager, Web Store flow and `userScripts` API
exist in this pinned revision; their operation on a phone remains unverified.

The isolated probe lives in `out/UpgridExtensionsProbe`, marked
`.upgrid-probe-owner`; it is not an APK release output. Its initial args retain
the previous test version. Never distribute an APK from that probe.

## Default blocker

`default_adblock_loader.cc.inc` and `default_adblock_provider.cc.inc` register
uBO Lite (`ddkjiahejlhfcafbddmgiahcphecmpfh`) through Chromium's normal external
extension updater, only when the experimental extension platform is compiled.
The updater downloads and verifies the Web Store CRX. The fixed blocker is
acknowledged as an Upgrid default; it does not receive component-only APIs or
enterprise policy privileges. User disable/uninstall decisions remain owned by
Chromium. Guest, system and off-the-record profiles do not initiate installation.
`--disable-default-apps` also suppresses it in browser tests.

This is uBO **Lite**, not the full Firefox uBlock Origin. No filtering success,
default-on runtime behavior, private-mode coverage or parity with the previous
browser has been established. Verify first launch online/offline, update retry,
enabled state, cosmetic filtering, site exceptions, disabling/re-enabling,
uninstall persistence and incognito explicitly. Do not claim an enabled shield
before the extension registry and a real request-blocking test confirm it.

Tampermonkey must use the normal installation and permission UI; it is not
included in the default blocker's automatic acknowledgement path. Verify its
official Web Store ID `dhdgffkkebhmkfjojejmpbldmpobfkfo`, dashboard and a benign
local `.user.js` fixture, including the per-extension user-script permission.

User clarification: the actual Tampermonkey extension is required because the
user already has working scripts. A replacement userscript manager or a partial
`GM_*` compatibility layer is not an acceptable substitute. Acceptance includes
installation and execution of the user's existing scripts. The user supplied
`https://tampermoney.neyron.site`, which redirects to
`https://tampermonkey.neyron.site/`. Four current scripts were retrieved as
read-only snapshots in `build/chromium/features-20260930/`; their syntax checks
pass, but Android execution is not yet established:

| Script | Version | Compatibility checks required |
| --- | --- | --- |
| VK theater | 1.1.0 | GM info/storage/menu commands, Shadow DOM, touch controls, enter/exit Upgrid fullscreen without replacing video |
| VK preview | 1.4.2 | grant-none DOM/CSS updates and mutation handling |
| Neyron updates | 1.0.3 | GM storage, cross-origin request to its declared host, register/unregister menu, local version events |
| Kick chat sync | 1.1.2 | document-start in raw page world, page fetch interception, IndexedDB/localStorage, VOD seeking and chat |

The Kick script specifically needs page-world injection before the site's
playback requests. Running it later through an ordinary isolated content script
is not an adequate compatibility test. All four scripts use the canonical
`tampermonkey.neyron.site` domain, consistent with the observed redirect.
The user also requested future third-party Chrome Web Store installations;
keep the general store and permission flow, not a Tampermonkey-only allowlist.
Do not promise all desktop extensions work on Android (for example native
messaging or desktop-only UI requires separate support).

The local `tampermonkey.html` / `tampermonkey-smoke.user.js` fixture checks the
real manager identity, legacy and async storage APIs, a local request, style
injection, iframe execution and menu commands. Its results are visible on the
fixture page; no user scripts or browser data are uploaded. A successful syntax
or HTTP check of the fixture is not a Tampermonkey/Android test.
`tampermonkey-raw.user.js` separately probes early MAIN-world fetch interception
on localhost to cover the Kick script's injection requirements. It restores its
own wrapper after DOM readiness and does not contact Kick or any other site.

References:
- https://github.com/uBlockOrigin/uBOL-home
- https://www.tampermonkey.net/index.php?browser=chrome&locale=en

## Translation

The stock `TranslateManager::CanManuallyTranslate` checks
`google_apis::HasAPIKeyConfigured()`. This build has no configured Google API
key. Do not bypass the check or embed a key belonging to another browser.
The user explicitly clarified on 2026-09-30 that translation must happen in
the current page, with automatic translation like Chrome. A separate Google
Translate tab does not satisfy this requirement. Google is the requested
translation provider. Remember the selected target language and automatic
translation choices; provide original-text restoration and language/site
exceptions. Russian is the working default for this user's acceptance checks.
The real TWP setup and Russian automatic-translation profile are now prepared
by `prepare-translation.cjs`; see [TRANSLATION.md](TRANSLATION.md). The upstream
settings importer and persistence across a simulated restart pass verification.
Installation and actual Google page translation on Android are still pending
the extension-capable APK. The running extensions-dev APK is an intermediate
test build, not completion of the requested feature set.

Another candidate for in-page translation is the author's MV3 beta of Translate
Web Pages; it supports translation providers, but is not Google's own browser
component and has not been tested in Upgrid:
https://github.com/FilipePS/Traduzir-paginas-web/releases/tag/MV3-BETA-10.1.5.0

The release links to Web Store ID `gkkkcomfmldkigajkmljnbpiajbpbgdg`.
Its pinned source has Google as the default provider, persistent
`alwaysTranslateLangs` / `alwaysTranslateSites`, original-text restoration and
dynamic-content translation. This establishes available code, not operation in
Upgrid. Installation must retain normal extension permission prompts; do not
silently grant it the blocker's acknowledgement or call it Google's own Chrome
component. Validate on Android after the extension-capable APK finishes: real
in-page Google translation, repeat navigation/cold start without another tap,
original text, exceptions and dynamically added text. Do not mark translation
complete based on a manifest, configuration file or successful APK compilation.

## Updating the installed app

The connected phone has `com.upgrid.browser.next.debug`, version
`0.6.6-player-tabs-fixes`, code 13. Its SHA-256 signing certificate is
`c77a22128b5adf0a60997f3dbb776633ec37b9933b8858c1c1ba525963dd8433`.
The matching signing key is available locally in WSL. The current Chromium APK
uses `com.upgrid.chromium` and a different signer, so it cannot update that app.
The old app APK was copied for metadata verification, not its private data.
Evidence is in `build/chromium/features-20260930/upgrade-preflight.json`.

Keeping the Android package, signer and a higher version code retains private
files, but does not convert their contents. Fenix uses Rust Places
`places.sqlite` and `mozilla_components_session_storage_<engine>.json`
(session format 2). Chromium uses different databases and tab state. The user
has been asked which data must survive; do not replace the installed Next app
before the importer and rollback path have been verified on a copied fixture.
Keep source files intact, never log URLs/passwords, and only mark each import
stage complete after its destination write has succeeded. Source profile
preservation alone is not proof that Chromium can use the data. Passwords and
site logins need separate migration work; no support is claimed.

## Branding and publication

The new generated icon is in `branding/`; both launcher manifest attributes
reference `upgrid_launcher`, with a themed monochrome variant. The actual
Chromium base resource targets compiled successfully. Binary overlay collision
and upgrade tests pass, together with the existing tools tests (14 total).
No new APK containing the icon has been built or installed yet.

The user explicitly authorized Telegram publication. Publish under `app_id=upgrid`
after the requested features and upgrade path pass mandatory verification;
follow the current server `AGENTS.md` and `RELEASING.md`, save the Telegram
`message_id` and `verify_release.py` receipt. No publication was performed for
this feature work. The old 146 reference base still requires a supported-base
update before a production release.
