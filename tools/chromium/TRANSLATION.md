# Google page translation in Upgrid

## Current phone-followup requirement (2026-10-04)

The owner rejected the extension-based translation shipped in .9 and requires
Chrome's native mobile translation. The current overlay no longer intercepts
Chrome's Translate command with TWP and preserves the upstream availability
checks. Working translation service access is not yet established: the pinned
TranslateManager requires a configured Google API key. A visible native UI or
an independently installed TWP extension does not satisfy this requirement.
The following sections document the historical .7-.9 implementation only.

The user requires translation **inside the current page**, with remembered
automatic translation like Chrome. The integration path is the real TWP MV3
extension with Google selected. This is not Google's native Chrome component.
Do not substitute a new Google Translate tab or report prepared settings as a
working translator.

## Browser menu

The `.7` overlay connects Chromium's existing `translate_id` menu item (including
its localized label and icon) to the installed TWP extension. The ordinary
Chromium availability check requires a configured Google API key, so installing
an extension alone did not expose this menu item.

`UpgridTranslate` checks the current WebContents, the enabled Web Store extension
ID, its declared `hotkey-toggle-translation` command, granted site access and
incognito permission. It revalidates at click time and sends the normal
`commands.onCommand` user-gesture event through EventRouter, which also wakes a
suspended extension worker. TWP handles the current tab, chosen service, target
language, original-text restoration and mobile panel. Withheld host permissions
are not treated as granted. No page script, shortcut ownership, API-key check or
extension permission is bypassed. The stock translator remains the fallback.

Local Java bridge checks (after building chrome_java and the existing Chromium
Robolectric dependencies): `python tools/chromium/tests/test-translate.py`.
These do not establish native event delivery or successful Google requests;
the real menu tap must still be checked on Android.

## Setup without another native build

Wait for the current `extensions-dev` APK and install/test its extension support.
TWP can then be installed independently, without changing Chromium GN flags or
recompiling the engine. Use its author's linked Web Store listing:

https://chromewebstore.google.com/detail/gkkkcomfmldkigajkmljnbpiajbpbgdg

Use the normal extension install/host permission flow. Do not add TWP to the
blocker's silent permission grant, inject a key from another browser, or change
stock TranslateManager's API-key check. Record the version actually installed:
Web Store updates may differ from the reviewed MV3 10.1.5.0 source.

Prepare a settings file in TWP's native import format:

```powershell
node tools/chromium/prepare-translation.cjs build/chromium/features-20260930/translation-source build/chromium/features-20260930/upgrid-google-autotranslate-ru.txt
node tools/chromium/tests/verify-translation.cjs build/chromium/features-20260930/translation-source
```

For an existing TWP installation, export its settings first and pass that file
as the third argument to `prepare-translation.cjs`, choosing a new output name.
This preserves its dictionary and site/language exceptions. The no-export
profile is for a fresh installation only. Import through TWP settings' restore
from file action; it accepts `.txt` containing JSON. Settings are local to the
extension; no extension permission is granted by preparing/importing this file.

The prepared profile selects Google and Russian, enables automatic translation
for supported foreign languages and dynamic content, and retains the normal
mobile panel for original text and settings. It disables provider fallback so
Google failure is not silently replaced by another translation provider. Page
text is sent to Google when translated. No API billing/project is configured.

## Acceptance still required on Android

Use the existing fixture at `http://127.0.0.1:8766/translation.html` via the
local fixture server and ADB reverse. Do not assume desktop behavior proves
Android compatibility. The reviewed TWP code allows automatic *language*
translation on mobile; some always-translate-*site* paths are desktop-only.

1. Confirm the actual extension version, Google provider and Russian target.
2. Confirm readable Russian in the current page and the unchanged page URL.
   DOM mutation counts alone do not prove correct translation.
3. Reload, navigate to another English page and cold-start with a saved tab.
   Translation must happen without pressing Translate again.
4. Restore original text; verify links, forms, player controls and scroll still
   work. Check `translate=no`, existing exceptions and Russian pages.
5. Add foreign text dynamically and confirm it translates. Verify offline
   failure leaves the original page usable, then test a manual retry online.
6. Save the installed version and the Android acceptance evidence separately
   from the settings-import receipt. Do not publish an APK as a complete release
   until translation and the other requested features pass their checks.

Current Android evidence: TWP 10.2.5.0 was installed using the ordinary Web Store
flow, and the user's screenshot shows readable Russian on the English local
fixture. The `.7` native menu and automatic translation across reload/restart,
exceptions and failure recovery still require Android acceptance.
