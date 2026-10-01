// Copyright 2026 Upgrid contributors. All rights reserved.
#ifndef THIRD_PARTY_BLINK_RENDERER_CORE_FRAME_UPGRID_FULLSCREEN_PRESENTATION_H_
#define THIRD_PARTY_BLINK_RENDERER_CORE_FRAME_UPGRID_FULLSCREEN_PRESENTATION_H_

#include "third_party/blink/renderer/core/fullscreen/fullscreen.h"
#include "third_party/blink/renderer/core/html/media/html_video_element.h"
#include "third_party/blink/renderer/platform/heap/collection_support/heap_vector.h"
#include "third_party/blink/renderer/platform/heap/garbage_collected.h"

namespace blink {

// Reuse Chromium's UA presentation path for a video inside an already granted
// fullscreen container. The ancestry flags affect rendering only: no DOM or
// author styles, media source, PiP state, or fullscreen permission is changed.
class UpgridFullscreenPresentation final
    : public GarbageCollected<UpgridFullscreenPresentation> {
 public:
  static UpgridFullscreenPresentation* Create(HTMLVideoElement& video,
                                              Element& root) {
    if (!Fullscreen::IsFullscreenElement(root) || video.IsPersistent())
      return nullptr;
    auto* presentation = MakeGarbageCollected<UpgridFullscreenPresentation>();
    Element* element = &video;
    for (; element && presentation->path_.size() < 64;
         element = element->ParentOrShadowHostElement()) {
      // An existing browser presentation owns these flags. Leave it intact.
      if (element->ContainsPersistentVideo())
        return nullptr;
      presentation->path_.push_back(element);
      if (element == &root)
        break;
    }
    if (element != &root)
      return nullptr;
    for (const auto& item : presentation->path_)
      item->SetContainsPersistentVideo(true);
    presentation->enabled_ = true;
    return presentation;
  }

  bool IsCurrent(HTMLVideoElement& video, Element& root) const {
    if (!enabled_ || !Fullscreen::IsFullscreenElement(root))
      return false;
    Element* element = &video;
    for (const auto& item : path_) {
      if (!element || element != item || !element->ContainsPersistentVideo())
        return false;
      if (element == &root)
        return true;
      element = element->ParentOrShadowHostElement();
    }
    return false;
  }

  void Close() {
    if (!enabled_)
      return;
    enabled_ = false;
    // Clear from video to root, including the old path after a DOM move.
    for (const auto& item : path_) {
      if (item)
        item->SetContainsPersistentVideo(false);
    }
    path_.clear();
  }

  void Trace(Visitor* visitor) const { visitor->Trace(path_); }

 private:
  // Keep the bounded old path alive until cleanup, even after a DOM move.
  HeapVector<Member<Element>> path_;
  bool enabled_ = false;
};

}  // namespace blink
#endif  // THIRD_PARTY_BLINK_RENDERER_CORE_FRAME_UPGRID_FULLSCREEN_PRESENTATION_H_
