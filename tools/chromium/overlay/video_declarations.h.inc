void GetUpgridVideoCandidate(bool fullscreen_only,
                            GetUpgridVideoCandidateCallback callback) final;
void OpenUpgridVideo(uint64_t token, bool user_initiated,
                     OpenUpgridVideoCallback callback) final;
void ReadUpgridVideo(uint64_t token, ReadUpgridVideoCallback callback) final;
void ControlUpgridVideo(uint64_t token,
                        mojom::blink::UpgridVideoCommand command,
                        double value,
                        ControlUpgridVideoCallback callback) final;
void ReleaseUpgridVideo(uint64_t token,
                        bool pause,
                        ReleaseUpgridVideoCallback callback) final;
bool IsUpgridVideoCurrent(uint64_t token) const;
bool IsUpgridVideoFullscreen() const;
  void UpgridSiteOptions(uint64_t token, const String& command, const String& option,
                        UpgridSiteOptionsCallback callback) final;
mojom::blink::UpgridVideoStatePtr UpgridVideoSnapshot();
void ClearUpgridVideo(bool pause, bool exit_fullscreen);
void SetUpgridVideoFit(const String& fit);

WeakMember<HTMLVideoElement> upgrid_video_;
WeakMember<Element> upgrid_fullscreen_root_;
Member<UpgridFullscreenGuard> upgrid_fullscreen_guard_;
Member<UpgridFullscreenPresentation> upgrid_presentation_;
// Keep at most 32 stable track identities until this player session closes.
// Commands still validate membership in the selected video's current list.
HeapVector<Member<TextTrack>> upgrid_tracks_;
KURL upgrid_source_;
uintptr_t upgrid_media_player_ = 0;
uint64_t upgrid_video_serial_ = 0;
uint64_t upgrid_source_epoch_ = 0;
double upgrid_candidate_area_ = 0;
bool upgrid_open_requested_ = false;
bool upgrid_controls_hidden_ = false;
String upgrid_original_fit_;
String upgrid_applied_fit_;
bool upgrid_original_fit_important_ = false;
String upgrid_original_mirror_scale_;
String upgrid_applied_mirror_scale_;
bool upgrid_original_mirror_scale_important_ = false;
bool upgrid_mirror_changed_ = false;
void BindUpgridVideoReceiver(
    mojo::PendingAssociatedReceiver<mojom::blink::UpgridVideoController>
        receiver);
HeapMojoAssociatedReceiver<mojom::blink::UpgridVideoController,
                           LocalFrameMojoHandler>
    upgrid_video_receiver_{this, nullptr};
