// Upgrid's source implementation of an element-bound renderer control channel.
// This is not the recovered native implementation of Banana Browser.

void LocalFrameMojoHandler::BindUpgridVideoReceiver(
    mojo::PendingAssociatedReceiver<mojom::blink::UpgridVideoController>
        receiver) {
  if (frame_->IsDetached())
    return;
  ClearUpgridVideo(false, false);
  upgrid_video_receiver_.reset();
  upgrid_video_receiver_.Bind(
      std::move(receiver), frame_->GetTaskRunner(TaskType::kInternalDefault));
  upgrid_video_receiver_.SetFilter(
      std::make_unique<ActiveURLMessageFilter>(frame_));
  upgrid_video_receiver_.set_disconnect_handler(
      BindOnce(&LocalFrameMojoHandler::ClearUpgridVideo,
               WrapWeakPersistent(this), true, true));
}

bool LocalFrameMojoHandler::IsUpgridVideoCurrent(uint64_t token) const {
  return token != 0 && token == upgrid_video_serial_ && upgrid_video_ &&
         !frame_->IsDetached() && upgrid_video_->isConnected() &&
         &upgrid_video_->GetDocument() == frame_->GetDocument() &&
         upgrid_video_->currentSrc() == upgrid_source_ &&
         upgrid_video_->UpgridSourceEpoch() == upgrid_source_epoch_ &&
         reinterpret_cast<uintptr_t>(upgrid_video_->GetWebMediaPlayer()) ==
             upgrid_media_player_;
}

mojom::blink::UpgridVideoStatePtr LocalFrameMojoHandler::UpgridVideoSnapshot() {
  if (!IsUpgridVideoCurrent(upgrid_video_serial_))
    return nullptr;
  auto& video = *upgrid_video_;
  const bool fullscreen = Fullscreen::IsFullscreenElement(video);
  // Only change controls and scaling once real engine fullscreen is confirmed.
  if (fullscreen && upgrid_open_requested_ && !upgrid_controls_hidden_) {
    const auto* style = video.InlineStyle();
    upgrid_original_fit_ =
        style ? style->GetPropertyValue(CSSPropertyID::kObjectFit) : String();
    upgrid_original_fit_important_ =
        style && style->PropertyIsImportant(CSSPropertyID::kObjectFit);
    video.SetUpgridControlsHidden(true);
    upgrid_controls_hidden_ = true;
    SetUpgridVideoFit("contain");
  }
  if (upgrid_controls_hidden_ && !fullscreen) {
    ClearUpgridVideo(false, false);
    return nullptr;
  }
  auto result = mojom::blink::UpgridVideoState::New();
  result->token = upgrid_video_serial_;
  result->paused = video.paused();
  result->ended = video.ended();
  result->buffering =
      !video.paused() && !video.ended() &&
      (video.seeking() ||
       video.getReadyState() < HTMLMediaElement::kHaveFutureData);
  result->muted = video.muted();
  result->loop = video.Loop();
  result->fullscreen = fullscreen;
  result->audible =
      !video.paused() && !video.ended() && !video.muted() && video.volume() > 0;
  result->can_download =
      video.SupportsSave() && video.downloadURL().ProtocolIsInHTTPFamily();
  auto* remote = RemotePlaybackController::From(video);
  result->can_cast =
      remote && remote->CanPromptForUpgrid() &&
      !video.FastHasAttribute(html_names::kDisableremoteplaybackAttr);
  result->can_pip = PictureInPictureController::From(video.GetDocument())
                        .IsElementAllowed(video) ==
                    PictureInPictureController::Status::kEnabled;
  result->position =
      std::isfinite(video.currentTime()) ? video.currentTime() : 0;
  result->duration = std::isfinite(video.duration()) ? video.duration() : 0;
  result->rate = video.playbackRate();
  // Area ranks discovery candidates only; polling controls must not force
  // layout on every update of the native seek bar.
  result->area = upgrid_candidate_area_;
  result->width = video.videoWidth();
  result->height = video.videoHeight();
  auto* tracks = video.textTracks();
  for (unsigned i = 0; i < std::min(tracks->length(), 32u); ++i) {
    TextTrack* track = tracks->AnonymousIndexedGetter(i);
    if (!track ||
        (track->kind().AsEnum() != V8TextTrackKind::Enum::kSubtitles &&
         track->kind().AsEnum() != V8TextTrackKind::Enum::kCaptions))
      continue;
    wtf_size_t slot = 0;
    while (slot < upgrid_tracks_.size() && upgrid_tracks_[slot] != track)
      ++slot;
    if (slot == upgrid_tracks_.size()) {
      if (slot >= 32)
        continue;
      upgrid_tracks_.push_back(track);
    }
    auto item = mojom::blink::UpgridCaptionTrack::New();
    item->id = slot + 1;
    item->label =
        (track->label().empty() ? track->language() : track->label());
    if (item->label.empty())
      item->label = String::Number(slot + 1);
    item->label = item->label.Substring(0, 128);
    item->selected = track->mode().AsEnum() == TextTrackMode::kShowing;
    result->tracks.push_back(std::move(item));
  }
  return result;
}

