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
        text = text.replace("R.layout.view_native_player", "R.layout.upgrid_view_native_player")
        add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/{name}", kotlin_resources(text))
    add(f"{APP}/src/main/res/layout/upgrid_view_fullscreen_controls.xml",
        xml_resources((local / "res/layout/view_fullscreen_controls.xml").read_text(encoding="utf-8")))
    add(f"{APP}/src/main/res/layout/upgrid_view_native_player.xml",
        xml_resources((local / "res/layout/view_native_player.xml").read_text(encoding="utf-8")))
    add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/UpgridPlayerFeature.kt",
        (HERE / "overlay/UpgridPlayerFeature.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/UpgridAdblock.kt",
        (HERE / "overlay/UpgridAdblock.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/UpgridUi.kt",
        (HERE / "overlay/UpgridUi.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/UpgridDiagnostics.kt",
        (HERE / "overlay/UpgridDiagnostics.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/test/java/org/mozilla/fenix/upgrid/UpgridDiagnosticsTest.kt",
        (HERE / "overlay/UpgridDiagnosticsTest.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/test/java/org/mozilla/fenix/upgrid/UpgridMenuTest.kt",
        (HERE / "overlay/UpgridMenuTest.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/test/java/org/mozilla/fenix/upgrid/UpgridPlayerOrientationTest.kt",
        (HERE / "overlay/UpgridPlayerOrientationTest.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/test/java/org/mozilla/fenix/upgrid/UpgridPlayerControlsTest.kt",
        (HERE / "overlay/UpgridPlayerControlsTest.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/test/java/org/mozilla/fenix/upgrid/UpgridPlayerBridgeTest.kt",
        (HERE / "overlay/UpgridPlayerBridgeTest.kt").read_text(encoding="utf-8"))
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
    for name in ("manifest.json", "background.js", "player.js", "lifecycle.js", "preload.js"):
        add(f"{APP}/src/main/assets/extensions/upgrid_fullscreen/{name}",
            (local / f"assets/extensions/upgrid_fullscreen/{name}").read_text(encoding="utf-8")
            .replace("var nativePlayerEnabled = false;", "var nativePlayerEnabled = true;")
            .replace("var enginePlayerPreferred = false;", "var enginePlayerPreferred = true;"))

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
    add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/UpgridTabMemory.kt",
        (HERE / "overlay/UpgridTabMemory.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/test/java/org/mozilla/fenix/upgrid/UpgridTabMemoryTest.kt",
        (HERE / "overlay/UpgridTabMemoryTest.kt").read_text(encoding="utf-8"))
    add(f"{APP}/src/test/java/org/mozilla/fenix/upgrid/UpgridTabsTest.kt",
        (HERE / "overlay/UpgridTabsTest.kt").read_text(encoding="utf-8"))
    application = f"{APP}/src/main/java/org/mozilla/fenix/FenixApplication.kt"
    text = replace_once(original(application), "                onUpdatePermissionRequest = components.addonUpdater::onUpdatePermissionRequest,", """                onUpdatePermissionRequest = components.addonUpdater::onUpdatePermissionRequest,
                autoGrantedExtensionIds = setOf(org.mozilla.fenix.upgrid.UpgridAdblock.ID),""")
    text = replace_once(text, """            )
        } catch (e: UnsupportedOperationException) {
            logger.error("Failed to initialize web extension support", e)""", """            )
            components.upgridAdblock.ensureInstalled()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // Initialize the single bridge after extension support, before a
                // first page needs the player. No browser view is attached here.
                components.upgridPlayer
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
                    org.mozilla.fenix.upgrid.UpgridTabMemory(components.core.store),
                )
            }
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
            importBookmarksFeatureFlagEnabled = true
            // New tab actions create a real, restorable about:home tab immediately.
            // Apply on upgrades too; the upstream default depends on remote experiments.
            enableHomepageAsNewTab = true
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
                        // The real Gecko fullscreen observer alone owns toolbar geometry.
                        // Resizing here as well caused two layouts during one transition.
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
    text = replace_once(text, """            feature = MediaSessionFullscreenFeature(
                requireActivity(),
                context.components.core.store,
                customTabSessionId,
            ),""", """            feature = MediaSessionFullscreenFeature(
                requireActivity(),
                context.components.core.store,
                customTabSessionId,
                autoRotate = false,
            ),""")
    text = replace_once(text, """            feature = ScreenOrientationFeature(
                engine = requireComponents.core.engine,
                activity = requireActivity(),
            ),""", """            feature = ScreenOrientationFeature(
                engine = requireComponents.core.engine,
                activity = requireActivity(),
                allowOrientationChange = { upgridPlayerFeature?.isActive != true },
            ),""")
    add(fragment, text)

    media = "mobile/android/android-components/components/feature/media/src/main/java/mozilla/components/feature/media/fullscreen/MediaSessionFullscreenFeature.kt"
    text = replace_once(original(media), "    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,",
        "    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,\n    private val autoRotate: Boolean = true,")
    text = replace_once(text, "                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER",
        "                    if (autoRotate) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER")
    text = replace_once(text, "                if (store.state.findCustomTabOrSelectedTab(tabId)?.id == state.id) {",
        "                if (autoRotate && store.state.findCustomTabOrSelectedTab(tabId)?.id == state.id) {")
    add(media, text)
    orientation = "mobile/android/android-components/components/feature/session/src/main/java/mozilla/components/feature/session/ScreenOrientationFeature.kt"
    text = replace_once(original(orientation), "    private val buildVersionProvider: () -> Int = { Build.VERSION.SDK_INT },",
        "    private val buildVersionProvider: () -> Int = { Build.VERSION.SDK_INT },\n    private val allowOrientationChange: () -> Boolean = { true },")
    text = replace_once(text, "    override fun onOrientationLock(requestedOrientation: Int): LockResult {",
        "    override fun onOrientationLock(requestedOrientation: Int): LockResult {\n        if (!allowOrientationChange()) return LockResult.NOT_SUPPORTED")
    text = replace_once(text, "    override fun onOrientationUnlock() {",
        "    override fun onOrientationUnlock() {\n        if (!allowOrientationChange()) return")
    add(orientation, text)

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
    text = replace_once(text, "    private fun updateEndPageActions(store: Store<BrowserToolbarState, BrowserToolbarAction>) =",
                        "    private var upgridBookmarkJob: kotlinx.coroutines.Job? = null\n\n    private fun updateEndPageActions(store: Store<BrowserToolbarState, BrowserToolbarAction>) =")
    start = text.index("    private fun updateEndPageActions(")
    end = text.index("    /**", start)
    text = text[:start] + """    private fun updateEndPageActions(store: Store<BrowserToolbarState, BrowserToolbarAction>) {
        upgridBookmarkJob?.cancel()
        upgridBookmarkJob = scope.launch {
            val tab = browserStore.state.selectedTab
            val actions = buildEndPageActions()
            if (browserStore.state.selectedTab?.id == tab?.id &&
                browserStore.state.selectedTab?.content?.url == tab?.content?.url) {
                store.dispatch(PageActionsEndUpdated(actions))
            }
        }
    }

""" + text[end:]
    text = replace_once(text, "private fun buildEndPageActions()", "private suspend fun buildEndPageActions()")
    text = replace_body(text, "private suspend fun buildEndPageActions()", """        return listOf(buildAction(getBookmarkAction(), Source.AddressBar.PageEnd))""")
    text = replace_once(text, """                updateCurrentPageOrigin(store)
                updateEndBrowserActions(store)""", """                updateCurrentPageOrigin(store)
                updateEndPageActions(store)
                updateEndBrowserActions(store)""")
    text = replace_once(text, "            }.collect { isBookmarked ->", """            }.collect { isBookmarked ->
                updateEndPageActions(store)""")
    text = replace_once(text, """                    if (ShortcutType.fromValue(settings.activeSimpleToolbarShortcutKey) == ShortcutType.TRANSLATE) {
                        updateEndBrowserActions(store)
                    }""", "                    updateEndBrowserActions(store)")
    text = replace_once(text, """            buildAction(ToolbarAction.TabCounter, Source.AddressBar.BrowserEnd),""", """            buildAction(ToolbarAction.Translate, Source.AddressBar.BrowserEnd),
            buildAction(ToolbarAction.TabCounter, Source.AddressBar.BrowserEnd),""")
    text = replace_body(text, "private suspend fun buildNavigationActions()", "        return emptyList()")
    add(toolbar, text)
    toolbar_test = f"{APP}/src/test/java/org/mozilla/fenix/components/toolbar/BrowserToolbarMiddlewareTest.kt"
    add(toolbar_test, replace_once(original(toolbar_test), "class BrowserToolbarMiddlewareTest {",
        "class BrowserToolbarMiddlewareTest {\n" + (HERE / "overlay/UpgridToolbarTests.body.kt").read_text(encoding="utf-8")))

    menu_dialog = f"{APP}/src/main/java/org/mozilla/fenix/components/menu/MenuDialogFragment.kt"
    add(menu_dialog, replace_once(original(menu_dialog), "        Events.toolbarMenuVisible.record(NoExtras())", """        org.mozilla.fenix.upgrid.UpgridDiagnostics.get(requireContext()).breadcrumb("menu_open")
        Events.toolbarMenuVisible.record(NoExtras())"""))
    add(menu_dialog, replace_once(files[menu_dialog].decode("utf-8"),
        "            mainDispatcher = Dispatchers.Main,", """            mainDispatcher = Dispatchers.Main,
            upgridCompactMenu = args.accesspoint != MenuAccessPoint.External,"""))
    menu_middleware = f"{APP}/src/main/java/org/mozilla/fenix/components/menu/middleware/MenuDialogMiddleware.kt"
    text = replace_once(original(menu_middleware),
        "    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,", """    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val upgridCompactMenu: Boolean = false,""")
    text = replace_once(text, """        setupBookmarkState(store)
        setupPinnedState(store)
        setupExtensionState(store)
        setupPageSummarizationState(store)""", """        setupBookmarkState(store)
        // Upgrid's main menu has no shortcut or summarization actions. Avoid
        // their storage/engine work on every opening; keep custom tabs unchanged.
        if (!upgridCompactMenu) setupPinnedState(store)
        setupExtensionState(store)
        if (!upgridCompactMenu) setupPageSummarizationState(store)""")
    add(menu_middleware, text)

    home = f"{APP}/src/main/java/org/mozilla/fenix/home/ui/Homepage.kt"
    add(home, replace_body(original(home), "internal fun Homepage(", """    org.mozilla.fenix.upgrid.UpgridHomepage(
        privateMode = state.browsingMode.isPrivate,
        onSearch = interactor::onNavigateSearch,
        onPrivateMode = { interactor.onPrivateModeButtonClicked(if (state.browsingMode.isPrivate) BrowsingMode.Normal else BrowsingMode.Private) },
        modifier = modifier,
    )"""))
    request_interceptor = f"{APP}/src/main/java/org/mozilla/fenix/AppRequestInterceptor.kt"
    text = replace_body(original(request_interceptor),
        "private fun interceptAboutHomeRequest(uri: String)", """        // A request can come from an unselected empty tab or an old queued load.
        // AboutHomeBinding owns home navigation from the selected tab's state;
        // routing here can cover a newly loaded website with the home screen.
        return uri == ABOUT_HOME_URL""")
    text = replace_once(text, "Intercepts [uri] request to [ABOUT_HOME_URL] and navigates to the homepage.",
        "Recognizes [ABOUT_HOME_URL]; selected-tab state owns homepage navigation.")
    add(request_interceptor, text)
    about_home = f"{APP}/src/main/java/org/mozilla/fenix/AboutHomeBinding.kt"
    text = replace_once(original(about_home), "    browserStore: BrowserStore,", "    private val browserStore: BrowserStore,")
    text = replace_once(text, "            .map { it.selectedTab?.content?.url }",
        "            .map { it.selectedTab?.let { tab -> tab.id to tab.content.url } }")
    text = replace_once(text, "            .collect { url ->", """            .collect { selection ->
                val url = selection?.second
                val showHome = org.mozilla.fenix.upgrid.UpgridHomeNavigation.shouldShowHome(browserStore)""")
    text = replace_once(text, "                if (url == ABOUT_HOME_URL &&", """                if (url == ABOUT_HOME_URL &&
                    // Check both fresh state and a load still awaiting Gecko's location callback.
                    showHome &&""")
    add(about_home, text)
    add(f"{APP}/src/main/java/org/mozilla/fenix/upgrid/UpgridHomeNavigation.kt",
        (HERE / "overlay/UpgridHomeNavigation.kt").read_text(encoding="utf-8"))
    browser_use_cases = f"{APP}/src/main/java/org/mozilla/fenix/components/usecases/FenixBrowserUseCases.kt"
    text = replace_once(original(browser_use_cases), "    private val profiler: Profiler?,", """    private val profiler: Profiler?,
    private val onLoadStarted: (String) -> Unit = {},""")
    text = replace_once(text, "        val startTime = profiler?.getProfilerTime()", """        // Browser navigation may already be queued while content.url is still about:home.
        if (!newTab) onLoadStarted(searchTermOrURL)
        val startTime = profiler?.getProfilerTime()""")
    text = replace_once(text, """    fun navigateToHomepage() {
        loadUrlUseCase.invoke(url = ABOUT_HOME_URL)""", """    fun navigateToHomepage() {
        onLoadStarted(ABOUT_HOME_URL)
        loadUrlUseCase.invoke(url = ABOUT_HOME_URL)""")
    add(browser_use_cases, text)
    use_cases = f"{APP}/src/main/java/org/mozilla/fenix/components/UseCases.kt"
    text = replace_once(original(use_cases), "            homepageTitle = context.getString(R.string.tab_tray_homepage_tab),", """            homepageTitle = context.getString(R.string.tab_tray_homepage_tab),
            onLoadStarted = { input -> org.mozilla.fenix.upgrid.UpgridHomeNavigation.onLoadStarted(store.value, input) },""")
    add(use_cases, text)
    # Mark and dispatch the load before entering BrowserFragment. Navigation can
    # synchronously run observers while Gecko still reports about:home.
    for path, navigation in (
        (f"{APP}/src/main/java/org/mozilla/fenix/search/BrowserToolbarSearchMiddleware.kt",
         "        navController.navigate(\n            NavGraphDirections.actionGlobalBrowser(),\n        )"),
        (f"{APP}/src/main/java/org/mozilla/fenix/search/FenixSearchMiddleware.kt",
         "        navController.navigate(R.id.browserFragment)"),
        (f"{APP}/src/main/java/org/mozilla/fenix/HomeActivity.kt",
         "        openToBrowser(from, customTabSessionId)"),
    ):
        text = files.get(path, original(path).encode("utf-8")).decode("utf-8")
        call = text.index("fenixBrowserUseCases.loadUrlOrSearch(", text.index(navigation))
        start = text.rfind(navigation, 0, call)
        end = text.index("\n        )", call) + len("\n        )")
        block = text[start:end]
        text = text[:start] + block.replace(navigation + "\n", "", 1).lstrip("\n") + "\n" + navigation + text[end:]
        add(path, text)
    search_test = f"{APP}/src/test/java/org/mozilla/fenix/search/BrowserToolbarSearchMiddlewareTest.kt"
    text, count = re.subn(
        r"(        verifyOrder \{\n)(            navController.navigate\(NavGraphDirections.actionGlobalBrowser\(\)\)\n)(            browserUseCases.loadUrlOrSearch\([\s\S]*?            \)\n)",
        r"\1\3\2", original(search_test))
    if count != 5:
        raise ValueError("Search navigation order tests changed")
    add(search_test, text)
    linking = "mobile/android/android-components/components/browser/state/src/main/java/mozilla/components/browser/state/engine/middleware/LinkingMiddleware.kt"
    text = replace_once(original(linking), "    override fun invoke(", """    // A direct URL submission can overtake the queued initial about:home load.
    // Weak keys retain neither sessions nor browsing data after a tab is closed.
    private val explicitLoads = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<EngineSession, Boolean>(),
    )

    override fun invoke(""")
    text = replace_once(text, "        var engineObserver: Pair<String, EngineObserver>? = null", """        if (action is EngineAction.OptimizedLoadUrlTriggeredAction) {
            store.state.findTabOrCustomTab(action.tabId)?.engineState?.engineSession?.let {
                explicitLoads[it] = true
            }
        }
        var engineObserver: Pair<String, EngineObserver>? = null""")
    text = replace_once(text, """    ) = scope.launch {
        engineSession.loadUrl(""", """    ) = scope.launch {
        if (url == mozilla.components.concept.engine.utils.ABOUT_HOME_URL &&
            explicitLoads.remove(engineSession) == true
        ) {
            // The newer explicit load already reached this same engine session.
            return@launch
        }
        engineSession.loadUrl(""")
    add(linking, text)
    interceptor_test = f"{APP}/src/test/java/org/mozilla/fenix/AppRequestInterceptorTest.kt"
    text = original(interceptor_test)
    text = replace_once(text,
        "fun `GIVEN request to ABOUT_HOME WHEN request is intercepted THEN return a null interception response and navigate to the homepage`",
        "fun `GIVEN request to ABOUT_HOME WHEN request is intercepted THEN allow load and leave navigation to selected tab state`")
    text = replace_once(text, """        verify {
            navigationController.navigate(NavGraphDirections.actionGlobalHome())
        }""", """        verify(exactly = 0) {
            navigationController.navigate(NavGraphDirections.actionGlobalHome())
        }""")
    add(interceptor_test, text)
    add(f"{APP}/src/test/java/org/mozilla/fenix/upgrid/UpgridHomeRoutingTest.kt",
        (HERE / "overlay/UpgridHomeRoutingTest.kt").read_text(encoding="utf-8"))
    menu = f"{APP}/src/main/java/org/mozilla/fenix/components/menu/compose/MainMenu.kt"
    add(menu, replace_body(original(menu), "fun MainMenu(", (HERE / "overlay/MainMenu.body.kt").read_text(encoding="utf-8")))

    settings = f"{APP}/src/main/java/org/mozilla/fenix/utils/Settings.kt"
    text = original(settings)
    text = replace_once(text, """        default = FxNimbus.features.tabStrip.value().enabled &&
                (isTabStripEligible(appContext) || FxNimbus.features.tabStrip.value().allowOnAllDevices),""",
                        "        default = appContext.resources.configuration.smallestScreenWidthDp >= 600,")
    text = replace_once(text, "default = { FxNimbus.features.defaultBottomToolbar.value().enabled },", "default = { false },")
    text = replace_once(text, "default = { FxNimbus.features.defaultExpandedToolbar.value().enabled },", "default = { false },")
    text = replace_once(text, """        appContext.getPreferenceKey(R.string.pref_key_translations_offer),
        default = true,""", """        appContext.getPreferenceKey(R.string.pref_key_translations_offer),
        default = false,""")
    text = replace_body(text, "fun shouldShowOnboarding(", "        return false")
    text = replace_body(text, "fun shouldShowSetAsDefaultPrompt(", "        return false")
    text = replace_once(text, """        appContext.getPreferenceKey(R.string.pref_key_telemetry),
        default = true,""", """        appContext.getPreferenceKey(R.string.pref_key_telemetry),
        default = false,""")
    add(settings, text)
    desktop = f"{APP}/src/main/java/org/mozilla/fenix/browser/desktopmode/DesktopModeRepository.kt"
    add(desktop, replace_once(original(desktop), "        context.isLargeScreenSize()", "        false"))
    translations = f"{APP}/src/main/java/org/mozilla/fenix/browser/TranslationsBinding.kt"
    # Manual toolbar navigation remains intact, including its errors. Page-load
    # offers are acknowledged but never open a dialog over the user's page.
    text = replace_once(original(translations), """                    offerToTranslateCurrentPage()
                }

                // Trigger automatic popup""", """                    // Upgrid opens translation only from the toolbar.
                }

                // Trigger automatic popup""")
    add(translations, text)
    translations_test = f"{APP}/src/test/java/org/mozilla/fenix/browser/TranslationsBindingTest.kt"
    text = original(translations_test)
    test_start = text.index("fun `GIVEN translationState WHEN translation state isOfferTranslate is true")
    test_end = text.index("    @Test", test_start)
    text = text[:test_start] + replace_once(text[test_start:test_end],
        "assertEquals(1, onShowTranslationsDialogCount)", "assertEquals(0, onShowTranslationsDialogCount)") + text[test_end:]
    text = replace_once(text, """            verify { binding.recordTranslationStartTelemetry() }
            verify(atLeast = 1) { appStore.dispatch(SnackbarAction.SnackbarDismissed) }
            verify { navController.navigate(expectedNavigation) }""", """            verify(exactly = 0) { binding.recordTranslationStartTelemetry() }
            verify(exactly = 0) { navController.navigate(expectedNavigation) }""")
    add(translations_test, text)
    translation_worker = "toolkit/components/translations/content/translations-engine.worker.js"
    text = original(translation_worker)
    text = replace_once(text, """    return this.#getWorkQueue(innerWindowId).runTask(translationId, () =>
      this.#syncTranslate(sourceText, isHTML, innerWindowId)
    );""", """    return this.#getWorkQueue(innerWindowId).runTask(translationId, sourceText, isHTML);""")
    text = replace_once(text, "    workQueue = new WorkQueue(innerWindowId);", """    workQueue = new UpgridTranslationQueue(batch => this.#syncTranslate(batch, innerWindowId));""")
    text = replace_body(text, "  #syncTranslate(sourceText, isHTML, innerWindowId)",
                        (HERE / "overlay/TranslationBatch.body.js").read_text(encoding="utf-8"))
    text = replace_once(text, "  #syncTranslate(sourceText, isHTML, innerWindowId)",
                        "  #syncTranslate(batch, innerWindowId)")
    text = text.replace("@type {Map<number, WorkQueue>}", "@type {Map<number, UpgridTranslationQueue>}")
    text = text.replace("@returns {WorkQueue}", "@returns {UpgridTranslationQueue}")
    sync_start = text.index("  #syncTranslate(batch, innerWindowId)")
    comment_start = text.rfind("  /**", 0, sync_start)
    text = text[:comment_start] + """  /**
   * Translate independent fragments together, retaining HTML boundaries.
   * @param {Array<{sourceText: string, isHTML: boolean}>} batch
   * @param {number} innerWindowId
   * @returns {Array<{targetText: string, inferenceMilliseconds: number}>}
   */
""" + text[sync_start:]
    text += "\n" + (HERE / "overlay/UpgridTranslationQueue.js").read_text(encoding="utf-8")
    add(translation_worker, text)
    translation_document = "toolkit/components/translations/content/translations-document.sys.mjs"
    text = original(translation_document)
    # Supply a bounded group of visible fragments to the batch-capable worker.
    # Lower priorities, lazy viewport selection, cancellation and cache stay intact.
    text = replace_once(text, "new AntiStarvationStack(2, 1), // p0 stack",
                        "new AntiStarvationStack(8, 2), // p0 stack: visible content")
    text = replace_once(text, "new AntiStarvationStack(2, 1), // p1 stack",
                        "new AntiStarvationStack(4, 1), // p1 stack")
    # Apply completed siblings together even near the end of a page. The normal
    # 25 ms DOM update window is still short enough for captions and live text.
    text, count = re.subn(
        r"    if \(this\.#scheduler\.isWithinFinalBatches\(\)\) \{[\s\S]*?    \} else if \(!this\.(#hasPendingUpdate(?:Content|Attributes)Callback)\) \{",
        r"    if (!this.\1) {", text)
    if count != 2:
        raise ValueError("Translation DOM batching integration changed")
    add(translation_document, text)
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
    # Framework tertiary_text_dark is fixed gray and does not follow the app's
    # light/dark/private palette. Use the same semantic color as the form labels.
    search_form = f"{APP}/src/main/res/layout/fragment_save_search_engine.xml"
    text = original(search_form)
    if text.count("@android:color/tertiary_text_dark") != 2:
        raise ValueError("Search engine form helper colors changed upstream")
    add(search_form, text.replace("@android:color/tertiary_text_dark", "?attr/colorOnSurfaceVariant"))
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
