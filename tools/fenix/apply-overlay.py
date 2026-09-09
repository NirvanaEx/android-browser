#!/usr/bin/env python3
"""Apply the small Upgrid prototype overlay to the pinned Mozilla checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parent.parent
UPSTREAM = json.loads((HERE / "upstream.json").read_text(encoding="utf-8"))
RELEASE = json.loads((HERE / "release.json").read_text(encoding="utf-8"))
APP = "mobile/android/fenix/app"
SUPPORT = "mobile/android/android-components/components/support/webextensions"


def digest(content):
    return hashlib.sha256(content).hexdigest()


def replace_once(text, before, after):
    if text.count(before) != 1:
        raise ValueError(f"Upstream integration point changed: {before!r}")
    return text.replace(before, after, 1)


def replace_body(text, signature, body):
    start = text.index(signature)
    masked = re.sub(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/',
                    lambda match: " " * len(match.group()), text)
    opening = masked.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (masked[end] == "{") - (masked[end] == "}")
        end += 1
    return text[:opening + 1] + "\n" + body.rstrip() + "\n" + text[end - 1:]


def generate(checkout):
    files = {}

    def add(path, text):
        files[path] = text.encode("utf-8")

    def original(path):
        return subprocess.check_output(
            ["git", "-C", str(checkout), "show", f"HEAD:{path}"],
        ).decode("utf-8")

    local = PROJECT / "app/src/main"
    kotlin = local / "java/com/upgrid/browser/fullscreen"
    resources = set()

    def xml_resources(text):
        def replace(match):
            kind, name = match.groups()
            resources.add((kind, name))
            return f"@{kind}/upgrid_{name}"
        return re.sub(r"@(drawable|string)/([a-z0-9_]+)", replace, text)

    def kotlin_resources(text):
        def replace(match):
            kind, name = match.groups()
            resources.add((kind, name))
            return f"R.{kind}.upgrid_{name}"
        return re.sub(r"(?<![\w.])R\.(drawable|string)\.([a-z0-9_]+)", replace, text)

    for name in ("VideoPlayerBridge.kt", "PlayerOverlayController.kt", "NativeVideoPlayer.kt"):
        text = (kotlin / name).read_text(encoding="utf-8")
        text = text.replace("package com.upgrid.browser.fullscreen", "package org.mozilla.fenix.upgrid")
        text = text.replace("import com.upgrid.browser.R", "import org.mozilla.fenix.R")
        text = text.replace("com.upgrid.browser.databinding.ViewFullscreenControlsBinding",
                            "org.mozilla.fenix.databinding.UpgridViewFullscreenControlsBinding")
        text = re.sub(r"\bViewFullscreenControlsBinding\b", "UpgridViewFullscreenControlsBinding", text)
        add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/{name}", kotlin_resources(text))
    add(f"{APP}/src/main/res/layout/upgrid_view_fullscreen_controls.xml",
        xml_resources((local / "res/layout/view_fullscreen_controls.xml").read_text(encoding="utf-8")))
    add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/UpgridPlayerFeature.kt",
        (HERE / "overlay/UpgridPlayerFeature.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/UpgridAdblock.kt",
        (HERE / "overlay/UpgridAdblock.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/UpgridUi.kt",
        (HERE / "overlay/UpgridUi.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/UpgridDiagnostics.kt",
        (HERE / "overlay/UpgridDiagnostics.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/main/res/drawable/ic_splash_logo.xml",
        (local / "res/drawable/ic_launcher_foreground.xml").read_text(encoding="utf-8"))
    add(f"{SUPPORT}/src/test/java/mozilla/components/support/webextensions/UpgridPermissionTest.kt",
        (HERE / "overlay/UpgridPermissionTest.kt").read_text(encoding="utf-8"))
    resources.update({("drawable", "ic_video_play"), ("drawable", "ic_launcher_foreground")})
    resources.update(("string", name) for name in (
        "player_not_ready", "player_fullscreen_failed", "player_no_video",
        "player_native_failed", "player_native_unsupported", "player_engine_tap",
    ))
    copied = set()
    while resources - copied:
        kind, name = sorted(resources - copied)[0]
        copied.add((kind, name))
        if kind == "drawable":
            add(f"{APP}/src/main/res/drawable/upgrid_{name}.xml",
                xml_resources((local / f"res/drawable/{name}.xml").read_text(encoding="utf-8")))
    strings = ET.Element("resources")
    for item in ET.parse(local / "res/values/strings.xml").getroot():
        name = item.get("name")
        if ("string", name) in resources:
            item.set("name", f"upgrid_{name}")
            strings.append(item)
    ET.SubElement(strings, "string", name="upgrid_player_open").text = "Open video in player"
    ET.SubElement(strings, "string", name="upgrid_app_name").text = "Upgrid Next"
    ET.SubElement(strings, "color", name="upgrid_launcher_background").text = "#1F6FEB"
    ui_labels = {
        "home_hint": "Search or enter address", "normal_tabs": "Browsing", "private_tabs": "Private",
        "private_hint": "Private browsing", "adblock": "AdBlock", "extensions": "Extensions",
        "desktop": "Desktop site", "desktop_on": "Desktop site: on", "find": "Find in page",
        "bookmark": "Bookmark this page", "edit_bookmark": "Edit bookmark", "settings": "Settings",
    }
    for name, value in ui_labels.items():
        ET.SubElement(strings, "string", name=f"upgrid_{name}").text = value
    ET.indent(strings)
    add(f"{APP}/src/main/res/values/upgrid_strings.xml", ET.tostring(strings, encoding="unicode") + "\n")
    add(f"{APP}/src/main/res/values-ru/upgrid_ui.xml", (HERE / "overlay/upgrid_ui_ru.xml").read_text(encoding="utf-8"))
    add(f"{APP}/src/main/res/mipmap-anydpi-v26/upgrid_ic_launcher.xml", '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/upgrid_launcher_background" />
    <foreground android:drawable="@drawable/upgrid_ic_launcher_foreground" />
</adaptive-icon>
''')
    for name in ("manifest.json", "background.js", "player.js", "lifecycle.js"):
        add(f"{APP}/src/main/assets/extensions/upgrid_fullscreen/{name}",
            (local / f"assets/extensions/upgrid_fullscreen/{name}").read_text(encoding="utf-8")
            .replace("var nativePlayerEnabled = false;", "var nativePlayerEnabled = true;"))

    components = f"{APP}/src/main/java/org/mozilla/fenix/components/Components.kt"
    add(components, replace_once(original(components), "    val useCases by lazyMonitored {", """    val upgridPlayer by lazyMonitored {
        org.mozilla.fenix.upgrid.VideoPlayerBridge(core.engine).also {
            it.onDiagnostic = { source, throwable ->
                org.mozilla.fenix.upgrid.UpgridDiagnostics.get(context).error(source, throwable)
            }
            it.setupAndInstall()
        }
    }

    val useCases by lazyMonitored {"""))
    text = files[components].decode("utf-8")
    add(components, replace_once(text, "    val upgridPlayer by lazyMonitored {", """    val upgridAdblock by lazyMonitored {
        org.mozilla.fenix.upgrid.UpgridAdblock(core.engine, addonManager)
    }

    val upgridPlayer by lazyMonitored {"""))
    application = f"{APP}/src/main/java/org/mozilla/fenix/FenixApplication.kt"
    text = replace_once(original(application), "                onUpdatePermissionRequest = components.addonUpdater::onUpdatePermissionRequest,", """                onUpdatePermissionRequest = components.addonUpdater::onUpdatePermissionRequest,
                autoGrantedExtensionIds = setOf(org.mozilla.fenix.upgrid.UpgridAdblock.ID),""")
    text = replace_once(text, """            )
        } catch (e: UnsupportedOperationException) {
            logger.error("Failed to initialize web extension support", e)""", """            )
            components.upgridAdblock.ensureInstalled()
        } catch (e: UnsupportedOperationException) {
            logger.error("Failed to initialize web extension support", e)""")
    add(application, text)
    text = replace_once(files[application].decode("utf-8"), "        maybeInitializeGlean()", """        components.settings.apply {
            isTelemetryEnabled = false
            isMarketingTelemetryEnabled = false
            isDailyUsagePingEnabled = false
            isExperimentationEnabled = false
            if (BuildConfig.UPGRID_DIAGNOSTICS_ENDPOINT.isNotBlank()) {
                crashReportChoice = mozilla.components.lib.crash.store.CrashReportOption.Auto.toString()
            }
        }
        maybeInitializeGlean()""")
    add(application, text)
    text = replace_once(files[application].decode("utf-8"), "        setDayNightTheme()", """        components.settings.apply {
            shouldUseBottomToolbar = false
            shouldUseExpandedToolbar = false
            isTabStripEnabled = resources.configuration.smallestScreenWidthDp >= 600
            microsurveyFeatureEnabled = false
            continuousOnboardingFeatureEnabled = false
            showPocketRecommendationsFeature = false
            showRecentTabsFeature = false
            showVoiceSearchInDisplayToolbar = false
        }
        setDayNightTheme()""")
    text = replace_once(text, "components.strictMode.enableStrictMode(true)",
                        "components.strictMode.enableStrictMode(withPenaltyDeath = false)")
    text = replace_once(text, "        initializeFenixProcess()", """        if (isMainProcess()) org.mozilla.fenix.upgrid.UpgridDiagnostics.installEarly(this)
        initializeFenixProcess()
        if (isMainProcess()) {
            org.mozilla.fenix.upgrid.UpgridDiagnostics.get(this).start(this, components.analytics.crashReporter)
        }""")
    add(application, text)

    crash_reporter = "mobile/android/android-components/components/lib/crash/src/main/java/mozilla/components/lib/crash/CrashReporter.kt"
    text = replace_once(original(crash_reporter), "    var enabled: Boolean = enabled", """    var onCrashCapture: ((Crash) -> Unit)? = null

    var enabled: Boolean = enabled""")
    text = replace_once(text, "        val crashWithTags = crash.withTags(runtimeTags)", """        runCatching { onCrashCapture?.invoke(crash) }
        val crashWithTags = crash.withTags(runtimeTags)""")
    add(crash_reporter, text)
    add("mobile/android/android-components/components/lib/crash/src/test/java/mozilla/components/lib/crash/UpgridCrashCaptureTest.kt",
        (HERE / "overlay/UpgridCrashCaptureTest.kt").read_text(encoding="utf-8"))
    analytics = f"{APP}/src/main/java/org/mozilla/fenix/components/Analytics.kt"
    add(analytics, replace_body(original(analytics), "val crashReporter: CrashReporter by lazyMonitored", """        val diagnostics = org.mozilla.fenix.upgrid.UpgridDiagnostics.get(context)
        CrashReporter(
            context = context,
            services = listOf(org.mozilla.fenix.upgrid.UpgridCrashService(diagnostics)),
            telemetryServices = emptyList(),
            shouldPrompt = CrashReporter.Prompt.NEVER,
            promptConfiguration = CrashReporter.PromptConfiguration(
                appName = context.getString(R.string.upgrid_app_name),
                organizationName = "Upgrid",
            ),
            enabled = true,
            useLegacyReporting = false,
            runtimeTagProviders = listOf(
                ReleaseRuntimeTagProvider(),
                BuildRuntimeTagProvider(context.versionInfoProvider),
            ),
        ).also { it.onCrashCapture = { crash -> diagnostics.recordCrash(crash) } }"""))
    support = f"{SUPPORT}/src/main/java/mozilla/components/support/webextensions/WebExtensionSupport.kt"
    text = replace_once(original(support), "        onExtensionsLoaded: ((List<WebExtension>) -> Unit)? = null,", """        onExtensionsLoaded: ((List<WebExtension>) -> Unit)? = null,
        autoGrantedExtensionIds: Set<String> = emptySet(),""")
    text = replace_once(text, "!installedExtensions.containsKey(extension.id) && !extension.isBuiltIn()",
                        "!installedExtensions.containsKey(extension.id) && !extension.isBuiltIn() && extension.id !in autoGrantedExtensionIds")
    text = replace_once(text, """                    onConfirm: (PermissionPromptResponse) -> Unit,
                ) {
                    store.dispatch(""", """                    onConfirm: (PermissionPromptResponse) -> Unit,
                ) {
                    if (extension.id in autoGrantedExtensionIds) {
                        onConfirm(PermissionPromptResponse(true, true, true))
                        return
                    }
                    store.dispatch(""")
    text = replace_once(text, """                    this@WebExtensionSupport.onUpdatePermissionRequest?.invoke(""", """                    if (extension.id in autoGrantedExtensionIds) {
                        onPermissionsGranted(true)
                        return
                    }
                    this@WebExtensionSupport.onUpdatePermissionRequest?.invoke(""")
    text = replace_once(text, """                    onPermissionsGranted: ((Boolean) -> Unit),
                ) {
                    store.dispatch(""", """                    onPermissionsGranted: ((Boolean) -> Unit),
                ) {
                    if (extension.id in autoGrantedExtensionIds) {
                        onPermissionsGranted(true)
                        return
                    }
                    store.dispatch(""")
    add(support, text)
    fragment = f"{APP}/src/main/java/org/mozilla/fenix/browser/BaseBrowserFragment.kt"
    text = replace_once(original(fragment), "    private val fullScreenFeature = ViewBoundFeatureWrapper<FullScreenFeature>()", """    private var upgridPlayerVisible = false
    private var upgridPlayerFeature: org.mozilla.fenix.upgrid.UpgridPlayerFeature? = null
    private val fullScreenFeature = ViewBoundFeatureWrapper<FullScreenFeature>()""")
    text = replace_once(text, """            initializeUI(view, tab)
            setupIMEInsetsHandling(view)""", """            initializeUI(view, tab)
            setupIMEInsetsHandling(view)
            // Session restoration may create this fragment before its selected tab exists.
            // Attach the player only after the tab-dependent toolbar and engine UI are ready.
            if (customTabSessionId == null && upgridPlayerFeature == null) {
                upgridPlayerFeature = org.mozilla.fenix.upgrid.UpgridPlayerFeature(
                    this, binding.browserLayout, requireComponents.upgridPlayer,
                    onVisibilityChanged = { visible ->
                        upgridPlayerVisible = visible
                        if (visible) expandBrowserView() else collapseBrowserView()
                        binding.swipeRefresh.isEnabled = !visible && shouldPullToRefreshBeEnabled(false)
                        (view as? SwipeGestureLayout)?.isSwipeEnabled = !visible
                    },
                )
            }""")
    text = replace_once(text, "        _browserToolbar = null", """        upgridPlayerFeature = null
        upgridPlayerVisible = false
        _browserToolbar = null""")
    text = text.replace("if (fullScreenFeature.get()?.isFullScreen == true) return 0 to 0",
                        "if (upgridPlayerVisible || fullScreenFeature.get()?.isFullScreen == true) return 0 to 0")
    text = text.replace("val shouldToolbarsBeHidden = isFullscreen || !webAppToolbarShouldBeVisible",
                        "val shouldToolbarsBeHidden = upgridPlayerVisible || isFullscreen || !webAppToolbarShouldBeVisible")
    add(fragment, text)

    toolbar = f"{APP}/src/main/java/org/mozilla/fenix/components/toolbar/BrowserToolbarMiddleware.kt"
    text = original(toolbar)
    text = replace_once(text, "import android.content.Context", "import android.content.Context\nimport android.widget.Toast\nimport org.mozilla.fenix.ext.components")
    text = replace_once(text, "    data class MenuClicked(override val source: Source)",
                        "    data object UpgridPlayerClicked : DisplayActions(Source.AddressBar.BrowserEnd)\n    data class MenuClicked(override val source: Source)")
    text = replace_once(text, "            is MenuClicked -> {", """            is DisplayActions.UpgridPlayerClicked -> {
                org.mozilla.fenix.upgrid.UpgridDiagnostics.get(uiContext).breadcrumb("player_button")
                if (!uiContext.components.upgridPlayer.requestTakeover()) {
                    Toast.makeText(uiContext, R.string.upgrid_player_not_ready, Toast.LENGTH_SHORT).show()
                }
                next(action)
            }
            is MenuClicked -> {""")
    text = replace_body(text, "private suspend fun buildEndBrowserActions()", """        return listOf(
            ActionButtonRes(
                drawableResId = R.drawable.upgrid_ic_video_play,
                contentDescription = R.string.upgrid_player_open,
                onClick = DisplayActions.UpgridPlayerClicked,
            ),
            buildAction(ToolbarAction.TabCounter, Source.AddressBar.BrowserEnd),
            buildAction(ToolbarAction.Menu, Source.AddressBar.BrowserEnd),
        )""")
    text = replace_body(text, "private fun buildEndPageActions()", "        return emptyList()")
    text = replace_body(text, "private suspend fun buildNavigationActions()", "        return emptyList()")
    add(toolbar, text)

    menu_dialog = f"{APP}/src/main/java/org/mozilla/fenix/components/menu/MenuDialogFragment.kt"
    add(menu_dialog, replace_once(original(menu_dialog), "        Events.toolbarMenuVisible.record(NoExtras())", """        org.mozilla.fenix.upgrid.UpgridDiagnostics.get(requireContext()).breadcrumb("menu_open")
        Events.toolbarMenuVisible.record(NoExtras())"""))

    home = f"{APP}/src/main/java/org/mozilla/fenix/home/ui/Homepage.kt"
    add(home, replace_body(original(home), "internal fun Homepage(", """    org.mozilla.fenix.upgrid.UpgridHomepage(
        privateMode = state.browsingMode.isPrivate,
        onSearch = interactor::onNavigateSearch,
        onPrivateMode = { interactor.onPrivateModeButtonClicked(if (state.browsingMode.isPrivate) BrowsingMode.Normal else BrowsingMode.Private) },
        modifier = modifier,
    )"""))
    menu = f"{APP}/src/main/java/org/mozilla/fenix/components/menu/compose/MainMenu.kt"
    add(menu, replace_body(original(menu), "fun MainMenu(", (HERE / "overlay/MainMenu.body.kt").read_text(encoding="utf-8")))

    settings = f"{APP}/src/main/java/org/mozilla/fenix/utils/Settings.kt"
    text = original(settings)
    text = replace_once(text, """        default = FxNimbus.features.tabStrip.value().enabled &&
                (isTabStripEligible(appContext) || FxNimbus.features.tabStrip.value().allowOnAllDevices),""",
                        "        default = appContext.resources.configuration.smallestScreenWidthDp >= 600,")
    text = replace_once(text, "default = { FxNimbus.features.defaultBottomToolbar.value().enabled },", "default = { false },")
    text = replace_once(text, "default = { FxNimbus.features.defaultExpandedToolbar.value().enabled },", "default = { false },")
    text = replace_body(text, "fun shouldShowOnboarding(", "        return false")
    text = replace_body(text, "fun shouldShowSetAsDefaultPrompt(", "        return false")
    text = replace_once(text, """        appContext.getPreferenceKey(R.string.pref_key_telemetry),
        default = true,""", """        appContext.getPreferenceKey(R.string.pref_key_telemetry),
        default = false,""")
    add(settings, text)
    settings_ui = f"{APP}/src/main/java/org/mozilla/fenix/settings/SettingsFragment.kt"
    text = replace_once(original(settings_ui), "        creatingFragment = false", """        listOf(
            R.string.pref_key_sign_in, R.string.pref_key_account_category,
            R.string.pref_key_sync_debug, R.string.pref_key_home,
            R.string.pref_key_email_masks, R.string.pref_key_page_summaries,
            R.string.pref_key_ai_controls, R.string.pref_key_ip_protection_settings,
            R.string.pref_key_override_amo_collection, R.string.pref_key_firefox_labs,
            R.string.pref_key_leakcanary, R.string.pref_key_remote_improvements,
            R.string.pref_key_rate, R.string.pref_key_debug_settings,
            R.string.pref_key_nimbus_experiments, R.string.pref_key_start_profiler,
        ).forEach { key -> findPreference<Preference>(getString(key))?.isVisible = false }
        creatingFragment = false""")
    add(settings_ui, text)
    build = f"{APP}/build.gradle"
    text = replace_once(original(build), 'applicationId "org.mozilla"',
                        'applicationId "com.upgrid.browser.next"')
    text = replace_once(text, "    implementation project('longfox')", '''    implementation project('longfox')
    // One pinned Media3 version for the independent video screen.
    implementation "androidx.media3:media3-exoplayer:1.11.0"
    implementation "androidx.media3:media3-exoplayer-hls:1.11.0"
    implementation "androidx.media3:media3-exoplayer-dash:1.11.0"
    implementation "androidx.media3:media3-ui:1.11.0"''')
    text = replace_once(text, 'applicationIdSuffix ".fenix.debug"', 'applicationIdSuffix ".debug"')
    text = replace_once(text, 'versionCode 1', f'versionCode {int(RELEASE["versionCode"])}')
    text = replace_once(text, 'versionName Config.generateDebugVersionName()',
                        'versionName ' + json.dumps(RELEASE["versionName"]))
    # The optimized build updates the existing Upgrid installation. In particular,
    # it must never inherit Firefox's shared UID or generated Firefox version code.
    text = replace_body(text, 'release releaseTemplate >>', '''            applicationIdSuffix ".debug"
            debuggable false
            buildConfigField "boolean", "USE_RELEASE_VERSIONING", "false"''')
    text = replace_once(text, "if (buildType in ['nightly', 'beta', 'release', 'benchmark'])",
                        "if (buildType in ['nightly', 'beta', 'benchmark'])")
    text = replace_once(text, '        versionCode ', '''        buildConfigField "String", "UPGRID_DIAGNOSTICS_ENDPOINT", groovy.json.JsonOutput.toJson(providers.environmentVariable("UPGRID_DIAGNOSTICS_ENDPOINT").getOrElse(""))
        buildConfigField "String", "UPGRID_DIAGNOSTICS_TOKEN", groovy.json.JsonOutput.toJson(providers.environmentVariable("UPGRID_DIAGNOSTICS_TOKEN").getOrElse(""))
        versionCode ''')
    add(build, text)
    manifest = f"{APP}/src/debug/AndroidManifest.xml"
    text = replace_once(original(manifest), 'tools:replace="android:name"',
                        'tools:replace="android:name,android:label,android:icon,android:roundIcon"')
    text = replace_once(text, 'android:name="org.mozilla.fenix.FenixApplication"', '''android:name="org.mozilla.fenix.FenixApplication"
        android:label="@string/upgrid_app_name"
        android:icon="@mipmap/upgrid_ic_launcher"
        android:roundIcon="@mipmap/upgrid_ic_launcher"''')
    add(manifest, text)
    add(f"{APP}/src/release/AndroidManifest.xml", '''<?xml version="1.0" encoding="utf-8"?>
<!-- This Source Code Form is subject to the terms of the Mozilla Public
   - License, v. 2.0. If a copy of the MPL was not distributed with this
   - file, You can obtain one at http://mozilla.org/MPL/2.0/. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application
        tools:replace="android:name,android:label,android:icon,android:roundIcon"
        android:name="org.mozilla.fenix.FenixApplication"
        android:label="@string/upgrid_app_name"
        android:icon="@mipmap/upgrid_ic_launcher"
        android:roundIcon="@mipmap/upgrid_ic_launcher">
        <profileable android:shell="true" />
    </application>
</manifest>
''')
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=Path)
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    checkout = args.checkout.resolve()
    commit = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
    if commit != UPSTREAM["commit"]:
        raise SystemExit(f"Wrong upstream commit: {commit}")
    files = generate(checkout)
    manifest = checkout / ".upgrid-overlay.json"
    previous = json.loads(manifest.read_text()) if manifest.exists() else {}
    for relative, content in files.items():
        target = (checkout / relative).resolve()
        if not target.is_relative_to(checkout):
            raise SystemExit(f"Path escapes checkout: {relative}")
        if args.check:
            if not target.exists() or target.read_bytes() != content:
                raise SystemExit(f"Overlay differs: {relative}")
            continue
        if not target.exists() or target.read_bytes() == content:
            continue
        current = target.read_bytes()
        if previous.get(relative) == digest(current):
            continue
        baseline = subprocess.run(["git", "-C", str(checkout), "show", f"HEAD:{relative}"],
                                  capture_output=True, check=False)
        if baseline.returncode or baseline.stdout != current:
            raise SystemExit(f"Local edits preserved; cannot overwrite: {relative}")
    if not args.check and not args.dry_run:
        for relative, content in files.items():
            target = checkout / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        manifest.write_text(json.dumps({p: digest(c) for p, c in files.items()}, indent=2) + "\n")
    action = "Verified" if args.check else "Prepared" if args.dry_run else "Applied"
    print(f"{action} {len(files)} Upgrid overlay files on {commit}")


if __name__ == "__main__":
    main()
