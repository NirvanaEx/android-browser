// A fullscreen request can resolve after the Android controller is closed.
// Keep a document-scoped listener until resolution so cancellation also covers
// that race, rather than leaving a late fullscreen surface behind the browser.
class UpgridFullscreenGuard final : public NativeEventListener {
 public:
  explicit UpgridFullscreenGuard(HTMLVideoElement& video)
      : video_(&video), document_(&video.GetDocument()) {
    document_->addEventListener(event_type_names::kFullscreenchange, this,
                                true);
    document_->addEventListener(event_type_names::kFullscreenerror, this, true);
  }
  void Cancel() {
    cancelled_ = true;
    if (!video_ || Fullscreen::IsFullscreenElement(*video_))
      Detach();
  }
  void Invoke(ExecutionContext*, Event* event) override {
    if (!video_) {
      Detach();
      return;
    }
    if (event->type() == event_type_names::kFullscreenerror &&
        event->target() == video_) {
      Detach();
      return;
    }
    if (Fullscreen::IsFullscreenElement(*video_)) {
      Document* document = document_.Get();
      // A new native session may already own the same element after a fast
      // close/reopen. An earlier cancelled request must not close that session.
      const bool owned_by_new_session = video_->IsUpgridPlayerActive();
      Detach();
      if (cancelled_ && document && !owned_by_new_session)
        Fullscreen::FullyExitFullscreen(*document, true);
    }
  }
  void Trace(Visitor* visitor) const override {
    visitor->Trace(video_);
    visitor->Trace(document_);
    NativeEventListener::Trace(visitor);
  }

 private:
  void Detach() {
    if (!document_)
      return;
    document_->removeEventListener(event_type_names::kFullscreenchange, this,
                                   true);
    document_->removeEventListener(event_type_names::kFullscreenerror, this,
                                   true);
    document_ = nullptr;
    video_ = nullptr;
  }
  WeakMember<HTMLVideoElement> video_;
  Member<Document> document_;
  bool cancelled_ = false;
};
