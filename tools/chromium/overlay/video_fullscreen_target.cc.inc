// Promote a site's video container within its already-authorized request.
// No cross-document lookup, synthetic activation, CSS scaling or new decoder.
Element* HTMLMediaElement::UpgridFullscreenTarget(Element& requested) {
  if (IsA<HTMLVideoElement>(requested) || !requested.isConnected())
    return &requested;
  HTMLVideoElement* best = nullptr;
  double best_area = 0;
  for (const auto& element : UpgridMediaCandidates(requested.GetDocument())) {
    auto* video = To<HTMLVideoElement>(element.Get());
    if (!requested.ContainsIncludingHostElements(*video) || !video->HasVideo())
      continue;
    auto* rect = video->GetBoundingClientRect();
    const auto* style = video->GetComputedStyle();
    if (!style || style->Visibility() != EVisibility::kVisible)
      continue;
    const double area = rect->width() * rect->height();
    if (rect->width() > 0 && rect->height() > 0 && std::isfinite(area) &&
        area > best_area) {
      best = video;
      best_area = area;
    }
  }
  return best ? best : &requested;
}

