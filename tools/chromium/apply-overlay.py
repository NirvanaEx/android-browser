#!/usr/bin/env python3
"""Apply Upgrid's reviewed source overlay to its pinned Chromium revision."""
import argparse
import hashlib
import json
import pathlib
import subprocess
import sys

HERE = pathlib.Path(__file__).resolve().parent
MOJOM = "third_party/blink/public/mojom/media/fullscreen_video_element.mojom"
HEADER = "third_party/blink/renderer/core/frame/local_frame_mojo_handler.h"
SOURCE = "third_party/blink/renderer/core/frame/local_frame_mojo_handler.cc"
MARKER = "// Upgrid element-bound video channel."
MEDIA_H = "third_party/blink/renderer/core/html/media/html_media_element.h"
MEDIA_CC = "third_party/blink/renderer/core/html/media/html_media_element.cc"
MEDIA_TEST = "third_party/blink/renderer/core/html/media/html_media_element_test.cc"
ORIENTATION = "third_party/blink/renderer/modules/media_controls/media_controls_orientation_lock_delegate.cc"
REMOTE_CORE = "third_party/blink/renderer/core/html/media/remote_playback_controller.h"
REMOTE_MODULE = "third_party/blink/renderer/modules/remoteplayback/remote_playback.h"
JAVA_SOURCES = "chrome/android/chrome_java_sources.gni"
JAVA_BUILD = "chrome/android/BUILD.gn"
NATIVE_BUILD = "chrome/browser/BUILD.gn"
ACTIVITY = "chrome/android/java/src/org/chromium/chrome/browser/ChromeTabbedActivity.java"
CHROME_ACTIVITY = "chrome/android/java/src/org/chromium/chrome/browser/app/ChromeActivity.java"
MENU = "chrome/android/java/src/org/chromium/chrome/browser/app/appmenu/AppMenuPropertiesDelegateImpl.java"
PACKAGE = "chrome/android/chrome_public_apk_tmpl.gni"
LABEL = "chrome/android/java/res_chromium_base/values/channel_constants.xml"
SCREEN_API = "content/public/android/java/src/org/chromium/content_public/browser/ScreenOrientationProvider.java"
SCREEN_IMPL = "content/public/android/java/src/org/chromium/content/browser/ScreenOrientationProviderImpl.java"
SCREEN_TEST = "content/public/android/junit/src/org/chromium/content/browser/ScreenOrientationProviderImplTest.java"
TOOLBAR_LAYOUT = "chrome/browser/ui/android/toolbar/java/res/layout/toolbar_phone.xml"
TOOLBAR_JAVA = "chrome/browser/ui/android/toolbar/java/src/org/chromium/chrome/browser/toolbar/top/ToolbarPhone.java"
TABLET_LAYOUT = "chrome/browser/ui/android/toolbar/java/res/layout/toolbar_tablet.xml"
TABLET_JAVA = "chrome/browser/ui/android/toolbar/java/src/org/chromium/chrome/browser/toolbar/top/ToolbarTablet.java"
FULLSCREEN_CSS = "third_party/blink/renderer/core/css/fullscreen.css"
FULLSCREEN = "third_party/blink/renderer/core/fullscreen/fullscreen.cc"
UPDATE_XZ = "components/update_client/op_xz.cc"
UPDATE_XZ_TEST = "components/update_client/op_xz_unittest.cc"
TOOLBAR_BUILD = "chrome/browser/ui/android/toolbar/BUILD.gn"
MANIFEST = "chrome/android/java/AndroidManifest.xml"
EXTERNAL_PROVIDERS = "chrome/browser/extensions/external_provider_impl.cc"
CONTEXT_MENU = "chrome/android/java/src/org/chromium/chrome/browser/contextmenu/ContextMenuMediator.java"
CONTEXT_MENU_TEST = "chrome/android/junit/src/org/chromium/chrome/browser/contextmenu/ContextMenuMediatorTest.java"
TOOLBAR_OVERLAY = "chrome/browser/ui/android/toolbar/java/src/org/chromium/chrome/browser/toolbar/top/TopToolbarOverlayMediator.java"
TOOLBAR_POSITION = "chrome/browser/ui/android/toolbar/java/src/org/chromium/chrome/browser/toolbar/ToolbarPositionController.java"
LINT_CONFIG = "chrome/android/expectations/lint-suppressions.xml"
TRACKED = (MOJOM, HEADER, SOURCE, MEDIA_H, MEDIA_CC, MEDIA_TEST, ORIENTATION,
           REMOTE_CORE, REMOTE_MODULE, JAVA_SOURCES, JAVA_BUILD,
           NATIVE_BUILD, ACTIVITY, MENU, PACKAGE, SCREEN_API, SCREEN_IMPL, SCREEN_TEST, LABEL,
           TOOLBAR_LAYOUT, TOOLBAR_JAVA, TOOLBAR_BUILD, MANIFEST, EXTERNAL_PROVIDERS,
           CONTEXT_MENU, CONTEXT_MENU_TEST, CHROME_ACTIVITY, TOOLBAR_OVERLAY, LINT_CONFIG,
           TABLET_LAYOUT, TABLET_JAVA, FULLSCREEN_CSS, FULLSCREEN,
           UPDATE_XZ, UPDATE_XZ_TEST, TOOLBAR_POSITION)
