  // Discovery is bounded and keeps URLs inside Blink. The browser compares
  // replies from eligible active frames before opening exactly one token.
  GetUpgridVideoCandidate(bool fullscreen_only) => (UpgridVideoState? state);
  // user_initiated=true must originate in an actual Android input callback.
  // Uses Chromium's normal fullscreen path without changing permissions.
  // Passive adoption never creates activation or requests fullscreen.
  OpenUpgridVideo(uint64 token, bool user_initiated) => (bool requested);
  ReadUpgridVideo(uint64 token) => (UpgridVideoState? state);
  ControlUpgridVideo(uint64 token, UpgridVideoCommand command, double value)
      => (UpgridVideoState? state);
  ReleaseUpgridVideo(uint64 token, bool pause) => (bool released);
  UpgridSiteOptions(uint64 token, string command, string option) => (string json);
