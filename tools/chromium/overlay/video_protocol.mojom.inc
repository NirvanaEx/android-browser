// Upgrid development interface. Browser-process callers only. A token identifies
// one element + source + WebMediaPlayer in this frame, never a network request.
enum UpgridVideoCommand {
  kPlay,
  kPause,
  kSeekTo,
  kSetRate,
  kSetMuted,
  kSetLoop,
  kFitContain,
  kFitCover,
  kFitFill,
  kSetCaptions,
  kDownload,
  kCast,
  kPictureInPicture,
};

struct UpgridCaptionTrack {
  uint32 id;
  string label;
  bool selected;
};

struct UpgridVideoState {
  uint64 token;
  bool paused;
  bool ended;
  bool buffering;
  bool muted;
  bool loop;
  bool fullscreen;
  bool audible;
  bool can_download;
  bool can_cast;
  bool can_pip;
  bool pip_requested;
  double position;
  double duration;
  double rate;
  double area;
  uint32 width;
  uint32 height;
  array<UpgridCaptionTrack> tracks;
};
