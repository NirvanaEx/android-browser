HeapVector<Member<HTMLMediaElement>> HTMLMediaElement::UpgridMediaCandidates(
    Document& document) {
  HeapVector<Member<HTMLMediaElement>> result;
  auto it = DocumentToElementSetMap().find(&document);
  if (it == DocumentToElementSetMap().end() || !it->value)
    return result;
  // Use the engine's media registry, including closed shadow roots. Snapshot
  // strong references before any later layout work. Bound pathological pages.
  size_t visited = 0;
  for (const auto& element : *it->value) {
    if (++visited > 512)
      break;
    if (element && element->IsHTMLVideoElement() && element->isConnected() &&
        &element->GetDocument() == &document && element->GetWebMediaPlayer() &&
        !element->error())
      result.push_back(element.Get());
  }
  return result;
}