NEW_FILES = {
    "chrome/browser/android/upgrid_player.cc": "player_android.cc",
    "chrome/browser/android/upgrid_translate.cc": "translate_android.cc",
    "chrome/android/java/src/org/chromium/chrome/browser/upgrid/UpgridTranslate.java": "UpgridTranslate.java",
    "chrome/android/java/src/org/chromium/chrome/browser/upgrid/UpgridPlayer.java": "UpgridPlayer.java",
    "chrome/android/java/src/org/chromium/chrome/browser/upgrid/UpgridScrubSession.java": "UpgridScrubSession.java",
    "chrome/android/java/src/org/chromium/chrome/browser/upgrid/UpgridPlayerCoordinator.java": "UpgridPlayerCoordinator.java",
    "chrome/android/java/src/org/chromium/chrome/browser/upgrid/UpgridOrientationLock.java": "UpgridOrientationLock.java",
    "chrome/browser/ui/android/toolbar/java/res/drawable/upgrid_player_button.xml": "upgrid_player_button.xml",
    "chrome/browser/ui/android/toolbar/java/res/values/upgrid_toolbar_strings.xml": "upgrid_toolbar_strings.xml",
    "chrome/android/java/res_chromium_base/drawable/upgrid_launcher.xml": "upgrid_launcher.xml",
    "chrome/android/java/res_chromium_base/drawable/upgrid_monochrome.xml": "upgrid_monochrome.xml",
}
PLAYER_DRAWABLES = (
    "ic_arrow_back", "ic_repeat", "ic_player_mirror", "ic_player_scale",
    "ic_rotate_phone", "ic_pause", "ic_play_filled", "ic_volume",
    "ic_fullscreen_exit", "ic_more_vert", "bg_fs_top_gradient", "bg_fs_bottom_gradient",
)


def replace_once(text, old, new, name):
    count = text.count(old)
    if count != 1:
        raise ValueError(f"{name}: expected one source anchor, found {count}")
    return text.replace(old, new, 1)


def fragment(name):
    return (HERE / "overlay" / name).read_text(encoding="utf-8")


def render_toolbar_position(source):
    return replace_once(source,
        "        boolean allowBottomAnchoredFocusedOmnibox =\n"
        "                ChromeFeatureList.sAndroidBottomToolbarV2.isEnabled();\n"
        "        boolean forceBottomForFocusedOmnibox =\n"
        "                isOmniboxFocused\n"
        "                        && (ChromeFeatureList.sAndroidBottomToolbarV2ForceBottomForFocusedOmnibox\n"
        "                                        .getValue()\n"
        "                                || (allowBottomAnchoredFocusedOmnibox\n"
        "                                        && !doesUserPreferTopToolbar));\n"
        "        @ControlsPosition int newControlsPosition;\n"
        "        if (!forceBottomForFocusedOmnibox\n"
        "                && (ntpShowing\n"
        "                        || tabSwitcherShowing\n"
        "                        || (isOmniboxFocused && !allowBottomAnchoredFocusedOmnibox)\n"
        "                        || isFindInPageShowing\n"
        "                        || doesUserPreferTopToolbar)) {",
        "        // Upgrid keeps editing above the keyboard, including when a saved\n"
        "        // bottom-toolbar preference or a field-trial parameter is active.\n"
        "        // Use the normal transition so dropdown anchors and offsets follow.\n"
        "        @ControlsPosition int newControlsPosition;\n"
        "        if (ntpShowing || tabSwitcherShowing || isOmniboxFocused\n"
        "                || isFindInPageShowing || doesUserPreferTopToolbar) {", TOOLBAR_POSITION)


