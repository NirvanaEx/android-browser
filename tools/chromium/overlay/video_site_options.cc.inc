void LocalFrameMojoHandler::UpgridSiteOptions(
    uint64_t token,
    const String& command,
    const String& option,
    UpgridSiteOptionsCallback callback) {
  const String failure = "{\"ok\":false,\"error\":\"unsupported\"}";
  if (!IsUpgridVideoCurrent(token) || !upgrid_open_requested_ ||
      !IsUpgridVideoFullscreen() ||
      command.length() > 16 || option.length() > 128) {
    std::move(callback).Run(failure);
    return;
  }
  if (command == "mirror") {
    if (option != "off" && option != "horizontal" && option != "vertical" &&
        option != "both") {
      std::move(callback).Run(failure);
      return;
    }
    auto& video = *upgrid_video_;
    if (!upgrid_mirror_changed_) {
      const auto* style = video.InlineStyle();
      upgrid_original_mirror_scale_ =
          style ? style->GetPropertyValue(CSSPropertyID::kScale) : String();
      upgrid_original_mirror_scale_important_ =
          style && style->PropertyIsImportant(CSSPropertyID::kScale);
      upgrid_mirror_changed_ = true;
    }
    // The independent scale property leaves website transforms and all parent
    // elements intact. The original inline value is restored on release.
    upgrid_applied_mirror_scale_ = option == "both" ? "-1 -1"
        : option == "horizontal" ? "-1 1" : option == "vertical" ? "1 -1" : "1 1";
    video.SetInlineStyleProperty(CSSPropertyID::kScale,
                                upgrid_applied_mirror_scale_, true);
    std::move(callback).Run("{\"ok\":true}");
    return;
  }
  // Only this selected video's YouTube document can use the site adapter.
  const String host = frame_->GetDocument()->Url().Host().ToString();
  if (!(host == "youtube.com" || host.EndsWith(".youtube.com") ||
        host == "youtube-nocookie.com" ||
        host.EndsWith(".youtube-nocookie.com")) ||
      !frame_->DomWindow()->CanExecuteScripts(kNotAboutToExecuteScript)) {
    std::move(callback).Run(failure);
    return;
  }
  ScriptState* script_state = ToScriptStateForMainWorld(frame_);
  if (!script_state || !script_state->ContextIsValid()) {
    std::move(callback).Run(failure);
    return;
  }
  v8::Isolate* isolate = script_state->GetIsolate();
  v8::HandleScope handles(isolate);
  ScriptState::Scope scope(script_state);
  v8::TryCatch exceptions(isolate);
  const String source = R"UPGRIDJS(
@@UPGRID_YOUTUBE_OPTIONS@@
)UPGRIDJS";
  auto evaluation = ClassicScript::CreateUnspecifiedScript(source)
                        ->RunScriptOnScriptStateAndReturnValue(script_state);
  if (evaluation.GetResultType() !=
      ScriptEvaluationResult::ResultType::kSuccess) {
    std::move(callback).Run(failure);
    return;
  }
  v8::Local<v8::Value> function = evaluation.GetSuccessValue();
  if (!function->IsFunction() || !IsUpgridVideoCurrent(token)) {
    std::move(callback).Run(failure);
    return;
  }
  v8::Local<v8::Value> arguments[] = {
      ToV8Traits<Element>::ToV8(script_state, upgrid_video_.Get()),
      V8String(isolate, command), V8String(isolate, option)};
  v8::Local<v8::Value> result;
  if (!V8ScriptRunner::CallFunction(function.As<v8::Function>(),
                                    frame_->DomWindow(), v8::Undefined(isolate),
                                    3, arguments, isolate)
           .ToLocal(&result) ||
      !IsUpgridVideoCurrent(token) || !result->IsString() ||
      result.As<v8::String>()->Length() > 16384) {
    std::move(callback).Run(failure);
    return;
  }
  std::move(callback).Run(ToCoreString(isolate, result.As<v8::String>()));
}