void LocalFrameMojoHandler::GetUpgridVideoCandidate(
    GetUpgridVideoCandidateCallback callback) {
  if (upgrid_open_requested_) {
    std::move(callback).Run(UpgridVideoSnapshot());
    return;
  }
  ClearUpgridVideo(false, false);
  HTMLVideoElement* best = nullptr;
  bool best_playing = false;
  bool best_audible = false;
  double best_area = 0;
  if (!frame_->IsDetached() && frame_->GetDocument()) {
    for (const auto& element :
         HTMLMediaElement::UpgridMediaCandidates(*frame_->GetDocument())) {
      auto* video = To<HTMLVideoElement>(element.Get());
      if (!video || !video->isConnected() || video->error() ||
          !video->GetWebMediaPlayer() || !video->HasVideo())
        continue;
      DOMRect* rect = video->GetBoundingClientRect();
      const auto* style = video->GetComputedStyle();
      if (!style || style->Visibility() != EVisibility::kVisible)
        continue;
      const double area = rect->width() * rect->height();
      if (rect->width() <= 0 || rect->height() <= 0 || !std::isfinite(area))
        continue;
      const bool playing = !video->paused() && !video->ended();
      const bool audible = playing && !video->muted() && video->volume() > 0;
      if (!best || std::make_tuple(playing, audible, area) >
                       std::make_tuple(best_playing, best_audible, best_area)) {
        best = video;
        best_playing = playing;
        best_audible = audible;
        best_area = area;
      }
    }
  }
  if (best) {
    upgrid_video_ = best;
    upgrid_candidate_area_ = best_area;
    upgrid_source_ = best->currentSrc();
    upgrid_source_epoch_ = best->UpgridSourceEpoch();
    upgrid_media_player_ =
        reinterpret_cast<uintptr_t>(best->GetWebMediaPlayer());
    if (++upgrid_video_serial_ == 0)
      ++upgrid_video_serial_;
  }
  std::move(callback).Run(UpgridVideoSnapshot());
}

void LocalFrameMojoHandler::OpenUpgridVideo(uint64_t token,
                                            OpenUpgridVideoCallback callback) {
  if (!IsUpgridVideoCurrent(token) || upgrid_video_->error()) {
    std::move(callback).Run(false);
    return;
  }
  upgrid_open_requested_ = true;
  // Suppress Chromium's automatic video rotation before its fullscreen event.
  // ShouldShowControls still leaves normal page controls alone until
  // fullscreen.
  upgrid_video_->SetUpgridControlsHidden(true);
  if (!Fullscreen::IsFullscreenElement(*upgrid_video_)) {
    upgrid_fullscreen_guard_ =
        MakeGarbageCollected<UpgridFullscreenGuard>(*upgrid_video_);
    // Same browser-UI activation path as RequestFullscreenVideoElement above.
    // Only the future native Android click handler may invoke this operation.
    // Fullscreen::RequestFullscreen still enforces the frame's permissions.
    frame_->NotifyUserActivation(
        mojom::blink::UserActivationNotificationType::kInteraction);
    Fullscreen::RequestFullscreen(*upgrid_video_);
  }
  // Accepted request is not a fullscreen acknowledgement. Android must wait
  // for a ReadUpgridVideo snapshot with fullscreen=true before showing
  // controls.
  std::move(callback).Run(true);
}