def render(inputs):
    output = dict(inputs)
    output[TOOLBAR_POSITION] = render_toolbar_position(inputs[TOOLBAR_POSITION])
    # Exact .9 symbols identify op_xz::Done deleting the failed output on the
    # browser sequence. Keep DCHECK enabled; perform filesystem cleanup on a
    # MayBlock worker and deliver the result back to the original sequence.
    output[UPDATE_XZ] = replace_once(output[UPDATE_XZ],
        '#include "base/task/sequenced_task_runner.h"',
        '#include "base/task/sequenced_task_runner.h"\n'
        '#include "base/task/thread_pool.h"', UPDATE_XZ)
    output[UPDATE_XZ] = replace_once(output[UPDATE_XZ],
        '  base::SequencedTaskRunner::GetCurrentDefault()->PostTask(\n'
        '      FROM_HERE,\n'
        '      base::BindOnce(\n'
        '          std::move(callback),\n'
        '          [&]() -> base::expected<base::FilePath, CategorizedError> {',
        '  base::ThreadPool::PostTaskAndReplyWithResult(\n'
        '      FROM_HERE, {base::MayBlock()},\n'
        '      base::BindOnce(\n'
        '          [](base::FilePath out_file, bool success)\n'
        '              -> base::expected<base::FilePath, CategorizedError> {', UPDATE_XZ)
    output[UPDATE_XZ] = replace_once(output[UPDATE_XZ],
        '          }()));',
        '          }, out_file, success),\n'
        '      std::move(callback));', UPDATE_XZ)
    output[UPDATE_XZ_TEST] = replace_once(output[UPDATE_XZ_TEST],
        '#include "base/test/task_environment.h"',
        '#include "base/test/task_environment.h"\n'
        '#include "base/threading/thread_restrictions.h"', UPDATE_XZ_TEST)
    # Only BadPatch's event loop disallows blocking; file assertions run after
    # leaving that scope. The old Done callback DCHECKs inside this scope.
    prefix, bad_patch = output[UPDATE_XZ_TEST].split(
        'TEST_F(XzOperationTest, BadPatch) {', 1)
    bad_patch = replace_once(bad_patch, '  loop_.Run();',
        '  {\n'
        '    base::ScopedDisallowBlocking no_blocking_on_caller;\n'
        '    loop_.Run();\n'
        '  }\n'
        '  EXPECT_FALSE(base::PathExists(in_file.DirName().AppendUTF8("decoded_xz")));',
        UPDATE_XZ_TEST)
    output[UPDATE_XZ_TEST] = prefix + 'TEST_F(XzOperationTest, BadPatch) {' + bad_patch
    output[LINT_CONFIG] = replace_once(output[LINT_CONFIG],
        '  <issue id="UnusedResources">',
        '  <issue id="UnusedResources">\n'
        '    <!-- Upgrid launcher is referenced by the generated APK manifest (icon and\n'
        '         roundIcon); its adaptive XML references the bitmap and monochrome vector.\n'
        '         Chromium resource-library lint does not retain this manifest root. -->\n'
        '    <ignore path="**/res_chromium_base/drawable/upgrid_launcher.xml"/>\n'
        '    <ignore path="**/res_chromium_base/drawable/upgrid_monochrome.xml"/>\n'
        '    <ignore path="**/res_chromium_base/drawable-nodpi/upgrid_icon.png"/>', LINT_CONFIG)
    output[TOOLBAR_OVERLAY] = replace_once(output[TOOLBAR_OVERLAY],
        "    private float mViewportHeight;",
        "    private float mViewportHeight;\n    private long mUpgridLastDiagnostic;", TOOLBAR_OVERLAY)
    output[TOOLBAR_OVERLAY] = replace_once(output[TOOLBAR_OVERLAY],
        "    private void applyContentOffsetToModel(float contentOffset) {",
        "    private void applyContentOffsetToModel(float contentOffset) {\n"
        "        long now = android.os.SystemClock.uptimeMillis();\n"
        "        if (org.chromium.build.BuildConfig.ENABLE_ASSERTS\n"
        "                && now - mUpgridLastDiagnostic > 500) {\n"
        "            mUpgridLastDiagnostic = now;\n"
        '            org.chromium.base.Log.i("UpgridToolbar",\n'
        '                    "overlay content=%s top=%s height=%s y=%s android=%s refactor=%s",\n'
        "                    contentOffset, mBrowserControlsStateProvider.getTopControlOffset(),\n"
        "                    mBrowserControlsStateProvider.getTopControlsHeight(),\n"
        "                    mModel.get(TopToolbarOverlayProperties.Y_OFFSET),\n"
        "                    mBrowserControlsStateProvider.getAndroidControlsVisibility(),\n"
        "                    BrowserControlsUtils.isTopControlsRefactorOffsetEnabled());\n"
        "        }", TOOLBAR_OVERLAY)
    # Keep Chrome's native Translate menu/UI and service readiness checks.
    # An installed extension must not intercept this browser command.
    # MenuModelBridge supplies native extension actions without an Android menu ID.
    # The hierarchy controller already wraps those actions with dialog dismissal.
    # Replacing them routes ID 0 to ChromeContextMenuPopulator and crashes on tap.
    output[CONTEXT_MENU] = replace_once(output[CONTEXT_MENU],
        "            if (item.type == ListItemType.MENU_ITEM\n"
        "                    || item.type == ContextMenuItemType.CONTEXT_MENU_ITEM_WITH_ICON_BUTTON) {",
        "            if ((item.type == ListItemType.MENU_ITEM\n"
        "                            || item.type == ContextMenuItemType.CONTEXT_MENU_ITEM_WITH_ICON_BUTTON)\n"
        "                    && !hierarchicalMenuController.hasClickListener(item)) {",
        CONTEXT_MENU)
    output[CONTEXT_MENU_TEST] = replace_once(output[CONTEXT_MENU_TEST],
        "    private ModelList getItemList(List<ModelList> items, boolean hasHeader) {",
        fragment("context_menu_test.java.inc") + "\n"
        "    private ModelList getItemList(List<ModelList> items, boolean hasHeader) {",
        CONTEXT_MENU_TEST)
    output[EXTERNAL_PROVIDERS] = replace_once(output[EXTERNAL_PROVIDERS],
        '#include "extensions/common/extension.h"',
        '#include "extensions/common/extension.h"\n'
        '#include "extensions/common/extension_urls.h"', EXTERNAL_PROVIDERS)
    output[EXTERNAL_PROVIDERS] = replace_once(output[EXTERNAL_PROVIDERS],
        "namespace {", "namespace {\n\n" + fragment("default_adblock_loader.cc.inc"),
        EXTERNAL_PROVIDERS)
    output[EXTERNAL_PROVIDERS] = replace_once(output[EXTERNAL_PROVIDERS],
        "  // In tests don't install pre-installed apps.",
        fragment("default_adblock_provider.cc.inc") +
        "\n  // In tests don't install pre-installed apps.", EXTERNAL_PROVIDERS)
    output[MOJOM] = replace_once(output[MOJOM], "interface FullscreenVideoElementHandler {",
        fragment("video_protocol.mojom.inc") + "\ninterface FullscreenVideoElementHandler {", MOJOM)
    output[MOJOM] += "\ninterface UpgridVideoController {\n" + fragment("video_methods.mojom.inc") + "};\n"
    output[HEADER] = replace_once(output[HEADER], "class Document;",
        "class Document;\nclass Element;\nclass HTMLVideoElement;\nclass TextTrack;\nclass UpgridFullscreenGuard;", HEADER)
    output[HEADER] = replace_once(output[HEADER], "namespace blink {",
        '#include "third_party/blink/renderer/platform/heap/collection_support/heap_vector.h"\n\n'
        "namespace blink {", HEADER)
    output[HEADER] = replace_once(output[HEADER], "      public mojom::blink::FullscreenVideoElementHandler,",
        "      public mojom::blink::FullscreenVideoElementHandler,\n      public mojom::blink::UpgridVideoController,", HEADER)
    output[HEADER] = replace_once(output[HEADER], "  void RequestFullscreenVideoElement() final;",
        "  void RequestFullscreenVideoElement() final;\n\n" + fragment("video_declarations.h.inc"), HEADER)
    includes = ("#include <algorithm>\n#include <cmath>\n#include <tuple>\n"
        '#include "third_party/blink/renderer/core/css/css_property_value_set.h"\n'
        '#include "third_party/blink/renderer/bindings/core/v8/to_v8_traits.h"\n'
        '#include "third_party/blink/renderer/platform/bindings/v8_binding.h"\n'
        '#include "third_party/blink/renderer/core/dom/events/native_event_listener.h"\n'
        '#include "third_party/blink/renderer/core/frame/picture_in_picture_controller.h"\n'
        '#include "third_party/blink/renderer/core/html/media/remote_playback_controller.h"\n'
        '#include "third_party/blink/renderer/core/event_type_names.h"\n'
        '#include "third_party/blink/renderer/core/geometry/dom_rect.h"\n'
        '#include "third_party/blink/renderer/core/html/track/text_track.h"\n'
        '#include "third_party/blink/renderer/core/html/track/text_track_list.h"\n'
        '#include "third_party/blink/renderer/core/html_names.h"\n'
        '#include "third_party/blink/renderer/core/style/computed_style.h"\n')
    includes += '#include "third_party/blink/renderer/platform/loader/fetch/resource_request.h"\n'
    output[SOURCE] = replace_once(output[SOURCE], "namespace blink {", includes + "\nnamespace blink {\n" +
        fragment("video_fullscreen_guard.cc.inc"), SOURCE)
    output[SOURCE] = replace_once(output[SOURCE],
        "  auto* registry = frame.GetInterfaceRegistry();",
        "  auto* registry = frame.GetInterfaceRegistry();\n"
        "  registry->AddAssociatedInterface(BindRepeating(\n"
        "      &LocalFrameMojoHandler::BindUpgridVideoReceiver, WrapWeakPersistent(this)));", SOURCE)
    output[SOURCE] = replace_once(output[SOURCE], "  visitor->Trace(frame_);",
        "  visitor->Trace(frame_);\n  visitor->Trace(upgrid_video_);\n"
        "  visitor->Trace(upgrid_fullscreen_guard_);\n  visitor->Trace(upgrid_tracks_);\n"
        "  visitor->Trace(upgrid_video_receiver_);", SOURCE)
    output[SOURCE] = replace_once(output[SOURCE], "void LocalFrameMojoHandler::DidDetachFrame() {",
        "void LocalFrameMojoHandler::DidDetachFrame() {\n  ClearUpgridVideo(true, false);\n  upgrid_video_receiver_.reset();", SOURCE)
    output[SOURCE] = replace_once(output[SOURCE], "}  // namespace blink",
        fragment("video_implementation.cc.inc") + "\n" +
        fragment("video_site_options.cc.inc").replace("@@UPGRID_YOUTUBE_OPTIONS@@",
            fragment("youtube-options.js")) + "\n}  // namespace blink", SOURCE)
    output[MEDIA_H] = replace_once(output[MEDIA_H], "  bool ShouldShowControls() const;",
        "  bool ShouldShowControls() const;\n  void SetUpgridControlsHidden(bool hidden);\n"
        "  bool IsUpgridPlayerActive() const { return upgrid_controls_hidden_; }\n"
        "  uint64_t UpgridSourceEpoch() const { return upgrid_source_epoch_; }\n"
        "  static HeapVector<Member<HTMLMediaElement>> UpgridMediaCandidates(Document&);\n"
        "  static Element* UpgridFullscreenTarget(Element&);", MEDIA_H)
    output[MEDIA_H] = replace_once(output[MEDIA_H], "  std::optional<bool> user_wants_controls_visible_;",
        "  bool upgrid_controls_hidden_ = false;\n  uint64_t upgrid_source_epoch_ = 0;\n"
        "  std::optional<bool> user_wants_controls_visible_;", MEDIA_H)
    output[MEDIA_CC] = replace_once(output[MEDIA_CC], "void HTMLMediaElement::InvokeLoadAlgorithm() {",
        "void HTMLMediaElement::InvokeLoadAlgorithm() {\n  ++upgrid_source_epoch_;", MEDIA_CC)
    output[MEDIA_CC] = replace_once(output[MEDIA_CC], "namespace blink {",
        '#include <cmath>\n'
        '#include "third_party/blink/renderer/core/geometry/dom_rect.h"\n'
        '#include "third_party/blink/renderer/core/style/computed_style.h"\n\n'
        'namespace blink {', MEDIA_CC)
    output[MEDIA_CC] = replace_once(output[MEDIA_CC], "bool HTMLMediaElement::ShouldShowControls() const {",
        fragment("video_registry.cc.inc") + fragment("video_fullscreen_target.cc.inc") +
        "void HTMLMediaElement::SetUpgridControlsHidden(bool hidden) {\n"
        "  upgrid_controls_hidden_ = hidden;\n  UpdateControlsVisibility();\n}\n\n"
        "bool HTMLMediaElement::ShouldShowControls() const {\n"
        "  if (upgrid_controls_hidden_) {\n"
        "    if (Fullscreen::IsFullscreenElement(*this))\n"
        "      return false;\n  }", MEDIA_CC)
    output[MEDIA_TEST] = replace_once(output[MEDIA_TEST], "}  // namespace blink",
        fragment("video_controls_test.cc.inc") + "\n}  // namespace blink", MEDIA_TEST)
    # Keep fullscreen.css in the write set to restore the previous overlay's
    # persistent-video rule when updating a cached Chromium checkout.
    output[FULLSCREEN_CSS] = inputs[FULLSCREEN_CSS]
    output[FULLSCREEN] = replace_once(output[FULLSCREEN],
        '#include "third_party/blink/renderer/core/html/html_body_element.h"',
        '#include "third_party/blink/renderer/core/html/media/html_media_element.h"\n'
        '#include "third_party/blink/renderer/core/html/html_body_element.h"', FULLSCREEN)
    output[FULLSCREEN] = replace_once(output[FULLSCREEN],
        '  LocalDOMWindow& window = *document.domWindow();\n\n  // 8. If `error` is false:',
        '  // The original request already passed activation and permissions.\n'
        '  // Select a video in that same container/document before entering the\n'
        '  // top layer, avoiding a second fullscreen transition or CSS capture.\n'
        '  if (!(request_type & FullscreenRequestType::kForCrossProcessDescendant))\n'
        '    pending = HTMLMediaElement::UpgridFullscreenTarget(*pending);\n\n'
        '  LocalDOMWindow& window = *document.domWindow();\n\n  // 8. If `error` is false:', FULLSCREEN)
    output[MEDIA_TEST] = replace_once(output[MEDIA_TEST],
        '#include "third_party/blink/renderer/core/dom/element.h"',
        '#include "third_party/blink/renderer/core/dom/element.h"\n'
        '#include "third_party/blink/renderer/core/dom/shadow_root.h"', MEDIA_TEST)
    output[ORIENTATION] = replace_once(output[ORIENTATION],
        "  state_ = State::kMaybeLockedFullscreen;",
        "  state_ = State::kMaybeLockedFullscreen;\n"
        "  if (VideoElement().IsUpgridPlayerActive())\n    return;", ORIENTATION)
    output[REMOTE_CORE] = replace_once(output[REMOTE_CORE],
        "  virtual void AddObserver(RemotePlaybackObserver*) = 0;",
        "  virtual bool CanPromptForUpgrid() const = 0;\n"
        "  virtual void PromptForUpgrid() = 0;\n\n"
        "  virtual void AddObserver(RemotePlaybackObserver*) = 0;", REMOTE_CORE)
    output[REMOTE_MODULE] = replace_once(output[REMOTE_MODULE],
        "  void PromptInternal();",
        "  void PromptInternal();\n"
        "  bool CanPromptForUpgrid() const override { return RemotePlaybackAvailable(); }\n"
        "  void PromptForUpgrid() override { PromptInternal(); }", REMOTE_MODULE)
    java_path = 'java/src/org/chromium/chrome/browser/upgrid/UpgridPlayer.java'
    anchor = '"java/src/org/chromium/chrome/browser/DevToolsServer.java",'
    for path in (JAVA_SOURCES, JAVA_BUILD):
        output[path] = replace_once(output[path], anchor, anchor + '\n      "' + java_path + '",', path)
        output[path] = replace_once(output[path], anchor, anchor +
            '\n      "java/src/org/chromium/chrome/browser/upgrid/UpgridTranslate.java",', path)
    output[JAVA_SOURCES] = replace_once(output[JAVA_SOURCES], '"' + java_path + '",',
        '"' + java_path + '",\n      "java/src/org/chromium/chrome/browser/upgrid/UpgridPlayerCoordinator.java",',
        JAVA_SOURCES)
    output[JAVA_SOURCES] = replace_once(output[JAVA_SOURCES], '"' + java_path + '",',
        '"' + java_path + '",\n      "java/src/org/chromium/chrome/browser/upgrid/UpgridOrientationLock.java",',
        JAVA_SOURCES)
    output[JAVA_SOURCES] = replace_once(output[JAVA_SOURCES], '"' + java_path + '",',
        '"' + java_path + '",\n      "java/src/org/chromium/chrome/browser/upgrid/UpgridScrubSession.java",',
        JAVA_SOURCES)
    anchor = '"android/devtools_server.cc",'
    output[NATIVE_BUILD] = replace_once(output[NATIVE_BUILD], anchor,
        anchor + '\n      "android/upgrid_player.cc",\n      "android/upgrid_translate.cc",', NATIVE_BUILD)
    anchor = "        boolean currentTabIsNtp = isTabNtp(currentTab);"
    output[ACTIVITY] = replace_once(output[ACTIVITY], anchor, anchor +
        "\n        if (id == org.chromium.chrome.browser.upgrid.UpgridPlayer.MENU_ID) {\n"
        "            org.chromium.chrome.browser.upgrid.UpgridPlayer.open(this, currentTab);\n"
        "            return true;\n        }", ACTIVITY)
    output[ACTIVITY] = replace_once(output[ACTIVITY],
        "import org.chromium.chrome.browser.tab.Tab;",
        "import org.chromium.chrome.browser.tab.Tab;\n"
        "import org.chromium.chrome.browser.upgrid.UpgridPlayerCoordinator;", ACTIVITY)
    output[ACTIVITY] = replace_once(output[ACTIVITY],
        "    private TabModelSelectorTabObserver mTabModelSelectorTabObserver;",
        "    private TabModelSelectorTabObserver mTabModelSelectorTabObserver;\n"
        "    private @Nullable UpgridPlayerCoordinator mUpgridPlayerCoordinator;",
        ACTIVITY)
    output[ACTIVITY] = replace_once(output[ACTIVITY],
        "            recordFirstAppLaunchTimestampIfNeeded();",
        "            mUpgridPlayerCoordinator =\n"
        "                    new org.chromium.chrome.browser.upgrid.UpgridPlayerCoordinator(\n"
        "                            this, getFullscreenManager(), this::getActivityTab);\n\n"
        "            recordFirstAppLaunchTimestampIfNeeded();", ACTIVITY)
    output[ACTIVITY] = replace_once(output[ACTIVITY],
        "    public void onDestroyInternal() {",
        "    public void onDestroyInternal() {\n"
        "        if (mUpgridPlayerCoordinator != null) {\n"
        "            mUpgridPlayerCoordinator.destroy();\n"
        "            mUpgridPlayerCoordinator = null;\n        }", ACTIVITY)
    output[TOOLBAR_LAYOUT] = replace_once(output[TOOLBAR_LAYOUT],
        '        <include layout="@layout/menu_button"/>',
        '        <org.chromium.ui.widget.ChromeImageButton\n'
        '            android:id="@+id/upgrid_player_button"\n'
        '            style="@style/ToolbarHoverableButton"\n'
        '            android:src="@drawable/upgrid_player_button"\n'
        '            android:contentDescription="@string/upgrid_player_button_label"\n'
        '            android:tooltipText="@string/upgrid_player_button_label"\n'
        '            android:layout_gravity="top"\n'
        '            app:tint="@color/default_icon_color_tint_list"/>\n\n'
        '        <include layout="@layout/menu_button"/>', TOOLBAR_LAYOUT)
    output[TABLET_LAYOUT] = replace_once(output[TABLET_LAYOUT],
        '        <include layout="@layout/menu_button"/>',
        '        <org.chromium.ui.widget.ChromeImageButton\n'
        '            android:id="@+id/upgrid_player_button"\n'
        '            style="@style/ToolbarHoverableButton.AdaptiveDensity"\n'
        '            android:src="@drawable/upgrid_player_button"\n'
        '            android:contentDescription="@string/upgrid_player_button_label"\n'
        '            android:tooltipText="@string/upgrid_player_button_label"\n'
        '            app:tint="@color/default_icon_color_tint_list"/>\n\n'
        '        <include layout="@layout/menu_button"/>', TABLET_LAYOUT)
    output[TABLET_JAVA] = replace_once(output[TABLET_JAVA],
        "    private ImageButton mHomeButton;",
        "    private ImageButton mHomeButton;\n"
        "    private ImageButton mUpgridPlayerButton;", TABLET_JAVA)
    output[TABLET_JAVA] = replace_once(output[TABLET_JAVA],
        "        mHomeButton = findViewById(R.id.home_button);",
        "        mHomeButton = findViewById(R.id.home_button);\n"
        "        mUpgridPlayerButton = findViewById(R.id.upgrid_player_button);", TABLET_JAVA)
    output[TABLET_JAVA] = replace_once(output[TABLET_JAVA],
        "        ImageViewCompat.setImageTintList(mHomeButton, activityFocusTint);",
        "        ImageViewCompat.setImageTintList(mHomeButton, activityFocusTint);\n"
        "        ImageViewCompat.setImageTintList(mUpgridPlayerButton, activityFocusTint);", TABLET_JAVA)
    output[TABLET_JAVA] = replace_once(output[TABLET_JAVA],
        "        mHomeButton.setBackgroundResource(toolbarIconRippleId);",
        "        mHomeButton.setBackgroundResource(toolbarIconRippleId);\n"
        "        mUpgridPlayerButton.setBackgroundResource(toolbarIconRippleId);", TABLET_JAVA)
    # Reserve the player's measured width in both tablet allocation passes and
    # resize callbacks. It stays beside Menu even when optional buttons hide.
    output[TABLET_JAVA] = replace_once(output[TABLET_JAVA],
        "        int width = MeasureSpec.getSize(widthMeasureSpec);",
        "        int width = MeasureSpec.getSize(widthMeasureSpec);\n"
        "        mUpgridPlayerButton.measure(\n"
        "                MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), heightMeasureSpec);\n"
        "        width = Math.max(0, width - mUpgridPlayerButton.getMeasuredWidth());", TABLET_JAVA)
    output[TABLET_JAVA] = replace_once(output[TABLET_JAVA],
        "                mToolbarWidthConsumers, getWidth(), unspecifiedSpec, unspecifiedSpec);",
        "                mToolbarWidthConsumers,\n"
        "                Math.max(0, getWidth() - mUpgridPlayerButton.getMeasuredWidth()),\n"
        "                unspecifiedSpec, unspecifiedSpec);", TABLET_JAVA)
    output[TOOLBAR_BUILD] = replace_once(output[TOOLBAR_BUILD],
        '    "java/res/layout/toolbar_phone.xml",',
        '    "java/res/drawable/upgrid_player_button.xml",\n'
        + ''.join(f'    "java/res/drawable/upgrid_{name}.xml",\n' for name in PLAYER_DRAWABLES)
        +
        '    "java/res/values/upgrid_toolbar_strings.xml",\n'
        '    "java/res/layout/toolbar_phone.xml",', TOOLBAR_BUILD)
    output[TOOLBAR_JAVA] = replace_once(output[TOOLBAR_JAVA],
        "import org.chromium.ui.base.ViewUtils;",
        "import org.chromium.ui.base.ViewUtils;\n"
        "import org.chromium.ui.widget.ChromeImageButton;", TOOLBAR_JAVA)
    output[TOOLBAR_JAVA] = replace_once(output[TOOLBAR_JAVA],
        "    private ViewGroup mToolbarButtonsContainer;",
        "    private ViewGroup mToolbarButtonsContainer;\n"
        "    private @MonotonicNonNull ChromeImageButton mUpgridPlayerButton;",
        TOOLBAR_JAVA)
    output[TOOLBAR_JAVA] = replace_once(output[TOOLBAR_JAVA],
        "            mToolbarButtonsContainer = findViewById(R.id.toolbar_buttons);",
        "            mToolbarButtonsContainer = findViewById(R.id.toolbar_buttons);\n"
        "            mUpgridPlayerButton = findViewById(R.id.upgrid_player_button);", TOOLBAR_JAVA)
    output[TOOLBAR_JAVA] = replace_once(output[TOOLBAR_JAVA],
        "        if (mOptionalButtonCoordinator != null) {\n"
        "            mOptionalButtonCoordinator.setIconForegroundColor(tint);",
        "        if (mUpgridPlayerButton != null) mUpgridPlayerButton.setImageTintList(tint);\n"
        "        if (mOptionalButtonCoordinator != null) {\n"
        "            mOptionalButtonCoordinator.setIconForegroundColor(tint);", TOOLBAR_JAVA)
    output[TOOLBAR_JAVA] = replace_once(output[TOOLBAR_JAVA],
        "        // Draw the tab stack button and associated text if necessary.",
        "        if (mUpgridPlayerButton != null && mUpgridPlayerButton.getVisibility() == VISIBLE) {\n"
        "            canvas.save();\n"
        "            ViewUtils.translateCanvasToView(mToolbarButtonsContainer, mUpgridPlayerButton, canvas);\n"
        "            mUpgridPlayerButton.draw(canvas);\n"
        "            canvas.restore();\n        }\n\n"
        "        // Draw the tab stack button and associated text if necessary.", TOOLBAR_JAVA)
    anchor = "        mModelList = buildMenuModelList();"
    output[MENU] = replace_once(output[MENU], anchor, anchor +
        "\n        if (!isInTabSwitcher()) {\n"
        "            mModelList.add(new MVCListAdapter.ListItem(AppMenuHandler.AppMenuItemType.STANDARD,\n"
        "                    new PropertyModel.Builder(AppMenuItemProperties.ALL_KEYS)\n"
        "                            .with(AppMenuItemProperties.MENU_ITEM_ID,\n"
        "                                    org.chromium.chrome.browser.upgrid.UpgridPlayer.MENU_ID)\n"
        '                            .with(AppMenuItemProperties.TITLE, "Видеоплеер Upgrid")\n'
        "                            .with(AppMenuItemProperties.ENABLED, true).build()));\n"
        "        }", MENU)
    output[PACKAGE] = replace_once(output[PACKAGE],
        'chrome_public_manifest_package = "org.chromium.chrome"',
        'chrome_public_manifest_package = "com.upgrid.chromium"', PACKAGE)
    for attribute in ("icon", "roundIcon"):
        old = "ic_launcher_round" if attribute == "roundIcon" else "ic_launcher"
        output[MANIFEST] = replace_once(output[MANIFEST],
            f'android:{attribute}="@drawable/{old}"',
            f'android:{attribute}="@drawable/upgrid_launcher"', MANIFEST)
    icon_anchor = '      "java/res_chromium_base/drawable/themed_app_icon.xml",'
    if output[JAVA_BUILD].count(icon_anchor) != 2:
        raise ValueError("Expected base and monochrome Chromium resource lists")
    output[JAVA_BUILD] = output[JAVA_BUILD].replace(icon_anchor, icon_anchor +
        '\n      "java/res_chromium_base/drawable/upgrid_launcher.xml",'
        '\n      "java/res_chromium_base/drawable/upgrid_monochrome.xml",'
        '\n      "java/res_chromium_base/drawable-nodpi/upgrid_icon.png",')
    output[LABEL] = replace_once(output[LABEL],
        '<string name="app_name" translatable="false">Chromium</string>',
        '<string name="app_name" translatable="false">Upgrid Chromium Dev</string>', LABEL)
    output[LABEL] = replace_once(output[LABEL], '</resources>',
        '    <string name="upgrid_player_time_range" translatable="false">%1$s / %2$s</string>\n'
        '    <string name="upgrid_player_live_time" translatable="false">%1$s · Прямой эфир</string>\n'
        '</resources>', LABEL)
    output[SCREEN_API] = replace_once(output[SCREEN_API],
        "    void setOrientationDelegate(@Nullable ScreenOrientationDelegate delegate);",
        "    void setOrientationDelegate(@Nullable ScreenOrientationDelegate delegate);\n\n"
        "    /** Keeps this window under manual browser control until released. */\n"
        "    void setUserControlledOrientation(WindowAndroid window, boolean controlled);", SCREEN_API)
    output[SCREEN_IMPL] = replace_once(output[SCREEN_IMPL],
        "    private @Nullable ScreenOrientationDelegate mDelegate;",
        "    private @Nullable ScreenOrientationDelegate mDelegate;\n"
        "    private final Map<Activity, Boolean> mUserControlledOrientation = new WeakHashMap<>();\n\n"
        "    @Override\n"
        "    public void setUserControlledOrientation(WindowAndroid window, boolean controlled) {\n"
        "        Activity activity = window.getActivity().get();\n"
        "        if (activity == null) return;\n"
        "        if (controlled) mUserControlledOrientation.put(activity, true);\n"
        "        else mUserControlledOrientation.remove(activity);\n"
        "    }", SCREEN_IMPL)
    output[SCREEN_IMPL] = replace_once(output[SCREEN_IMPL],
        "            mDelayedRequests.remove(activity);",
        "            mDelayedRequests.remove(activity);\n"
        "            mUserControlledOrientation.remove(activity);", SCREEN_IMPL)
    output[SCREEN_IMPL] = replace_once(output[SCREEN_IMPL],
        "            Activity activity, boolean lock, int orientation) {\n",
        "            Activity activity, boolean lock, int orientation) {\n"
        "        if (mUserControlledOrientation.containsKey(activity)) return;\n", SCREEN_IMPL)
    output[SCREEN_IMPL] = replace_once(output[SCREEN_IMPL],
        "    private void setRequestedOrientationNow(Activity activity, boolean lock, int orientation) {",
        "    private void setRequestedOrientationNow(Activity activity, boolean lock, int orientation) {\n"
        "        if (mUserControlledOrientation.containsKey(activity)) return;", SCREEN_IMPL)
    output[SCREEN_TEST] = replace_once(output[SCREEN_TEST],
        "    private ActivityWindowAndroid buildMockWindowForActivity(Activity activity) {",
        fragment("orientation_test.java.inc") + "\n"
        "    private ActivityWindowAndroid buildMockWindowForActivity(Activity activity) {", SCREEN_TEST)
    result = {name: text if name.endswith((".xml", ".css")) else
              (MARKER.replace("//", "#", 1) if name.endswith((".gn", ".gni")) else MARKER) +
              "\n" + text for name, text in output.items()}
    result.update({name: fragment(source) for name, source in NEW_FILES.items()})
    drawable_root = HERE.parents[1] / "app/src/main/res/drawable"
    result.update({
        f"chrome/browser/ui/android/toolbar/java/res/drawable/upgrid_{name}.xml":
            (drawable_root / f"{name}.xml").read_text(encoding="utf-8")
        for name in PLAYER_DRAWABLES
    })
    result["chrome/android/java/res_chromium_base/drawable-nodpi/upgrid_icon.png"] = (
        HERE / "branding/upgrid-icon-432.png").read_bytes()
    return result


