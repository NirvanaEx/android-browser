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

TEST_P(HTMLMediaElementTest, UpgridContainerFullscreenKeepsSiteControls) {
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
  EXPECT_TRUE(ControlsVisible());
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

TEST_P(HTMLMediaElementTest, UpgridShadowHostFullscreenIsNotVideoFullscreen) {
  if (GetParam() != MediaTestParam::kVideo)
    return;
  Document& document = Media()->GetDocument();
  document.GetSettings()->SetScriptEnabled(true);
  auto* host = document.CreateRawElement(html_names::kDivTag);
  document.body()->AppendChild(host);
  auto& shadow = host->AttachShadowRootForTesting(ShadowRootMode::kClosed);
  shadow.AppendChild(Media());
  Media()->SetBooleanAttribute(html_names::kControlsAttr, true);
  Media()->SetUpgridControlsHidden(true);
  SimulateEnterFullscreen(host);
  EXPECT_TRUE(ControlsVisible());
  SimulateExitFullscreen(host);
  SimulateEnterFullscreen(Media());
  EXPECT_FALSE(ControlsVisible());
  SimulateExitFullscreen(Media());
  EXPECT_TRUE(ControlsVisible());
}
