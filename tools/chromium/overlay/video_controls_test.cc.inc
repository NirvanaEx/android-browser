TEST_P(HTMLMediaElementTest, UpgridControlsOnlyOverrideActualVideoFullscreen) {
  if (GetParam() != MediaTestParam::kVideo)
    return;
  auto* video = To<HTMLVideoElement>(Media());
  video->GetDocument().GetSettings()->SetScriptEnabled(true);
  video->GetDocument().body()->AppendChild(video);
  video->SetBooleanAttribute(html_names::kControlsAttr, true);
  video->SetUpgridControlsHidden(true);
  EXPECT_TRUE(ControlsVisible());
  SimulateEnterFullscreen(video);
  EXPECT_FALSE(ControlsVisible());
  EXPECT_TRUE(video->FastHasAttribute(html_names::kControlsAttr));
  video->SetUpgridControlsHidden(false);
  EXPECT_TRUE(ControlsVisible());
  SimulateExitFullscreen(video);
  EXPECT_TRUE(ControlsVisible());
}

TEST_P(HTMLMediaElementTest, UpgridReturnPreservesUserControlsPreference) {
  Media()->GetDocument().GetSettings()->SetScriptEnabled(true);
  Media()->SetBooleanAttribute(html_names::kControlsAttr, true);
  Media()->SetUserWantsControlsVisible(false);
  Media()->SetUpgridControlsHidden(true);
  Media()->SetUpgridControlsHidden(false);
  EXPECT_FALSE(ControlsVisible());
}

TEST_P(HTMLMediaElementTest, UpgridControlsAreScopedToTheFullscreenContainer) {
  if (GetParam() != MediaTestParam::kVideo)
    return;
  Document& document = Media()->GetDocument();
  document.GetSettings()->SetScriptEnabled(true);
  auto* container = document.CreateRawElement(html_names::kDivTag);
  document.body()->AppendChild(container);
  container->AppendChild(Media());
  Media()->SetBooleanAttribute(html_names::kControlsAttr, true);
  Media()->SetUpgridControlsHidden(true);
  EXPECT_TRUE(ControlsVisible());
  SimulateEnterFullscreen(container);
  EXPECT_FALSE(ControlsVisible());
  EXPECT_TRUE(Media()->FastHasAttribute(html_names::kControlsAttr));
  document.body()->AppendChild(Media());
  EXPECT_TRUE(ControlsVisible());
  SimulateExitFullscreen(container);
  EXPECT_TRUE(ControlsVisible());
}

TEST_P(HTMLMediaElementTest,
       UpgridIdentityExpiresEvenWhenReloadKeepsTheSameUrl) {
  Media()->SetSrc(SrcSchemeToURL(TestURLScheme::kHttp));
  auto first_epoch = Media()->UpgridSourceEpoch();
  Media()->load();
  EXPECT_NE(first_epoch, Media()->UpgridSourceEpoch());
}

TEST_P(HTMLMediaElementTest,
       UpgridRegistryFindsShadowVideoButExcludesAudioAndDetachedMedia) {
  Document& document = Media()->GetDocument();
  auto* host = document.CreateRawElement(html_names::kDivTag);
  document.body()->AppendChild(host);
  auto& shadow = host->AttachShadowRootForTesting(ShadowRootMode::kClosed);
  shadow.AppendChild(Media());
  Media()->SetSrc(SrcSchemeToURL(TestURLScheme::kHttp));
  Media()->Play();
  test::RunPendingTasks();
  ASSERT_NE(Media()->GetWebMediaPlayer(), nullptr);
  EXPECT_EQ(
      GetParam() == MediaTestParam::kVideo,
      HTMLMediaElement::UpgridMediaCandidates(document).Contains(Media()));
  host->remove();
  EXPECT_TRUE(HTMLMediaElement::UpgridMediaCandidates(document).empty());
}