def sha(data):
    return hashlib.sha256(as_bytes(data)).hexdigest()


def as_bytes(data):
    return data if isinstance(data, bytes) else data.encode("utf-8")


def read_content(path, desired):
    return path.read_bytes() if isinstance(desired, bytes) else path.read_text(encoding="utf-8")


def validate_changes(root, originals, desired, previous_files):
    """Check the entire write set before allowing the first mutation."""
    for name, text in desired.items():
        actual = read_content(root / name, text) if (root / name).exists() else None
        prior = previous_files.get(name, {})
        known_hashes = {prior.get("after"), prior.get("previousAfter")}
        if actual not in (originals.get(name), text) and (actual is None or sha(actual) not in known_hashes):
            raise ValueError(f"Local changes in {name}; preserving them")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("checkout", type=pathlib.Path)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--snapshot", action="store_true",
                        help="Allow a non-Git reference snapshot; always read-only")
    args = parser.parse_args()
    root = args.checkout.resolve()
    pinned = json.loads((HERE / "upstream.json").read_text())["commit"]
    originals = {}
    if args.snapshot:
        for name in TRACKED:
            originals[name] = (root / name).read_text(encoding="utf-8")
    else:
        head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
        if head != pinned:
            raise ValueError(f"Unexpected Chromium revision {head}; expected {pinned}")
        for name in TRACKED:
            originals[name] = subprocess.check_output(["git", "show", f"HEAD:{name}"], cwd=root).decode("utf-8")
    desired = render(originals)
    receipt_path = root.parent / "upgrid-overlay-receipt.json"
    previous = json.loads(receipt_path.read_text()) if receipt_path.exists() else {}
    previous_files = previous.get("files", {}) if previous.get("revision") == pinned else {}
    # Validate every file before modifying any. Refuse unrelated user changes.
    validate_changes(root, originals, desired, previous_files)
    receipt = {"revision": pinned, "snapshotOnly": args.snapshot,
               "applied": not args.dry_run and not args.snapshot,
               "compiled": False,
               "files": {name: {"before": sha(originals[name]) if name in originals else None,
                                "previousAfter": sha(read_content(root / name, text))
                                    if (root / name).exists() else None,
                                "after": sha(text)}
                         for name, text in desired.items()}}
    if receipt["applied"]:
        # Save expected hashes first, so an interrupted write can be resumed.
        receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
        for name, text in desired.items():
            target = root / name
            # Preserve timestamps for unchanged files, especially Mojo headers:
            # rewriting them would invalidate thousands of incremental targets.
            if target.exists() and target.read_bytes() == as_bytes(text):
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(as_bytes(text))
    print(json.dumps(receipt, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
