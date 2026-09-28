  // Discovery is bounded and keeps URLs inside Blink. The browser compares
  // replies from eligible active frames before opening exactly one token.
  GetUpgridVideoCandidate() => (UpgridVideoState? state);
  // Must originate in an actual Android input callback. Uses Chromium's normal
  // browser-initiated fullscreen path; does not change fullscreen preferences.
  OpenUpgridVideo(uint64 token) => (bool requested);
  ReadUpgridVideo(uint64 token) => (UpgridVideoState? state);
  ControlUpgridVideo(uint64 token, UpgridVideoCommand command, double value)
      => (UpgridVideoState? state);
  ReleaseUpgridVideo(uint64 token, bool pause) => (bool released);
  UpgridSiteOptions(uint64 token, string command, string option) => (string json);