void LocalFrameMojoHandler::ReadUpgridVideo(uint64_t token,
                                            ReadUpgridVideoCallback callback) {
  if (!IsUpgridVideoCurrent(token)) {
    if (token == upgrid_video_serial_)
      ClearUpgridVideo(false, false);
    std::move(callback).Run(nullptr);
    return;
  }
  std::move(callback).Run(UpgridVideoSnapshot());
}

void LocalFrameMojoHandler::SetUpgridVideoFit(const String& fit) {
  if (!upgrid_controls_hidden_ || !upgrid_video_ ||
      !Fullscreen::IsFullscreenElement(*upgrid_video_))
    return;
  upgrid_video_->SetInlineStyleProperty(CSSPropertyID::kObjectFit, fit, true);
  upgrid_applied_fit_ = fit;
}

void LocalFrameMojoHandler::ControlUpgridVideo(
    uint64_t token,
    mojom::blink::UpgridVideoCommand command,
    double value,
    ControlUpgridVideoCallback callback) {
  if (!IsUpgridVideoCurrent(token) || !upgrid_open_requested_ ||
      !std::isfinite(value)) {
    std::move(callback).Run(nullptr);
    return;
  }
  auto& video = *upgrid_video_;
  using Command = mojom::blink::UpgridVideoCommand;
  // Background cancellation can pause without fullscreen. Other commands may
  // only target a video whose fullscreen has actually been granted.
  if (!Fullscreen::IsFullscreenElement(video) && command != Command::kPause) {
    std::move(callback).Run(nullptr);
    return;
  }
  switch (command) {
    case Command::kPlay:
      frame_->NotifyUserActivation(
          mojom::blink::UserActivationNotificationType::kInteraction);
      video.Play();
      break;
    case Command::kPause:
      video.pause();
      break;
    case Command::kSeekTo:
      if (std::isfinite(video.duration()) && video.duration() > 0)
        video.setCurrentTime(std::clamp(value, 0.0, video.duration()));
      break;
    case Command::kSetRate:
      if (value >= 0.25 && value <= 2.0)
        video.setPlaybackRate(value);
      break;
    case Command::kSetMuted:
      video.setMuted(value != 0);
      break;
    case Command::kSetLoop:
      video.SetLoop(value != 0);
      break;
    case Command::kFitContain:
      SetUpgridVideoFit("contain");
      break;
    case Command::kFitCover:
      SetUpgridVideoFit("cover");
      break;
    case Command::kFitFill:
      SetUpgridVideoFit("fill");
      break;
    case Command::kSetCaptions: {
      // IDs are stable references, not mutable positions in the site's list.
      if (value < 0 || value > upgrid_tracks_.size() ||
          std::floor(value) != value)
        break;
      TextTrack* selected =
          value == 0 ? nullptr
                     : upgrid_tracks_[static_cast<unsigned>(value) - 1].Get();
      auto* tracks = video.textTracks();
      if (value != 0 && (!selected || !tracks->Contains(selected)))
        break;
      for (const auto& track : upgrid_tracks_) {
        if (track && tracks->Contains(track))
          track->SetModeEnum(track == selected ? TextTrackMode::kShowing
                                               : TextTrackMode::kDisabled);
      }
      break;
    }
    case Command::kDownload: {
      // Use Chromium's own download path for this selected video.
      // Authentication and redirect policy remain inside the browser; Java
      // receives no URL.
      if (!video.SupportsSave() ||
          !video.downloadURL().ProtocolIsInHTTPFamily())
        break;
      frame_->NotifyUserActivation(
          mojom::blink::UserActivationNotificationType::kInteraction);
      ResourceRequest request(video.downloadURL());
      request.SetSuggestedFilename(video.title());
      request.SetRequestContext(mojom::blink::RequestContextType::DOWNLOAD);
      request.SetRequestorOrigin(
          video.GetExecutionContext()->GetSecurityOrigin());
      frame_->DownloadURL(request, network::mojom::blink::RedirectMode::kError);
      break;
    }
    case Command::kCast: {
      auto* remote = RemotePlaybackController::From(video);
      if (remote && remote->CanPromptForUpgrid() &&
          !video.FastHasAttribute(html_names::kDisableremoteplaybackAttr)) {
        frame_->NotifyUserActivation(
            mojom::blink::UserActivationNotificationType::kInteraction);
        remote->PromptForUpgrid();
      }
      break;
    }
    case Command::kPictureInPicture: {
      auto& controller = PictureInPictureController::From(video.GetDocument());
      if (controller.IsElementAllowed(video) !=
          PictureInPictureController::Status::kEnabled)
        break;
      auto state = UpgridVideoSnapshot();
      if (!state)
        break;
      // Hand playback to Chromium PiP before Android's pause/hidden callbacks.
      // No longer owning the element prevents those callbacks pausing PiP.
      ClearUpgridVideo(false, false);
      frame_->NotifyUserActivation(
          mojom::blink::UserActivationNotificationType::kInteraction);
      controller.EnterPictureInPicture(&video, nullptr);
      state->pip_requested = true;
      std::move(callback).Run(std::move(state));
      return;
    }
  }
  std::move(callback).Run(UpgridVideoSnapshot());
}

