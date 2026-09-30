// Copyright 2026 Upgrid contributors. All rights reserved.
// Upgrid Chromium development overlay.

#include <cmath>
#include <memory>
#include <tuple>
#include <vector>

#include "base/android/callback_android.h"
#include "base/android/jni_android.h"
#include "base/android/jni_string.h"
#include "base/android/scoped_java_ref.h"
#include "base/functional/bind.h"
#include "base/json/json_writer.h"
#include "base/memory/weak_ptr.h"
#include "base/task/single_thread_task_runner.h"
#include "base/time/time.h"
#include "base/timer/timer.h"
#include "base/values.h"
#include "content/public/browser/render_frame_host.h"
#include "content/public/browser/web_contents.h"
#include "content/public/browser/web_contents_observer.h"
#include "content/public/browser/web_contents_user_data.h"
#include "mojo/public/cpp/bindings/associated_remote.h"
#include "third_party/blink/public/common/associated_interfaces/associated_interface_provider.h"
#include "third_party/blink/public/mojom/media/fullscreen_video_element.mojom.h"

// JNI conversions must be visible before the generated header.
#include "chrome/android/chrome_jni_headers/UpgridPlayer_jni.h"

namespace upgrid {
namespace {
using State = blink::mojom::UpgridVideoStatePtr;
using Command = blink::mojom::UpgridVideoCommand;
using JavaCallback = base::android::ScopedJavaGlobalRef<jobject>;

void Reply(JavaCallback callback, State state, const char* error = "") {
  base::DictValue result;
  result.Set("ok", !!state);
  result.Set("error", error);
  if (state) {
    result.Set("paused", state->paused);
    result.Set("ended", state->ended);
    result.Set("buffering", state->buffering);
    result.Set("muted", state->muted);
    result.Set("loop", state->loop);
    result.Set("fullscreen", state->fullscreen);
    result.Set("position", state->position);
    result.Set("duration", state->duration);
    result.Set("rate", state->rate);
    result.Set("videoWidth", static_cast<double>(state->width));
    result.Set("videoHeight", static_cast<double>(state->height));
    result.Set("canDownload", state->can_download);
    result.Set("canCast", state->can_cast);
    result.Set("canPip", state->can_pip);
    result.Set("pipRequested", state->pip_requested);
    base::ListValue tracks;
    for (const auto& track : state->tracks) {
      base::DictValue item;
      item.Set("id", static_cast<int>(track->id));
      item.Set("label", track->label);
      item.Set("selected", track->selected);
      tracks.Append(std::move(item));
    }
    result.Set("tracks", std::move(tracks));
  }
  // Element tokens and source URLs never cross into Java or diagnostics.
  base::android::RunStringCallbackAndroid(
      callback, base::WriteJson(result).value_or("{\"ok\":false}"));
}

struct Target {
  explicit Target(content::RenderFrameHost& frame)
      : frame_id(frame.GetGlobalId()) {
    frame.GetRemoteAssociatedInterfaces()->GetInterface(&remote);
  }
  content::GlobalRenderFrameHostId frame_id;
  mojo::AssociatedRemote<blink::mojom::UpgridVideoController> remote;
  State state;
};

class PlayerHost : public content::WebContentsObserver,
                   public content::WebContentsUserData<PlayerHost> {
 public:
  ~PlayerHost() override { Close(true); }

  void Open(JavaCallback callback, bool fullscreen_only) {
    Close(false);
    fullscreen_only_ = fullscreen_only;
    if (!web_contents() ||
        web_contents()->GetVisibility() != content::Visibility::VISIBLE) {
      Reply(std::move(callback), nullptr, "hidden");
      return;
    }
    open_callback_ = std::move(callback);
    discovering_ = true;
    const uint64_t generation = generation_;
    auto* main = web_contents()->GetPrimaryMainFrame();
    // Include cross-origin out-of-process frames, but never prerendered pages.
    main->ForEachRenderFrameHost([&](content::RenderFrameHost* frame) {
      if (candidates_.size() < 64 && frame->IsActive() &&
          frame->GetOutermostMainFrame() == main) {
        candidates_.push_back(std::make_shared<Target>(*frame));
      }
    });
    pending_ = candidates_.size();
    if (!pending_) {
      FinishDiscovery(generation);
      return;
    }
    deadline_.Start(FROM_HERE, base::Milliseconds(500),
                    base::BindOnce(&PlayerHost::FinishDiscovery,
                                   weak_.GetWeakPtr(), generation));
    for (auto& target : candidates_) {
      target->remote->GetUpgridVideoCandidate(
          fullscreen_only_,
          base::BindOnce(&PlayerHost::OnCandidate, weak_.GetWeakPtr(),
                         generation, std::weak_ptr<Target>(target)));
    }
  }

  void Read(JavaCallback callback) {
    if (!Current()) {
      Close(true);
      Reply(std::move(callback), nullptr, "source_changed");
      return;
    }
    selected_->remote->ReadUpgridVideo(
        selected_->state->token,
        base::BindOnce(&PlayerHost::OnState, weak_.GetWeakPtr(), generation_,
                       std::move(callback)));
  }

  void Control(int command, double value, JavaCallback callback) {
    if (!Current() || !std::isfinite(value) || command < 0 ||
        command > static_cast<int>(Command::kPictureInPicture)) {
      Reply(std::move(callback), nullptr, "invalid_command");
      return;
    }
    selected_->remote->ControlUpgridVideo(
        selected_->state->token, static_cast<Command>(command), value,
        base::BindOnce(&PlayerHost::OnState, weak_.GetWeakPtr(), generation_,
                       std::move(callback)));
  }

  void Close(bool pause) {
    ++generation_;
    deadline_.Stop();
    discovering_ = false;
    if (selected_ && selected_->state && selected_->remote.is_connected()) {
      // Keep the pipe alive through the renderer acknowledgement. Otherwise its
      // disconnect handler would turn a normal return into a background pause.
      auto target = std::move(selected_);
      const uint64_t token = target->state->token;
      target->remote->ReleaseUpgridVideo(
          token, pause,
          base::BindOnce(
              [](std::weak_ptr<Target> weak_target, bool released) {
                if (auto target = weak_target.lock())
                  target->remote.reset();
              },
              std::weak_ptr<Target>(target)));
      // Bound the lifetime even if a renderer never acknowledges the release.
      base::SingleThreadTaskRunner::GetCurrentDefault()->PostDelayedTask(
          FROM_HERE,
          base::BindOnce(
              [](std::shared_ptr<Target> target) { target->remote.reset(); },
              target),
          base::Seconds(2));
    }
    selected_.reset();
    candidates_.clear();
    if (open_callback_) {
      Reply(std::move(open_callback_), nullptr, "cancelled");
      open_callback_.Reset();
    }
  }

  void SiteOptions(const std::string& command,
                   const std::string& option,
                   JavaCallback callback) {
    if (!Current() || command.size() > 16 || option.size() > 256) {
      Reply(std::move(callback), nullptr, "source_changed");
      return;
    }
    selected_->remote->UpgridSiteOptions(
        selected_->state->token, command, option,
        base::BindOnce(&PlayerHost::OnSiteOptions, weak_.GetWeakPtr(),
                       generation_, std::move(callback)));
  }

  void PrimaryPageChanged(content::Page&) override { Close(true); }
  void OnVisibilityChanged(content::Visibility visibility) override {
    if (visibility != content::Visibility::VISIBLE)
      Close(true);
  }
  void RenderFrameDeleted(content::RenderFrameHost* frame) override {
    if (selected_ && selected_->frame_id == frame->GetGlobalId())
      Close(true);
  }
  void WebContentsDestroyed() override { Close(true); }

 private:
  friend class content::WebContentsUserData<PlayerHost>;
  explicit PlayerHost(content::WebContents* contents)
      : content::WebContentsObserver(contents),
        content::WebContentsUserData<PlayerHost>(*contents) {}

  bool Current() const {
    if (!web_contents() || !selected_ || !selected_->state ||
        !selected_->remote.is_connected() ||
        web_contents()->GetVisibility() != content::Visibility::VISIBLE)
      return false;
    auto* frame = content::RenderFrameHost::FromID(selected_->frame_id);
    return frame && frame->IsActive() &&
           frame->GetOutermostMainFrame() ==
               web_contents()->GetPrimaryMainFrame();
  }

  void OnDisconnected(uint64_t generation) {
    if (generation == generation_)
      Close(true);
  }

  void OnCandidate(uint64_t generation,
                   std::weak_ptr<Target> weak_target,
                   State state) {
    if (generation != generation_ || !open_callback_ || !discovering_)
      return;
    auto target = weak_target.lock();
    if (!target)
      return;
    if (state && state->token && std::isfinite(state->area) && state->area > 0 &&
        (!fullscreen_only_ || state->fullscreen))
      target->state = std::move(state);
    if (--pending_ == 0)
      FinishDiscovery(generation);
  }

  void FinishDiscovery(uint64_t generation) {
    if (generation != generation_ || !open_callback_ || !discovering_)
      return;
    discovering_ = false;
    deadline_.Stop();
    for (auto& target : candidates_) {
      if (!target->state)
        continue;
      auto better = [this](const State& lhs, const State& rhs) {
        if (fullscreen_only_)
          return std::make_tuple(lhs->area, !lhs->paused && !lhs->ended,
                                 lhs->audible) >
                 std::make_tuple(rhs->area, !rhs->paused && !rhs->ended,
                                 rhs->audible);
        return std::make_tuple(lhs->fullscreen, !lhs->paused && !lhs->ended,
                               lhs->audible, lhs->area) >
               std::make_tuple(rhs->fullscreen, !rhs->paused && !rhs->ended,
                               rhs->audible, rhs->area);
      };
      if (!selected_ || better(target->state, selected_->state))
        selected_ = target;
    }
    candidates_.clear();
    if (!Current()) {
      Reply(std::move(open_callback_), nullptr, "no_video");
      open_callback_.Reset();
      return;
    }
    selected_->remote.set_disconnect_handler(base::BindOnce(
        &PlayerHost::OnDisconnected, weak_.GetWeakPtr(), generation));
    selected_->remote->OpenUpgridVideo(
        selected_->state->token, !fullscreen_only_,
        base::BindOnce(&PlayerHost::OnOpened, weak_.GetWeakPtr(), generation));
  }

  void OnOpened(uint64_t generation, bool requested) {
    if (generation != generation_ || !open_callback_)
      return;
    JavaCallback callback = std::move(open_callback_);
    open_callback_.Reset();
    if (!requested || !Current()) {
      Close(false);
      Reply(std::move(callback), nullptr, "fullscreen_failed");
      return;
    }
    // Java waits for a fullscreen acknowledgement; acceptance is insufficient.
    Read(std::move(callback));
  }

  void OnState(uint64_t generation, JavaCallback callback, State state) {
    if (generation != generation_) {
      Reply(std::move(callback), nullptr, "cancelled");
      return;
    }
    if (!state) {
      Close(false);
      Reply(std::move(callback), nullptr, "source_changed");
      return;
    }
    Reply(std::move(callback), std::move(state));
  }

  void OnSiteOptions(uint64_t generation,
                     JavaCallback callback,
                     const std::string& json) {
    if (generation != generation_ || !Current() || json.size() > 32768) {
      Reply(std::move(callback), nullptr, "source_changed");
      return;
    }
    base::android::RunStringCallbackAndroid(callback, json);
  }

  uint64_t generation_ = 0;
  size_t pending_ = 0;
  bool discovering_ = false;
  bool fullscreen_only_ = false;
  JavaCallback open_callback_;
  std::vector<std::shared_ptr<Target>> candidates_;
  std::shared_ptr<Target> selected_;
  base::OneShotTimer deadline_;
  base::WeakPtrFactory<PlayerHost> weak_{this};
  WEB_CONTENTS_USER_DATA_KEY_DECL();
};
WEB_CONTENTS_USER_DATA_KEY_IMPL(PlayerHost);
}  // namespace
}  // namespace upgrid

