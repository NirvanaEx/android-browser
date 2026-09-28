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
MENU = "chrome/android/java/src/org/chromium/chrome/browser/app/appmenu/AppMenuPropertiesDelegateImpl.java"
PACKAGE = "chrome/android/chrome_public_apk_tmpl.gni"
LABEL = "chrome/android/java/res_chromium_base/values/channel_constants.xml"
SCREEN_API = "content/public/android/java/src/org/chromium/content_public/browser/ScreenOrientationProvider.java"
SCREEN_IMPL = "content/public/android/java/src/org/chromium/content/browser/ScreenOrientationProviderImpl.java"
SCREEN_TEST = "content/public/android/junit/src/org/chromium/content/browser/ScreenOrientationProviderImplTest.java"
TRACKED = (MOJOM, HEADER, SOURCE, MEDIA_H, MEDIA_CC, MEDIA_TEST, ORIENTATION,
           REMOTE_CORE, REMOTE_MODULE, JAVA_SOURCES, JAVA_BUILD,
           NATIVE_BUILD, ACTIVITY, MENU, PACKAGE, SCREEN_API, SCREEN_IMPL, SCREEN_TEST, LABEL)
NEW_FILES = {
    "chrome/browser/android/upgrid_player.cc": "player_android.cc",
    "chrome/android/java/src/org/chromium/chrome/browser/upgrid/UpgridPlayer.java": "UpgridPlayer.java",
    "chrome/android/java/src/org/chromium/chrome/browser/upgrid/UpgridScrubSession.java": "UpgridScrubSession.java",
}


def replace_once(text, old, new, name):
    count = text.count(old)
    if count != 1:
        raise ValueError(f"{name}: expected one source anchor, found {count}")
    return text.replace(old, new, 1)


def fragment(name):
    return (HERE / "overlay" / name).read_text(encoding="utf-8")


def render(inputs):
    output = dict(inputs)
    output[MOJOM] = replace_once(output[MOJOM], "interface FullscreenVideoElementHandler {",
        fragment("video_protocol.mojom.inc") + "\ninterface FullscreenVideoElementHandler {", MOJOM)
    output[MOJOM] += "\ninterface UpgridVideoController {\n" + fragment("video_methods.mojom.inc") + "};\n"
    output[HEADER] = replace_once(output[HEADER], "class Document;",
        "class Document;\nclass HTMLVideoElement;\nclass TextTrack;\nclass UpgridFullscreenGuard;", HEADER)
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
        "  static HeapVector<Member<HTMLMediaElement>> UpgridMediaCandidates(Document&);", MEDIA_H)
    output[MEDIA_H] = replace_once(output[MEDIA_H], "  std::optional<bool> user_wants_controls_visible_;",
        "  bool upgrid_controls_hidden_ = false;\n  uint64_t upgrid_source_epoch_ = 0;\n"
        "  std::optional<bool> user_wants_controls_visible_;", MEDIA_H)
    output[MEDIA_CC] = replace_once(output[MEDIA_CC], "void HTMLMediaElement::InvokeLoadAlgorithm() {",
        "void HTMLMediaElement::InvokeLoadAlgorithm() {\n  ++upgrid_source_epoch_;", MEDIA_CC)
    output[MEDIA_CC] = replace_once(output[MEDIA_CC], "bool HTMLMediaElement::ShouldShowControls() const {",
        fragment("video_registry.cc.inc") +
        "void HTMLMediaElement::SetUpgridControlsHidden(bool hidden) {\n"
        "  upgrid_controls_hidden_ = hidden;\n  UpdateControlsVisibility();\n}\n\n"
        "bool HTMLMediaElement::ShouldShowControls() const {\n"
        "  if (upgrid_controls_hidden_ && IsFullscreen())\n    return false;", MEDIA_CC)
    output[MEDIA_TEST] = replace_once(output[MEDIA_TEST], "}  // namespace blink",
        fragment("video_controls_test.cc.inc") + "\n}  // namespace blink", MEDIA_TEST)
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
    output[JAVA_SOURCES] = replace_once(output[JAVA_SOURCES], '"' + java_path + '",',
        '"' + java_path + '",\n      "java/src/org/chromium/chrome/browser/upgrid/UpgridScrubSession.java",',
        JAVA_SOURCES)
    anchor = '"android/devtools_server.cc",'
    output[NATIVE_BUILD] = replace_once(output[NATIVE_BUILD], anchor,
        anchor + '\n      "android/upgrid_player.cc",', NATIVE_BUILD)
    anchor = "        boolean currentTabIsNtp = isTabNtp(currentTab);"
    output[ACTIVITY] = replace_once(output[ACTIVITY], anchor, anchor +
        "\n        if (id == org.chromium.chrome.browser.upgrid.UpgridPlayer.MENU_ID) {\n"
        "            org.chromium.chrome.browser.upgrid.UpgridPlayer.open(this, currentTab);\n"
        "            return true;\n        }", ACTIVITY)
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
    output[LABEL] = replace_once(output[LABEL],
        '<string name="app_name" translatable="false">Chromium</string>',
        '<string name="app_name" translatable="false">Upgrid Chromium Dev</string>', LABEL)
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
    result = {name: text if name.endswith(".xml") else
              (MARKER.replace("//", "#", 1) if name.endswith((".gn", ".gni")) else MARKER) +
              "\n" + text for name, text in output.items()}
    result.update({name: fragment(source) for name, source in NEW_FILES.items()})
    return result


def sha(data):
    return hashlib.sha256(data.encode("utf-8")).hexdigest()


def validate_changes(root, originals, desired, previous_files):
    """Check the entire write set before allowing the first mutation."""
    for name, text in desired.items():
        actual = (root / name).read_text(encoding="utf-8") if (root / name).exists() else None
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
                                "previousAfter": sha((root / name).read_text(encoding="utf-8"))
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
            if target.exists() and target.read_text(encoding="utf-8") == text:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8", newline="\n")
    print(json.dumps(receipt, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