void LocalFrameMojoHandler::ClearUpgridVideo(bool pause, bool exit_fullscreen) {
  if (upgrid_fullscreen_guard_) {
    upgrid_fullscreen_guard_->Cancel();
    upgrid_fullscreen_guard_ = nullptr;
  }
  if (upgrid_video_) {
    auto& video = *upgrid_video_;
    const bool same_source = IsUpgridVideoCurrent(upgrid_video_serial_);
    if (pause && upgrid_open_requested_ && same_source)
      video.pause();
    video.SetUpgridControlsHidden(false);
    if (upgrid_controls_hidden_) {
      // Restore only values still owned by our controller. Subsequent website
      // mutations are not overwritten on return.
      const auto* style = video.InlineStyle();
      if (style &&
          style->GetPropertyValue(CSSPropertyID::kObjectFit) ==
              upgrid_applied_fit_ &&
          style->PropertyIsImportant(CSSPropertyID::kObjectFit)) {
        if (upgrid_original_fit_.empty())
          video.RemoveInlineStyleProperty(CSSPropertyID::kObjectFit);
        else
          video.SetInlineStyleProperty(CSSPropertyID::kObjectFit,
                                       upgrid_original_fit_,
                                       upgrid_original_fit_important_);
      }
    }
    if (exit_fullscreen && upgrid_open_requested_ && same_source &&
        Fullscreen::IsFullscreenElement(video))
      Fullscreen::FullyExitFullscreen(video.GetDocument(), true);
  }
  upgrid_video_ = nullptr;
  upgrid_tracks_.clear();
  upgrid_source_ = KURL();
  upgrid_media_player_ = 0;
  upgrid_candidate_area_ = 0;
  upgrid_open_requested_ = false;
  upgrid_controls_hidden_ = false;
  upgrid_original_fit_ = String();
  upgrid_applied_fit_ = String();
}

void LocalFrameMojoHandler::ReleaseUpgridVideo(
    uint64_t token,
    bool pause,
    ReleaseUpgridVideoCallback callback) {
  if (token == 0 || token != upgrid_video_serial_) {
    std::move(callback).Run(false);
    return;
  }
  ClearUpgridVideo(pause, true);
  std::move(callback).Run(true);
}