static void JNI_UpgridPlayer_Open(
    JNIEnv* env,
    content::WebContents* contents,
    bool fullscreen_only,
    const base::android::JavaRef<jobject>& callback) {
  if (!contents) {
    upgrid::Reply(upgrid::JavaCallback(callback), nullptr, "closed");
    return;
  }
  upgrid::PlayerHost::CreateForWebContents(contents);
  upgrid::PlayerHost::FromWebContents(contents)->Open(
      upgrid::JavaCallback(callback), fullscreen_only);
}

static void JNI_UpgridPlayer_Read(
    JNIEnv* env,
    content::WebContents* contents,
    const base::android::JavaRef<jobject>& callback) {
  auto* host =
      contents ? upgrid::PlayerHost::FromWebContents(contents) : nullptr;
  if (host)
    host->Read(upgrid::JavaCallback(callback));
  else
    upgrid::Reply(upgrid::JavaCallback(callback), nullptr, "closed");
}

static void JNI_UpgridPlayer_Control(
    JNIEnv* env,
    content::WebContents* contents,
    int32_t command,
    double value,
    const base::android::JavaRef<jobject>& callback) {
  auto* host =
      contents ? upgrid::PlayerHost::FromWebContents(contents) : nullptr;
  if (host)
    host->Control(command, value, upgrid::JavaCallback(callback));
  else
    upgrid::Reply(upgrid::JavaCallback(callback), nullptr, "closed");
}

static void JNI_UpgridPlayer_Close(JNIEnv* env,
                                   content::WebContents* contents,
                                   bool pause) {
  if (contents) {
    if (auto* host = upgrid::PlayerHost::FromWebContents(contents))
      host->Close(pause);
  }
}

static void JNI_UpgridPlayer_SiteOptions(
    JNIEnv* env,
    content::WebContents* contents,
    const std::string& command,
    const std::string& option,
    const base::android::JavaRef<jobject>& callback) {
  auto* host =
      contents ? upgrid::PlayerHost::FromWebContents(contents) : nullptr;
  if (host)
    host->SiteOptions(command, option, upgrid::JavaCallback(callback));
  else
    upgrid::Reply(upgrid::JavaCallback(callback), nullptr, "closed");
}

DEFINE_JNI(UpgridPlayer)