TEST_P(HTMLMediaElementTest, UpgridPresentationHidesSiteUiAndRestoresIt) {
  if (GetParam() != MediaTestParam::kVideo)
    return;
  Document& document = Media()->GetDocument();
  document.GetSettings()->SetScriptEnabled(true);
  auto* root = document.CreateRawElement(html_names::kDivTag);
  auto* wrapper = document.CreateRawElement(html_names::kDivTag);
  auto* controls = document.CreateRawElement(html_names::kDivTag);
  document.body()->AppendChild(root);
  root->AppendChild(wrapper);
  wrapper->AppendChild(Media());
  root->AppendChild(controls);
  controls->SetInlineStyleProperty(CSSPropertyID::kDisplay, "block", true);
  SimulateEnterFullscreen(root);
  Persistent<UpgridFullscreenPresentation> presentation =
      UpgridFullscreenPresentation::Create(*To<HTMLVideoElement>(Media()), *root);
  ASSERT_NE(presentation, nullptr);
  EXPECT_TRUE(presentation->IsCurrent(*To<HTMLVideoElement>(Media()), *root));
  EXPECT_FALSE(To<HTMLVideoElement>(Media())->IsPersistent());
  document.View()->UpdateAllLifecyclePhasesForTest();
  EXPECT_EQ(controls->GetLayoutObject(), nullptr);
  EXPECT_NE(Media()->GetLayoutObject(), nullptr);
  auto* late_controls = document.CreateRawElement(html_names::kDivTag);
  wrapper->AppendChild(late_controls);
  document.View()->UpdateAllLifecyclePhasesForTest();
  EXPECT_EQ(late_controls->GetLayoutObject(), nullptr);
  presentation->Close();
  presentation->Close();
  document.View()->UpdateAllLifecyclePhasesForTest();
  EXPECT_NE(controls->GetLayoutObject(), nullptr);
  EXPECT_NE(late_controls->GetLayoutObject(), nullptr);
  EXPECT_FALSE(root->ContainsPersistentVideo());
  EXPECT_FALSE(wrapper->ContainsPersistentVideo());
  EXPECT_FALSE(Media()->ContainsPersistentVideo());
  SimulateExitFullscreen(root);
}

TEST_P(HTMLMediaElementTest, UpgridPresentationRejectsNoFullscreenAndOtherOwner) {
  if (GetParam() != MediaTestParam::kVideo)
    return;
  Document& document = Media()->GetDocument();
  document.GetSettings()->SetScriptEnabled(true);
  auto* root = document.CreateRawElement(html_names::kDivTag);
  document.body()->AppendChild(root);
  root->AppendChild(Media());
  auto* video = To<HTMLVideoElement>(Media());
  EXPECT_EQ(UpgridFullscreenPresentation::Create(*video, *root), nullptr);
  SimulateEnterFullscreen(root);
  root->SetContainsPersistentVideo(true);
  EXPECT_EQ(UpgridFullscreenPresentation::Create(*video, *root), nullptr);
  EXPECT_FALSE(video->ContainsPersistentVideo());
  EXPECT_TRUE(root->ContainsPersistentVideo());
  root->SetContainsPersistentVideo(false);
  SimulateExitFullscreen(root);
}

TEST_P(HTMLMediaElementTest, UpgridPresentationCleansOldShadowPathAfterMove) {
  if (GetParam() != MediaTestParam::kVideo)
    return;
  Document& document = Media()->GetDocument();
  document.GetSettings()->SetScriptEnabled(true);
  auto* root = document.CreateRawElement(html_names::kDivTag);
  auto* host = document.CreateRawElement(html_names::kDivTag);
  document.body()->AppendChild(root);
  root->AppendChild(host);
  auto& shadow = host->AttachShadowRootForTesting(ShadowRootMode::kClosed);
  shadow.AppendChild(Media());
  auto* video = To<HTMLVideoElement>(Media());
  SimulateEnterFullscreen(root);
  Persistent<UpgridFullscreenPresentation> presentation =
      UpgridFullscreenPresentation::Create(*video, *root);
  ASSERT_NE(presentation, nullptr);
  EXPECT_TRUE(presentation->IsCurrent(*video, *root));
  root->AppendChild(Media());
  EXPECT_FALSE(presentation->IsCurrent(*video, *root));
  presentation->Close();
  EXPECT_FALSE(host->ContainsPersistentVideo());
  EXPECT_FALSE(root->ContainsPersistentVideo());
  EXPECT_FALSE(video->ContainsPersistentVideo());
  SimulateExitFullscreen(root);
}
